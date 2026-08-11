/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet.lower

import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.backend.dotnet.DotNetBackendContext
import org.jetbrains.kotlin.backend.dotnet.DotNetKClassRuntime
import org.jetbrains.kotlin.backend.dotnet.DotNetStdlibLibrary
import org.jetbrains.kotlin.backend.dotnet.isDotNetResolutionOnlyStdlibDeclaration
import org.jetbrains.kotlin.config.dotNetMemberReflection
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.IrStatementOriginImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrFunctionReferenceImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrPropertyReferenceImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrVarargImpl
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.starProjectedType
import org.jetbrains.kotlin.ir.types.typeWithArguments
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.ir.util.eraseTypeParameters
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.getKFunctionType
import org.jetbrains.kotlin.ir.util.isAnonymousObject
import org.jetbrains.kotlin.ir.util.isOriginallyLocalDeclaration
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.name.Name

/** Distinguishes producer-owned KClass member values from ordinary user callable references. */
internal val DOTNET_REFLECTED_MEMBER_REFERENCE: IrStatementOrigin =
    IrStatementOriginImpl("DOTNET_REFLECTED_MEMBER_REFERENCE")

/**
 * Emits producer-owned executable member metadata after KLIB serialization and before ordinary
 * callable-reference lowering. The private factory contains only ordinary unbound references;
 * every signature, annotation, accessor, invocation and equality rule is therefore supplied by
 * the already selected callable/property pipeline rather than inferred from physical CLR rows.
 */
internal class DotNetMemberReflectionLowering(
    private val context: DotNetBackendContext,
) : FileLoweringPass {

    override fun lower(irFile: IrFile) {
        if (!context.configuration.dotNetMemberReflection) return
        // The first optional-product closure is deliberately disjoint from Stdlib/mapped
        // classifiers. Several compiler-only Stdlib declarations do not yet have complete direct
        // callable-reference execution shapes; emitting a partial member set would be a lie, and
        // making every ordinary build depend on those shapes would violate reflection optionality.
        if (DotNetStdlibLibrary.hasImplementation(irFile)) return

        val classes = mutableListOf<IrClass>()
        irFile.acceptVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitClass(declaration: IrClass) {
                classes += declaration
                declaration.acceptChildrenVoid(this)
            }
        })

        classes.forEach { irClass -> irClass.addMemberFactoryOrSkip() }
    }

    private fun IrClass.addMemberFactoryOrSkip() {
        if (isDotNetResolutionOnlyStdlibDeclaration ||
            visibility == DescriptorVisibilities.LOCAL ||
            isAnonymousObject ||
            isOriginallyLocalDeclaration ||
            fqNameWhenAvailable == null
        ) {
            return
        }

        val logicalMembers = declarations.filter(::isLogicalMemberDeclaration)
        val references = logicalMembers.map { declaration ->
            when (declaration) {
                is IrSimpleFunction -> functionReference(declaration)
                is IrProperty -> propertyReferenceOrNull(declaration) ?: return
                else -> error("Unexpected member declaration: $declaration")
            }
        }

        val owner = this
        val holder = context.irFactory.buildClass {
            startOffset = owner.startOffset
            endOffset = owner.endOffset
            origin = DOTNET_STATIC_HOLDER
            name = Name.special(DotNetKClassRuntime.MEMBER_FACTORY_HOLDER_NAME)
            kind = ClassKind.CLASS
            modality = Modality.FINAL
            visibility = DescriptorVisibilities.PRIVATE
        }.apply {
            parent = owner
            superTypes = listOf(context.irBuiltIns.anyType)
            createThisReceiverParameter()
        }

        val callableType = context.irBuiltIns.kCallableClass.starProjectedType
        val arrayType = context.irBuiltIns.arrayClass.typeWithArguments(listOf(callableType))
        val factory = context.irFactory.buildFun {
            startOffset = owner.startOffset
            endOffset = owner.endOffset
            origin = IrDeclarationOrigin.DEFINED
            name = Name.special(DotNetKClassRuntime.MEMBER_FACTORY_METHOD_NAME)
            returnType = arrayType
            visibility = DescriptorVisibilities.PRIVATE
        }.apply {
            parent = holder
            body = context.createIrBuilder(symbol).irBlockBody {
                +irReturn(
                    IrVarargImpl(
                        UNDEFINED_OFFSET,
                        UNDEFINED_OFFSET,
                        arrayType,
                        callableType,
                        references,
                    )
                )
            }
        }
        holder.declarations += factory
        declarations += holder
    }

    private fun IrClass.functionReference(function: IrSimpleFunction): IrExpression {
        val parameterTypes = function.parameters.map { parameter ->
            if (parameter.kind == IrParameterKind.DispatchReceiver) symbol.starProjectedType
            else parameter.type.eraseTypeParameters()
        }
        val returnType = function.returnType.eraseTypeParameters()
        val referenceType = if (function.isSuspend) {
            context.irBuiltIns.kSuspendFunctionN(parameterTypes.size).symbol
                .typeWithArguments(parameterTypes + returnType)
        } else {
            context.irBuiltIns.getKFunctionType(returnType, parameterTypes)
        }
        return IrFunctionReferenceImpl(
            function.startOffset,
            function.endOffset,
            referenceType,
            function.symbol,
            typeArgumentsCount = function.typeParameters.size,
            reflectionTarget = function.symbol,
        ).apply {
            origin = DOTNET_REFLECTED_MEMBER_REFERENCE
            function.typeParameters.forEachIndexed { index, typeParameter ->
                typeArguments[index] = typeParameter.defaultType.eraseTypeParameters()
            }
        }
    }

    private fun IrClass.propertyReferenceOrNull(property: IrProperty): IrExpression? {
        val getter = property.getter ?: return null
        val parameterTypes = getter.parameters.map { parameter ->
            if (parameter.kind == IrParameterKind.DispatchReceiver) symbol.starProjectedType
            else parameter.type.eraseTypeParameters()
        }
        if (parameterTypes.size !in 0..2) return null
        val returnType = getter.returnType.eraseTypeParameters()
        val referenceType = context.irBuiltIns
            .getKPropertyClass(mutable = property.setter != null, n = parameterTypes.size)
            .typeWithArguments(parameterTypes + returnType)
        return IrPropertyReferenceImpl(
            property.startOffset,
            property.endOffset,
            referenceType,
            property.symbol,
            typeArgumentsCount = getter.typeParameters.size,
            field = property.backingField?.symbol,
            getter = getter.symbol,
            setter = property.setter?.symbol,
        ).apply {
            origin = DOTNET_REFLECTED_MEMBER_REFERENCE
            getter.typeParameters.forEachIndexed { index, typeParameter ->
                typeArguments[index] = typeParameter.defaultType.eraseTypeParameters()
            }
        }
    }

    private fun isLogicalMemberDeclaration(declaration: IrDeclaration): Boolean = when (declaration) {
        is IrProperty -> true
        is IrSimpleFunction -> declaration.correspondingPropertySymbol == null
        else -> false
    }
}
