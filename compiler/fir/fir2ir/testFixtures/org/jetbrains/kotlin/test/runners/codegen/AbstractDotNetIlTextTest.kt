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
import org.jetbrains.kotlin.backend.dotnet.DotNetRuntimeArtifact
import org.jetbrains.kotlin.backend.dotnet.DotNetStdlibArtifact
import org.jetbrains.kotlin.backend.dotnet.dotNetExports
import org.jetbrains.kotlin.backend.dotnet.dotNetFriendPaths
import org.jetbrains.kotlin.backend.dotnet.dotNetPropertyExports
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.config.addKotlinSourceRoot
import org.jetbrains.kotlin.cli.dotnet.config.addDotNetClasspathRoot
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.pipeline.dotnet.DotNetBackendPipelinePhase
import org.jetbrains.kotlin.cli.pipeline.dotnet.DotNetFir2IrPipelineArtifact
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerCandidateDisposition
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerCandidateClassificationRecord
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerMemberFamilyRole
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerMemberPolicy
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerOverrideTargetKind
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalFamilyArtifact
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalFamilyCodec
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalFamilyRecord
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalGenericParameterRecord
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalGenericParameterSpecialConstraint
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerConstructionMode
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerConstructorArgumentMapping
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerConstructorDelegationKind
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerConstructionRouteKind
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalConstructorRecord
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalDelegatingConstructorRecord
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalConstructorVisibility
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalCallableReflectionRecord
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalDefaultDispatcherRecord
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalDirectSuperTargetRecord
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalMemberDispatch
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalMemberFamilyRecord
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalMemberSlotRecord
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalMemberVisibility
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalMethodIdentityRecord
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalMethodSignatureRecord
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalNamedTypeCategory
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalOpenTypeDefinitionRecord
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalReflectionRecord
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalSlotDomain
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalStateRecord
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalStateVisibility
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalStateAccessConversion
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalStateAccessDomain
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalStateAccessOperation
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalStateAccessRecord
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalTargetProfile
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalTypeVisibility
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalTypeExpressionRecord
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalTypeDispatch
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalTypeKind
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalTypeScope
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalValueSlotRecord
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalizedOverrideSlotRecord
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPrototypeMemberSnapshot
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPrototypeSnapshot
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerRuntimeClassificationMode
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerReflectionCallableExposure
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerReflectionCapabilityExposure
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerReflectionClassifierNormalizationMode
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerReflectionTypeArgumentAuthority
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerSemanticHookReason
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerStateCarrierRequirement
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerWriteValueProvenance
import org.jetbrains.kotlin.backend.dotnet.resolveExternalPhysicalFamilies
import org.jetbrains.kotlin.backend.dotnet.requirePhysicalFamily
import org.jetbrains.kotlin.backend.dotnet.reflectionClassifierForExactOpenTypeDefinitionOrNull
import org.jetbrains.kotlin.backend.dotnet.reflectionClassifierMatchesAncestry
import org.jetbrains.kotlin.backend.dotnet.physicalizeExternalSubclass
import org.jetbrains.kotlin.backend.dotnet.planFiniteOpenNullableConstruction
import org.jetbrains.kotlin.cli.pipeline.dotnet.DotNetFir2IrPipelinePhase
import org.jetbrains.kotlin.cli.pipeline.dotnet.DotNetFrontendPipelineArtifact
import org.jetbrains.kotlin.cli.pipeline.dotnet.DotNetFrontendPipelinePhase
import org.jetbrains.kotlin.cli.pipeline.dotnet.DotNetKlibInliningPipelinePhase
import org.jetbrains.kotlin.cli.pipeline.dotnet.DotNetLibraryMetadataFinalizationPipelinePhase
import org.jetbrains.kotlin.cli.pipeline.dotnet.DotNetLibraryMetadataSerializationPipelinePhase
import org.jetbrains.kotlin.cli.pipeline.withNewDiagnosticCollector
import org.jetbrains.kotlin.config.AnalysisFlag
import org.jetbrains.kotlin.config.AnalysisFlags
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.DotNetTarget
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.config.LanguageVersion
import org.jetbrains.kotlin.config.LanguageVersionSettings
import org.jetbrains.kotlin.config.MessageCollectorAccess
import org.jetbrains.kotlin.config.dotNetAssemblyName
import org.jetbrains.kotlin.config.dotNetMemberReflection
import org.jetbrains.kotlin.config.dotNetOutput
import org.jetbrains.kotlin.config.dotNetProducesLibrary
import org.jetbrains.kotlin.config.dotNetTarget
import org.jetbrains.kotlin.config.languageVersionSettings
import org.jetbrains.kotlin.config.klibRelativePathBases
import org.jetbrains.kotlin.config.messageCollector
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
import org.jetbrains.kotlin.test.services.sourceFileProvider
import org.jetbrains.kotlin.test.services.targetPlatform
import org.jetbrains.kotlin.test.services.temporaryDirectoryManager
import org.jetbrains.kotlin.test.services.transitiveDependsOnDependencies
import org.jetbrains.kotlin.test.services.transitiveFriendDependencies
import org.jetbrains.kotlin.test.services.transitiveRegularDependencies
import org.jetbrains.kotlin.test.services.configuration.CommonEnvironmentConfigurator
import org.jetbrains.kotlin.test.services.configuration.addSourcesForDependsOnClosure
import org.jetbrains.kotlin.test.services.sourceProviders.CoroutineHelpersSourceFilesProvider
import org.jetbrains.kotlin.test.services.sourceProviders.MainFunctionForBlackBoxTestsSourceProvider
import org.jetbrains.kotlin.test.utils.MultiModuleInfoDumper
import org.jetbrains.kotlin.test.utils.MessageCollectorForCompilerTests
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
    private val validatesCrossAssemblerCompatibility: Boolean = false,
) : AbstractKotlinCompilerDotNetTest() {
    override fun configure(builder: TestConfigurationBuilder): Unit = with(builder) {
        configureDotNetBase(parser, outputExtension = "il")

        dotNetArtifactsHandlersStep {
            if (validatesCrossAssemblerCompatibility) {
                useHandlers(::DotNetCrossAssemblerIlTextHandler)
            } else {
                useHandlers(::DotNetIlTextHandler)
            }
        }
    }
}

open class AbstractFirLightTreeDotNetIlTextTest : AbstractDotNetIlTextTestBase(FirParser.LightTree)

@FirPsiCodegenTest
open class AbstractFirPsiDotNetIlTextTest : AbstractDotNetIlTextTestBase(FirParser.Psi)

open class AbstractFirLightTreeDotNetCrossAssemblerTest :
    AbstractDotNetIlTextTestBase(FirParser.LightTree, validatesCrossAssemblerCompatibility = true)

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

        // Use the same directive-owned coroutine helpers as the JVM/JS/Wasm shared box corpus.
        // The provider is inert unless a test declares WITH_COROUTINES.
        useAdditionalSourceProviders(::CoroutineHelpersSourceFilesProvider)
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
    @OptIn(MessageCollectorAccess::class)
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
        val metadataInput = if (loweredInput.configuration.dotNetProducesLibrary) {
            DotNetLibraryMetadataSerializationPipelinePhase.executePhase(loweredInput)
        } else {
            loweredInput
        }
        val backendOutput = DotNetBackendPipelinePhase.executePhase(metadataInput)
        val completedOutput = if (loweredInput.configuration.dotNetProducesLibrary) {
            DotNetLibraryMetadataFinalizationPipelinePhase.executePhase(backendOutput)
        } else {
            backendOutput
        }
        check(completedOutput.output.isFile) {
            val messages = (input.configuration.messageCollector as? MessageCollectorForCompilerTests)
                ?.nonSourceMessages
                ?.joinToString("\n")
                .orEmpty()
            "The .NET backend produced no file at ${completedOutput.output.path}:\n$messages"
        }
        validateGenericOwnerHardestModelPrototype(completedOutput.genericOwnerPrototypes)
        physicalizeGenericOwnerHardestModelPrototype(
            completedOutput.genericOwnerPrototypes,
            loweredInput.configuration.dotNetTarget,
            completedOutput.output,
            testServices.moduleStructure.originalTestDataFiles.single(),
            testServices.getOrCreateTempDirectory("generic-owner-snapshot-physicalizer"),
        )
        return BinaryArtifacts.DotNet(completedOutput.output)
    }
}

private fun validateGenericOwnerHardestModelPrototype(
    prototypes: List<DotNetGenericOwnerPrototypeSnapshot>,
) {
    fun DotNetGenericOwnerPrototypeSnapshot.hasSimpleName(name: String): Boolean =
        ownerName == name || ownerName.endsWith(".$name")

    prototypes.singleOrNull { prototype -> prototype.hasSimpleName("WidenedProbe") }?.let { probe ->
        check(probe.states.singleOrNull()?.let { state ->
            state.fieldName == "expected" &&
                    state.requirement == DotNetGenericOwnerStateCarrierRequirement.TYPED_STORAGE_PRODUCER_GRAPH_PROVEN
        } == true) {
            "Only owner-dependent expected:T, not lastPacket:Any?, may enter the generic-owner carrier proof: ${probe.states}"
        }
    }

    prototypes.singleOrNull { prototype -> prototype.hasSimpleName("ConsumerUnsafeLeaf") }?.let { consumer ->
        check(consumer.disposition ==
                DotNetGenericOwnerCandidateDisposition.REQUIRES_EXTERNAL_OVERRIDE_BINDING_SCHEMA) {
            "A generic consumer subclass must remain blocked until the producer family binding schema is available: $consumer"
        }
        mapOf(
            "writeUnsafe" to setOf(DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY),
            "read" to setOf(DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY),
            "echo" to setOf(DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY),
            "label" to setOf(DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY),
        ).forEach { entry ->
            val memberName = entry.key
            val expectedRoles = entry.value
            val member = consumer.members.single { candidate -> candidate.sourceName == memberName }
            check(member.overrideBindings.map { binding -> binding.role }.toSet() == expectedRoles &&
                    member.overrideBindings.all { binding ->
                        binding.targetKind == DotNetGenericOwnerOverrideTargetKind.EXTERNAL_LOGICAL_BINDING_REQUIRED &&
                        binding.overriddenLogicalBindingKey != null
                    }) {
                "ConsumerUnsafeLeaf.$memberName must retain an external logical family requirement: $member"
            }
        }
    }

    // This is a test-fixture assertion, not a compiler selector. The normal backend constructs
    // snapshots for every generic class and always emits the erased production ABI. When the
    // hostile oracle is present, require its exact detached prototype before discarding snapshots.
    if (prototypes.none { prototype -> prototype.hasSimpleName("HostileUnsafeProducer") }) return

    val unsafeProducer = prototypes.single { prototype ->
        prototype.hasSimpleName("HostileUnsafeProducer")
    }
    val producerState = unsafeProducer.states.single()
    check(producerState.requirement ==
            DotNetGenericOwnerStateCarrierRequirement.TYPED_STORAGE_PRODUCER_GRAPH_PROVEN &&
            producerState.initializationWriterLabels == listOf("<field-initializer:expected>") &&
            producerState.writes.singleOrNull()?.let { write ->
                write.producerName == "<field-initializer:expected>" &&
                        write.provenance == DotNetGenericOwnerWriteValueProvenance.PHYSICALLY_TYPED
            } == true &&
            !producerState.externalAccessGraphRequired) {
        "HostileUnsafeProducer's private read-only state must remain typed after complete producer analysis: $producerState"
    }
    val mixed = prototypes.single { prototype -> prototype.hasSimpleName("HostileMixed") }
    val describe = mixed.members.single { member -> member.sourceName == "describe" }
    check(describe.returnSlotDomain == DotNetGenericOwnerPhysicalSlotDomain.DECLARATION_INDEPENDENT &&
            describe.parameterSlotDomains == listOf(
                DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_INPUT,
                DotNetGenericOwnerPhysicalSlotDomain.BROAD_CANDIDATE_INPUT,
            )) {
        "HostileMixed.describe must classify strict and broad owner inputs independently: $describe"
    }

    val nullableDerived = prototypes.single { prototype ->
        prototype.hasSimpleName("HostileNullableDerived")
    }
    check(nullableDerived.disposition ==
            DotNetGenericOwnerCandidateDisposition.BLOCKED_METADATA_FIXED_CONDITIONAL_SUPERTYPE) {
        "HostileNullableDerived must remain blocked by its metadata-fixed T? supertype"
    }
    check(nullableDerived.metadataFixedConditionalSupertypeCount == 1) {
        "HostileNullableDerived must record exactly one metadata-fixed conditional supertype"
    }
    val directBaseRead = nullableDerived.members.single { member ->
        member.sourceName == "readDirectFromBase"
    }
    check(directBaseRead.directSuperCallCount == 1) {
        "HostileNullableDerived.readDirectFromBase must retain one exact direct-super target"
    }

    val unsafeStore = prototypes.single { prototype ->
        prototype.hasSimpleName("HostileUnsafeStore")
    }
    val primaryConstructor = unsafeStore.constructors.single { constructor ->
        constructor.parameterSlotDomains == listOf(DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_INPUT)
    }
    val secondaryConstructor = unsafeStore.constructors.single { constructor ->
        constructor.parameterSlotDomains == listOf(
            DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_INPUT,
            DotNetGenericOwnerPhysicalSlotDomain.DECLARATION_INDEPENDENT,
        )
    }
    check(!primaryConstructor.delegatesToThis && secondaryConstructor.delegatesToThis &&
            secondaryConstructor.delegatedConstructorLogicalBindingKey == primaryConstructor.logicalBindingKey) {
        "HostileUnsafeStore must retain exact primary/secondary construction joins: ${unsafeStore.constructors}"
    }
    check(primaryConstructor.exactPhysicalSignature?.parameterSlots?.singleOrNull()?.type?.kind ==
            DotNetGenericOwnerPhysicalTypeKind.OWNER_TYPE_PARAMETER &&
            secondaryConstructor.exactPhysicalSignature?.parameterSlots?.map { slot -> slot.type.kind } == listOf(
                DotNetGenericOwnerPhysicalTypeKind.OWNER_TYPE_PARAMETER,
                DotNetGenericOwnerPhysicalTypeKind.INT32,
            )) {
        "HostileUnsafeStore constructors must use compiler-derived owner/int carriers"
    }
    check(unsafeStore.disposition ==
            DotNetGenericOwnerCandidateDisposition.BLOCKED_OPEN_OUTPUT_STATE_COHERENCE) {
        "HostileUnsafeStore must remain blocked until typed/semantic output overrides are coherent"
    }
    val unsafeState = unsafeStore.states.single()
    check(unsafeState.requirement == DotNetGenericOwnerStateCarrierRequirement.SEMANTIC_OBJECT_REQUIRED) {
        "HostileUnsafeStore's one state field must accept widened semantic writes"
    }
    check(unsafeState.fieldName == "stored" &&
            unsafeState.directWriterNames == listOf("<set-stored>") &&
            unsafeState.semanticReachableWriterNames == listOf("<set-stored>") &&
            unsafeState.initializationWriterLabels == listOf("<field-initializer:stored>") &&
            unsafeState.writes.associate { write -> write.producerName to write.provenance } == mapOf(
                "<set-stored>" to DotNetGenericOwnerWriteValueProvenance.SEMANTIC_OBJECT,
                "<field-initializer:stored>" to DotNetGenericOwnerWriteValueProvenance.PHYSICALLY_TYPED,
            ) &&
            !unsafeState.externalAccessGraphRequired) {
        "HostileUnsafeStore's private state write must be found through its lowered setter call graph: $unsafeState"
    }

    val write = unsafeStore.members.single { member -> member.sourceName == "writeUnsafe" }
    check(write.policy == DotNetGenericOwnerMemberPolicy.SEMANTIC_BODY)
    check(write.roles == setOf(
        DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY,
        DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK,
        DotNetGenericOwnerMemberFamilyRole.CAPABILITY_DISPATCHER,
    ))
    check(write.semanticHookReasons ==
            setOf(DotNetGenericOwnerSemanticHookReason.GENERAL_WIDENED_BODY))
    check(write.typedRetainsOwnerDependentInput && write.semanticErasesOwnerDependentInput)
    check(write.returnSlotDomain == DotNetGenericOwnerPhysicalSlotDomain.DECLARATION_INDEPENDENT &&
            write.parameterSlotDomains == listOf(DotNetGenericOwnerPhysicalSlotDomain.BROAD_CANDIDATE_INPUT)) {
        "HostileUnsafeStore.writeUnsafe must retain its exact broad-candidate domain vector: $write"
    }
    check(write.exactPhysicalSignatures?.keys == write.roles &&
            write.exactPhysicalSignatures?.get(DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY)
                ?.parameterSlots?.singleOrNull()?.type?.kind ==
                DotNetGenericOwnerPhysicalTypeKind.OWNER_TYPE_PARAMETER &&
            write.exactPhysicalSignatures?.get(DotNetGenericOwnerMemberFamilyRole.CAPABILITY_DISPATCHER)
                ?.parameterSlots?.singleOrNull()?.type?.kind == DotNetGenericOwnerPhysicalTypeKind.OBJECT) {
        "HostileUnsafeStore.writeUnsafe must retain its compiler-derived typed/capability carriers"
    }
    check(write.requiresDirectSuperTargets)
    check(write.directStateWriteNames.isEmpty() &&
            write.directProducerCallNames == listOf("installUnchecked") &&
            write.transitiveStateWriteNames == listOf("stored")) {
        "HostileUnsafeStore.writeUnsafe must reach, but not directly contain, its semantic state write: $write"
    }

    val installer = unsafeStore.members.single { member -> member.sourceName == "installUnchecked" }
    check(installer.policy == DotNetGenericOwnerMemberPolicy.STRICT_TYPED &&
            installer.directStateWriteNames.isEmpty() &&
            installer.directProducerCallNames == listOf("<set-stored>") &&
            installer.transitiveStateWriteNames == listOf("stored") &&
            installer.reachableFromSemanticEntry) {
        "HostileUnsafeStore.installUnchecked must be tainted by the widened entry call closure: $installer"
    }
    val storedSetter = unsafeStore.members.single { member -> member.sourceName == "<set-stored>" }
    check(storedSetter.policy == DotNetGenericOwnerMemberPolicy.STRICT_TYPED &&
            storedSetter.directStateWriteNames == listOf("stored") &&
            storedSetter.reachableFromSemanticEntry) {
        "The private lowered setter must be a strict helper reached from, not itself classified as, a semantic entry: $storedSetter"
    }

    val typedStore = prototypes.single { prototype ->
        prototype.hasSimpleName("HostileTypedStore")
    }
    val typedState = typedStore.states.single()
    check(typedState.requirement ==
            DotNetGenericOwnerStateCarrierRequirement.TYPED_STORAGE_PRODUCER_GRAPH_PROVEN &&
            typedState.writes.associate { write -> write.producerName to write.provenance } == mapOf(
                "<set-stored>" to DotNetGenericOwnerWriteValueProvenance.PHYSICALLY_TYPED,
                "<field-initializer:stored>" to DotNetGenericOwnerWriteValueProvenance.PHYSICALLY_TYPED,
            )) {
        "HostileTypedStore must prove both initializer and function writes physically typed: $typedState"
    }
    val typedWrite = typedStore.members.single { member -> member.sourceName == "write" }
    check(typedWrite.directProducerCallNames == listOf("installBoxed") &&
            typedWrite.transitiveStateWriteNames == listOf("stored")) {
        "HostileTypedStore.write must retain its boxed helper write chain: $typedWrite"
    }

    val unsafeDerived = prototypes.singleOrNull { prototype ->
        prototype.hasSimpleName("HostileUnsafeDerived")
    } ?: prototypes.single { prototype ->
        prototype.hasSimpleName("HostileUnsafeMid")
    }
    check(unsafeDerived.constructors.singleOrNull()?.let { constructor ->
        !constructor.delegatesToThis &&
                constructor.parameterSlotDomains ==
                listOf(DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_INPUT) &&
                constructor.delegatedConstructorLogicalBindingKey == primaryConstructor.logicalBindingKey &&
                constructor.delegatedOwnerName?.endsWith("HostileUnsafeStore") == true
    } == true) {
        "${unsafeDerived.ownerName} must retain its exact generic base-constructor join: ${unsafeDerived.constructors}"
    }
    listOf("writeUnsafe", "read").forEach { memberName ->
        val member = unsafeDerived.members.single { candidate -> candidate.sourceName == memberName }
        check(DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK in member.roles &&
                DotNetGenericOwnerSemanticHookReason.INHERITED_SEMANTIC_OVERRIDE in member.semanticHookReasons &&
                member.overrideBindings.map { binding -> binding.role }.toSet() == setOf(
                    DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY,
                    DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK,
                ) &&
                member.overrideBindings.all { binding ->
                    binding.targetKind == DotNetGenericOwnerOverrideTargetKind.LOCAL_DETACHED_PROTOTYPE
                } && member.directSuperCalls.size == member.directSuperCallCount &&
                member.directSuperCalls.singleOrNull()?.let { call ->
                    call.logicalOwnerName.endsWith("HostileUnsafeStore") &&
                            call.superQualifierName.endsWith("HostileUnsafeStore")
                } == true) {
            "${unsafeDerived.ownerName}.$memberName must bind typed and inherited semantic families independently: $member"
        }
    }

    val read = unsafeStore.members.single { member -> member.sourceName == "read" }
    check(read.policy == DotNetGenericOwnerMemberPolicy.STRICT_TYPED)
    check(read.roles == setOf(
        DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY,
        DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK,
        DotNetGenericOwnerMemberFamilyRole.CAPABILITY_DISPATCHER,
    ))
    check(read.semanticHookReasons ==
            setOf(DotNetGenericOwnerSemanticHookReason.PAIRED_OPEN_OUTPUT_STATE))
    check(read.typedRetainsOwnerDependentOutput && read.semanticErasesOwnerDependentOutput)
    check(read.returnSlotDomain == DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_OUTPUT &&
            read.parameterSlotDomains.isEmpty()) {
        "HostileUnsafeStore.read must retain its exact strict-output domain vector: $read"
    }
    check(read.requiresDirectSuperTargets)

    val echo = unsafeStore.members.single { member -> member.sourceName == "echo" }
    check(echo.returnSlotDomain == DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_OUTPUT &&
            echo.parameterSlotDomains == listOf(DotNetGenericOwnerPhysicalSlotDomain.BROAD_CANDIDATE_INPUT)) {
        "HostileUnsafeStore.echo must retain its nested broad-input/strict-output domain vector: $echo"
    }
    val relay = unsafeStore.members.single { member -> member.sourceName == "relay" }
    check(relay.returnSlotDomain == DotNetGenericOwnerPhysicalSlotDomain.DECLARATION_INDEPENDENT &&
            relay.parameterSlotDomains == listOf(DotNetGenericOwnerPhysicalSlotDomain.DECLARATION_INDEPENDENT) &&
            relay.exactPhysicalSignatures?.get(DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY)
                ?.returnSlot?.type?.arguments?.singleOrNull()?.kind ==
                DotNetGenericOwnerPhysicalTypeKind.METHOD_TYPE_PARAMETER) {
        "HostileUnsafeStore.relay must keep method-owned carriers outside the owner slot domain: $relay"
    }
    val label = unsafeStore.members.single { member -> member.sourceName == "label" }
    check(label.hasMaskedDefaultDispatcher &&
            label.exactMaskedDefaultDispatcher?.parameterSlotsAfterReceiver?.map { slot -> slot.type.kind } ==
            listOf(DotNetGenericOwnerPhysicalTypeKind.STRING, DotNetGenericOwnerPhysicalTypeKind.INT32)) {
        "HostileUnsafeStore.label must retain the lowered compiler-derived default helper tail"
    }

    val hostileCell = prototypes.single { prototype -> prototype.hasSimpleName("HostileCell") }
    val readOr = hostileCell.members.single { member -> member.sourceName == "readOr" }
    check(readOr.hasMaskedDefaultDispatcher) {
        "HostileCell.readOr must retain its selected masked default dispatcher"
    }

    if (unsafeStore.ownerName.contains('.')) {
        check(unsafeStore.logicalBindingKey != null &&
                write.logicalBindingKey != null && read.logicalBindingKey != null) {
            "The separate hostile producer must retain owner/member KLIB binding identities"
        }
    }
}

