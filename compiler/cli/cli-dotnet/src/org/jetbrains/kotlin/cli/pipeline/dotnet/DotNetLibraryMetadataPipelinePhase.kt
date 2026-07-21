/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.cli.pipeline.dotnet

import org.jetbrains.kotlin.backend.dotnet.DOTNET_STDLIB_SOURCES
import org.jetbrains.kotlin.backend.dotnet.DotNetLibraryArtifact
import org.jetbrains.kotlin.backend.dotnet.DotNetLibraryAbiCodec
import org.jetbrains.kotlin.backend.dotnet.dotNetFriendAssemblies
import org.jetbrains.kotlin.backend.dotnet.dotNetOutput
import org.jetbrains.kotlin.backend.dotnet.dotNetProducedLibraryArtifact
import org.jetbrains.kotlin.backend.dotnet.dotNetProducesLibrary
import org.jetbrains.kotlin.backend.dotnet.dotNetProducesStdlib
import org.jetbrains.kotlin.cli.pipeline.CheckCompilationErrors
import org.jetbrains.kotlin.cli.pipeline.PerformanceNotifications
import org.jetbrains.kotlin.cli.pipeline.PipelinePhase
import org.jetbrains.kotlin.config.KotlinCompilerVersion
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.config.languageVersionSettings
import org.jetbrains.kotlin.config.perfManager
import org.jetbrains.kotlin.fir.packageFqName
import org.jetbrains.kotlin.fir.resolve.providers.firProvider
import org.jetbrains.kotlin.fir.serialization.FirKLibSerializerExtension
import org.jetbrains.kotlin.fir.serialization.serializeSingleFirFile
import org.jetbrains.kotlin.library.KlibFormat
import org.jetbrains.kotlin.library.KotlinAbiVersion
import org.jetbrains.kotlin.library.KotlinLibraryVersioning
import org.jetbrains.kotlin.library.SerializedFirFile
import org.jetbrains.kotlin.library.SerializedMetadata
import org.jetbrains.kotlin.library.impl.BuiltInsPlatform
import org.jetbrains.kotlin.library.loadSizeInfo
import org.jetbrains.kotlin.library.metadata.KlibMetadataHeaderFlags
import org.jetbrains.kotlin.library.metadata.KlibMetadataProtoBuf
import org.jetbrains.kotlin.library.writer.KlibWriter
import org.jetbrains.kotlin.library.writer.includeMetadata
import org.jetbrains.kotlin.util.metadataVersion
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.io.path.absolute

