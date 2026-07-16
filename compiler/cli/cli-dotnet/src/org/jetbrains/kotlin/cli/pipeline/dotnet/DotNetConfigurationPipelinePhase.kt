package org.jetbrains.kotlin.cli.pipeline.dotnet

import org.jetbrains.kotlin.backend.dotnet.DOTNET_STDLIB_SOURCES
import org.jetbrains.kotlin.backend.dotnet.DotNetExport
import org.jetbrains.kotlin.backend.dotnet.DotNetLibraryArtifact
import org.jetbrains.kotlin.backend.dotnet.DotNetPropertyExport
import org.jetbrains.kotlin.backend.dotnet.DotNetStdlibArtifact
import org.jetbrains.kotlin.backend.dotnet.DotNetTarget
import org.jetbrains.kotlin.backend.dotnet.dotNetAssemblyName
import org.jetbrains.kotlin.backend.dotnet.dotNetExports
import org.jetbrains.kotlin.backend.dotnet.dotNetOutput
import org.jetbrains.kotlin.backend.dotnet.dotNetPropertyExports
import org.jetbrains.kotlin.backend.dotnet.dotNetProducesLibrary
import org.jetbrains.kotlin.backend.dotnet.dotNetProducesStdlib
import org.jetbrains.kotlin.backend.dotnet.dotNetTarget
import org.jetbrains.kotlin.cli.CliDiagnostics.COMPILER_ARGUMENTS_ERROR
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.arguments.K2DotNetCompilerArguments
import org.jetbrains.kotlin.cli.common.config.addKotlinSourceRoot
import org.jetbrains.kotlin.cli.common.kotlinPaths
import org.jetbrains.kotlin.cli.jvm.config.JvmClasspathRoot
import org.jetbrains.kotlin.cli.pipeline.AbstractConfigurationPhase
import org.jetbrains.kotlin.cli.pipeline.ArgumentsPipelineArtifact
import org.jetbrains.kotlin.cli.pipeline.CheckCompilationErrors
import org.jetbrains.kotlin.cli.pipeline.ConfigurationUpdater
import org.jetbrains.kotlin.cli.report
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.getModuleNameForSource
import org.jetbrains.kotlin.config.moduleName
import org.jetbrains.kotlin.config.perfManager
import org.jetbrains.kotlin.config.targetPlatform
import org.jetbrains.kotlin.metadata.deserialization.BinaryVersion
import org.jetbrains.kotlin.metadata.deserialization.MetadataVersion
import org.jetbrains.kotlin.platform.DotNetPlatforms
import java.io.File

object DotNetConfigurationPipelinePhase : AbstractConfigurationPhase<K2DotNetCompilerArguments>(
    name = "DotNetConfigurationPipelinePhase",
    postActions = setOf(CheckCompilationErrors.CheckDiagnosticCollector),
    configurationUpdaters = listOf(DotNetConfigurationUpdater),
) {
    override fun createMetadataVersion(versionArray: IntArray): BinaryVersion = MetadataVersion(*versionArray)
}

