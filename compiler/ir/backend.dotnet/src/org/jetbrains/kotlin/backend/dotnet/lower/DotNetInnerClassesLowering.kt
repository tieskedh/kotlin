/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet.lower

import org.jetbrains.kotlin.backend.common.ModuleLoweringPass
import org.jetbrains.kotlin.backend.common.lower.InnerClassConstructorCallsLowering
import org.jetbrains.kotlin.backend.common.lower.InnerClassesLowering
import org.jetbrains.kotlin.backend.common.lower.InnerClassesMemberBodyLowering
import org.jetbrains.kotlin.backend.common.lower.InnerClassesSupport
import org.jetbrains.kotlin.backend.dotnet.DotNetBackendContext
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.builders.declarations.buildConstructor
import org.jetbrains.kotlin.ir.builders.declarations.buildField
import org.jetbrains.kotlin.ir.builders.declarations.buildValueParameter
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrFactory
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrTypeParameter
import org.jetbrains.kotlin.ir.declarations.copyAttributes
import org.jetbrains.kotlin.ir.irAttribute
import org.jetbrains.kotlin.ir.expressions.IrBody
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrDelegatingConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetField
import org.jetbrains.kotlin.ir.expressions.impl.IrConstructorCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrDelegatingConstructorCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.fromSymbolOwner
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.util.IrTypeParameterRemapper
import org.jetbrains.kotlin.ir.util.copyAnnotationsFrom
import org.jetbrains.kotlin.ir.util.copyTo
import org.jetbrains.kotlin.ir.util.copyTypeParameters
import org.jetbrains.kotlin.ir.util.copyTypeParametersFrom
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.dump
import org.jetbrains.kotlin.ir.util.nonDispatchParameters
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.remapTypes
import org.jetbrains.kotlin.ir.util.substitute
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.utils.addToStdlib.assignFrom
import org.jetbrains.kotlin.utils.addToStdlib.getOrSetIfNull

private var IrClass.dotNetOuterThisField: IrField? by irAttribute(copyByDefault = false)
private var IrConstructor.dotNetConstructorWithOuterThis: IrConstructor? by irAttribute(copyByDefault = false)
private var IrClass.dotNetOriginalInnerPrimaryConstructor: IrConstructor? by irAttribute(copyByDefault = false)
private var IrClass.dotNetOuterTypeParameterCopies: Map<IrTypeParameter, IrTypeParameter>?
    by irAttribute(copyByDefault = false)

private fun IrClass.dotNetOuterThisType(): IrType {
    val outerType = parentAsClass.defaultType
    val typeParameterCopies = dotNetOuterTypeParameterCopies ?: return outerType
    return IrTypeParameterRemapper(typeParameterCopies).remapType(outerType)
}

/**
 * The JVM/common inner-class representation, reused directly for CLR nested metadata: every inner
 * class owns a private `this$0` field, and every constructor replaces its dispatch receiver with a
 * regular leading outer-instance parameter. [InnerClassesMemberBodyLowering] then rewrites outer
 * `this` reads into field chains, while [InnerClassConstructorCallsLowering] moves the source call's
 * dispatch receiver into that leading argument.
 *
 * CLR-specific probe result (`innerprobe_s1`/`_s2`): both CoreCLR 10.0.9 and Framework 4.8 permit
 * the common lowering's `stfld this$0` before the base `.ctor` call. No CLR-specific constructor
 * reordering is needed. [DotNetInnerClassTypeParametersLowering] first gives an inner class below
 * a generic outer explicit copies of every immediate-outer type parameter, because a CLR nested
 * type does not inherit its metadata parent's `!n` parameter space.
 */
internal class DotNetInnerClassesSupport(private val irFactory: IrFactory) : InnerClassesSupport {
    override fun getOuterThisField(innerClass: IrClass): IrField =
        innerClass::dotNetOuterThisField.getOrSetIfNull {
            check(innerClass.isInner) { "Class is not inner: ${innerClass.dump()}" }
            irFactory.buildField {
                name = Name.identifier("this$0")
                type = innerClass.dotNetOuterThisType()
                origin = IrDeclarationOrigin.FIELD_FOR_OUTER_THIS
                visibility = DescriptorVisibilities.PRIVATE
                isFinal = true
            }.apply {
                parent = innerClass
            }
        }

