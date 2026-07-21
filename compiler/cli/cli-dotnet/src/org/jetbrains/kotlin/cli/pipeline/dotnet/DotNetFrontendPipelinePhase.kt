package org.jetbrains.kotlin.cli.pipeline.dotnet

import org.jetbrains.kotlin.CoreEnvironmentDeprecation
import org.jetbrains.kotlin.KtPsiSourceFile
import org.jetbrains.kotlin.KtSourceFile
import org.jetbrains.kotlin.backend.common.loadMetadataKlibs
import org.jetbrains.kotlin.backend.dotnet.DotNetExternalStdlib
import org.jetbrains.kotlin.backend.dotnet.DotNetExternalLibrary
import org.jetbrains.kotlin.backend.dotnet.DotNetLibraryAbiCodec
import org.jetbrains.kotlin.backend.dotnet.DotNetLibraryArtifact
import org.jetbrains.kotlin.backend.dotnet.DotNetPlatformAssemblyIdentity
import org.jetbrains.kotlin.backend.dotnet.DotNetStdlibArtifact
import org.jetbrains.kotlin.backend.dotnet.dotNetExternalStdlib
import org.jetbrains.kotlin.backend.dotnet.dotNetExternalLibraries
import org.jetbrains.kotlin.backend.dotnet.dotNetAssemblyName
import org.jetbrains.kotlin.backend.dotnet.dotNetFriendPaths
import org.jetbrains.kotlin.backend.dotnet.dotNetTarget
import org.jetbrains.kotlin.cli.CliDiagnostics.COMPILER_ARGUMENTS_ERROR
import org.jetbrains.kotlin.cli.common.collectSources
import org.jetbrains.kotlin.cli.common.contentRoots
import org.jetbrains.kotlin.cli.common.diagnosticsCollector
import org.jetbrains.kotlin.cli.common.fileBelongsToModuleForPsi
import org.jetbrains.kotlin.cli.common.isCommonSourceForPsi
import org.jetbrains.kotlin.cli.common.isCommonSourceForLt
import org.jetbrains.kotlin.cli.common.fileBelongsToModuleForLt
import org.jetbrains.kotlin.cli.common.messages.SyntaxErrorReporter
import org.jetbrains.kotlin.cli.common.prepareMetadataSessions
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.jvm.compiler.toVfsBasedProjectEnvironment
import org.jetbrains.kotlin.cli.jvm.config.JvmClasspathRoot
import org.jetbrains.kotlin.cli.pipeline.CheckCompilationErrors
import org.jetbrains.kotlin.cli.pipeline.ConfigurationPipelineArtifact
import org.jetbrains.kotlin.cli.pipeline.PerformanceNotifications
import org.jetbrains.kotlin.cli.pipeline.PipelinePhase
import org.jetbrains.kotlin.cli.report
import org.jetbrains.kotlin.compiler.plugin.getCompilerExtensions
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.moduleName
import org.jetbrains.kotlin.config.perfManager
import org.jetbrains.kotlin.fir.DependencyListForCliModule
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.pipeline.AllModulesFrontendOutput
import org.jetbrains.kotlin.fir.pipeline.buildFirFromKtFiles
import org.jetbrains.kotlin.fir.pipeline.buildFirViaLightTree
import org.jetbrains.kotlin.fir.pipeline.resolveAndCheckFir
import org.jetbrains.kotlin.fir.pipeline.runPlatformCheckers
import org.jetbrains.kotlin.library.KotlinLibrary
import org.jetbrains.kotlin.library.uniqueName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.util.PhaseType
import org.jetbrains.kotlin.util.PotentiallyIncorrectPhaseTimeMeasurement
import java.io.File

