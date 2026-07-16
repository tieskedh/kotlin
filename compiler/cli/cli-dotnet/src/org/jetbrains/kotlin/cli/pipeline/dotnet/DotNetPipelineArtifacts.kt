package org.jetbrains.kotlin.cli.pipeline.dotnet

import org.jetbrains.kotlin.KtSourceFile
import org.jetbrains.kotlin.cli.pipeline.Fir2IrPipelineArtifact
import org.jetbrains.kotlin.cli.pipeline.FrontendPipelineArtifact
import org.jetbrains.kotlin.cli.pipeline.PipelineArtifact
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fir.pipeline.AllModulesFrontendOutput
import org.jetbrains.kotlin.fir.pipeline.Fir2IrActualizedResult
import org.jetbrains.kotlin.library.SerializedMetadata
import java.io.File

data class DotNetFrontendPipelineArtifact(
    override val frontendOutput: AllModulesFrontendOutput,
    override val configuration: CompilerConfiguration,
    val sourceFiles: List<KtSourceFile>,
    val stdlibMetadata: SerializedMetadata? = null,
) : FrontendPipelineArtifact() {
    @CliPipelineInternals(OPT_IN_MESSAGE)
    override fun withCompilerConfiguration(newConfiguration: CompilerConfiguration): DotNetFrontendPipelineArtifact =
        copy(configuration = newConfiguration)

    override fun withNewFrontendOutputImpl(newFrontendOutput: AllModulesFrontendOutput): FrontendPipelineArtifact =
        copy(frontendOutput = newFrontendOutput)
}

data class DotNetFir2IrPipelineArtifact(
    override val result: Fir2IrActualizedResult,
    val frontendOutput: AllModulesFrontendOutput,
    override val configuration: CompilerConfiguration,
    val stdlibMetadata: SerializedMetadata?,
) : Fir2IrPipelineArtifact() {
    @CliPipelineInternals(OPT_IN_MESSAGE)
    override fun withCompilerConfiguration(newConfiguration: CompilerConfiguration): DotNetFir2IrPipelineArtifact =
        copy(configuration = newConfiguration)
}

data class DotNetBackendPipelineArtifact(
    val output: File,
    override val configuration: CompilerConfiguration,
    val stdlibMetadata: SerializedMetadata?,
) : PipelineArtifact() {
    @CliPipelineInternals(OPT_IN_MESSAGE)
    override fun withCompilerConfiguration(newConfiguration: CompilerConfiguration): DotNetBackendPipelineArtifact =
        copy(configuration = newConfiguration)
}