    override fun getInnerClassConstructorWithOuterThisParameter(innerClassConstructor: IrConstructor): IrConstructor {
        val innerClass = innerClassConstructor.parentAsClass
        check(innerClass.isInner) { "Class is not inner: ${innerClass.dump()}" }
        return innerClassConstructor::dotNetConstructorWithOuterThis.getOrSetIfNull {
            createConstructorWithOuterThis(innerClassConstructor)
        }.also {
            if (innerClassConstructor.isPrimary) {
                innerClass.dotNetOriginalInnerPrimaryConstructor = innerClassConstructor
            }
        }
    }

    override fun getInnerClassOriginalPrimaryConstructorOrNull(innerClass: IrClass): IrConstructor? {
        check(innerClass.isInner) { "Class is not inner: ${innerClass.dump()}" }
        return innerClass.dotNetOriginalInnerPrimaryConstructor
    }

    private fun createConstructorWithOuterThis(oldConstructor: IrConstructor): IrConstructor =
        irFactory.buildConstructor {
            updateFrom(oldConstructor)
            returnType = oldConstructor.returnType
        }.apply {
            parent = oldConstructor.parent
            returnType = oldConstructor.returnType
            copyAnnotationsFrom(oldConstructor)
            copyTypeParametersFrom(oldConstructor)
            val outerThisParameter = buildValueParameter(this) {
                kind = IrParameterKind.Regular
                origin = IrDeclarationOrigin.FIELD_FOR_OUTER_THIS
                name = Name.identifier("this$0")
                type = oldConstructor.parentAsClass.dotNetOuterThisType()
            }
            parameters = listOf(outerThisParameter) + oldConstructor.nonDispatchParameters.map { it.copyTo(this) }
            metadata = oldConstructor.metadata
        }
}

/**
 * Makes Kotlin's implicit generic-outer arguments explicit on CLR inner metadata.
 *
 * FIR types already spell an inner instantiation as `Inner<own, outer...>` even though the IR
 * declaration initially owns only `own`. Appending copies of the immediate outer's full parameter
 * list therefore makes declaration and use-site arities agree without rewriting call sites. The
 * pass runs outer-first: at the next nesting level the immediate outer already owns its inherited
 * copies, so `Second<V>` below `Outer<T>.First<U>` becomes `Second<V, U, T>`.
 *
 * All types inside the inner subtree are remapped from the outer parameters to the new independent
 * slots before the common inner-class passes create `this$0`. The positional model, generic base
 * links, duplicate parameter names, and multi-level ordering are probe-verified by
 * `genericinner_s1`–`_s3` on CoreCLR and Framework ILAsm/runtimes.
 */
internal class DotNetInnerClassTypeParametersLowering(
    @Suppress("UNUSED_PARAMETER") context: DotNetBackendContext,
) : ModuleLoweringPass {
    override fun lower(irModule: IrModuleFragment) {
        for (irFile in irModule.files) {
            irFile.declarations.filterIsInstance<IrClass>().forEach(::lowerClassTree)
        }
    }

    private fun lowerClassTree(irClass: IrClass) {
        if (irClass.isInner) {
            val outerClass = irClass.parentAsClass
            if (outerClass.typeParameters.isNotEmpty()) {
                val copiedOuterTypeParameters = irClass.copyTypeParameters(outerClass.typeParameters)
                val outerToInner = outerClass.typeParameters.zip(copiedOuterTypeParameters).toMap()
                irClass.dotNetOuterTypeParameterCopies = outerToInner
                irClass.remapTypes(IrTypeParameterRemapper(outerToInner))
            }
        }
        irClass.declarations.filterIsInstance<IrClass>().forEach(::lowerClassTree)
    }
}

internal class DotNetInnerClassesLowering(context: DotNetBackendContext) : InnerClassesLowering(context)

