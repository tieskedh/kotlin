package org.jetbrains.kotlin.cli.pipeline.dotnet

import org.jetbrains.kotlin.KtPsiSourceFile
import org.jetbrains.kotlin.KtSourceFile
import org.jetbrains.kotlin.backend.common.loadMetadataKlibs
import org.jetbrains.kotlin.cli.common.checkKotlinPackageUsageForLightTree
import org.jetbrains.kotlin.cli.common.checkKotlinPackageUsageForPsi
import org.jetbrains.kotlin.cli.common.collectSources
import org.jetbrains.kotlin.cli.common.contentRoots
import org.jetbrains.kotlin.cli.common.diagnosticsCollector
import org.jetbrains.kotlin.cli.common.fileBelongsToModuleForPsi
import org.jetbrains.kotlin.cli.common.isCommonSourceForPsi
import org.jetbrains.kotlin.cli.common.isCommonSourceForLt
import org.jetbrains.kotlin.cli.common.fileBelongsToModuleForLt
import org.jetbrains.kotlin.cli.common.messages.AnalyzerWithCompilerReport
import org.jetbrains.kotlin.cli.common.prepareMetadataSessions
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.jvm.compiler.toVfsBasedProjectEnvironment
import org.jetbrains.kotlin.cli.jvm.config.JvmClasspathRoot
import org.jetbrains.kotlin.cli.pipeline.CheckCompilationErrors
import org.jetbrains.kotlin.cli.pipeline.ConfigurationPipelineArtifact
import org.jetbrains.kotlin.cli.pipeline.PerformanceNotifications
import org.jetbrains.kotlin.cli.pipeline.PipelinePhase
import org.jetbrains.kotlin.cli.pipeline.jvm.asKtFilesList
import org.jetbrains.kotlin.compiler.plugin.getCompilerExtensions
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.messageCollector
import org.jetbrains.kotlin.config.moduleName
import org.jetbrains.kotlin.config.perfManager
import org.jetbrains.kotlin.config.useLightTree
import org.jetbrains.kotlin.fir.DependencyListForCliModule
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.pipeline.AllModulesFrontendOutput
import org.jetbrains.kotlin.fir.pipeline.buildFirFromKtFiles
import org.jetbrains.kotlin.fir.pipeline.buildFirViaLightTree
import org.jetbrains.kotlin.fir.pipeline.resolveAndCheckFir
import org.jetbrains.kotlin.fir.pipeline.runPlatformCheckers
import org.jetbrains.kotlin.library.KotlinLibrary
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.util.PhaseType
import org.jetbrains.kotlin.util.PotentiallyIncorrectPhaseTimeMeasurement

object DotNetFrontendPipelinePhase : PipelinePhase<ConfigurationPipelineArtifact, DotNetFrontendPipelineArtifact>(
    name = "DotNetFrontendPipelinePhase",
    postActions = setOf(PerformanceNotifications.AnalysisFinished, CheckCompilationErrors.CheckDiagnosticCollector),
) {
    override fun executePhase(input: ConfigurationPipelineArtifact): DotNetFrontendPipelineArtifact? {
        val (configuration, rootDisposable) = input
        val diagnosticsReporter = configuration.diagnosticsCollector
        val messageCollector = configuration.messageCollector
        val rootModuleName = Name.special("<${configuration.moduleName!!}>")
        val isLightTree = configuration.getBoolean(CommonConfigurationKeys.USE_LIGHT_TREE)
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
        val libraryList = DependencyListForCliModule.build(rootModuleName) {}
        val klibs: List<KotlinLibrary> = loadMetadataKlibs(
            libraryPaths = configuration.contentRoots.mapNotNull { (it as? JvmClasspathRoot)?.file?.path },
            configuration = configuration,
        ).all
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
                createProviderAndScopeForIncrementalCompilation = { null },
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
                AnalyzerWithCompilerReport.reportSyntaxErrors(ktFile, diagnosticsReporter)
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
                createProviderAndScopeForIncrementalCompilation = { null },
            )
            sessionsWithSources.map { (session, files) ->
                val firFiles = session.buildFirFromKtFiles(files)
                resolveAndCheckFir(session, firFiles, diagnosticsReporter)
            }
        }

        outputs.runPlatformCheckers(diagnosticsReporter)
        val kotlinPackageUsageIsFine = if (configuration.useLightTree) {
            outputs.all { checkKotlinPackageUsageForLightTree(configuration, it.fir) }
        } else {
            checkKotlinPackageUsageForPsi(configuration, sourceFiles.asKtFilesList())
        }
        if (!kotlinPackageUsageIsFine || messageCollector.hasErrors() || diagnosticsReporter.hasErrors) return null

        return DotNetFrontendPipelineArtifact(AllModulesFrontendOutput(outputs), configuration, sourceFiles)
    }
}