private const val GENERIC_OWNER_PHYSICAL_FAMILY_FILE = "SnapshotProducer.generic-owner-families"
private const val GENERIC_OWNER_MEASUREMENT_PROJECT_FILE = "RecordedFamilyMeasurement.csproj"
private const val GENERIC_OWNER_MEASUREMENT_MANIFEST_FILE = "generic-owner-measurement.properties"
private const val GENERIC_OWNER_MEASUREMENT_EXPORT_PROPERTY =
    "kotlin.dotnet.genericOwnerMeasurementDir"
private const val GENERIC_OWNER_APPLICATION_EXPORT_PROPERTY =
    "kotlin.dotnet.genericOwnerApplicationDir"
private const val GENERIC_OWNER_MEASUREMENT_WORKLOAD_VERSION = 2
private const val GENERIC_OWNER_ERASED_PRODUCER_FILE = "lib.dll"
private const val GENERIC_OWNER_ERASED_CSHARP_SOURCE_FILE = "ErasedCSharpConsumer.cs"
private const val GENERIC_OWNER_APPLICATION_SOURCE_FILE = "genericOwnerHardestModelOracle.kt"
private const val GENERIC_OWNER_APPLICATION_MANIFEST_FILE = "generic-owner-application.properties"

private fun genericOwnerErasedConsumerFile(target: DotNetTarget): String =
    if (target == DotNetTarget.NET48) "ErasedConsumer.exe" else "ErasedConsumer.dll"

private fun genericOwnerErasedCSharpAssemblyFile(target: DotNetTarget): String =
    if (target == DotNetTarget.NET48) "ErasedCSharpConsumer.exe" else "ErasedCSharpConsumer.dll"

private fun genericOwnerPrototypePhysicalMethodName(
    member: DotNetGenericOwnerPrototypeMemberSnapshot,
    role: DotNetGenericOwnerMemberFamilyRole,
): String = when (role) {
    DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY -> member.physicalBaseName
    DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK -> "${member.physicalBaseName}__KotlinSemantic"
    DotNetGenericOwnerMemberFamilyRole.CAPABILITY_DISPATCHER -> "${member.physicalBaseName}__KotlinCapability"
}

private fun genericOwnerPhysicalReflectionCallables(
    members: List<DotNetGenericOwnerPhysicalMemberFamilyRecord>,
): List<DotNetGenericOwnerPhysicalCallableReflectionRecord> = members.map { member ->
    val invocationRole = if (DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK in member.roles) {
        DotNetGenericOwnerMemberFamilyRole.CAPABILITY_DISPATCHER
    } else {
        DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY
    }
    val invocationSlot = member.slots.single { slot -> slot.role == invocationRole }
    DotNetGenericOwnerPhysicalCallableReflectionRecord(
        logicalMemberKey = member.logicalMemberKey,
        exposure = DotNetGenericOwnerReflectionCallableExposure.SINGLE_LOGICAL_DECLARATION,
        invocationRole = invocationRole,
        invocationMethod = DotNetGenericOwnerPhysicalMethodIdentityRecord(
            invocationSlot.physicalOwnerPath,
            invocationSlot.physicalMethodName,
            invocationSlot.signature,
        ),
        physicalMethods = buildList {
            member.slots.forEach { slot ->
                add(DotNetGenericOwnerPhysicalMethodIdentityRecord(
                    slot.physicalOwnerPath,
                    slot.physicalMethodName,
                    slot.signature,
                ))
                slot.capabilitySlot?.let(::add)
            }
            member.defaultDispatcher?.let { dispatcher ->
                add(DotNetGenericOwnerPhysicalMethodIdentityRecord(
                    dispatcher.physicalOwnerPath,
                    dispatcher.physicalMethodName,
                    dispatcher.signature,
                ))
            }
        },
    )
}

private fun createGenericOwnerPhysicalFamilyArtifact(
    prototypes: List<DotNetGenericOwnerPrototypeSnapshot>,
    producerFingerprint: String,
    target: DotNetTarget,
): DotNetGenericOwnerPhysicalFamilyArtifact {
    fun DotNetGenericOwnerPrototypeSnapshot.hasSimpleName(name: String): Boolean =
        ownerName == name || ownerName.endsWith(".$name")

    val membersByLogicalKey = prototypes.flatMap { prototype -> prototype.members }
        .mapNotNull { member -> member.logicalBindingKey?.let { key -> key to member } }
        .toMap()
    val constructorsByLogicalKey = prototypes.flatMap { prototype ->
        prototype.constructors.mapNotNull { constructor ->
            constructor.logicalBindingKey?.let { key -> key to (prototype to constructor) }
        }
    }.toMap()
    val classifications = prototypes.mapNotNull { prototype ->
        val logicalOwnerKey = prototype.logicalBindingKey ?: return@mapNotNull null
        DotNetGenericOwnerCandidateClassificationRecord(
            logicalOwnerKey = logicalOwnerKey,
            genericArity = prototype.genericArity,
            disposition = prototype.disposition,
            logicalConstructorKeys = prototype.constructors.mapNotNull { constructor ->
                constructor.logicalBindingKey
            }.distinct().sorted(),
            logicalMemberKeys = prototype.members.mapNotNull { member ->
                member.logicalBindingKey
            }.distinct().sorted(),
        )
    }.sortedBy { classification -> classification.logicalOwnerKey }
    fun overrideRoots(
        member: DotNetGenericOwnerPrototypeMemberSnapshot,
        visiting: Set<String> = emptySet(),
    ): Set<String> {
        val logicalKey = checkNotNull(member.logicalBindingKey)
        check(logicalKey !in visiting) { "Generic-owner override-family roots contain a cycle at '$logicalKey'" }
        val overriddenKeys = member.overrideBindings.mapNotNull { binding -> binding.overriddenLogicalBindingKey }
        if (overriddenKeys.isEmpty()) return setOf(logicalKey)
        return overriddenKeys.flatMapTo(linkedSetOf()) { overriddenKey ->
            membersByLogicalKey[overriddenKey]
                ?.let { overridden -> overrideRoots(overridden, visiting + logicalKey) }
                ?: setOf(overriddenKey)
        }
    }

    val owners = listOf("HostileUnsafeStore", "HostileUnsafeMid").map { simpleName ->
        val prototype = prototypes.single { candidate -> candidate.hasSimpleName(simpleName) }
        val ownerPath = listOf("KotlinSnapshotPrototype", simpleName)
        val baseOwnerPath = listOf("KotlinSnapshotPrototype", "HostileUnsafeStore")
        val capabilityOwnerPath = listOf("KotlinSnapshotPrototype", "IHostileUnsafeStoreSemantic")
        val constructors = prototype.constructors.map { constructor ->
            val logicalConstructorKey = checkNotNull(constructor.logicalBindingKey) {
                "The hostile physical family requires a logical constructor binding"
            }
            val signature = checkNotNull(constructor.exactPhysicalSignature) {
                "The hostile physical family requires a compiler-derived constructor signature"
            }
            val delegatedOwnerName = checkNotNull(constructor.delegatedOwnerName) {
                "The hostile physical family requires an exact delegated constructor owner"
            }
            val delegated = constructor.delegatedConstructorLogicalBindingKey?.let(constructorsByLogicalKey::get)
            val delegatedSignature = delegated?.let { pair ->
                checkNotNull(pair.second.exactPhysicalSignature) {
                    "The hostile physical family requires a compiler-derived delegated constructor signature"
                }
            } ?: DotNetGenericOwnerPhysicalMethodSignatureRecord(
                isInstance = true,
                genericArity = 0,
                returnSlot = DotNetGenericOwnerPhysicalValueSlotRecord(
                    DotNetGenericOwnerPhysicalSlotDomain.DECLARATION_INDEPENDENT,
                    DotNetGenericOwnerPhysicalTypeExpressionRecord.voidType(),
                ),
                parameterSlots = emptyList(),
            )
            val delegatedOwnerType = when {
                delegatedOwnerName == "kotlin.Any" || delegatedOwnerName == "Any" ->
                    DotNetGenericOwnerPhysicalTypeExpressionRecord.coreType(
                        typePath = listOf("System", "Object"),
                        category = DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS,
                    )
                else -> DotNetGenericOwnerPhysicalTypeExpressionRecord.producerType(
                    typePath = if (constructor.delegatesToThis) {
                        ownerPath
                    } else {
                        listOf("KotlinSnapshotPrototype", delegatedOwnerName.substringAfterLast('.'))
                    },
                    category = DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS,
                    arguments = listOf(DotNetGenericOwnerPhysicalTypeExpressionRecord.ownerParameter(0)),
                )
            }
            DotNetGenericOwnerPhysicalConstructorRecord(
                logicalConstructorKey = logicalConstructorKey,
                constructionMode = DotNetGenericOwnerConstructionMode.STATIC_EXACT,
                visibility = DotNetGenericOwnerPhysicalConstructorVisibility.PUBLIC,
                constructedOwnerType = DotNetGenericOwnerPhysicalTypeExpressionRecord.producerType(
                    typePath = ownerPath,
                    category = DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS,
                    arguments = List(prototype.genericArity) { index ->
                        DotNetGenericOwnerPhysicalTypeExpressionRecord.ownerParameter(index)
                    },
                ),
                physicalConstructor = DotNetGenericOwnerPhysicalMethodIdentityRecord(
                    physicalOwnerPath = ownerPath,
                    physicalMethodName = ".ctor",
                    signature = signature,
                ),
                delegation = DotNetGenericOwnerPhysicalDelegatingConstructorRecord(
                    kind = if (constructor.delegatesToThis) {
                        DotNetGenericOwnerConstructorDelegationKind.THIS
                    } else {
                        DotNetGenericOwnerConstructorDelegationKind.BASE
                    },
                    logicalConstructorKey = constructor.delegatedConstructorLogicalBindingKey,
                    physicalOwnerType = delegatedOwnerType,
                    physicalMethodName = ".ctor",
                    signature = delegatedSignature,
                ),
            )
        }
        check(constructors.map { constructor -> constructor.logicalConstructorKey }.toSet() ==
                prototype.constructors.mapNotNull { constructor -> constructor.logicalBindingKey }.toSet()) {
            "The hostile producer physical-family record omitted a bindable logical constructor"
        }
        val members = prototype.members.mapNotNull { member ->
            val logicalMemberKey = member.logicalBindingKey ?: return@mapNotNull null
            DotNetGenericOwnerPhysicalMemberFamilyRecord(
                logicalMemberKey = logicalMemberKey,
                overrideRootLogicalMemberKeys = overrideRoots(member).sorted(),
                policy = member.policy,
                roles = member.roles,
                semanticHookReasons = member.semanticHookReasons,
                slots = member.roles.map { role ->
                    val methodName = genericOwnerPrototypePhysicalMethodName(member, role)
                    val signature = checkNotNull(member.exactPhysicalSignatures?.get(role)) {
                        "The hostile physical family requires a compiler-derived signature for ${member.sourceName}/$role"
                    }
                    val isCapability = role == DotNetGenericOwnerMemberFamilyRole.CAPABILITY_DISPATCHER
                    DotNetGenericOwnerPhysicalMemberSlotRecord(
                        role = role,
                        physicalOwnerPath = if (isCapability) baseOwnerPath else ownerPath,
                        physicalMethodName = if (isCapability) {
                            "${capabilityOwnerPath.joinToString(".")}.$methodName"
                        } else {
                            methodName
                        },
                        dispatch = when {
                            isCapability ->
                                DotNetGenericOwnerPhysicalMemberDispatch.FINAL
                            member.isAbstract -> DotNetGenericOwnerPhysicalMemberDispatch.ABSTRACT
                            member.isOverridable -> DotNetGenericOwnerPhysicalMemberDispatch.OVERRIDABLE
                            else -> DotNetGenericOwnerPhysicalMemberDispatch.FINAL
                        },
                        signature = signature,
                        capabilitySlot = if (isCapability) {
                            DotNetGenericOwnerPhysicalMethodIdentityRecord(
                                physicalOwnerPath = capabilityOwnerPath,
                                physicalMethodName = methodName,
                                signature = signature,
                            )
                        } else {
                            null
                        },
                    )
                },
                directSuperTargets = member.directSuperCalls.flatMap { call ->
                    val logicalTargetKey = checkNotNull(call.logicalMemberKey) {
                        "The separate hostile producer requires logical direct-super targets"
                    }
                    member.roles.filter { role ->
                        role != DotNetGenericOwnerMemberFamilyRole.CAPABILITY_DISPATCHER
                    }.map { role ->
                        DotNetGenericOwnerPhysicalDirectSuperTargetRecord(
                            role = role,
                            logicalTargetMemberKey = logicalTargetKey,
                            physicalOwnerPath = listOf(
                                "KotlinSnapshotPrototype",
                                call.superQualifierName.substringAfterLast('.'),
                            ),
                            physicalMethodName = genericOwnerPrototypePhysicalMethodName(member, role),
                            signature = checkNotNull(member.exactPhysicalSignatures?.get(role)) {
                                "The hostile physical family requires a compiler-derived direct-super signature"
                            },
                        )
                    }
                },
                defaultDispatcher = if (member.hasMaskedDefaultDispatcher) {
                    val dispatcher = checkNotNull(member.exactMaskedDefaultDispatcher) {
                        "The hostile physical family requires a compiler-derived default helper signature"
                    }
                    DotNetGenericOwnerPhysicalDefaultDispatcherRecord(
                        physicalOwnerPath = listOf("KotlinSnapshotPrototype", simpleName),
                        physicalMethodName = "${genericOwnerPrototypePhysicalMethodName(member, DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY)}Default",
                        signature = dispatcher.physicalSignature(ownerPath, prototype.genericArity),
                    )
                } else {
                    null
                },
            )
        }
        check(members.map { member -> member.logicalMemberKey }.toSet() ==
                prototype.members.mapNotNull { member -> member.logicalBindingKey }.toSet()) {
            "The hostile producer physical-family record omitted a bindable logical member"
        }
        DotNetGenericOwnerPhysicalFamilyRecord(
            logicalOwnerKey = checkNotNull(prototype.logicalBindingKey) {
                "The separate hostile producer requires a logical owner binding"
            },
            physicalOwnerPath = ownerPath,
            physicalCapabilityOwnerPath = capabilityOwnerPath,
            genericArity = prototype.genericArity,
            physicalGenericParameters = checkNotNull(prototype.physicalGenericParameters) {
                "The hostile physical family requires exact GenericParam constraints"
            },
            disposition = prototype.disposition,
            runtimeClassificationMode = DotNetGenericOwnerRuntimeClassificationMode.OPEN_TYPEDEF_ANCESTRY,
            constructionModes = setOf(DotNetGenericOwnerConstructionMode.STATIC_EXACT),
            constructors = constructors,
            reflection = DotNetGenericOwnerPhysicalReflectionRecord(
                logicalClassifierKey = checkNotNull(prototype.logicalBindingKey),
                physicalOpenTypeDefinition = DotNetGenericOwnerPhysicalOpenTypeDefinitionRecord(
                    physicalTypePath = ownerPath,
                    genericArity = prototype.genericArity,
                ),
                classifierNormalizationMode =
                    DotNetGenericOwnerReflectionClassifierNormalizationMode.EXACT_OPEN_TYPEDEF,
                instanceClassificationMode = DotNetGenericOwnerRuntimeClassificationMode.OPEN_TYPEDEF_ANCESTRY,
                typeArgumentAuthority = DotNetGenericOwnerReflectionTypeArgumentAuthority.KLIB_LOGICAL_GRAPH,
                capabilityExposure = DotNetGenericOwnerReflectionCapabilityExposure.HIDDEN_COMPILER_ABI,
                callables = genericOwnerPhysicalReflectionCallables(members),
            ),
            members = members,
            states = prototype.states.map { state ->
                fun access(
                    source: DotNetGenericOwnerPrototypeMemberSnapshot,
                    domain: DotNetGenericOwnerPhysicalStateAccessDomain,
                    operation: DotNetGenericOwnerPhysicalStateAccessOperation,
                    conversion: DotNetGenericOwnerPhysicalStateAccessConversion,
                ): DotNetGenericOwnerPhysicalStateAccessRecord {
                    val logicalMemberKey = checkNotNull(source.logicalBindingKey)
                    val role = when (domain) {
                        DotNetGenericOwnerPhysicalStateAccessDomain.TYPED ->
                            DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY
                        DotNetGenericOwnerPhysicalStateAccessDomain.SEMANTIC ->
                            DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK
                    }
                    val slot = members.single { member -> member.logicalMemberKey == logicalMemberKey }
                        .slots.single { candidate -> candidate.role == role }
                    return DotNetGenericOwnerPhysicalStateAccessRecord(
                        domain = domain,
                        operation = operation,
                        conversion = conversion,
                        logicalMemberKey = logicalMemberKey,
                        role = role,
                        physicalMethod = DotNetGenericOwnerPhysicalMethodIdentityRecord(
                            physicalOwnerPath = slot.physicalOwnerPath,
                            physicalMethodName = slot.physicalMethodName,
                            signature = slot.signature,
                        ),
                    )
                }
                fun semanticStateFamily(
                    operation: DotNetGenericOwnerPhysicalStateAccessOperation,
                ): DotNetGenericOwnerPrototypeMemberSnapshot = prototype.members.single { member ->
                    val reachesState = when (operation) {
                        DotNetGenericOwnerPhysicalStateAccessOperation.READ ->
                            state.fieldName in member.transitiveStateReadNames
                        DotNetGenericOwnerPhysicalStateAccessOperation.WRITE ->
                            state.fieldName in member.transitiveStateWriteNames
                    }
                    reachesState &&
                            DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY in member.roles &&
                            DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK in member.roles
                }
                DotNetGenericOwnerPhysicalStateRecord(
                    logicalFieldName = state.fieldName,
                    physicalFieldName = state.fieldName,
                    physicalVisibility = DotNetGenericOwnerPhysicalStateVisibility.PRIVATE,
                    requirement = state.requirement,
                    physicalType = when (state.requirement) {
                        DotNetGenericOwnerStateCarrierRequirement.SEMANTIC_OBJECT_REQUIRED ->
                            DotNetGenericOwnerPhysicalTypeExpressionRecord.objectType()
                        DotNetGenericOwnerStateCarrierRequirement.TYPED_STORAGE_PRODUCER_GRAPH_PROVEN ->
                            DotNetGenericOwnerPhysicalTypeExpressionRecord.ownerParameter(0)
                        DotNetGenericOwnerStateCarrierRequirement.COMPLETE_ACCESS_GRAPH_REQUIRED,
                        DotNetGenericOwnerStateCarrierRequirement.TYPED_WRITE_VALUE_PROVENANCE_REQUIRED,
                        -> error("The hostile physical family cannot publish unresolved state storage")
                    },
                    accessPaths = when (state.requirement) {
                        DotNetGenericOwnerStateCarrierRequirement.SEMANTIC_OBJECT_REQUIRED -> {
                            val writer = semanticStateFamily(DotNetGenericOwnerPhysicalStateAccessOperation.WRITE)
                            val reader = semanticStateFamily(DotNetGenericOwnerPhysicalStateAccessOperation.READ)
                            listOf(
                                access(
                                    writer,
                                    DotNetGenericOwnerPhysicalStateAccessDomain.TYPED,
                                    DotNetGenericOwnerPhysicalStateAccessOperation.WRITE,
                                    DotNetGenericOwnerPhysicalStateAccessConversion.INPUT_TO_STATE_BOX_OR_REFERENCE_WIDEN,
                                ),
                                access(
                                    writer,
                                    DotNetGenericOwnerPhysicalStateAccessDomain.SEMANTIC,
                                    DotNetGenericOwnerPhysicalStateAccessOperation.WRITE,
                                    DotNetGenericOwnerPhysicalStateAccessConversion.IDENTITY,
                                ),
                                access(
                                    reader,
                                    DotNetGenericOwnerPhysicalStateAccessDomain.TYPED,
                                    DotNetGenericOwnerPhysicalStateAccessOperation.READ,
                                    DotNetGenericOwnerPhysicalStateAccessConversion.STATE_TO_OUTPUT_CHECKED_CAST_OR_UNBOX,
                                ),
                                access(
                                    reader,
                                    DotNetGenericOwnerPhysicalStateAccessDomain.SEMANTIC,
                                    DotNetGenericOwnerPhysicalStateAccessOperation.READ,
                                    DotNetGenericOwnerPhysicalStateAccessConversion.IDENTITY,
                                ),
                            )
                        }
                        DotNetGenericOwnerStateCarrierRequirement.TYPED_STORAGE_PRODUCER_GRAPH_PROVEN -> emptyList()
                        DotNetGenericOwnerStateCarrierRequirement.COMPLETE_ACCESS_GRAPH_REQUIRED,
                        DotNetGenericOwnerStateCarrierRequirement.TYPED_WRITE_VALUE_PROVENANCE_REQUIRED,
                        -> error("The hostile physical family cannot publish unresolved state access")
                    },
                )
            },
        )
    }
    return DotNetGenericOwnerPhysicalFamilyArtifact(
        producerFingerprint = producerFingerprint,
        targetProfile = when (target) {
            DotNetTarget.NET48 -> DotNetGenericOwnerPhysicalTargetProfile.NET48
            DotNetTarget.NET10_0 -> DotNetGenericOwnerPhysicalTargetProfile.NET10_0
            DotNetTarget.NETSTANDARD_2_0 -> error("The hostile physical family has no netstandard profile")
        },
        classifications = classifications,
        owners = owners,
    )
}