internal class DotNetInnerClassesMemberBodyLowering(context: DotNetBackendContext) :
    InnerClassesMemberBodyLowering(context) {
    override fun lower(irBody: IrBody, container: IrDeclaration) {
        super.lower(irBody, container)
        irBody.transformChildrenVoid(object : IrElementTransformerVoid() {
            override fun visitGetField(expression: IrGetField): IrExpression {
                expression.transformChildrenVoid(this)
                val field = expression.symbol.owner
                if (field.origin != IrDeclarationOrigin.FIELD_FOR_OUTER_THIS) return expression
                val owner = field.parentAsClass
                val receiverType = expression.receiver?.type as? IrSimpleType ?: return expression
                if (receiverType.classOrNull?.owner !== owner ||
                    receiverType.arguments.size != owner.typeParameters.size
                ) {
                    return expression
                }
                val receiverArguments = receiverType.arguments.map {
                    (it as? IrTypeProjection)?.type ?: return expression
                }
                val substitution = owner.typeParameters.map { it.symbol }
                    .zip(receiverArguments)
                    .toMap()
                expression.type = field.type.substitute(substitution)
                return expression
            }
        })
    }
}

internal class DotNetInnerClassConstructorCallsLowering(context: DotNetBackendContext) :
    InnerClassConstructorCallsLowering(context) {
    override fun lower(irBody: IrBody, container: IrDeclaration) {
        irBody.transformChildrenVoid(object : IrElementTransformerVoid() {
            override fun visitConstructorCall(expression: IrConstructorCall): IrExpression {
                expression.transformChildrenVoid(this)
                val targetClass = expression.symbol.owner.parentAsClass
                if (targetClass.dotNetOuterTypeParameterCopies == null ||
                    expression.typeArguments.size == targetClass.typeParameters.size
                ) {
                    return expression
                }
                val instantiation = expression.type.dotNetTypeArgumentsFor(targetClass) ?: return expression
                return IrConstructorCallImpl.fromSymbolOwner(
                    expression.startOffset,
                    expression.endOffset,
                    expression.type,
                    expression.symbol,
                    targetClass.typeParameters.size,
                    expression.origin,
                ).also { replacement ->
                    instantiation.forEachIndexed { index, type -> replacement.typeArguments[index] = type }
                    replacement.arguments.assignFrom(expression.arguments)
                    replacement.copyAttributes(expression)
                }
            }

            override fun visitDelegatingConstructorCall(expression: IrDelegatingConstructorCall): IrExpression {
                expression.transformChildrenVoid(this)
                val targetClass = expression.symbol.owner.parentAsClass
                if (targetClass.dotNetOuterTypeParameterCopies == null ||
                    expression.typeArguments.size == targetClass.typeParameters.size
                ) {
                    return expression
                }
                val currentClass = (container as? IrConstructor)?.parentAsClass ?: return expression
                val targetType = if (currentClass === targetClass) {
                    currentClass.defaultType
                } else {
                    currentClass.superTypes.firstOrNull { it.classOrNull?.owner === targetClass }
                } ?: return expression
                val instantiation = targetType.dotNetTypeArgumentsFor(targetClass) ?: return expression
                return IrDelegatingConstructorCallImpl(
                    expression.startOffset,
                    expression.endOffset,
                    expression.type,
                    expression.symbol,
                    targetClass.typeParameters.size,
                ).also { replacement ->
                    instantiation.forEachIndexed { index, type -> replacement.typeArguments[index] = type }
                    replacement.arguments.assignFrom(expression.arguments)
                    replacement.copyAttributes(expression)
                }
            }
        })
        super.lower(irBody, container)
    }

    private fun IrType.dotNetTypeArgumentsFor(targetClass: IrClass): List<IrType>? {
        val simpleType = this as? IrSimpleType ?: return null
        if (simpleType.classOrNull?.owner !== targetClass ||
            simpleType.arguments.size != targetClass.typeParameters.size
        ) {
            return null
        }
        return simpleType.arguments.map { (it as? IrTypeProjection)?.type ?: return null }
    }
}
