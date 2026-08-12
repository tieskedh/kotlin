package org.jetbrains.kotlin.cli.pipeline.dotnet

import org.jetbrains.kotlin.backend.dotnet.DotNetBackend
import org.jetbrains.kotlin.backend.dotnet.DotNetPhysicalDeclaration
import org.jetbrains.kotlin.backend.dotnet.dotNetProducedLibraryArtifact
import org.jetbrains.kotlin.cli.pipeline.CheckCompilationErrors
import org.jetbrains.kotlin.cli.pipeline.PipelinePhase

object DotNetBackendPipelinePhase : PipelinePhase<DotNetFir2IrPipelineArtifact, DotNetBackendPipelineArtifact>(
    name = "DotNetBackendPipelinePhase",
    postActions = setOf(CheckCompilationErrors.CheckDiagnosticCollector),
) {
    override fun executePhase(input: DotNetFir2IrPipelineArtifact): DotNetBackendPipelineArtifact {
        val kotlinMetadataResourceFactory:
                ((Map<String, DotNetPhysicalDeclaration>) -> ByteArray)? =
            input.libraryMetadata?.let { metadata ->
                val artifact = checkNotNull(input.configuration.dotNetProducedLibraryArtifact)
                val resourceFactory: (Map<String, DotNetPhysicalDeclaration>) -> ByteArray = { declarations ->
                    DotNetLibraryMetadataPackager.createEmbeddedResource(
                        configuration = input.configuration,
                        artifact = artifact,
                        metadata = metadata,
                        ir = checkNotNull(input.libraryIr),
                        declarations = declarations,
                    )
                }
                resourceFactory
            }
        val output = DotNetBackend.compile(
            input.result.irModuleFragment,
            input.result.irBuiltIns,
            input.result.symbolTable,
            input.configuration,
            kotlinMetadataResourceFactory,
            input.exactContractProjections,
        )
        return DotNetBackendPipelineArtifact(
            output.file,
            input.configuration,
            input.libraryMetadata,
            input.libraryIr,
            output.declarations,
            output.genericOwnerPrototypes,
        )
    }
}
