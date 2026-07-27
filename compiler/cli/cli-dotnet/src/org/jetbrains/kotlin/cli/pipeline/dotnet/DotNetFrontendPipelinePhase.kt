package org.jetbrains.kotlin.cli.pipeline.dotnet

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.CoreEnvironmentDeprecation
import org.jetbrains.kotlin.KtPsiSourceFile
import org.jetbrains.kotlin.KtSourceFile
import org.jetbrains.kotlin.backend.common.loadMetadataKlibs
import org.jetbrains.kotlin.backend.dotnet.DotNetBadImageFormatException
import org.jetbrains.kotlin.backend.dotnet.DotNetManagedAssemblyIdentity
import org.jetbrains.kotlin.backend.dotnet.DotNetManagedResourceReader
import org.jetbrains.kotlin.backend.dotnet.DotNetExternalStdlib
import org.jetbrains.kotlin.backend.dotnet.DotNetExternalLibrary
import org.jetbrains.kotlin.backend.dotnet.DotNetLibraryAbiCodec
import org.jetbrains.kotlin.backend.dotnet.DotNetLibraryArtifact
import org.jetbrains.kotlin.backend.dotnet.DotNetKotlinMetadataResource
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
import java.io.IOException
import java.nio.file.Files

object DotNetFrontendPipelinePhase : PipelinePhase<ConfigurationPipelineArtifact, DotNetFrontendPipelineArtifact>(
    name = "DotNetFrontendPipelinePhase",
    postActions = setOf(PerformanceNotifications.AnalysisFinished, CheckCompilationErrors.CheckDiagnosticCollector),
) {
    override fun executePhase(input: ConfigurationPipelineArtifact): DotNetFrontendPipelineArtifact {
        val (configuration, rootDisposable) = input
        val diagnosticsReporter = configuration.diagnosticsCollector
        val rootModuleName = Name.special("<${configuration.moduleName!!}>")
        val isLightTree = configuration.getBoolean(CommonConfigurationKeys.USE_LIGHT_TREE)
        val preparedLibraries = configuration.prepareDotNetDllLibraries(rootDisposable)
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
            friendDependencies(preparedLibraries.resolvedFriendPaths)
        }
        val klibs: List<KotlinLibrary> = loadMetadataKlibs(
            libraryPaths = libraryPaths,
            configuration = configuration,
        ).all
        configuration.recordExternalDotNetLibraries(klibs, preparedLibraries.embeddedSourceByMetadataFile)
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

private data class DotNetEmbeddedMetadataSource(
    val assemblyFile: File,
    val assemblyIdentity: DotNetManagedAssemblyIdentity,
)

private data class PreparedDotNetLibraries(
    val embeddedSourceByMetadataFile: Map<File, DotNetEmbeddedMetadataSource>,
    val resolvedFriendPaths: List<String>,
)

/**
 * Presents the complete KLIB embedded in a Kotlin-produced DLL to the shared KLIB loader.
 *
 * Extraction is a JVM-hosted implementation detail scoped to one compilation. The published
 * dependency remains the CLR DLL, while Kotlin declaration identity and deserialization continue
 * to use the common KLIB machinery without a second metadata model.
 */
private fun org.jetbrains.kotlin.config.CompilerConfiguration.prepareDotNetDllLibraries(
    rootDisposable: Disposable,
): PreparedDotNetLibraries {
    val extractedByAssembly = linkedMapOf<File, File>()
    val sourceByMetadata = linkedMapOf<File, DotNetEmbeddedMetadataSource>()
    val temporaryFiles = mutableListOf<java.nio.file.Path>()

    fun extract(assemblyFile: File): File? {
        val canonicalAssembly = assemblyFile.canonicalFile
        extractedByAssembly[canonicalAssembly]?.let { return it }
        val resource = try {
            DotNetManagedResourceReader.read(canonicalAssembly, DotNetKotlinMetadataResource.MANAGED_RESOURCE_NAME)
        } catch (exception: DotNetBadImageFormatException) {
            report(COMPILER_ARGUMENTS_ERROR, exception.message ?: "Invalid managed assembly '${assemblyFile.path}'.")
            return null
        }
        if (resource == null) {
            report(
                COMPILER_ARGUMENTS_ERROR,
                "Managed assembly '${assemblyFile.path}' has no private " +
                        "'${DotNetKotlinMetadataResource.MANAGED_RESOURCE_NAME}' Kotlin metadata resource.",
            )
            return null
        }
        if (!resource.isPrivate) {
            report(
                COMPILER_ARGUMENTS_ERROR,
                "Managed assembly '${assemblyFile.path}' exposes " +
                        "'${DotNetKotlinMetadataResource.MANAGED_RESOURCE_NAME}' as a non-private resource.",
            )
            return null
        }
        val metadataPath = try {
            Files.createTempFile("kotlin-dotnet-metadata-", ".klib").also { path ->
                temporaryFiles.add(path)
                Files.write(path, resource.content)
            }
        } catch (exception: IOException) {
            report(
                COMPILER_ARGUMENTS_ERROR,
                "Could not prepare Kotlin metadata from managed assembly '${assemblyFile.path}': ${exception.message}",
            )
            return null
        }
        val metadataFile = metadataPath.toFile().canonicalFile
        extractedByAssembly[canonicalAssembly] = metadataFile
        sourceByMetadata[metadataFile] = DotNetEmbeddedMetadataSource(canonicalAssembly, resource.assemblyIdentity)
        return metadataFile
    }

    contentRoots = contentRoots.mapNotNull { root ->
        val classpathRoot = root as? JvmClasspathRoot ?: return@mapNotNull root
        if (!classpathRoot.file.extension.equals("dll", ignoreCase = true)) return@mapNotNull root
        extract(classpathRoot.file)?.let { metadataFile ->
            JvmClasspathRoot(metadataFile, classpathRoot.isSdkRoot)
        }
    }
    val resolvedFriendPaths = dotNetFriendPaths.map { friendPath ->
        val friendFile = File(friendPath)
        if (!friendFile.extension.equals("dll", ignoreCase = true)) {
            friendPath
        } else {
            extractedByAssembly[friendFile.canonicalFile]?.path ?: friendPath
        }
    }
    if (temporaryFiles.isNotEmpty()) {
        Disposer.register(rootDisposable, Disposable {
            temporaryFiles.forEach { path -> runCatching { Files.deleteIfExists(path) } }
        })
    }
    return PreparedDotNetLibraries(sourceByMetadata, resolvedFriendPaths)
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
            "Kotlin/.NET library '${library.metadataFile.path}' does not declare the supported " +
                    "'${DotNetStdlibArtifact.ASSEMBLY_NAME}' standard-library identity.",
        )
        return
    }
    dotNetExternalStdlib = DotNetExternalStdlib(
        library.metadataFile,
        library.implementationFile,
        artifact.targetFramework,
    )
}