object DotNetFrontendPipelinePhase : PipelinePhase<ConfigurationPipelineArtifact, DotNetFrontendPipelineArtifact>(
    name = "DotNetFrontendPipelinePhase",
    postActions = setOf(PerformanceNotifications.AnalysisFinished, CheckCompilationErrors.CheckDiagnosticCollector),
) {
    override fun executePhase(input: ConfigurationPipelineArtifact): DotNetFrontendPipelineArtifact {
        val (configuration, rootDisposable) = input
        val diagnosticsReporter = configuration.diagnosticsCollector
        val rootModuleName = Name.special("<${configuration.moduleName!!}>")
        val isLightTree = configuration.getBoolean(CommonConfigurationKeys.USE_LIGHT_TREE)
        @OptIn(CoreEnvironmentDeprecation::class)
        val environment = KotlinCoreEnvironment.createForProduction(
            rootDisposable,
            configuration,
            EnvironmentConfigFiles.METADATA_CONFIG_FILES,
        )

        configuration.perfManager?.let {
            @OptIn(PotentiallyIncorrectPhaseTimeMeasurement::class)
            it.notifyCurrentPhaseFinishedIfNeeded()
            it.notifyPhaseStarted(PhaseType.Analysis)
        }

        val projectEnvironment = environment.toVfsBasedProjectEnvironment()
        val librariesScope = projectEnvironment.getSearchScopeForProjectLibraries()
        val libraryPaths = configuration.contentRoots.mapNotNull { (it as? JvmClasspathRoot)?.file?.path }
        val libraryList = DependencyListForCliModule.build(rootModuleName) {
            dependencies(libraryPaths)
            friendDependencies(configuration.dotNetFriendPaths)
        }
        val klibs: List<KotlinLibrary> = loadMetadataKlibs(
            libraryPaths = libraryPaths,
            configuration = configuration,
        ).all
        configuration.recordExternalDotNetStdlib(klibs)
        configuration.recordExternalDotNetLibraries(klibs)
        configuration.validateDotNetFriendDependencies()
        val extensionRegistrars = configuration.getCompilerExtensions(FirExtensionRegistrar)

        val sourceFiles: List<KtSourceFile>
        val outputs = if (isLightTree) {
            val groupedSources = collectSources(configuration, projectEnvironment)
            val lightTreeFiles = (groupedSources.commonSources + groupedSources.platformSources).toList().also {
                sourceFiles = it
            }
            val sessionsWithSources = prepareMetadataSessions(
                lightTreeFiles,
                configuration,
                projectEnvironment,
                rootModuleName,
                extensionRegistrars,
                librariesScope,
                libraryList,
                klibs,
                groupedSources.isCommonSourceForLt,
                groupedSources.fileBelongsToModuleForLt,
                incrementalCompilationContext = null,
            )
            sessionsWithSources.map { (session, files) ->
                val firFiles = session.buildFirViaLightTree(files, diagnosticsReporter) { filesCount, lines ->
                    configuration.perfManager?.addSourcesStats(filesCount, lines)
                }
                resolveAndCheckFir(session, firFiles, diagnosticsReporter)
            }
        } else {
            val ktFiles = environment.getSourceFiles().also { ktFiles ->
                configuration.perfManager?.addSourcesStats(ktFiles.size, environment.countLinesOfCode(ktFiles))
                sourceFiles = ktFiles.map { KtPsiSourceFile(it) }
            }
            for (ktFile in ktFiles) {
                SyntaxErrorReporter.reportSyntaxErrors(ktFile, diagnosticsReporter)
            }
            val sessionsWithSources = prepareMetadataSessions(
                ktFiles,
                configuration,
                projectEnvironment,
                rootModuleName,
                extensionRegistrars,
                librariesScope,
                libraryList,
                klibs,
                isCommonSourceForPsi,
                fileBelongsToModuleForPsi,
                incrementalCompilationContext = null,
            )
            sessionsWithSources.map { (session, files) ->
                val firFiles = session.buildFirFromKtFiles(files)
                resolveAndCheckFir(session, firFiles, diagnosticsReporter)
            }
        }

        outputs.runPlatformCheckers(diagnosticsReporter)

        // Frontend errors must NOT make this phase return null: the artifact is returned so the
        // CheckDiagnosticCollector post-action (and, in tests, FIR handlers such as
        // NoFirCompilationErrorsHandler) can render the actual diagnostics, mirroring
        // JvmFrontendPipelinePhase. Kotlin-package usage is enforced by FirKotlinPackageChecker
        // via the analysis flag set in DotNetConfigurationUpdater.
        return DotNetFrontendPipelineArtifact(AllModulesFrontendOutput(outputs), configuration, sourceFiles)
    }
}

