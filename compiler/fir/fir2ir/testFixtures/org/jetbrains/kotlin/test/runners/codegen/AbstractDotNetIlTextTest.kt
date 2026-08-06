/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.test.runners.codegen

import org.jetbrains.kotlin.backend.dotnet.DOTNET_STDLIB_SOURCES
import org.jetbrains.kotlin.backend.dotnet.DOTNET_STDLIB_COMMON_SOURCE_NAMES
import org.jetbrains.kotlin.backend.dotnet.DOTNET_STDLIB_SOURCE_PATHS
import org.jetbrains.kotlin.backend.dotnet.DotNetExport
import org.jetbrains.kotlin.backend.dotnet.DotNetIlAssembler
import org.jetbrains.kotlin.backend.dotnet.DotNetPropertyExport
import org.jetbrains.kotlin.backend.dotnet.dotNetExports
import org.jetbrains.kotlin.backend.dotnet.dotNetPropertyExports
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.config.addKotlinSourceRoot
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.pipeline.dotnet.DotNetBackendPipelinePhase
import org.jetbrains.kotlin.cli.pipeline.dotnet.DotNetFir2IrPipelineArtifact
import org.jetbrains.kotlin.cli.pipeline.dotnet.DotNetFir2IrPipelinePhase
import org.jetbrains.kotlin.cli.pipeline.dotnet.DotNetFrontendPipelineArtifact
import org.jetbrains.kotlin.cli.pipeline.dotnet.DotNetFrontendPipelinePhase
import org.jetbrains.kotlin.cli.pipeline.dotnet.DotNetKlibInliningPipelinePhase
import org.jetbrains.kotlin.cli.pipeline.withNewDiagnosticCollector
import org.jetbrains.kotlin.config.AnalysisFlag
import org.jetbrains.kotlin.config.AnalysisFlags
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.DotNetTarget
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.config.LanguageVersion
import org.jetbrains.kotlin.config.LanguageVersionSettings
import org.jetbrains.kotlin.config.dotNetAssemblyName
import org.jetbrains.kotlin.config.dotNetOutput
import org.jetbrains.kotlin.config.dotNetTarget
import org.jetbrains.kotlin.config.languageVersionSettings
import org.jetbrains.kotlin.config.targetPlatform
import org.jetbrains.kotlin.diagnostics.impl.DiagnosticsCollectorImpl
import org.jetbrains.kotlin.fir.moduleData
import org.jetbrains.kotlin.fir.pipeline.SingleModuleFrontendOutput
import org.jetbrains.kotlin.platform.dotnet.DotNetPlatforms
import org.jetbrains.kotlin.platform.dotnet.isDotNet
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
import org.jetbrains.kotlin.test.directives.model.SimpleDirectivesContainer
import org.jetbrains.kotlin.test.frontend.fir.Fir2IrCliBasedOutputArtifact
import org.jetbrains.kotlin.test.frontend.fir.Fir2IrCliFacade
import org.jetbrains.kotlin.test.frontend.fir.FirCliFacade
import org.jetbrains.kotlin.test.frontend.fir.FirOutputPartForDependsOnModule
import org.jetbrains.kotlin.test.frontend.fir.toTestOutputPart
import org.jetbrains.kotlin.test.model.ArtifactKinds
import org.jetbrains.kotlin.test.model.BackendFacade
import org.jetbrains.kotlin.test.model.BackendKinds
import org.jetbrains.kotlin.test.model.BinaryArtifacts
import org.jetbrains.kotlin.test.model.DependencyKind
import org.jetbrains.kotlin.test.model.FrontendKinds
import org.jetbrains.kotlin.test.model.TestFile
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.runners.AbstractKotlinCompilerDotNetTest
import org.jetbrains.kotlin.test.services.AdditionalSourceProvider
import org.jetbrains.kotlin.test.services.EnvironmentConfigurator
import org.jetbrains.kotlin.test.services.TestModuleStructure
import org.jetbrains.kotlin.test.services.TestServices
import org.jetbrains.kotlin.test.services.getOrCreateTempDirectory
import org.jetbrains.kotlin.test.services.moduleStructure
import org.jetbrains.kotlin.test.services.targetPlatform
import org.jetbrains.kotlin.test.services.temporaryDirectoryManager
import org.jetbrains.kotlin.test.services.transitiveDependsOnDependencies
import org.jetbrains.kotlin.test.services.configuration.CommonEnvironmentConfigurator
import org.jetbrains.kotlin.test.services.configuration.addSourcesForDependsOnClosure
import org.jetbrains.kotlin.test.services.sourceProviders.MainFunctionForBlackBoxTestsSourceProvider
import org.jetbrains.kotlin.test.utils.MultiModuleInfoDumper
import org.jetbrains.kotlin.test.utils.withExtension
import org.jetbrains.kotlin.utils.bind
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.parallel.ResourceLock
import org.opentest4j.TestAbortedException
import java.io.File
import java.util.concurrent.TimeUnit