/**
 * Loads every KLIB explicitly produced as a bound Kotlin/.NET library, including stdlib.
 *
 * A transitional sibling KLIB is authenticated by the final DLL hash. An embedded KLIB is
 * authenticated by its private resource location and by matching its declared identity against
 * the containing PE's Assembly row.
 */
private fun org.jetbrains.kotlin.config.CompilerConfiguration.recordExternalDotNetLibraries(
    klibs: List<KotlinLibrary>,
    embeddedSourceByMetadataFile: Map<File, DotNetEmbeddedMetadataSource>,
) {
    val klibByMetadataFile = klibs.associateBy { library -> library.path.toFile().canonicalFile }
    for (entry in embeddedSourceByMetadataFile) {
        val metadataFile = entry.key
        val embeddedSource = entry.value
        val library = klibByMetadataFile[metadataFile]
        if (library == null) {
            report(
                COMPILER_ARGUMENTS_ERROR,
                "Embedded '${DotNetKotlinMetadataResource.MANAGED_RESOURCE_NAME}' in " +
                        "'${embeddedSource.assemblyFile.path}' is not a loadable Kotlin library.",
            )
            return
        }
        if (library.manifestProperties.getProperty(DotNetLibraryAbiCodec.ABI_VERSION_PROPERTY) == null) {
            report(
                COMPILER_ARGUMENTS_ERROR,
                "Embedded Kotlin metadata in '${embeddedSource.assemblyFile.path}' does not declare the " +
                        "Kotlin/.NET CLR ABI contract.",
            )
            return
        }
    }
    val candidates = klibs.filter { library ->
        library.manifestProperties.getProperty(DotNetLibraryAbiCodec.ABI_VERSION_PROPERTY) != null
    }
    if (candidates.isEmpty()) return

    val libraries = mutableListOf<DotNetExternalLibrary>()
    val assemblyNames = hashSetOf<String>()
    val logicalKeys = hashSetOf<String>()
    for (library in candidates) {
        val metadataKlibFile = library.path.toFile().canonicalFile
        val embeddedSource = embeddedSourceByMetadataFile[metadataKlibFile]
        val displayPath = embeddedSource?.assemblyFile?.path ?: library.path.toString()
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
        val expectedContainerFormat = if (embeddedSource == null) {
            DotNetKotlinMetadataResource.SIBLING_KLIB_FORMAT
        } else {
            DotNetKotlinMetadataResource.EMBEDDED_KLIB_FORMAT
        }
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
        val expectedImplementationBinding = if (embeddedSource == null) {
            DotNetKotlinMetadataResource.SIBLING_SHA256_IMPLEMENTATION_BINDING
        } else {
            DotNetKotlinMetadataResource.SELF_IMPLEMENTATION_BINDING
        }
        if (implementationBinding != expectedImplementationBinding) {
            report(
                COMPILER_ARGUMENTS_ERROR,
                "Kotlin/.NET library '$displayPath' declares implementation binding '$implementationBinding', but " +
                        "its physical carrier requires '$expectedImplementationBinding'.",
            )
            return
        }
        val expectedImplementationHash = properties.getProperty(DotNetLibraryAbiCodec.IMPLEMENTATION_SHA256_PROPERTY)
        if (embeddedSource != null && expectedImplementationHash != null) {
            report(
                COMPILER_ARGUMENTS_ERROR,
                "Self-bound Kotlin/.NET library '$displayPath' must not declare the recursive " +
                        "'${DotNetLibraryAbiCodec.IMPLEMENTATION_SHA256_PROPERTY}' property.",
            )
            return
        }
        if (
            embeddedSource == null &&
            (expectedImplementationHash == null || !expectedImplementationHash.matches(Regex("[0-9a-f]{64}")))
        ) {
            report(
                COMPILER_ARGUMENTS_ERROR,
                "Kotlin/.NET library '$displayPath' has invalid implementation SHA-256 " +
                        "'${expectedImplementationHash ?: "<missing>"}'.",
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
        val identityIsSupported = assemblyName == library.uniqueName &&
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
        val metadataFile: File
        val implementationFile: File
        if (embeddedSource != null) {
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
            metadataFile = embeddedSource.assemblyFile
            implementationFile = embeddedSource.assemblyFile
        } else {
            metadataFile = metadataKlibFile
            implementationFile = metadataFile.parentFile.resolve(assemblyFileName)
            if (!implementationFile.isFile) {
                report(
                    COMPILER_ARGUMENTS_ERROR,
                    "Kotlin/.NET metadata '${metadataFile.path}' is bound to missing CLR assembly " +
                            "'${implementationFile.path}'.",
                )
                return
            }
            val actualImplementationHash = DotNetLibraryAbiCodec.implementationSha256(implementationFile)
            if (actualImplementationHash != checkNotNull(expectedImplementationHash)) {
                report(
                    COMPILER_ARGUMENTS_ERROR,
                    "Kotlin/.NET metadata '${metadataFile.path}' is bound to '${implementationFile.path}', but its " +
                            "SHA-256 is $actualImplementationHash instead of $expectedImplementationHash.",
                )
                return
            }
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
 * assembly, mirroring the InternalsVisibleTo row in its CLR implementation.
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
