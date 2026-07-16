package org.jetbrains.kotlin.cli.pipeline.dotnet

import org.jetbrains.kotlin.cli.common.arguments.K2DotNetCompilerArguments
import org.jetbrains.kotlin.backend.common.phaser.then
import org.jetbrains.kotlin.cli.pipeline.AbstractCliPipeline
import org.jetbrains.kotlin.cli.pipeline.ArgumentsPipelineArtifact
import org.jetbrains.kotlin.cli.pipeline.PipelineContext
import org.jetbrains.kotlin.config.phaser.CompilerPhase
import org.jetbrains.kotlin.util.PerformanceManager

class DotNetCliPipeline(override val defaultPerformanceManager: PerformanceManager) :
    AbstractCliPipeline<K2DotNetCompilerArguments>() {
    override fun createCompoundPhase(
        arguments: K2DotNetCompilerArguments,
    ): CompilerPhase<PipelineContext, ArgumentsPipelineArtifact<K2DotNetCompilerArguments>, *> {
        return if (arguments.dotNetProduceStdlib || arguments.dotNetProduceLibrary) {
            DotNetConfigurationPipelinePhase then
                    DotNetFrontendPipelinePhase then
                    DotNetLibraryMetadataSerializationPipelinePhase then
                    DotNetFir2IrPipelinePhase then
                    DotNetBackendPipelinePhase then
                    DotNetLibraryMetadataPackagingPipelinePhase
        } else {
            DotNetConfigurationPipelinePhase then
                    DotNetFrontendPipelinePhase then
                    DotNetFir2IrPipelinePhase then
                    DotNetBackendPipelinePhase
        }
    }

    override fun isKaptMode(arguments: K2DotNetCompilerArguments): Boolean = false
}