object DotNetConfigurationUpdater : ConfigurationUpdater<K2DotNetCompilerArguments>() {
    override fun fillConfiguration(
        input: ArgumentsPipelineArtifact<K2DotNetCompilerArguments>,
        configuration: CompilerConfiguration,
    ) {
        val arguments = input.arguments
        configuration.dotNetProducesStdlib = arguments.dotNetProduceStdlib
        configuration.dotNetProducesLibrary = arguments.dotNetProduceLibrary
        if (arguments.dotNetProduceStdlib && arguments.dotNetProduceLibrary) {
            configuration.report(
                COMPILER_ARGUMENTS_ERROR,
                "-Xdotnet-produce-stdlib and -Xdotnet-produce-library are mutually exclusive."
            )
        }
        if (arguments.dotNetProduceStdlib) {
            if (arguments.freeArgs.isNotEmpty()) {
                configuration.report(
                    COMPILER_ARGUMENTS_ERROR,
                    "-Xdotnet-produce-stdlib compiles the compiler-owned bootstrap sources and accepts no user source files."
                )
            }
            if (arguments.noStdlib) {
                configuration.report(
                    COMPILER_ARGUMENTS_ERROR,
                    "-Xdotnet-produce-stdlib cannot be combined with -no-stdlib."
                )
            }
            if (!arguments.dotNetExports.isNullOrEmpty() || !arguments.dotNetPropertyExports.isNullOrEmpty()) {
                configuration.report(
                    COMPILER_ARGUMENTS_ERROR,
                    "-Xdotnet-produce-stdlib cannot be combined with CLR export options."
                )
            }
            if (arguments.moduleName != null && arguments.moduleName != DotNetStdlibArtifact.ASSEMBLY_NAME) {
                configuration.report(
                    COMPILER_ARGUMENTS_ERROR,
                    "-Xdotnet-produce-stdlib owns module name '${DotNetStdlibArtifact.ASSEMBLY_NAME}'."
                )
            }
        }
        if (arguments.dotNetProduceLibrary && arguments.freeArgs.isEmpty()) {
            configuration.report(
                COMPILER_ARGUMENTS_ERROR,
                "-Xdotnet-produce-library requires at least one Kotlin source file."
            )
        }
        val commonSources = arguments.commonSources.toSet()
        val hmppCliModuleStructure = configuration.get(CommonConfigurationKeys.HMPP_MODULE_STRUCTURE)
        for (arg in arguments.freeArgs) {
            configuration.addKotlinSourceRoot(
                path = arg,
                isCommon = arg in commonSources,
                hmppModuleName = hmppCliModuleStructure?.getModuleNameForSource(arg),
            )
        }

        configuration.targetPlatform = DotNetPlatforms.defaultDotNetPlatform

        val requestedTarget = arguments.dotNetTarget
        if (requestedTarget != null) {
            val target = DotNetTarget.fromFlagValue(requestedTarget)
            if (target == null) {
                configuration.report(
                    COMPILER_ARGUMENTS_ERROR,
                    "Unknown value '$requestedTarget' for -Xdotnet-target. " +
                            "Supported values: ${DotNetTarget.entries.joinToString(", ") { it.flagValue }}"
                )
            } else {
                configuration.dotNetTarget = target
            }
        }

        val exports = mutableListOf<DotNetExport>()
        for (rawExport in arguments.dotNetExports.orEmpty()) {
            val export = try {
                DotNetExport.parse(rawExport)
            } catch (e: IllegalArgumentException) {
                configuration.report(
                    COMPILER_ARGUMENTS_ERROR,
                    "Invalid value '$rawExport' for -Xdotnet-export: ${e.message}"
                )
                continue
            }
            if (exports.any { it.kotlinSelector == export.kotlinSelector }) {
                configuration.report(
                    COMPILER_ARGUMENTS_ERROR,
                    "Duplicate -Xdotnet-export target '${export.kotlinSelector}'."
                )
                continue
            }
            exports += export
        }
        configuration.dotNetExports = exports

        val propertyExports = mutableListOf<DotNetPropertyExport>()
        for (rawExport in arguments.dotNetPropertyExports.orEmpty()) {
            val export = try {
                DotNetPropertyExport.parse(rawExport)
            } catch (e: IllegalArgumentException) {
                configuration.report(
                    COMPILER_ARGUMENTS_ERROR,
                    "Invalid value '$rawExport' for -Xdotnet-export-property: ${e.message}"
                )
                continue
            }
            if (propertyExports.any { it.kotlinFqName == export.kotlinFqName }) {
                configuration.report(
                    COMPILER_ARGUMENTS_ERROR,
                    "Duplicate -Xdotnet-export-property target '${export.kotlinFqName}'."
                )
                continue
            }
            propertyExports += export
        }
        configuration.dotNetPropertyExports = propertyExports

        val destination = arguments.destination
        if (destination == null) {
            configuration.report(COMPILER_ARGUMENTS_ERROR, "Specify destination via -d")
        } else {
            val output = File(destination)
            if ((arguments.dotNetProduceStdlib || arguments.dotNetProduceLibrary) &&
                output.exists() && !output.isDirectory
            ) {
                configuration.report(
                    COMPILER_ARGUMENTS_ERROR,
                    "Kotlin/.NET library production requires -d to name an output directory."
                )
            }
            configuration.dotNetOutput = output
        }

        val assemblyName = if (arguments.dotNetProduceStdlib) {
            DotNetStdlibArtifact.ASSEMBLY_NAME
        } else {
            arguments.moduleName
                ?: destination?.let { File(it).nameWithoutExtension.takeIf(String::isNotBlank) }
                ?: "main"
        }
        configuration.moduleName = assemblyName
        configuration.dotNetAssemblyName = assemblyName

        val usesBootstrapStdlibSources = when {
            arguments.dotNetProduceStdlib -> true
            arguments.noStdlib -> false
            configuration.addInstalledDotNetStdlib() -> false
            else -> true
        }
        if (usesBootstrapStdlibSources) {
            configuration.addDotNetStdlibSourceRoots()
        }
        // Only the temporary compiler-owned source corpus needs permission to declare kotlin.*
        // packages. An installed stdlib must not broaden the user's source-package permissions.
        configuration.put(
            CLIConfigurationKeys.ALLOW_KOTLIN_PACKAGE,
            arguments.allowKotlinPackage || usesBootstrapStdlibSources,
        )

        for (path in arguments.classpath?.split(File.pathSeparatorChar).orEmpty()) {
            if (path.isNotEmpty()) {
                configuration.add(CLIConfigurationKeys.CONTENT_ROOTS, JvmClasspathRoot(File(path)))
            }
        }

        configuration.perfManager?.apply {
            outputKind =
                if (arguments.dotNetProduceStdlib || arguments.dotNetProduceLibrary) "KLIB + .NET library" else "IL"
            targetDescription = assemblyName
        }
    }
}

