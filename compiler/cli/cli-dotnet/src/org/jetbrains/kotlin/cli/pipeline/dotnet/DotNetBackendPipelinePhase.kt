package org.jetbrains.kotlin.cli.pipeline.dotnet

import org.jetbrains.kotlin.backend.dotnet.DotNetBackend
import org.jetbrains.kotlin.cli.pipeline.CheckCompilationErrors
import org.jetbrains.kotlin.cli.pipeline.PerformanceNotifications
import org.jetbrains.kotlin.cli.pipeline.PipelinePhase

object DotNetBackendPipelinePhase : PipelinePhase<DotNetFir2IrPipelineArtifact, DotNetBackendPipelineArtifact>(
    name = "DotNetBackendPipelinePhase",
    preActions = setOf(PerformanceNotifications.BackendStarted),
    postActions = setOf(PerformanceNotifications.BackendFinished, CheckCompilationErrors.CheckDiagnosticCollector),
) {
    override fun executePhase(input: DotNetFir2IrPipelineArtifact): DotNetBackendPipelineArtifact {
        val output = DotNetBackend.compile(
            input.result.irModuleFragment,
            input.result.irBuiltIns,
            input.result.symbolTable,
            input.configuration,
        )
        return DotNetBackendPipelineArtifact(output, input.configuration, input.libraryMetadata)
    }
}