private fun validateGenericOwnerPhysicalFamilyCodec(
    artifact: DotNetGenericOwnerPhysicalFamilyArtifact,
    encoded: String,
) {
    fun expectRejected(label: String, operation: () -> Unit) {
        check(runCatching(operation).isFailure) {
            "The generic-owner family codec accepted $label"
        }
    }

    val decoded = DotNetGenericOwnerPhysicalFamilyCodec.decode(encoded, artifact.producerFingerprint)
    check(DotNetGenericOwnerPhysicalFamilyCodec.encode(decoded) == encoded &&
            DotNetGenericOwnerPhysicalFamilyCodec.encode(artifact) == encoded) {
        "The generic-owner physical family artifact has nondeterministic serialization"
    }
    val metadataFixedExclusion = decoded.classifications.single { classification ->
        classification.disposition ==
                DotNetGenericOwnerCandidateDisposition.BLOCKED_METADATA_FIXED_CONDITIONAL_SUPERTYPE
    }
    check(metadataFixedExclusion.logicalConstructorKeys.isNotEmpty() &&
            metadataFixedExclusion.logicalMemberKeys.isNotEmpty() &&
            decoded.owners.none { owner -> owner.logicalOwnerKey == metadataFixedExclusion.logicalOwnerKey }) {
        "The producer catalog did not retain its metadata-fixed erased-only classification"
    }
    val metadataFixedFailure = runCatching {
        decoded.requirePhysicalFamily(metadataFixedExclusion.logicalOwnerKey)
    }.exceptionOrNull()
    check(metadataFixedFailure?.message?.contains(
        DotNetGenericOwnerCandidateDisposition.BLOCKED_METADATA_FIXED_CONDITIONAL_SUPERTYPE.name
    ) == true) {
        "The producer catalog did not fail with the recorded metadata-fixed disposition: $metadataFixedFailure"
    }
    decoded.owners.forEach { owner ->
        check(decoded.requirePhysicalFamily(owner.logicalOwnerKey) == owner) {
            "The producer catalog did not resolve its exact published physical family"
        }
    }
    check(decoded.owners.all { owner ->
        owner.runtimeClassificationMode == DotNetGenericOwnerRuntimeClassificationMode.OPEN_TYPEDEF_ANCESTRY &&
                owner.physicalGenericParameters == List(owner.genericArity) { index ->
                    DotNetGenericOwnerPhysicalGenericParameterRecord(index, emptySet(), emptyList())
                } &&
                owner.constructionModes == setOf(DotNetGenericOwnerConstructionMode.STATIC_EXACT) &&
                owner.constructors.isNotEmpty() && owner.constructors.all { constructor ->
                    constructor.visibility == DotNetGenericOwnerPhysicalConstructorVisibility.PUBLIC &&
                            constructor.constructedOwnerType.typePath == owner.physicalOwnerPath
                } && owner.reflection.let { reflection ->
                    reflection.logicalClassifierKey == owner.logicalOwnerKey &&
                            reflection.physicalOpenTypeDefinition.physicalTypePath == owner.physicalOwnerPath &&
                            reflection.classifierNormalizationMode ==
                            DotNetGenericOwnerReflectionClassifierNormalizationMode.EXACT_OPEN_TYPEDEF &&
                            reflection.instanceClassificationMode == owner.runtimeClassificationMode &&
                            reflection.typeArgumentAuthority ==
                            DotNetGenericOwnerReflectionTypeArgumentAuthority.KLIB_LOGICAL_GRAPH &&
                            reflection.capabilityExposure ==
                            DotNetGenericOwnerReflectionCapabilityExposure.HIDDEN_COMPILER_ABI &&
                            reflection.callables.map { callable -> callable.logicalMemberKey }.toSet() ==
                            owner.members.map { candidate -> candidate.logicalMemberKey }.toSet()
                }
    }) {
        "The generic-owner artifact lacks its exact construction/profile/reflection record"
    }
    expectRejected("a method parameter in a TypeDef GenericParam constraint") {
        DotNetGenericOwnerPhysicalGenericParameterRecord(
            index = 0,
            specialConstraints = emptySet(),
            typeConstraints = listOf(DotNetGenericOwnerPhysicalTypeExpressionRecord.methodParameter(0)),
        )
    }
    expectRejected("a consumer-compilation type in a producer GenericParam constraint") {
        artifact.copy(
            owners = artifact.owners.map { owner ->
                owner.copy(
                    physicalGenericParameters = owner.physicalGenericParameters.map { parameter ->
                        parameter.copy(
                            typeConstraints = listOf(
                                DotNetGenericOwnerPhysicalTypeExpressionRecord.currentCompilationType(
                                    typePath = listOf("ConsumerOnlyConstraint"),
                                    category = DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS,
                                ),
                            ),
                        )
                    },
                )
            },
        )
    }
    expectRejected("a stale schema") {
        DotNetGenericOwnerPhysicalFamilyCodec.decode(
            encoded.replaceFirst(
                "\t${DotNetGenericOwnerPhysicalFamilyCodec.SCHEMA_VERSION}\n",
                "\t${DotNetGenericOwnerPhysicalFamilyCodec.SCHEMA_VERSION - 1}\n",
            )
        )
    }
    expectRejected("a truncated owner family") {
        DotNetGenericOwnerPhysicalFamilyCodec.decode(
            encoded.trimEnd('\r', '\n').lines().dropLast(1).joinToString("\n", postfix = "\n")
        )
    }
    expectRejected("a record for another producer") {
        DotNetGenericOwnerPhysicalFamilyCodec.decode(encoded, "0".repeat(64))
    }
    expectRejected("a record for another target profile") {
        DotNetGenericOwnerPhysicalFamilyCodec.decode(
            encoded,
            artifact.producerFingerprint,
            DotNetGenericOwnerPhysicalTargetProfile.entries.first { profile ->
                profile != artifact.targetProfile
            },
        )
    }
    expectRejected("a duplicate logical owner") {
        DotNetGenericOwnerPhysicalFamilyArtifact(
            artifact.producerFingerprint,
            artifact.targetProfile,
            artifact.classifications,
            artifact.owners + artifact.owners.first(),
        )
    }
    expectRejected("a duplicate candidate classification") {
        artifact.copy(classifications = artifact.classifications + artifact.classifications.first())
    }
    val classifiedOwner = artifact.owners.first()
    expectRejected("a physical family without its producer classification") {
        artifact.copy(classifications = artifact.classifications.filterNot { classification ->
            classification.logicalOwnerKey == classifiedOwner.logicalOwnerKey
        })
    }
    expectRejected("a physical family whose candidate member catalog disagrees") {
        artifact.copy(classifications = artifact.classifications.map { classification ->
            if (classification.logicalOwnerKey != classifiedOwner.logicalOwnerKey) {
                classification
            } else {
                classification.copy(logicalMemberKeys = classification.logicalMemberKeys.dropLast(1))
            }
        })
    }
    val member = artifact.owners.first().members.first { candidate ->
        DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK in candidate.roles
    }
    expectRejected("an incomplete member role family") {
        member.copy(slots = member.slots.filterNot { slot ->
            slot.role == DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK
        })
    }
    expectRejected("a member family whose roles disagree on the slot-domain vector") {
        member.copy(slots = member.slots.map { slot ->
            if (slot.role != DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY) slot else slot.copy(
                signature = slot.signature.copy(
                    parameterSlots = slot.signature.parameterSlots.map { parameter ->
                        if (parameter.domain != DotNetGenericOwnerPhysicalSlotDomain.BROAD_CANDIDATE_INPUT) {
                            parameter
                        } else {
                            parameter.copy(domain = DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_INPUT)
                        }
                    }
                )
            )
        })
    }
    expectRejected("an unsorted duplicate override-root family") {
        member.copy(overrideRootLogicalMemberKeys = listOf(member.logicalMemberKey, member.logicalMemberKey))
    }
    val constructionOwner = artifact.owners.first { owner -> owner.constructors.size > 1 }
    val secondaryConstructor = constructionOwner.constructors.single { constructor ->
        constructor.delegation.kind == DotNetGenericOwnerConstructorDelegationKind.THIS
    }
    expectRejected("an owner with a static-exact mode but no constructor") {
        constructionOwner.copy(constructors = emptyList())
    }
    expectRejected("a constructor MethodDef on another physical owner") {
        constructionOwner.copy(constructors = constructionOwner.constructors.map { constructor ->
            if (constructor != secondaryConstructor) constructor else constructor.copy(
                physicalConstructor = constructor.physicalConstructor.copy(
                    physicalOwnerPath = listOf("Wrong", "Owner"),
                )
            )
        })
    }
    expectRejected("two logical constructors mapped to one physical MethodDef") {
        constructionOwner.copy(constructors = constructionOwner.constructors.map { constructor ->
            if (constructor != secondaryConstructor) constructor else constructor.copy(
                physicalConstructor = constructionOwner.constructors.first { candidate ->
                    candidate != secondaryConstructor
                }.physicalConstructor,
            )
        })
    }
    expectRejected("a constructor whose constructed owner has the wrong argument vector") {
        constructionOwner.copy(constructors = constructionOwner.constructors.map { constructor ->
            if (constructor != secondaryConstructor) constructor else constructor.copy(
                constructedOwnerType = DotNetGenericOwnerPhysicalTypeExpressionRecord.producerType(
                    typePath = constructionOwner.physicalOwnerPath,
                    category = DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS,
                    arguments = listOf(DotNetGenericOwnerPhysicalTypeExpressionRecord.objectType()),
                )
            )
        })
    }
    expectRejected("a local delegated constructor with a different physical signature") {
        DotNetGenericOwnerPhysicalFamilyArtifact(
            artifact.producerFingerprint,
            artifact.targetProfile,
            artifact.classifications,
            artifact.owners.map { owner ->
                if (owner != constructionOwner) owner else owner.copy(
                    constructors = owner.constructors.map { constructor ->
                        if (constructor != secondaryConstructor) constructor else constructor.copy(
                            delegation = constructor.delegation.copy(
                                signature = constructor.delegation.signature.copy(parameterSlots = emptyList()),
                            )
                        )
                    }
                )
            },
        )
    }
    expectRejected("a this-constructor edge without a logical target") {
        secondaryConstructor.copy(
            delegation = secondaryConstructor.delegation.copy(logicalConstructorKey = null),
        )
    }
    expectRejected("a cyclic local this-constructor graph") {
        val primary = constructionOwner.constructors.single { constructor ->
            constructor != secondaryConstructor
        }
        val cyclicPrimary = primary.copy(
            delegation = primary.delegation.copy(
                kind = DotNetGenericOwnerConstructorDelegationKind.THIS,
                logicalConstructorKey = secondaryConstructor.logicalConstructorKey,
                physicalOwnerType = primary.constructedOwnerType,
                signature = secondaryConstructor.physicalConstructor.signature,
            ),
        )
        DotNetGenericOwnerPhysicalFamilyArtifact(
            artifact.producerFingerprint,
            artifact.targetProfile,
            artifact.classifications,
            artifact.owners.map { owner ->
                if (owner != constructionOwner) owner else owner.copy(
                    constructors = owner.constructors.map { constructor ->
                        if (constructor == primary) cyclicPrimary else constructor
                    },
                )
            },
        )
    }
    expectRejected("a reflection classifier for another physical TypeDef") {
        constructionOwner.copy(
            reflection = constructionOwner.reflection.copy(
                physicalOpenTypeDefinition = constructionOwner.reflection.physicalOpenTypeDefinition.copy(
                    physicalTypePath = listOf("Wrong", "Classifier"),
                ),
            ),
        )
    }
    expectRejected("a reflection classifier with a different logical owner") {
        constructionOwner.copy(
            reflection = constructionOwner.reflection.copy(logicalClassifierKey = "wrong-logical-owner"),
        )
    }
    val reflectedCallable = constructionOwner.reflection.callables.first { callable ->
        callable.invocationRole == DotNetGenericOwnerMemberFamilyRole.CAPABILITY_DISPATCHER
    }
    expectRejected("an omitted logical reflected callable") {
        constructionOwner.copy(
            reflection = constructionOwner.reflection.copy(
                callables = constructionOwner.reflection.callables - reflectedCallable,
            ),
        )
    }
    expectRejected("a reflected semantic family invoked through its typed entry") {
        val reflectedMember = constructionOwner.members.single { candidate ->
            candidate.logicalMemberKey == reflectedCallable.logicalMemberKey
        }
        val typedSlot = reflectedMember.slots.single { slot ->
            slot.role == DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY
        }
        constructionOwner.copy(
            reflection = constructionOwner.reflection.copy(
                callables = constructionOwner.reflection.callables.map { callable ->
                    if (callable != reflectedCallable) callable else callable.copy(
                        invocationRole = DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY,
                        invocationMethod = DotNetGenericOwnerPhysicalMethodIdentityRecord(
                            typedSlot.physicalOwnerPath,
                            typedSlot.physicalMethodName,
                            typedSlot.signature,
                        ),
                    )
                },
            ),
        )
    }
    expectRejected("a reflected callable with an incomplete physical MethodDef family") {
        constructionOwner.copy(
            reflection = constructionOwner.reflection.copy(
                callables = constructionOwner.reflection.callables.map { callable ->
                    if (callable != reflectedCallable) callable else callable.copy(
                        physicalMethods = callable.physicalMethods.filter { method ->
                            method == callable.invocationMethod
                        },
                    )
                },
            ),
        )
    }
    val exactReflection = artifact.reflectionClassifierForExactOpenTypeDefinitionOrNull(
        constructionOwner.reflection.physicalOpenTypeDefinition,
    )
    check(artifact.owners.mapNotNull { owner -> owner.physicalCapabilityOwnerPath }.distinct().size == 1 &&
            exactReflection == constructionOwner.reflection &&
            artifact.reflectionClassifierForExactOpenTypeDefinitionOrNull(
                constructionOwner.reflection.physicalOpenTypeDefinition.copy(
                    physicalTypePath = checkNotNull(constructionOwner.physicalCapabilityOwnerPath),
                )
            ) == null) {
        "Generic-owner reflection normalization confused the implementation and capability TypeDefs"
    }
    val reflectionAncestry = artifact.owners.map { owner -> owner.reflection.physicalOpenTypeDefinition }
    check(artifact.owners.all { owner ->
        artifact.reflectionClassifierMatchesAncestry(owner.logicalOwnerKey, reflectionAncestry)
    } && !artifact.reflectionClassifierMatchesAncestry("missing-logical-classifier", reflectionAncestry)) {
        "Generic-owner reflection ancestry did not use exact recorded open TypeDefs"
    }
    expectRejected("an out-of-range owner parameter in a delegated construction type") {
        constructionOwner.copy(constructors = constructionOwner.constructors.map { constructor ->
            if (constructor != secondaryConstructor) constructor else constructor.copy(
                delegation = constructor.delegation.copy(
                    physicalOwnerType = DotNetGenericOwnerPhysicalTypeExpressionRecord.producerType(
                        typePath = constructionOwner.physicalOwnerPath,
                        category = DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS,
                        arguments = listOf(
                            DotNetGenericOwnerPhysicalTypeExpressionRecord.ownerParameter(
                                constructionOwner.genericArity
                            )
                        ),
                    )
                )
            )
        })
    }
    expectRejected("a capability dispatcher direct-super target") {
        DotNetGenericOwnerPhysicalDirectSuperTargetRecord(
            role = DotNetGenericOwnerMemberFamilyRole.CAPABILITY_DISPATCHER,
            logicalTargetMemberKey = member.logicalMemberKey,
            physicalOwnerPath = listOf("KotlinSnapshotPrototype", "HostileUnsafeStore"),
            physicalMethodName = "WriteSemantic",
            signature = member.slots.first().signature,
        )
    }
    val dispatcher = member.slots.single { slot ->
        slot.role == DotNetGenericOwnerMemberFamilyRole.CAPABILITY_DISPATCHER
    }
    expectRejected("a dispatcher without an exact capability slot") {
        dispatcher.copy(capabilitySlot = null)
    }
    expectRejected("a capability slot with a different physical signature") {
        dispatcher.copy(
            capabilitySlot = checkNotNull(dispatcher.capabilitySlot).copy(
                signature = dispatcher.signature.copy(parameterSlots = emptyList()),
            )
        )
    }
    val directSuperMember = artifact.owners.flatMap { owner -> owner.members }
        .first { candidate -> candidate.directSuperTargets.isNotEmpty() }
    expectRejected("a direct-super target with a different physical signature") {
        directSuperMember.copy(
            directSuperTargets = directSuperMember.directSuperTargets.mapIndexed { index, target ->
                if (index != 0) target else target.copy(
                    signature = target.signature.copy(parameterSlots = emptyList()),
                )
            }
        )
    }
    expectRejected("two logical members with one colliding physical MethodDef") {
        val collisionOwner = artifact.owners.first { owner ->
            owner.members.count { candidate ->
                candidate.roles == setOf(DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY) &&
                        candidate.directSuperTargets.isEmpty()
            } >= 2
        }
        val candidates = collisionOwner.members.filter { candidate ->
            candidate.roles == setOf(DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY) &&
                    candidate.directSuperTargets.isEmpty()
        }.take(2)
        val first = candidates.first().slots.single()
        val second = candidates.last()
        collisionOwner.copy(members = collisionOwner.members.map { candidate ->
            if (candidate != second) candidate else candidate.copy(
                slots = listOf(second.slots.single().copy(
                    physicalOwnerPath = first.physicalOwnerPath,
                    physicalMethodName = first.physicalMethodName,
                    signature = first.signature,
                ))
            )
        })
    }
    expectRejected("an out-of-range owner type parameter") {
        val relayOwner = artifact.owners.first { owner -> owner.members.any { candidate ->
            candidate.slots.any { slot -> slot.signature.genericArity == 1 }
        } }
        val relay = relayOwner.members.single { candidate ->
            candidate.slots.any { slot -> slot.signature.genericArity == 1 }
        }
        relayOwner.copy(
            members = relayOwner.members.map { candidate ->
                if (candidate != relay) candidate else candidate.copy(
                    slots = candidate.slots.map { slot ->
                        if (slot.role != DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY) slot else slot.copy(
                            signature = slot.signature.copy(
                                parameterSlots = listOf(
                                    DotNetGenericOwnerPhysicalValueSlotRecord(
                                        DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_INPUT,
                                        DotNetGenericOwnerPhysicalTypeExpressionRecord.ownerParameter(
                                            relayOwner.genericArity
                                        ),
                                    )
                                )
                            )
                        )
                    }
                )
            }
        )
    }
    expectRejected("void as a named generic argument") {
        DotNetGenericOwnerPhysicalTypeExpressionRecord.coreType(
            typePath = listOf("System", "Collections", "Generic", "List"),
            category = DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS,
            arguments = listOf(DotNetGenericOwnerPhysicalTypeExpressionRecord.voidType()),
        )
    }
    val stateOwner = artifact.owners.first { owner -> owner.states.isNotEmpty() }
    val state = stateOwner.states.single()
    val typedWriteAccess = state.accessPaths.single { access ->
        access.domain == DotNetGenericOwnerPhysicalStateAccessDomain.TYPED &&
                access.operation == DotNetGenericOwnerPhysicalStateAccessOperation.WRITE
    }
    expectRejected("an unpaired semantic-object state access") {
        state.copy(accessPaths = state.accessPaths - typedWriteAccess)
    }
    expectRejected("an identity conversion between typed input and semantic storage") {
        state.copy(accessPaths = state.accessPaths.map { access ->
            if (access != typedWriteAccess) access else access.copy(
                conversion = DotNetGenericOwnerPhysicalStateAccessConversion.IDENTITY,
            )
        })
    }
    expectRejected("a state access bound to an unrecorded MethodDef") {
        stateOwner.copy(states = listOf(state.copy(accessPaths = state.accessPaths.map { access ->
            if (access != typedWriteAccess) access else access.copy(
                physicalMethod = access.physicalMethod.copy(physicalMethodName = "WrongWrite"),
            )
        })))
    }
    expectRejected("an out-of-range owner type parameter in state storage") {
        stateOwner.copy(
            states = stateOwner.states.mapIndexed { index, state ->
                if (index != 0) state else state.copy(
                    physicalType = DotNetGenericOwnerPhysicalTypeExpressionRecord.ownerParameter(stateOwner.genericArity),
                )
            }
        )
    }
    val echo = artifact.owners.first().members.single { candidate ->
        candidate.slots.any { slot ->
            slot.role == DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY &&
                    slot.signature.genericArity == 0 &&
                    slot.signature.returnSlot.type.kind == DotNetGenericOwnerPhysicalTypeKind.SZ_ARRAY &&
                    slot.signature.returnSlot.type.arguments.singleOrNull()?.kind ==
                    DotNetGenericOwnerPhysicalTypeKind.OWNER_TYPE_PARAMETER
        }
    }
    val typedEcho = echo.slots.single { slot -> slot.role == DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY }
    val semanticEcho = echo.slots.single { slot -> slot.role == DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK }
    check(typedEcho.signature.returnSlot.type.kind == DotNetGenericOwnerPhysicalTypeKind.SZ_ARRAY &&
            typedEcho.signature.returnSlot.type.arguments.single().kind ==
            DotNetGenericOwnerPhysicalTypeKind.OWNER_TYPE_PARAMETER &&
            semanticEcho.signature.returnSlot.type.scope == DotNetGenericOwnerPhysicalTypeScope.CORE_LIBRARY &&
            semanticEcho.signature.returnSlot.type.typePath == listOf("System", "Array")) {
        "The generic-owner artifact did not retain its nested typed/semantic array carriers"
    }
    val relay = artifact.owners.first().members.single { candidate ->
        candidate.slots.any { slot -> slot.signature.genericArity == 1 }
    }.slots.single()
    check(relay.signature.genericArity == 1 &&
            relay.signature.returnSlot.type.arguments.single().kind ==
            DotNetGenericOwnerPhysicalTypeKind.METHOD_TYPE_PARAMETER &&
            relay.signature.returnSlot.type.arguments.single().parameterIndex == 0) {
        "The generic-owner artifact did not retain its nested method-generic array carrier"
    }
    check(artifact.owners.flatMap { owner -> owner.members }.mapNotNull { candidate ->
        candidate.defaultDispatcher
    }.singleOrNull()?.let { dispatcherRecord ->
        dispatcherRecord.signature.parameterSlots.first().type.arguments.singleOrNull()?.kind ==
                DotNetGenericOwnerPhysicalTypeKind.OWNER_TYPE_PARAMETER
    } == true) {
        "The generic-owner artifact lacks the selected physical default dispatcher"
    }
}

/**
 * Test-owned physicalization of the compiler snapshot. This deliberately does not reuse the
 * production emitter: it turns the recorded role/state decisions into a temporary CLR-generic
 * producer, then proves that a separately compiled C# subclass observes those decisions.
 */
private fun copyGenericOwnerErasedArtifact(source: File, destination: File) {
    check(source.isFile) { "The generic-owner application corpus lacks erased output: ${source.path}" }
    check(!destination.exists()) {
        "The generic-owner application corpus attempted to overwrite erased output: ${destination.path}"
    }
    source.copyTo(destination)
}

