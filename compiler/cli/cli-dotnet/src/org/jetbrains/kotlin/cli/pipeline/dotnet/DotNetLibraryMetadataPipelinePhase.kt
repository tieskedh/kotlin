/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.cli.pipeline.dotnet

import org.jetbrains.kotlin.backend.common.serialization.addLanguageFeaturesToManifest
import org.jetbrains.kotlin.backend.common.serialization.IrSerializationSettings
import org.jetbrains.kotlin.backend.dotnet.DOTNET_STDLIB_SOURCES
import org.jetbrains.kotlin.backend.dotnet.DOTNET_STDLIB_SOURCE_PATHS
import org.jetbrains.kotlin.backend.dotnet.DotNetLibraryArtifact
import org.jetbrains.kotlin.backend.dotnet.DotNetLibraryAbiCodec
import org.jetbrains.kotlin.backend.dotnet.DotNetKotlinMetadataResource
import org.jetbrains.kotlin.backend.dotnet.DotNetPhysicalDeclaration
import org.jetbrains.kotlin.backend.dotnet.dotNetFriendAssemblies
import org.jetbrains.kotlin.backend.dotnet.dotNetProducedLibraryArtifact
import org.jetbrains.kotlin.backend.dotnet.serialization.DotNetIrModuleSerializer
import org.jetbrains.kotlin.cli.pipeline.CheckCompilationErrors
import org.jetbrains.kotlin.cli.pipeline.PerformanceNotifications
import org.jetbrains.kotlin.cli.pipeline.PipelinePhase
import org.jetbrains.kotlin.cli.common.diagnosticsCollector
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.KotlinCompilerVersion
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.config.dotNetOutput
import org.jetbrains.kotlin.config.dotNetProducesLibrary
import org.jetbrains.kotlin.config.dotNetProducesStdlib
import org.jetbrains.kotlin.config.klibRelativePathBases
import org.jetbrains.kotlin.config.languageVersionSettings
import org.jetbrains.kotlin.config.perfManager
import org.jetbrains.kotlin.fir.packageFqName
import org.jetbrains.kotlin.fir.pipeline.Fir2KlibMetadataSerializer
import org.jetbrains.kotlin.library.KlibFormat
import org.jetbrains.kotlin.library.KotlinAbiVersion
import org.jetbrains.kotlin.library.KotlinLibraryVersioning
import org.jetbrains.kotlin.library.SerializedMetadata
import org.jetbrains.kotlin.library.SerializedIrModule
import org.jetbrains.kotlin.library.impl.BuiltInsPlatform
import org.jetbrains.kotlin.library.loadSizeInfo
import org.jetbrains.kotlin.library.metadata.KlibMetadataProtoBuf
import org.jetbrains.kotlin.library.metadata.addMetadataFlagsToHeader
import org.jetbrains.kotlin.library.metadata.metadataFlags
import org.jetbrains.kotlin.library.writer.KlibWriter
import org.jetbrains.kotlin.library.writer.includeMetadata
import org.jetbrains.kotlin.library.writer.includeIr
import org.jetbrains.kotlin.ir.KtDiagnosticReporterWithImplicitIrBasedContext
import org.jetbrains.kotlin.util.metadataVersion
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolute

/** Serializes the Kotlin declarations embedded in one profile-specific CLR implementation assembly. */
object DotNetLibraryMetadataSerializationPipelinePhase :
    PipelinePhase<DotNetFir2IrPipelineArtifact, DotNetFir2IrPipelineArtifact>(
        name = "DotNetLibraryMetadataSerializationPipelinePhase",
        preActions = setOf(PerformanceNotifications.KlibWritingStarted),
        postActions = setOf(CheckCompilationErrors.CheckDiagnosticCollector),
    ) {
    override fun executePhase(input: DotNetFir2IrPipelineArtifact): DotNetFir2IrPipelineArtifact {
        val configuration = input.configuration
        check(configuration.dotNetProducesStdlib.xor(configuration.dotNetProducesLibrary))
        val artifact = checkNotNull(configuration.dotNetProducedLibraryArtifact)
        val outputDirectory = configuration.dotNetOutput!!
        outputDirectory.mkdirs()
        outputDirectory.resolve("${artifact.assemblyName}.klib").deleteRecursively()
        outputDirectory.resolve(".${artifact.assemblyName}.klib.tmp").deleteRecursively()

        val metadataVersion = configuration.metadataVersion()
        val fragments = mutableMapOf<String, MutableList<Pair<String, ByteArray>>>()
        val serializedSourceNames = mutableSetOf<String>()
        val excludedIrSourcePaths = mutableSetOf<String>()
        val stdlibSourceBaseDirs = mutableSetOf<String>()
        val metadataSerializer = Fir2KlibMetadataSerializer(
            compilerConfiguration = configuration,
            firOutputs = input.frontendOutput.outputs,
            fir2IrActualizedResult = input.result,
            produceHeaderKlib = false,
        )
        for (output in input.frontendOutput.outputs) {
            val fir = output.fir
            for (firFile in fir) {
                val isBootstrapStdlibSource = firFile.name in DOTNET_STDLIB_SOURCES
                if (configuration.dotNetProducesStdlib != isBootstrapStdlibSource) {
                    excludedIrSourcePaths += firFile.sourceFile?.path ?: firFile.name
                    continue
                }
                val packageFragment = metadataSerializer.serializeSingleFileMetadata(firFile)
                serializedSourceNames += firFile.name
                if (configuration.dotNetProducesStdlib) {
                    stdlibSourceBaseDirs += dotNetStdlibSourceBaseDir(
                        sourcePath = checkNotNull(firFile.sourceFile?.path) {
                            "The Kotlin/.NET stdlib source '${firFile.name}' has no physical path"
                        },
                        relativePath = DOTNET_STDLIB_SOURCE_PATHS.getValue(firFile.name),
                    )
                }
                fragments.getOrPut(firFile.packageFqName.asString()) { mutableListOf() }
                    .add(firFile.name to packageFragment.toByteArray())
            }
        }
        if (configuration.dotNetProducesStdlib) {
            check(serializedSourceNames == DOTNET_STDLIB_SOURCES.keys) {
                "The stdlib producer resolved ${serializedSourceNames.sorted()}, expected ${DOTNET_STDLIB_SOURCES.keys.sorted()}"
            }
            check(stdlibSourceBaseDirs.size == 1) {
                "The stdlib producer resolved incompatible source roots ${stdlibSourceBaseDirs.sorted()}"
            }
        } else {
            check(serializedSourceNames.isNotEmpty()) {
                "The library producer did not resolve any user source files"
            }
        }

        val header = KlibMetadataProtoBuf.Header.newBuilder().apply {
            moduleName = artifact.assemblyName
            addMetadataFlagsToHeader(this, configuration.languageVersionSettings)
        }
        val fragmentNames = mutableListOf<String>()
        val fragmentParts = mutableListOf<List<ByteArray>>()
        for ([fqName, fragment] in fragments.entries.sortedBy { it.key }) {
            val orderedFragment = fragment.sortedBy { it.first }.map { it.second }
            fragmentNames += fqName
            fragmentParts += orderedFragment
            header.addPackageFragmentName(fqName)
        }
        val metadata = SerializedMetadata(
            header.build().toByteArray(),
            fragmentParts,
            fragmentNames,
            metadataVersion.toArray(),
        )
        // HMPP actualization does not retain a one-to-one FIR-file -> IR-file relation for
        // Common source files. Select from the actualized main IR instead: for an ordinary
        // library this excludes only compiler-injected bootstrap stdlib sources, while a
        // stdlib product retains its complete actualized IR and any plugin-generated files.
        val serializedIrFiles = input.result.irModuleFragment.files
            .filterTo(linkedSetOf()) { file -> file.fileEntry.name !in excludedIrSourcePaths }
        check(serializedIrFiles.isNotEmpty()) {
            "The .NET library producer did not resolve any actualized IR files"
        }
        val ir = DotNetIrModuleSerializer(
            settings = IrSerializationSettings(
                configuration,
                sourceBaseDirs = stdlibSourceBaseDirs + configuration.klibRelativePathBases,
            ),
            diagnosticReporter = KtDiagnosticReporterWithImplicitIrBasedContext(
                configuration.diagnosticsCollector,
                configuration.languageVersionSettings,
            ),
            builtIns = input.result.irBuiltIns,
            fileFilter = serializedIrFiles::contains,
        ).serializedIrModule(input.result.irModuleFragment)
        check(ir.files.size == serializedIrFiles.size) {
            "The .NET library producer serialized ${ir.files.size} IR files, expected ${serializedIrFiles.size}"
        }
        return input.copy(libraryMetadata = metadata, libraryIr = ir)
    }

    private fun dotNetStdlibSourceBaseDir(sourcePath: String, relativePath: String): String {
        val source = File(sourcePath).absoluteFile.normalize().toPath()
        val relative = File(relativePath).toPath().normalize()
        var base = source
        repeat(relative.nameCount) {
            base = checkNotNull(base.parent) {
                "The Kotlin/.NET stdlib source '$sourcePath' does not match '$relativePath'"
            }
        }
        check(base.resolve(relative).normalize() == source) {
            "The Kotlin/.NET stdlib source '$sourcePath' does not match '$relativePath'"
        }
        return base.toString()
    }
}

