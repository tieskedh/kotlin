/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet.lower.coroutines

import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.backend.common.lower.AddFunctionSupertypeToSuspendFunctionLowering
import org.jetbrains.kotlin.backend.common.lower.coroutines.AbstractAddContinuationToFunctionCallsLowering
import org.jetbrains.kotlin.backend.dotnet.DotNetBackendContext
import org.jetbrains.kotlin.builtins.functions.BuiltInFunctionArity
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrRawFunctionReference
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.impl.IrTypeOperatorCallImpl
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.types.typeWithArguments
import org.jetbrains.kotlin.ir.util.getAllSubstitutedSupertypes
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.invokeFun
import org.jetbrains.kotlin.ir.util.isSuspend
import org.jetbrains.kotlin.ir.util.isKSuspendFunction
import org.jetbrains.kotlin.ir.util.isSuspendFunction
import org.jetbrains.kotlin.ir.util.nonDispatchArguments
import org.jetbrains.kotlin.ir.util.overrides
import org.jetbrains.kotlin.ir.util.simpleFunctions
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.util.OperatorNameConventions

/** Rewrites suspend calls after the Common declaration lowering has appended continuations. */
internal class DotNetAddContinuationToFunctionCallsLowering(
    private val context: DotNetBackendContext,
) : FileLoweringPass {
    private val delegate by lazy { DotNetAddContinuationToFunctionCallsLoweringImpl(context) }

    override fun lower(irFile: IrFile) {
        if (irFile.needsContinuationCallLowering()) delegate.lower(irFile)
    }
}

private class DotNetAddContinuationToFunctionCallsLoweringImpl(
    override val context: DotNetBackendContext,
) : AbstractAddContinuationToFunctionCallsLowering() {
    override fun IrSimpleFunction.isContinuationItself(): Boolean =
        overriddenSymbols.any { overridden ->
            overridden.owner.name.asString() == "doResume" &&
                    overridden.owner.parent == context.symbols.coroutineImpl.owner
        }
}

private fun IrFile.needsContinuationCallLowering(): Boolean {
    var needed = false
    acceptChildrenVoid(object : IrVisitorVoid() {
        override fun visitElement(element: org.jetbrains.kotlin.ir.IrElement) {
            if (!needed) element.acceptChildrenVoid(this)
        }

        override fun visitCall(expression: IrCall) {
            val callee = expression.symbol.owner
            if (expression.isSuspend || callee.fqNameWhenAvailable?.asString() == "kotlin.dotnet.internal.getContinuation") {
                needed = true
            } else {
                super.visitCall(expression)
            }
        }

        override fun visitRawFunctionReference(expression: IrRawFunctionReference) {
            if ((expression.symbol.owner as? IrSimpleFunction)?.isSuspend == true) {
                needed = true
            } else {
                super.visitRawFunctionReference(expression)
            }
        }
    })
    return needed
}

/**
 * Preserve only the JVM-compatible direction required to invoke a suspend callable through its
 * continuation-shaped FunctionN view. The reverse relation would classify arbitrary FunctionN
 * implementations as Kotlin suspend functions and is not required by the .NET runtime ABI.
 */
internal class DotNetAddFunctionSupertypeToSuspendFunctionLowering(
    override val context: DotNetBackendContext,
) : AddFunctionSupertypeToSuspendFunctionLowering(context) {
    override fun addMissingSupertypes(clazz: IrClass) {
        val substitutedSupertypes = getAllSubstitutedSupertypes(clazz)
        addFunctionSupertypesToSuspendFunctions(clazz, substitutedSupertypes)

        // Common maps KSuspendFunctionN to KFunctionN+1 for its reflection identity. The .NET
        // runtime keeps that orthogonal KFunction capability, but execution still needs the same
        // erased FunctionN+1 slot as an ordinary SuspendFunctionN. Add that one-way capability
        // without making an arbitrary FunctionN+1 object a suspend function.
        for (suspendType in substitutedSupertypes.filter { it.isKSuspendFunction() }) {
            val projectedTypes = suspendType.arguments.map { argument ->
                (argument as? IrTypeProjection)?.type
            }
            if (projectedTypes.any { it == null }) continue
            val logicalTypes = projectedTypes.filterNotNull()
            val physicalArity = logicalTypes.size
            if (physicalArity !in 1 until BuiltInFunctionArity.BIG_ARITY) continue
            val functionClass = context.irBuiltIns.functionN(physicalArity)
            val executionType = functionClass.symbol.typeWithArguments(
                logicalTypes.dropLast(1) +
                        context.symbols.continuationClass.typeWith(logicalTypes.last()) +
                        context.irBuiltIns.anyNType
            )
            if (clazz.superTypes.none { it == executionType }) clazz.superTypes += executionType

            val suspendInvoke = suspendType.classOrNull?.owner?.simpleFunctions()
                ?.singleOrNull { it.name == OperatorNameConventions.INVOKE }
                ?: continue
            val loweredSuspendInvoke = getLoweredForSuspendFun(suspendInvoke)
            val implementation = clazz.simpleFunctions()
                .singleOrNull { it.overrides(loweredSuspendInvoke) }
                ?: continue
            val executionInvoke = functionClass.invokeFun
                ?: error("Internal .NET backend error: kotlin.Function$physicalArity has no invoke member")
            if (executionInvoke.symbol !in implementation.overriddenSymbols) {
                implementation.overriddenSymbols += executionInvoke.symbol
            }
        }
    }

    override fun getLoweredForSuspendFun(irFunction: IrSimpleFunction): IrSimpleFunction =
        irFunction.factory.stageController.restrictTo(irFunction) {
            super.getLoweredForSuspendFun(irFunction)
        }
}

