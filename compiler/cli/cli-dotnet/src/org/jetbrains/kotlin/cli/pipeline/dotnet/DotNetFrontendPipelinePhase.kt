package org.jetbrains.kotlin.cli.pipeline.dotnet

import org.jetbrains.kotlin.CoreEnvironmentDeprecation
import org.jetbrains.kotlin.KtPsiSourceFile
import org.jetbrains.kotlin.KtSourceFile
import org.jetbrains.kotlin.backend.common.loadMetadataKlibs
import org.jetbrains.kotlin.load.dotnet.DotNetBadImageFormatException
import org.jetbrains.kotlin.backend.dotnet.DotNetCSharpImplementationManifestCodec
import org.jetbrains.kotlin.load.dotnet.DotNetClrClasspathAssembly
import org.jetbrains.kotlin.load.dotnet.DotNetClrClasspathAssemblyReader
import org.jetbrains.kotlin.load.dotnet.DotNetManagedAssemblyIdentity
import org.jetbrains.kotlin.backend.dotnet.DotNetExternalStdlib
import org.jetbrains.kotlin.backend.dotnet.DotNetExternalLibrary
import org.jetbrains.kotlin.backend.dotnet.DotNetLibraryAbiCodec
import org.jetbrains.kotlin.backend.dotnet.DotNetLibraryArtifact
import org.jetbrains.kotlin.backend.dotnet.DotNetKotlinMetadataResource
import org.jetbrains.kotlin.backend.dotnet.DotNetPlatformAssemblyIdentity
import org.jetbrains.kotlin.backend.dotnet.DotNetRuntimeArtifact
import org.jetbrains.kotlin.backend.dotnet.DotNetStdlibArtifact
import org.jetbrains.kotlin.load.dotnet.DotNetManagedResourceReader
import org.jetbrains.kotlin.backend.dotnet.dotNetExternalClrAssemblies
import org.jetbrains.kotlin.backend.dotnet.dotNetExternalStdlib
import org.jetbrains.kotlin.backend.dotnet.dotNetExternalLibraries
import org.jetbrains.kotlin.backend.dotnet.dotNetFriendPaths
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
import org.jetbrains.kotlin.cli.dotnet.config.DotNetClasspathRoot
import org.jetbrains.kotlin.cli.pipeline.CheckCompilationErrors
import org.jetbrains.kotlin.cli.pipeline.ConfigurationPipelineArtifact
import org.jetbrains.kotlin.cli.pipeline.PerformanceNotifications
import org.jetbrains.kotlin.cli.pipeline.PipelinePhase
import org.jetbrains.kotlin.cli.report
import org.jetbrains.kotlin.compiler.plugin.getCompilerExtensions
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.config.canConsumeLibrary
import org.jetbrains.kotlin.config.dotNetAssemblyName
import org.jetbrains.kotlin.config.dotNetProducesStdlib
import org.jetbrains.kotlin.config.dotNetTarget
import org.jetbrains.kotlin.config.languageVersionSettings
import org.jetbrains.kotlin.config.moduleName
import org.jetbrains.kotlin.config.perfManager
import org.jetbrains.kotlin.fir.DependencyListForCliModule
import org.jetbrains.kotlin.fir.FirBinaryDependenciesModuleData
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.dotnet.DotNetClrFirSymbolProvider
import org.jetbrains.kotlin.fir.pipeline.AllModulesFrontendOutput
import org.jetbrains.kotlin.fir.pipeline.buildFirFromKtFiles
import org.jetbrains.kotlin.fir.pipeline.buildFirViaLightTree
import org.jetbrains.kotlin.fir.pipeline.resolveAndCheckFir
import org.jetbrains.kotlin.fir.pipeline.runPlatformCheckers
import org.jetbrains.kotlin.fir.session.AdditionalProvidersSupplier
import org.jetbrains.kotlin.library.KLIB_PROPERTY_UNIQUE_NAME
import org.jetbrains.kotlin.library.KotlinLibrary
import org.jetbrains.kotlin.library.loader.loadPackedKlib
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
        if (configuration.dotNetProducesStdlib) {
            check(configuration.languageVersionSettings.supportsFeature(LanguageFeature.MultiPlatformProjects)) {
                "The Kotlin/.NET stdlib Common/actual source product requires multiplatform FIR sessions"
            }
        }
        val rootModuleName = Name.special("<${configuration.moduleName!!}>")
        val isLightTree = configuration.getBoolean(CommonConfigurationKeys.USE_LIGHT_TREE)
        val preparedLibraries = configuration.prepareDotNetDllLibraries()
        configuration.dotNetExternalClrAssemblies = preparedLibraries.foreignAssemblies
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
        val ordinaryKlibs = loadMetadataKlibs(
            libraryPaths = preparedLibraries.ordinaryLibraryPaths,
            configuration = configuration,
        ).all
        val klibs = preparedLibraries.librariesInClasspathOrder(ordinaryKlibs)
        val libraryPaths = klibs.map { library -> library.path.toString() }
        val libraryList = DependencyListForCliModule.build(rootModuleName) {
            dependencies(libraryPaths)
            friendDependencies(preparedLibraries.resolvedFriendPaths)
        }
        configuration.recordExternalDotNetLibraries(klibs, preparedLibraries.embeddedSourceByLibrary)
        configuration.recordExternalDotNetStdlib()
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
                additionalProviders = configuration.dotNetForeignClrProviders(),
                metadataCompilationMode = false,
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
                additionalProviders = configuration.dotNetForeignClrProviders(),
                metadataCompilationMode = false,
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

