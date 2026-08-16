/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet.lower

import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.ModuleLoweringPass
import org.jetbrains.kotlin.backend.common.defaultArgumentsOriginalFunction
import org.jetbrains.kotlin.backend.common.lower.at
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.backend.common.lower.SpecialBridgeMethods
import org.jetbrains.kotlin.backend.dotnet.DotNetBackendContext
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerArchitecturePlan
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerCandidateDisposition
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerConstructorArgumentMapping
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerConstructorPlan
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerCallReceiverProvenance
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerCallRoutePlan
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerCallRouteRequirement
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerDirectSuperCallPlan
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerMemberFamilyPlan
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerMemberFamilyRole
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerMemberAccessPlan
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerMemberPolicy
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerOverrideBindingPlan
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerOverrideTargetKind
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalSlotDomain
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPrototypeMember
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerSemanticHookReason
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerStateCarrierPlan
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerStateInitializerPlan
import org.jetbrains.kotlin.backend.dotnet.mergeDotNetGenericOwnerParameterSlotDomains
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerStateCarrierRequirement
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerStateWriteProvenancePlan
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPrototypeStateInitializerKind
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerWriteValueProvenance
import org.jetbrains.kotlin.backend.dotnet.dotNetLibraryAbiKeyOrNull
import org.jetbrains.kotlin.backend.dotnet.dotNetGenericOwnerCallRouteTraceHooks
import org.jetbrains.kotlin.backend.dotnet.isDotNetGenericClassDeclaration
import org.jetbrains.kotlin.backend.dotnet.isDotNetResolutionOnlyStdlibDeclaration
import org.jetbrains.kotlin.backend.dotnet.referencesTypeParameterOf
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.builders.irBlock
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irInt
import org.jetbrains.kotlin.ir.builders.irTemporary
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrAnonymousInitializer
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOriginImpl
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueDeclaration
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrContainerExpression
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrDelegatingConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrSetField
import org.jetbrains.kotlin.ir.expressions.IrSetValue
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrGetField
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrFunctionAccessExpression
import org.jetbrains.kotlin.ir.expressions.IrExpressionBody
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.expressions.IrWhen
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.IrTypeSubstitutor
import org.jetbrains.kotlin.ir.types.SimpleTypeNullability
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.isInt
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.util.copyTo
import org.jetbrains.kotlin.ir.util.copyTypeParametersFrom
import org.jetbrains.kotlin.ir.util.createDispatchReceiverParameterWithClassParent
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.resolveFakeOverride
import org.jetbrains.kotlin.ir.util.resolveFakeOverrideMaybeAbstract
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.types.Variance
import org.jetbrains.kotlin.name.Name
import java.util.Collections
import java.util.IdentityHashMap

private val DOTNET_GENERIC_OWNER_PROTOTYPE_MEMBER: IrDeclarationOrigin =
    IrDeclarationOriginImpl("DOTNET_GENERIC_OWNER_PROTOTYPE_MEMBER")

/**
 * Records conservative proof obligations for the eventual CLR-generic Kotlin class-owner ABI.
 *
 * This pass is intentionally adjacent to, but independent from, erased generic-interface bridge
 * construction. It normally changes no IR and grants no class permission to use a reified
 * physical owner. An architecture test may explicitly supply one module-local trace recorder;
 * that separate instrumented product preserves evaluation order and records the already-derived
 * call-site index, but is never a production or performance artifact. Keeping the analysis in the
 * production pipeline makes it run over every supported semantic corpus without allowing a
 * partial ABI switch.
 */