/**
 * Recognizes an explicit metadata-KLIB/CLR-DLL pair without turning arbitrary classpath entries
 * into physical CLR references. The KLIB manifest binds Kotlin declarations to one stable,
 * portable assembly companion; ordinary metadata KLIBs remain compile-time-only inputs.
 */
private fun org.jetbrains.kotlin.config.CompilerConfiguration.recordExternalDotNetStdlib(
    klibs: List<KotlinLibrary>,
) {
    val candidates = klibs.filter { it.uniqueName == DotNetStdlibArtifact.METADATA_UNIQUE_NAME }
    if (candidates.isEmpty()) return
    if (candidates.size != 1) {
        report(
            COMPILER_ARGUMENTS_ERROR,
            "Multiple '${DotNetStdlibArtifact.METADATA_UNIQUE_NAME}' metadata libraries were loaded.",
        )
        return
    }

    val library = candidates.single()
    val properties = library.manifestProperties
    val expectedProperties = mapOf(
        DotNetLibraryAbiCodec.ABI_VERSION_PROPERTY to DotNetLibraryAbiCodec.ABI_VERSION,
        DotNetLibraryAbiCodec.LOGICAL_IDENTITY_SCHEME_PROPERTY to DotNetLibraryAbiCodec.LOGICAL_IDENTITY_SCHEME,
        DotNetLibraryAbiCodec.PHYSICAL_NAME_GRAMMAR_VERSION_PROPERTY to
                DotNetLibraryAbiCodec.PHYSICAL_NAME_GRAMMAR_VERSION,
        DotNetLibraryAbiCodec.RUNTIME_SURFACE_LEVEL_PROPERTY to
                DotNetLibraryAbiCodec.CURRENT_RUNTIME_SURFACE_LEVEL.toString(),
        DotNetLibraryAbiCodec.FRIEND_ASSEMBLIES_PROPERTY to "",
        DotNetLibraryArtifact.METADATA_ASSEMBLY_NAME_PROPERTY to DotNetStdlibArtifact.ASSEMBLY_NAME,
        DotNetLibraryArtifact.METADATA_ASSEMBLY_VERSION_PROPERTY to DotNetStdlibArtifact.ASSEMBLY_VERSION,
        DotNetLibraryArtifact.METADATA_ASSEMBLY_CULTURE_PROPERTY to DotNetStdlibArtifact.ASSEMBLY_CULTURE,
        DotNetLibraryArtifact.METADATA_ASSEMBLY_PUBLIC_KEY_TOKEN_PROPERTY to
                DotNetStdlibArtifact.ASSEMBLY_PUBLIC_KEY_TOKEN,
        DotNetLibraryArtifact.METADATA_ASSEMBLY_FILE_PROPERTY to DotNetStdlibArtifact.ASSEMBLY_FILE_NAME,
    )
    val mismatch = expectedProperties.entries.firstOrNull { entry ->
        properties.getProperty(entry.key) != entry.value
    }
    if (mismatch != null) {
        report(
            COMPILER_ARGUMENTS_ERROR,
            "Metadata library '${library.path}' must declare ${mismatch.key}=${mismatch.value} to bind it to the " +
                    "requested Kotlin/.NET stdlib assembly.",
        )
        return
    }
    val targetFramework = properties.getProperty(DotNetLibraryArtifact.METADATA_LIBRARY_TARGET_FRAMEWORK_PROPERTY)
    if (targetFramework == null || !dotNetTarget.canConsumeLibrary(targetFramework)) {
        report(
            COMPILER_ARGUMENTS_ERROR,
            "Metadata library '${library.path}' targets '${targetFramework ?: "<missing>"}', which is not " +
                    "compatible with Kotlin/.NET target '${dotNetTarget.flagValue}'.",
        )
        return
    }

    val metadataFile = library.path.toFile()
    val implementationFile = metadataFile.parentFile.resolve(DotNetStdlibArtifact.ASSEMBLY_FILE_NAME)
    dotNetExternalStdlib = DotNetExternalStdlib(metadataFile, implementationFile, targetFramework)
}