private fun org.jetbrains.kotlin.config.CompilerConfiguration.dotNetForeignClrProviders() =
    AdditionalProvidersSupplier { session, _, scopeProvider, _ ->
        val assemblies = dotNetExternalClrAssemblies
        if (assemblies.isEmpty()) {
            emptyList()
        } else {
            val moduleData = FirBinaryDependenciesModuleData(
                Name.special("<foreign CLR dependencies>")
            ).apply {
                bindSession(session)
            }
            listOf(
                DotNetClrFirSymbolProvider(
                    session,
                    moduleData,
                    scopeProvider,
                    assemblies,
                )
            )
        }
    }

private data class DotNetEmbeddedMetadataSource(
    val assemblyFile: File,
    val assemblyIdentity: DotNetManagedAssemblyIdentity,
)

private data class PreparedDotNetLibraries(
    val classpathOrder: List<File>,
    val ordinaryLibraryPaths: List<String>,
    val embeddedLibraryByAssembly: Map<File, KotlinLibrary>,
    val embeddedSourceByLibrary: Map<KotlinLibrary, DotNetEmbeddedMetadataSource>,
    val foreignAssemblies: List<DotNetClrClasspathAssembly.WithoutCarrier>,
    val resolvedFriendPaths: List<String>,
) {
    fun librariesInClasspathOrder(ordinaryLibraries: List<KotlinLibrary>): List<KotlinLibrary> {
        val libraryByPath = ordinaryLibraries.associateBy { library -> library.path.toFile().canonicalFile } +
                embeddedLibraryByAssembly
        val seen = hashSetOf<KotlinLibrary>()
        return classpathOrder.mapNotNull(libraryByPath::get).filter(seen::add)
    }
}

/**
 * Presents the metadata KLIB embedded in a Kotlin-produced DLL through the shared KotlinLibrary
 * component contract.
 *
 * The JVM-hosted compiler reads the private resource without loading target code. The physical
 * library path remains the CLR DLL; no temporary KLIB path or second artifact identity is created.
 */
