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
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerMemberFamilyRole
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerMemberPolicy
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerOverrideTargetKind
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalFamilyArtifact
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalFamilyCodec
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalFamilyRecord
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalDefaultDispatcherRecord
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalDirectSuperTargetRecord
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalMemberDispatch
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalMemberFamilyRecord
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalMemberSlotRecord
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalStateRecord
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPrototypeMemberSnapshot
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPrototypeSnapshot
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerSemanticHookReason
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerStateCarrierRequirement
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerWriteValueProvenance
import org.jetbrains.kotlin.backend.dotnet.resolveExternalPhysicalFamilies
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
        validateGenericOwnerHardestModelPrototype(completedOutput.genericOwnerPrototypes)
        physicalizeGenericOwnerHardestModelPrototype(
            completedOutput.genericOwnerPrototypes,
            loweredInput.configuration.dotNetTarget,
            testServices.getOrCreateTempDirectory("generic-owner-snapshot-physicalizer"),
        )
        check(completedOutput.output.isFile) {
            val messages = (input.configuration.messageCollector as? MessageCollectorForCompilerTests)
                ?.nonSourceMessages
                ?.joinToString("\n")
                .orEmpty()
            "The .NET backend produced no file at ${completedOutput.output.path}:\n$messages"
        }
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
        listOf("writeUnsafe", "read").forEach { memberName ->
            val member = consumer.members.single { candidate -> candidate.sourceName == memberName }
            check(member.overrideBindings.singleOrNull()?.let { binding ->
                binding.role == DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY &&
                        binding.targetKind == DotNetGenericOwnerOverrideTargetKind.EXTERNAL_LOGICAL_BINDING_REQUIRED &&
                        binding.overriddenLogicalBindingKey != null
            } == true) {
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
    check(read.requiresDirectSuperTargets)

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

private fun genericOwnerPrototypePhysicalMethodName(
    member: DotNetGenericOwnerPrototypeMemberSnapshot,
    role: DotNetGenericOwnerMemberFamilyRole,
): String {
    val typedName = when (member.sourceName) {
        "writeUnsafe" -> "WriteUnsafe"
        "read" -> "Read"
        "label" -> "Label"
        else -> error("The hostile physicalizer has no selected MethodDef name for '${member.sourceName}'")
    }
    return when (role) {
        DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY -> typedName
        DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK -> "${typedName}SemanticCore"
        DotNetGenericOwnerMemberFamilyRole.CAPABILITY_DISPATCHER -> when (member.sourceName) {
            "writeUnsafe" -> "WriteSemantic"
            "read" -> "ReadSemantic"
            else -> error("The hostile physicalizer has no capability slot for '${member.sourceName}'")
        }
    }
}

private fun createGenericOwnerPhysicalFamilyArtifact(
    prototypes: List<DotNetGenericOwnerPrototypeSnapshot>,
    producerFingerprint: String,
): DotNetGenericOwnerPhysicalFamilyArtifact {
    fun DotNetGenericOwnerPrototypeSnapshot.hasSimpleName(name: String): Boolean =
        ownerName == name || ownerName.endsWith(".$name")

    val membersByLogicalKey = prototypes.flatMap { prototype -> prototype.members }
        .mapNotNull { member -> member.logicalBindingKey?.let { key -> key to member } }
        .toMap()
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
        val members = prototype.members.mapNotNull { member ->
            val logicalMemberKey = member.logicalBindingKey ?: return@mapNotNull null
            DotNetGenericOwnerPhysicalMemberFamilyRecord(
                logicalMemberKey = logicalMemberKey,
                overrideRootLogicalMemberKeys = overrideRoots(member).sorted(),
                policy = member.policy,
                roles = member.roles,
                semanticHookReasons = member.semanticHookReasons,
                slots = member.roles.map { role ->
                    DotNetGenericOwnerPhysicalMemberSlotRecord(
                        role = role,
                        physicalMethodName = genericOwnerPrototypePhysicalMethodName(member, role),
                        dispatch = when {
                            role == DotNetGenericOwnerMemberFamilyRole.CAPABILITY_DISPATCHER ->
                                DotNetGenericOwnerPhysicalMemberDispatch.FINAL
                            member.isAbstract -> DotNetGenericOwnerPhysicalMemberDispatch.ABSTRACT
                            member.isOverridable -> DotNetGenericOwnerPhysicalMemberDispatch.OVERRIDABLE
                            else -> DotNetGenericOwnerPhysicalMemberDispatch.FINAL
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
                        )
                    }
                },
                defaultDispatcher = if (member.hasMaskedDefaultDispatcher) {
                    DotNetGenericOwnerPhysicalDefaultDispatcherRecord(
                        physicalOwnerPath = listOf("KotlinSnapshotPrototype", simpleName),
                        physicalMethodName = "${genericOwnerPrototypePhysicalMethodName(member, DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY)}Default",
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
            physicalOwnerPath = listOf("KotlinSnapshotPrototype", simpleName),
            physicalCapabilityOwnerPath = listOf(
                "KotlinSnapshotPrototype",
                "IHostileUnsafeStoreSemantic",
            ),
            genericArity = prototype.genericArity,
            disposition = prototype.disposition,
            members = members,
            states = prototype.states.map { state ->
                DotNetGenericOwnerPhysicalStateRecord(
                    logicalFieldName = state.fieldName,
                    physicalFieldName = state.fieldName,
                    requirement = state.requirement,
                )
            },
        )
    }
    return DotNetGenericOwnerPhysicalFamilyArtifact(producerFingerprint, owners)
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
    expectRejected("a duplicate logical owner") {
        DotNetGenericOwnerPhysicalFamilyArtifact(
            artifact.producerFingerprint,
            artifact.owners + artifact.owners.first(),
        )
    }
    val member = artifact.owners.first().members.first { candidate ->
        DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK in candidate.roles
    }
    expectRejected("an incomplete member role family") {
        member.copy(slots = member.slots.filterNot { slot ->
            slot.role == DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK
        })
    }
    expectRejected("an unsorted duplicate override-root family") {
        member.copy(overrideRootLogicalMemberKeys = listOf(member.logicalMemberKey, member.logicalMemberKey))
    }
    expectRejected("a capability dispatcher direct-super target") {
        DotNetGenericOwnerPhysicalDirectSuperTargetRecord(
            role = DotNetGenericOwnerMemberFamilyRole.CAPABILITY_DISPATCHER,
            logicalTargetMemberKey = member.logicalMemberKey,
            physicalOwnerPath = listOf("KotlinSnapshotPrototype", "HostileUnsafeStore"),
            physicalMethodName = "WriteSemantic",
        )
    }
    check(artifact.owners.flatMap { owner -> owner.members }.mapNotNull { candidate ->
        candidate.defaultDispatcher
    }.singleOrNull()?.physicalMethodName == "LabelDefault") {
        "The generic-owner artifact lacks the selected physical default dispatcher"
    }
}

/**
 * Test-owned physicalization of the compiler snapshot. This deliberately does not reuse the
 * production emitter: it turns the recorded role/state decisions into a temporary CLR-generic
 * producer, then proves that a separately compiled C# subclass observes those decisions.
 */
private fun physicalizeGenericOwnerHardestModelPrototype(
    prototypes: List<DotNetGenericOwnerPrototypeSnapshot>,
    target: DotNetTarget,
    directory: File,
) {
    fun DotNetGenericOwnerPrototypeSnapshot.hasSimpleName(name: String): Boolean =
        ownerName == name || ownerName.endsWith(".$name")

    prototypes.singleOrNull { prototype -> prototype.hasSimpleName("ConsumerUnsafeLeaf") }?.let { consumer ->
        consumeGenericOwnerPhysicalFamilyArtifact(consumer, target, directory)
        return
    }
    if (prototypes.none { prototype -> prototype.hasSimpleName("HostileUnsafeProducer") }) return
    val owner = prototypes.single { prototype -> prototype.hasSimpleName("HostileUnsafeStore") }
    val write = owner.members.single { member -> member.sourceName == "writeUnsafe" }
    val read = owner.members.single { member -> member.sourceName == "read" }
    val state = owner.states.single()
    fun hasRole(
        member: DotNetGenericOwnerPrototypeMemberSnapshot,
        role: DotNetGenericOwnerMemberFamilyRole,
    ): Boolean = role in member.roles

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
        public virtual void WriteUnsafe(T next)
        {
            stored = next;
        }
        """.trimIndent()
    } else ""
    val semanticWrite = if (hasRole(write, DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK)) {
        """
        protected virtual void WriteUnsafeSemanticCore(object next)
        {
            stored = next;
        }
        """.trimIndent()
    } else ""
    val writeDispatcher = if (hasRole(write, DotNetGenericOwnerMemberFamilyRole.CAPABILITY_DISPATCHER)) {
        """
        void IHostileUnsafeStoreSemantic.WriteSemantic(object next)
        {
            if (IsCompatible(next)) WriteUnsafe((T)next);
            else WriteUnsafeSemanticCore(next);
        }
        """.trimIndent()
    } else ""
    val typedRead = if (hasRole(read, DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY)) {
        """
        public virtual T Read()
        {
            return (T)stored;
        }
        """.trimIndent()
    } else ""
    val semanticRead = if (hasRole(read, DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK)) {
        """
        protected virtual object ReadSemanticCore()
        {
            return stored;
        }
        """.trimIndent()
    } else ""
    val readDispatcher = if (hasRole(read, DotNetGenericOwnerMemberFamilyRole.CAPABILITY_DISPATCHER)) {
        """
        object IHostileUnsafeStoreSemantic.ReadSemantic()
        {
            return ReadSemanticCore();
        }
        """.trimIndent()
    } else ""

    directory.mkdirs()
    val producerSource = directory.resolve("SnapshotProducer.cs").apply {
        writeText(
            """
            using System;

            namespace KotlinSnapshotPrototype
            {
                public interface IHostileUnsafeStoreSemantic
                {
                    object ReadSemantic();
                    void WriteSemantic(object next);
                }

                public class HostileUnsafeStore<T> : IHostileUnsafeStoreSemantic
                {
                    private $stateType stored;

                    public HostileUnsafeStore(T initial)
                    {
                        stored = initial;
                    }

                    public virtual string Label(string prefix)
                    {
                        return prefix;
                    }

                    public static string LabelDefault(HostileUnsafeStore<T> receiver, string prefix, int mask)
                    {
                        if ((mask & 1) != 0) prefix = "default";
                        return receiver.Label(prefix);
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
                }

                public class HostileUnsafeMid<T> : HostileUnsafeStore<T>
                {
                    public HostileUnsafeMid(T initial) : base(initial) {}

                    public override void WriteUnsafe(T next)
                    {
                        base.WriteUnsafe(next);
                    }

                    protected override void WriteUnsafeSemanticCore(object next)
                    {
                        base.WriteUnsafeSemanticCore(next);
                    }

                    public override T Read()
                    {
                        return base.Read();
                    }

                    protected override object ReadSemanticCore()
                    {
                        return base.ReadSemanticCore();
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

                public override void WriteUnsafe(int next)
                {
                    base.WriteUnsafe(next + 1);
                }
            }

            public sealed class SemanticWriteConsumer : HostileUnsafeStore<int>
            {
                public SemanticWriteConsumer() : base(1) {}

                protected override void WriteUnsafeSemanticCore(object next)
                {
                    base.WriteUnsafeSemanticCore("semantic:" + next);
                }
            }

            public sealed class PairedReadConsumer : HostileUnsafeStore<int>
            {
                public PairedReadConsumer() : base(1) {}

                public override int Read()
                {
                    return 43;
                }

                protected override object ReadSemanticCore()
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
                    semantic.WriteSemantic("wrong");
                    if (!object.Equals(semantic.ReadSemantic(), "wrong")) return 1;
                    try
                    {
                        value.Read();
                        return 2;
                    }
                    catch (InvalidCastException)
                    {
                    }
                    semantic.WriteSemantic(2);
                    if (value.Read() != 2) return 3;

                    TypedWriteConsumer typed = new TypedWriteConsumer();
                    ((IHostileUnsafeStoreSemantic)typed).WriteSemantic(4);
                    if (typed.Read() != 5) return 4;

                    SemanticWriteConsumer broad = new SemanticWriteConsumer();
                    IHostileUnsafeStoreSemantic broadSemantic = broad;
                    broadSemantic.WriteSemantic("wrong");
                    if (!object.Equals(broadSemantic.ReadSemantic(), "semantic:wrong")) return 5;

                    PairedReadConsumer paired = new PairedReadConsumer();
                    if (paired.Read() != 43 ||
                        !object.Equals(((IHostileUnsafeStoreSemantic)paired).ReadSemantic(), 43)) return 6;

                    Type definition = typeof(HostileUnsafeStore<>);
                    if (!definition.IsGenericTypeDefinition || definition.GetGenericArguments().Length != 1) return 7;
                    System.Reflection.FieldInfo[] fields = definition.GetFields(
                        System.Reflection.BindingFlags.Instance |
                        System.Reflection.BindingFlags.NonPublic |
                        System.Reflection.BindingFlags.DeclaredOnly
                    );
                    if (fields.Length != 1 || fields[0].FieldType != typeof(object)) return 8;
                    System.Reflection.InterfaceMapping map =
                        typeof(HostileUnsafeStore<int>).GetInterfaceMap(typeof(IHostileUnsafeStoreSemantic));
                    if (map.TargetMethods.Length != 2) return 9;
                    for (int index = 0; index < map.TargetMethods.Length; index++)
                    {
                        System.Reflection.MethodInfo method = map.TargetMethods[index];
                        if (!method.IsPrivate || !method.IsVirtual || !method.IsFinal) return 10;
                    }
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
        val artifact = createGenericOwnerPhysicalFamilyArtifact(prototypes, fingerprint)
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
    val artifact = DotNetGenericOwnerPhysicalFamilyCodec.decode(recordFile.readText(), fingerprint)
    val unresolvedKeys = consumer.members.flatMap { member -> member.overrideBindings }
        .filter { binding ->
            binding.targetKind == DotNetGenericOwnerOverrideTargetKind.EXTERNAL_LOGICAL_BINDING_REQUIRED
        }
        .map { binding -> checkNotNull(binding.overriddenLogicalBindingKey) }
        .toSet()
    check(unresolvedKeys.isNotEmpty()) {
        "ConsumerUnsafeLeaf must enter family resolution with external logical obligations"
    }
    val missingMemberArtifact = artifact.copy(
        owners = artifact.owners.map { owner ->
            owner.copy(members = owner.members.filterNot { member -> member.logicalMemberKey in unresolvedKeys })
        }
    )
    check(runCatching { consumer.resolveExternalPhysicalFamilies(missingMemberArtifact) }.isFailure) {
        "Generic-owner family resolution accepted a producer with missing logical members"
    }

    val resolved = consumer.resolveExternalPhysicalFamilies(artifact)
    check(resolved.disposition == DotNetGenericOwnerCandidateDisposition.REQUIRES_MEMBER_PHYSICALIZATION_PROOF) {
        "A completely bound external family must advance only to member physicalization proof: $resolved"
    }
    val write = resolved.members.single { member -> member.sourceName == "writeUnsafe" }
    val read = resolved.members.single { member -> member.sourceName == "read" }
    listOf(write, read).forEach { member ->
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
                    binding.overriddenPhysicalDispatch == DotNetGenericOwnerPhysicalMemberDispatch.OVERRIDABLE
        } && DotNetGenericOwnerSemanticHookReason.INHERITED_SEMANTIC_OVERRIDE in member.semanticHookReasons) {
            "${resolved.ownerName}.${member.sourceName} did not resolve a complete typed/semantic producer family: $member"
        }
    }

    fun DotNetGenericOwnerPrototypeMemberSnapshot.methodName(
        role: DotNetGenericOwnerMemberFamilyRole,
    ): String = checkNotNull(overrideBindings.single { binding -> binding.role == role }.overriddenPhysicalMethodName)

    val ownerRecord = artifact.owners.single { owner ->
        owner.members.any { member -> member.logicalMemberKey in unresolvedKeys }
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
    val baseTypeName = ownerRecord.physicalOwnerPath.joinToString(".")
    val capabilityTypeName = checkNotNull(ownerRecord.physicalCapabilityOwnerPath).joinToString(".")
    val defaultEntry = artifact.owners.flatMap { owner ->
        owner.members.mapNotNull { member -> member.defaultDispatcher?.let { owner to member } }
    }.single()
    val defaultOwner = defaultEntry.first
    val defaultMember = defaultEntry.second
    val defaultDispatcher = checkNotNull(defaultMember.defaultDispatcher)
    val defaultOwnerTypeName = defaultDispatcher.physicalOwnerPath.joinToString(".")
    val defaultTypedMethodName = defaultMember.slots.single { slot ->
        slot.role == DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY
    }.physicalMethodName
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
        }.physicalMethodName
    }

    val writeTyped = write.methodName(DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY)
    val writeSemantic = write.methodName(DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK)
    val readTyped = read.methodName(DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY)
    val readSemantic = read.methodName(DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK)
    val writeCapability = capabilityMethodName(write)
    val readCapability = capabilityMethodName(read)
    val source = directory.resolve("RecordedFamilyConsumer.cs").apply {
        writeText(
            """
            using System;

            public sealed class RecordedFamilyConsumer : $baseTypeName<int>
            {
                public RecordedFamilyConsumer() : base(1) {}

                public override void $writeTyped(int next)
                {
                    base.$writeTyped(next + 1);
                }

                protected override void $writeSemantic(object next)
                {
                    base.$writeSemantic("recorded:" + next);
                }

                public override int $readTyped()
                {
                    return base.$readTyped();
                }

                protected override object $readSemantic()
                {
                    return base.$readSemantic();
                }

                public override string $defaultTypedMethodName(string prefix)
                {
                    return "consumer:" + prefix;
                }
            }

            public static class RecordedFamilyEntry
            {
                public static int Main()
                {
                    RecordedFamilyConsumer value = new RecordedFamilyConsumer();
                    $capabilityTypeName semantic = value;
                    semantic.$writeCapability("wrong");
                    if (!object.Equals(semantic.$readCapability(), "recorded:wrong")) return 1;
                    try
                    {
                        value.$readTyped();
                        return 2;
                    }
                    catch (InvalidCastException)
                    {
                    }
                    semantic.$writeCapability(4);
                    if (value.$readTyped() != 5 || !object.Equals(semantic.$readCapability(), 5)) return 3;
                    if ($defaultOwnerTypeName<int>.${defaultDispatcher.physicalMethodName}(value, null, 1) !=
                        "consumer:default") return 4;
                    return 0;
                }
            }
            """.trimIndent()
        )
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
}

private data class SnapshotCSharpCompilation(
    val exitCode: Int,
    val output: String,
)

private fun compileFrameworkSnapshotCSharp(
    compiler: File,
    source: File,
    output: File,
    references: List<File>,
    executable: Boolean,
): SnapshotCSharpCompilation {
    output.delete()
    return runSnapshotCompiler(buildList {
        add(compiler.path)
        add("/nologo")
        add("/target:${if (executable) "exe" else "library"}")
        add("/out:${output.path}")
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
