/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet.lower

import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.backend.common.lower.AbstractFunctionReferenceLowering
import org.jetbrains.kotlin.backend.common.lower.LocalDeclarationsLowering
import org.jetbrains.kotlin.backend.common.lower.UpgradeCallableReferences
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.backend.dotnet.DotNetBackendContext
import org.jetbrains.kotlin.backend.dotnet.dotNetFixedFunctionArityOrNull
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.declarations.addGetter
import org.jetbrains.kotlin.ir.builders.declarations.addProperty
import org.jetbrains.kotlin.ir.builders.declarations.buildField
import org.jetbrains.kotlin.ir.builders.irDelegatingConstructorCall
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOriginImpl
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.createExpressionBody
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrDelegatingConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrRichFunctionReference
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.impl.IrConstructorCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetFieldImpl
import org.jetbrains.kotlin.ir.expressions.impl.fromSymbolOwner
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrFail
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.typeWithArguments
import org.jetbrains.kotlin.ir.util.createDispatchReceiverParameterWithClassParent
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.isKFunction
import org.jetbrains.kotlin.ir.util.isKSuspendFunction
import org.jetbrains.kotlin.ir.util.isLambda
import org.jetbrains.kotlin.ir.util.invokeFun
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.util.properties
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.Name

internal val DOTNET_LAMBDA_IMPL: IrDeclarationOrigin = IrDeclarationOriginImpl("DOTNET_LAMBDA_IMPL")
internal val DOTNET_FUNCTION_REFERENCE_IMPL: IrDeclarationOrigin =
    IrDeclarationOriginImpl("DOTNET_FUNCTION_REFERENCE_IMPL")
private val DOTNET_CALLABLE_CONSTRUCTOR: IrDeclarationOrigin =
    IrDeclarationOriginImpl("DOTNET_CALLABLE_CONSTRUCTOR")

internal val IrClass.isDotNetCallableObject: Boolean
    get() = origin == DOTNET_LAMBDA_IMPL || origin == DOTNET_FUNCTION_REFERENCE_IMPL

/** Builds rich callable IR while deliberately leaving SAM conversions outside this slice. */
internal class DotNetUpgradeCallableReferences(context: DotNetBackendContext) :
    UpgradeCallableReferences(context, upgradeSamConversions = false)

/**
 * Turns a rich callable into the same local-class shape used by the common JS lowering: an Any
 * subclass implementing its fixed-arity function interface with one invoke override. Direct
 * references additionally implement the orthogonal non-generic KFunction reflection view. The
 * CLR representation is Kotlin-owned; `System.Func`/`System.Action` adapters belong at future
 * interop boundaries.
 */