private fun org.jetbrains.kotlin.config.CompilerConfiguration.prepareDotNetDllLibraries(): PreparedDotNetLibraries {
    val embeddedLibraryByAssembly = linkedMapOf<File, KotlinLibrary>()
    val sourceByLibrary = linkedMapOf<KotlinLibrary, DotNetEmbeddedMetadataSource>()
    val classificationByAssembly = linkedMapOf<File, DotNetClrClasspathAssembly>()
    val originalContentRoots = contentRoots
    val classpathOrder = originalContentRoots.mapNotNull { root ->
        (root as? DotNetClasspathRoot)?.file?.canonicalFile
    }
    val ordinaryLibraryPaths = originalContentRoots.mapNotNull { root ->
        val classpathRoot = root as? DotNetClasspathRoot ?: return@mapNotNull null
        classpathRoot.file.path.takeUnless { classpathRoot.file.extension.equals("dll", ignoreCase = true) }
    }

    fun load(assemblyFile: File): KotlinLibrary? {
        val canonicalAssembly = assemblyFile.canonicalFile
        embeddedLibraryByAssembly[canonicalAssembly]?.let { return it }
        val classification = try {
            DotNetClrClasspathAssemblyReader.read(
                canonicalAssembly,
                DotNetKotlinMetadataResource.MANAGED_RESOURCE_NAME,
            )
        } catch (exception: DotNetBadImageFormatException) {
            report(COMPILER_ARGUMENTS_ERROR, exception.message ?: "Invalid managed assembly '${assemblyFile.path}'.")
            return null
        }
        classificationByAssembly[canonicalAssembly] = classification
        if (classification is DotNetClrClasspathAssembly.WithoutCarrier) return null
        val resource = (classification as DotNetClrClasspathAssembly.WithCarrier).carrierResource
        if (!resource.isPrivate) {
            report(
                COMPILER_ARGUMENTS_ERROR,
                "Managed assembly '${assemblyFile.path}' exposes " +
                        "'${DotNetKotlinMetadataResource.MANAGED_RESOURCE_NAME}' as a non-private resource.",
            )
            return null
        }
        val library = try {
            loadPackedKlib(canonicalAssembly.toPath(), resource.content)
        } catch (exception: IllegalArgumentException) {
            report(
                COMPILER_ARGUMENTS_ERROR,
                "Managed assembly '${assemblyFile.path}' has invalid private " +
                        "'${DotNetKotlinMetadataResource.MANAGED_RESOURCE_NAME}' Kotlin metadata: " +
                        exception.message,
            )
            return null
        }
        embeddedLibraryByAssembly[canonicalAssembly] = library
        sourceByLibrary[library] = DotNetEmbeddedMetadataSource(canonicalAssembly, resource.assemblyIdentity)
        return library
    }

    contentRoots = originalContentRoots.filterNot { root ->
        val classpathRoot = root as? DotNetClasspathRoot ?: return@filterNot false
        classpathRoot.file.extension.equals("dll", ignoreCase = true)
    }
    classpathOrder
        .filter { file -> file.extension.equals("dll", ignoreCase = true) }
        .distinct()
        .forEach(::load)
    val resolvedFriendPaths = dotNetFriendPaths.map { friendPath ->
        val friendFile = File(friendPath)
        if (!friendFile.extension.equals("dll", ignoreCase = true)) {
            friendPath
        } else {
            embeddedLibraryByAssembly[friendFile.canonicalFile]?.path?.toString() ?: friendPath
        }
    }
    return PreparedDotNetLibraries(
        classpathOrder,
        ordinaryLibraryPaths,
        embeddedLibraryByAssembly,
        sourceByLibrary,
        classificationByAssembly.values.filterIsInstance<DotNetClrClasspathAssembly.WithoutCarrier>(),
        resolvedFriendPaths,
    )
}

