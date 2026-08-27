/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.analysis.dotnet.checkers

import org.jetbrains.kotlin.KtRealSourceElementKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.hasExplicitReturnType
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirFunctionCallChecker
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirReturnExpressionChecker
import org.jetbrains.kotlin.fir.analysis.diagnostics.dotnet.FirDotNetErrors
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.dotnet.DotNetClrForeignVarianceConversionResult
import org.jetbrains.kotlin.fir.dotnet.dotNetClrForeignVarianceConversion
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirReturnExpression
import org.jetbrains.kotlin.fir.expressions.createConeSubstitutorFromTypeArguments
import org.jetbrains.kotlin.fir.expressions.resolvedArgumentMapping
import org.jetbrains.kotlin.fir.expressions.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.expressions.unwrapArgument
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.resolvedType

object FirDotNetClrVarianceReturnChecker : FirReturnExpressionChecker(MppCheckerKind.Platform) {
    override val platformSpecificCheckerEnabledInMetadataCompilation: Boolean
        get() = true

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirReturnExpression) {
        val target = expression.target.labeledElement as? FirNamedFunction ?: return
        if (!target.symbol.hasExplicitReturnType) return
        expression.result.reportInvalidClrVarianceConversion(target.returnTypeRef.coneType)
    }
}

object FirDotNetClrVarianceArgumentChecker : FirFunctionCallChecker(MppCheckerKind.Platform) {
    override val platformSpecificCheckerEnabledInMetadataCompilation: Boolean
        get() = true

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirFunctionCall) {
        val symbol = expression.toResolvedCallableSymbol() as? FirFunctionSymbol<*> ?: return
        val substitutor = expression.createConeSubstitutorFromTypeArguments(symbol, context.session)
        for ([argument, parameter] in expression.resolvedArgumentMapping ?: return) {
            if (parameter.isVararg) continue
            argument.unwrapArgument().reportInvalidClrVarianceConversion(
                substitutor.substituteOrSelf(parameter.returnTypeRef.coneType)
            )
        }
    }
}

context(context: CheckerContext, reporter: DiagnosticReporter)
private fun FirExpression.reportInvalidClrVarianceConversion(expectedType: ConeKotlinType) {
    if (source?.kind !is KtRealSourceElementKind) return
    val actualType = resolvedType
    val conversion = dotNetClrForeignVarianceConversion(
        actualType,
        expectedType,
        context.session,
    )
    if (conversion == DotNetClrForeignVarianceConversionResult.REQUIRES_REFERENCE_ARGUMENTS) {
        reporter.reportOn(
            source,
            FirDotNetErrors.DOTNET_CLR_VARIANCE_REQUIRES_REFERENCE_ARGUMENTS,
            expectedType,
            actualType,
        )
    }
}