/** Loads every KLIB explicitly produced as a bound Kotlin/.NET library pair, including stdlib. */
private fun org.jetbrains.kotlin.config.CompilerConfiguration.recordExternalDotNetLibraries(
    klibs: List<KotlinLibrary>,
) {
    val candidates = klibs.filter { library ->
        library.manifestProperties.getProperty(DotNetLibraryAbiCodec.ABI_VERSION_PROPERTY) != null
    }
    if (candidates.isEmpty()) return

    val libraries = mutableListOf<DotNetExternalLibrary>()
    val assemblyNames = hashSetOf<String>()
    val logicalKeys = hashSetOf<String>()
    for (library in candidates) {
        val properties = library.manifestProperties
        val abiVersion = properties.getProperty(DotNetLibraryAbiCodec.ABI_VERSION_PROPERTY)
        if (abiVersion != DotNetLibraryAbiCodec.ABI_VERSION) {
            report(
                COMPILER_ARGUMENTS_ERROR,
                "Kotlin/.NET metadata library '${library.path}' uses unsupported CLR ABI index version '$abiVersion'.",
            )
            return
        }
        fun required(name: String): String? = properties.getProperty(name)?.takeIf(String::isNotEmpty).also { value ->
            if (value == null) {
                report(COMPILER_ARGUMENTS_ERROR, "Kotlin/.NET metadata library '${library.path}' is missing '$name'.")
            }
        }
        val logicalIdentityScheme = required(DotNetLibraryAbiCodec.LOGICAL_IDENTITY_SCHEME_PROPERTY) ?: return
        if (logicalIdentityScheme != DotNetLibraryAbiCodec.LOGICAL_IDENTITY_SCHEME) {
            report(
                COMPILER_ARGUMENTS_ERROR,
                "Kotlin/.NET metadata library '${library.path}' uses unsupported logical identity scheme " +
                        "'$logicalIdentityScheme'.",
            )
            return
        }
        val physicalNameGrammar = required(DotNetLibraryAbiCodec.PHYSICAL_NAME_GRAMMAR_VERSION_PROPERTY) ?: return
        if (physicalNameGrammar != DotNetLibraryAbiCodec.PHYSICAL_NAME_GRAMMAR_VERSION) {
            report(
                COMPILER_ARGUMENTS_ERROR,
                "Kotlin/.NET metadata library '${library.path}' uses unsupported physical-name grammar version " +
                        "'$physicalNameGrammar'.",
            )
            return
        }
        val runtimeSurfaceLevelText = required(DotNetLibraryAbiCodec.RUNTIME_SURFACE_LEVEL_PROPERTY) ?: return
        val runtimeSurfaceLevel = runtimeSurfaceLevelText.toIntOrNull()
        if (runtimeSurfaceLevel == null || runtimeSurfaceLevel !in 1..DotNetLibraryAbiCodec.CURRENT_RUNTIME_SURFACE_LEVEL) {
            report(
                COMPILER_ARGUMENTS_ERROR,
                "Kotlin/.NET metadata library '${library.path}' requires unsupported Kotlin.Runtime surface level " +
                        "'$runtimeSurfaceLevelText' (compiler supports ${DotNetLibraryAbiCodec.CURRENT_RUNTIME_SURFACE_LEVEL}).",
            )
            return
        }
        val encodedFriendAssemblies = properties.getProperty(DotNetLibraryAbiCodec.FRIEND_ASSEMBLIES_PROPERTY)
        if (encodedFriendAssemblies == null) {
            report(
                COMPILER_ARGUMENTS_ERROR,
                "Kotlin/.NET metadata library '${library.path}' is missing " +
                        "'${DotNetLibraryAbiCodec.FRIEND_ASSEMBLIES_PROPERTY}'.",
            )
            return
        }
        val friendAssemblies = try {
            DotNetLibraryAbiCodec.decodeFriendAssemblies(encodedFriendAssemblies)
        } catch (exception: IllegalArgumentException) {
            report(
                COMPILER_ARGUMENTS_ERROR,
                "Kotlin/.NET metadata library '${library.path}' has invalid CLR friend identities: " +
                        exception.message,
            )
            return
        }
        val expectedImplementationHash = required(DotNetLibraryAbiCodec.IMPLEMENTATION_SHA256_PROPERTY) ?: return
        if (!expectedImplementationHash.matches(Regex("[0-9a-f]{64}"))) {
            report(
                COMPILER_ARGUMENTS_ERROR,
                "Kotlin/.NET metadata library '${library.path}' has invalid implementation SHA-256 " +
                        "'$expectedImplementationHash'.",
            )
            return
        }
        val assemblyName = required(DotNetLibraryArtifact.METADATA_ASSEMBLY_NAME_PROPERTY) ?: return
        val platformAssemblyName = DotNetPlatformAssemblyIdentity.canonicalNameOrNull(assemblyName)
        if (platformAssemblyName == DotNetPlatformAssemblyIdentity.RUNTIME_ASSEMBLY_NAME) {
            report(
                COMPILER_ARGUMENTS_ERROR,
                "Kotlin/.NET metadata library '${library.path}' cannot bind declarations to the compiler-owned " +
                        "runtime assembly '$platformAssemblyName'.",
            )
            return
        }
        if (platformAssemblyName != null && assemblyName != platformAssemblyName) {
            report(
                COMPILER_ARGUMENTS_ERROR,
                "Kotlin/.NET platform assembly '$assemblyName' must use canonical name '$platformAssemblyName'.",
            )
            return
        }
        val assemblyVersion = required(DotNetLibraryArtifact.METADATA_ASSEMBLY_VERSION_PROPERTY) ?: return
        val assemblyCulture = required(DotNetLibraryArtifact.METADATA_ASSEMBLY_CULTURE_PROPERTY) ?: return
        val publicKeyToken = required(DotNetLibraryArtifact.METADATA_ASSEMBLY_PUBLIC_KEY_TOKEN_PROPERTY) ?: return
        val assemblyFileName = required(DotNetLibraryArtifact.METADATA_ASSEMBLY_FILE_PROPERTY) ?: return
        val targetFramework = required(DotNetLibraryArtifact.METADATA_LIBRARY_TARGET_FRAMEWORK_PROPERTY) ?: return
        val identityIsSupported = assemblyName == library.uniqueName &&
                assemblyFileName == "$assemblyName.dll" &&
                java.io.File(assemblyFileName).name == assemblyFileName &&
                assemblyVersion.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+")) &&
                assemblyCulture == DotNetLibraryArtifact.DEFAULT_ASSEMBLY_CULTURE &&
                publicKeyToken == DotNetLibraryArtifact.DEFAULT_ASSEMBLY_PUBLIC_KEY_TOKEN
        if (!identityIsSupported) {
            report(
                COMPILER_ARGUMENTS_ERROR,
                "Kotlin/.NET metadata library '${library.path}' does not declare the supported unsigned " +
                        "sibling-assembly identity.",
            )
            return
        }
        if (!dotNetTarget.canConsumeLibrary(targetFramework)) {
            report(
                COMPILER_ARGUMENTS_ERROR,
                "Kotlin/.NET metadata library '${library.path}' targets '$targetFramework', which is not " +
                        "compatible with Kotlin/.NET target '${dotNetTarget.flagValue}'.",
            )
            return
        }
        if (!assemblyNames.add(assemblyName.lowercase())) {
            report(COMPILER_ARGUMENTS_ERROR, "Multiple Kotlin/.NET libraries declare assembly '$assemblyName'.")
            return
        }
        val declarations = try {
            DotNetLibraryAbiCodec.decode(properties)
        } catch (exception: IllegalArgumentException) {
            report(
                COMPILER_ARGUMENTS_ERROR,
                "Kotlin/.NET metadata library '${library.path}' has an invalid CLR declaration index: " +
                        exception.message,
            )
            return
        }
        val duplicateLogicalKey = declarations.keys.firstOrNull { !logicalKeys.add(it) }
        if (duplicateLogicalKey != null) {
            report(
                COMPILER_ARGUMENTS_ERROR,
                "Multiple Kotlin/.NET libraries bind declaration '$duplicateLogicalKey'.",
            )
            return
        }
        val metadataFile = library.path.toFile()
        val implementationFile = metadataFile.parentFile.resolve(assemblyFileName)
        if (!implementationFile.isFile) {
            report(
                COMPILER_ARGUMENTS_ERROR,
                "Kotlin/.NET metadata '${metadataFile.path}' is bound to missing CLR assembly " +
                        "'${implementationFile.path}'.",
            )
            return
        }
        val actualImplementationHash = DotNetLibraryAbiCodec.implementationSha256(implementationFile)
        if (actualImplementationHash != expectedImplementationHash) {
            report(
                COMPILER_ARGUMENTS_ERROR,
                "Kotlin/.NET metadata '${metadataFile.path}' is bound to '${implementationFile.path}', but its " +
                        "SHA-256 is $actualImplementationHash instead of $expectedImplementationHash.",
            )
            return
        }
        libraries += DotNetExternalLibrary(
            DotNetLibraryArtifact(assemblyName, targetFramework, assemblyVersion, assemblyCulture, publicKeyToken),
            metadataFile,
            implementationFile,
            declarations,
            friendAssemblies,
        )
    }
    dotNetExternalLibraries = libraries
}