/** Selects the validated Kotlin/.NET stdlib from the complete external-library set. */
private fun org.jetbrains.kotlin.config.CompilerConfiguration.recordExternalDotNetStdlib() {
    val candidates = dotNetExternalLibraries.filter {
        it.artifact.assemblyName == DotNetStdlibArtifact.ASSEMBLY_NAME
    }
    if (candidates.isEmpty()) return
    if (candidates.size != 1) {
        report(
            COMPILER_ARGUMENTS_ERROR,
            "Multiple '${DotNetStdlibArtifact.ASSEMBLY_NAME}' Kotlin/.NET libraries were loaded.",
        )
        return
    }

    val library = candidates.single()
    val artifact = library.artifact
    if (
        artifact.assemblyVersion != DotNetStdlibArtifact.ASSEMBLY_VERSION ||
        artifact.assemblyCulture != DotNetStdlibArtifact.ASSEMBLY_CULTURE ||
        artifact.assemblyPublicKeyToken != DotNetStdlibArtifact.ASSEMBLY_PUBLIC_KEY_TOKEN ||
        library.friendAssemblies.isNotEmpty()
    ) {
        report(
            COMPILER_ARGUMENTS_ERROR,
            "Kotlin/.NET library '${library.assemblyFile.path}' does not declare the supported " +
                    "'${DotNetStdlibArtifact.ASSEMBLY_NAME}' standard-library identity.",
        )
        return
    }
    val runtimeFile = (library.assemblyFile.parentFile ?: File("."))
        .resolve(DotNetRuntimeArtifact.ASSEMBLY_FILE_NAME)
    val runtimeAssemblyFile = runtimeFile.takeIf(File::isFile)?.let { candidate ->
        validateDotNetRuntime(candidate, artifact.targetFramework) ?: return
    }
    dotNetExternalStdlib = DotNetExternalStdlib(
        assemblyFile = library.assemblyFile,
        targetFramework = artifact.targetFramework,
        runtimeAssemblyFile = runtimeAssemblyFile,
    )
}

/**
 * Authenticates the runtime half of a selected platform pair without loading target code.
 *
 * The stdlib KLIB owns Kotlin declaration metadata. The runtime instead publishes the same
 * built-in-derived C# contract used by Roslyn tooling, whose envelope records the producing
 * profile and is bound here to the physical CLR Assembly row.
 */
private fun org.jetbrains.kotlin.config.CompilerConfiguration.validateDotNetRuntime(
    runtimeFile: File,
    targetFramework: String,
): File? {
    val resource = try {
        DotNetManagedResourceReader.read(
            runtimeFile,
            DotNetCSharpImplementationManifestCodec.MANAGED_RESOURCE_NAME,
        )
    } catch (exception: DotNetBadImageFormatException) {
        report(
            COMPILER_ARGUMENTS_ERROR,
            "Kotlin/.NET runtime assembly '${runtimeFile.path}' is invalid: ${exception.message}",
        )
        return null
    }
    if (resource == null) {
        report(
            COMPILER_ARGUMENTS_ERROR,
            "Kotlin/.NET runtime assembly '${runtimeFile.path}' has no public " +
                    "'${DotNetCSharpImplementationManifestCodec.MANAGED_RESOURCE_NAME}' profile contract.",
        )
        return null
    }
    if (!resource.isPublic) {
        report(
            COMPILER_ARGUMENTS_ERROR,
            "Kotlin/.NET runtime assembly '${runtimeFile.path}' has a non-public " +
                    "'${DotNetCSharpImplementationManifestCodec.MANAGED_RESOURCE_NAME}' profile contract.",
        )
        return null
    }
    val manifest = try {
        DotNetCSharpImplementationManifestCodec.decodeManagedResource(resource.content)
    } catch (exception: IllegalArgumentException) {
        report(
            COMPILER_ARGUMENTS_ERROR,
            "Kotlin/.NET runtime assembly '${runtimeFile.path}' has invalid profile metadata: " +
                    exception.message,
        )
        return null
    }
    val identity = resource.assemblyIdentity
    if (
        identity.name != DotNetRuntimeArtifact.ASSEMBLY_NAME ||
        identity.version != DotNetRuntimeArtifact.ASSEMBLY_VERSION ||
        identity.culture != DotNetRuntimeArtifact.ASSEMBLY_CULTURE ||
        identity.hasPublicKey ||
        manifest.assemblyName != identity.name ||
        manifest.targetProfile != targetFramework
    ) {
        report(
            COMPILER_ARGUMENTS_ERROR,
            "Kotlin/.NET runtime assembly '${runtimeFile.path}' does not declare the supported " +
                    "'${DotNetRuntimeArtifact.ASSEMBLY_NAME}' identity for profile '$targetFramework'.",
        )
        return null
    }
    return runtimeFile.canonicalFile
}

