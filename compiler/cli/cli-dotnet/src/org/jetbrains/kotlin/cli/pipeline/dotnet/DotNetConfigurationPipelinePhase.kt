package org.jetbrains.kotlin.cli.pipeline.dotnet

import org.jetbrains.kotlin.backend.dotnet.DOTNET_STDLIB_SOURCES
import org.jetbrains.kotlin.backend.dotnet.DOTNET_STDLIB_COMMON_SOURCE_NAMES
import org.jetbrains.kotlin.backend.dotnet.DOTNET_STDLIB_SOURCE_PATHS
import org.jetbrains.kotlin.backend.dotnet.DotNetExport
import org.jetbrains.kotlin.backend.dotnet.DotNetFriendAssemblyIdentity
import org.jetbrains.kotlin.backend.dotnet.DotNetLibraryArtifact
import org.jetbrains.kotlin.backend.dotnet.DotNetPropertyExport
import org.jetbrains.kotlin.backend.dotnet.DotNetRuntimeArtifact
import org.jetbrains.kotlin.backend.dotnet.DotNetStdlibArtifact
import org.jetbrains.kotlin.backend.dotnet.dotNetExports
import org.jetbrains.kotlin.backend.dotnet.dotNetFriendAssemblies
import org.jetbrains.kotlin.backend.dotnet.dotNetFriendPaths
import org.jetbrains.kotlin.backend.dotnet.dotNetPropertyExports
import org.jetbrains.kotlin.cli.CliDiagnostics.COMPILER_ARGUMENTS_ERROR
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.arguments.K2DotNetCompilerArguments
import org.jetbrains.kotlin.cli.common.config.addKotlinSourceRoot
import org.jetbrains.kotlin.cli.common.kotlinPaths
import org.jetbrains.kotlin.cli.common.setupCommonKlibArguments
import org.jetbrains.kotlin.cli.dotnet.config.addDotNetClasspathRoot
import org.jetbrains.kotlin.cli.pipeline.AbstractConfigurationPhase
import org.jetbrains.kotlin.cli.pipeline.ArgumentsPipelineArtifact
import org.jetbrains.kotlin.cli.pipeline.CheckCompilationErrors
import org.jetbrains.kotlin.cli.pipeline.ConfigurationUpdater
import org.jetbrains.kotlin.cli.report
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.AnalysisFlag
import org.jetbrains.kotlin.config.AnalysisFlags
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.DotNetTarget
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.config.LanguageVersionSettings
import org.jetbrains.kotlin.config.dotNetAssemblyName
import org.jetbrains.kotlin.config.dotNetMemberReflection
import org.jetbrains.kotlin.config.dotNetOutput
import org.jetbrains.kotlin.config.dotNetProducesLibrary
import org.jetbrains.kotlin.config.dotNetProducesStdlib
import org.jetbrains.kotlin.config.dotNetTarget
import org.jetbrains.kotlin.config.supportsExecutables
import org.jetbrains.kotlin.config.getModuleNameForSource
import org.jetbrains.kotlin.config.languageVersionSettings
import org.jetbrains.kotlin.config.moduleName
import org.jetbrains.kotlin.config.perfManager
import org.jetbrains.kotlin.config.targetPlatform
import org.jetbrains.kotlin.metadata.deserialization.BinaryVersion
import org.jetbrains.kotlin.metadata.deserialization.MetadataVersion
import org.jetbrains.kotlin.platform.dotnet.DotNetPlatforms
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
        configuration.setupCommonKlibArguments(
            arguments,
            canBeMetadataKlibCompilation = false,
            rootDisposable = input.rootDisposable,
        )
        configuration.dotNetProducesStdlib = arguments.dotNetProduceStdlib
        configuration.dotNetProducesLibrary = arguments.dotNetProduceLibrary
        configuration.dotNetMemberReflection = arguments.dotNetReflection
        val commonSources = arguments.commonSources.toSet()
        if (arguments.dotNetProduceStdlib && arguments.dotNetProduceLibrary) {
            configuration.report(
                COMPILER_ARGUMENTS_ERROR,
                "-Xdotnet-produce-stdlib and -Xdotnet-produce-library are mutually exclusive."
            )
        }
        if (arguments.dotNetProduceStdlib) {
            if (arguments.freeArgs.isNotEmpty()) {
                val suppliedSourceNames = arguments.freeArgs.map { File(it).name }
                val suppliedCommonSourceNames = commonSources.mapTo(linkedSetOf()) { File(it).name }
                val duplicateSourceNames = suppliedSourceNames.groupingBy { it }
                    .eachCount()
                    .filterValues { it > 1 }
                    .keys
                if (duplicateSourceNames.isNotEmpty() ||
                    suppliedSourceNames.toSet() != DOTNET_STDLIB_SOURCES.keys ||
                    suppliedCommonSourceNames != DOTNET_STDLIB_COMMON_SOURCE_NAMES
                ) {
                    configuration.report(
                        COMPILER_ARGUMENTS_ERROR,
                        "-Xdotnet-produce-stdlib requires exactly the complete Kotlin/.NET " +
                                "stdlib source set ${DOTNET_STDLIB_SOURCES.keys.sorted()}, with Common sources " +
                                "${DOTNET_STDLIB_COMMON_SOURCE_NAMES.sorted()}; received " +
                                "${suppliedSourceNames.sorted()}, with Common sources " +
                                "${suppliedCommonSourceNames.sorted()}."
                    )
                }
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
            if (arguments.friendPaths.isNotEmpty() || !arguments.dotNetFriendAssemblies.isNullOrEmpty()) {
                configuration.report(
                    COMPILER_ARGUMENTS_ERROR,
                    "-Xdotnet-produce-stdlib cannot be combined with friend-module options."
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
            val target = DotNetTarget.fromString(requestedTarget)
            if (target == null) {
                configuration.report(
                    COMPILER_ARGUMENTS_ERROR,
                    "Unknown value '$requestedTarget' for -Xdotnet-target. " +
                            "Supported values: ${DotNetTarget.entries.joinToString(", ") { it.description }}"
                )
            } else {
                configuration.dotNetTarget = target
            }
        }
        if (!configuration.dotNetTarget.supportsExecutables &&
            !arguments.dotNetProduceStdlib && !arguments.dotNetProduceLibrary
        ) {
            configuration.report(
                COMPILER_ARGUMENTS_ERROR,
                "Target profile '${configuration.dotNetTarget.description}' is library-only; " +
                        "use -Xdotnet-produce-library or -Xdotnet-produce-stdlib."
            )
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

        val friendAssemblies = mutableListOf<DotNetFriendAssemblyIdentity>()
        for (rawIdentity in arguments.dotNetFriendAssemblies.orEmpty()) {
            val identity = try {
                DotNetFriendAssemblyIdentity.parse(rawIdentity)
            } catch (exception: IllegalArgumentException) {
                configuration.report(
                    COMPILER_ARGUMENTS_ERROR,
                    "Invalid value '$rawIdentity' for -Xdotnet-friend-assembly: ${exception.message}"
                )
                continue
            }
            if (friendAssemblies.any { existing ->
                    existing.displayName.equals(identity.displayName, ignoreCase = true)
                }
            ) {
                configuration.report(
                    COMPILER_ARGUMENTS_ERROR,
                    "Duplicate CLR friend assembly identity '${identity.displayName}'."
                )
                continue
            }
            friendAssemblies += identity
        }
        configuration.dotNetFriendAssemblies = friendAssemblies.sortedBy { it.displayName.lowercase() }

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
        if (friendAssemblies.any { it.authorizes(assemblyName) }) {
            configuration.report(
                COMPILER_ARGUMENTS_ERROR,
                "Output assembly '$assemblyName' cannot authorize itself as an unsigned CLR friend."
            )
        }

        val usesBootstrapStdlibSources = when {
            arguments.dotNetProduceStdlib -> arguments.freeArgs.isEmpty()
            arguments.noStdlib -> false
            configuration.addInstalledDotNetStdlib() -> false
            else -> true
        }
        if (usesBootstrapStdlibSources) {
            configuration.addDotNetStdlibSourceRoots()
        }
        // Only the temporary compiler-owned source corpus needs permission to declare kotlin.*
        // packages. An installed stdlib must not broaden the user's source-package permissions.
        val allowsKotlinPackage =
            arguments.allowKotlinPackage || arguments.dotNetProduceStdlib || usesBootstrapStdlibSources
        configuration.put(CLIConfigurationKeys.ALLOW_KOTLIN_PACKAGE, allowsKotlinPackage)
        configuration.languageVersionSettings =
            configuration.languageVersionSettings.withDotNetSourceProductSettings(
                allowKotlinPackage = allowsKotlinPackage,
                enableMultiplatform = usesBootstrapStdlibSources || commonSources.isNotEmpty(),
                muteExpectActualClassesWarning =
                    arguments.dotNetProduceStdlib || usesBootstrapStdlibSources,
                dontWarnOnErrorSuppression =
                    arguments.dotNetProduceStdlib || usesBootstrapStdlibSources,
                optInExperimentalMultiplatform =
                    arguments.dotNetProduceStdlib || usesBootstrapStdlibSources,
                optInExperimentalContracts =
                    arguments.dotNetProduceStdlib || usesBootstrapStdlibSources,
            )

        val classpathFiles = linkedSetOf<File>()
        for (path in arguments.classpath?.split(File.pathSeparatorChar).orEmpty()) {
            if (path.isNotEmpty()) {
                classpathFiles += File(path).canonicalFile
            }
        }
        val friendPaths = arguments.friendPaths.map { File(it).canonicalFile }
        configuration.dotNetFriendPaths = friendPaths.map(File::getPath)
        classpathFiles += friendPaths
        classpathFiles.forEach(configuration::addDotNetClasspathRoot)

        configuration.perfManager?.apply {
            outputKind =
                if (arguments.dotNetProduceStdlib || arguments.dotNetProduceLibrary) ".NET library" else "IL"
            targetDescription = assemblyName
        }
    }
}

private fun LanguageVersionSettings.withDotNetSourceProductSettings(
    allowKotlinPackage: Boolean,
    enableMultiplatform: Boolean,
    muteExpectActualClassesWarning: Boolean,
    dontWarnOnErrorSuppression: Boolean,
    optInExperimentalMultiplatform: Boolean,
    optInExperimentalContracts: Boolean,
): LanguageVersionSettings {
    val delegate = this
    return object : LanguageVersionSettings by delegate {
        override fun getFeatureSupport(feature: LanguageFeature): LanguageFeature.State {
            if (feature == LanguageFeature.MultiPlatformProjects && enableMultiplatform) {
                return LanguageFeature.State.ENABLED
            }
            return delegate.getFeatureSupport(feature)
        }

        override fun supportsFeature(feature: LanguageFeature): Boolean =
            getFeatureSupport(feature) == LanguageFeature.State.ENABLED

        override fun getCustomizedLanguageFeatures(): Map<LanguageFeature, LanguageFeature.State> =
            if (enableMultiplatform) {
                delegate.getCustomizedLanguageFeatures() +
                        (LanguageFeature.MultiPlatformProjects to LanguageFeature.State.ENABLED)
            } else {
                delegate.getCustomizedLanguageFeatures()
            }

        override fun <T> getFlag(flag: AnalysisFlag<T>): T {
            @Suppress("UNCHECKED_CAST")
            if (flag == AnalysisFlags.allowKotlinPackage) return allowKotlinPackage as T
            @Suppress("UNCHECKED_CAST")
            if (flag == AnalysisFlags.muteExpectActualClassesWarning) {
                return (muteExpectActualClassesWarning ||
                        delegate.getFlag(AnalysisFlags.muteExpectActualClassesWarning)) as T
            }
            @Suppress("UNCHECKED_CAST")
            if (flag == AnalysisFlags.dontWarnOnErrorSuppression) {
                return (dontWarnOnErrorSuppression ||
                        delegate.getFlag(AnalysisFlags.dontWarnOnErrorSuppression)) as T
            }
            @Suppress("UNCHECKED_CAST")
            if (
                flag == AnalysisFlags.optIn &&
                (optInExperimentalMultiplatform || optInExperimentalContracts)
            ) {
                val productOptIns = buildList {
                    if (optInExperimentalMultiplatform) add("kotlin.ExperimentalMultiplatform")
                    if (optInExperimentalContracts) add("kotlin.contracts.ExperimentalContracts")
                }
                return (delegate.getFlag(AnalysisFlags.optIn) + productOptIns).distinct() as T
            }
            return delegate.getFlag(flag)
        }
    }
}

/** Adds the best compatible self-describing stdlib DLL for the selected target profile. */
private fun CompilerConfiguration.addInstalledDotNetStdlib(): Boolean {
    val kotlinLibDirectory = kotlinPaths?.libPath ?: return false
    val targetFrameworks = buildList {
        add(dotNetTarget.description)
        if (dotNetTarget != DotNetTarget.NETSTANDARD_2_0) add(DotNetTarget.NETSTANDARD_2_0.description)
    }
    for (targetFramework in targetFrameworks) {
        val directory = DotNetStdlibArtifact.distributionDirectory(kotlinLibDirectory, targetFramework)
        val implementationFile = directory.resolve(DotNetStdlibArtifact.ASSEMBLY_FILE_NAME)
        if (!implementationFile.isFile) continue
        val runtimeFile = directory.resolve(DotNetRuntimeArtifact.ASSEMBLY_FILE_NAME)
        if (!runtimeFile.isFile) {
            report(
                COMPILER_ARGUMENTS_ERROR,
                "Installed Kotlin/.NET platform profile '$targetFramework' is incomplete: " +
                        "'${implementationFile.path}' has no sibling '${DotNetRuntimeArtifact.ASSEMBLY_FILE_NAME}'.",
            )
            return true
        }
        addDotNetClasspathRoot(implementationFile)
        return true
    }
    return false
}

private fun CompilerConfiguration.addDotNetStdlibSourceRoots() {
    // Stable paths (rewritten only when their content is outdated) rather than a fresh
    // Files.createTempDirectory per invocation, so repeated compilations do not accumulate
    // temp directories.
    val stdlibDirectory = File(System.getProperty("java.io.tmpdir")).resolve("kotlinc-dotnet-stdlib")
    for ([fileName, source] in DOTNET_STDLIB_SOURCES) {
        val stdlibSource = stdlibDirectory.resolve(DOTNET_STDLIB_SOURCE_PATHS.getValue(fileName))
        if (!stdlibSource.isFile || stdlibSource.readText() != source) {
            stdlibSource.parentFile.mkdirs()
            stdlibSource.writeText(source)
        }
        addKotlinSourceRoot(
            path = stdlibSource.path,
            isCommon = fileName in DOTNET_STDLIB_COMMON_SOURCE_NAMES,
        )
    }
}
