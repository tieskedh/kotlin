/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet.lower

import org.jetbrains.kotlin.backend.common.phaser.IrValidationAfterLoweringsSecondStagePhase
import org.jetbrains.kotlin.backend.common.suspendFunction
import org.jetbrains.kotlin.backend.dotnet.DotNetBackendContext
import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrFunctionReference
import org.jetbrains.kotlin.ir.expressions.IrRawFunctionReference
import org.jetbrains.kotlin.ir.expressions.IrRichFunctionReference
import org.jetbrains.kotlin.ir.expressions.IrSuspendableExpression
import org.jetbrains.kotlin.ir.expressions.IrSuspensionPoint
import org.jetbrains.kotlin.ir.util.isSuspend
import org.jetbrains.kotlin.ir.util.isTopLevelInPackage
import org.jetbrains.kotlin.ir.validation.IrValidatorConfig
import org.jetbrains.kotlin.ir.validation.checkers.IrElementChecker
import org.jetbrains.kotlin.ir.validation.checkers.context.CheckerContext

/**
 * Verifies the physical IR contract consumed by CIL generation.
 *
 * JVM adds its target invariants to the shared final validation phase, while JS and Native keep
 * late codegen assertions that no suspend declarations remain. Kotlin/.NET does both: this phase
 * gives `-Xverify-ir` an early, source-located failure, and the emitter retains its unconditional
 * guard for production compilations where optional IR verification is disabled.
 */
internal class DotNetIrValidationAfterLoweringPhase(
    context: DotNetBackendContext,
) : IrValidationAfterLoweringsSecondStagePhase<DotNetBackendContext>(context) {
    override val defaultValidationConfig: IrValidatorConfig
        get() = super.defaultValidationConfig.withCheckers(
            NoResidualSuspendDeclarationChecker,
            NoResidualSuspendCallChecker,
            NoResidualSuspendFunctionReferenceChecker,
            NoResidualSuspendRawFunctionReferenceChecker,
            NoResidualSuspendRichFunctionReferenceChecker,
            NoResidualSuspensionPointChecker,
            NoResidualSuspendableExpressionChecker,
            NoCoroutineCompilerIntrinsicCallChecker,
        )
}

private object NoResidualSuspendDeclarationChecker : IrElementChecker<IrSimpleFunction>(IrSimpleFunction::class) {
    override fun check(element: IrSimpleFunction, context: CheckerContext) {
        if (element.isSuspend) {
            context.error(element, "Suspend declarations must be continuation-lowered before .NET CIL generation")
        }
    }
}

private object NoResidualSuspendCallChecker : IrElementChecker<IrCall>(IrCall::class) {
    override fun check(element: IrCall, context: CheckerContext) {
        if (element.isSuspend) {
            context.error(element, "Suspend calls must be continuation-lowered before .NET CIL generation")
        }
    }
}

private object NoResidualSuspendFunctionReferenceChecker : IrElementChecker<IrFunctionReference>(IrFunctionReference::class) {
    override fun check(element: IrFunctionReference, context: CheckerContext) {
        if (element.symbol.owner.isSuspend) {
            context.error(element, "Suspend function references must be lowered before .NET CIL generation")
        }
    }
}

private object NoResidualSuspendRawFunctionReferenceChecker : IrElementChecker<IrRawFunctionReference>(IrRawFunctionReference::class) {
    override fun check(element: IrRawFunctionReference, context: CheckerContext) {
        if (element.symbol.owner.isSuspend) {
            context.error(element, "Raw suspend function references must be continuation-lowered before .NET CIL generation")
        }
    }
}

private object NoResidualSuspendRichFunctionReferenceChecker : IrElementChecker<IrRichFunctionReference>(IrRichFunctionReference::class) {
    override fun check(element: IrRichFunctionReference, context: CheckerContext) {
        if (
            element.invokeFunction.isSuspend ||
            element.overriddenFunctionSymbol.owner.isSuspend ||
            element.reflectionTargetSymbol?.owner?.isSuspend == true
        ) {
            context.error(element, "Rich suspend function references must be lowered before .NET CIL generation")
        }
    }
}

private object NoResidualSuspensionPointChecker : IrElementChecker<IrSuspensionPoint>(IrSuspensionPoint::class) {
    override fun check(element: IrSuspensionPoint, context: CheckerContext) {
        context.error(element, "Suspension-point pseudo-IR must be lowered before .NET CIL generation")
    }
}

private object NoResidualSuspendableExpressionChecker : IrElementChecker<IrSuspendableExpression>(IrSuspendableExpression::class) {
    override fun check(element: IrSuspendableExpression, context: CheckerContext) {
        context.error(element, "Suspendable-expression pseudo-IR must be lowered before .NET CIL generation")
    }
}

private object NoCoroutineCompilerIntrinsicCallChecker : IrElementChecker<IrCall>(IrCall::class) {
    override fun check(element: IrCall, context: CheckerContext) {
        val callee = element.symbol.owner
        val logicalCallee = callee.suspendFunction ?: callee
        if (callee.isCoroutineCompilerIntrinsic() || logicalCallee.isCoroutineCompilerIntrinsic()) {
            context.error(
                element,
                "Coroutine compiler intrinsic '${logicalCallee.name.asString()}' must be consumed before .NET CIL generation",
            )
        }
    }
}

private val dotNetCoroutineInternalPackage = FqName("kotlin.dotnet.internal")

/**
 * Matches declaration identity without resolving the stdlib symbols. The final validator also runs
 * for valid `-no-stdlib` foreign-metadata compilations, so merely constructing it must not make
 * `kotlin.coroutines` a required dependency.
 */
private fun IrSimpleFunction.isCoroutineCompilerIntrinsic(): Boolean =
    correspondingPropertySymbol?.owner?.isTopLevelInPackage(
        "coroutineContext",
        StandardNames.COROUTINES_PACKAGE_FQ_NAME,
    ) == true || when (name.asString()) {
        "getCoroutineContext",
        "getContinuation",
        "returnIfSuspended",
        "suspendCoroutineUninterceptedOrReturnDotNet",
        -> isTopLevelInPackage(name.asString(), dotNetCoroutineInternalPackage)

        else -> false
    }
