/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.test.runners.codegen

import org.jetbrains.kotlin.backend.dotnet.DOTNET_STDLIB_SOURCES
import org.jetbrains.kotlin.backend.dotnet.dotNetAssemblyName
import org.jetbrains.kotlin.backend.dotnet.dotNetOutput
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.config.addKotlinSourceRoot
import org.jetbrains.kotlin.cli.pipeline.dotnet.DotNetBackendPipelinePhase
import org.jetbrains.kotlin.cli.pipeline.dotnet.DotNetFir2IrPipelineArtifact
import org.jetbrains.kotlin.cli.pipeline.dotnet.DotNetFir2IrPipelinePhase
import org.jetbrains.kotlin.cli.pipeline.dotnet.DotNetFrontendPipelineArtifact
import org.jetbrains.kotlin.cli.pipeline.dotnet.DotNetFrontendPipelinePhase
import org.jetbrains.kotlin.cli.pipeline.withNewDiagnosticCollector
import org.jetbrains.kotlin.config.AnalysisFlag
import org.jetbrains.kotlin.config.AnalysisFlags
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.LanguageVersion
import org.jetbrains.kotlin.config.targetPlatform
import org.jetbrains.kotlin.diagnostics.impl.DiagnosticsCollectorImpl
import org.jetbrains.kotlin.platform.DotNetPlatforms
import org.jetbrains.kotlin.platform.isDotNet
import org.jetbrains.kotlin.test.Constructor
import org.jetbrains.kotlin.test.FirParser
import org.jetbrains.kotlin.test.TargetBackend
import org.jetbrains.kotlin.test.backend.handlers.DotNetBinaryArtifactHandler
import org.jetbrains.kotlin.test.backend.handlers.NoFirCompilationErrorsHandler
import org.jetbrains.kotlin.test.backend.ir.IrBackendInput
import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder
import org.jetbrains.kotlin.test.builders.dotNetArtifactsHandlersStep
import org.jetbrains.kotlin.test.builders.firHandlersStep
import org.jetbrains.kotlin.test.builders.irHandlersStep
import org.jetbrains.kotlin.test.directives.configureFirParser
import org.jetbrains.kotlin.test.directives.model.RegisteredDirectives
import org.jetbrains.kotlin.test.frontend.fir.Fir2IrCliBasedOutputArtifact
import org.jetbrains.kotlin.test.frontend.fir.Fir2IrCliFacade
import org.jetbrains.kotlin.test.frontend.fir.FirCliFacade
import org.jetbrains.kotlin.test.model.ArtifactKinds
import org.jetbrains.kotlin.test.model.BackendFacade
import org.jetbrains.kotlin.test.model.BackendKinds
import org.jetbrains.kotlin.test.model.BinaryArtifacts
import org.jetbrains.kotlin.test.model.DependencyKind
import org.jetbrains.kotlin.test.model.FrontendKinds
import org.jetbrains.kotlin.test.model.TestFile
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.runners.AbstractKotlinCompilerWithTargetBackendTest
import org.jetbrains.kotlin.test.services.AdditionalSourceProvider
import org.jetbrains.kotlin.test.services.EnvironmentConfigurator
import org.jetbrains.kotlin.test.services.TestModuleStructure
import org.jetbrains.kotlin.test.services.TestServices
import org.jetbrains.kotlin.test.services.getOrCreateTempDirectory
import org.jetbrains.kotlin.test.services.moduleStructure
import org.jetbrains.kotlin.test.services.targetPlatform
import org.jetbrains.kotlin.test.services.temporaryDirectoryManager
import org.jetbrains.kotlin.test.services.configuration.CommonEnvironmentConfigurator
import org.jetbrains.kotlin.test.services.configuration.addSourcesForDependsOnClosure
import org.jetbrains.kotlin.test.services.sourceProviders.MainFunctionForBlackBoxTestsSourceProvider
import org.jetbrains.kotlin.test.utils.MultiModuleInfoDumper
import org.jetbrains.kotlin.test.utils.withExtension
import org.jetbrains.kotlin.utils.bind
import java.io.File
import java.util.concurrent.TimeUnit

abstract class AbstractDotNetIlTextTestBase(
    private val parser: FirParser,
) : AbstractKotlinCompilerWithTargetBackendTest(TargetBackend.DOTNET) {
    override fun configure(builder: TestConfigurationBuilder): Unit = with(builder) {
        configureDotNetBase(parser, outputExtension = "il")

        dotNetArtifactsHandlersStep {
            useHandlers(::DotNetIlTextHandler)
        }
    }
}

open class AbstractFirLightTreeDotNetIlTextTest : AbstractDotNetIlTextTestBase(FirParser.LightTree)

@FirPsiCodegenTest
open class AbstractFirPsiDotNetIlTextTest : AbstractDotNetIlTextTestBase(FirParser.Psi)

