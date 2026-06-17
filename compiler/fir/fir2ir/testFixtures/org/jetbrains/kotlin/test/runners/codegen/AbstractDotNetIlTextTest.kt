/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.test.runners.codegen

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
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.targetPlatform
import org.jetbrains.kotlin.diagnostics.impl.DiagnosticsCollectorImpl
import org.jetbrains.kotlin.platform.DotNetPlatforms
import org.jetbrains.kotlin.platform.isDotNet
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
import org.jetbrains.kotlin.test.frontend.fir.Fir2IrCliBasedOutputArtifact
import org.jetbrains.kotlin.test.frontend.fir.Fir2IrCliFacade
import org.jetbrains.kotlin.test.frontend.fir.FirCliFacade
import org.jetbrains.kotlin.test.model.ArtifactKinds
import org.jetbrains.kotlin.test.model.BackendFacade
import org.jetbrains.kotlin.test.model.BackendKinds
import org.jetbrains.kotlin.test.model.BinaryArtifacts
import org.jetbrains.kotlin.test.model.DependencyKind
import org.jetbrains.kotlin.test.model.FrontendKinds
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.runners.AbstractKotlinCompilerWithTargetBackendTest
import org.jetbrains.kotlin.test.services.EnvironmentConfigurator
import org.jetbrains.kotlin.test.services.TestServices
import org.jetbrains.kotlin.test.services.getOrCreateTempDirectory
import org.jetbrains.kotlin.test.services.moduleStructure
import org.jetbrains.kotlin.test.services.targetPlatform
import org.jetbrains.kotlin.test.services.configuration.CommonEnvironmentConfigurator
import org.jetbrains.kotlin.test.services.configuration.addSourcesForDependsOnClosure
import org.jetbrains.kotlin.test.utils.MultiModuleInfoDumper
import org.jetbrains.kotlin.test.utils.withExtension

abstract class AbstractDotNetIlTextTestBase(
    private val parser: FirParser,
) : AbstractKotlinCompilerWithTargetBackendTest(TargetBackend.DOTNET) {
    override fun configure(builder: TestConfigurationBuilder): Unit = with(builder) {
        globalDefaults {
            frontend = FrontendKinds.FIR
            targetPlatform = DotNetPlatforms.defaultDotNetPlatform
            targetBackend = TargetBackend.DOTNET
            artifactKind = ArtifactKinds.DotNet
            dependencyKind = DependencyKind.Binary
        }

        useConfigurators(
            ::CommonEnvironmentConfigurator,
            ::DotNetEnvironmentConfigurator,
        )

        facadeStep(::FirCliDotNetFacade)
        firHandlersStep {
            useHandlers(::NoFirCompilationErrorsHandler)
        }

        facadeStep(::Fir2IrCliDotNetFacade)
        irHandlersStep()

        facadeStep(::BackendCliDotNetFacade)
        dotNetArtifactsHandlersStep {
            useHandlers(::DotNetIlTextHandler)
        }

        configureFirParser(parser)
    }
}

open class AbstractFirLightTreeDotNetIlTextTest : AbstractDotNetIlTextTestBase(FirParser.LightTree)

@FirPsiCodegenTest
open class AbstractFirPsiDotNetIlTextTest : AbstractDotNetIlTextTestBase(FirParser.Psi)

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

private class DotNetEnvironmentConfigurator(testServices: TestServices) : EnvironmentConfigurator(testServices) {
    override fun configureCompilerConfiguration(configuration: CompilerConfiguration, module: TestModule) {
        if (!module.targetPlatform(testServices).isDotNet()) return

        val artifactName = getArtifactName(module)
        configuration.put(CLIConfigurationKeys.ALLOW_KOTLIN_PACKAGE, true)
        configuration.put(CommonConfigurationKeys.MODULE_NAME, module.name)
        configuration.targetPlatform = DotNetPlatforms.defaultDotNetPlatform
        configuration.dotNetAssemblyName = artifactName
        configuration.dotNetOutput = getOutputFile(module, artifactName)
        configuration.addSourcesForDependsOnClosure(module, testServices)
        configuration.addKotlinSourceRoot(getOrCreateStdlibSource().canonicalPath)
    }

    private fun getArtifactName(module: TestModule): String {
        val testName = testServices.moduleStructure.originalTestDataFiles.first().nameWithoutExtension
        return module.name.takeUnless { it == "main" } ?: testName
    }

    private fun getOutputFile(module: TestModule, artifactName: String) =
        testServices.getOrCreateTempDirectory("dotnet").resolve("${module.name}-$artifactName.il")

    private fun getOrCreateStdlibSource() =
        testServices.getOrCreateTempDirectory("dotnet-stdlib").resolve("DotNetStdlib.kt").also { file ->
            if (!file.isFile || file.readText() != DOTNET_STDLIB_SOURCE) {
                file.writeText(DOTNET_STDLIB_SOURCE)
            }
        }
}

private class DotNetIlTextHandler(testServices: TestServices) : DotNetBinaryArtifactHandler(testServices) {
    private val multiModuleInfoDumper = MultiModuleInfoDumper()

    override fun processModule(module: TestModule, info: BinaryArtifacts.DotNet) {
        multiModuleInfoDumper.builderForModule(module).append(info.outputFile.readText())
    }

    override fun processAfterAllModules(someAssertionWasFailed: Boolean) {
        val expectedFile = testServices.moduleStructure.originalTestDataFiles.first().withExtension(".txt")
        assertions.assertEqualsToFile(expectedFile, multiModuleInfoDumper.generateResultingDump())
    }
}

private const val DOTNET_STDLIB_SOURCE = """@file:Suppress("UNUSED_PARAMETER")
package kotlin.io

public fun println() {}

public fun println(message: String) {}
"""