private fun physicalizeGenericOwnerHardestModelPrototype(
    prototypes: List<DotNetGenericOwnerPrototypeSnapshot>,
    target: DotNetTarget,
    erasedOutput: File,
    applicationSource: File,
    directory: File,
) {
    fun DotNetGenericOwnerPrototypeSnapshot.hasSimpleName(name: String): Boolean =
        ownerName == name || ownerName.endsWith(".$name")

    directory.mkdirs()
    prototypes.singleOrNull { prototype -> prototype.hasSimpleName("ConsumerUnsafeLeaf") }?.let { consumer ->
        copyGenericOwnerErasedArtifact(
            erasedOutput,
            directory.resolve(genericOwnerErasedConsumerFile(target)),
        )
        consumeGenericOwnerPhysicalFamilyArtifact(consumer, target, directory)
        return
    }
    if (prototypes.none { prototype -> prototype.hasSimpleName("HostileUnsafeProducer") }) return
    copyGenericOwnerErasedArtifact(
        applicationSource,
        directory.resolve(GENERIC_OWNER_APPLICATION_SOURCE_FILE),
    )
    copyGenericOwnerErasedArtifact(
        erasedOutput,
        directory.resolve(GENERIC_OWNER_ERASED_PRODUCER_FILE),
    )
    val owner = prototypes.single { prototype -> prototype.hasSimpleName("HostileUnsafeStore") }
    val write = owner.members.single { member -> member.sourceName == "writeUnsafe" }
    val read = owner.members.single { member -> member.sourceName == "read" }
    val echo = owner.members.single { member -> member.sourceName == "echo" }
    val relay = owner.members.single { member -> member.sourceName == "relay" }
    val label = owner.members.single { member -> member.sourceName == "label" }
    val state = owner.states.single()
    fun hasRole(
        member: DotNetGenericOwnerPrototypeMemberSnapshot,
        role: DotNetGenericOwnerMemberFamilyRole,
    ): Boolean = role in member.roles
    fun physicalName(
        member: DotNetGenericOwnerPrototypeMemberSnapshot,
        role: DotNetGenericOwnerMemberFamilyRole,
    ): String = genericOwnerPrototypePhysicalMethodName(member, role)
    val writeTypedName = physicalName(write, DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY)
    val writeSemanticName = physicalName(write, DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK)
    val writeCapabilityName = physicalName(write, DotNetGenericOwnerMemberFamilyRole.CAPABILITY_DISPATCHER)
    val readTypedName = physicalName(read, DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY)
    val readSemanticName = physicalName(read, DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK)
    val readCapabilityName = physicalName(read, DotNetGenericOwnerMemberFamilyRole.CAPABILITY_DISPATCHER)
    val echoTypedName = physicalName(echo, DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY)
    val echoSemanticName = physicalName(echo, DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK)
    val echoCapabilityName = physicalName(echo, DotNetGenericOwnerMemberFamilyRole.CAPABILITY_DISPATCHER)
    val relayTypedName = physicalName(relay, DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY)
    val labelTypedName = physicalName(label, DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY)
    val labelDefaultName = "${labelTypedName}Default"

    val stateType = when (state.requirement) {
        DotNetGenericOwnerStateCarrierRequirement.SEMANTIC_OBJECT_REQUIRED -> "object"
        DotNetGenericOwnerStateCarrierRequirement.TYPED_STORAGE_PRODUCER_GRAPH_PROVEN -> "T"
        DotNetGenericOwnerStateCarrierRequirement.COMPLETE_ACCESS_GRAPH_REQUIRED ->
            error("The hostile physicalizer cannot select storage while the access graph is incomplete")
        DotNetGenericOwnerStateCarrierRequirement.TYPED_WRITE_VALUE_PROVENANCE_REQUIRED ->
            error("The hostile physicalizer cannot select storage while typed write provenance is incomplete")
    }
    val typedWrite = if (hasRole(write, DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY)) {
        """
        public virtual void $writeTypedName(T next)
        {
            stored = next;
        }
        """.trimIndent()
    } else ""
    val semanticWrite = if (hasRole(write, DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK)) {
        """
        protected virtual void $writeSemanticName(object next)
        {
            stored = next;
        }
        """.trimIndent()
    } else ""
    val writeDispatcher = if (hasRole(write, DotNetGenericOwnerMemberFamilyRole.CAPABILITY_DISPATCHER)) {
        """
        void IHostileUnsafeStoreSemantic.$writeCapabilityName(object next)
        {
            if (IsCompatible(next)) $writeTypedName((T)next);
            else $writeSemanticName(next);
        }
        """.trimIndent()
    } else ""
    val typedRead = if (hasRole(read, DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY)) {
        """
        public virtual T $readTypedName()
        {
            return (T)stored;
        }
        """.trimIndent()
    } else ""
    val semanticRead = if (hasRole(read, DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK)) {
        """
        protected virtual object $readSemanticName()
        {
            return stored;
        }
        """.trimIndent()
    } else ""
    val readDispatcher = if (hasRole(read, DotNetGenericOwnerMemberFamilyRole.CAPABILITY_DISPATCHER)) {
        """
        object IHostileUnsafeStoreSemantic.$readCapabilityName()
        {
            return $readSemanticName();
        }
        """.trimIndent()
    } else ""
    val typedEcho = if (hasRole(echo, DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY)) {
        """
        public virtual T[] $echoTypedName(T[] values)
        {
            return values;
        }
        """.trimIndent()
    } else ""
    val semanticEcho = if (hasRole(echo, DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK)) {
        """
        protected virtual Array $echoSemanticName(Array values)
        {
            return values;
        }
        """.trimIndent()
    } else ""
    val echoDispatcher = if (hasRole(echo, DotNetGenericOwnerMemberFamilyRole.CAPABILITY_DISPATCHER)) {
        """
        Array IHostileUnsafeStoreSemantic.$echoCapabilityName(Array values)
        {
            T[] compatible = values as T[];
            return compatible != null ? $echoTypedName(compatible) : $echoSemanticName(values);
        }
        """.trimIndent()
    } else ""
    val typedRelay = if (hasRole(relay, DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY)) {
        """
        public virtual R[] $relayTypedName<R>(R[] values)
        {
            return values;
        }
        """.trimIndent()
    } else ""

    val producerSource = directory.resolve("SnapshotProducer.cs").apply {
        writeText(
            """
            using System;

            namespace KotlinSnapshotPrototype
            {
                public interface IHostileUnsafeStoreSemantic
                {
                    object $readCapabilityName();
                    void $writeCapabilityName(object next);
                    Array $echoCapabilityName(Array values);
                }

                public class HostileUnsafeStore<T> : IHostileUnsafeStoreSemantic
                {
                    private $stateType stored;

                    public HostileUnsafeStore(T initial)
                    {
                        stored = initial;
                    }

                    public HostileUnsafeStore(T initial, int marker) : this(initial) {}

                    public virtual string $labelTypedName(string prefix)
                    {
                        return prefix;
                    }

                    public static string $labelDefaultName(HostileUnsafeStore<T> receiver, string prefix, int mask)
                    {
                        if ((mask & 1) != 0) prefix = "default";
                        return receiver.$labelTypedName(prefix);
                    }

                    $typedWrite

                    $semanticWrite

                    $typedRead

                    $semanticRead

                    private static bool IsCompatible(object candidate)
                    {
                        if (candidate != null) return candidate is T;
                        return object.ReferenceEquals(default(T), null);
                    }

                    $writeDispatcher

                    $readDispatcher

                    $typedEcho

                    $semanticEcho

                    $echoDispatcher

                    $typedRelay
                }

                public class HostileUnsafeMid<T> : HostileUnsafeStore<T>
                {
                    public HostileUnsafeMid(T initial) : base(initial) {}

                    public override void $writeTypedName(T next)
                    {
                        base.$writeTypedName(next);
                    }

                    protected override void $writeSemanticName(object next)
                    {
                        base.$writeSemanticName(next);
                    }

                    public override T $readTypedName()
                    {
                        return base.$readTypedName();
                    }

                    protected override object $readSemanticName()
                    {
                        return base.$readSemanticName();
                    }

                    public override T[] $echoTypedName(T[] values)
                    {
                        return base.$echoTypedName(values);
                    }

                    protected override Array $echoSemanticName(Array values)
                    {
                        return base.$echoSemanticName(values);
                    }
                }
            }
            """.trimIndent()
        )
    }
    val consumerSource = directory.resolve("SnapshotConsumer.cs").apply {
        writeText(
            """
            using System;
            using KotlinSnapshotPrototype;

            public sealed class TypedWriteConsumer : HostileUnsafeStore<int>
            {
                public TypedWriteConsumer() : base(1) {}

                public override void $writeTypedName(int next)
                {
                    base.$writeTypedName(next + 1);
                }
            }

            public sealed class SemanticWriteConsumer : HostileUnsafeStore<int>
            {
                public SemanticWriteConsumer() : base(1) {}

                protected override void $writeSemanticName(object next)
                {
                    base.$writeSemanticName("semantic:" + next);
                }
            }

            public sealed class PairedReadConsumer : HostileUnsafeStore<int>
            {
                public PairedReadConsumer() : base(1) {}

                public override int $readTypedName()
                {
                    return 43;
                }

                protected override object $readSemanticName()
                {
                    return 43;
                }
            }

            public static class SnapshotConsumer
            {
                public static int Main()
                {
                    HostileUnsafeStore<int> value = new HostileUnsafeStore<int>(1);
                    IHostileUnsafeStoreSemantic semantic = value;
                    semantic.$writeCapabilityName("wrong");
                    if (!object.Equals(semantic.$readCapabilityName(), "wrong")) return 1;
                    try
                    {
                        value.$readTypedName();
                        return 2;
                    }
                    catch (InvalidCastException)
                    {
                    }
                    semantic.$writeCapabilityName(2);
                    if (value.$readTypedName() != 2) return 3;
                    int[] typedArray = new int[] { 1, 2 };
                    if (!object.ReferenceEquals(value.$echoTypedName(typedArray), typedArray)) return 11;
                    string[] semanticArray = new string[] { "nested" };
                    if (!object.ReferenceEquals(semantic.$echoCapabilityName(semanticArray), semanticArray)) return 12;

                    TypedWriteConsumer typed = new TypedWriteConsumer();
                    ((IHostileUnsafeStoreSemantic)typed).$writeCapabilityName(4);
                    if (typed.$readTypedName() != 5) return 4;

                    SemanticWriteConsumer broad = new SemanticWriteConsumer();
                    IHostileUnsafeStoreSemantic broadSemantic = broad;
                    broadSemantic.$writeCapabilityName("wrong");
                    if (!object.Equals(broadSemantic.$readCapabilityName(), "semantic:wrong")) return 5;

                    PairedReadConsumer paired = new PairedReadConsumer();
                    if (paired.$readTypedName() != 43 ||
                        !object.Equals(((IHostileUnsafeStoreSemantic)paired).$readCapabilityName(), 43)) return 6;

                    Type definition = typeof(HostileUnsafeStore<>);
                    if (!definition.IsGenericTypeDefinition || definition.GetGenericArguments().Length != 1) return 7;
                    Type ownerParameter = definition.GetGenericArguments()[0];
                    if (definition.GetConstructor(new Type[] { ownerParameter }) == null ||
                        definition.GetConstructor(new Type[] { ownerParameter, typeof(int) }) == null) return 18;
                    Type midDefinition = typeof(HostileUnsafeMid<>);
                    Type midBase = midDefinition.BaseType;
                    if (midBase == null || !midBase.IsGenericType ||
                        midBase.GetGenericTypeDefinition() != definition ||
                        midBase.GetGenericArguments()[0] != midDefinition.GetGenericArguments()[0]) return 19;
                    System.Reflection.MethodInfo typedEchoMethod = definition.GetMethod("$echoTypedName");
                    if (typedEchoMethod == null ||
                        typedEchoMethod.ReturnType != ownerParameter.MakeArrayType() ||
                        typedEchoMethod.GetParameters().Length != 1 ||
                        typedEchoMethod.GetParameters()[0].ParameterType != ownerParameter.MakeArrayType()) return 13;
                    System.Reflection.MethodInfo capabilityEchoMethod =
                        typeof(IHostileUnsafeStoreSemantic).GetMethod("$echoCapabilityName");
                    if (capabilityEchoMethod == null ||
                        capabilityEchoMethod.ReturnType != typeof(Array) ||
                        capabilityEchoMethod.GetParameters().Length != 1 ||
                        capabilityEchoMethod.GetParameters()[0].ParameterType != typeof(Array)) return 14;
                    System.Reflection.MethodInfo relayMethod = definition.GetMethod("$relayTypedName");
                    if (relayMethod == null || !relayMethod.IsGenericMethodDefinition ||
                        relayMethod.GetGenericArguments().Length != 1) return 16;
                    Type methodParameter = relayMethod.GetGenericArguments()[0];
                    if (relayMethod.ReturnType != methodParameter.MakeArrayType() ||
                        relayMethod.GetParameters().Length != 1 ||
                        relayMethod.GetParameters()[0].ParameterType != methodParameter.MakeArrayType()) return 17;
                    System.Reflection.FieldInfo[] fields = definition.GetFields(
                        System.Reflection.BindingFlags.Instance |
                        System.Reflection.BindingFlags.NonPublic |
                        System.Reflection.BindingFlags.DeclaredOnly
                    );
                    if (fields.Length != 1 || fields[0].FieldType != typeof(object)) return 8;
                    System.Reflection.InterfaceMapping map =
                        typeof(HostileUnsafeStore<int>).GetInterfaceMap(typeof(IHostileUnsafeStoreSemantic));
                    if (map.TargetMethods.Length != 3) return 9;
                    for (int index = 0; index < map.TargetMethods.Length; index++)
                    {
                        System.Reflection.MethodInfo method = map.TargetMethods[index];
                        if (!method.IsPrivate || !method.IsVirtual || !method.IsFinal) return 10;
                    }
                    bool foundExactEchoDispatcher = false;
                    for (int index = 0; index < map.InterfaceMethods.Length; index++)
                    {
                        if (map.InterfaceMethods[index].Name == "$echoCapabilityName")
                        {
                            foundExactEchoDispatcher =
                                map.TargetMethods[index].Name ==
                                "KotlinSnapshotPrototype.IHostileUnsafeStoreSemantic.$echoCapabilityName";
                        }
                    }
                    if (!foundExactEchoDispatcher) return 15;
                    return 0;
                }
            }
            """.trimIndent()
        )
    }

    val producer = directory.resolve("SnapshotProducer.dll")
    val consumer = directory.resolve(if (target == DotNetTarget.NET48) "SnapshotConsumer.exe" else "SnapshotConsumer.dll")
    val compilation = when (target) {
        DotNetTarget.NET48 -> {
            val compiler = checkNotNull(DotNetIlAssembler.findFrameworkCSharpCompiler()) {
                ".NET Framework C# compiler is required for the generic-owner snapshot physicalizer"
            }
            compileFrameworkSnapshotCSharp(compiler, producerSource, producer, references = emptyList(), executable = false)
                .also { result -> check(result.exitCode == 0) { result.output } }
            compileFrameworkSnapshotCSharp(compiler, consumerSource, consumer, references = listOf(producer), executable = true)
        }
        DotNetTarget.NET10_0 -> {
            val toolchain = checkNotNull(DotNetIlAssembler.findModernCSharpCompiler()) {
                "Modern C# compiler is required for the generic-owner snapshot physicalizer"
            }
            compileModernSnapshotCSharp(toolchain, producerSource, producer, references = emptyList(), executable = false)
                .also { result -> check(result.exitCode == 0) { result.output } }
            compileModernSnapshotCSharp(toolchain, consumerSource, consumer, references = listOf(producer), executable = true)
        }
        DotNetTarget.NETSTANDARD_2_0 -> error("The hostile box oracle cannot target netstandard2.0")
    }
    check(compilation.exitCode == 0) { compilation.output }
    if (owner.logicalBindingKey != null) {
        val fingerprint = DotNetGenericOwnerPhysicalFamilyCodec.producerFingerprint(producer.readBytes())
        val artifact = createGenericOwnerPhysicalFamilyArtifact(prototypes, fingerprint, target)
        val sourceRelabeledPrototypes = prototypes.map { prototype ->
            if (!prototype.hasSimpleName("HostileUnsafeStore") &&
                    !prototype.hasSimpleName("HostileUnsafeMid")) {
                prototype
            } else {
                prototype.copy(members = prototype.members.mapIndexed { index, member ->
                    member.copy(sourceName = "diagnosticLabelMustNotOwnAbi$index")
                })
            }
        }
        check(createGenericOwnerPhysicalFamilyArtifact(sourceRelabeledPrototypes, fingerprint, target) == artifact) {
            "Changing diagnostic source labels changed compiler-derived physical family ABI"
        }
        val encoded = DotNetGenericOwnerPhysicalFamilyCodec.encode(artifact)
        validateGenericOwnerPhysicalFamilyCodec(artifact, encoded)
        directory.resolve(GENERIC_OWNER_PHYSICAL_FAMILY_FILE).writeText(encoded)
    }
    executeSnapshotConsumer(target, consumer, directory)
}