abstract class AbstractDotNetBoxTestBase(
    private val parser: FirParser,
) : AbstractKotlinCompilerWithTargetBackendTest(TargetBackend.DOTNET) {
    override fun configure(builder: TestConfigurationBuilder): Unit = with(builder) {
        configureDotNetBase(parser, outputExtension = "exe", additionalSourceProvider = ::DotNetBoxMainSourceProvider)

        dotNetArtifactsHandlersStep {
            useHandlers(::DotNetBoxRunner)
        }
    }
}

open class AbstractFirLightTreeDotNetBoxTest : AbstractDotNetBoxTestBase(FirParser.LightTree)

@FirPsiCodegenTest
open class AbstractFirPsiDotNetBoxTest : AbstractDotNetBoxTestBase(FirParser.Psi)

private fun TestConfigurationBuilder.configureDotNetBase(
    parser: FirParser,
    outputExtension: String,
    additionalSourceProvider: Constructor<AdditionalSourceProvider>? = null,
) {
    with(this) {
        globalDefaults {
            frontend = FrontendKinds.FIR
            targetPlatform = DotNetPlatforms.defaultDotNetPlatform
            targetBackend = TargetBackend.DOTNET
            artifactKind = ArtifactKinds.DotNet
            dependencyKind = DependencyKind.Binary
        }

        additionalSourceProvider?.let { useAdditionalSourceProviders(it) }

        useConfigurators(
            ::CommonEnvironmentConfigurator,
            ::DotNetEnvironmentConfigurator.bind(outputExtension),
        )

        facadeStep(::FirCliDotNetFacade)
        firHandlersStep {
            useHandlers(::NoFirCompilationErrorsHandler)
        }

        facadeStep(::Fir2IrCliDotNetFacade)
        irHandlersStep()

        facadeStep(::BackendCliDotNetFacade)

        configureFirParser(parser)
    }
}

private class FirCliDotNetFacade(
    testServices: TestServices,
) : FirCliFacade<DotNetFrontendPipelinePhase, DotNetFrontendPipelineArtifact>(testServices, DotNetFrontendPipelinePhase)

private class Fir2IrCliDotNetFacade(
    testServices: TestServices,
) : Fir2IrCliFacade<DotNetFir2IrPipelinePhase, DotNetFrontendPipelineArtifact, DotNetFir2IrPipelineArtifact>(
    testServices,
    DotNetFir2IrPipelinePhase,
)

private class BackendCliDotNetFacade(
    testServices: TestServices,
) : BackendFacade<IrBackendInput, BinaryArtifacts.DotNet>(
    testServices,
    BackendKinds.IrBackend,
    ArtifactKinds.DotNet,
) {
    override fun transform(module: TestModule, inputArtifact: IrBackendInput): BinaryArtifacts.DotNet {
        require(inputArtifact is Fir2IrCliBasedOutputArtifact<*>) {
            "BackendCliDotNetFacade expects Fir2IrCliBasedOutputArtifact as input, but ${inputArtifact::class} was found"
        }
        val cliArtifact = inputArtifact.cliArtifact
        require(cliArtifact is DotNetFir2IrPipelineArtifact) {
            "BackendCliDotNetFacade expects DotNetFir2IrPipelineArtifact as input, but ${cliArtifact::class} was found"
        }
        val input = cliArtifact.withNewDiagnosticCollector(DiagnosticsCollectorImpl())
        return BinaryArtifacts.DotNet(DotNetBackendPipelinePhase.executePhase(input).output)
    }
}

private class DotNetEnvironmentConfigurator(
    testServices: TestServices,
    private val outputExtension: String,
) : EnvironmentConfigurator(testServices) {
    /**
     * The injected fake stdlib sources declare `package kotlin.io` and `package kotlin`, and
     * besides permitting the package names, [AnalysisFlags.allowKotlinPackage] makes the default
     * star imports (`kotlin.*`, `kotlin.io.*`) look at source-declared symbols, so `println` and
     * `Char.code` resolve without an explicit import. Mirrors
     * `K2DotNetCompilerArgumentsConfigurator` on the CLI side.
     * (TODO: this also allows test code in `kotlin.*`.)
     */
    override fun provideAdditionalAnalysisFlags(
        directives: RegisteredDirectives,
        languageVersion: LanguageVersion,
    ): Map<AnalysisFlag<*>, Any?> {
        return mapOf(AnalysisFlags.allowKotlinPackage to true)
    }

    override fun configureCompilerConfiguration(configuration: CompilerConfiguration, module: TestModule) {
        if (!module.targetPlatform(testServices).isDotNet()) return

        val artifactName = getArtifactName(module)
        configuration.put(CLIConfigurationKeys.ALLOW_KOTLIN_PACKAGE, true)
        configuration.put(CommonConfigurationKeys.MODULE_NAME, module.name)
        configuration.targetPlatform = DotNetPlatforms.defaultDotNetPlatform
        configuration.dotNetAssemblyName = artifactName
        configuration.dotNetOutput = getOutputFile(module, artifactName)
        configuration.addSourcesForDependsOnClosure(module, testServices)
        for (stdlibSource in getOrCreateStdlibSources()) {
            configuration.addKotlinSourceRoot(stdlibSource.canonicalPath)
        }
    }

    private fun getArtifactName(module: TestModule): String {
        val testName = testServices.moduleStructure.originalTestDataFiles.first().nameWithoutExtension
        return module.name.takeUnless { it == "main" } ?: testName
    }

    private fun getOutputFile(module: TestModule, artifactName: String) =
        testServices.getOrCreateTempDirectory("dotnet").resolve("${module.name}-$artifactName.$outputExtension")

    private fun getOrCreateStdlibSources() =
        DOTNET_STDLIB_SOURCES.map { (fileName, source) ->
            testServices.getOrCreateTempDirectory("dotnet-stdlib").resolve(fileName).also { file ->
                if (!file.isFile || file.readText() != source) {
                    file.writeText(source)
                }
            }
        }
}