// Framework ILAsm and the Windows PowerShell CLR 4 host are external, process-wide resources.
// Unbounded JUnit 5 fan-out produces nondeterministic empty host failures and occasionally no PE
// output. Keep only the Framework lane exclusive; modern .NET boxes remain parallel.
@ResourceLock("kotlin-dotnet-framework-toolchain")
abstract class AbstractDotNetIlTextTestBase(
    private val parser: FirParser,
) : AbstractKotlinCompilerDotNetTest() {
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
) : AbstractKotlinCompilerDotNetTest() {
    override fun configure(builder: TestConfigurationBuilder): Unit = with(builder) {
        // The box suite targets modern .NET: the artifact is a dll launched via the signed
        // `dotnet` host (`dotnet exec`), never a directly executed unsigned .exe (see
        // compiler/ir/backend.dotnet/AGENTS.md).
        configureDotNetBase(
            parser,
            outputExtension = "dll",
            target = DotNetTarget.NET10_0,
            additionalSourceProvider = ::DotNetBoxMainSourceProvider,
        )

        dotNetArtifactsHandlersStep {
            useHandlers(::DotNetBoxRunner)
        }
    }

    override fun runTest(filePath: String) {
        val toolchainAvailable =
            DotNetIlAssembler.findModernIlasm() != null && DotNetIlAssembler.findModernDotNetHost() != null
        val message = "Modern .NET toolchain (ilasm + dotnet host) not found; " +
                "provision with compiler/ir/backend.dotnet/tools/provision-dotnet-toolchain.ps1"
        if (dotNetToolchainIsRequired()) {
            check(toolchainAvailable) { "$message (KOTLIN_DOTNET_REQUIRE_TOOLCHAIN is enabled)" }
        } else {
            Assumptions.assumeTrue(toolchainAvailable, message)
        }
        super.runTest(filePath)
    }
}

open class AbstractFirLightTreeDotNetBoxTest : AbstractDotNetBoxTestBase(FirParser.LightTree)

@FirPsiCodegenTest
open class AbstractFirPsiDotNetBoxTest : AbstractDotNetBoxTestBase(FirParser.Psi)

@ResourceLock("kotlin-dotnet-framework-toolchain")
abstract class AbstractDotNetFrameworkBoxTestBase(
    private val parser: FirParser,
) : AbstractKotlinCompilerDotNetTest() {
    override fun configure(builder: TestConfigurationBuilder): Unit = with(builder) {
        configureDotNetBase(
            parser,
            outputExtension = "exe",
            target = DotNetTarget.NET48,
            additionalSourceProvider = ::DotNetBoxMainSourceProvider,
        )

        dotNetArtifactsHandlersStep {
            useHandlers(::DotNetFrameworkBoxRunner)
        }
    }

    override fun runTest(filePath: String) {
        val toolchainAvailable =
            DotNetIlAssembler.findFrameworkIlasm() != null &&
                    DotNetIlAssembler.findFrameworkPowerShellHost() != null
        val message = ".NET Framework toolchain (ILAsm + Windows PowerShell CLR 4 host) not found"
        if (dotNetToolchainIsRequired()) {
            check(toolchainAvailable) { "$message (KOTLIN_DOTNET_REQUIRE_TOOLCHAIN is enabled)" }
        } else {
            Assumptions.assumeTrue(toolchainAvailable, message)
        }
        super.runTest(filePath)
    }
}

open class AbstractFirLightTreeDotNetFrameworkBoxTest : AbstractDotNetFrameworkBoxTestBase(FirParser.LightTree)

@FirPsiCodegenTest
open class AbstractFirPsiDotNetFrameworkBoxTest : AbstractDotNetFrameworkBoxTestBase(FirParser.Psi)