/**
 * Produces the private metadata resource from one serialization result and one physical
 * declaration index. The complete packed KLIB is self-bound to its containing DLL.
 */
internal object DotNetLibraryMetadataPackager {
    fun createEmbeddedResource(
        configuration: CompilerConfiguration,
        artifact: DotNetLibraryArtifact,
        metadata: SerializedMetadata,
        ir: SerializedIrModule,
        declarations: Map<String, DotNetPhysicalDeclaration>,
    ): ByteArray {
        val temporaryFile = Files.createTempFile("kotlin-dotnet-metadata-", ".klib")
        Files.delete(temporaryFile)
        return try {
            writeKlib(
                output = temporaryFile,
                configuration = configuration,
                artifact = artifact,
                metadata = metadata,
                ir = ir,
                declarations = declarations,
            )
            loadSizeInfo(temporaryFile.absolute())?.flatten()?.let { stats ->
                configuration.perfManager?.registerKlibElementStats(stats)
            }
            Files.readAllBytes(temporaryFile)
        } finally {
            Files.deleteIfExists(temporaryFile)
        }
    }

    private fun writeKlib(
        output: Path,
        configuration: CompilerConfiguration,
        artifact: DotNetLibraryArtifact,
        metadata: SerializedMetadata,
        ir: SerializedIrModule,
        declarations: Map<String, DotNetPhysicalDeclaration>,
    ) {
        val versions = KotlinLibraryVersioning(
            abiVersion = KotlinAbiVersion.CURRENT,
            compilerVersion = KotlinCompilerVersion.getVersion(),
            metadataVersion = configuration.metadataVersion(),
        )
        KlibWriter {
            format(KlibFormat.ZipArchive)
            manifest {
                moduleName(artifact.assemblyName)
                versions(versions)
                // There is no durable .NET KLIB platform kind yet. The custom target binding
                // below prevents this provisional common encoding from claiming portability.
                platformAndTargets(BuiltInsPlatform.COMMON)
                metadataFlags(configuration.languageVersionSettings)
                customProperties {
                    addLanguageFeaturesToManifest(this, configuration.languageVersionSettings)
                    setProperty(DotNetLibraryArtifact.METADATA_ASSEMBLY_NAME_PROPERTY, artifact.assemblyName)
                    setProperty(DotNetLibraryArtifact.METADATA_ASSEMBLY_VERSION_PROPERTY, artifact.assemblyVersion)
                    setProperty(DotNetLibraryArtifact.METADATA_ASSEMBLY_CULTURE_PROPERTY, artifact.assemblyCulture)
                    setProperty(
                        DotNetLibraryArtifact.METADATA_ASSEMBLY_PUBLIC_KEY_TOKEN_PROPERTY,
                        artifact.assemblyPublicKeyToken,
                    )
                    setProperty(DotNetLibraryArtifact.METADATA_ASSEMBLY_FILE_PROPERTY, artifact.assemblyFileName)
                    setProperty(
                        DotNetLibraryArtifact.METADATA_LIBRARY_TARGET_FRAMEWORK_PROPERTY,
                        artifact.targetFramework,
                    )
                    setProperty(
                        DotNetKotlinMetadataResource.CONTAINER_FORMAT_PROPERTY,
                        DotNetKotlinMetadataResource.EMBEDDED_KLIB_FORMAT,
                    )
                    setProperty(
                        DotNetKotlinMetadataResource.IMPLEMENTATION_BINDING_PROPERTY,
                        DotNetKotlinMetadataResource.SELF_IMPLEMENTATION_BINDING,
                    )
                    setProperty(DotNetLibraryAbiCodec.ABI_VERSION_PROPERTY, DotNetLibraryAbiCodec.ABI_VERSION)
                    setProperty(
                        DotNetLibraryAbiCodec.LOGICAL_IDENTITY_SCHEME_PROPERTY,
                        DotNetLibraryAbiCodec.LOGICAL_IDENTITY_SCHEME,
                    )
                    setProperty(
                        DotNetLibraryAbiCodec.PHYSICAL_NAME_GRAMMAR_VERSION_PROPERTY,
                        DotNetLibraryAbiCodec.PHYSICAL_NAME_GRAMMAR_VERSION,
                    )
                    setProperty(
                        DotNetLibraryAbiCodec.RUNTIME_SURFACE_LEVEL_PROPERTY,
                        DotNetLibraryAbiCodec.CURRENT_RUNTIME_SURFACE_LEVEL.toString(),
                    )
                    setProperty(
                        DotNetLibraryAbiCodec.FRIEND_ASSEMBLIES_PROPERTY,
                        DotNetLibraryAbiCodec.encodeFriendAssemblies(configuration.dotNetFriendAssemblies),
                    )
                    for (entry in DotNetLibraryAbiCodec.encode(declarations)) {
                        setProperty(entry.key, entry.value)
                    }
                }
            }
            includeMetadata(metadata)
            includeIr(ir)
        }.writeTo(output)
    }
}

/** Closes KLIB performance accounting and removes obsolete sidecars after DLL production. */
object DotNetLibraryMetadataFinalizationPipelinePhase :
    PipelinePhase<DotNetBackendPipelineArtifact, DotNetBackendPipelineArtifact>(
        name = "DotNetLibraryMetadataFinalizationPipelinePhase",
        postActions = setOf(PerformanceNotifications.KlibWritingFinished, CheckCompilationErrors.CheckDiagnosticCollector),
    ) {
    override fun executePhase(input: DotNetBackendPipelineArtifact): DotNetBackendPipelineArtifact {
        val configuration = input.configuration
        check(configuration.dotNetProducesStdlib.xor(configuration.dotNetProducesLibrary))
        val artifact = checkNotNull(configuration.dotNetProducedLibraryArtifact)
        val implementationFile = input.output
        if (!implementationFile.isFile || implementationFile.name != artifact.assemblyFileName) return input

        val outputDirectory = configuration.dotNetOutput!!
        outputDirectory.resolve("${artifact.assemblyName}.klib").deleteRecursively()
        outputDirectory.resolve(".${artifact.assemblyName}.klib.tmp").deleteRecursively()
        return input
    }
}