/** Adds the portable stdlib pair installed under `<kotlin-home>/lib/dotnet/netstandard2.0`. */
private fun CompilerConfiguration.addInstalledDotNetStdlib(): Boolean {
    val kotlinLibDirectory = kotlinPaths?.libPath ?: return false
    val directory = DotNetStdlibArtifact.distributionDirectory(kotlinLibDirectory)
    val metadataFile = directory.resolve(DotNetStdlibArtifact.METADATA_FILE_NAME)
    val implementationFile = directory.resolve(DotNetStdlibArtifact.ASSEMBLY_FILE_NAME)
    if (!metadataFile.exists() && !implementationFile.exists()) return false
    if (!metadataFile.isFile || !implementationFile.isFile) {
        report(
            COMPILER_ARGUMENTS_ERROR,
            "Incomplete Kotlin/.NET ${DotNetLibraryArtifact.LIBRARY_TARGET_FRAMEWORK} stdlib installation in '$directory': " +
                    "both ${DotNetStdlibArtifact.METADATA_FILE_NAME} and ${DotNetStdlibArtifact.ASSEMBLY_FILE_NAME} are required.",
        )
        return true
    }
    add(CLIConfigurationKeys.CONTENT_ROOTS, JvmClasspathRoot(metadataFile))
    return true
}

private fun CompilerConfiguration.addDotNetStdlibSourceRoots() {
    // Stable paths (rewritten only when their content is outdated) rather than a fresh
    // Files.createTempDirectory per invocation, so repeated compilations do not accumulate
    // temp directories.
    val stdlibDirectory = File(System.getProperty("java.io.tmpdir")).resolve("kotlinc-dotnet-stdlib")
    for ([fileName, source] in DOTNET_STDLIB_SOURCES) {
        val stdlibSource = stdlibDirectory.resolve(fileName)
        if (!stdlibSource.isFile || stdlibSource.readText() != source) {
            stdlibSource.parentFile.mkdirs()
            stdlibSource.writeText(source)
        }
        addKotlinSourceRoot(stdlibSource.path)
    }
}