private fun consumeGenericOwnerPhysicalFamilyArtifact(
    consumer: DotNetGenericOwnerPrototypeSnapshot,
    target: DotNetTarget,
    directory: File,
) {
    val producer = directory.resolve("SnapshotProducer.dll")
    val recordFile = directory.resolve(GENERIC_OWNER_PHYSICAL_FAMILY_FILE)
    check(producer.isFile && recordFile.isFile) {
        "The separate generic-owner consumer lacks its producer or physical family artifact"
    }
    val fingerprint = DotNetGenericOwnerPhysicalFamilyCodec.producerFingerprint(producer.readBytes())
    val expectedProfile = when (target) {
        DotNetTarget.NET48 -> DotNetGenericOwnerPhysicalTargetProfile.NET48
        DotNetTarget.NET10_0 -> DotNetGenericOwnerPhysicalTargetProfile.NET10_0
        DotNetTarget.NETSTANDARD_2_0 -> error("netstandard2.0 has no executable family-record consumer")
    }
    val artifact = DotNetGenericOwnerPhysicalFamilyCodec.decode(
        recordFile.readText(),
        fingerprint,
        expectedProfile,
    )
    val unresolvedKeys = consumer.members.flatMap { member -> member.overrideBindings }
        .filter { binding ->
            binding.targetKind == DotNetGenericOwnerOverrideTargetKind.EXTERNAL_LOGICAL_BINDING_REQUIRED
        }
        .map { binding -> checkNotNull(binding.overriddenLogicalBindingKey) }
        .toSet()
    check(unresolvedKeys.isNotEmpty()) {
        "ConsumerUnsafeLeaf must enter family resolution with external logical obligations"
    }
    check(runCatching {
        artifact.copy(
            owners = artifact.owners.map { owner ->
                val retainedMembers = owner.members.filterNot { member ->
                    member.logicalMemberKey in unresolvedKeys
                }
                owner.copy(
                    members = retainedMembers,
                    reflection = owner.reflection.copy(
                        callables = genericOwnerPhysicalReflectionCallables(retainedMembers),
                    ),
                )
            }
        )
    }.isFailure) {
        "The producer catalog accepted a physical family with missing classified logical members"
    }
    val unavailableOwners = artifact.owners.filter { owner ->
        owner.members.any { member -> member.logicalMemberKey in unresolvedKeys }
    }
    check(unavailableOwners.isNotEmpty()) {
        "The separate consumer's logical obligations have no classified producer owner"
    }
    val classifiedButUnavailableArtifact = artifact.copy(
        owners = artifact.owners - unavailableOwners.toSet(),
    )
    val unavailableFailure = runCatching {
        consumer.resolveExternalPhysicalFamilies(classifiedButUnavailableArtifact)
    }.exceptionOrNull()
    check(unavailableFailure?.message?.let { message ->
        "has no physical family" in message && unavailableOwners.any { owner ->
            owner.logicalOwnerKey in message && owner.disposition.name in message
        }
    } == true) {
        "Generic-owner resolution did not distinguish classified absence from an unknown member: $unavailableFailure"
    }
    var replacedUnknownBinding = false
    val unknownMemberConsumer = consumer.copy(
        members = consumer.members.map { member ->
            member.copy(
                overrideBindings = member.overrideBindings.map { binding ->
                    if (replacedUnknownBinding ||
                            binding.targetKind !=
                            DotNetGenericOwnerOverrideTargetKind.EXTERNAL_LOGICAL_BINDING_REQUIRED) {
                        binding
                    } else {
                        replacedUnknownBinding = true
                        binding.copy(
                            overriddenLogicalBindingKey =
                                "${checkNotNull(binding.overriddenLogicalBindingKey)}#unknown",
                        )
                    }
                },
            )
        },
    )
    check(replacedUnknownBinding)
    val unknownFailure = runCatching {
        unknownMemberConsumer.resolveExternalPhysicalFamilies(artifact)
    }.exceptionOrNull()
    check(unknownFailure?.message?.let { message ->
        "lacks logical member" in message && "has no physical family" !in message
    } == true) {
        "Generic-owner resolution confused an unknown logical member with classified absence: $unknownFailure"
    }
    fun DotNetGenericOwnerPhysicalTypeExpressionRecord.eraseOwnerParameterForNegativeTest():
            DotNetGenericOwnerPhysicalTypeExpressionRecord =
        if (kind == DotNetGenericOwnerPhysicalTypeKind.OWNER_TYPE_PARAMETER) {
            DotNetGenericOwnerPhysicalTypeExpressionRecord.objectType()
        } else {
            copy(arguments = arguments.map { argument -> argument.eraseOwnerParameterForNegativeTest() })
        }
    fun DotNetGenericOwnerPhysicalMethodSignatureRecord.eraseOwnerParametersForNegativeTest():
            DotNetGenericOwnerPhysicalMethodSignatureRecord = copy(
        returnSlot = returnSlot.copy(type = returnSlot.type.eraseOwnerParameterForNegativeTest()),
        parameterSlots = parameterSlots.map { parameter ->
            parameter.copy(type = parameter.type.eraseOwnerParameterForNegativeTest())
        },
    )
    val mismatchedSignatureArtifact = artifact.copy(
        owners = artifact.owners.map { owner ->
            val changedMembers = owner.members.map { member ->
                if (member.logicalMemberKey !in unresolvedKeys) {
                    member
                } else {
                    val changedSlots = member.slots.map { slot ->
                        if (slot.role != DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY) slot else slot.copy(
                            signature = slot.signature.eraseOwnerParametersForNegativeTest(),
                        )
                    }
                    member.copy(
                        slots = changedSlots,
                        directSuperTargets = member.directSuperTargets.map { target ->
                            target.copy(signature = changedSlots.single { slot ->
                                slot.role == target.role
                            }.signature)
                        },
                    )
                }
            }
            owner.copy(
                members = changedMembers,
                reflection = owner.reflection.copy(
                    callables = genericOwnerPhysicalReflectionCallables(changedMembers),
                ),
            )
        }
    )
    check(runCatching { consumer.resolveExternalPhysicalFamilies(mismatchedSignatureArtifact) }.isFailure) {
        "Generic-owner family resolution inferred a typed signature despite producer/consumer slot-domain disagreement"
    }
    val resolved = consumer.resolveExternalPhysicalFamilies(artifact)
    check(resolved.disposition == DotNetGenericOwnerCandidateDisposition.REQUIRES_MEMBER_PHYSICALIZATION_PROOF) {
        "A completely bound external family must advance only to member physicalization proof: $resolved"
    }
    val write = resolved.members.single { member -> member.sourceName == "writeUnsafe" }
    val read = resolved.members.single { member -> member.sourceName == "read" }
    val echo = resolved.members.single { member -> member.sourceName == "echo" }
    val label = resolved.members.single { member -> member.sourceName == "label" }
    check(write.parameterSlotDomains ==
            listOf(DotNetGenericOwnerPhysicalSlotDomain.BROAD_CANDIDATE_INPUT)) {
        "The consumer did not inherit the producer's authoritative broad input domain: $write"
    }
    listOf(write, read, echo).forEach { member ->
        check(member.overrideBindings.map { binding -> binding.role }.toSet() == setOf(
            DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY,
            DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK,
        ) && member.overrideBindings.all { binding ->
            binding.targetKind == DotNetGenericOwnerOverrideTargetKind.EXTERNAL_PHYSICAL_FAMILY_RECORD &&
                    binding.overriddenLogicalBindingKey != null &&
                    binding.overriddenPhysicalMethodName != null &&
                    binding.overriddenPhysicalOwnerPath == artifact.owners.single { owner ->
                        owner.members.any { candidate ->
                            candidate.logicalMemberKey == binding.overriddenLogicalBindingKey
                        }
                    }.physicalOwnerPath &&
                    binding.overriddenPhysicalDispatch == DotNetGenericOwnerPhysicalMemberDispatch.OVERRIDABLE &&
                    binding.overriddenPhysicalSignature != null &&
                    binding.overriddenCapabilitySlot == null
        } && DotNetGenericOwnerSemanticHookReason.INHERITED_SEMANTIC_OVERRIDE in member.semanticHookReasons) {
            "${resolved.ownerName}.${member.sourceName} did not resolve a complete typed/semantic producer family: $member"
        }
    }
    check(label.overrideBindings.singleOrNull()?.let { binding ->
        binding.role == DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY &&
                binding.targetKind == DotNetGenericOwnerOverrideTargetKind.EXTERNAL_PHYSICAL_FAMILY_RECORD &&
                binding.overriddenPhysicalDispatch == DotNetGenericOwnerPhysicalMemberDispatch.OVERRIDABLE &&
                binding.overriddenPhysicalSignature != null
    } == true && label.semanticHookReasons.isEmpty()) {
        "${resolved.ownerName}.label did not retain its strict producer family: $label"
    }
    val mismatchedConstraintArtifact = artifact.copy(
        owners = artifact.owners.map { owner ->
            owner.copy(
                physicalGenericParameters = listOf(
                    DotNetGenericOwnerPhysicalGenericParameterRecord(
                        index = 0,
                        specialConstraints = setOf(
                            DotNetGenericOwnerPhysicalGenericParameterSpecialConstraint.REFERENCE_TYPE,
                        ),
                        typeConstraints = emptyList(),
                    ),
                ),
            )
        },
    )
    check(runCatching {
        consumer.physicalizeExternalSubclass(mismatchedConstraintArtifact, listOf("MismatchedConstraintConsumer"))
    }.isFailure) {
        "Kotlin subclass physicalization ignored producer GenericParam constraints"
    }
    val delegatedConstructorKey = checkNotNull(
        consumer.constructors.single { constructor -> !constructor.delegatesToThis }
            .delegatedConstructorLogicalBindingKey,
    )
    val mismatchedConstructorArtifact = artifact.copy(
        owners = artifact.owners.map { owner ->
            owner.copy(
                constructors = owner.constructors.map { constructor ->
                    if (constructor.logicalConstructorKey != delegatedConstructorKey) {
                        constructor
                    } else {
                        constructor.copy(
                            physicalConstructor = constructor.physicalConstructor.copy(
                                signature = constructor.physicalConstructor.signature
                                    .eraseOwnerParametersForNegativeTest(),
                            ),
                        )
                    }
                },
            )
        },
    )
    check(runCatching {
        consumer.physicalizeExternalSubclass(mismatchedConstructorArtifact, listOf("MismatchedConsumer"))
    }.isFailure) {
        "Kotlin subclass physicalization inferred a constructor from matching slot domains alone"
    }
    val transformedConstructorConsumer = consumer.copy(
        constructors = consumer.constructors.map { constructor ->
            if (constructor.delegatesToThis) constructor else constructor.copy(
                delegationArgumentMapping = DotNetGenericOwnerConstructorArgumentMapping.UNSUPPORTED,
            )
        },
    )
    check(runCatching {
        transformedConstructorConsumer.physicalizeExternalSubclass(
            artifact,
            listOf("TransformedConstructorConsumer"),
        )
    }.isFailure) {
        "Kotlin subclass physicalization regenerated a transformed base-constructor argument as identity"
    }
    check(runCatching {
        consumer.copy(
            physicalVisibility = DotNetGenericOwnerPhysicalTypeVisibility.NOT_PUBLIC,
        ).physicalizeExternalSubclass(artifact, listOf("NonPublicConsumer"))
    }.isFailure && runCatching {
        consumer.copy(
            physicalDispatch = DotNetGenericOwnerPhysicalTypeDispatch.FINAL,
        ).physicalizeExternalSubclass(artifact, listOf("FinalConsumer"))
    }.isFailure && runCatching {
        consumer.copy(
            directSupertypeCount = 2,
        ).physicalizeExternalSubclass(artifact, listOf("AdditionalInterfaceConsumer"))
    }.isFailure && runCatching {
        consumer.copy(
            isInner = true,
        ).physicalizeExternalSubclass(artifact, listOf("InnerConsumer"))
    }.isFailure && runCatching {
        consumer.copy(
            directFieldCount = 1,
        ).physicalizeExternalSubclass(artifact, listOf("AdditionalFieldConsumer"))
    }.isFailure && runCatching {
        consumer.copy(
            constructors = consumer.constructors.map { constructor ->
                if (constructor.delegatesToThis) constructor else constructor.copy(
                    hasOnlyDelegationAndInstanceInitializer = false,
                )
            },
        ).physicalizeExternalSubclass(artifact, listOf("EffectfulConstructorConsumer"))
    }.isFailure && runCatching {
        consumer.copy(
            constructors = consumer.constructors + consumer.constructors.single().copy(
                sourceIndex = consumer.constructors.maxOf { constructor -> constructor.sourceIndex } + 1,
                delegatesToThis = true,
            ),
        ).physicalizeExternalSubclass(artifact, listOf("SecondaryConstructorConsumer"))
    }.isFailure && runCatching {
        consumer.copy(
            members = consumer.members + consumer.members.first().copy(
                sourceName = "newLocalMember",
                sourceIndex = consumer.members.maxOf { member -> member.sourceIndex } + 1,
                overrideBindings = emptyList(),
            ),
        ).physicalizeExternalSubclass(artifact, listOf("AdditionalMemberConsumer"))
    }.isFailure) {
        "Kotlin subclass physicalization silently omitted an unsupported owner/member shape"
    }
    val physicalized = consumer.physicalizeExternalSubclass(
        artifact = artifact,
        physicalOwnerPath = listOf("RecordedFamilyConsumer"),
    )
    check(physicalized.visibility == DotNetGenericOwnerPhysicalTypeVisibility.PUBLIC &&
            physicalized.genericArity == 1 && physicalized.logicalOwnerKey == null &&
            physicalized.physicalGenericParameters == listOf(
                DotNetGenericOwnerPhysicalGenericParameterRecord(0, emptySet(), emptyList()),
            ) &&
            physicalized.dispatch == DotNetGenericOwnerPhysicalTypeDispatch.OVERRIDABLE &&
            consumer.directFieldCount == 0 && consumer.anonymousInitializerCount == 0 &&
            consumer.directNestedClassCount == 0 && !consumer.isInner &&
            physicalized.constructor.visibility == DotNetGenericOwnerPhysicalConstructorVisibility.PUBLIC &&
            consumer.constructors.single { constructor -> !constructor.delegatesToThis }
                .let { constructor ->
                    constructor.delegationArgumentMapping ==
                            DotNetGenericOwnerConstructorArgumentMapping.POSITIONAL_IDENTITY &&
                            constructor.hasOnlyDelegationAndInstanceInitializer
                } &&
            physicalized.constructor.constructedOwnerType.scope ==
            DotNetGenericOwnerPhysicalTypeScope.CURRENT_COMPILATION &&
            physicalized.members.map { member -> member.sourceName }.toSet() ==
            setOf("writeUnsafe", "read", "echo", "label") &&
            physicalized.members.flatMap { member -> member.slots }.all { slot ->
                slot.dispatch == DotNetGenericOwnerPhysicalMemberDispatch.OVERRIDABLE &&
                        (slot.role != DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK ||
                                slot.visibility == DotNetGenericOwnerPhysicalMemberVisibility.FAMILY)
            } && physicalized.members.all { member ->
                member.directSuperTargets.map { target -> target.role }.toSet() ==
                        member.slots.map { slot -> slot.role }.toSet()
            }) {
        "The compiler-derived external Kotlin subclass physicalization is incomplete: $physicalized"
    }
    val finalPhysicalized = consumer.copy(
        members = consumer.members.map { member -> member.copy(isOverridable = false) },
    ).physicalizeExternalSubclass(artifact, listOf("RecordedFinalFamilyConsumer"))
    check(finalPhysicalized.members.flatMap { member -> member.slots }.all { slot ->
        slot.dispatch == DotNetGenericOwnerPhysicalMemberDispatch.FINAL
    }) {
        "A legal final Kotlin override did not become a sealed CLR override: $finalPhysicalized"
    }
    check(runCatching {
        consumer.physicalizeExternalSubclass(artifact, artifact.owners.first().physicalOwnerPath)
    }.isFailure) {
        "Kotlin subclass physicalization accepted a producer-owned TypeDef path"
    }

    val ownerRecord = artifact.owners.single { owner ->
        owner.physicalOwnerPath == physicalized.constructor.constructedBaseOwner.typePath
    }
    val stateOwnerRecord = artifact.owners.single { owner -> owner.states.isNotEmpty() }
    val physicalizedLabel = physicalized.members.single { member -> member.sourceName == "label" }
        .slots.single()
    check(ownerRecord.physicalOwnerPath.last() == "HostileUnsafeMid" &&
            physicalizedLabel.overriddenPhysicalMethod.physicalOwnerPath == stateOwnerRecord.physicalOwnerPath &&
            stateOwnerRecord.members.any { member ->
                member.logicalMemberKey == physicalizedLabel.overriddenLogicalMemberKey
            }) {
        "A fake override confused the immediate constructed base with its declaring MethodDef: $physicalizedLabel"
    }
    val primaryConstructor = stateOwnerRecord.constructors.single { constructor ->
        constructor.physicalConstructor.signature.parameterSlots.size == 1
    }
    val secondaryConstructor = stateOwnerRecord.constructors.single { constructor ->
        constructor.physicalConstructor.signature.parameterSlots.size == 2
    }
    val immediateBaseConstructor = ownerRecord.constructors.single()
    val intRuntimeType = DotNetGenericOwnerPhysicalTypeExpressionRecord.int32Type()
    val nullableIntRuntimeType = DotNetGenericOwnerPhysicalTypeExpressionRecord.coreType(
        typePath = listOf("System", "Nullable"),
        category = DotNetGenericOwnerPhysicalNamedTypeCategory.VALUE_TYPE,
        arguments = listOf(intRuntimeType),
    )
    val knownStructRuntimeType = DotNetGenericOwnerPhysicalTypeExpressionRecord.currentCompilationType(
        typePath = listOf("RecordedKnownStruct"),
        category = DotNetGenericOwnerPhysicalNamedTypeCategory.VALUE_TYPE,
    )
    val unknownStructRuntimeType = DotNetGenericOwnerPhysicalTypeExpressionRecord.currentCompilationType(
        typePath = listOf("RecordedUnknownStruct"),
        category = DotNetGenericOwnerPhysicalNamedTypeCategory.VALUE_TYPE,
    )
    val unknownReferenceRuntimeType = DotNetGenericOwnerPhysicalTypeExpressionRecord.currentCompilationType(
        typePath = listOf("RecordedUnknownReference"),
        category = DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS,
    )
    val nullableKnownStructRuntimeType = DotNetGenericOwnerPhysicalTypeExpressionRecord.coreType(
        typePath = listOf("System", "Nullable"),
        category = DotNetGenericOwnerPhysicalNamedTypeCategory.VALUE_TYPE,
        arguments = listOf(knownStructRuntimeType),
    )
    val constructionPlan = artifact.planFiniteOpenNullableConstruction(
        logicalConstructionKey = "${consumer.ownerName}#openNullableConstruction",
        logicalOwnerKey = stateOwnerRecord.logicalOwnerKey,
        logicalConstructorKey = primaryConstructor.logicalConstructorKey,
        exactRuntimeArgumentTypes = listOf(
            intRuntimeType,
            nullableIntRuntimeType,
            DotNetGenericOwnerPhysicalTypeExpressionRecord.stringType(),
            knownStructRuntimeType,
        ),
    )
    val constructionFallback = constructionPlan.routes.single { route ->
        route.kind == DotNetGenericOwnerConstructionRouteKind.SEMANTIC_FALLBACK
    }
    check(constructionPlan.producerFingerprint == artifact.producerFingerprint &&
            constructionPlan.targetProfile == artifact.targetProfile &&
            constructionPlan.physicalCapabilityOwnerPath == stateOwnerRecord.physicalCapabilityOwnerPath &&
            constructionPlan.routes.count { route ->
                route.kind == DotNetGenericOwnerConstructionRouteKind.RUNTIME_EXACT
            } == 4 &&
            constructionPlan.selectRoute(intRuntimeType).constructedOwnerType.arguments.single() ==
            nullableIntRuntimeType &&
            constructionPlan.selectRoute(nullableIntRuntimeType).constructedOwnerType.arguments.single() ==
            nullableIntRuntimeType &&
            constructionPlan.selectRoute(DotNetGenericOwnerPhysicalTypeExpressionRecord.stringType())
                .constructedOwnerType.arguments.single() ==
            DotNetGenericOwnerPhysicalTypeExpressionRecord.stringType() &&
            constructionPlan.selectRoute(knownStructRuntimeType).constructedOwnerType.arguments.single() ==
            nullableKnownStructRuntimeType &&
            constructionPlan.selectRoute(unknownStructRuntimeType) == constructionFallback &&
            constructionPlan.selectRoute(unknownReferenceRuntimeType) == constructionFallback) {
        "The finite open-nullable construction plan lost exact or fallback routes: $constructionPlan"
    }
    check(runCatching {
        artifact.planFiniteOpenNullableConstruction(
            logicalConstructionKey = "duplicate",
            logicalOwnerKey = stateOwnerRecord.logicalOwnerKey,
            logicalConstructorKey = primaryConstructor.logicalConstructorKey,
            exactRuntimeArgumentTypes = listOf(intRuntimeType, intRuntimeType),
        )
    }.isFailure && runCatching {
        artifact.planFiniteOpenNullableConstruction(
            logicalConstructionKey = "open",
            logicalOwnerKey = stateOwnerRecord.logicalOwnerKey,
            logicalConstructorKey = primaryConstructor.logicalConstructorKey,
            exactRuntimeArgumentTypes = listOf(
                DotNetGenericOwnerPhysicalTypeExpressionRecord.methodParameter(0),
            ),
        )
    }.isFailure && runCatching {
        val invalidNullableReference = DotNetGenericOwnerPhysicalTypeExpressionRecord.coreType(
            typePath = listOf("System", "Nullable"),
            category = DotNetGenericOwnerPhysicalNamedTypeCategory.VALUE_TYPE,
            arguments = listOf(DotNetGenericOwnerPhysicalTypeExpressionRecord.stringType()),
        )
        artifact.planFiniteOpenNullableConstruction(
            logicalConstructionKey = "invalid-nullable",
            logicalOwnerKey = stateOwnerRecord.logicalOwnerKey,
            logicalConstructorKey = primaryConstructor.logicalConstructorKey,
            exactRuntimeArgumentTypes = listOf(invalidNullableReference),
        )
    }.isFailure && runCatching {
        val constrainedArtifact = artifact.copy(
            owners = artifact.owners.map { candidate ->
                candidate.copy(
                    physicalGenericParameters = listOf(
                        DotNetGenericOwnerPhysicalGenericParameterRecord(
                            index = 0,
                            specialConstraints = setOf(
                                DotNetGenericOwnerPhysicalGenericParameterSpecialConstraint.REFERENCE_TYPE,
                            ),
                            typeConstraints = emptyList(),
                        ),
                    ),
                )
            },
        )
        constrainedArtifact.planFiniteOpenNullableConstruction(
            logicalConstructionKey = "constrained",
            logicalOwnerKey = stateOwnerRecord.logicalOwnerKey,
            logicalConstructorKey = primaryConstructor.logicalConstructorKey,
            exactRuntimeArgumentTypes = listOf(DotNetGenericOwnerPhysicalTypeExpressionRecord.stringType()),
        )
    }.isFailure && runCatching {
        constructionPlan.copy(
            routes = constructionPlan.routes.filterNot { route ->
                route.kind == DotNetGenericOwnerConstructionRouteKind.SEMANTIC_FALLBACK
            },
        )
    }.isFailure) {
        "The finite construction proof admitted duplicate/open/invalid/constrained roots or no fallback"
    }
    check(stateOwnerRecord.runtimeClassificationMode ==
            DotNetGenericOwnerRuntimeClassificationMode.OPEN_TYPEDEF_ANCESTRY &&
            stateOwnerRecord.constructionModes == setOf(DotNetGenericOwnerConstructionMode.STATIC_EXACT) &&
            primaryConstructor.visibility == DotNetGenericOwnerPhysicalConstructorVisibility.PUBLIC &&
            primaryConstructor.delegation.kind == DotNetGenericOwnerConstructorDelegationKind.BASE &&
            primaryConstructor.delegation.physicalOwnerType.scope ==
            DotNetGenericOwnerPhysicalTypeScope.CORE_LIBRARY &&
            primaryConstructor.delegation.physicalOwnerType.typePath == listOf("System", "Object") &&
            secondaryConstructor.delegation.kind == DotNetGenericOwnerConstructorDelegationKind.THIS &&
            secondaryConstructor.delegation.logicalConstructorKey == primaryConstructor.logicalConstructorKey &&
            immediateBaseConstructor.delegation.kind == DotNetGenericOwnerConstructorDelegationKind.BASE &&
            immediateBaseConstructor.delegation.logicalConstructorKey == primaryConstructor.logicalConstructorKey &&
            immediateBaseConstructor.delegation.physicalOwnerType.typePath == stateOwnerRecord.physicalOwnerPath) {
        "The external producer family lacks exact profile/constructor/base-constructor identities"
    }
    val stateRecord = stateOwnerRecord.states.single()
    check(stateRecord.physicalVisibility == DotNetGenericOwnerPhysicalStateVisibility.PRIVATE &&
            stateRecord.accessPaths.map { access -> access.domain to access.operation }.toSet() ==
            DotNetGenericOwnerPhysicalStateAccessDomain.entries.flatMap { domain ->
                DotNetGenericOwnerPhysicalStateAccessOperation.entries.map { operation -> domain to operation }
            }.toSet() && stateRecord.accessPaths.count { access ->
                access.conversion == DotNetGenericOwnerPhysicalStateAccessConversion.IDENTITY
            } == 2 && stateRecord.accessPaths.any { access ->
                access.conversion ==
                        DotNetGenericOwnerPhysicalStateAccessConversion.INPUT_TO_STATE_BOX_OR_REFERENCE_WIDEN
            } && stateRecord.accessPaths.any { access ->
                access.conversion ==
                        DotNetGenericOwnerPhysicalStateAccessConversion.STATE_TO_OUTPUT_CHECKED_CAST_OR_UNBOX
            }) {
        "The external producer family lacks complete typed/semantic state access paths: $stateRecord"
    }
    val stateReflection = stateOwnerRecord.reflection
    val immediateReflection = ownerRecord.reflection
    check(stateReflection.logicalClassifierKey == stateOwnerRecord.logicalOwnerKey &&
            stateReflection.logicalClassifierKey.contains("generic.owner.oracle") &&
            !stateReflection.logicalClassifierKey.contains(
                stateReflection.physicalOpenTypeDefinition.physicalTypePath.joinToString(".")
            ) && immediateReflection.logicalClassifierKey.contains("generic.owner.oracle") &&
            stateReflection.typeArgumentAuthority ==
            DotNetGenericOwnerReflectionTypeArgumentAuthority.KLIB_LOGICAL_GRAPH &&
            stateReflection.capabilityExposure ==
            DotNetGenericOwnerReflectionCapabilityExposure.HIDDEN_COMPILER_ABI) {
        "The external producer reflection record lost logical Kotlin classifier authority"
    }
    check(artifact.reflectionClassifierForExactOpenTypeDefinitionOrNull(
        stateReflection.physicalOpenTypeDefinition,
    ) == stateReflection && artifact.reflectionClassifierForExactOpenTypeDefinitionOrNull(
        immediateReflection.physicalOpenTypeDefinition,
    ) == immediateReflection && artifact.reflectionClassifierForExactOpenTypeDefinitionOrNull(
        stateReflection.physicalOpenTypeDefinition.copy(
            physicalTypePath = checkNotNull(stateOwnerRecord.physicalCapabilityOwnerPath),
        ),
    ) == null) {
        "The external producer reflection record normalized a capability or missed an exact owner"
    }
    val physicalAncestry = listOf(
        immediateReflection.physicalOpenTypeDefinition,
        stateReflection.physicalOpenTypeDefinition,
    )
    check(artifact.reflectionClassifierMatchesAncestry(
        immediateReflection.logicalClassifierKey,
        physicalAncestry,
    ) && artifact.reflectionClassifierMatchesAncestry(
        stateReflection.logicalClassifierKey,
        physicalAncestry,
    )) {
        "The external producer reflection record lost an exact generic-owner ancestry edge"
    }
    ownerRecord.members.filter { member -> member.logicalMemberKey in unresolvedKeys }.forEach { member ->
        check(member.overrideRootLogicalMemberKeys.size == 1 &&
                member.directSuperTargets.map { target -> target.role }.toSet() == setOf(
                    DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY,
                    DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK,
                ) && member.directSuperTargets.all { target ->
                    target.logicalTargetMemberKey in member.overrideRootLogicalMemberKeys &&
                            target.physicalOwnerPath.last() == "HostileUnsafeStore"
                }) {
            "The external producer family lacks exact root/direct-super identities: $member"
        }
    }
    val capabilitySlots = artifact.owners.flatMap { owner -> owner.members }
        .flatMap { member -> member.slots }
        .mapNotNull { slot -> slot.capabilitySlot }
    val capabilityPath = capabilitySlots.map { slot -> slot.physicalOwnerPath }.distinct().single()
    check(capabilityPath == ownerRecord.physicalCapabilityOwnerPath) {
        "The family capability owner differs from its exact recorded interface slots"
    }
    val capabilityTypeName = capabilityPath.joinToString(".")
    val defaultEntry = artifact.owners.flatMap { owner ->
        owner.members.mapNotNull { member -> member.defaultDispatcher?.let { owner to member } }
    }.single()
    val defaultOwner = defaultEntry.first
    val defaultMember = defaultEntry.second
    val defaultDispatcher = checkNotNull(defaultMember.defaultDispatcher)
    check(defaultOwner.logicalOwnerKey != ownerRecord.logicalOwnerKey ||
            defaultOwner.physicalOwnerPath != ownerRecord.physicalOwnerPath) {
        "The default dispatcher must retain its own producer owner rather than the overridden base owner"
    }
    fun capabilityMethodName(
        member: DotNetGenericOwnerPrototypeMemberSnapshot,
    ): String {
        val logicalKey = member.overrideBindings.first().overriddenLogicalBindingKey
        val producerMember = ownerRecord.members.single { candidate -> candidate.logicalMemberKey == logicalKey }
        return producerMember.slots.single { slot ->
            slot.role == DotNetGenericOwnerMemberFamilyRole.CAPABILITY_DISPATCHER
        }.capabilitySlot?.physicalMethodName
            ?: error("The recorded dispatcher lacks an exact capability MethodDef")
    }

    val writeCapability = capabilityMethodName(write)
    val readCapability = capabilityMethodName(read)
    fun reflectedCallable(
        member: DotNetGenericOwnerPrototypeMemberSnapshot,
    ): DotNetGenericOwnerPhysicalCallableReflectionRecord {
        val logicalKey = checkNotNull(member.overrideBindings.first().overriddenLogicalBindingKey)
        return artifact.owners.flatMap { owner -> owner.reflection.callables }
            .single { callable -> callable.logicalMemberKey == logicalKey }
    }
    val writeReflection = reflectedCallable(write)
    val readReflection = reflectedCallable(read)
    check(writeReflection.invocationRole == DotNetGenericOwnerMemberFamilyRole.CAPABILITY_DISPATCHER &&
            readReflection.invocationRole == DotNetGenericOwnerMemberFamilyRole.CAPABILITY_DISPATCHER &&
            writeReflection.invocationMethod.physicalMethodName.endsWith(".$writeCapability") &&
            readReflection.invocationMethod.physicalMethodName.endsWith(".$readCapability") &&
            stateReflection.callables.single { callable -> callable.physicalMethods.any { method ->
                method.signature.genericArity == 1
            } }.invocationRole == DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY &&
            stateReflection.callables.single { callable -> callable.physicalMethods.any { method ->
                method.physicalMethodName == defaultDispatcher.physicalMethodName
            } }.physicalMethods.any { method -> method == defaultDispatcher.let { dispatcher ->
                DotNetGenericOwnerPhysicalMethodIdentityRecord(
                    dispatcher.physicalOwnerPath,
                    dispatcher.physicalMethodName,
                    dispatcher.signature,
                )
            } }) {
        "The external producer reflection record did not collapse each physical family into one callable"
    }
    val ownerArguments = listOf("int")
    val declarationOwnerArguments = listOf("T")
    fun physicalizedMember(sourceName: String) = physicalized.members.single { member ->
        member.sourceName == sourceName
    }
    fun physicalizedSlot(
        sourceName: String,
        role: DotNetGenericOwnerMemberFamilyRole,
    ) = physicalizedMember(sourceName).slots.single { slot -> slot.role == role }
    val physicalizedWriteTyped = physicalizedSlot("writeUnsafe", DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY)
    val physicalizedWriteSemantic = physicalizedSlot("writeUnsafe", DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK)
    val physicalizedReadTyped = physicalizedSlot("read", DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY)
    val physicalizedReadSemantic = physicalizedSlot("read", DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK)
    val physicalizedEchoTyped = physicalizedSlot("echo", DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY)
    val physicalizedEchoSemantic = physicalizedSlot("echo", DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK)
    val physicalizedLabelTyped = physicalizedSlot("label", DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY)
    fun physicalizedSuperMethodName(
        sourceName: String,
        role: DotNetGenericOwnerMemberFamilyRole,
    ): String = physicalizedMember(sourceName).directSuperTargets.single { target ->
        target.role == role
    }.physicalMethodName
    val baseTypeName = physicalized.constructor.constructedBaseOwner
        .renderSnapshotCSharpType(declarationOwnerArguments)
    val defaultOwnerTypeName = defaultDispatcher.signature.parameterSlots.first().type
        .renderSnapshotCSharpType(ownerArguments)
    val constructorParameterType = physicalized.constructor.physicalConstructor.signature.parameterSlots.single().type
        .renderSnapshotCSharpType(declarationOwnerArguments)
    val writeTypedParameterType = physicalizedWriteTyped.physicalMethod.signature.parameterSlots.single().type
        .renderSnapshotCSharpType(declarationOwnerArguments)
    val writeSemanticParameterType = physicalizedWriteSemantic.physicalMethod.signature.parameterSlots.single().type
        .renderSnapshotCSharpType(declarationOwnerArguments)
    val readTypedReturnType = physicalizedReadTyped.physicalMethod.signature.returnSlot.type
        .renderSnapshotCSharpType(declarationOwnerArguments)
    val readSemanticReturnType = physicalizedReadSemantic.physicalMethod.signature.returnSlot.type
        .renderSnapshotCSharpType(declarationOwnerArguments)
    val echoTypedReturnType = physicalizedEchoTyped.physicalMethod.signature.returnSlot.type
        .renderSnapshotCSharpType(declarationOwnerArguments)
    val echoTypedParameterType = physicalizedEchoTyped.physicalMethod.signature.parameterSlots.single().type
        .renderSnapshotCSharpType(declarationOwnerArguments)
    val echoSemanticReturnType = physicalizedEchoSemantic.physicalMethod.signature.returnSlot.type
        .renderSnapshotCSharpType(declarationOwnerArguments)
    val echoSemanticParameterType = physicalizedEchoSemantic.physicalMethod.signature.parameterSlots.single().type
        .renderSnapshotCSharpType(declarationOwnerArguments)
    val echoCapability = capabilityMethodName(echo)
    val physicalizedTypeName = physicalized.physicalOwnerPath.joinToString(".")
    val physicalizedOpenTypeName = "$physicalizedTypeName<>"
    val physicalizedClosedTypeName = "$physicalizedTypeName<int>"
    fun DotNetGenericOwnerPhysicalMemberVisibility.renderSnapshotCSharpVisibility(): String = when (this) {
        DotNetGenericOwnerPhysicalMemberVisibility.PUBLIC -> "public"
        DotNetGenericOwnerPhysicalMemberVisibility.FAMILY -> "protected"
        DotNetGenericOwnerPhysicalMemberVisibility.ASSEMBLY -> "internal"
        DotNetGenericOwnerPhysicalMemberVisibility.FAMILY_OR_ASSEMBLY -> "protected internal"
        DotNetGenericOwnerPhysicalMemberVisibility.PRIVATE -> "private"
    }
    fun DotNetGenericOwnerPhysicalConstructorVisibility.renderSnapshotCSharpVisibility(): String = when (this) {
        DotNetGenericOwnerPhysicalConstructorVisibility.PUBLIC -> "public"
        DotNetGenericOwnerPhysicalConstructorVisibility.FAMILY -> "protected"
        DotNetGenericOwnerPhysicalConstructorVisibility.ASSEMBLY -> "internal"
        DotNetGenericOwnerPhysicalConstructorVisibility.FAMILY_OR_ASSEMBLY -> "protected internal"
        DotNetGenericOwnerPhysicalConstructorVisibility.PRIVATE -> "private"
    }
    fun DotNetGenericOwnerPhysicalizedOverrideSlotRecord.renderSnapshotCSharpOverridePrefix(): String =
        "${visibility.renderSnapshotCSharpVisibility()} " + when (dispatch) {
            DotNetGenericOwnerPhysicalMemberDispatch.FINAL -> "sealed override"
            DotNetGenericOwnerPhysicalMemberDispatch.OVERRIDABLE -> "override"
            DotNetGenericOwnerPhysicalMemberDispatch.ABSTRACT -> "abstract override"
        }
    fun DotNetGenericOwnerPhysicalTypeDispatch.renderSnapshotCSharpTypeModifier(): String = when (this) {
        DotNetGenericOwnerPhysicalTypeDispatch.FINAL -> "sealed "
        DotNetGenericOwnerPhysicalTypeDispatch.OVERRIDABLE -> ""
        DotNetGenericOwnerPhysicalTypeDispatch.ABSTRACT -> "abstract "
        DotNetGenericOwnerPhysicalTypeDispatch.SEALED ->
            error("The hostile physicalizer cannot expose Kotlin sealed subclass semantics")
    }
    fun String.asSnapshotCSharpStringLiteral(): String = "\"" +
            replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "\\r").replace("\n", "\\n") + "\""
    fun DotNetGenericOwnerPhysicalOpenTypeDefinitionRecord.renderSnapshotCSharpUnboundType(): String =
        physicalTypePath.joinToString(".") + "<" + ",".repeat(genericArity - 1) + ">"
    val reflectionRecords = artifact.owners.map { owner -> owner.reflection }
    val exactNormalizationBranches = reflectionRecords.joinToString("\n") { reflection ->
        "if (definition == typeof(${reflection.physicalOpenTypeDefinition.renderSnapshotCSharpUnboundType()})) " +
                "return ${reflection.logicalClassifierKey.asSnapshotCSharpStringLiteral()};"
    }
    val logicalTargetBranches = reflectionRecords.joinToString("\n") { reflection ->
        "if (logicalKey == ${reflection.logicalClassifierKey.asSnapshotCSharpStringLiteral()}) " +
                "return typeof(${reflection.physicalOpenTypeDefinition.renderSnapshotCSharpUnboundType()});"
    }
    val exactConstructionBranches = constructionPlan.routes.filter { route ->
        route.kind == DotNetGenericOwnerConstructionRouteKind.RUNTIME_EXACT
    }.joinToString("\n") { route ->
        val runtimeType = checkNotNull(route.runtimeArgumentType).renderSnapshotCSharpType(emptyList())
        val ownerType = route.constructedOwnerType.renderSnapshotCSharpType(emptyList())
        val argumentType = route.constructedOwnerType.arguments.single().renderSnapshotCSharpType(emptyList())
        "if (runtimeArgument == typeof($runtimeType)) return new $ownerType(($argumentType)initial);"
    }
    val fallbackOwnerType = constructionFallback.constructedOwnerType.renderSnapshotCSharpType(emptyList())
    val fallbackArgumentType = constructionFallback.constructedOwnerType.arguments.single()
        .renderSnapshotCSharpType(emptyList())
    val constructionFactory = """
        public static class RecordedOpenNullableFactory
        {
            public static $capabilityTypeName Create(Type runtimeArgument, object initial)
            {
                if (runtimeArgument == null) throw new ArgumentNullException("runtimeArgument");
                $exactConstructionBranches
                return new $fallbackOwnerType(($fallbackArgumentType)initial);
            }
        }
    """.trimIndent()
    val stateLogicalClassifierKey = stateReflection.logicalClassifierKey.asSnapshotCSharpStringLiteral()
    val immediateLogicalClassifierKey = immediateReflection.logicalClassifierKey.asSnapshotCSharpStringLiteral()
    val stateOpenTypeName = stateReflection.physicalOpenTypeDefinition.renderSnapshotCSharpUnboundType()
    val immediateOpenTypeName = immediateReflection.physicalOpenTypeDefinition.renderSnapshotCSharpUnboundType()
    val stateClosedTypeName = primaryConstructor.constructedOwnerType.renderSnapshotCSharpType(ownerArguments)
    val stateAlternativeClosedTypeName = primaryConstructor.constructedOwnerType
        .renderSnapshotCSharpType(listOf("string"))
    val immediateClosedTypeName = immediateBaseConstructor.constructedOwnerType.renderSnapshotCSharpType(ownerArguments)
    val writeInvocationMethodName = writeReflection.invocationMethod.physicalMethodName
        .asSnapshotCSharpStringLiteral()
    val source = directory.resolve("RecordedFamilyConsumer.cs").apply {
        writeText(
            """
            using System;

            public struct RecordedKnownStruct
            {
                public int Value;
            }

            public struct RecordedUnknownStruct
            {
                public int Value;
            }

            public sealed class RecordedUnknownReference
            {
                public string Value;
            }

            $constructionFactory

            #if !GENERIC_OWNER_MEASUREMENT
            public static class RecordedReflectionRegistry
            {
                public static string NormalizeExact(Type runtimeType)
                {
                    if (runtimeType == null || !runtimeType.IsGenericType) return null;
                    Type definition = runtimeType.IsGenericTypeDefinition
                        ? runtimeType
                        : runtimeType.GetGenericTypeDefinition();
                    $exactNormalizationBranches
                    return null;
                }

                private static Type OpenDefinition(string logicalKey)
                {
                    $logicalTargetBranches
                    return null;
                }

                private static bool Matches(Type candidate, Type target)
                {
                    return candidate != null && candidate.IsGenericType &&
                        candidate.GetGenericTypeDefinition() == target;
                }

                public static bool IsLogicalInstance(object value, string logicalKey)
                {
                    if (value == null) return false;
                    Type target = OpenDefinition(logicalKey);
                    if (target == null) return false;
                    Type current = value.GetType();
                    while (current != null)
                    {
                        if (Matches(current, target)) return true;
                        Type[] interfaces = current.GetInterfaces();
                        for (int index = 0; index < interfaces.Length; index++)
                        {
                            if (Matches(interfaces[index], target)) return true;
                        }
                        current = current.BaseType;
                    }
                    return false;
                }
            }
            #endif

            ${if (physicalized.visibility == DotNetGenericOwnerPhysicalTypeVisibility.PUBLIC) "public" else "internal"}
            ${physicalized.dispatch.renderSnapshotCSharpTypeModifier()}class $physicalizedTypeName<T> : $baseTypeName
            {
                ${physicalized.constructor.visibility.renderSnapshotCSharpVisibility()}
                $physicalizedTypeName($constructorParameterType initial) : base(initial) {}

                ${physicalizedWriteTyped.renderSnapshotCSharpOverridePrefix()}
                void ${physicalizedWriteTyped.physicalMethod.physicalMethodName}($writeTypedParameterType next)
                {
                    base.${physicalizedSuperMethodName("writeUnsafe", DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY)}(next);
                }

                ${physicalizedWriteSemantic.renderSnapshotCSharpOverridePrefix()}
                void ${physicalizedWriteSemantic.physicalMethod.physicalMethodName}($writeSemanticParameterType next)
                {
                    base.${physicalizedSuperMethodName("writeUnsafe", DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK)}(next);
                }

                ${physicalizedReadTyped.renderSnapshotCSharpOverridePrefix()}
                $readTypedReturnType ${physicalizedReadTyped.physicalMethod.physicalMethodName}()
                {
                    return base.${physicalizedSuperMethodName("read", DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY)}();
                }

                ${physicalizedReadSemantic.renderSnapshotCSharpOverridePrefix()}
                $readSemanticReturnType ${physicalizedReadSemantic.physicalMethod.physicalMethodName}()
                {
                    return base.${physicalizedSuperMethodName("read", DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK)}();
                }

                ${physicalizedEchoTyped.renderSnapshotCSharpOverridePrefix()}
                $echoTypedReturnType ${physicalizedEchoTyped.physicalMethod.physicalMethodName}($echoTypedParameterType values)
                {
                    return base.${physicalizedSuperMethodName("echo", DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY)}(values);
                }

                ${physicalizedEchoSemantic.renderSnapshotCSharpOverridePrefix()}
                $echoSemanticReturnType ${physicalizedEchoSemantic.physicalMethod.physicalMethodName}($echoSemanticParameterType values)
                {
                    return base.${physicalizedSuperMethodName("echo", DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK)}(values);
                }

                ${physicalizedLabelTyped.renderSnapshotCSharpOverridePrefix()}
                string ${physicalizedLabelTyped.physicalMethod.physicalMethodName}(string prefix)
                {
                    return "consumer:" + base.${physicalizedSuperMethodName("label", DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY)}(prefix);
                }
            }

            public sealed class RecordedFamilyGrandchild<T> : $physicalizedTypeName<T>
            {
                public RecordedFamilyGrandchild(T initial) : base(initial) {}

                public override void ${physicalizedWriteTyped.physicalMethod.physicalMethodName}(T next)
                {
                    base.${physicalizedWriteTyped.physicalMethod.physicalMethodName}(next);
                }

                protected override void ${physicalizedWriteSemantic.physicalMethod.physicalMethodName}(object next)
                {
                    base.${physicalizedWriteSemantic.physicalMethod.physicalMethodName}("grandchild:" + next);
                }
            }

            public static class RecordedFamilyEntry
            {
                #if GENERIC_OWNER_MEASUREMENT
                private static int ExecuteMeasurementWorkload(int iterations)
                {
                    int checksum = 17;
                    int[] typedArray = new int[] { 1, 2, 3 };
                    string[] semanticArray = new string[] { "semantic", "array" };
                    $physicalizedClosedTypeName typedOwner = new $physicalizedClosedTypeName(0);
                    $capabilityTypeName typedCapability = typedOwner;
                    RecordedFamilyGrandchild<int> hostileOwner = new RecordedFamilyGrandchild<int>(0);
                    $capabilityTypeName hostileCapability = hostileOwner;

                    for (int index = 0; index < iterations; index++)
                    {
                        int next = index & 1023;
                        $capabilityTypeName exactValue =
                            RecordedOpenNullableFactory.Create(typeof(int), null);
                        exactValue.$writeCapability(next);
                        int exactRead = (int)exactValue.$readCapability();
                        if (exactRead != next) throw new InvalidOperationException("exact value route");

                        $capabilityTypeName exactNullable =
                            RecordedOpenNullableFactory.Create(typeof(int?), null);
                        exactNullable.$writeCapability(next);
                        if ((int)exactNullable.$readCapability() != next)
                            throw new InvalidOperationException("already-nullable route");

                        $capabilityTypeName exactReference =
                            RecordedOpenNullableFactory.Create(typeof(string), null);
                        exactReference.$writeCapability("reference");
                        if (!object.Equals(exactReference.$readCapability(), "reference"))
                            throw new InvalidOperationException("exact reference route");

                        RecordedKnownStruct known = new RecordedKnownStruct { Value = next };
                        $capabilityTypeName exactStruct =
                            RecordedOpenNullableFactory.Create(typeof(RecordedKnownStruct), null);
                        exactStruct.$writeCapability(known);
                        if (((RecordedKnownStruct)exactStruct.$readCapability()).Value != next)
                            throw new InvalidOperationException("exact struct route");

                        RecordedUnknownStruct unknown = new RecordedUnknownStruct { Value = next + 1 };
                        $capabilityTypeName fallbackStruct =
                            RecordedOpenNullableFactory.Create(typeof(RecordedUnknownStruct), null);
                        fallbackStruct.$writeCapability(unknown);
                        if (((RecordedUnknownStruct)fallbackStruct.$readCapability()).Value != next + 1)
                            throw new InvalidOperationException("fallback struct route");

                        RecordedUnknownReference unknownReference =
                            new RecordedUnknownReference { Value = "fallback" };
                        $capabilityTypeName fallbackReference =
                            RecordedOpenNullableFactory.Create(typeof(RecordedUnknownReference), null);
                        fallbackReference.$writeCapability(unknownReference);
                        if (!object.ReferenceEquals(fallbackReference.$readCapability(), unknownReference))
                            throw new InvalidOperationException("fallback reference route");

                        typedCapability.$writeCapability(next);
                        int typedRead = typedOwner.${physicalizedReadTyped.physicalMethod.physicalMethodName}();
                        if (typedRead != next) throw new InvalidOperationException("typed dispatch");
                        if (!object.ReferenceEquals(
                                typedOwner.${physicalizedEchoTyped.physicalMethod.physicalMethodName}(typedArray),
                                typedArray) || !object.ReferenceEquals(
                                typedCapability.$echoCapability(semanticArray), semanticArray))
                            throw new InvalidOperationException("array dispatch");

                        if ((index & 63) == 0)
                        {
                            hostileCapability.$writeCapability("wrong");
                            if (!object.Equals(
                                    hostileCapability.$readCapability(), "grandchild:wrong"))
                                throw new InvalidOperationException("semantic hostile dispatch");
                            try
                            {
                                hostileOwner.${physicalizedReadTyped.physicalMethod.physicalMethodName}();
                                throw new InvalidOperationException("delayed typed read did not fail");
                            }
                            catch (InvalidCastException)
                            {
                                checksum = unchecked(checksum * 31 + 7);
                            }
                        }
                        checksum = unchecked(checksum * 31 + exactRead + typedRead + known.Value + unknown.Value);
                    }
                    return checksum;
                }

                public static int Main(string[] arguments)
                {
                    bool holdForPeakWorkingSet = arguments.Length == 3 &&
                        arguments[2] == "--hold-for-peak-working-set";
                    if ((arguments.Length != 2 && !holdForPeakWorkingSet) ||
                            arguments[0] != "--measurement" ||
                            !int.TryParse(arguments[1], out int iterations) || iterations < 0)
                    {
                        Console.Error.WriteLine(
                            "usage: --measurement <non-negative iterations> " +
                            "[--hold-for-peak-working-set]");
                        return 64;
                    }
                    if (iterations > 0) ExecuteMeasurementWorkload(Math.Min(iterations, 512));
                    GC.Collect();
                    GC.WaitForPendingFinalizers();
                    GC.Collect();
                    long allocatedBefore = GC.GetAllocatedBytesForCurrentThread();
                    long started = System.Diagnostics.Stopwatch.GetTimestamp();
                    int checksum = ExecuteMeasurementWorkload(iterations);
                    long elapsedTicks = System.Diagnostics.Stopwatch.GetTimestamp() - started;
                    long allocatedBytes = GC.GetAllocatedBytesForCurrentThread() - allocatedBefore;
                    Console.WriteLine(
                        "GENERIC_OWNER_MEASUREMENT|workloadVersion=$GENERIC_OWNER_MEASUREMENT_WORKLOAD_VERSION" +
                        "|iterations=" + iterations +
                        "|checksum=" + checksum +
                        "|elapsedTicks=" + elapsedTicks +
                        "|frequency=" + System.Diagnostics.Stopwatch.Frequency +
                        "|allocatedBytes=" + allocatedBytes);
                    if (holdForPeakWorkingSet)
                    {
                        Console.Out.Flush();
                        if (Console.In.ReadLine() != "release")
                        {
                            Console.Error.WriteLine("peak-working-set hold was not released");
                            return 65;
                        }
                    }
                    return 0;
                }
                #else
                public static int Main()
                {
                    $capabilityTypeName exactValue = RecordedOpenNullableFactory.Create(typeof(int), null);
                    if (exactValue.GetType().GetGenericArguments()[0] != typeof(int?) ||
                        RecordedReflectionRegistry.NormalizeExact(exactValue.GetType()) !=
                            $stateLogicalClassifierKey) return 19;
                    exactValue.$writeCapability(7);
                    if (!object.Equals(exactValue.$readCapability(), 7)) return 20;

                    $capabilityTypeName exactNullable =
                        RecordedOpenNullableFactory.Create(typeof(int?), null);
                    if (exactNullable.GetType().GetGenericArguments()[0] != typeof(int?)) return 21;
                    exactNullable.$writeCapability(9);
                    if (!object.Equals(exactNullable.$readCapability(), 9)) return 22;

                    $capabilityTypeName exactReference =
                        RecordedOpenNullableFactory.Create(typeof(string), null);
                    if (exactReference.GetType().GetGenericArguments()[0] != typeof(string)) return 23;
                    exactReference.$writeCapability("reference");
                    if (!object.Equals(exactReference.$readCapability(), "reference")) return 24;

                    RecordedKnownStruct known = new RecordedKnownStruct { Value = 31 };
                    $capabilityTypeName exactStruct =
                        RecordedOpenNullableFactory.Create(typeof(RecordedKnownStruct), null);
                    if (exactStruct.GetType().GetGenericArguments()[0] != typeof(RecordedKnownStruct?)) return 25;
                    exactStruct.$writeCapability(known);
                    if (!object.Equals(exactStruct.$readCapability(), known)) return 26;

                    RecordedUnknownStruct unknown = new RecordedUnknownStruct { Value = 37 };
                    $capabilityTypeName fallbackStruct =
                        RecordedOpenNullableFactory.Create(typeof(RecordedUnknownStruct), null);
                    if (fallbackStruct.GetType().GetGenericArguments()[0] != typeof(object) ||
                        RecordedReflectionRegistry.NormalizeExact(fallbackStruct.GetType()) !=
                            $stateLogicalClassifierKey) return 27;
                    fallbackStruct.$writeCapability(unknown);
                    if (!object.Equals(fallbackStruct.$readCapability(), unknown)) return 28;

                    RecordedUnknownReference unknownReference = new RecordedUnknownReference { Value = "fallback" };
                    $capabilityTypeName fallbackReference =
                        RecordedOpenNullableFactory.Create(typeof(RecordedUnknownReference), null);
                    if (fallbackReference.GetType().GetGenericArguments()[0] != typeof(object) ||
                        RecordedReflectionRegistry.NormalizeExact(fallbackReference.GetType()) !=
                            $stateLogicalClassifierKey) return 29;
                    fallbackReference.$writeCapability(unknownReference);
                    if (!object.ReferenceEquals(fallbackReference.$readCapability(), unknownReference)) return 30;

                    $physicalizedClosedTypeName physicalizedValue = new $physicalizedClosedTypeName(1);
                    RecordedFamilyGrandchild<int> value = new RecordedFamilyGrandchild<int>(1);
                    Type physicalizedDefinition = typeof($physicalizedOpenTypeName);
                    Type physicalizedParameter = physicalizedDefinition.GetGenericArguments()[0];
                    if (!physicalizedDefinition.IsPublic ||
                        physicalizedDefinition.BaseType == null ||
                        physicalizedDefinition.BaseType.GetGenericTypeDefinition() != typeof($immediateOpenTypeName) ||
                        physicalizedDefinition.BaseType.GetGenericArguments()[0] != physicalizedParameter ||
                        (physicalizedParameter.GenericParameterAttributes &
                            System.Reflection.GenericParameterAttributes.SpecialConstraintMask) != 0 ||
                        physicalizedParameter.GetGenericParameterConstraints().Length != 0 ||
                        physicalizedDefinition.GetConstructor(new Type[] { physicalizedParameter }) == null) return 7;
                    if (RecordedReflectionRegistry.NormalizeExact(typeof($stateClosedTypeName)) !=
                        $stateLogicalClassifierKey) return 8;
                    if (RecordedReflectionRegistry.NormalizeExact(typeof($stateOpenTypeName)) !=
                        $stateLogicalClassifierKey) return 9;
                    if (RecordedReflectionRegistry.NormalizeExact(typeof($stateAlternativeClosedTypeName)) !=
                        $stateLogicalClassifierKey) return 15;
                    if (RecordedReflectionRegistry.NormalizeExact(typeof($immediateOpenTypeName)) !=
                        $immediateLogicalClassifierKey) return 10;
                    if (RecordedReflectionRegistry.NormalizeExact(typeof($immediateClosedTypeName)) !=
                        $immediateLogicalClassifierKey) return 16;
                    if (RecordedReflectionRegistry.NormalizeExact(typeof($capabilityTypeName)) != null) return 11;
                    if (RecordedReflectionRegistry.NormalizeExact(typeof($physicalizedClosedTypeName)) != null) return 12;
                    if (!RecordedReflectionRegistry.IsLogicalInstance(value, $stateLogicalClassifierKey) ||
                        !RecordedReflectionRegistry.IsLogicalInstance(value, $immediateLogicalClassifierKey)) return 13;
                    System.Reflection.MethodInfo reflectedWrite = typeof($stateClosedTypeName).GetMethod(
                        $writeInvocationMethodName,
                        System.Reflection.BindingFlags.Instance |
                        System.Reflection.BindingFlags.Public |
                        System.Reflection.BindingFlags.NonPublic);
                    if (reflectedWrite == null || !reflectedWrite.IsPrivate) return 14;
                    System.Reflection.MethodInfo physicalizedTypedWrite = physicalizedDefinition.GetMethod(
                        ${physicalizedWriteTyped.physicalMethod.physicalMethodName.asSnapshotCSharpStringLiteral()},
                        System.Reflection.BindingFlags.Instance |
                        System.Reflection.BindingFlags.Public |
                        System.Reflection.BindingFlags.NonPublic |
                        System.Reflection.BindingFlags.DeclaredOnly);
                    System.Reflection.MethodInfo physicalizedSemanticWrite = physicalizedDefinition.GetMethod(
                        ${physicalizedWriteSemantic.physicalMethod.physicalMethodName.asSnapshotCSharpStringLiteral()},
                        System.Reflection.BindingFlags.Instance |
                        System.Reflection.BindingFlags.Public |
                        System.Reflection.BindingFlags.NonPublic |
                        System.Reflection.BindingFlags.DeclaredOnly);
                    System.Reflection.MethodInfo immediateTypedWrite = physicalizedDefinition.BaseType.GetMethod(
                        ${physicalizedWriteTyped.overriddenPhysicalMethod.physicalMethodName.asSnapshotCSharpStringLiteral()},
                        System.Reflection.BindingFlags.Instance |
                        System.Reflection.BindingFlags.Public |
                        System.Reflection.BindingFlags.NonPublic);
                    System.Reflection.MethodInfo immediateSemanticWrite = physicalizedDefinition.BaseType.GetMethod(
                        ${physicalizedWriteSemantic.overriddenPhysicalMethod.physicalMethodName.asSnapshotCSharpStringLiteral()},
                        System.Reflection.BindingFlags.Instance |
                        System.Reflection.BindingFlags.Public |
                        System.Reflection.BindingFlags.NonPublic);
                    if (physicalizedTypedWrite == null || physicalizedSemanticWrite == null ||
                        immediateTypedWrite == null || immediateSemanticWrite == null ||
                        !physicalizedTypedWrite.IsVirtual || !physicalizedSemanticWrite.IsVirtual ||
                        physicalizedTypedWrite.IsFinal || physicalizedSemanticWrite.IsFinal ||
                        physicalizedTypedWrite.GetParameters()[0].ParameterType != physicalizedParameter ||
                        physicalizedSemanticWrite.GetParameters()[0].ParameterType != typeof(object) ||
                        immediateTypedWrite.DeclaringType.GetGenericTypeDefinition() != typeof($immediateOpenTypeName) ||
                        immediateSemanticWrite.DeclaringType.GetGenericTypeDefinition() != typeof($immediateOpenTypeName) ||
                        physicalizedTypedWrite.GetBaseDefinition().DeclaringType.GetGenericTypeDefinition() !=
                            typeof($stateOpenTypeName) ||
                        physicalizedSemanticWrite.GetBaseDefinition().DeclaringType.GetGenericTypeDefinition() !=
                            typeof($stateOpenTypeName)) return 17;
                    $capabilityTypeName semantic = value;
                    semantic.$writeCapability("wrong");
                    if (!object.Equals(semantic.$readCapability(), "grandchild:wrong")) return 1;
                    try
                    {
                        value.${physicalizedReadTyped.physicalMethod.physicalMethodName}();
                        return 2;
                    }
                    catch (InvalidCastException)
                    {
                    }
                    semantic.$writeCapability(4);
                    if (value.${physicalizedReadTyped.physicalMethod.physicalMethodName}() != 4 ||
                        !object.Equals(semantic.$readCapability(), 4)) return 3;
                    int[] typedArray = new int[] { 1, 2 };
                    if (!object.ReferenceEquals(
                        value.${physicalizedEchoTyped.physicalMethod.physicalMethodName}(typedArray), typedArray)) return 5;
                    string[] semanticArray = new string[] { "nested" };
                    if (!object.ReferenceEquals(semantic.$echoCapability(semanticArray), semanticArray)) return 6;
                    if ($defaultOwnerTypeName.${defaultDispatcher.physicalMethodName}(value, null, 1) !=
                        "consumer:default") return 4;
                    $capabilityTypeName directSemantic = physicalizedValue;
                    directSemantic.$writeCapability("direct");
                    if (!object.Equals(directSemantic.$readCapability(), "direct")) return 18;
                    return 0;
                }
                #endif
            }
            """.trimIndent()
        )
    }
    check("MakeGenericType" !in source.readText() && "Activator.CreateInstance" !in source.readText()) {
        "The finite generic-owner construction renderer reintroduced an unbounded dynamic-code path"
    }
    val output = directory.resolve(
        if (target == DotNetTarget.NET48) "RecordedFamilyConsumer.exe" else "RecordedFamilyConsumer.dll"
    )
    val compilation = when (target) {
        DotNetTarget.NET48 -> {
            val compiler = checkNotNull(DotNetIlAssembler.findFrameworkCSharpCompiler()) {
                ".NET Framework C# compiler is required for generic-owner family-record consumption"
            }
            compileFrameworkSnapshotCSharp(compiler, source, output, listOf(producer), executable = true)
        }
        DotNetTarget.NET10_0 -> {
            val toolchain = checkNotNull(DotNetIlAssembler.findModernCSharpCompiler()) {
                "Modern C# compiler is required for generic-owner family-record consumption"
            }
            compileModernSnapshotCSharp(toolchain, source, output, listOf(producer), executable = true)
        }
        DotNetTarget.NETSTANDARD_2_0 -> error("netstandard2.0 has no executable family-record consumer")
    }
    check(compilation.exitCode == 0) { compilation.output }
    executeSnapshotConsumer(target, output, directory)
    val measurementExportPath = System.getProperty(GENERIC_OWNER_MEASUREMENT_EXPORT_PROPERTY)
    if (target == DotNetTarget.NET10_0 && measurementExportPath != null) {
        check(measurementExportPath.isNotBlank()) {
            "Generic-owner measurement export path must not be blank"
        }
        prepareGenericOwnerMeasurementBundle(
            directory = directory,
            source = source,
            producer = producer,
            physicalFamilyArtifact = recordFile,
            logicalConstructionKey = constructionPlan.logicalConstructionKey,
            exportDirectory = File(measurementExportPath),
        )
    }
    val applicationExportPath = System.getProperty(GENERIC_OWNER_APPLICATION_EXPORT_PROPERTY)
    if (applicationExportPath != null) {
        check(applicationExportPath.isNotBlank()) {
            "Generic-owner application export path must not be blank"
        }
        prepareGenericOwnerApplicationBundle(
            directory = directory,
            target = target,
            source = source,
            candidateProducer = producer,
            candidateConsumer = output,
            physicalFamilyArtifact = recordFile,
            exportDirectory = File(applicationExportPath),
        )
    }
}