/** Serializes the Kotlin declarations paired with one portable CLR implementation assembly. */
object DotNetLibraryMetadataSerializationPipelinePhase :
    PipelinePhase<DotNetFrontendPipelineArtifact, DotNetFrontendPipelineArtifact>(
        name = "DotNetLibraryMetadataSerializationPipelinePhase",
        preActions = setOf(PerformanceNotifications.KlibWritingStarted),
        postActions = setOf(CheckCompilationErrors.CheckDiagnosticCollector),
    ) {
    override fun executePhase(input: DotNetFrontendPipelineArtifact): DotNetFrontendPipelineArtifact {
        val configuration = input.configuration
        check(configuration.dotNetProducesStdlib.xor(configuration.dotNetProducesLibrary))
        val artifact = checkNotNull(configuration.dotNetProducedLibraryArtifact)
        val outputDirectory = configuration.dotNetOutput!!
        outputDirectory.mkdirs()
        outputDirectory.resolve(artifact.metadataFileName).deleteRecursively()

        val metadataVersion = configuration.metadataVersion()
        val fragments = mutableMapOf<String, MutableList<SerializedFirFile>>()
        val serializedSourceNames = mutableSetOf<String>()
        for (output in input.frontendOutput.outputs) {
            val (session, scopeSession, fir) = output
            val languageVersionSettings = configuration.languageVersionSettings
            for (firFile in fir) {
                val isBootstrapStdlibSource = firFile.name in DOTNET_STDLIB_SOURCES
                if (configuration.dotNetProducesStdlib != isBootstrapStdlibSource) continue
                val packageFragment = serializeSingleFirFile(
                    firFile,
                    session,
                    scopeSession,
                    actualizedExpectDeclarations = null,
                    FirKLibSerializerExtension(
                        session,
                        scopeSession,
                        session.firProvider,
                        metadataVersion,
                        exportKDoc = languageVersionSettings.supportsFeature(LanguageFeature.ExportKDocDocumentationToKlib),
                        additionalMetadataProvider = null,
                    ),
                    languageVersionSettings,
                )
                serializedSourceNames += firFile.name
                fragments.getOrPut(firFile.packageFqName.asString()) { mutableListOf() }
                    .add(SerializedFirFile(firFile.name, packageFragment.toByteArray(), firFile.sourceFile?.path))
            }
        }
        if (configuration.dotNetProducesStdlib) {
            check(serializedSourceNames == DOTNET_STDLIB_SOURCES.keys) {
                "The stdlib producer resolved ${serializedSourceNames.sorted()}, expected ${DOTNET_STDLIB_SOURCES.keys.sorted()}"
            }
        } else {
            check(serializedSourceNames.isNotEmpty()) {
                "The library producer did not resolve any user source files"
            }
        }

        val header = KlibMetadataProtoBuf.Header.newBuilder().apply {
            moduleName = artifact.assemblyName
            if (configuration.languageVersionSettings.isPreRelease()) {
                flags = KlibMetadataHeaderFlags.PRE_RELEASE
            }
        }
        val fragmentNames = mutableListOf<String>()
        val fragmentParts = mutableListOf<List<SerializedFirFile>>()
        for ([fqName, fragment] in fragments.entries.sortedBy { it.key }) {
            val orderedFragment = fragment.sortedBy(SerializedFirFile::name)
            fragmentNames += fqName
            fragmentParts += orderedFragment
            header.addPackageFragmentName(fqName)
        }
        val metadata = SerializedMetadata(
            header.build().toByteArray(),
            fragmentParts.map { fragment -> fragment.map { it.content } },
            fragmentNames,
            metadataVersion.toArray(),
        )
        return input.copy(libraryMetadata = metadata)
    }
}

/** Writes the bound metadata companion only after the CLR implementation assembly was produced. */
object DotNetLibraryMetadataPackagingPipelinePhase :
    PipelinePhase<DotNetBackendPipelineArtifact, DotNetBackendPipelineArtifact>(
        name = "DotNetLibraryMetadataPackagingPipelinePhase",
        postActions = setOf(PerformanceNotifications.KlibWritingFinished, CheckCompilationErrors.CheckDiagnosticCollector),
    ) {
    override fun executePhase(input: DotNetBackendPipelineArtifact): DotNetBackendPipelineArtifact {
        val configuration = input.configuration
        check(configuration.dotNetProducesStdlib.xor(configuration.dotNetProducesLibrary))
        val artifact = checkNotNull(configuration.dotNetProducedLibraryArtifact)
        val metadata = checkNotNull(input.libraryMetadata)
        val implementationFile = input.output
        if (!implementationFile.isFile || implementationFile.name != artifact.assemblyFileName) return input

        val outputDirectory = configuration.dotNetOutput!!
        val finalFile = outputDirectory.resolve(artifact.metadataFileName)
        val temporaryFile = outputDirectory.resolve(".${artifact.metadataFileName}.tmp")
        temporaryFile.deleteRecursively()
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
                customProperties {
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
                    // Both explicit library products and the target-stdlib product pair Kotlin
                    // metadata with a CLR implementation. Persist the same physical declaration
                    // index for both; otherwise ordinary stdlib functions can resolve in FIR but
                    // have no durable cross-module CLR owner/method binding.
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
                    setProperty(
                        DotNetLibraryAbiCodec.IMPLEMENTATION_SHA256_PROPERTY,
                        DotNetLibraryAbiCodec.implementationSha256(implementationFile),
                    )
                    for (entry in DotNetLibraryAbiCodec.encode(input.declarations)) {
                        setProperty(entry.key, entry.value)
                    }
                }
            }
            includeMetadata(metadata)
        }.writeTo(temporaryFile.toPath())

        finalFile.deleteRecursively()
        try {
            Files.move(
                temporaryFile.toPath(),
                finalFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporaryFile.toPath(), finalFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        loadSizeInfo(finalFile.toPath().absolute())?.flatten()?.let { stats ->
            configuration.perfManager?.registerKlibElementStats(stats)
        }
        return input
    }
}
