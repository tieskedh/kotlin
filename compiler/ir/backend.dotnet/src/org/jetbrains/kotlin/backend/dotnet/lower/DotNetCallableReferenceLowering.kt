/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet.lower

import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.backend.common.lower.AbstractFunctionReferenceLowering
import org.jetbrains.kotlin.backend.common.lower.UpgradeCallableReferences
import org.jetbrains.kotlin.backend.dotnet.DotNetBackendContext
import org.jetbrains.kotlin.backend.dotnet.dotNetFixedFunctionArityOrNull
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.declarations.buildField
import org.jetbrains.kotlin.ir.builders.irDelegatingConstructorCall
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOriginImpl
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFile
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
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.isKFunction
import org.jetbrains.kotlin.ir.util.isKSuspendFunction
import org.jetbrains.kotlin.ir.util.isLambda
import org.jetbrains.kotlin.ir.util.primaryConstructor
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
 * subclass implementing its fixed-arity function interface with one invoke override. The CLR
 * representation is Kotlin-owned; `System.Func`/`System.Action` adapters belong at future
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

    override fun postprocessClass(functionReferenceClass: IrClass, functionReference: IrRichFunctionReference) {
        functionReferenceClass.dotNetInventedLocalClassName = functionReference.dotNetInventedLocalClassName
        if (
            functionReference.type.isKFunction() &&
            !functionReference.type.isKSuspendFunction()
        ) {
            // FIR retains KFunctionN as the expression type even when the reference is consumed
            // through an explicitly declared FunctionN slot. Reflection metadata is a later
            // slice, so make the generated class implement only its invokable FunctionN view.
            // A value that is actually stored/passed as KFunctionN still fails in type mapping;
            // this does not silently claim that the reflective ABI exists.
            val reflectiveType = functionReference.type as? IrSimpleType
                ?: error("Internal .NET backend error: KFunction reference has a non-simple type")
            val arity = reflectiveType.arguments.size - 1
            val functionType = context.irBuiltIns.functionN(arity).symbol.typeWithArguments(reflectiveType.arguments)
            functionReferenceClass.superTypes = functionReferenceClass.superTypes.map { superType ->
                if (superType.isKFunction()) functionType else superType
            }
            functionReferenceClass.declarations.removeAll { declaration ->
                declaration.origin == IrDeclarationOrigin.FAKE_OVERRIDE
            }
        }
        functionReferenceClass.dotNetLocalCaptureRejectionReason = when {
            functionReference.boundValues.isNotEmpty() ->
                "has a bound receiver or captured value; capturing callable objects are not supported yet"
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