/**
 * Loads every self-describing Kotlin/.NET DLL, including stdlib.
 *
 * The embedded KLIB is authenticated by its private resource location and by matching its
 * declared identity against the containing PE's Assembly row. A standalone KLIB with a .NET ABI
 * marker is rejected: it is not a physical Kotlin/.NET library artifact.
 */
private fun org.jetbrains.kotlin.config.CompilerConfiguration.recordExternalDotNetLibraries(
    klibs: List<KotlinLibrary>,
    embeddedSourceByLibrary: Map<KotlinLibrary, DotNetEmbeddedMetadataSource>,
) {
    for ([library, embeddedSource] in embeddedSourceByLibrary) {
        if (library.manifestProperties.getProperty(DotNetLibraryAbiCodec.ABI_VERSION_PROPERTY) == null) {
            report(
                COMPILER_ARGUMENTS_ERROR,
                "Embedded Kotlin metadata in '${embeddedSource.assemblyFile.path}' does not declare the " +
                        "Kotlin/.NET CLR ABI contract.",
            )
            return
        }
    }
    val standaloneDotNetKlib = klibs.firstOrNull { library ->
        library.manifestProperties.getProperty(DotNetLibraryAbiCodec.ABI_VERSION_PROPERTY) != null &&
                library !in embeddedSourceByLibrary
    }
    if (standaloneDotNetKlib != null) {
        report(
            COMPILER_ARGUMENTS_ERROR,
            "Standalone Kotlin/.NET metadata KLIB '${standaloneDotNetKlib.path}' is not supported; " +
                    "supply its self-describing CLR DLL instead.",
        )
        return
    }
    val candidates = klibs.filter { library -> library in embeddedSourceByLibrary }
    if (candidates.isEmpty()) return

    val libraries = mutableListOf<DotNetExternalLibrary>()
    val assemblyNames = hashSetOf<String>()
    val logicalKeys = hashSetOf<String>()
    for (library in candidates) {
        val embeddedSource = checkNotNull(embeddedSourceByLibrary[library])
        val displayPath = embeddedSource.assemblyFile.path
        val properties = library.manifestProperties
        val abiVersion = properties.getProperty(DotNetLibraryAbiCodec.ABI_VERSION_PROPERTY)
        if (abiVersion != DotNetLibraryAbiCodec.ABI_VERSION) {
            report(
                COMPILER_ARGUMENTS_ERROR,
                "Kotlin/.NET library '$displayPath' uses unsupported CLR ABI index version '$abiVersion'.",
            )
            return
        }
        fun required(name: String): String? = properties.getProperty(name)?.takeIf(String::isNotEmpty).also { value ->
            if (value == null) {
                report(COMPILER_ARGUMENTS_ERROR, "Kotlin/.NET library '$displayPath' is missing '$name'.")
            }
        }
        val logicalIdentityScheme = required(DotNetLibraryAbiCodec.LOGICAL_IDENTITY_SCHEME_PROPERTY) ?: return
        if (logicalIdentityScheme != DotNetLibraryAbiCodec.LOGICAL_IDENTITY_SCHEME) {
            report(
                COMPILER_ARGUMENTS_ERROR,
                "Kotlin/.NET library '$displayPath' uses unsupported logical identity scheme " +
                        "'$logicalIdentityScheme'.",
            )
            return
        }
        val physicalNameGrammar = required(DotNetLibraryAbiCodec.PHYSICAL_NAME_GRAMMAR_VERSION_PROPERTY) ?: return
        if (physicalNameGrammar != DotNetLibraryAbiCodec.PHYSICAL_NAME_GRAMMAR_VERSION) {
            report(
                COMPILER_ARGUMENTS_ERROR,
                "Kotlin/.NET library '$displayPath' uses unsupported physical-name grammar version " +
                        "'$physicalNameGrammar'.",
            )
            return
        }
        val runtimeSurfaceLevelText = required(DotNetLibraryAbiCodec.RUNTIME_SURFACE_LEVEL_PROPERTY) ?: return
        val runtimeSurfaceLevel = runtimeSurfaceLevelText.toIntOrNull()
        if (runtimeSurfaceLevel == null || runtimeSurfaceLevel !in 1..DotNetLibraryAbiCodec.CURRENT_RUNTIME_SURFACE_LEVEL) {
            report(
                COMPILER_ARGUMENTS_ERROR,
                "Kotlin/.NET library '$displayPath' requires unsupported Kotlin.Runtime surface level " +
                        "'$runtimeSurfaceLevelText' (compiler supports ${DotNetLibraryAbiCodec.CURRENT_RUNTIME_SURFACE_LEVEL}).",
            )
            return
        }
        val encodedFriendAssemblies = properties.getProperty(DotNetLibraryAbiCodec.FRIEND_ASSEMBLIES_PROPERTY)
        if (encodedFriendAssemblies == null) {
            report(
                COMPILER_ARGUMENTS_ERROR,
                "Kotlin/.NET library '$displayPath' is missing " +
                        "'${DotNetLibraryAbiCodec.FRIEND_ASSEMBLIES_PROPERTY}'.",
            )
            return
        }
        val friendAssemblies = try {
            DotNetLibraryAbiCodec.decodeFriendAssemblies(encodedFriendAssemblies)
        } catch (exception: IllegalArgumentException) {
            report(
                COMPILER_ARGUMENTS_ERROR,
                "Kotlin/.NET library '$displayPath' has invalid CLR friend identities: " +
                        exception.message,
            )
            return
        }
        val containerFormat = required(DotNetKotlinMetadataResource.CONTAINER_FORMAT_PROPERTY) ?: return
        val expectedContainerFormat = DotNetKotlinMetadataResource.EMBEDDED_KLIB_FORMAT
        if (containerFormat != expectedContainerFormat) {
            report(
                COMPILER_ARGUMENTS_ERROR,
                "Kotlin/.NET library '$displayPath' declares metadata container '$containerFormat', but its " +
                        "physical carrier requires '$expectedContainerFormat'.",
            )
            return
        }
        val implementationBinding =
            required(DotNetKotlinMetadataResource.IMPLEMENTATION_BINDING_PROPERTY) ?: return
        val expectedImplementationBinding = DotNetKotlinMetadataResource.SELF_IMPLEMENTATION_BINDING
        if (implementationBinding != expectedImplementationBinding) {
            report(
                COMPILER_ARGUMENTS_ERROR,
                "Kotlin/.NET library '$displayPath' declares implementation binding '$implementationBinding', but " +
                        "its physical carrier requires '$expectedImplementationBinding'.",
            )
            return
        }
        val expectedImplementationHash = properties.getProperty(DotNetLibraryAbiCodec.IMPLEMENTATION_SHA256_PROPERTY)
        if (expectedImplementationHash != null) {
            report(
                COMPILER_ARGUMENTS_ERROR,
                "Self-bound Kotlin/.NET library '$displayPath' must not declare the recursive " +
                        "'${DotNetLibraryAbiCodec.IMPLEMENTATION_SHA256_PROPERTY}' property.",
            )
            return
        }
        val assemblyName = required(DotNetLibraryArtifact.METADATA_ASSEMBLY_NAME_PROPERTY) ?: return
        if (
            assemblyName == DotNetStdlibArtifact.ASSEMBLY_NAME &&
            runtimeSurfaceLevel != DotNetLibraryAbiCodec.CURRENT_RUNTIME_SURFACE_LEVEL
        ) {
            report(
                COMPILER_ARGUMENTS_ERROR,
                "Kotlin/.NET standard library '$displayPath' declares Kotlin.Runtime surface level " +
                        "$runtimeSurfaceLevel, but this compiler requires " +
                        "${DotNetLibraryAbiCodec.CURRENT_RUNTIME_SURFACE_LEVEL}.",
            )
            return
        }
        val platformAssemblyName = DotNetPlatformAssemblyIdentity.canonicalNameOrNull(assemblyName)
        if (platformAssemblyName == DotNetPlatformAssemblyIdentity.RUNTIME_ASSEMBLY_NAME) {
            report(
                COMPILER_ARGUMENTS_ERROR,
                "Kotlin/.NET library '$displayPath' cannot bind declarations to the compiler-owned " +
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
        val uniqueName = required(KLIB_PROPERTY_UNIQUE_NAME) ?: return
        val identityIsSupported = assemblyName == uniqueName &&
                assemblyFileName == "$assemblyName.dll" &&
                java.io.File(assemblyFileName).name == assemblyFileName &&
                assemblyVersion.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+")) &&
                assemblyCulture == DotNetLibraryArtifact.DEFAULT_ASSEMBLY_CULTURE &&
                publicKeyToken == DotNetLibraryArtifact.DEFAULT_ASSEMBLY_PUBLIC_KEY_TOKEN
        if (!identityIsSupported) {
            report(
                COMPILER_ARGUMENTS_ERROR,
                "Kotlin/.NET library '$displayPath' does not declare the supported unsigned CLR assembly identity.",
            )
            return
        }
        if (!dotNetTarget.canConsumeLibrary(targetFramework)) {
            report(
                COMPILER_ARGUMENTS_ERROR,
                "Kotlin/.NET library '$displayPath' targets '$targetFramework', which is not " +
                        "compatible with Kotlin/.NET target '${dotNetTarget.description}'.",
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
                "Kotlin/.NET library '$displayPath' has an invalid CLR declaration index: " +
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
        val physicalIdentity = embeddedSource.assemblyIdentity
        if (
            physicalIdentity.name != assemblyName ||
            physicalIdentity.version != assemblyVersion ||
            physicalIdentity.culture != assemblyCulture ||
            physicalIdentity.hasPublicKey
        ) {
            report(
                COMPILER_ARGUMENTS_ERROR,
                "Embedded Kotlin metadata in '${embeddedSource.assemblyFile.path}' declares CLR assembly " +
                        "'$assemblyName, Version=$assemblyVersion, Culture=$assemblyCulture, " +
                        "PublicKeyToken=$publicKeyToken', but the containing PE declares " +
                        "'${physicalIdentity.name}, Version=${physicalIdentity.version}, " +
                        "Culture=${physicalIdentity.culture}, " +
                        "PublicKeyToken=${if (physicalIdentity.hasPublicKey) "<signed>" else "null"}'.",
            )
            return
        }
        libraries += DotNetExternalLibrary(
            DotNetLibraryArtifact(assemblyName, targetFramework, assemblyVersion, assemblyCulture, publicKeyToken),
            embeddedSource.assemblyFile,
            declarations,
            friendAssemblies,
        )
    }
    dotNetExternalLibraries = libraries
}

/**
 * Verifies both sides of a friend relationship before FIR grants internal source visibility.
 * A path alone is never authority: the producer metadata embedded in the assembly must name this
 * unsigned output assembly, mirroring the InternalsVisibleTo row in the same CLR implementation.
 */
private fun org.jetbrains.kotlin.config.CompilerConfiguration.validateDotNetFriendDependencies() {
    if (dotNetFriendPaths.isEmpty()) return
    val consumerAssemblyName = checkNotNull(dotNetAssemblyName)
    val librariesByPath = dotNetExternalLibraries.associateBy { it.assemblyFile.canonicalFile }
    for (friendPath in dotNetFriendPaths) {
        val canonicalPath = File(friendPath).canonicalFile
        val library = librariesByPath[canonicalPath]
        if (library == null) {
            report(
                COMPILER_ARGUMENTS_ERROR,
                "Kotlin/.NET friend path '$friendPath' is not a validated Kotlin/.NET library.",
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