private fun TestConfigurationBuilder.configureDotNetBase(
    parser: FirParser,
    outputExtension: String,
    // NET48 by default: the ilText goldens embed the `.module` directive naming the
    // artifact file, so the ilText suite must keep its historical target/extension untouched.
    target: DotNetTarget = DotNetTarget.NET48,
    additionalSourceProvider: Constructor<AdditionalSourceProvider>? = null,
) {
    with(this) {
        useDirectives(DotNetCodegenDirectives)
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
            ::DotNetEnvironmentConfigurator.bind(outputExtension, target),
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
    private val dotNetTestServices: TestServices,
) : FirCliFacade<DotNetFrontendPipelinePhase, DotNetFrontendPipelineArtifact>(
    dotNetTestServices,
    DotNetFrontendPipelinePhase,
) {
    override fun getPartsForDependsOnModules(
        module: TestModule,
        firOutputs: List<SingleModuleFrontendOutput>,
    ): List<FirOutputPartForDependsOnModule> {
        val modulesBySessionName = module.transitiveDependsOnDependencies(includeSelf = true, reverseOrder = true)
            .associateBy { "<${it.name}>" }
        return firOutputs.map { output ->
            val sessionName = output.session.moduleData.name.asString()
            val logicalSessionName = sessionName.removeSuffix("-common")
            output.toTestOutputPart(modulesBySessionName.getValue(logicalSessionName), dotNetTestServices)
        }
    }
}

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
        val loweredInput = DotNetKlibInliningPipelinePhase.executePhase(input)
        return BinaryArtifacts.DotNet(DotNetBackendPipelinePhase.executePhase(loweredInput).output)
    }
}