private fun prepareGenericOwnerMeasurementBundle(
    directory: File,
    source: File,
    producer: File,
    physicalFamilyArtifact: File,
    logicalConstructionKey: String,
    exportDirectory: File,
) {
    check(source.isFile && producer.isFile && physicalFamilyArtifact.isFile) {
        "The generic-owner measurement bundle requires its exact source, producer, and family artifact"
    }
    val sourceText = source.readText()
    check("GENERIC_OWNER_MEASUREMENT" in sourceText &&
            "--hold-for-peak-working-set" in sourceText &&
            "Console.In.ReadLine()" in sourceText &&
            "MakeGenericType" !in sourceText && "Activator.CreateInstance" !in sourceText) {
        "The generic-owner measurement source lost its finite statically rooted workload"
    }
    val project = directory.resolve(GENERIC_OWNER_MEASUREMENT_PROJECT_FILE).apply {
        writeText(
            """
            <Project Sdk="Microsoft.NET.Sdk">
              <PropertyGroup>
                <OutputType>Exe</OutputType>
                <TargetFramework>net10.0</TargetFramework>
                <AssemblyName>RecordedFamilyMeasurement</AssemblyName>
                <RootNamespace>RecordedFamilyMeasurement</RootNamespace>
                <EnableDefaultCompileItems>false</EnableDefaultCompileItems>
                <DefineConstants>GENERIC_OWNER_MEASUREMENT</DefineConstants>
                <ImplicitUsings>disable</ImplicitUsings>
                <Nullable>disable</Nullable>
                <Optimize>true</Optimize>
                <Deterministic>true</Deterministic>
                <DebugSymbols>false</DebugSymbols>
                <DebugType>none</DebugType>
                <InvariantGlobalization>true</InvariantGlobalization>
                <IsAotCompatible>true</IsAotCompatible>
                <WarningsAsErrors>IL2026;IL3050</WarningsAsErrors>
              </PropertyGroup>
              <ItemGroup>
                <Compile Include="${source.name}" />
                <Reference Include="SnapshotProducer">
                  <HintPath>${producer.name}</HintPath>
                  <Private>true</Private>
                </Reference>
              </ItemGroup>
            </Project>
            """.trimIndent()
        )
    }
    val globalJson = directory.resolve("global.json").apply {
        writeText(
            """
            {
              "sdk": {
                "version": "10.0.100",
                "rollForward": "disable",
                "allowPrerelease": false
              }
            }
            """.trimIndent()
        )
    }
    val manifest = directory.resolve(GENERIC_OWNER_MEASUREMENT_MANIFEST_FILE).apply {
        val sourceFingerprint = DotNetGenericOwnerPhysicalFamilyCodec.producerFingerprint(source.readBytes())
        val projectFingerprint = DotNetGenericOwnerPhysicalFamilyCodec.producerFingerprint(project.readBytes())
        val globalJsonFingerprint = DotNetGenericOwnerPhysicalFamilyCodec.producerFingerprint(globalJson.readBytes())
        val artifactFingerprint =
            DotNetGenericOwnerPhysicalFamilyCodec.producerFingerprint(physicalFamilyArtifact.readBytes())
        check(logicalConstructionKey.none { character -> character == '\n' || character == '\r' })
        writeText(
            """
            schema=1
            workloadVersion=$GENERIC_OWNER_MEASUREMENT_WORKLOAD_VERSION
            sdkVersion=10.0.100
            targetProfile=NET10_0
            logicalConstructionKey=$logicalConstructionKey
            producerSha256=${DotNetGenericOwnerPhysicalFamilyCodec.producerFingerprint(producer.readBytes())}
            sourceSha256=$sourceFingerprint
            projectSha256=$projectFingerprint
            globalJsonSha256=$globalJsonFingerprint
            physicalFamilyArtifactSha256=$artifactFingerprint
            """.trimIndent()
        )
    }
    val bundleFiles = listOf(source, producer, physicalFamilyArtifact, project, globalJson, manifest)
    check(bundleFiles.map(File::getName).toSet().size == bundleFiles.size && bundleFiles.all(File::isFile)) {
        "The generic-owner measurement bundle is incomplete or has colliding file names"
    }
    check(!exportDirectory.exists() || exportDirectory.isDirectory) {
        "Generic-owner measurement export path is not a directory: $exportDirectory"
    }
    check(exportDirectory.mkdirs() || exportDirectory.isDirectory) {
        "Cannot create generic-owner measurement export directory: $exportDirectory"
    }
    check(exportDirectory.list()?.isEmpty() == true) {
        "Generic-owner measurement export directory must be empty: $exportDirectory"
    }
    bundleFiles.forEach { file -> file.copyTo(exportDirectory.resolve(file.name), overwrite = false) }
}