private class DotNetIlTextHandler(testServices: TestServices) : DotNetBinaryArtifactHandler(testServices) {
    private val multiModuleInfoDumper = MultiModuleInfoDumper()

    override fun processModule(module: TestModule, info: BinaryArtifacts.DotNet) {
        // The backend writes the .il file as UTF-8 with a BOM (required by ilasm); the BOM is an
        // encoding artifact, not part of the IL text under test.
        multiModuleInfoDumper.builderForModule(module).append(info.outputFile.readText().removePrefix("\uFEFF"))
    }

    override fun processAfterAllModules(someAssertionWasFailed: Boolean) {
        val expectedFile = testServices.moduleStructure.originalTestDataFiles.first().withExtension(".txt")
        assertions.assertEqualsToFile(expectedFile, multiModuleInfoDumper.generateResultingDump())
    }
}

private class DotNetBoxMainSourceProvider(testServices: TestServices) : AdditionalSourceProvider(testServices) {
    override fun produceAdditionalFiles(
        globalDirectives: RegisteredDirectives,
        module: TestModule,
        testModuleStructure: TestModuleStructure
    ): List<TestFile> {
        val fileWithBox = module.files.firstOrNull {
            MainFunctionForBlackBoxTestsSourceProvider.containsBoxMethod(it.originalContent)
        } ?: return emptyList()

        val code = buildString {
            MainFunctionForBlackBoxTestsSourceProvider.detectPackage(fileWithBox)?.let {
                appendLine("package $it")
                appendLine()
            }
            appendLine("import kotlin.io.println")
            appendLine()
            appendLine("fun main() {")
            appendLine("    println(box())")
            appendLine("}")
        }
        val file = testServices.temporaryDirectoryManager.getOrCreateTempDirectory("src")
            .resolve(MainFunctionForBlackBoxTestsSourceProvider.BOX_MAIN_FILE_NAME)
        file.writeText(code)

        return listOf(file.toTestFile())
    }
}

private class DotNetBoxRunner(testServices: TestServices) : DotNetBinaryArtifactHandler(testServices) {
    private var boxMethodFound = false

    override fun processModule(module: TestModule, info: BinaryArtifacts.DotNet) {
        if (module.files.none { MainFunctionForBlackBoxTestsSourceProvider.containsBoxMethod(it.originalContent) }) return

        boxMethodFound = true
        val result = runExecutable(info.outputFile).trim()
        val outputFile = testServices.moduleStructure.originalTestDataFiles.first().withExtension(OUTPUT_EXTENSION)
        if (outputFile.exists()) {
            assertions.assertEqualsToFile(outputFile, result)
        } else {
            assertions.assertEquals(DEFAULT_EXPECTED_RESULT, result)
        }
    }

    override fun processAfterAllModules(someAssertionWasFailed: Boolean) {
        if (!boxMethodFound) {
            assertions.fail { "Can't find box methods" }
        }
    }

    private fun runExecutable(file: File): String {
        if (!file.isFile) {
            assertions.fail { "Expected .NET executable was not produced: ${file.path}" }
        }

        val process = ProcessBuilder(file.absolutePath)
            .directory(file.parentFile)
            .redirectErrorStream(true)
            .start()
        if (!process.waitFor(3, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            assertions.fail { ".NET executable timed out: ${file.path}" }
        }

        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.exitValue()
        if (exitCode != 0) {
            assertions.fail {
                ".NET executable failed with exit code $exitCode${output.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()}"
            }
        }
        return output
    }

    private companion object {
        const val DEFAULT_EXPECTED_RESULT = "OK"
        const val OUTPUT_EXTENSION = "box.txt"
    }
}
