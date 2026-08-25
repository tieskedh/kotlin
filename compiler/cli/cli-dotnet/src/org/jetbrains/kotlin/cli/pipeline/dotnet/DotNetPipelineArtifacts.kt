package org.jetbrains.kotlin.cli.pipeline.dotnet

import org.jetbrains.kotlin.KtSourceFile
import org.jetbrains.kotlin.backend.dotnet.DotNetPhysicalDeclaration
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPrototypeSnapshot
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerCallRouteSnapshot
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalValueShadowSnapshot
import org.jetbrains.kotlin.cli.pipeline.Fir2IrPipelineArtifact
import org.jetbrains.kotlin.cli.pipeline.FrontendPipelineArtifact
import org.jetbrains.kotlin.cli.pipeline.PipelineArtifact
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fir.pipeline.AllModulesFrontendOutput
import org.jetbrains.kotlin.fir.pipeline.Fir2IrActualizedResult
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.library.SerializedMetadata
import org.jetbrains.kotlin.library.SerializedIrModule
import org.jetbrains.kotlin.load.dotnet.DotNetExactContractProjection
import java.io.File

data class DotNetFrontendPipelineArtifact(
    override val frontendOutput: AllModulesFrontendOutput,
    override val configuration: CompilerConfiguration,
    val sourceFiles: List<KtSourceFile>,
    val libraryMetadata: SerializedMetadata? = null,
    val libraryIr: SerializedIrModule? = null,
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
    val libraryMetadata: SerializedMetadata?,
    val libraryIr: SerializedIrModule?,
    val exactContractProjections: Map<IrSimpleFunction, DotNetExactContractProjection>,
) : Fir2IrPipelineArtifact() {
    @CliPipelineInternals(OPT_IN_MESSAGE)
    override fun withCompilerConfiguration(newConfiguration: CompilerConfiguration): DotNetFir2IrPipelineArtifact =
        copy(configuration = newConfiguration)
}

data class DotNetBackendPipelineArtifact(
    val output: File,
    override val configuration: CompilerConfiguration,
    val libraryMetadata: SerializedMetadata?,
    val libraryIr: SerializedIrModule?,
    val declarations: Map<String, DotNetPhysicalDeclaration>,
    val genericOwnerPrototypes: List<DotNetGenericOwnerPrototypeSnapshot>,
    val genericOwnerCallRoutes: List<DotNetGenericOwnerCallRouteSnapshot>,
    val genericOwnerPhysicalValueShadows: List<DotNetGenericOwnerPhysicalValueShadowSnapshot>,
    val genericOwnerRehearsal: Boolean,
) : PipelineArtifact() {
    @CliPipelineInternals(OPT_IN_MESSAGE)
    override fun withCompilerConfiguration(newConfiguration: CompilerConfiguration): DotNetBackendPipelineArtifact =
        copy(configuration = newConfiguration)
}