private class DotNetEnvironmentConfigurator(
    testServices: TestServices,
    private val outputExtension: String,
    private val target: DotNetTarget,
) : EnvironmentConfigurator(testServices) {
    /**
     * The injected fake stdlib sources declare `package kotlin.io` and `package kotlin`, and
     * besides permitting the package names, [AnalysisFlags.allowKotlinPackage] makes the default
     * star imports (`kotlin.*`, `kotlin.io.*`) look at source-declared symbols, so `println` and
     * `Char.code` resolve without an explicit import. Mirrors
     * `K2DotNetCompilerArgumentsConfigurator` on the CLI side.
     * The compiler-owned Common stdlib headers additionally opt in through the wrapped language
     * settings below, preserving any test-declared opt-ins rather than replacing their list.
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
        configuration.dotNetExports = module.directives[DotNetCodegenDirectives.DOTNET_EXPORT]
            .map(DotNetExport::parse)
        configuration.dotNetPropertyExports = module.directives[DotNetCodegenDirectives.DOTNET_EXPORT_PROPERTY]
            .map(DotNetPropertyExport::parse)
        configuration.dotNetOutput = getOutputFile(module, artifactName)
        configuration.dotNetTarget = target
        configuration.languageVersionSettings =
            configuration.languageVersionSettings.withDotNetSourceProductSettings()
        configuration.addSourcesForDependsOnClosure(module, testServices)
        for (stdlibSource in getOrCreateStdlibSources()) {
            configuration.addKotlinSourceRoot(
                path = stdlibSource.canonicalPath,
                isCommon = stdlibSource.name in DOTNET_STDLIB_COMMON_SOURCE_NAMES,
            )
        }
    }

    private fun getArtifactName(module: TestModule): String {
        val testName = testServices.moduleStructure.originalTestDataFiles.first().nameWithoutExtension
        return module.name.takeUnless { it == "main" } ?: testName
    }

    private fun getOutputFile(module: TestModule, artifactName: String) =
        testServices.getOrCreateTempDirectory("dotnet").resolve("${module.name}-$artifactName.$outputExtension")

    private fun getOrCreateStdlibSources() =
        DOTNET_STDLIB_SOURCES.map { [fileName, source] ->
            testServices.getOrCreateTempDirectory("dotnet-stdlib")
                .resolve(DOTNET_STDLIB_SOURCE_PATHS.getValue(fileName))
                .also { file ->
                if (!file.isFile || file.readText() != source) {
                    file.parentFile.mkdirs()
                    file.writeText(source)
                }
            }
        }
}

private fun LanguageVersionSettings.withDotNetSourceProductSettings(): LanguageVersionSettings {
    val delegate = this
    return object : LanguageVersionSettings by delegate {
        override fun getFeatureSupport(feature: LanguageFeature): LanguageFeature.State =
            if (feature == LanguageFeature.MultiPlatformProjects) {
                LanguageFeature.State.ENABLED
            } else {
                delegate.getFeatureSupport(feature)
            }

        override fun supportsFeature(feature: LanguageFeature): Boolean =
            getFeatureSupport(feature) == LanguageFeature.State.ENABLED

        override fun getCustomizedLanguageFeatures(): Map<LanguageFeature, LanguageFeature.State> =
            delegate.getCustomizedLanguageFeatures() +
                    (LanguageFeature.MultiPlatformProjects to LanguageFeature.State.ENABLED)

        override fun <T> getFlag(flag: AnalysisFlag<T>): T {
            @Suppress("UNCHECKED_CAST")
            if (flag == AnalysisFlags.dontWarnOnErrorSuppression) return true as T
            @Suppress("UNCHECKED_CAST")
            if (flag == AnalysisFlags.optIn) {
                val productOptIns = listOf(
                    "kotlin.ExperimentalMultiplatform",
                    "kotlin.contracts.ExperimentalContracts",
                )
                return (delegate.getFlag(AnalysisFlags.optIn) + productOptIns).distinct() as T
            }
            return delegate.getFlag(flag)
        }
    }
}

private object DotNetCodegenDirectives : SimpleDirectivesContainer() {
    val DOTNET_EXPORT by stringDirective(
        "Explicit CLR function export in <kotlin-selector>=<clr-method-name> form"
    )
    val DOTNET_EXPORT_PROPERTY by stringDirective(
        "Provisional CLR property export in <kotlin-fq-name>=<clr-property-name> form"
    )
}

private class DotNetIlTextHandler(testServices: TestServices) : DotNetBinaryArtifactHandler(testServices) {
    private val multiModuleInfoDumper = MultiModuleInfoDumper()

    override fun processModule(module: TestModule, info: BinaryArtifacts.DotNet) {
        // The backend writes the .il file as UTF-8 with a BOM (required by ilasm); the BOM is an
        // encoding artifact, not part of the IL text under test.
        val ilText = info.outputFile.readText().removePrefix("\uFEFF")
        multiModuleInfoDumper.builderForModule(module).append(ilText)

        val hasEntryPoint = Regex("(?m)^\\s*\\.entrypoint\\s*$").containsMatchIn(ilText)
        val validationDirectory =
            testServices.getOrCreateTempDirectory("dotnet-ilasm-validation")
        val validations = buildList {
            if (DotNetIlAssembler.findFrameworkIlasm() != null) {
                add(DotNetIlasmValidation("Framework", DotNetTarget.NET48))
            }
            if (DotNetIlAssembler.findModernIlasm() != null) {
                add(DotNetIlasmValidation("modern", DotNetTarget.NET10_0))
            }
        }
        if (dotNetToolchainIsRequired() && validations.size != 2) {
            val available = validations.joinToString { it.name }.ifEmpty { "none" }
            assertions.fail {
                "Both .NET Framework and modern ILAsm are required to validate accepted IL text " +
                        "because KOTLIN_DOTNET_REQUIRE_TOOLCHAIN is enabled; available: $available"
            }
        }
        validations.forEach { validation ->
            validateAssembly(
                module,
                info,
                hasEntryPoint,
                validationDirectory,
                validation,
            )
        }
    }

    private fun validateAssembly(
        module: TestModule,
        info: BinaryArtifacts.DotNet,
        hasEntryPoint: Boolean,
        validationDirectory: File,
        validation: DotNetIlasmValidation,
    ) {
        // Modern .NET applications are dlls with an entry point and runtime config. Framework
        // applications remain exe files. The IL itself stays the net48 golden in both cases: the
        // modern pass is an assembler-compatibility oracle, not a net10 profile validation.
        val outputExtension = if (hasEntryPoint && validation.target == DotNetTarget.NET48) "exe" else "dll"
        val assembly = validationDirectory.resolve(
            "${info.outputFile.nameWithoutExtension}-${module.name}-${validation.name.lowercase()}.$outputExtension"
        )
        val assemblyMessages = DotNetIlasmMessageCollector()
        val assembled = if (hasEntryPoint) {
            DotNetIlAssembler.assembleExecutable(
                info.outputFile,
                assembly,
                validation.target,
                assemblyMessages,
            )
        } else {
            DotNetIlAssembler.assembleLibrary(
                info.outputFile,
                assembly,
                validation.target,
                assemblyMessages,
            )
        }
        if (!assembled) {
            assertions.fail {
                "Accepted net48 IL did not assemble with ${validation.name} ILAsm as a $outputExtension: " +
                        "${info.outputFile.path}\n${assemblyMessages.render()}"
            }
        }
    }

    override fun processAfterAllModules(someAssertionWasFailed: Boolean) {
        val expectedFile = testServices.moduleStructure.originalTestDataFiles.first().withExtension(".txt")
        val actual = multiModuleInfoDumper.generateResultingDump()
        if (System.getProperty("kotlin.test.update.test.data") == "true") {
            expectedFile.writeText(actual)
        }
        assertions.assertEqualsToFile(expectedFile, actual)
    }
}

private data class DotNetIlasmValidation(
    val name: String,
    val target: DotNetTarget,
)

private class DotNetIlasmMessageCollector : MessageCollector {
    private val messages = mutableListOf<String>()
    private var errorsReported = false

    override fun clear() {
        messages.clear()
        errorsReported = false
    }

    override fun report(
        severity: CompilerMessageSeverity,
        message: String,
        location: CompilerMessageSourceLocation?,
    ) {
        messages += "${severity.name}: $message"
        errorsReported = errorsReported || severity.isError
    }

    override fun hasErrors(): Boolean = errorsReported

    fun render(): String = messages.joinToString("\n").ifEmpty { "ILAsm reported no diagnostic text." }
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

private class DotNetBoxRunner(testServices: TestServices) :
    AbstractDotNetBoxRunner(testServices, DotNetTarget.NET10_0)

private class DotNetFrameworkBoxRunner(testServices: TestServices) :
    AbstractDotNetBoxRunner(testServices, DotNetTarget.NET48)

private abstract class AbstractDotNetBoxRunner(
    testServices: TestServices,
    private val target: DotNetTarget,
) : DotNetBinaryArtifactHandler(testServices) {
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
            assertions.fail { "Expected .NET assembly was not produced: ${file.path}" }
        }
        val outputDirectory = file.parentFile ?: File(".")
        val runtimeFile = outputDirectory.resolve("Kotlin.Runtime.dll")
        if (!runtimeFile.isFile) {
            assertions.fail { "Expected Kotlin/.NET runtime assembly was not produced: ${runtimeFile.path}" }
        }
        val stdlibFile = outputDirectory.resolve("Kotlin.Stdlib.dll")
        if (!stdlibFile.isFile) {
            assertions.fail { "Expected Kotlin/.NET stdlib assembly was not produced: ${stdlibFile.path}" }
        }
        val stdlibIlFile = outputDirectory.resolve("Kotlin.Stdlib.il")
        val stdlibIlText = stdlibIlFile.takeIf(File::isFile)?.readText().orEmpty().replace("\r\n", "\n")
        val requiredStdlibIl = listOf(
            ".assembly extern mscorlib {}",
            ".assembly 'Kotlin.Stdlib'\n{\n  .ver 1:0:0:0",
            "System.Runtime.Versioning.TargetFrameworkAttribute",
            ".class private auto ansi sealed beforefieldinit 'Kotlin.Collections.ArrayIterator'",
            ".class private auto ansi sealed beforefieldinit 'Kotlin.Collections.ArrayIterable'",
            ".class private auto ansi sealed beforefieldinit 'Kotlin.Collections.ArrayAsList'",
            ".class public abstract auto ansi beforefieldinit 'Kotlin.Collections.AbstractCollection'",
            ".class public abstract auto ansi beforefieldinit 'Kotlin.Collections.AbstractList'",
            ".class public abstract sealed auto ansi beforefieldinit 'Kotlin.Collections.CollectionsKt'",
            "'Kotlin.Collections.EmptyIterator'",
            "'Kotlin.Collections.EmptyList'",
            ".class interface public abstract auto ansi 'Kotlin.Collections.RandomAccess'",
            ".class interface public abstract auto ansi 'Kotlin.Text.Appendable'",
            ".class public auto ansi sealed beforefieldinit 'Kotlin.Text.StringBuilder'",
            ".class public abstract sealed auto ansi beforefieldinit 'Kotlin.Text.StringsKt'",
            ".class public auto ansi sealed beforefieldinit 'Kotlin.NotImplementedError'",
            ".method public hidebysig static class [Kotlin.Runtime]'Kotlin.Collections.List' " +
                    "'emptyList'<'T'>()",
            ".method public hidebysig static class [Kotlin.Runtime]'Kotlin.Collections.List' " +
                    "'asList'<'T'>(class [mscorlib]System.Array '<this>')",
            ".method public hidebysig static bool 'any'<'T'>(" +
                    "class [Kotlin.Runtime]'Kotlin.Collections.Iterable' '<this>')",
            ".method public hidebysig specialname static int32 'get_lastIndex'<'T'>(" +
                    "class [Kotlin.Runtime]'Kotlin.Collections.List' '<this>')",
            ".method public hidebysig static object 'firstOrNull'<'T'>(" +
                    "class [Kotlin.Runtime]'Kotlin.Collections.Iterable' '<this>')",
            ".method public hidebysig static object 'lastOrNull'<'T'>(" +
                    "class [Kotlin.Runtime]'Kotlin.Collections.List' '<this>')",
            ".method public hidebysig static bool 'none'<'T'>(" +
                    "class [Kotlin.Runtime]'Kotlin.Collections.Iterable' '<this>')",
            ".method public hidebysig static !!0 'single'<'T'>(" +
                    "class [Kotlin.Runtime]'Kotlin.Collections.List' '<this>')",
            ".method public hidebysig static object 'singleOrNull'<'T'>(" +
                    "class [Kotlin.Runtime]'Kotlin.Collections.Iterable' '<this>')",
        )
        requiredStdlibIl.firstOrNull { it !in stdlibIlText }?.let { missing ->
            assertions.fail { "Expected Kotlin.Stdlib IL to contain '$missing': ${stdlibIlFile.path}" }
        }
        if ("Kotlin.Collections.CollectionsKt1" in stdlibIlText) {
            assertions.fail {
                "Compiler-owned collection source shards must share Kotlin.Collections.CollectionsKt: " +
                        stdlibIlFile.path
            }
        }
        if (".assembly extern Kotlin.Stdlib" in stdlibIlText) {
            assertions.fail { "Kotlin.Stdlib must not carry an AssemblyRef to itself: ${stdlibIlFile.path}" }
        }
        if ("[netstandard]" in stdlibIlText) {
            assertions.fail { "The box stdlib must use the selected $target API profile: ${stdlibIlFile.path}" }
        }
        val ilFile = outputDirectory.resolve("${file.nameWithoutExtension}.il")
        val ilText = ilFile.takeIf(File::isFile)?.readText().orEmpty()

        if (".assembly extern Kotlin.Runtime" !in ilText || ".ver 1:0:0:0" !in ilText) {
            assertions.fail { "Expected .NET assembly to reference Kotlin.Runtime: ${ilFile.path}" }
        }

        val command = when (target) {
            DotNetTarget.NET10_0 -> {
                // Modern boxes are dlls launched through the signed dotnet host.
                val dotnetHost = DotNetIlAssembler.findModernDotNetHost() ?: assertions.fail {
                    "No modern 'dotnet' host found even though the toolchain assumption passed; " +
                            "provision with compiler/ir/backend.dotnet/tools/provision-dotnet-toolchain.ps1"
                }
                listOf(dotnetHost.absolutePath, "exec", file.absolutePath)
            }
            DotNetTarget.NET48 -> {
                // Framework boxes are loaded by the signed Windows PowerShell CLR 4 host. This
                // invokes the exact managed entry point without directly activating an unsigned exe.
                val frameworkHost = DotNetIlAssembler.findFrameworkPowerShellHost() ?: assertions.fail {
                    "No Windows PowerShell CLR 4 host found even though the toolchain assumption passed"
                }
                frameworkExecutionCommand(frameworkHost, file)
            }
            DotNetTarget.NETSTANDARD_2_0 -> assertions.fail {
                "The library-only netstandard2.0 profile cannot execute box tests"
            }
        }

        // Windows Smart App Control makes a per-file cloud-reputation call the first time the CLR
        // loads a freshly assembled unsigned assembly and fails-closed on a negative verdict
        // (FileLoadException, HRESULT 0x800711C7). A signed host avoids direct unsigned-executable
        // activation, but it cannot override a block on mapping the managed assembly itself.
        // Measured: the verdict is a function of the assembly CONTENT, not just its hash, so for an
        // affected test program the block is deterministic and re-running never clears it. We
        // retry to absorb a genuinely in-flight verdict, then abort the test as SKIPPED with a diagnostic
        // that names SAC: like a missing toolchain, a host that refuses to load the assembly is an
        // environment that cannot execute the test — the test still runs everywhere SAC is not
        // enforced. Skipping (visible in reports) is not a reputation bypass; rewriting the test
        // or perturbing artifact hashes to dodge the classifier would be, and is out of bounds.
        // See 'Box tests' in compiler/ir/backend.dotnet/AGENTS.md.
        var lastBlockedMessage: String? = null
        repeat(SAC_MAX_ATTEMPTS) {
            val execution = execManaged(command, file)
            val exitCode = execution.first
            val output = execution.second
            if (exitCode == 0) return output
            if (!isSmartAppControlBlock(output)) {
                assertions.fail {
                    ".NET executable failed with exit code $exitCode${output.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()}"
                }
            }
            lastBlockedMessage = output
            Thread.sleep(SAC_RETRY_DELAY_MS)
        }
        val blockedMessage =
            "Windows Smart App Control blocked loading the assembled test on all $SAC_MAX_ATTEMPTS " +
                    "attempts: ${file.path}\n" +
                    "The SmartScreen verdict is content-derived and can be deterministically negative for a " +
                    "specific test program (measured: the same IL reassembled under a fresh hash is blocked " +
                    "again), so re-running may not help. The test executes on hosts without Smart App Control; " +
                    "to run it here, sign the test assemblies with a reputable certificate or turn SAC off " +
                    "(SAC has no per-file/per-directory exclusions).\n" +
                    "Last output: $lastBlockedMessage"
        if (dotNetToolchainIsRequired()) {
            assertions.fail { "$blockedMessage\nKOTLIN_DOTNET_REQUIRE_TOOLCHAIN is enabled, so execution may not be skipped." }
        }
        throw TestAbortedException(blockedMessage)
    }

    private fun execManaged(command: List<String>, artifact: File): Pair<Int, String> {
        val process = ProcessBuilder(command)
            .directory(artifact.parentFile)
            .redirectErrorStream(true)
            .start()
        if (!process.waitFor(3, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            assertions.fail { ".NET executable timed out: ${artifact.path}" }
        }
        return process.exitValue() to process.inputStream.bufferedReader().readText()
    }

    private fun frameworkExecutionCommand(host: File, assembly: File): List<String> {
        val escapedAssemblyPath = assembly.absolutePath.replace("'", "''")
        val command = """
            ${'$'}ErrorActionPreference = 'Stop'
            try {
                ${'$'}assembly = [Reflection.Assembly]::LoadFrom('$escapedAssemblyPath')
                ${'$'}entryPoint = ${'$'}assembly.EntryPoint
                if (${'$'}null -eq ${'$'}entryPoint) { throw 'Assembly has no managed entry point.' }
                if (${'$'}entryPoint.GetParameters().Count -eq 0) {
                    [void] ${'$'}entryPoint.Invoke(${'$'}null, ${'$'}null)
                } else {
                    [void] ${'$'}entryPoint.Invoke(${'$'}null, [object[]] @(,[string[]] @()))
                }
            } catch {
                [Console]::Error.WriteLine(${'$'}_.Exception.ToString())
                exit 1
            }
        """.trimIndent()
        return listOf(host.path, "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", command)
    }

    /**
     * `0x800711C7` is `ERROR_SYSTEM_INTEGRITY_POLICY_VIOLATION` as an HRESULT — the exact code the
     * CLR's `System.IO.FileLoadException` carries when Code Integrity (Smart App Control) refuses
     * to map the assembly. The prose is matched as a fallback for host-level block messages.
     */
    private fun isSmartAppControlBlock(output: String): Boolean =
        output.contains("0x800711C7") || output.contains("Application Control policy has blocked this file")

    private companion object {
        const val DEFAULT_EXPECTED_RESULT = "OK"
        const val OUTPUT_EXTENSION = "box.txt"
        const val SAC_MAX_ATTEMPTS = 3
        const val SAC_RETRY_DELAY_MS = 1_000L
    }
}

private fun dotNetToolchainIsRequired(): Boolean =
    System.getenv("KOTLIN_DOTNET_REQUIRE_TOOLCHAIN")?.let { value ->
        value == "1" || value.equals("true", ignoreCase = true)
    } == true