/**
 * Binds a continuation-lowered `(K)SuspendFunctionN.invoke` call to its physical
 * `FunctionN+1.invoke` capability. Common owns the appended continuation and erased return; this
 * target pass only replaces the logical built-in member token after the class capability has
 * been established above.
 */
internal class DotNetSuspendFunctionInvokeLowering(
    private val context: DotNetBackendContext,
) : IrElementTransformerVoid(), FileLoweringPass {
    override fun lower(irFile: IrFile) {
        irFile.transformChildrenVoid(this)
    }

    override fun visitCall(expression: IrCall): IrExpression {
        expression.transformChildrenVoid(this)
        val callee = expression.symbol.owner
        if (callee.name != OperatorNameConventions.INVOKE) return expression
        val logicalOwner = callee.parent as? IrClass ?: return expression
        if (!logicalOwner.symbol.isSuspendFunction() && !logicalOwner.symbol.isKSuspendFunction()) {
            return expression
        }
        val receiver = expression.dispatchReceiver ?: return expression
        val sourceArity = logicalOwner.name.asString()
            .removePrefix("K")
            .removePrefix("SuspendFunction")
            .toIntOrNull()
            ?: return expression
        val physicalArity = sourceArity + 1
        if (physicalArity !in 1 until BuiltInFunctionArity.BIG_ARITY) return expression

        // A direct interface receiver carries P.../R itself. A freshly lowered suspend lambda or
        // callable reference instead has its concrete generated class type here; recover the
        // substituted suspend supertype rather than mistaking that class's own arity (usually
        // zero) for the callable arity.
        val receiverType = receiver.type as? IrSimpleType
        val suspendView = receiverType?.let { type ->
            sequenceOf(type)
                .plus(type.classOrNull?.owner?.let(::getAllSubstitutedSupertypes).orEmpty().asSequence())
                .firstOrNull { candidate ->
                    candidate.arguments.size == sourceArity + 1 &&
                            (candidate.isSuspendFunction() || candidate.isKSuspendFunction())
                }
        }
        val receiverLogicalTypes = suspendView?.arguments?.map { argument ->
            (argument as? IrTypeProjection)?.type
        }?.takeIf { types -> types.none { it == null } }
            ?.filterNotNull()
        val containsRawOwnerParameter = receiverLogicalTypes?.any { type ->
            val parameter = (type as? IrSimpleType)?.classifier as? IrTypeParameterSymbol
            parameter?.owner?.parent == logicalOwner
        } == true
        val callSiteLogicalTypes: List<IrType>? = if (containsRawOwnerParameter) {
            val arguments = expression.nonDispatchArguments
            val parameterTypes = arguments.take(sourceArity).map { argument -> argument?.type }
            val continuationType = arguments.getOrNull(sourceArity)?.type as? IrSimpleType
            val resultType = continuationType
                ?.takeIf { it.classOrNull?.owner == context.symbols.continuationClass.owner }
                ?.arguments
                ?.singleOrNull()
                ?.let { it as? IrTypeProjection }
                ?.type
            if (parameterTypes.size == sourceArity && parameterTypes.none { it == null } && resultType != null) {
                parameterTypes.filterNotNull() + resultType
            } else {
                null
            }
        } else {
            null
        }
        val logicalTypes = callSiteLogicalTypes
            ?: receiverLogicalTypes
            ?: List(sourceArity + 1) { context.irBuiltIns.anyNType }

        val functionClass = context.irBuiltIns.functionN(physicalArity)
        val executionType = functionClass.symbol.typeWithArguments(
            logicalTypes.dropLast(1) +
                    context.symbols.continuationClass.typeWith(logicalTypes.last()) +
                    context.irBuiltIns.anyNType
        )
        expression.symbol = functionClass.invokeFun?.symbol
            ?: error("Internal .NET backend error: kotlin.Function$physicalArity has no invoke member")
        expression.dispatchReceiver = IrTypeOperatorCallImpl(
            receiver.startOffset,
            receiver.endOffset,
            executionType,
            IrTypeOperator.IMPLICIT_CAST,
            executionType,
            receiver,
        )
        return expression
    }
}
