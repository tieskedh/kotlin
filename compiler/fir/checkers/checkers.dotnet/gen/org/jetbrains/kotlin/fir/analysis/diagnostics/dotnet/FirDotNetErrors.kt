/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.analysis.diagnostics.dotnet

import org.jetbrains.kotlin.diagnostics.*
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory2
import org.jetbrains.kotlin.diagnostics.KtDiagnosticsContainer
import org.jetbrains.kotlin.diagnostics.Severity.ERROR
import org.jetbrains.kotlin.diagnostics.SourceElementPositioningStrategies
import org.jetbrains.kotlin.diagnostics.rendering.BaseDiagnosticRendererFactory
import org.jetbrains.kotlin.fir.analysis.diagnostics.*
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.psi.KtExpression

/**
 * Generated from: [org.jetbrains.kotlin.fir.checkers.generator.diagnostics.DOTNET_DIAGNOSTICS_LIST]
 */
@Suppress("IncorrectFormatting")
object FirDotNetErrors : KtDiagnosticsContainer() {
    // CLR interop
    val DOTNET_CLR_VARIANCE_REQUIRES_REFERENCE_ARGUMENTS: KtDiagnosticFactory2<ConeKotlinType, ConeKotlinType> = KtDiagnosticFactory2("DOTNET_CLR_VARIANCE_REQUIRES_REFERENCE_ARGUMENTS", ERROR, SourceElementPositioningStrategies.WHOLE_ELEMENT, KtExpression::class, getRendererFactory())

    override fun getRendererFactory(): BaseDiagnosticRendererFactory = FirDotNetErrorsDefaultMessages
}