internal class DotNetCallableReferenceLowering(context: DotNetBackendContext) :
    AbstractFunctionReferenceLowering<DotNetBackendContext>(context) {

    override fun getReferenceClassName(reference: IrRichFunctionReference): Name =
        Name.identifier(if (reference.origin.isLambda) "lambda" else "functionReference")

    override fun getSuperClassType(reference: IrRichFunctionReference): IrType = context.irBuiltIns.anyType

    override fun IrBuilderWithScope.generateSuperClassConstructorCall(
        constructor: IrConstructor,
        superClassType: IrType,
        functionReference: IrRichFunctionReference,
    ): IrDelegatingConstructorCall = irDelegatingConstructorCall(
        superClassType.classOrFail.owner.primaryConstructor
            ?: error("Internal .NET backend error: kotlin.Any has no primary constructor")
    )

    override fun getClassOrigin(reference: IrRichFunctionReference): IrDeclarationOrigin =
        if (reference.origin.isLambda) DOTNET_LAMBDA_IMPL else DOTNET_FUNCTION_REFERENCE_IMPL

    override fun getConstructorOrigin(reference: IrRichFunctionReference): IrDeclarationOrigin =
        DOTNET_CALLABLE_CONSTRUCTOR

    override fun getInvokeMethodOrigin(reference: IrRichFunctionReference): IrDeclarationOrigin =
        IrDeclarationOrigin.DEFINED

    override fun getConstructorCallOrigin(reference: IrRichFunctionReference): IrStatementOrigin? = null

    override fun getAdditionalInterfaces(reference: IrRichFunctionReference): List<IrType> {
        val reflectiveType = reference.type.takeIf { it.isKFunction() && !it.isKSuspendFunction() } as? IrSimpleType
            ?: return emptyList()
        val arity = reflectiveType.arguments.size - 1
        if (arity !in 0..2) return emptyList()
        return listOf(context.irBuiltIns.functionN(arity).symbol.typeWithArguments(reflectiveType.arguments))
    }

    override fun postprocessInvoke(invokeFunction: IrSimpleFunction, functionReference: IrRichFunctionReference) {
        val reflectiveType = functionReference.type
            .takeIf { it.isKFunction() && !it.isKSuspendFunction() } as? IrSimpleType
            ?: return
        val arity = reflectiveType.arguments.size - 1
        if (arity !in 0..2) return
        val executionInvoke = context.irBuiltIns.functionN(arity).invokeFun
            ?: error("Internal .NET backend error: kotlin.Function$arity has no invoke member")
        if (executionInvoke.symbol !in invokeFunction.overriddenSymbols) {
            invokeFunction.overriddenSymbols += executionInvoke.symbol
        }
    }

    override fun generateExtraMethods(functionReferenceClass: IrClass, reference: IrRichFunctionReference) {
        if (!reference.type.isKFunction() || reference.type.isKSuspendFunction()) return
        val reflectionTarget = reference.reflectionTargetSymbol?.owner ?: return
        val superProperty = context.irBuiltIns.kCallableClass.owner.properties
            .single { it.name.asString() == "name" }
        val superGetter = superProperty.getter
            ?: error("Internal .NET backend error: kotlin.reflect.KCallable.name has no getter")

        val nameProperty = functionReferenceClass.addProperty {
            startOffset = reference.startOffset
            endOffset = reference.endOffset
            origin = IrDeclarationOrigin.DEFINED
            name = superProperty.name
            visibility = superProperty.visibility
        }.apply {
            overriddenSymbols = listOf(superProperty.symbol)
        }
        nameProperty.addGetter {
            startOffset = reference.startOffset
            endOffset = reference.endOffset
            origin = IrDeclarationOrigin.DEFINED
            returnType = context.irBuiltIns.stringType
            visibility = superGetter.visibility
        }.apply {
            overriddenSymbols = listOf(superGetter.symbol)
            parameters += createDispatchReceiverParameterWithClassParent()
            body = context.createIrBuilder(symbol).irBlockBody {
                +irReturn(irString(reflectionTarget.metadata?.name?.asString() ?: reflectionTarget.name.asString()))
            }
        }
    }

    override fun postprocessClass(functionReferenceClass: IrClass, functionReference: IrRichFunctionReference) {
        functionReferenceClass.dotNetInventedLocalClassName = functionReference.dotNetInventedLocalClassName
        if (functionReference.type.isKFunction() && !functionReference.type.isKSuspendFunction()) {
            // AbstractFunctionReferenceLowering adds fake overrides after generateExtraMethods.
            // Its KFunctionN view does not recognize the concrete KCallable.name property above
            // as satisfying the inherited property, so discard only that redundant fake pair.
            // The concrete getter remains the single CLR implementation of KCallable.get_name.
            val name = context.irBuiltIns.kCallableClass.owner.properties
                .single { it.name.asString() == "name" }
                .name
            functionReferenceClass.declarations.removeAll { declaration ->
                declaration.origin == IrDeclarationOrigin.FAKE_OVERRIDE && when (declaration) {
                    is IrProperty -> declaration.name == name
                    is IrSimpleFunction -> declaration.correspondingPropertySymbol?.owner?.name == name
                    else -> false
                }
            }
        }
        // Common function-reference lowering creates bound-value fields without a specialized
        // origin. Mark them as ordinary captured-value fields so the strict CLR class renderer
        // accepts exactly the same private state shape that closure conversion produces.
        functionReferenceClass.declarations.filterIsInstance<IrField>().forEach { field ->
            if (field.correspondingPropertySymbol == null && field.origin == IrDeclarationOrigin.DEFINED) {
                field.origin = LocalDeclarationsLowering.DECLARATION_ORIGIN_FIELD_FOR_CAPTURED_VALUE
            }
        }
        functionReferenceClass.dotNetLocalCaptureRejectionReason = when {
            functionReference.invokeFunction.isSuspend ->
                "is suspend; the suspend-callable ABI is deliberately reserved"
            functionReference.type.isKSuspendFunction() ->
                "requires suspend-callable reflection metadata, which is deliberately outside the callable ABI slice"
            functionReferenceClass.superTypes.none { superType ->
                superType.classOrNull?.owner?.dotNetFixedFunctionArityOrNull() != null
            } ->
                "does not use a supported fixed Function0, Function1, or Function2 interface"
            else -> null
        }
    }
}

/**
 * Caches every non-capturing callable expression in a class-local static field, following the
 * JVM StaticCallableReferenceLowering identity rule. The later static-initializer sweep moves
 * the initializer into this synthetic class's `.cctor`.
 */
internal class DotNetStaticCallableReferenceLowering(private val context: DotNetBackendContext) :
    IrElementTransformerVoid(), FileLoweringPass {

    private val fields = mutableMapOf<IrClass, IrField>()

    override fun lower(irFile: IrFile) {
        irFile.transformChildrenVoid(this)
    }

    override fun visitClass(declaration: IrClass): IrStatement {
        val field = declaration.takeIf(::isCacheable)?.let(::fieldFor)
        declaration.transformChildrenVoid(this)
        if (field != null && field !in declaration.declarations) {
            declaration.declarations.add(0, field)
        }
        return declaration
    }

    override fun visitConstructorCall(expression: IrConstructorCall): IrExpression {
        val constructedClass = expression.symbol.owner.parent as? IrClass
            ?: return super.visitConstructorCall(expression)
        if (!isCacheable(constructedClass) || expression.symbol.owner != constructedClass.primaryConstructor) {
            return super.visitConstructorCall(expression)
        }
        val field = fieldFor(constructedClass)
        return IrGetFieldImpl(expression.startOffset, expression.endOffset, field.symbol, expression.type)
    }

    private fun isCacheable(irClass: IrClass): Boolean =
        irClass.isDotNetCallableObject &&
                irClass.dotNetLocalCaptureRejectionReason == null &&
                irClass.primaryConstructor?.parameters?.isEmpty() == true

    private fun fieldFor(irClass: IrClass): IrField = fields.getOrPut(irClass) {
        val constructor = irClass.primaryConstructor
            ?: error("Internal .NET backend error: callable class '${irClass.name}' has no primary constructor")
        context.irFactory.buildField {
            name = Name.identifier("INSTANCE")
            type = irClass.defaultType
            origin = IrDeclarationOrigin.FIELD_FOR_OBJECT_INSTANCE
            visibility = DescriptorVisibilities.PUBLIC
            isFinal = true
            isStatic = true
        }.apply {
            parent = irClass
            initializer = context.irFactory.createExpressionBody(
                IrConstructorCallImpl.fromSymbolOwner(
                    startOffset,
                    endOffset,
                    irClass.defaultType,
                    constructor.symbol,
                    classTypeParametersCount = 0,
                )
            )
        }
    }
}