internal class DotNetGenericOwnerArchitecturePlanningLowering(
    private val context: DotNetBackendContext,
) : ModuleLoweringPass {
    private val specialBridgeMethods = SpecialBridgeMethods(context)

    override fun lower(irModule: IrModuleFragment) {
        check(context.genericOwnerArchitecturePlans.isEmpty()) {
            "Internal .NET backend error: generic-owner architecture planning ran more than once"
        }
        check(context.genericOwnerCallRoutes.isEmpty()) {
            "Internal .NET backend error: generic-owner call-route planning ran more than once"
        }

        val owners = mutableListOf<IrClass>()
        val producerFunctions = linkedSetOf<IrFunction>()
        val producerInitializers = mutableListOf<ProducerInitializer>()
        irModule.acceptVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitClass(declaration: IrClass) {
                if (declaration.origin == IrDeclarationOrigin.IR_EXTERNAL_DECLARATION_STUB) return
                if (!declaration.isDotNetResolutionOnlyStdlibDeclaration &&
                    declaration.isDotNetGenericClassDeclaration
                ) {
                    owners += declaration
                }
                declaration.acceptChildrenVoid(this)
            }

            override fun visitFunction(declaration: IrFunction) {
                if (declaration.origin == IrDeclarationOrigin.IR_EXTERNAL_DECLARATION_STUB) return
                producerFunctions += declaration
                declaration.acceptChildrenVoid(this)
            }

            override fun visitAnonymousInitializer(declaration: IrAnonymousInitializer) {
                producerInitializers += ProducerInitializer(
                    label = "<initializer:${declaration.startOffset}>",
                    element = declaration.body,
                )
                declaration.acceptChildrenVoid(this)
            }

            override fun visitField(declaration: IrField) {
                declaration.initializer?.let { initializer ->
                    producerInitializers += ProducerInitializer(
                        label = "<field-initializer:${declaration.name.asString()}>",
                        element = initializer,
                        implicitWrite = declaration,
                    )
                }
                declaration.acceptChildrenVoid(this)
            }
        })
        val producerAccesses = producerFunctions.associateWithTo(linkedMapOf()) { function ->
            function.collectDirectAccesses(producerFunctions)
        }
        val initializerAccesses = producerInitializers.associateWithTo(linkedMapOf()) { initializer ->
            initializer.element.collectDirectAccesses(producerFunctions).withImplicitWrite(initializer)
        }

        for (owner in owners) {
            context.genericOwnerArchitecturePlans[owner] = plan(owner, producerAccesses, initializerAccesses)
        }
        linkDetachedOverrideFamilies()
        val callRoutes = GenericOwnerCallRouteAnalyzer(
            producerFunctions = producerFunctions,
            producerInitializers = producerInitializers,
            producerAccesses = producerAccesses,
            initializerAccesses = initializerAccesses,
        ).analyze()
        context.genericOwnerCallRoutes += callRoutes
        context.configuration.dotNetGenericOwnerCallRouteTraceHooks?.let { hooks ->
            instrumentCallRoutes(irModule, callRoutes, hooks.recorder)
        }
    }

    private fun instrumentCallRoutes(
        irModule: IrModuleFragment,
        callRoutes: List<DotNetGenericOwnerCallRoutePlan>,
        recorder: IrSimpleFunction,
    ) {
        check(recorder.parent in irModule.files &&
                DescriptorVisibilities.isPrivate(recorder.visibility) &&
                recorder.typeParameters.isEmpty() && !recorder.isSuspend && recorder.body != null &&
                recorder.parameters.singleOrNull()?.let { parameter ->
                    parameter.kind == IrParameterKind.Regular && parameter.type.isInt()
                } == true && recorder.returnType.isUnit()) {
            "Generic-owner route tracing requires one private module-local (Int) -> Unit recorder"
        }
        val indexByCall = IdentityHashMap<IrCall, Int>()
        callRoutes.forEach { route ->
            check(indexByCall.put(route.call, route.callSiteIndex) == null) {
                "Generic-owner route tracing encountered one call under multiple site indices"
            }
        }
        val remainingCalls = Collections.newSetFromMap(IdentityHashMap<IrCall, Boolean>()).apply {
            addAll(indexByCall.keys)
        }
        irModule.transformChildrenVoid(object : IrElementTransformerVoidWithContext() {
            override fun visitCall(expression: IrCall): IrExpression {
                expression.transformChildrenVoid(this)
                val callSiteIndex = indexByCall[expression] ?: return expression
                check(remainingCalls.remove(expression)) {
                    "Generic-owner route tracing visited one call site more than once"
                }
                val builder = context.createIrBuilder(currentScope!!.scope.scopeOwnerSymbol).at(expression)
                return builder.irBlock(resultType = expression.type) {
                    expression.arguments.indices.forEach { argumentIndex ->
                        val argument = expression.arguments[argumentIndex] ?: return@forEach
                        val temporary = irTemporary(argument, nameHint = "<genericOwnerRouteArgument>")
                        expression.arguments[argumentIndex] = irGet(temporary)
                    }
                    +irCall(recorder).apply {
                        arguments[0] = irInt(callSiteIndex)
                    }
                    +expression
                }
            }
        })
        check(remainingCalls.isEmpty()) {
            "Generic-owner route tracing did not instrument ${remainingCalls.size} analyzed call sites"
        }
    }

    private fun plan(
        owner: IrClass,
        producerAccesses: Map<IrFunction, DirectMemberAccesses>,
        producerInitializerAccesses: Map<ProducerInitializer, DirectMemberAccesses>,
    ): DotNetGenericOwnerArchitecturePlan {
        val members = owner.directSimpleFunctions()
        val constructors = owner.declarations.filterIsInstance<IrConstructor>().map { constructor ->
            var delegatedConstructor: IrConstructor? = null
            var delegationArgumentMapping = DotNetGenericOwnerConstructorArgumentMapping.UNSUPPORTED
            constructor.body?.acceptVoid(object : IrVisitorVoid() {
                override fun visitElement(element: IrElement) {
                    element.acceptChildrenVoid(this)
                }

                override fun visitClass(declaration: IrClass) = Unit

                override fun visitFunction(declaration: IrFunction) = Unit

                override fun visitDelegatingConstructorCall(expression: IrDelegatingConstructorCall) {
                    check(delegatedConstructor == null) {
                        "Internal .NET backend error: generic-owner constructor has multiple delegation calls"
                    }
                    delegatedConstructor = expression.symbol.owner
                    delegationArgumentMapping = if (expression.arguments.size == constructor.parameters.size &&
                        expression.arguments.indices.all { index ->
                            (expression.arguments[index] as? IrGetValue)?.symbol == constructor.parameters[index].symbol
                        }
                    ) {
                        DotNetGenericOwnerConstructorArgumentMapping.POSITIONAL_IDENTITY
                    } else {
                        DotNetGenericOwnerConstructorArgumentMapping.UNSUPPORTED
                    }
                }
            })
            val delegated = delegatedConstructor
            val delegatedOwner = delegated?.parent as? IrClass
            DotNetGenericOwnerConstructorPlan(
                source = constructor,
                logicalBindingKey = context.preLoweringDeclarationKeys[constructor]
                    ?: constructor.dotNetLibraryAbiKeyOrNull("F"),
                parameterSlotDomains = constructor.parameters.map { parameter ->
                    if (parameter.type.referencesTypeParameterOf(owner)) {
                        DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_INPUT
                    } else {
                        DotNetGenericOwnerPhysicalSlotDomain.DECLARATION_INDEPENDENT
                    }
                },
                delegationArgumentMapping = delegationArgumentMapping,
                delegatedConstructorLogicalBindingKey = delegated?.let { target ->
                    context.preLoweringDeclarationKeys[target] ?: target.dotNetLibraryAbiKeyOrNull("F")
                },
                delegatedOwnerName = delegatedOwner?.fqNameWhenAvailable?.asString()
                    ?: delegatedOwner?.name?.asString(),
                delegatesToThis = delegatedOwner == owner,
            )
        }
        val fields = owner.directFields()
        val ownerDependentFields = fields.filterTo(linkedSetOf()) { field ->
            field.type.referencesTypeParameterOf(owner)
        }
        val memberPolicies = members.associateWithTo(linkedMapOf()) { member ->
            member.policyFor(owner)
        }
        val conditionalSupertypes = owner.superTypes.filter { superType ->
            superType.hasExplicitNullableParameterOf(owner)
        }
        val directAccesses = producerAccesses.mapValuesTo(linkedMapOf()) { entry ->
            entry.value.restrictTo(ownerDependentFields)
        }
        val initializerAccesses = producerInitializerAccesses.mapValuesTo(linkedMapOf()) { entry ->
            entry.value.restrictTo(ownerDependentFields)
        }
        val semanticEntries = memberPolicies
            .filterValues { policy -> policy == DotNetGenericOwnerMemberPolicy.SEMANTIC_BODY }
            .keys
        val semanticReachableMembers = semanticEntries
            .flatMapTo(linkedSetOf<IrFunction>()) { member -> member.transitiveCalls(directAccesses) + member }
        val memberAccesses = members.associateWithTo(linkedMapOf()) { member ->
            val transitiveCalls = member.transitiveCalls(directAccesses)
            val reachableBodies = transitiveCalls + member
            DotNetGenericOwnerMemberAccessPlan(
                source = member,
                directCalls = directAccesses.getValue(member).calls,
                transitiveCalls = transitiveCalls,
                directReads = directAccesses.getValue(member).reads,
                directWrites = directAccesses.getValue(member).writes,
                transitiveReads = reachableBodies.flatMapTo(linkedSetOf()) { reachable ->
                    directAccesses.getValue(reachable).reads
                },
                transitiveWrites = reachableBodies.flatMapTo(linkedSetOf()) { reachable ->
                    directAccesses.getValue(reachable).writes
                },
                reachableFromSemanticEntry = member in semanticReachableMembers,
            )
        }
        val directSemanticWriteFields = semanticEntries.flatMapTo(linkedSetOf()) { member ->
            directAccesses.getValue(member).writes
        }
        val semanticReachableWriteFields = semanticReachableMembers.flatMapTo(linkedSetOf()) { member ->
            directAccesses.getValue(member).writes
        }
        val writeValueProvenances = TypedWriteValueProvenanceAnalyzer(
            owner = owner,
            members = members,
            memberPolicies = memberPolicies,
            producerAccesses = directAccesses,
            initializerAccesses = initializerAccesses,
        ).analyze(ownerDependentFields)
        val semanticValueWriteFields = writeValueProvenances
            .filterValues { writes ->
                writes.any { write ->
                    write.provenance == DotNetGenericOwnerWriteValueProvenance.SEMANTIC_OBJECT
                }
            }
            .keys
        val semanticStateWriteFields = semanticReachableWriteFields + semanticValueWriteFields
        val openOutputs = if (owner.modality == Modality.FINAL) {
            emptySet()
        } else {
            members.filterTo(linkedSetOf()) { member ->
                member.modality != Modality.FINAL && member.returnType.referencesTypeParameterOf(owner)
            }
        }
        val memberFamilies = members.associateWithTo(linkedMapOf()) { member ->
            val directSuperCalls = mutableListOf<DotNetGenericOwnerDirectSuperCallPlan>()
            member.body?.acceptVoid(object : IrVisitorVoid() {
                override fun visitElement(element: IrElement) {
                    element.acceptChildrenVoid(this)
                }

                override fun visitCall(expression: IrCall) {
                    expression.superQualifierSymbol?.let { superQualifier ->
                        directSuperCalls += DotNetGenericOwnerDirectSuperCallPlan(
                            target = expression.symbol.owner,
                            superQualifier = superQualifier.owner,
                        )
                    }
                    expression.acceptChildrenVoid(this)
                }
            })
            val ownerDependentInputs = member.parameters.withIndex().mapNotNull { indexedParameter ->
                val parameter = indexedParameter.value
                indexedParameter.index.takeIf {
                    parameter.kind != IrParameterKind.DispatchReceiver &&
                            parameter.type.referencesTypeParameterOf(owner)
                }
            }
            val hasOwnerDependentOutput = member.returnType.referencesTypeParameterOf(owner)
            val explicitParameters = member.parameters.filter { parameter ->
                parameter.kind != IrParameterKind.DispatchReceiver
            }
            val specialArgumentsToCheck = specialBridgeMethods
                .findSpecialWithOverride(member, includeSelf = true)
                ?.second
                ?.argumentsToCheck
                ?: 0
            val parameterSlotDomains = explicitParameters.mapIndexed { index, parameter ->
                when {
                    !parameter.type.referencesTypeParameterOf(owner) ->
                        DotNetGenericOwnerPhysicalSlotDomain.DECLARATION_INDEPENDENT
                    !DescriptorVisibilities.isPrivate(member.visibility) &&
                            (index < specialArgumentsToCheck ||
                                    !parameter.type.isLegalAtOwnerVariance(owner, TypePolarity.IN)) ->
                        DotNetGenericOwnerPhysicalSlotDomain.BROAD_CANDIDATE_INPUT
                    else -> DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_INPUT
                }
            }
            val returnSlotDomain = if (hasOwnerDependentOutput) {
                DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_OUTPUT
            } else {
                DotNetGenericOwnerPhysicalSlotDomain.DECLARATION_INDEPENDENT
            }
            val semanticHookReasons = buildSet {
                if (memberPolicies.getValue(member) == DotNetGenericOwnerMemberPolicy.SEMANTIC_BODY) {
                    add(DotNetGenericOwnerSemanticHookReason.GENERAL_WIDENED_BODY)
                }
                if (semanticStateWriteFields.isNotEmpty() && member in openOutputs) {
                    add(DotNetGenericOwnerSemanticHookReason.PAIRED_OPEN_OUTPUT_STATE)
                }
            }
            val roles = buildSet {
                add(DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY)
                if (ownerDependentInputs.isNotEmpty() || hasOwnerDependentOutput) {
                    add(DotNetGenericOwnerMemberFamilyRole.CAPABILITY_DISPATCHER)
                }
                if (semanticHookReasons.isNotEmpty()) {
                    add(DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK)
                    add(DotNetGenericOwnerMemberFamilyRole.CAPABILITY_DISPATCHER)
                }
            }
            DotNetGenericOwnerMemberFamilyPlan(
                source = member,
                policy = memberPolicies.getValue(member),
                ownerDependentInputIndices = ownerDependentInputs,
                hasOwnerDependentOutput = hasOwnerDependentOutput,
                returnSlotDomain = returnSlotDomain,
                parameterSlotDomains = parameterSlotDomains,
                roles = roles,
                semanticHookReasons = semanticHookReasons,
                requiresDirectSuperTargets = member.modality != Modality.FINAL,
                directSuperCallCount = directSuperCalls.size,
                directSuperCalls = directSuperCalls,
                maskedDefaultDispatcher = context.defaultArgumentDispatchers[member],
                logicalBindingKey = context.preLoweringDeclarationKeys[member],
            )
        }
        val stateCarriers = ownerDependentFields
            .associateWithTo(linkedMapOf()) { field ->
                val directReaders = directAccesses
                    .filterTo(linkedMapOf()) { entry -> field in entry.value.reads }
                    .keys
                val directWriters = directAccesses
                    .filterTo(linkedMapOf()) { entry -> field in entry.value.writes }
                    .keys
                val semanticReachableReaders = directReaders.filterTo(linkedSetOf()) { member ->
                    member in semanticReachableMembers
                }
                val semanticReachableWriters = directWriters.filterTo(linkedSetOf()) { member ->
                    member in semanticReachableMembers
                }
                val initializationReaders = initializerAccesses
                    .filter { entry ->
                        field in entry.value.reads || entry.value.calls.any { call ->
                            field in call.transitiveFieldReads(directAccesses)
                        }
                    }
                    .keys
                    .mapTo(linkedSetOf()) { initializer -> initializer.label }
                val initializationWriters = initializerAccesses
                    .filter { entry ->
                        field in entry.value.writes || entry.value.calls.any { call ->
                            field in call.transitiveFieldWrites(directAccesses)
                        }
                    }
                    .keys
                    .mapTo(linkedSetOf()) { initializer -> initializer.label }
                val writes = writeValueProvenances.getValue(field)
                val hasOnlyProvenTypedWrites = writes.isNotEmpty() && writes.all { write ->
                    write.provenance == DotNetGenericOwnerWriteValueProvenance.PHYSICALLY_TYPED
                }
                val externalAccessGraphRequired = !DescriptorVisibilities.isPrivate(field.visibility)
                DotNetGenericOwnerStateCarrierPlan(
                    field = field,
                    requirement = when {
                        semanticReachableWriters.isNotEmpty() || field in semanticValueWriteFields ->
                            DotNetGenericOwnerStateCarrierRequirement.SEMANTIC_OBJECT_REQUIRED
                        externalAccessGraphRequired ->
                            DotNetGenericOwnerStateCarrierRequirement.COMPLETE_ACCESS_GRAPH_REQUIRED
                        hasOnlyProvenTypedWrites ->
                            DotNetGenericOwnerStateCarrierRequirement.TYPED_STORAGE_PRODUCER_GRAPH_PROVEN
                        else ->
                            DotNetGenericOwnerStateCarrierRequirement.TYPED_WRITE_VALUE_PROVENANCE_REQUIRED
                    },
                    initializers = producerInitializerAccesses.keys.mapNotNull { initializer ->
                        initializer.stateInitializerPlanOrNull(field, owner)
                    },
                    writes = writes,
                    directReaders = directReaders,
                    directWriters = directWriters,
                    semanticReachableReaders = semanticReachableReaders,
                    semanticReachableWriters = semanticReachableWriters,
                    initializationReaderLabels = initializationReaders,
                    initializationWriterLabels = initializationWriters,
                    externalAccessGraphRequired = externalAccessGraphRequired,
                )
            }
        val disposition = when {
            conditionalSupertypes.isNotEmpty() ->
                DotNetGenericOwnerCandidateDisposition.BLOCKED_METADATA_FIXED_CONDITIONAL_SUPERTYPE
            semanticStateWriteFields.isNotEmpty() && openOutputs.isNotEmpty() ->
                DotNetGenericOwnerCandidateDisposition.BLOCKED_OPEN_OUTPUT_STATE_COHERENCE
            semanticStateWriteFields.isNotEmpty() ->
                DotNetGenericOwnerCandidateDisposition.REQUIRES_SEMANTIC_STATE_PROOF
            stateCarriers.values.any { state ->
                state.requirement == DotNetGenericOwnerStateCarrierRequirement.COMPLETE_ACCESS_GRAPH_REQUIRED
            } ->
                DotNetGenericOwnerCandidateDisposition.REQUIRES_COMPLETE_FIELD_ACCESS_GRAPH
            stateCarriers.values.any { state ->
                state.requirement == DotNetGenericOwnerStateCarrierRequirement.TYPED_WRITE_VALUE_PROVENANCE_REQUIRED
            } ->
                DotNetGenericOwnerCandidateDisposition.REQUIRES_TYPED_WRITE_VALUE_PROVENANCE
            else ->
                DotNetGenericOwnerCandidateDisposition.REQUIRES_MEMBER_PHYSICALIZATION_PROOF
        }
        check(memberFamilies.keys == memberPolicies.keys) {
            "Internal .NET backend error: generic-owner member-family planning is incomplete"
        }
        check(memberAccesses.keys == memberPolicies.keys) {
            "Internal .NET backend error: generic-owner field/call planning is incomplete"
        }
        check(semanticStateWriteFields.all { field ->
            stateCarriers[field]?.requirement == DotNetGenericOwnerStateCarrierRequirement.SEMANTIC_OBJECT_REQUIRED
        }) {
            "Internal .NET backend error: a widened semantic state write retained typed-only storage"
        }
        if (disposition == DotNetGenericOwnerCandidateDisposition.BLOCKED_OPEN_OUTPUT_STATE_COHERENCE) {
            check(openOutputs.all { output ->
                DotNetGenericOwnerSemanticHookReason.PAIRED_OPEN_OUTPUT_STATE in
                        memberFamilies.getValue(output).semanticHookReasons
            }) {
                "Internal .NET backend error: open output/state coherence lacks a paired semantic family"
            }
        }
        val prototypeMembers = memberFamilies.entries.mapIndexed { memberIndex, entry ->
            val source = entry.key
            val family = entry.value
            source to family.roles.associateWithTo(linkedMapOf()) { role ->
                createDetachedPrototypeMember(owner, source, role, memberIndex)
            }
        }.toMap(linkedMapOf())
        check(owner.declarations.none { declaration -> declaration.origin == DOTNET_GENERIC_OWNER_PROTOTYPE_MEMBER }) {
            "Internal .NET backend error: a generic-owner prototype member entered production IR"
        }
        check(prototypeMembers.all { entry ->
            val source = entry.key
            val roles = entry.value
            roles.keys == memberFamilies.getValue(source).roles &&
                    roles.values.all { prototype: DotNetGenericOwnerPrototypeMember ->
                        prototype.function !in owner.declarations
                    }
        }) {
            "Internal .NET backend error: generic-owner detached prototype family is incomplete"
        }
        return DotNetGenericOwnerArchitecturePlan(
            owner = owner,
            logicalBindingKey = context.preLoweringDeclarationKeys[owner],
            disposition = disposition,
            constructors = constructors,
            memberPolicies = memberPolicies,
            memberFamilies = memberFamilies,
            memberAccesses = memberAccesses,
            prototypeMembers = prototypeMembers,
            metadataFixedConditionalSupertypes = conditionalSupertypes,
            directSemanticWriteFields = directSemanticWriteFields,
            semanticReachableWriteFields = semanticReachableWriteFields,
            semanticValueWriteFields = semanticValueWriteFields,
            overrideBindings = emptyMap(),
            stateCarriers = stateCarriers,
            openOwnerOutputs = openOutputs,
        )
    }

    /**
     * Connects detached member families across Kotlin-produced generic subclasses after every
     * local owner has been planned. Typed entries override typed entries. A semantic hook is
     * inherited as a family obligation and overrides only the ancestor semantic hook; private
     * capability dispatchers remain final selectors and never form an override chain.
     *
     * An external generic base has no detached prototype in this compilation. Record its stable
     * logical member key as an explicit future binding-schema requirement instead of guessing a
     * physical MethodDef. This keeps separate compilation fail-closed while the production ABI is
     * still erased and no prototype schema is serialized.
     */
    private fun linkDetachedOverrideFamilies() {
        val plans = context.genericOwnerArchitecturePlans
        var changed: Boolean
        do {
            changed = false
            plans.entries.toList().forEach { planEntry ->
                val owner = planEntry.key
                val plan = plans.getValue(owner)
                val families = plan.memberFamilies.toMutableMap()
                plan.memberFamilies.forEach { familyEntry ->
                    val source = familyEntry.key
                    if (source.isFakeOverride) return@forEach
                    val overriddenFamilies = source.overriddenSymbols.mapNotNull { overriddenSymbol ->
                        val overridden = overriddenSymbol.owner
                        val overriddenOwner = overridden.parent as? IrClass ?: return@mapNotNull null
                        plans[overriddenOwner]?.memberFamilies?.get(overridden)
                    }
                    val family = families.getValue(source)
                    val inheritsSemanticHook = overriddenFamilies.any { overriddenFamily ->
                        DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK in overriddenFamily.roles
                    }
                    val mergedParameterSlotDomains = overriddenFamilies.fold(family.parameterSlotDomains) {
                            domains, overriddenFamily ->
                        mergeDotNetGenericOwnerParameterSlotDomains(
                            domains,
                            overriddenFamily.parameterSlotDomains,
                        )
                    }
                    val updatedFamily = family.copy(
                        roles = if (inheritsSemanticHook) {
                            family.roles + setOf(
                                DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK,
                                DotNetGenericOwnerMemberFamilyRole.CAPABILITY_DISPATCHER,
                            )
                        } else {
                            family.roles
                        },
                        semanticHookReasons = if (inheritsSemanticHook) {
                            family.semanticHookReasons +
                                    DotNetGenericOwnerSemanticHookReason.INHERITED_SEMANTIC_OVERRIDE
                        } else {
                            family.semanticHookReasons
                        },
                        parameterSlotDomains = mergedParameterSlotDomains,
                    )
                    if (updatedFamily != family) {
                        families[source] = updatedFamily
                        changed = true
                    }
                }
                if (families != plan.memberFamilies) {
                    plans[owner] = plan.copy(memberFamilies = families)
                }
            }
        } while (changed)

        plans.entries.toList().forEach { planEntry ->
            val owner = planEntry.key
            val plan = plans.getValue(owner)
            val prototypes = plan.prototypeMembers.mapValuesTo(linkedMapOf()) { entry ->
                entry.value.toMutableMap()
            }
            plan.memberFamilies.entries.forEachIndexed { memberIndex, familyEntry ->
                val source = familyEntry.key
                val family = familyEntry.value
                val roles = prototypes.getOrPut(source) { linkedMapOf() }
                family.roles.forEach { role ->
                    roles.getOrPut(role) {
                        createDetachedPrototypeMember(owner, source, role, memberIndex)
                    }
                }
            }
            plans[owner] = plan.copy(prototypeMembers = prototypes)
        }

        plans.entries.toList().forEach { planEntry ->
            val owner = planEntry.key
            val plan = plans.getValue(owner)
            val bindings = linkedMapOf<IrSimpleFunction, MutableList<DotNetGenericOwnerOverrideBindingPlan>>()
            plan.memberFamilies.forEach { familyEntry ->
                val source = familyEntry.key
                if (source.isFakeOverride) return@forEach
                val family = familyEntry.value
                source.overriddenSymbols.forEach { overriddenSymbol ->
                    val overridden = overriddenSymbol.owner
                    val overriddenOwner = overridden.parent as? IrClass ?: return@forEach
                    if (!overriddenOwner.isDotNetGenericClassDeclaration) return@forEach
                    val overriddenPlan = plans[overriddenOwner]
                    val overriddenFamily = overriddenPlan?.memberFamilies?.get(overridden)
                    if (overriddenPlan != null && overriddenFamily != null) {
                        listOf(
                            DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY,
                            DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK,
                        ).forEach { role ->
                            if (role !in family.roles || role !in overriddenFamily.roles) return@forEach
                            val sourcePrototype = checkNotNull(plan.prototypeMembers[source]?.get(role))
                            val overriddenPrototype = checkNotNull(overriddenPlan.prototypeMembers[overridden]?.get(role))
                            sourcePrototype.function.overriddenSymbols =
                                (sourcePrototype.function.overriddenSymbols + overriddenPrototype.function.symbol).distinct()
                            bindings.getOrPut(source) { mutableListOf() } += DotNetGenericOwnerOverrideBindingPlan(
                                source = source,
                                role = role,
                                overriddenSource = overridden,
                                targetKind = DotNetGenericOwnerOverrideTargetKind.LOCAL_DETACHED_PROTOTYPE,
                                overriddenLogicalBindingKey = overriddenFamily.logicalBindingKey,
                            )
                        }
                    } else {
                        val declaringOverride = if (overridden.isFakeOverride) {
                            overridden.resolveFakeOverride()
                                ?: overridden.resolveFakeOverrideMaybeAbstract()
                                ?: error("generic-owner external fake override has no declaring Kotlin root")
                        } else {
                            overridden
                        }
                        bindings.getOrPut(source) { mutableListOf() } += DotNetGenericOwnerOverrideBindingPlan(
                            source = source,
                            role = DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY,
                            overriddenSource = declaringOverride,
                            targetKind = DotNetGenericOwnerOverrideTargetKind.EXTERNAL_LOGICAL_BINDING_REQUIRED,
                            overriddenLogicalBindingKey = declaringOverride.dotNetLibraryAbiKeyOrNull("F"),
                        )
                    }
                }
            }
            check(bindings.values.flatten().none { binding ->
                binding.role == DotNetGenericOwnerMemberFamilyRole.CAPABILITY_DISPATCHER
            }) {
                "Internal .NET backend error: a private generic-owner capability dispatcher entered an override chain"
            }
            val needsExternalBindingSchema = bindings.values.flatten().any { binding ->
                binding.targetKind == DotNetGenericOwnerOverrideTargetKind.EXTERNAL_LOGICAL_BINDING_REQUIRED
            }
            plans[owner] = plan.copy(
                disposition = if (needsExternalBindingSchema &&
                    plan.disposition == DotNetGenericOwnerCandidateDisposition.REQUIRES_MEMBER_PHYSICALIZATION_PROOF
                ) {
                    DotNetGenericOwnerCandidateDisposition.REQUIRES_EXTERNAL_OVERRIDE_BINDING_SCHEMA
                } else {
                    plan.disposition
                },
                overrideBindings = bindings,
            )
        }
    }

    private fun createDetachedPrototypeMember(
        owner: IrClass,
        source: IrSimpleFunction,
        role: DotNetGenericOwnerMemberFamilyRole,
        memberIndex: Int,
    ): DotNetGenericOwnerPrototypeMember {
        val prototype = context.irFactory.buildFun {
            startOffset = source.startOffset
            endOffset = source.endOffset
            origin = DOTNET_GENERIC_OWNER_PROTOTYPE_MEMBER
            name = Name.special("<GenericOwnerPrototype-${role.name}-$memberIndex>")
            visibility = when (role) {
                DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY -> source.visibility
                DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK -> DescriptorVisibilities.PROTECTED
                DotNetGenericOwnerMemberFamilyRole.CAPABILITY_DISPATCHER -> DescriptorVisibilities.PRIVATE
            }
            modality = when (role) {
                DotNetGenericOwnerMemberFamilyRole.CAPABILITY_DISPATCHER -> Modality.FINAL
                else -> source.modality
            }
            returnType = context.irBuiltIns.anyNType
        }
        prototype.parent = owner
        prototype.parameters += prototype.createDispatchReceiverParameterWithClassParent()
        val copiedMethodParameters = prototype.copyTypeParametersFrom(source)
        val ownerErasure = IrTypeSubstitutor(
            owner.typeParameters.associate { parameter -> parameter.symbol to context.irBuiltIns.anyNType },
            allowEmptySubstitution = true,
        )
        if (role != DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY) {
            copiedMethodParameters.forEach { parameter ->
                parameter.superTypes = parameter.superTypes.map(ownerErasure::substitute)
            }
        }
        val methodSubstitution = source.typeParameters.zip(copiedMethodParameters).associate { pair ->
            pair.first.symbol to pair.second.symbol.defaultType
        }
        val methodSubstitutor = IrTypeSubstitutor(methodSubstitution, allowEmptySubstitution = true)
        fun prototypeType(type: IrType): IrType {
            val ownerType = if (role == DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY) {
                type
            } else {
                ownerErasure.substitute(type)
            }
            return methodSubstitutor.substitute(ownerType)
        }
        prototype.returnType = prototypeType(source.returnType)
        source.parameters
            .filter { parameter -> parameter.kind != IrParameterKind.DispatchReceiver }
            .forEach { parameter ->
                prototype.parameters += parameter.copyTo(
                    prototype,
                    type = prototypeType(parameter.type),
                    varargElementType = parameter.varargElementType?.let(::prototypeType),
                    defaultValue = null,
                )
            }
        return DotNetGenericOwnerPrototypeMember(source, role, prototype)
    }

    private fun IrSimpleFunction.policyFor(owner: IrClass): DotNetGenericOwnerMemberPolicy {
        // Kotlin excludes private-to-owner declarations from declaration-site variance checks.
        // Such a helper is not itself callable through a widened receiver; it inherits semantic
        // reachability only through the producer call graph of an exposed broad entry.
        if (DescriptorVisibilities.isPrivate(visibility)) {
            return DotNetGenericOwnerMemberPolicy.STRICT_TYPED
        }
        val hasBroadInput = parameters.any { parameter ->
            parameter.kind != IrParameterKind.DispatchReceiver &&
                    !parameter.type.isLegalAtOwnerVariance(owner, TypePolarity.IN)
        }
        if (!hasBroadInput) return DotNetGenericOwnerMemberPolicy.STRICT_TYPED
        return if (specialBridgeMethods.findSpecialWithOverride(this, includeSelf = true) != null) {
            DotNetGenericOwnerMemberPolicy.TYPE_SAFE_BARRIER
        } else {
            DotNetGenericOwnerMemberPolicy.SEMANTIC_BODY
        }
    }

    private fun IrClass.directSimpleFunctions(): List<IrSimpleFunction> =
        declarations.flatMap { declaration ->
            when (declaration) {
                is IrSimpleFunction -> listOf(declaration)
                is IrProperty -> listOfNotNull(declaration.getter, declaration.setter)
                else -> emptyList()
            }
        }.distinctBy { function -> function.symbol }

    private fun IrClass.directFields(): Set<IrField> =
        declarations.flatMapTo(linkedSetOf()) { declaration ->
            when (declaration) {
                is IrField -> listOf(declaration)
                is IrProperty -> listOfNotNull(declaration.backingField)
                else -> emptyList()
            }
        }

    /**
     * Computes static receiver evidence for every Kotlin-owned generic-class call in this module.
     * The result is deliberately more conservative than call lowering: it cannot alter IR and an
     * unsupported producer always becomes a capability or external-family proof obligation.
     */
    private enum class ReceiverOriginKind {
        EXACT,
        UNRESOLVED,
    }

    private data class ReceiverOrigin(
        val kind: ReceiverOriginKind,
        val exactType: IrType? = null,
    ) {
        init {
            require((kind == ReceiverOriginKind.EXACT) == (exactType != null)) {
                "an exact generic-owner receiver origin requires exactly one physical type"
            }
        }
    }

    private data class CallScope(
        val callerName: String,
        val callerLogicalBindingKey: String?,
        val accesses: DirectMemberAccesses,
    )

    private data class GenericOwnerCallTarget(
        val callee: IrSimpleFunction,
        val owner: IrClass,
        val logicalBindingKey: String?,
        val localFamily: DotNetGenericOwnerMemberFamilyPlan?,
        val receiver: IrExpression,
    )

    private inner class GenericOwnerCallRouteAnalyzer(
        private val producerFunctions: Set<IrFunction>,
        private val producerInitializers: List<ProducerInitializer>,
        private val producerAccesses: Map<IrFunction, DirectMemberAccesses>,
        private val initializerAccesses: Map<ProducerInitializer, DirectMemberAccesses>,
    ) {

        private val origins = linkedMapOf<Any, MutableSet<ReceiverOrigin>>()
        private val localDefaultSourcesByDispatcher = context.defaultArgumentDispatchers.entries.associate { entry ->
            entry.value to entry.key
        }

        fun analyze(): List<DotNetGenericOwnerCallRoutePlan> {
            seedBoundaries()
            val accesses = producerAccesses.values + initializerAccesses.values
            accesses.forEach { access ->
                access.valueDefinitions.keys.forEach(::node)
                access.reads.forEach(::node)
                access.writes.forEach(::node)
            }
            producerFunctions.forEach(::node)

            var changed: Boolean
            do {
                changed = false
                accesses.forEach { access ->
                    access.valueDefinitions.forEach { entry ->
                        changed = addOrigins(
                            entry.key,
                            entry.value.flatMapTo(linkedSetOf(), ::originsOf),
                        ) || changed
                    }
                    access.writeValues.forEach { entry ->
                        changed = addOrigins(
                            entry.key,
                            entry.value.flatMapTo(linkedSetOf()) { value -> originsOf(value) },
                        ) || changed
                    }
                }
                producerFunctions.forEach { function ->
                    changed = addOrigins(
                        function,
                        producerAccesses.getValue(function).returns.flatMapTo(linkedSetOf(), ::originsOf),
                    ) || changed
                }
                accesses.forEach { access ->
                    access.callSites.forEach { call ->
                        val target = call.symbol.owner
                        if (target !in producerFunctions || !target.hasClosedCallBoundary()) return@forEach
                        target.parameters.forEach { parameter ->
                            if (parameter.kind == IrParameterKind.DispatchReceiver) return@forEach
                            val argument = call.arguments.getOrNull(parameter.indexInParameters)
                            changed = addOrigins(
                                parameter,
                                if (argument == null) unresolved() else originsOf(argument),
                            ) || changed
                        }
                    }
                }
            } while (changed)

            val scopes = producerFunctions.map { function ->
                CallScope(
                    callerName = function.fqNameWhenAvailable?.asString() ?: function.name.asString(),
                    callerLogicalBindingKey = context.preLoweringDeclarationKeys[function],
                    accesses = producerAccesses.getValue(function),
                )
            } + producerInitializers.map { initializer ->
                CallScope(
                    callerName = initializer.label,
                    callerLogicalBindingKey = null,
                    accesses = initializerAccesses.getValue(initializer),
                )
            }
            return buildList {
                var relevantCallIndex = 0
                scopes.forEach { scope ->
                    scope.accesses.genericOwnerCallSites.forEach { call ->
                        val target = call.genericOwnerCallTargetOrNull() ?: return@forEach
                        val provenance = if (call.superQualifierSymbol != null) {
                            DotNetGenericOwnerCallReceiverProvenance.EXACT_CONSTRUCTION
                        } else {
                            receiverProvenance(target.receiver)
                        }
                        val requirement = when {
                            target.localFamily == null ->
                                DotNetGenericOwnerCallRouteRequirement.EXTERNAL_FAMILY_RECORD_REQUIRED
                            provenance == DotNetGenericOwnerCallReceiverProvenance.EXACT_CONSTRUCTION -> {
                                check(DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY in target.localFamily.roles) {
                                    "Internal .NET backend error: an exact generic-owner call lacks a typed entry"
                                }
                                DotNetGenericOwnerCallRouteRequirement.EXACT_TYPED_ENTRY
                            }
                            DotNetGenericOwnerMemberFamilyRole.CAPABILITY_DISPATCHER in target.localFamily.roles ->
                                DotNetGenericOwnerCallRouteRequirement.SEMANTIC_CAPABILITY
                            else -> DotNetGenericOwnerCallRouteRequirement.MISSING_CAPABILITY
                        }
                        add(DotNetGenericOwnerCallRoutePlan(
                            callerName = scope.callerName,
                            callerLogicalBindingKey = scope.callerLogicalBindingKey,
                            callSiteIndex = relevantCallIndex++,
                            call = call,
                            callee = target.callee,
                            calleeOwner = target.owner,
                            calleeLogicalBindingKey = target.logicalBindingKey,
                            receiverProvenance = provenance,
                            routeRequirement = requirement,
                        ))
                    }
                }
            }
        }

        private fun seedBoundaries() {
            producerFunctions.forEach { function ->
                function.parameters.forEach { parameter ->
                    when {
                        parameter.kind == IrParameterKind.DispatchReceiver ->
                            addOrigin(parameter, exact(parameter.type))
                        !function.hasClosedCallBoundary() -> if (parameter.type.isStaticallyExactGenericOwnerView()) {
                            addOrigin(parameter, exact(parameter.type))
                        } else {
                            addOrigins(parameter, unresolved())
                        }
                    }
                }
                if (function.body == null) {
                    if (function.returnType.isStaticallyExactGenericOwnerView()) {
                        addOrigin(function, exact(function.returnType))
                    } else {
                        addOrigins(function, unresolved())
                    }
                }
            }
            val fields = buildSet {
                producerAccesses.values.forEach { access ->
                    addAll(access.reads)
                    addAll(access.writes)
                }
                initializerAccesses.values.forEach { access ->
                    addAll(access.reads)
                    addAll(access.writes)
                }
            }
            fields.filterNotTo(linkedSetOf()) { field -> DescriptorVisibilities.isPrivate(field.visibility) }
                .forEach { field ->
                    if (field.type.isStaticallyExactGenericOwnerView()) {
                        addOrigin(field, exact(field.type))
                    } else {
                        addOrigins(field, unresolved())
                    }
                }
        }

        private fun IrFunction.hasClosedCallBoundary(): Boolean =
            DescriptorVisibilities.isPrivate(visibility) || visibility == DescriptorVisibilities.LOCAL

        private fun IrCall.genericOwnerCallTargetOrNull(): GenericOwnerCallTarget? {
            val raw = symbol.owner
            localDefaultSourcesByDispatcher[raw]?.let { source ->
                val owner = source.parent as? IrClass ?: return null
                val family = context.genericOwnerArchitecturePlans[owner]?.memberFamilies?.get(source) ?: return null
                return GenericOwnerCallTarget(
                    callee = source,
                    owner = owner,
                    logicalBindingKey = context.preLoweringDeclarationKeys[source]
                        ?: source.dotNetLibraryAbiKeyOrNull("F"),
                    localFamily = family,
                    receiver = movedDispatchReceiverArgumentOrNull() ?: return null,
                )
            }
            context.externalDefaultArgumentDispatchers[raw]?.let { bound ->
                val receiver = movedDispatchReceiverArgumentOrNull() ?: return null
                val owner = ((receiver.type as? IrSimpleType)?.classifier as? IrClassSymbol)?.owner ?: return null
                if (!owner.isDotNetGenericClassDeclaration) return null
                val logicalBindingKey = bound.library.declarations.entries.singleOrNull { entry ->
                    entry.value === bound.function
                }?.key ?: return null
                return GenericOwnerCallTarget(
                    callee = raw.defaultArgumentsOriginalFunction as? IrSimpleFunction ?: raw,
                    owner = owner,
                    logicalBindingKey = logicalBindingKey,
                    localFamily = null,
                    receiver = receiver,
                )
            }
            val receiver = dispatchReceiver ?: return null
            val localRawOwner = raw.parent as? IrClass
            val localRawFamily = localRawOwner
                ?.let { owner -> context.genericOwnerArchitecturePlans[owner]?.memberFamilies?.get(raw) }
            if (localRawOwner != null && localRawFamily != null) {
                return GenericOwnerCallTarget(
                    callee = raw,
                    owner = localRawOwner,
                    logicalBindingKey = context.preLoweringDeclarationKeys[raw]
                        ?: raw.dotNetLibraryAbiKeyOrNull("F"),
                    localFamily = localRawFamily,
                    receiver = receiver,
                )
            }
            val callee = if (raw.isFakeOverride) {
                raw.resolveFakeOverride() ?: raw.resolveFakeOverrideMaybeAbstract() ?: return null
            } else {
                raw
            }
            val owner = callee.parent as? IrClass ?: return null
            if (!owner.isDotNetGenericClassDeclaration) return null
            val localFamily = context.genericOwnerArchitecturePlans[owner]?.memberFamilies?.get(callee)
            val logicalBindingKey = context.preLoweringDeclarationKeys[callee]
                ?: callee.dotNetLibraryAbiKeyOrNull("F")
            if (localFamily == null && logicalBindingKey == null) return null
            return GenericOwnerCallTarget(callee, owner, logicalBindingKey, localFamily, receiver)
        }

        private fun IrCall.movedDispatchReceiverArgumentOrNull(): IrExpression? {
            val receiverParameter = symbol.owner.parameters.singleOrNull { parameter ->
                parameter.origin == IrDeclarationOrigin.MOVED_DISPATCH_RECEIVER
            } ?: return null
            return arguments.getOrNull(receiverParameter.indexInParameters)
        }

        private fun receiverProvenance(receiver: IrExpression): DotNetGenericOwnerCallReceiverProvenance {
            val candidates = originsOf(receiver)
            if (candidates.isEmpty() || candidates.any { origin -> origin.kind == ReceiverOriginKind.UNRESOLVED }) {
                return DotNetGenericOwnerCallReceiverProvenance.UNRESOLVED
            }
            val exact = candidates.filter { origin -> origin.kind == ReceiverOriginKind.EXACT }
            if (exact.isEmpty() || exact.any { origin -> !checkNotNull(origin.exactType).hasPhysicalView(receiver.type) }) {
                return DotNetGenericOwnerCallReceiverProvenance.SEMANTIC_VIEW
            }
            return DotNetGenericOwnerCallReceiverProvenance.EXACT_CONSTRUCTION
        }

        private fun originsOf(expression: IrExpression?): Set<ReceiverOrigin> {
            if (expression == null) return unresolved()
            return when (expression) {
                is IrConstructorCall -> {
                    val owner = expression.symbol.owner.parent as? IrClass
                    if (owner?.hasRelevantGenericOwnerInAncestry() == true) {
                        setOf(exact(expression.type))
                    } else {
                        emptySet()
                    }
                }
                is IrGetValue -> origins[expression.symbol.owner].orEmpty()
                is IrGetField -> origins[expression.symbol.owner].orEmpty().ifEmpty { unresolved() }
                is IrTypeOperatorCall -> originsOf(expression.argument)
                is IrReturn -> originsOf(expression.value)
                is IrWhen -> expression.branches.flatMapTo(linkedSetOf()) { branch ->
                    originsOf(branch.result)
                }
                is IrContainerExpression -> originsOf(expression.statements.lastOrNull() as? IrExpression)
                is IrFunctionAccessExpression -> {
                    val target = expression.symbol.owner
                    if (target in producerFunctions) {
                        origins[target].orEmpty()
                    } else if (expression.type.hasRelevantGenericOwnerInAncestry()) {
                        unresolved()
                    } else {
                        emptySet()
                    }
                }
                else -> if (expression.type.hasRelevantGenericOwnerInAncestry()) unresolved() else emptySet()
            }
        }

        private fun IrType.hasRelevantGenericOwnerInAncestry(): Boolean {
            val owner = ((this as? IrSimpleType)?.classifier as? IrClassSymbol)?.owner ?: return false
            return owner.hasRelevantGenericOwnerInAncestry()
        }

        private fun IrType.isStaticallyExactGenericOwnerView(): Boolean {
            val simple = this as? IrSimpleType ?: return false
            val owner = (simple.classifier as? IrClassSymbol)?.owner ?: return false
            if (!owner.hasRelevantGenericOwnerInAncestry()) return false
            if (simple.arguments.size != owner.typeParameters.size) return false
            if (owner.typeParameters.any { parameter -> parameter.variance != Variance.INVARIANT }) return false
            return simple.arguments.all { argument ->
                val projection = argument as? IrTypeProjection ?: return@all false
                projection.variance == Variance.INVARIANT
            }
        }

        private fun IrClass.hasRelevantGenericOwnerInAncestry(visited: MutableSet<IrClass> = hashSetOf()): Boolean {
            if (!visited.add(this)) return false
            if (this in context.genericOwnerArchitecturePlans ||
                (isDotNetGenericClassDeclaration && dotNetLibraryAbiKeyOrNull("C") != null)
            ) {
                return true
            }
            return superTypes.any { superType ->
                val superClass = ((superType as? IrSimpleType)?.classifier as? IrClassSymbol)?.owner
                superClass?.hasRelevantGenericOwnerInAncestry(visited) == true
            }
        }

        private fun IrType.hasPhysicalView(expected: IrType): Boolean {
            val pending = ArrayDeque<IrType>()
            val visited = hashSetOf<IrType>()
            pending += this
            while (pending.isNotEmpty()) {
                val candidate = pending.removeFirst()
                if (!visited.add(candidate)) continue
                if (candidate.sameInvariantTypeAs(expected)) return true
                val simple = candidate as? IrSimpleType ?: continue
                val classifier = (simple.classifier as? IrClassSymbol)?.owner ?: continue
                if (simple.arguments.size != classifier.typeParameters.size) continue
                val substitutions = classifier.typeParameters.zip(simple.arguments).mapNotNull { pair ->
                    val projection = pair.second as? IrTypeProjection ?: return@mapNotNull null
                    pair.first.symbol to projection.type
                }
                if (substitutions.size != classifier.typeParameters.size) continue
                val substitutor = IrTypeSubstitutor(substitutions.toMap(), allowEmptySubstitution = true)
                classifier.superTypes.mapTo(pending, substitutor::substitute)
            }
            return false
        }

        private fun IrType.sameInvariantTypeAs(other: IrType): Boolean {
            val left = this as? IrSimpleType ?: return false
            val right = other as? IrSimpleType ?: return false
            if (left.classifier != right.classifier || left.nullability != right.nullability ||
                left.arguments.size != right.arguments.size
            ) {
                return false
            }
            return left.arguments.indices.all { index ->
                val leftProjection = left.arguments[index] as? IrTypeProjection ?: return@all false
                val rightProjection = right.arguments[index] as? IrTypeProjection ?: return@all false
                leftProjection.variance == Variance.INVARIANT &&
                        rightProjection.variance == Variance.INVARIANT &&
                        leftProjection.type.sameInvariantTypeAs(rightProjection.type)
            }
        }

        private fun exact(type: IrType): ReceiverOrigin = ReceiverOrigin(ReceiverOriginKind.EXACT, type)

        private fun unresolved(): Set<ReceiverOrigin> = setOf(ReceiverOrigin(ReceiverOriginKind.UNRESOLVED))

        private fun node(key: Any): MutableSet<ReceiverOrigin> = origins.getOrPut(key) { linkedSetOf() }

        private fun addOrigin(key: Any, origin: ReceiverOrigin): Boolean = node(key).add(origin)

        private fun addOrigins(key: Any, additions: Set<ReceiverOrigin>): Boolean = node(key).addAll(additions)
    }

    /**
     * Traces the physical domain of field-write values through parameters, calls, local aliases,
     * assignments, returns, and casts. This is deliberately context-insensitive and fail-closed:
     * merging any object-domain producer keeps the write semantic, while an unsupported or
     * source-free path remains unresolved. In particular, a cast to an owner parameter preserves
     * its input provenance and can never create typed evidence by itself.
     */
    private inner class TypedWriteValueProvenanceAnalyzer(
        private val owner: IrClass,
        private val members: List<IrSimpleFunction>,
        private val memberPolicies: Map<IrSimpleFunction, DotNetGenericOwnerMemberPolicy>,
        private val producerAccesses: Map<IrFunction, DirectMemberAccesses>,
        private val initializerAccesses: Map<ProducerInitializer, DirectMemberAccesses>,
    ) {
        private val provenances = linkedMapOf<Any, MutableSet<DotNetGenericOwnerWriteValueProvenance>>()
        private val typedBoundaryParameters = linkedSetOf<IrValueDeclaration>()

        fun analyze(
            fields: Set<IrField>,
        ): Map<IrField, List<DotNetGenericOwnerStateWriteProvenancePlan>> {
            val activeSlice = activeProducerSlice(fields)
            val activeFunctions = activeSlice.first
            val activeInitializers = activeSlice.second
            seedCallableBoundaries(activeFunctions)
            activeFunctions.forEach { function ->
                val access = producerAccesses.getValue(function)
                if (function.body == null) {
                    addProvenance(function, defaultProvenance(function.returnType))
                }
                access.valueDefinitions.keys.forEach { declaration -> node(declaration) }
                node(function)
            }

            val allAccesses = activeFunctions.map { function -> producerAccesses.getValue(function) } +
                    activeInitializers.map { initializer -> initializerAccesses.getValue(initializer) }
            var changed: Boolean
            do {
                changed = false
                activeFunctions.forEach { function ->
                    val access = producerAccesses.getValue(function)
                    access.valueDefinitions.forEach { entry ->
                        val declaration = entry.key
                        val definitions = entry.value
                        changed = addProvenances(declaration, definitions.flatMapTo(linkedSetOf(), ::provenanceOf)) || changed
                    }
                    changed = addProvenances(function, access.returns.flatMapTo(linkedSetOf(), ::provenanceOf)) || changed
                }
                allAccesses.forEach { access ->
                    access.callSites.forEach { call ->
                        val target = call.symbol.owner
                        target.parameters.forEach { parameter ->
                            if (parameter.kind == IrParameterKind.DispatchReceiver) return@forEach
                            if (parameter in typedBoundaryParameters) return@forEach
                            val argument = call.arguments.getOrNull(parameter.indexInParameters)
                            val argumentProvenance = if (argument == null) {
                                setOf(DotNetGenericOwnerWriteValueProvenance.UNRESOLVED)
                            } else {
                                provenanceOf(argument)
                            }
                            changed = addProvenances(parameter, argumentProvenance) || changed
                        }
                    }
                }
            } while (changed)

            return fields.associateWithTo(linkedMapOf()) { field ->
                buildList {
                    producerAccesses.forEach { entry ->
                        val function = entry.key
                        val access = entry.value
                        val values = access.writeValues[field]
                            ?: if (field in access.writes) listOf(null) else emptyList()
                        if (values.isNotEmpty()) {
                            add(DotNetGenericOwnerStateWriteProvenancePlan(
                                producerName = function.name.asString(),
                                provenance = select(values.flatMapTo(linkedSetOf()) { value ->
                                    provenanceOf(value).ifEmpty {
                                        setOf(DotNetGenericOwnerWriteValueProvenance.UNRESOLVED)
                                    }
                                }),
                            ))
                        }
                    }
                    initializerAccesses.forEach { entry ->
                        val initializer = entry.key
                        val access = entry.value
                        val values = access.writeValues[field]
                            ?: if (field in access.writes) listOf(null) else emptyList()
                        if (values.isNotEmpty()) {
                            add(DotNetGenericOwnerStateWriteProvenancePlan(
                                producerName = initializer.label,
                                provenance = select(values.flatMapTo(linkedSetOf()) { value ->
                                    provenanceOf(value).ifEmpty {
                                        setOf(DotNetGenericOwnerWriteValueProvenance.UNRESOLVED)
                                    }
                                }),
                            ))
                        }
                    }
                }
            }
        }

        private fun activeProducerSlice(
            fields: Set<IrField>,
        ): Pair<Set<IrFunction>, Set<ProducerInitializer>> {
            val functions = producerAccesses
                .filterTo(linkedMapOf()) { entry ->
                    val function = entry.key
                    val access = entry.value
                    function in members ||
                            (function is IrConstructor && function.parent == owner) ||
                            access.writes.any { field -> field in fields }
                }
                .keys
                .toMutableSet()
            val initializers = initializerAccesses
                .filterTo(linkedMapOf()) { entry ->
                    val access = entry.value
                    access.writes.any { field -> field in fields }
                }
                .keys
                .toMutableSet()
            var changed: Boolean
            do {
                changed = false
                val reachableCalls = buildSet {
                    functions.forEach { function -> addAll(producerAccesses.getValue(function).calls) }
                    initializers.forEach { initializer -> addAll(initializerAccesses.getValue(initializer).calls) }
                }
                if (functions.addAll(reachableCalls)) changed = true
                initializerAccesses.forEach { entry ->
                    val initializer = entry.key
                    val access = entry.value
                    if (initializer !in initializers && access.calls.any { function -> function in functions }) {
                        initializers += initializer
                        changed = true
                    }
                }
            } while (changed)
            return functions to initializers
        }

        private fun seedCallableBoundaries(activeFunctions: Set<IrFunction>) {
            activeFunctions.forEach { function ->
                val isOwnerConstructor = function is IrConstructor && function.parent == owner
                if (DescriptorVisibilities.isPrivate(function.visibility) && !isOwnerConstructor) return@forEach
                val member = (function as? IrSimpleFunction)?.takeIf { candidate -> candidate in members }
                function.parameters.forEach { parameter ->
                    if (parameter.kind == IrParameterKind.DispatchReceiver) return@forEach
                    val isTypedOwnerInput = parameter.type.referencesTypeParameterOf(owner) && when {
                        isOwnerConstructor -> true
                        member != null -> parameter.type.isLegalAtOwnerVariance(owner, TypePolarity.IN)
                        else -> false
                    }
                    addProvenance(
                        parameter,
                        if (isTypedOwnerInput) {
                            typedBoundaryParameters += parameter
                            DotNetGenericOwnerWriteValueProvenance.PHYSICALLY_TYPED
                        } else {
                            DotNetGenericOwnerWriteValueProvenance.SEMANTIC_OBJECT
                        },
                    )
                }
            }
            check(memberPolicies.keys == members.toSet()) {
                "Internal .NET backend error: typed-write provenance lacks owner member policies"
            }
        }

        private fun provenanceOf(expression: IrExpression?): Set<DotNetGenericOwnerWriteValueProvenance> {
            if (expression == null) return setOf(DotNetGenericOwnerWriteValueProvenance.UNRESOLVED)
            return when (expression) {
                is IrGetValue -> provenances[expression.symbol.owner].orEmpty()
                is IrTypeOperatorCall -> provenanceOf(expression.argument)
                is IrReturn -> provenanceOf(expression.value)
                is IrWhen -> expression.branches.flatMapTo(linkedSetOf()) { branch ->
                    provenanceOf(branch.result)
                }
                is IrContainerExpression -> {
                    val result = expression.statements.lastOrNull() as? IrExpression
                    provenanceOf(result)
                }
                is IrFunctionAccessExpression -> {
                    val target = expression.symbol.owner
                    if (expression.isPhysicallyTypedOwnerClassifierArrayAllocation(owner)) {
                        setOf(DotNetGenericOwnerWriteValueProvenance.PHYSICALLY_TYPED)
                    } else if (target in producerAccesses) {
                        provenances[target].orEmpty()
                    } else {
                        setOf(defaultProvenance(expression.type))
                    }
                }
                is IrGetField -> setOf(defaultProvenance(expression.type))
                else -> setOf(defaultProvenance(expression.type))
            }
        }

        /**
         * `arrayOfNulls<Node<T>>()` is a typed producer when Node is a local Kotlin generic
         * classifier. The current erased owner emits Node[] and a future admitted CLR-generic
         * owner emits Node<T>[]; neither route passes through object-domain element storage.
         * Direct `arrayOfNulls<T>()`/`arrayOfNulls<T?>()` deliberately fails this test.
         */
        private fun defaultProvenance(type: IrType): DotNetGenericOwnerWriteValueProvenance =
            if (type.referencesTypeParameterOf(owner)) {
                DotNetGenericOwnerWriteValueProvenance.UNRESOLVED
            } else {
                DotNetGenericOwnerWriteValueProvenance.SEMANTIC_OBJECT
            }

        private fun node(key: Any): MutableSet<DotNetGenericOwnerWriteValueProvenance> =
            provenances.getOrPut(key) { linkedSetOf() }

        private fun addProvenance(
            key: Any,
            provenance: DotNetGenericOwnerWriteValueProvenance,
        ): Boolean = node(key).add(provenance)

        private fun addProvenances(
            key: Any,
            additions: Set<DotNetGenericOwnerWriteValueProvenance>,
        ): Boolean = node(key).addAll(additions)

        private fun select(
            candidates: Set<DotNetGenericOwnerWriteValueProvenance>,
        ): DotNetGenericOwnerWriteValueProvenance = when {
            DotNetGenericOwnerWriteValueProvenance.SEMANTIC_OBJECT in candidates ->
                DotNetGenericOwnerWriteValueProvenance.SEMANTIC_OBJECT
            DotNetGenericOwnerWriteValueProvenance.UNRESOLVED in candidates ->
                DotNetGenericOwnerWriteValueProvenance.UNRESOLVED
            DotNetGenericOwnerWriteValueProvenance.PHYSICALLY_TYPED in candidates ->
                DotNetGenericOwnerWriteValueProvenance.PHYSICALLY_TYPED
            else -> DotNetGenericOwnerWriteValueProvenance.UNRESOLVED
        }
    }

    private data class DirectMemberAccesses(
        val calls: Set<IrFunction>,
        val callSites: List<IrFunctionAccessExpression>,
        val genericOwnerCallSites: List<IrCall>,
        val reads: Set<IrField>,
        val writes: Set<IrField>,
        val writeValues: Map<IrField, List<IrExpression?>>,
        val valueDefinitions: Map<IrValueDeclaration, List<IrExpression>>,
        val returns: List<IrExpression>,
    ) {
        fun restrictTo(fields: Set<IrField>): DirectMemberAccesses = copy(
            reads = reads.filterTo(linkedSetOf()) { field -> field in fields },
            writes = writes.filterTo(linkedSetOf()) { field -> field in fields },
            writeValues = writeValues.filterKeys { field -> field in fields },
        )

        fun withImplicitWrite(initializer: ProducerInitializer): DirectMemberAccesses {
            val field = initializer.implicitWrite ?: return this
            val expression = (initializer.element as? IrExpressionBody)?.expression
            return copy(
                writes = writes + field,
                writeValues = writeValues + (field to (writeValues[field].orEmpty() + expression)),
            )
        }
    }

    private data class ProducerInitializer(
        val label: String,
        val element: IrElement,
        val implicitWrite: IrField? = null,
    )

    private fun ProducerInitializer.stateInitializerPlanOrNull(
        field: IrField,
        owner: IrClass,
    ): DotNetGenericOwnerStateInitializerPlan? {
        if (implicitWrite != field) return null
        val expression = (element as? IrExpressionBody)?.expression
        val allocation = expression as? IrFunctionAccessExpression
        val fixedElementCount = allocation
            ?.takeIf { candidate -> candidate.isPhysicallyTypedOwnerClassifierArrayAllocation(owner) }
            ?.arguments
            ?.singleOrNull()
            ?.let { argument -> (argument as? IrConst)?.value as? Int }
        return DotNetGenericOwnerStateInitializerPlan(
            producerName = label,
            kind = if (fixedElementCount != null) {
                DotNetGenericOwnerPrototypeStateInitializerKind.FIXED_ZEROED_SZ_ARRAY
            } else {
                DotNetGenericOwnerPrototypeStateInitializerKind.UNSUPPORTED
            },
            fixedElementCount = fixedElementCount,
        )
    }

    private fun IrFunctionAccessExpression.isPhysicallyTypedOwnerClassifierArrayAllocation(
        owner: IrClass,
    ): Boolean {
        if (symbol != context.irBuiltIns.arrayOfNulls) return false
        val arrayType = type as? IrSimpleType ?: return false
        if (arrayType.classifier != context.irBuiltIns.arrayClass) return false
        val elementProjection = arrayType.arguments.singleOrNull() as? IrTypeProjection ?: return false
        if (elementProjection.variance != Variance.INVARIANT ||
            !elementProjection.type.referencesTypeParameterOf(owner)
        ) {
            return false
        }
        val elementClass = ((elementProjection.type as? IrSimpleType)?.classifier as? IrClassSymbol)
            ?.owner
            ?: return false
        return elementClass.origin != IrDeclarationOrigin.IR_EXTERNAL_DECLARATION_STUB &&
                elementClass.isDotNetGenericClassDeclaration
    }

    private fun IrElement.collectDirectAccesses(
        producerFunctions: Set<IrFunction>,
        returnTarget: IrFunction? = null,
    ): DirectMemberAccesses {
        val calls = linkedSetOf<IrFunction>()
        val callSites = mutableListOf<IrFunctionAccessExpression>()
        val genericOwnerCallSites = mutableListOf<IrCall>()
        val reads = linkedSetOf<IrField>()
        val writes = linkedSetOf<IrField>()
        val writeValues = linkedMapOf<IrField, MutableList<IrExpression?>>()
        val valueDefinitions = linkedMapOf<IrValueDeclaration, MutableList<IrExpression>>()
        val returns = mutableListOf<IrExpression>()
        acceptVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitClass(declaration: IrClass) {
                // A nested declaration is an independently indexed producer body, not an effect
                // of declaring the class while this body executes.
            }

            override fun visitFunction(declaration: IrFunction) {
                // Local functions are independently indexed and become reachable only by a call.
            }

            override fun visitFunctionAccess(expression: IrFunctionAccessExpression) {
                if (expression is IrCall) genericOwnerCallSites += expression
                val target = expression.symbol.owner
                if (target in producerFunctions) {
                    calls += target
                    callSites += expression
                }
                expression.acceptChildrenVoid(this)
            }

            override fun visitVariable(declaration: IrVariable) {
                declaration.initializer?.let { initializer ->
                    valueDefinitions.getOrPut(declaration) { mutableListOf() } += initializer
                }
                declaration.acceptChildrenVoid(this)
            }

            override fun visitSetValue(expression: IrSetValue) {
                valueDefinitions.getOrPut(expression.symbol.owner) { mutableListOf() } += expression.value
                expression.acceptChildrenVoid(this)
            }

            override fun visitReturn(expression: IrReturn) {
                if (expression.returnTargetSymbol.owner == returnTarget) returns += expression.value
                expression.acceptChildrenVoid(this)
            }

            override fun visitGetField(expression: IrGetField) {
                val field = expression.symbol.owner
                reads += field
                expression.acceptChildrenVoid(this)
            }

            override fun visitSetField(expression: IrSetField) {
                val field = expression.symbol.owner
                writes += field
                writeValues.getOrPut(field) { mutableListOf() } += expression.value
                expression.acceptChildrenVoid(this)
            }
        })
        return DirectMemberAccesses(
            calls = calls,
            callSites = callSites,
            genericOwnerCallSites = genericOwnerCallSites,
            reads = reads,
            writes = writes,
            writeValues = writeValues,
            valueDefinitions = valueDefinitions,
            returns = returns,
        )
    }

    private fun IrFunction.collectDirectAccesses(
        producerFunctions: Set<IrFunction>,
    ): DirectMemberAccesses = body?.collectDirectAccesses(producerFunctions, this)?.let { access ->
        val expressionResult = (body as? IrExpressionBody)?.expression
        if (expressionResult == null) access else access.copy(returns = access.returns + expressionResult)
    } ?: DirectMemberAccesses(
            calls = emptySet(),
            callSites = emptyList(),
            genericOwnerCallSites = emptyList(),
            reads = emptySet(),
            writes = emptySet(),
            writeValues = emptyMap(),
            valueDefinitions = emptyMap(),
            returns = emptyList(),
        )

    private fun IrFunction.transitiveCalls(
        directAccesses: Map<IrFunction, DirectMemberAccesses>,
    ): Set<IrFunction> {
        val result = linkedSetOf<IrFunction>()
        val worklist = ArrayDeque(directAccesses.getValue(this).calls)
        while (worklist.isNotEmpty()) {
            val target = worklist.removeFirst()
            if (target == this || !result.add(target)) continue
            worklist.addAll(directAccesses.getValue(target).calls)
        }
        return result
    }

    private fun IrFunction.transitiveFieldReads(
        directAccesses: Map<IrFunction, DirectMemberAccesses>,
    ): Set<IrField> = (transitiveCalls(directAccesses) + this)
        .flatMapTo(linkedSetOf()) { function -> directAccesses.getValue(function).reads }

    private fun IrFunction.transitiveFieldWrites(
        directAccesses: Map<IrFunction, DirectMemberAccesses>,
    ): Set<IrField> = (transitiveCalls(directAccesses) + this)
        .flatMapTo(linkedSetOf()) { function -> directAccesses.getValue(function).writes }

    /** Detects the `D<T> : C<T?>` family whose TypeDef edge cannot vary per closed T. */
    private fun IrType.hasExplicitNullableParameterOf(owner: IrClass): Boolean {
        val simpleType = this as? IrSimpleType ?: return false
        val parameter = (simpleType.classifier as? IrTypeParameterSymbol)?.owner
        if (parameter?.parent == owner && simpleType.nullability == SimpleTypeNullability.MARKED_NULLABLE) {
            return true
        }
        return simpleType.arguments.any { argument ->
            (argument as? IrTypeProjection)?.type?.hasExplicitNullableParameterOf(owner) == true
        }
    }

    private enum class TypePolarity {
        OUT,
        IN,
        BOTH;

        fun through(variance: Variance): TypePolarity = when {
            this == BOTH || variance == Variance.INVARIANT -> BOTH
            variance == Variance.OUT_VARIANCE -> this
            else -> if (this == OUT) IN else OUT
        }
    }

    /** Kotlin declaration/use-site variance, including the variance Kotlin permits on classes. */
    private fun IrType.isLegalAtOwnerVariance(owner: IrClass, polarity: TypePolarity): Boolean {
        val simpleType = this as? IrSimpleType ?: return true
        val parameter = (simpleType.classifier as? IrTypeParameterSymbol)?.owner
        if (parameter?.parent == owner) {
            return when (polarity) {
                TypePolarity.OUT -> parameter.variance != Variance.IN_VARIANCE
                TypePolarity.IN -> parameter.variance != Variance.OUT_VARIANCE
                TypePolarity.BOTH -> parameter.variance == Variance.INVARIANT
            }
        }

        val classifier = (simpleType.classifier as? IrClassSymbol)?.owner ?: return true
        return simpleType.arguments.withIndex().all { indexedArgument ->
            val index = indexedArgument.index
            val argument = indexedArgument.value
            val projection = argument as? IrTypeProjection ?: return@all true
            val declarationVariance = classifier.typeParameters.getOrNull(index)?.variance
                ?: Variance.INVARIANT
            val effectiveVariance = if (projection.variance == Variance.INVARIANT) {
                declarationVariance
            } else {
                projection.variance
            }
            projection.type.isLegalAtOwnerVariance(owner, polarity.through(effectiveVariance))
        }
    }
}
