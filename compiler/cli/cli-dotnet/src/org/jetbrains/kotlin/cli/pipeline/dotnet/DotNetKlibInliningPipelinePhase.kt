/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.cli.pipeline.dotnet

import org.jetbrains.kotlin.backend.common.phaser.PhaseEngine
import org.jetbrains.kotlin.backend.dotnet.DotNetPreSerializationLoweringContext
import org.jetbrains.kotlin.backend.dotnet.dotNetLoweringsOfTheFirstPhase
import org.jetbrains.kotlin.backend.dotnet.hasDotNetPreSerializationLoweringSymbols
import org.jetbrains.kotlin.cli.common.diagnosticsCollector
import org.jetbrains.kotlin.cli.common.runPreSerializationLoweringPhases
import org.jetbrains.kotlin.cli.pipeline.CheckCompilationErrors
import org.jetbrains.kotlin.cli.pipeline.PerformanceNotifications
import org.jetbrains.kotlin.cli.pipeline.PipelinePhase
import org.jetbrains.kotlin.config.languageVersionSettings
import org.jetbrains.kotlin.config.phaseConfig
import org.jetbrains.kotlin.config.phaser.PhaseConfig
import org.jetbrains.kotlin.config.phaser.PhaserState
import org.jetbrains.kotlin.ir.KtDiagnosticReporterWithImplicitIrBasedContext

object DotNetKlibInliningPipelinePhase :
    PipelinePhase<DotNetFir2IrPipelineArtifact, DotNetFir2IrPipelineArtifact>(
        name = "DotNetKlibInliningPipelinePhase",
        preActions = setOf(PerformanceNotifications.IrPreLoweringStarted),
        postActions = setOf(PerformanceNotifications.IrPreLoweringFinished, CheckCompilationErrors.CheckDiagnosticCollector),
    ) {
    override fun executePhase(input: DotNetFir2IrPipelineArtifact): DotNetFir2IrPipelineArtifact {
        val configuration = input.configuration
        // `-no-stdlib` is a supported diagnostic/foreign-CLR compilation mode. Common's
        // first-stage KLIB lowerings require compiler ABI classes from the Kotlin stdlib, so
        // there is no valid lowering context to construct when those classes are absent.
        if (!input.result.irBuiltIns.hasDotNetPreSerializationLoweringSymbols()) return input
        val context = DotNetPreSerializationLoweringContext(
            input.result.irBuiltIns,
            configuration,
            KtDiagnosticReporterWithImplicitIrBasedContext(
                configuration.diagnosticsCollector,
                configuration.languageVersionSettings,
            ),
        )
        val transformedResult = PhaseEngine(
            configuration.phaseConfig ?: PhaseConfig(),
            PhaserState(),
            context,
        ).runPreSerializationLoweringPhases(
            input.result,
            dotNetLoweringsOfTheFirstPhase(configuration.languageVersionSettings),
        )
        return input.copy(result = transformedResult)
    }
}