private fun prepareGenericOwnerApplicationBundle(
    directory: File,
    target: DotNetTarget,
    source: File,
    candidateProducer: File,
    candidateConsumer: File,
    physicalFamilyArtifact: File,
    exportDirectory: File,
) {
    val applicationSource = directory.resolve(GENERIC_OWNER_APPLICATION_SOURCE_FILE)
    val erasedProducer = directory.resolve(GENERIC_OWNER_ERASED_PRODUCER_FILE)
    val erasedConsumer = directory.resolve(genericOwnerErasedConsumerFile(target))
    val candidateRuntimeConfig = if (target == DotNetTarget.NET10_0) {
        directory.resolve("${candidateConsumer.nameWithoutExtension}.runtimeconfig.json")
    } else {
        null
    }
    val platformProperty = "kotlin.dotnet.test.platform.${target.description}.path"
    val platformDirectory = System.getProperty(platformProperty)?.let(::File)
        ?: error("Missing reusable Kotlin/.NET test platform property '$platformProperty'")
    val runtime = platformDirectory.resolve(DotNetRuntimeArtifact.ASSEMBLY_FILE_NAME)
    val stdlib = platformDirectory.resolve(DotNetStdlibArtifact.ASSEMBLY_FILE_NAME)
    check(listOf(
        source,
        candidateProducer,
        candidateConsumer,
        physicalFamilyArtifact,
        applicationSource,
        erasedProducer,
        erasedConsumer,
        runtime,
        stdlib,
    ).all(File::isFile) && candidateRuntimeConfig?.isFile != false) {
        "The generic-owner application bundle requires candidate, erased, runtime, and stdlib artifacts"
    }
    val stagedRuntime = directory.resolve(runtime.name).also { destination ->
        if (!destination.exists()) runtime.copyTo(destination)
    }
    val stagedStdlib = directory.resolve(stdlib.name).also { destination ->
        if (!destination.exists()) stdlib.copyTo(destination)
    }
    check(stagedRuntime.isFile && stagedStdlib.isFile &&
            stagedRuntime.readBytes().contentEquals(runtime.readBytes()) &&
            stagedStdlib.readBytes().contentEquals(stdlib.readBytes())) {
        "The generic-owner application bundle staged stale runtime or stdlib artifacts"
    }
    executeSnapshotConsumer(target, erasedConsumer, directory)
    val erasedConsumerRuntimeConfig = if (target == DotNetTarget.NET10_0) {
        directory.resolve("${erasedConsumer.nameWithoutExtension}.runtimeconfig.json")
    } else {
        null
    }
    check(erasedConsumerRuntimeConfig?.isFile != false) {
        "The erased Kotlin generic-owner application lacks its runtime config"
    }
    val erasedCSharpSource = directory.resolve(GENERIC_OWNER_ERASED_CSHARP_SOURCE_FILE).apply {
        writeText(
            """
            using System;
            using ErasedStore = global::generic.owner.oracle.HostileUnsafeStore;
            using ErasedMid = global::generic.owner.oracle.HostileUnsafeMid;

            public enum ApplicationEnum
            {
                First = 1,
                Second = 2,
            }

            public struct ApplicationStruct
            {
                public int Count;
                public Guid Id;
            }

            public class ErasedCSharpLeaf : ErasedMid
            {
                public ErasedCSharpLeaf(object initial) : base(initial) {}

                public override void writeUnsafe(object next)
                {
                    base.writeUnsafe(next is string ? "csharp:" + next : next);
                }

                public override object read()
                {
                    return base.read();
                }

                public override Array echo(Array values)
                {
                    return base.echo(values);
                }
            }

            public sealed class ErasedCSharpGrandchild : ErasedCSharpLeaf
            {
                public ErasedCSharpGrandchild(object initial) : base(initial) {}

                public override void writeUnsafe(object next)
                {
                    base.writeUnsafe(next is string ? "grandchild:" + next : next);
                }
            }

            public static class ErasedApplicationEntry
            {
                private static bool RoundTrips(object value)
                {
                    ErasedStore owner = new ErasedStore(value);
                    if (!object.Equals(owner.read(), value)) return false;
                    owner.writeUnsafe(value);
                    return object.Equals(owner.read(), value);
                }

                public static int Main()
                {
                    Guid guid = new Guid("00112233-4455-6677-8899-aabbccddeeff");
                    DateTime date = new DateTime(638900000000000000L, DateTimeKind.Utc);
                    decimal amount = 1234567.8901m;
                    ValueTuple<int, string> tuple = new ValueTuple<int, string>(7, "tuple");
                    ApplicationStruct user = new ApplicationStruct { Count = 11, Id = guid };
                    if (!RoundTrips(guid) || !RoundTrips(date) || !RoundTrips(amount) ||
                            !RoundTrips(ApplicationEnum.Second) || !RoundTrips(tuple) ||
                            !RoundTrips(user) || !RoundTrips(null)) return 1;

                    ErasedStore mixed = new ErasedStore(guid);
                    mixed.writeUnsafe(date);
                    if (!object.Equals(mixed.read(), date)) return 2;
                    mixed.writeUnsafe(amount);
                    if (!object.Equals(mixed.read(), amount)) return 3;
                    mixed.writeUnsafe(user);
                    if (!object.Equals(mixed.read(), user)) return 4;

                    Guid?[] nullableGuids = new Guid?[] { guid, null };
                    string[] strings = new string[] { "array" };
                    if (!object.ReferenceEquals(mixed.echo(nullableGuids), nullableGuids) ||
                            !object.ReferenceEquals(mixed.echo(strings), strings)) return 5;
                    ApplicationStruct[] users = new ApplicationStruct[] { user };
                    if (!object.ReferenceEquals(mixed.relay<ApplicationStruct>(users), users)) return 6;

                    ErasedCSharpGrandchild child = new ErasedCSharpGrandchild("initial");
                    child.writeUnsafe("value");
                    if (!object.Equals(child.read(), "csharp:grandchild:value")) return 7;
                    if (!object.ReferenceEquals(child.echo(strings), strings)) return 8;

                    Type ownerType = typeof(ErasedStore);
                    System.Reflection.MethodInfo read = ownerType.GetMethod("read");
                    System.Reflection.MethodInfo write = ownerType.GetMethod("writeUnsafe");
                    System.Reflection.MethodInfo echo = ownerType.GetMethod("echo");
                    System.Reflection.MethodInfo relay = ownerType.GetMethod("relay");
                    if (ownerType.IsGenericType || ownerType.GetGenericArguments().Length != 0 ||
                            ownerType.GetConstructor(new Type[] { typeof(object) }) == null ||
                            read == null || read.ReturnType != typeof(object) ||
                            write == null || write.GetParameters()[0].ParameterType != typeof(object) ||
                            echo == null || echo.ReturnType != typeof(Array) ||
                            echo.GetParameters()[0].ParameterType != typeof(Array) ||
                            relay == null || !relay.IsGenericMethodDefinition ||
                            relay.GetGenericArguments().Length != 1) return 9;
                    return 0;
                }
            }
            """.trimIndent()
        )
    }
    val erasedCSharpAssembly = directory.resolve(genericOwnerErasedCSharpAssemblyFile(target))
    val erasedCSharpCompilation = when (target) {
        DotNetTarget.NET48 -> compileFrameworkSnapshotCSharp(
            checkNotNull(DotNetIlAssembler.findFrameworkCSharpCompiler()) {
                "Framework C# compiler is required for the erased generic-owner application corpus"
            },
            erasedCSharpSource,
            erasedCSharpAssembly,
            references = listOf(erasedProducer, stagedRuntime, stagedStdlib),
            executable = true,
        )
        DotNetTarget.NET10_0 -> compileModernSnapshotCSharp(
            checkNotNull(DotNetIlAssembler.findModernCSharpCompiler()) {
                "Modern C# compiler is required for the erased generic-owner application corpus"
            },
            erasedCSharpSource,
            erasedCSharpAssembly,
            references = listOf(erasedProducer, stagedRuntime, stagedStdlib),
            executable = true,
        )
        DotNetTarget.NETSTANDARD_2_0 -> error("netstandard2.0 has no executable application corpus")
    }
    check(erasedCSharpCompilation.exitCode == 0) { erasedCSharpCompilation.output }
    executeSnapshotConsumer(target, erasedCSharpAssembly, directory)
    val erasedCSharpRuntimeConfig = if (target == DotNetTarget.NET10_0) {
        directory.resolve("${erasedCSharpAssembly.nameWithoutExtension}.runtimeconfig.json")
    } else {
        null
    }
    check(erasedCSharpRuntimeConfig?.isFile != false) {
        "The erased generic-owner C# application lacks its runtime config"
    }
    val globalJson = if (target == DotNetTarget.NET10_0) {
        directory.resolve("global.json").apply {
            writeText(
            """
            {
              "sdk": {
                "version": "10.0.100",
                "rollForward": "disable",
                "allowPrerelease": false
              }
            }
            """.trimIndent()
            )
        }
    } else {
        null
    }
    val manifest = directory.resolve(GENERIC_OWNER_APPLICATION_MANIFEST_FILE).apply {
        writeText(buildString {
            appendLine("schema=1")
            appendLine("sdkVersion=${if (target == DotNetTarget.NET10_0) "10.0.100" else "framework-clr"}")
            appendLine("targetProfile=${target.name}")
            appendLine("applicationSourceSha256=${DotNetGenericOwnerPhysicalFamilyCodec.producerFingerprint(applicationSource.readBytes())}")
            appendLine("candidateProducerSha256=${DotNetGenericOwnerPhysicalFamilyCodec.producerFingerprint(candidateProducer.readBytes())}")
            appendLine("candidateConsumerSha256=${DotNetGenericOwnerPhysicalFamilyCodec.producerFingerprint(candidateConsumer.readBytes())}")
            candidateRuntimeConfig?.let { file ->
                appendLine("candidateRuntimeConfigSha256=${DotNetGenericOwnerPhysicalFamilyCodec.producerFingerprint(file.readBytes())}")
            }
            appendLine("candidateSourceSha256=${DotNetGenericOwnerPhysicalFamilyCodec.producerFingerprint(source.readBytes())}")
            appendLine("physicalFamilyArtifactSha256=${DotNetGenericOwnerPhysicalFamilyCodec.producerFingerprint(physicalFamilyArtifact.readBytes())}")
            appendLine("erasedProducerSha256=${DotNetGenericOwnerPhysicalFamilyCodec.producerFingerprint(erasedProducer.readBytes())}")
            appendLine("erasedConsumerSha256=${DotNetGenericOwnerPhysicalFamilyCodec.producerFingerprint(erasedConsumer.readBytes())}")
            erasedConsumerRuntimeConfig?.let { file ->
                appendLine("erasedConsumerRuntimeConfigSha256=${DotNetGenericOwnerPhysicalFamilyCodec.producerFingerprint(file.readBytes())}")
            }
            appendLine("erasedCSharpSourceSha256=${DotNetGenericOwnerPhysicalFamilyCodec.producerFingerprint(erasedCSharpSource.readBytes())}")
            appendLine("erasedCSharpAssemblySha256=${DotNetGenericOwnerPhysicalFamilyCodec.producerFingerprint(erasedCSharpAssembly.readBytes())}")
            erasedCSharpRuntimeConfig?.let { file ->
                appendLine("erasedCSharpRuntimeConfigSha256=${DotNetGenericOwnerPhysicalFamilyCodec.producerFingerprint(file.readBytes())}")
            }
            appendLine("runtimeSha256=${DotNetGenericOwnerPhysicalFamilyCodec.producerFingerprint(runtime.readBytes())}")
            appendLine("stdlibSha256=${DotNetGenericOwnerPhysicalFamilyCodec.producerFingerprint(stdlib.readBytes())}")
            globalJson?.let { file ->
                append("globalJsonSha256=${DotNetGenericOwnerPhysicalFamilyCodec.producerFingerprint(file.readBytes())}")
            }
        })
    }
    val bundleFiles = listOf(
        source,
        candidateProducer,
        candidateConsumer,
        physicalFamilyArtifact,
        applicationSource,
        erasedProducer,
        erasedConsumer,
        erasedCSharpSource,
        erasedCSharpAssembly,
        stagedRuntime,
        stagedStdlib,
        manifest,
    ) + listOfNotNull(
        candidateRuntimeConfig,
        erasedConsumerRuntimeConfig,
        erasedCSharpRuntimeConfig,
        globalJson,
    )
    check(bundleFiles.map(File::getName).toSet().size == bundleFiles.size && bundleFiles.all(File::isFile)) {
        "The generic-owner application bundle is incomplete or has colliding file names"
    }
    check(!exportDirectory.exists() || exportDirectory.isDirectory) {
        "Generic-owner application export path is not a directory: $exportDirectory"
    }
    check(exportDirectory.mkdirs() || exportDirectory.isDirectory) {
        "Cannot create generic-owner application export directory: $exportDirectory"
    }
    check(exportDirectory.list()?.isEmpty() == true) {
        "Generic-owner application export directory must be empty: $exportDirectory"
    }
    bundleFiles.forEach { file -> file.copyTo(exportDirectory.resolve(file.name), overwrite = false) }
}

