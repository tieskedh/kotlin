/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.cli.pipeline.dotnet

import org.jetbrains.kotlin.backend.dotnet.DOTNET_STDLIB_SOURCES
import org.jetbrains.kotlin.backend.dotnet.DotNetStdlibArtifact
import org.jetbrains.kotlin.backend.dotnet.dotNetOutput
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

/** Serializes the compiler-owned bootstrap stdlib declarations from the explicit producer run. */
object DotNetStdlibMetadataSerializationPipelinePhase :
    PipelinePhase<DotNetFrontendPipelineArtifact, DotNetFrontendPipelineArtifact>(
        name = "DotNetStdlibMetadataSerializationPipelinePhase",
        preActions = setOf(PerformanceNotifications.KlibWritingStarted),
        postActions = setOf(CheckCompilationErrors.CheckDiagnosticCollector),
    ) {
    override fun executePhase(input: DotNetFrontendPipelineArtifact): DotNetFrontendPipelineArtifact {
        check(input.configuration.dotNetProducesStdlib)
        val outputDirectory = input.configuration.dotNetOutput!!
        outputDirectory.mkdirs()
        outputDirectory.resolve(DotNetStdlibArtifact.METADATA_FILE_NAME).deleteRecursively()

        val configuration = input.configuration
        val metadataVersion = configuration.metadataVersion()
        val fragments = mutableMapOf<String, MutableList<SerializedFirFile>>()
        val serializedSourceNames = mutableSetOf<String>()
        for (output in input.frontendOutput.outputs) {
            val (session, scopeSession, fir) = output
            val languageVersionSettings = configuration.languageVersionSettings
            for (firFile in fir) {
                if (firFile.name !in DOTNET_STDLIB_SOURCES) continue
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
        check(serializedSourceNames == DOTNET_STDLIB_SOURCES.keys) {
            "The stdlib producer resolved ${serializedSourceNames.sorted()}, expected ${DOTNET_STDLIB_SOURCES.keys.sorted()}"
        }

        val header = KlibMetadataProtoBuf.Header.newBuilder().apply {
            moduleName = DotNetStdlibArtifact.METADATA_UNIQUE_NAME
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
        return input.copy(stdlibMetadata = metadata)
    }
}

/** Writes the bound metadata companion only after the CLR stdlib assembly was produced. */
object DotNetStdlibMetadataPackagingPipelinePhase :
    PipelinePhase<DotNetBackendPipelineArtifact, DotNetBackendPipelineArtifact>(
        name = "DotNetStdlibMetadataPackagingPipelinePhase",
        postActions = setOf(PerformanceNotifications.KlibWritingFinished, CheckCompilationErrors.CheckDiagnosticCollector),
    ) {
    override fun executePhase(input: DotNetBackendPipelineArtifact): DotNetBackendPipelineArtifact {
        check(input.configuration.dotNetProducesStdlib)
        val metadata = checkNotNull(input.stdlibMetadata)
        val implementationFile = input.output
        if (!implementationFile.isFile || implementationFile.name != DotNetStdlibArtifact.ASSEMBLY_FILE_NAME) return input

        val configuration = input.configuration
        val outputDirectory = configuration.dotNetOutput!!
        val finalFile = outputDirectory.resolve(DotNetStdlibArtifact.METADATA_FILE_NAME)
        val temporaryFile = outputDirectory.resolve(".${DotNetStdlibArtifact.METADATA_FILE_NAME}.tmp")
        temporaryFile.deleteRecursively()
        val versions = KotlinLibraryVersioning(
            abiVersion = KotlinAbiVersion.CURRENT,
            compilerVersion = KotlinCompilerVersion.getVersion(),
            metadataVersion = configuration.metadataVersion(),
        )
        KlibWriter {
            format(KlibFormat.ZipArchive)
            manifest {
                moduleName(DotNetStdlibArtifact.METADATA_UNIQUE_NAME)
                versions(versions)
                // There is no durable .NET KLIB platform kind yet. The custom target binding
                // below prevents this provisional common encoding from claiming portability.
                platformAndTargets(BuiltInsPlatform.COMMON)
                customProperties {
                    setProperty(DotNetStdlibArtifact.METADATA_ASSEMBLY_NAME_PROPERTY, DotNetStdlibArtifact.ASSEMBLY_NAME)
                    setProperty(DotNetStdlibArtifact.METADATA_ASSEMBLY_VERSION_PROPERTY, DotNetStdlibArtifact.ASSEMBLY_VERSION)
                    setProperty(DotNetStdlibArtifact.METADATA_ASSEMBLY_CULTURE_PROPERTY, DotNetStdlibArtifact.ASSEMBLY_CULTURE)
                    setProperty(
                        DotNetStdlibArtifact.METADATA_ASSEMBLY_PUBLIC_KEY_TOKEN_PROPERTY,
                        DotNetStdlibArtifact.ASSEMBLY_PUBLIC_KEY_TOKEN,
                    )
                    setProperty(DotNetStdlibArtifact.METADATA_ASSEMBLY_FILE_PROPERTY, DotNetStdlibArtifact.ASSEMBLY_FILE_NAME)
                    setProperty(
                        DotNetStdlibArtifact.METADATA_LIBRARY_TARGET_FRAMEWORK_PROPERTY,
                        DotNetStdlibArtifact.LIBRARY_TARGET_FRAMEWORK,
                    )
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