/**
 * Verifies both halves of a friend relationship before FIR grants internal source visibility.
 * A path alone is never authority: the bound producer metadata must name this unsigned output
 * assembly, mirroring the InternalsVisibleTo row in its sibling CLR implementation.
 */
private fun org.jetbrains.kotlin.config.CompilerConfiguration.validateDotNetFriendDependencies() {
    if (dotNetFriendPaths.isEmpty()) return
    val consumerAssemblyName = checkNotNull(dotNetAssemblyName)
    val librariesByPath = dotNetExternalLibraries.associateBy { it.metadataFile.canonicalFile }
    for (friendPath in dotNetFriendPaths) {
        val canonicalPath = File(friendPath).canonicalFile
        val library = librariesByPath[canonicalPath]
        if (library == null) {
            report(
                COMPILER_ARGUMENTS_ERROR,
                "Kotlin/.NET friend path '$friendPath' is not a bound Kotlin/.NET metadata-KLIB/CLR-DLL pair.",
            )
            continue
        }
        if (library.friendAssemblies.none { it.authorizes(consumerAssemblyName) }) {
            report(
                COMPILER_ARGUMENTS_ERROR,
                "Kotlin/.NET friend producer '${library.artifact.assemblyName}' does not authorize unsigned " +
                        "consumer assembly '$consumerAssemblyName' through InternalsVisibleTo.",
            )
        }
    }
}