private fun DotNetGenericOwnerPhysicalTypeExpressionRecord.renderSnapshotCSharpType(
    ownerArguments: List<String>,
    methodArguments: List<String> = emptyList(),
): String = when (kind) {
    DotNetGenericOwnerPhysicalTypeKind.VOID -> "void"
    DotNetGenericOwnerPhysicalTypeKind.BOOLEAN -> "bool"
    DotNetGenericOwnerPhysicalTypeKind.INT32 -> "int"
    DotNetGenericOwnerPhysicalTypeKind.STRING -> "string"
    DotNetGenericOwnerPhysicalTypeKind.OBJECT -> "object"
    DotNetGenericOwnerPhysicalTypeKind.OWNER_TYPE_PARAMETER ->
        ownerArguments.getOrElse(checkNotNull(parameterIndex)) {
            error("The recorded physical type references a missing owner argument: $this")
        }
    DotNetGenericOwnerPhysicalTypeKind.METHOD_TYPE_PARAMETER ->
        methodArguments.getOrElse(checkNotNull(parameterIndex)) {
            error("The recorded physical type references a missing method argument: $this")
        }
    DotNetGenericOwnerPhysicalTypeKind.NAMED -> buildString {
        check(scope != null && typePath.isNotEmpty()) { "The recorded named physical type is incomplete: $this" }
        append(typePath.joinToString("."))
        if (arguments.isNotEmpty()) {
            append('<')
            append(arguments.joinToString(", ") { argument ->
                argument.renderSnapshotCSharpType(ownerArguments, methodArguments)
            })
            append('>')
        }
    }
    DotNetGenericOwnerPhysicalTypeKind.SZ_ARRAY ->
        "${arguments.single().renderSnapshotCSharpType(ownerArguments, methodArguments)}[]"
}

private data class SnapshotCSharpCompilation(
    val exitCode: Int,
    val output: String,
)

private fun compileFrameworkSnapshotCSharp(
    frameworkCompiler: File,
    source: File,
    output: File,
    references: List<File>,
    executable: Boolean,
): SnapshotCSharpCompilation {
    output.delete()
    val toolchain = checkNotNull(DotNetIlAssembler.findModernCSharpCompiler()) {
        "Modern Roslyn is required for deterministic Framework snapshot compilation"
    }
    val frameworkReferences = listOf("mscorlib.dll", "System.dll", "System.Core.dll").map { name ->
        frameworkCompiler.parentFile.resolve(name).also { reference ->
            check(reference.isFile) { "Framework reference assembly is missing: ${reference.path}" }
        }
    }
    return runSnapshotCompiler(buildList {
        add(toolchain.dotNetHost.path)
        add(toolchain.compiler.path)
        add("/nologo")
        add("/noconfig")
        add("/nostdlib+")
        add("/deterministic+")
        add("/target:${if (executable) "exe" else "library"}")
        add("/out:${output.path}")
        frameworkReferences.forEach { reference -> add("/reference:${reference.path}") }
        references.forEach { reference -> add("/reference:${reference.path}") }
        add(source.path)
    }, output.parentFile)
}

private fun compileModernSnapshotCSharp(
    toolchain: org.jetbrains.kotlin.backend.dotnet.DotNetModernCSharpToolchain,
    source: File,
    output: File,
    references: List<File>,
    executable: Boolean,
): SnapshotCSharpCompilation {
    output.delete()
    val frameworkReferences = toolchain.referenceDirectory.listFiles { file ->
        file.isFile && file.extension.equals("dll", ignoreCase = true)
    }?.sortedBy(File::getName) ?: error("Unreadable modern reference pack: ${toolchain.referenceDirectory}")
    return runSnapshotCompiler(buildList {
        add(toolchain.dotNetHost.path)
        add(toolchain.compiler.path)
        add("/nologo")
        add("/noconfig")
        add("/nostdlib+")
        add("/deterministic+")
        add("/target:${if (executable) "exe" else "library"}")
        add("/out:${output.path}")
        frameworkReferences.forEach { reference -> add("/reference:${reference.path}") }
        references.forEach { reference -> add("/reference:${reference.path}") }
        add(source.path)
    }, output.parentFile)
}

private fun runSnapshotCompiler(arguments: List<String>, directory: File): SnapshotCSharpCompilation {
    val process = ProcessBuilder(arguments)
        .directory(directory)
        .redirectErrorStream(true)
        .start()
    check(process.waitFor(3, TimeUnit.MINUTES)) {
        process.destroyForcibly()
        "C# snapshot physicalizer timed out"
    }
    val output = process.inputStream.bufferedReader().use { it.readText() }
    return SnapshotCSharpCompilation(process.exitValue(), output)
}

private fun executeSnapshotConsumer(target: DotNetTarget, assembly: File, directory: File) {
    val command = when (target) {
        DotNetTarget.NET48 -> {
            val host = checkNotNull(DotNetIlAssembler.findFrameworkPowerShellHost())
            val escapedAssembly = assembly.absolutePath.replace("'", "''")
            val script = """
                ${'$'}ErrorActionPreference = 'Stop'
                try {
                    ${'$'}assembly = [Reflection.Assembly]::LoadFrom('$escapedAssembly')
                    ${'$'}result = ${'$'}assembly.EntryPoint.Invoke(${'$'}null, ${'$'}null)
                    if ([int]${'$'}result -ne 0) { exit [int]${'$'}result }
                } catch {
                    [Console]::Error.WriteLine(${'$'}_.Exception.ToString())
                    exit 1
                }
            """.trimIndent()
            listOf(host.path, "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", script)
        }
        DotNetTarget.NET10_0 -> {
            assembly.resolveSibling("${assembly.nameWithoutExtension}.runtimeconfig.json").writeText(
                """
                {
                  "runtimeOptions": {
                    "tfm": "net10.0",
                    "framework": {
                      "name": "Microsoft.NETCore.App",
                      "version": "10.0.0"
                    },
                    "rollForward": "LatestMinor"
                  }
                }
                """.trimIndent()
            )
            val host = checkNotNull(DotNetIlAssembler.findModernDotNetHost())
            listOf(host.path, "exec", assembly.path)
        }
        DotNetTarget.NETSTANDARD_2_0 -> error("netstandard2.0 has no executable snapshot consumer")
    }
    val process = ProcessBuilder(command)
        .directory(directory)
        .redirectErrorStream(true)
        .start()
    check(process.waitFor(3, TimeUnit.MINUTES)) {
        process.destroyForcibly()
        "Generic-owner snapshot consumer timed out"
    }
    val output = process.inputStream.bufferedReader().use { it.readText() }
    check(process.exitValue() == 0) {
        "Generic-owner snapshot consumer failed with ${process.exitValue()}: $output"
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
        // Selected upstream tests receive narrow kotlin.test/kotlin.text helpers from the
        // additional-source provider. This permission is test-source policy and is independent
        // of whether the platform library is consumed as a DLL or produced from source.
        return mapOf(AnalysisFlags.allowKotlinPackage to true)
    }

    override fun configureCompilerConfiguration(configuration: CompilerConfiguration, module: TestModule) {
        if (!module.targetPlatform(testServices).isDotNet()) return

        val artifactName = getArtifactName(module)
        val producesStdlibFromSource = DotNetCodegenDirectives.DOTNET_STDLIB_FROM_SOURCE in module.directives
        configuration.put(CLIConfigurationKeys.ALLOW_KOTLIN_PACKAGE, true)
        configuration.put(CommonConfigurationKeys.MODULE_NAME, module.name)
        configuration.targetPlatform = DotNetPlatforms.defaultDotNetPlatform
        configuration.dotNetAssemblyName = artifactName
        configuration.dotNetProducesLibrary = isLibraryModule(module)
        configuration.dotNetMemberReflection =
            DotNetCodegenDirectives.DOTNET_MEMBER_REFLECTION in module.directives
        configuration.dotNetExports = module.directives[DotNetCodegenDirectives.DOTNET_EXPORT]
            .map(DotNetExport::parse)
        configuration.dotNetPropertyExports = module.directives[DotNetCodegenDirectives.DOTNET_EXPORT_PROPERTY]
            .map(DotNetPropertyExport::parse)
        configuration.dotNetOutput = getConfiguredOutput(module, artifactName)
        configuration.dotNetTarget = target
        // KLIB source identities must not retain the test framework's random temporary root.
        // Besides making embedded library metadata reproducible, this matches the public
        // -Xklib-relative-path-base contract used by the other KLIB-producing backends.
        configuration.klibRelativePathBases = listOf(
            testServices.sourceFileProvider.getKotlinSourceDirectoryForModule(module).canonicalPath,
        )
        // Match the KLIB test environments: a selected binary library's regular dependency
        // closure is part of the compiler classpath. Inline bodies may refer to declarations in
        // that closure even when the consuming source names only the immediate library.
        val binaryLibraries = buildList {
            addAll(module.transitiveRegularDependencies(reverseOrder = true) { it.kind == DependencyKind.Binary })
            addAll(module.transitiveFriendDependencies(reverseOrder = true) { it.kind == DependencyKind.Binary })
        }.distinct()
        for (dependencyModule in binaryLibraries) {
            val dependencyOutput = getProducedAssembly(dependencyModule, getArtifactName(dependencyModule))
            check(dependencyOutput.isFile) { "Missing compiled test dependency: ${dependencyOutput.path}" }
            configuration.addDotNetClasspathRoot(dependencyOutput)
        }
        configuration.dotNetFriendPaths = module.friendDependencies
            .filter { dependency -> dependency.kind == DependencyKind.Binary }
            .map { dependency ->
                val dependencyModule = dependency.dependencyModule
                getProducedAssembly(dependencyModule, getArtifactName(dependencyModule)).path
            }
        configuration.addSourcesForDependsOnClosure(module, testServices)
        if (producesStdlibFromSource) {
            configuration.languageVersionSettings =
                configuration.languageVersionSettings.withDotNetSourceProductSettings()
            for (stdlibSource in getOrCreateStdlibSources()) {
                configuration.addKotlinSourceRoot(
                    path = stdlibSource.canonicalPath,
                    isCommon = stdlibSource.name in DOTNET_STDLIB_COMMON_SOURCE_NAMES,
                )
            }
        } else {
            configuration.addDotNetClasspathRoot(getPrebuiltStdlib())
        }
    }

    private fun getArtifactName(module: TestModule): String {
        val testName = testServices.moduleStructure.originalTestDataFiles.first().nameWithoutExtension
        return module.name.takeUnless { it == "main" } ?: testName
    }

    private fun isLibraryModule(module: TestModule): Boolean =
        outputExtension != "il" &&
                module.files.none { file ->
                    MainFunctionForBlackBoxTestsSourceProvider.containsBoxMethod(file.originalContent)
                }

    private fun getConfiguredOutput(module: TestModule, artifactName: String): File {
        val outputDirectory = testServices.getOrCreateTempDirectory("dotnet")
        return if (isLibraryModule(module)) {
            outputDirectory.resolve("${module.name}-$artifactName-library")
        } else {
            outputDirectory.resolve("${module.name}-$artifactName.$outputExtension")
        }
    }

    private fun getProducedAssembly(module: TestModule, artifactName: String): File {
        val configuredOutput = getConfiguredOutput(module, artifactName)
        return if (isLibraryModule(module)) configuredOutput.resolve("$artifactName.dll") else configuredOutput
    }

    private fun getOrCreateStdlibSources(): List<File> =
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

    private fun getPrebuiltStdlib(): File {
        val propertyName = "kotlin.dotnet.test.platform.${target.description}.path"
        val directory = System.getProperty(propertyName)?.let(::File)
            ?: error("Missing reusable Kotlin/.NET test platform property '$propertyName'")
        val stdlib = directory.resolve(DotNetStdlibArtifact.ASSEMBLY_FILE_NAME)
        check(stdlib.isFile) { "Missing reusable Kotlin/.NET stdlib: ${stdlib.path}" }
        val runtime = directory.resolve(DotNetRuntimeArtifact.ASSEMBLY_FILE_NAME)
        check(runtime.isFile) { "Missing reusable Kotlin/.NET runtime: ${runtime.path}" }
        return stdlib
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
    val DOTNET_MEMBER_REFLECTION by directive(
        "Emit the pre-ABI executable producer metadata consumed by Kotlin.Reflection.dll"
    )
    val DOTNET_STDLIB_FROM_SOURCE by directive(
        "Compile the compiler-owned Kotlin/.NET stdlib source product in this test instead of consuming the reusable fixture"
    )
    val DOTNET_EXPORT by stringDirective(
        "Explicit CLR function export in <kotlin-selector>=<clr-method-name> form"
    )
    val DOTNET_EXPORT_PROPERTY by stringDirective(
        "Provisional CLR property export in <kotlin-fq-name>=<clr-property-name> form"
    )
}

private class DotNetIlTextHandler(testServices: TestServices) :
    AbstractDotNetIlTextHandler(testServices, validatesCrossAssemblerCompatibility = false)

private class DotNetCrossAssemblerIlTextHandler(testServices: TestServices) :
    AbstractDotNetIlTextHandler(testServices, validatesCrossAssemblerCompatibility = true)

private abstract class AbstractDotNetIlTextHandler(
    testServices: TestServices,
    private val validatesCrossAssemblerCompatibility: Boolean,
) : DotNetBinaryArtifactHandler(testServices) {
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
            if (validatesCrossAssemblerCompatibility && DotNetIlAssembler.findModernIlasm() != null) {
                add(DotNetIlasmValidation("modern", DotNetTarget.NET10_0))
            }
        }
        val requiredValidations = if (validatesCrossAssemblerCompatibility) 2 else 1
        if (dotNetToolchainIsRequired() && validations.size != requiredValidations) {
            val available = validations.joinToString { it.name }.ifEmpty { "none" }
            assertions.fail {
                val requirement = if (validatesCrossAssemblerCompatibility) {
                    "Both .NET Framework and modern ILAsm are required for the cross-assembler compatibility suite"
                } else {
                    ".NET Framework ILAsm is required to validate accepted net48 IL text"
                }
                "$requirement because KOTLIN_DOTNET_REQUIRE_TOOLCHAIN is enabled; available: $available"
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
        val sourceDirectory = testServices.temporaryDirectoryManager.getOrCreateTempDirectory("src")
        val file = sourceDirectory
            .resolve(MainFunctionForBlackBoxTestsSourceProvider.BOX_MAIN_FILE_NAME)
        file.writeText(code)

        return buildList {
            add(file.toTestFile())
            if (module.files.any { source -> "kotlin.test" in source.originalContent }) {
                val assertions = sourceDirectory.resolve("DotNetTestAssertions.kt")
                assertions.writeText(
                    """
                    package kotlin.test

                    public fun <T> assertEquals(expected: T, actual: T, message: String? = null) {
                        if (expected != actual) {
                            throw AssertionError(message ?: "Expected <${'$'}expected>, actual <${'$'}actual>.")
                        }
                    }

                    public fun <T> assertNotEquals(illegal: T, actual: T, message: String? = null) {
                        if (illegal == actual) {
                            throw AssertionError(message ?: "Illegal value: <${'$'}actual>.")
                        }
                    }

                    public fun assertTrue(actual: Boolean, message: String? = null) {
                        if (!actual) throw AssertionError(message ?: "Expected value to be true.")
                    }

                    public fun assertFalse(actual: Boolean, message: String? = null) {
                        if (actual) throw AssertionError(message ?: "Expected value to be false.")
                    }
                    """.trimIndent()
                )
                add(assertions.toTestFile())
            }
            if (module.files.any { source ->
                    "substringAfterLast(" in source.originalContent || ".endsWith(" in source.originalContent
                }
            ) {
                // Transitional test-only floor: these helpers let the unchanged upstream
                // reflection corpus assert names without making an unrelated String stdlib
                // expansion part of the KType feature. Product sources remain untouched.
                val textAssertions = sourceDirectory.resolve("DotNetTestTextAssertions.kt")
                textAssertions.writeText(
                    """
                    package kotlin.text

                    public fun String.substringAfterLast(
                        delimiter: Char,
                        missingDelimiterValue: String = this,
                    ): String {
                        var index = length - 1
                        while (index >= 0) {
                            if (this[index] == delimiter) {
                                val result = StringBuilder()
                                var resultIndex = index + 1
                                while (resultIndex < length) {
                                    result.append(this[resultIndex])
                                    resultIndex += 1
                                }
                                return result.toString()
                            }
                            index -= 1
                        }
                        return missingDelimiterValue
                    }

                    public fun String.endsWith(suffix: String, ignoreCase: Boolean = false): Boolean {
                        if (ignoreCase) throw UnsupportedOperationException("ignoreCase test helper is not implemented")
                        if (suffix.length > length) return false
                        val offset = length - suffix.length
                        var index = 0
                        while (index < suffix.length) {
                            if (this[offset + index] != suffix[index]) return false
                            index += 1
                        }
                        return true
                    }
                    """.trimIndent()
                )
                add(textAssertions.toTestFile())
            }
        }
    }
}

private class DotNetBoxRunner(testServices: TestServices) :
    AbstractDotNetBoxRunner(testServices, DotNetTarget.NET10_0)

private class DotNetFrameworkBoxRunner(testServices: TestServices) :
    AbstractDotNetBoxRunner(testServices, DotNetTarget.NET48)

private abstract class AbstractDotNetBoxRunner(
    private val dotNetTestServices: TestServices,
    private val target: DotNetTarget,
) : DotNetBinaryArtifactHandler(dotNetTestServices) {
    private var boxMethodFound = false

    override fun processModule(module: TestModule, info: BinaryArtifacts.DotNet) {
        if (module.files.none { MainFunctionForBlackBoxTestsSourceProvider.containsBoxMethod(it.originalContent) }) return

        boxMethodFound = true
        stageRuntimeDependencies(module, info.outputFile)
        val producesStdlibFromSource = DotNetCodegenDirectives.DOTNET_STDLIB_FROM_SOURCE in module.directives
        val result = runExecutable(info.outputFile, producesStdlibFromSource).trim()
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

    private fun stageRuntimeDependencies(module: TestModule, executable: File) {
        val outputDirectory = executable.parentFile ?: return
        val binaryLibraries = buildList {
            addAll(module.transitiveRegularDependencies(reverseOrder = true) { it.kind == DependencyKind.Binary })
            addAll(module.transitiveFriendDependencies(reverseOrder = true) { it.kind == DependencyKind.Binary })
        }.distinct()
        for (dependencyModule in binaryLibraries) {
            val artifactName = dependencyModule.name.takeUnless { it == "main" }
                ?: dotNetTestServices.moduleStructure.originalTestDataFiles.first().nameWithoutExtension
            val producedAssembly = outputDirectory
                .resolve("${dependencyModule.name}-$artifactName-library")
                .resolve("$artifactName.dll")
            check(producedAssembly.isFile) {
                "Compiled .NET test dependency was not produced: ${producedAssembly.path}"
            }
            val runtimeAssembly = outputDirectory.resolve("$artifactName.dll")
            if (producedAssembly.canonicalFile != runtimeAssembly.canonicalFile) {
                producedAssembly.copyTo(runtimeAssembly, overwrite = true)
            }
        }
    }

    private fun runExecutable(file: File, producesStdlibFromSource: Boolean): String {
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
        if (!producesStdlibFromSource && stdlibIlFile.exists()) {
            assertions.fail { "An ordinary .NET consumer rebuilt Kotlin.Stdlib from source: ${stdlibIlFile.path}" }
        }
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
        if (producesStdlibFromSource) {
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
