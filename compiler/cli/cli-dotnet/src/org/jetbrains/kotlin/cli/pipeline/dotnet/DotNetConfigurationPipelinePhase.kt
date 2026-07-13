package org.jetbrains.kotlin.cli.pipeline.dotnet

import org.jetbrains.kotlin.backend.dotnet.DOTNET_STDLIB_SOURCES
import org.jetbrains.kotlin.backend.dotnet.DotNetTarget
import org.jetbrains.kotlin.backend.dotnet.dotNetAssemblyName
import org.jetbrains.kotlin.backend.dotnet.dotNetOutput
import org.jetbrains.kotlin.backend.dotnet.dotNetTarget
import org.jetbrains.kotlin.cli.CliDiagnostics.COMPILER_ARGUMENTS_ERROR
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.arguments.K2DotNetCompilerArguments
import org.jetbrains.kotlin.cli.common.config.addKotlinSourceRoot
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
        val commonSources = arguments.commonSources.toSet()
        val hmppCliModuleStructure = configuration.get(CommonConfigurationKeys.HMPP_MODULE_STRUCTURE)
        for (arg in arguments.freeArgs) {
            configuration.addKotlinSourceRoot(
                path = arg,
                isCommon = arg in commonSources,
                hmppModuleName = hmppCliModuleStructure?.getModuleNameForSource(arg),
            )
        }

        // The injected fake stdlib source declares `package kotlin.io`, which is forbidden by default.
        // TODO: this is loose — it also allows *user* code to declare packages in `kotlin.*`.
        configuration.put(CLIConfigurationKeys.ALLOW_KOTLIN_PACKAGE, arguments.allowKotlinPackage || !arguments.noStdlib)
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

        val destination = arguments.destination
        if (destination == null) {
            configuration.report(COMPILER_ARGUMENTS_ERROR, "Specify destination via -d")
        } else {
            configuration.dotNetOutput = File(destination)
        }

        val assemblyName = arguments.moduleName
            ?: destination?.let { File(it).nameWithoutExtension.takeIf(String::isNotBlank) }
            ?: "main"
        configuration.moduleName = assemblyName
        configuration.dotNetAssemblyName = assemblyName

        if (!arguments.noStdlib) {
            configuration.addDotNetStdlibSourceRoots()
        }

        for (path in arguments.classpath?.split(File.pathSeparatorChar).orEmpty()) {
            if (path.isNotEmpty()) {
                configuration.add(CLIConfigurationKeys.CONTENT_ROOTS, JvmClasspathRoot(File(path)))
            }
        }

        configuration.perfManager?.apply {
            outputKind = "IL"
            targetDescription = assemblyName
        }
    }
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
