package org.jetbrains.kotlin.cli.pipeline.dotnet

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.dotnet.DotNetIrMangler
import org.jetbrains.kotlin.builtins.DefaultBuiltIns
import org.jetbrains.kotlin.cli.common.diagnosticsCollector
import org.jetbrains.kotlin.cli.pipeline.CheckCompilationErrors
import org.jetbrains.kotlin.cli.pipeline.PerformanceNotifications
import org.jetbrains.kotlin.cli.pipeline.PipelinePhase
import org.jetbrains.kotlin.compiler.plugin.getCompilerExtensions
import org.jetbrains.kotlin.fir.backend.Fir2IrConfiguration
import org.jetbrains.kotlin.fir.backend.Fir2IrExtensions
import org.jetbrains.kotlin.fir.backend.Fir2IrVisibilityConverter
import org.jetbrains.kotlin.fir.pipeline.convertToIrAndActualize
import org.jetbrains.kotlin.ir.types.IrTypeSystemContextImpl

object DotNetFir2IrPipelinePhase : PipelinePhase<DotNetFrontendPipelineArtifact, DotNetFir2IrPipelineArtifact>(
    name = "DotNetFir2IrPipelinePhase",
    preActions = setOf(PerformanceNotifications.TranslationToIrStarted),
    postActions = setOf(PerformanceNotifications.TranslationToIrFinished, CheckCompilationErrors.CheckDiagnosticCollector),
) {
    override fun executePhase(input: DotNetFrontendPipelineArtifact): DotNetFir2IrPipelineArtifact {
        val configuration = input.configuration
        val diagnosticsReporter = configuration.diagnosticsCollector
        val fir2IrResult = input.frontendOutput.convertToIrAndActualize(
            Fir2IrExtensions.Default,
            Fir2IrConfiguration.forKlibCompilation(configuration, diagnosticsReporter),
            configuration.getCompilerExtensions(IrGenerationExtension),
            DotNetIrMangler,
            Fir2IrVisibilityConverter.Default,
            DefaultBuiltIns.Instance,
            ::IrTypeSystemContextImpl,
            specialAnnotationsProvider = null,
            extraActualDeclarationExtractorsInitializer = { emptyList() },
        )

        return DotNetFir2IrPipelineArtifact(
            fir2IrResult,
            input.frontendOutput,
            configuration,
            input.stdlibMetadata,
        )
    }
}
