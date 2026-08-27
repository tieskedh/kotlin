/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.checkers.generator.diagnostics

import org.jetbrains.kotlin.fir.checkers.generator.diagnostics.model.DiagnosticList
import org.jetbrains.kotlin.fir.checkers.generator.diagnostics.model.PositioningStrategy
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.util.PrivateForInline

@Suppress("UNUSED_VARIABLE", "ClassName", "unused")
@OptIn(PrivateForInline::class)
object DOTNET_DIAGNOSTICS_LIST : DiagnosticList("FirDotNetErrors") {
    val CLR_INTEROP by object : DiagnosticGroup("CLR interop") {
        val DOTNET_CLR_VARIANCE_REQUIRES_REFERENCE_ARGUMENTS by
            error<KtExpression>(PositioningStrategy.WHOLE_ELEMENT) {
                parameter<ConeKotlinType>("expectedType")
                parameter<ConeKotlinType>("actualType")
            }
    }
}
