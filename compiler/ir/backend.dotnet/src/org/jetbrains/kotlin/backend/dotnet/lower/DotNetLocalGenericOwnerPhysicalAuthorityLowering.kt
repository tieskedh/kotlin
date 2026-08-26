/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet.lower

import org.jetbrains.kotlin.backend.common.ModuleLoweringPass
import org.jetbrains.kotlin.backend.dotnet.DotNetBackendContext
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerDirectSupertypeKind
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalBindingResult
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalCallableResultLayoutReference
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalCallableValueSlotReference
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalDirectSupertypeEdgeReference
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalDirectSupertypeEdgeSet
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalMemberDispatch
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalMemberVisibility
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalMethodDefIdentity
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalMethodDefReference
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalMethodSignatureReference
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalSlotDomain
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalTypeDefIdentity
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerSymbolicCarrierReference
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerMemberFamilyRole
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericInterfaceView
import org.jetbrains.kotlin.backend.dotnet.DotNetInterfaceDefaultPromotionView
import org.jetbrains.kotlin.backend.dotnet.DotNetLocalGenericOwnerPhysicalAuthority
import org.jetbrains.kotlin.backend.dotnet.DotNetLocalGenericOwnerPhysicalBoundInput
import org.jetbrains.kotlin.backend.dotnet.DotNetLocalGenericOwnerPhysicalCallableFamilyInput
import org.jetbrains.kotlin.backend.dotnet.DotNetLocalGenericOwnerPhysicalClassEdgePlan
import org.jetbrains.kotlin.backend.dotnet.DotNetLocalGenericOwnerPhysicalCompleteEmissionFamilyInput
import org.jetbrains.kotlin.backend.dotnet.DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodImplKind
import org.jetbrains.kotlin.backend.dotnet.DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodImplReference
import org.jetbrains.kotlin.backend.dotnet.DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodInput
import org.jetbrains.kotlin.backend.dotnet.DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodKind
import org.jetbrains.kotlin.backend.dotnet.DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind
import org.jetbrains.kotlin.backend.dotnet.DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeParameterReference
import org.jetbrains.kotlin.backend.dotnet.DotNetLocalGenericOwnerPhysicalInterfaceEdgeInput
import org.jetbrains.kotlin.backend.dotnet.DotNetLocalGenericOwnerPhysicalInterfaceCapabilityDispatcherSelection
import org.jetbrains.kotlin.backend.dotnet.DotNetLocalGenericOwnerPhysicalTypeInput
import org.jetbrains.kotlin.backend.dotnet.DotNetLocalGenericOwnerPhysicalTypeRole
import org.jetbrains.kotlin.backend.dotnet.DotNetPublishedGenericInterfaceCapabilityBindingKind
import org.jetbrains.kotlin.backend.dotnet.DotNetPublishedGenericInterfaceFamilyKind
import org.jetbrains.kotlin.backend.dotnet.DotNetPublishedGenericInterfaceMemberRole
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalTypeParameterVariance
import org.jetbrains.kotlin.backend.dotnet.dotNetBaseSuperTypeOrNull
import org.jetbrains.kotlin.backend.dotnet.dotNetDirectInterfaceTypes
import org.jetbrains.kotlin.backend.dotnet.dotNetGenericOwnerRehearsal
import org.jetbrains.kotlin.backend.dotnet.dotNetPhysicalValueStableName
import org.jetbrains.kotlin.backend.dotnet.isReifiedByGenericOwnerRehearsal
import org.jetbrains.kotlin.backend.dotnet.requiresExactInputView
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.isAny
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.types.isNullableAny
import org.jetbrains.kotlin.types.Variance
import java.util.IdentityHashMap

/**
 * Records one complete bounded class edge plan at the point where interface bridges have selected
 * their physical families. Looking at a shape here may reject it; only the explicit records below
 * can later prove an edge. The bound-authority pass never re-reads these IR supertypes.
 */
internal fun recordLocalGenericOwnerPhysicalClassEdgesAtBridgeSelection(
    context: DotNetBackendContext,
    owner: IrClass,
) {
    if (!context.configuration.dotNetGenericOwnerRehearsal) return
    val plan = context.genericOwnerArchitecturePlans[owner]
        ?.takeIf { candidate ->
            candidate.isReifiedByGenericOwnerRehearsal && owner.kind == ClassKind.CLASS
        }
        ?: return
    if (owner.dotNetBaseSuperTypeOrNull() != null) return
    if (owner.typeParameters.any { parameter ->
            parameter.superTypes.any { bound -> !bound.isAny() && !bound.isNullableAny() }
        }) return
    if (context.externalGenericOwnerCapabilitySupertypeProviders[owner].orEmpty().isNotEmpty()) {
        return
    }
    if (context.genericInterfaceViewBridges.any { bridge ->
            bridge.owner == owner &&
                    bridge.physicalView == DotNetInterfaceDefaultPromotionView.EXACT
        }) return

    val directInterfaces = owner.dotNetDirectInterfaceTypes()
    val naturalEntries = directInterfaces.mapNotNull { interfaceType ->
        val targetOwner = (interfaceType.classifier as? IrClassSymbol)?.owner ?: return
        val contract = context.publishedGenericInterfaceFamilies[targetOwner] ?: return@mapNotNull null
        Triple(interfaceType, targetOwner, contract)
    }
    val naturalEntry = naturalEntries.singleOrNull() ?: return
    val naturalType = naturalEntry.first
    val naturalOwner = naturalEntry.second
    val naturalContract = naturalEntry.third
    if (naturalOwner !in context.reifiedGenericInterfaces ||
        naturalContract.kind != DotNetPublishedGenericInterfaceFamilyKind.ROOT ||
        naturalContract.capabilityBindingKind !=
        DotNetPublishedGenericInterfaceCapabilityBindingKind.OWNED ||
        naturalContract.requiresExactInputView
    ) return
    val classCapabilityOwner = context.genericOwnerCapabilityInterfaces[owner] ?: return
    val interfaceCapabilityOwner = context.genericOwnerCapabilityInterfaces[naturalOwner] ?: return
    val capabilityOwners = setOf(classCapabilityOwner, interfaceCapabilityOwner)
    if (capabilityOwners.size != 2 || directInterfaces.size != 3 ||
        directInterfaces.map { type -> (type.classifier as? IrClassSymbol)?.owner }.toSet() !=
        setOf(naturalOwner) + capabilityOwners
    ) return

    // The emitter may independently append interface rows from generated MethodImpl bridges.
    // None is represented by this first selection-site grammar, so reject every such origin
    // rather than assuming that a same-root bridge can only duplicate the natural row.
    if (owner.declarations.filterIsInstance<IrSimpleFunction>().any { member ->
            member.origin == DOTNET_GENERIC_INTERFACE_CANONICAL_BRIDGE ||
                    member.origin.dotNetGenericInterfaceBridgeMemberViewOrNull != null
        }
    ) return

    val recordedInterfaces = mutableListOf<DotNetLocalGenericOwnerPhysicalInterfaceEdgeInput>()
    for (interfaceType in directInterfaces) {
        val targetOwner = (interfaceType.classifier as? IrClassSymbol)?.owner ?: return
        val targetIdentity: DotNetGenericOwnerPhysicalTypeDefIdentity.Local
        val sourceParameterIndices: List<Int>
        when {
            targetOwner === naturalOwner -> {
                if (interfaceType != naturalType) return
                if (interfaceType.arguments.size != naturalContract.genericArity) return
                val indices = mutableListOf<Int>()
                for (argument in interfaceType.arguments) {
                    val projection = argument as? IrTypeProjection ?: return
                    if (projection.variance != Variance.INVARIANT || projection.type.isMarkedNullable()) return
                    val parameter = (projection.type as? IrSimpleType)?.classifier as? IrTypeParameterSymbol
                        ?: return
                    val index = owner.typeParameters.indexOfFirst { candidate ->
                        candidate.symbol == parameter && projection.type == candidate.defaultType
                    }.takeIf { candidate -> candidate >= 0 } ?: return
                    indices += index
                }
                targetIdentity = DotNetGenericOwnerPhysicalTypeDefIdentity.Local(
                    targetOwner.symbol,
                    DotNetGenericInterfaceView.DECLARED,
                )
                sourceParameterIndices = indices
            }
            targetOwner in capabilityOwners -> {
                if (interfaceType.arguments.isNotEmpty()) return
                targetIdentity = DotNetGenericOwnerPhysicalTypeDefIdentity.Local(
                    targetOwner.symbol,
                    view = null,
                )
                sourceParameterIndices = emptyList()
            }
            else -> return
        }
        recordedInterfaces += DotNetLocalGenericOwnerPhysicalInterfaceEdgeInput(
            targetIdentity,
            sourceParameterIndices,
        )
    }

    val sourceIdentity = DotNetGenericOwnerPhysicalTypeDefIdentity.Local(plan.owner.symbol, view = null)
    val edgePlan = DotNetLocalGenericOwnerPhysicalClassEdgePlan(sourceIdentity, recordedInterfaces)
    val plans = context.localGenericOwnerPhysicalClassEdgePlans
        ?: IdentityHashMap<IrClass, DotNetLocalGenericOwnerPhysicalClassEdgePlan>().also { created ->
            context.localGenericOwnerPhysicalClassEdgePlans = created
        }
    check(plans.put(owner, edgePlan) == null) {
        "Internal .NET backend error: one class received multiple physical edge selections"
    }
}

/**
 * Freezes the bounded local TypeDef/InterfaceImpl graph selected by the preceding lowerings.
 *
 * This rehearsal-only pass records the emitter-facing direct-interface input; it never walks the
 * planner's logical supertype snapshot and it never invents a construction from a destination
 * type. A source whose complete physical row set cannot be translated simply receives no edge
 * set, preserving the distinction between unavailable ancestry and a recorded empty set.
 */
internal class DotNetLocalGenericOwnerPhysicalAuthorityLowering(
    private val context: DotNetBackendContext,
) : ModuleLoweringPass {
    override fun lower(irModule: IrModuleFragment) {
        if (!context.configuration.dotNetGenericOwnerRehearsal) return
        val earlyAuthority = when (val binding = context.localGenericOwnerPhysicalAuthority) {
            is DotNetGenericOwnerPhysicalBindingResult.Bound -> binding.value
            is DotNetGenericOwnerPhysicalBindingResult.Conflict -> error(
                "Internal .NET backend error: ${binding.reason}",
            )
            DotNetGenericOwnerPhysicalBindingResult.Unavailable -> return
        }
        check(earlyAuthority.boundDeclarations == null) {
            "Internal .NET backend error: local physical authority was finalized twice"
        }

        val classInputs = context.genericOwnerArchitecturePlans.values
            .asSequence()
            .filter { plan ->
                plan.isReifiedByGenericOwnerRehearsal && plan.owner.kind == ClassKind.CLASS
            }
            .map { plan ->
                DotNetLocalGenericOwnerPhysicalTypeInput(
                    identity = DotNetGenericOwnerPhysicalTypeDefIdentity.Local(plan.owner.symbol, view = null),
                    logicalOwnerName = plan.owner.dotNetPhysicalValueStableName(),
                    genericArity = plan.owner.typeParameters.size,
                    role = DotNetLocalGenericOwnerPhysicalTypeRole.GENERIC_CLASS,
                )
            }
            .toList()
        val naturalInputs = context.reifiedGenericInterfaces.map { owner ->
            DotNetLocalGenericOwnerPhysicalTypeInput(
                identity = DotNetGenericOwnerPhysicalTypeDefIdentity.Local(
                    owner.symbol,
                    DotNetGenericInterfaceView.DECLARED,
                ),
                logicalOwnerName = owner.dotNetPhysicalValueStableName(),
                genericArity = owner.typeParameters.size,
                role = DotNetLocalGenericOwnerPhysicalTypeRole.NATURAL_INTERFACE,
            )
        }
        // A reused capability may describe several logical families. Until the snapshot carries
        // that set explicitly, omit the ambiguous TypeDef rather than choosing one logical name.
        val capabilityInputs = context.genericOwnerCapabilityInterfaces.entries
            .groupBy { entry -> entry.value.symbol }
            .mapNotNull { group ->
                val capabilitySymbol = group.key
                val entries = group.value
                val logicalOwners = entries.map { entry -> entry.key.symbol }.distinct()
                val logicalOwner = logicalOwners.singleOrNull() ?: return@mapNotNull null
                DotNetLocalGenericOwnerPhysicalTypeInput(
                    identity = DotNetGenericOwnerPhysicalTypeDefIdentity.Local(
                        capabilitySymbol,
                        view = null,
                    ),
                    logicalOwnerName = logicalOwner.owner.dotNetPhysicalValueStableName(),
                    genericArity = 0,
                    role = DotNetLocalGenericOwnerPhysicalTypeRole.SEMANTIC_CAPABILITY,
                )
            }
        val additionalInputs = (naturalInputs + capabilityInputs)
            .distinctBy(DotNetLocalGenericOwnerPhysicalTypeInput::identity)
        val inputsByIdentity = (classInputs + additionalInputs)
            .associateBy(DotNetLocalGenericOwnerPhysicalTypeInput::identity)
        val recordedPlans = context.localGenericOwnerPhysicalClassEdgePlans.orEmpty()

        val bound = earlyAuthority.advanceBound(additionalInputs) boundBuilder@{ declarations ->
            val edgeSets = mutableListOf<DotNetGenericOwnerPhysicalDirectSupertypeEdgeSet>()
            for (source in classInputs) {
                val recordedPlan = recordedPlans[source.identity.owner.owner] ?: continue
                when (val result = bindRecordedEdgeSetOrUnavailable(
                    recordedPlan,
                    inputsByIdentity,
                    declarations,
                )) {
                    is DotNetGenericOwnerPhysicalBindingResult.Bound -> edgeSets += result.value
                    is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return@boundBuilder result
                    DotNetGenericOwnerPhysicalBindingResult.Unavailable -> Unit
                }
            }
            val methodDefinitions = mutableListOf<DotNetGenericOwnerPhysicalMethodDefReference>()
            val callableFamilies = mutableListOf<DotNetLocalGenericOwnerPhysicalCallableFamilyInput>()
            val directProducerSelections = IdentityHashMap<IrSimpleFunction, DirectProducerCallableSelection>()
            for (entry in context.genericOwnerCapabilitySlots.entries) {
                val source = entry.key
                val semanticSlot = entry.value
                val selection = bindDirectProducerCallableOrNull(
                    source,
                    semanticSlot,
                    inputsByIdentity,
                    declarations,
                ) ?: continue
                methodDefinitions += selection.methodDefinitions
                callableFamilies += selection.callableFamily
                directProducerSelections[source] = selection
            }
            val completeEmissionFamilies =
                mutableListOf<DotNetLocalGenericOwnerPhysicalCompleteEmissionFamilyInput>()
            for (dispatcherSelection in
                context.localGenericOwnerPhysicalInterfaceCapabilityDispatcherSelections
            ) {
                val completeSelection = bindCompleteDirectProducerImplementationOrNull(
                    dispatcherSelection,
                    directProducerSelections,
                    inputsByIdentity,
                    declarations,
                ) ?: continue
                methodDefinitions += completeSelection.methodDefinitions
                edgeSets += completeSelection.edgeSets
                completeEmissionFamilies += completeSelection.family
            }
            DotNetGenericOwnerPhysicalBindingResult.Bound(
                DotNetLocalGenericOwnerPhysicalBoundInput(
                    methodDefinitions.distinct(),
                    callableFamilies,
                    edgeSets.distinct(),
                    completeEmissionFamilies,
                ),
            )
        }
        context.localGenericOwnerPhysicalAuthority = bound
        if (bound is DotNetGenericOwnerPhysicalBindingResult.Conflict) {
            error("Internal .NET backend error: ${bound.reason}")
        }
    }

    private data class DirectProducerCallableSelection(
        val methodDefinitions: List<DotNetGenericOwnerPhysicalMethodDefReference>,
        val callableFamily: DotNetLocalGenericOwnerPhysicalCallableFamilyInput,
    )

    private data class CompleteDirectProducerImplementationSelection(
        val methodDefinitions: List<DotNetGenericOwnerPhysicalMethodDefReference>,
        val edgeSets: List<DotNetGenericOwnerPhysicalDirectSupertypeEdgeSet>,
        val family: DotNetLocalGenericOwnerPhysicalCompleteEmissionFamilyInput,
    )

    /**
     * First callable grammar: one abstract, parameterless, direct owner-parameter producer.
     * Split-nullable and input-bearing contracts remain unbound until their later selection point.
     */
    private fun bindDirectProducerCallableOrNull(
        source: IrSimpleFunction,
        semanticSlot: IrSimpleFunction,
        inputsByIdentity: Map<
                DotNetGenericOwnerPhysicalTypeDefIdentity.Local,
                DotNetLocalGenericOwnerPhysicalTypeInput,
                >,
        declarations: org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalDeclarationIndex,
    ): DirectProducerCallableSelection? {
        val owner = source.parent as? IrClass ?: return null
        if (owner.kind != ClassKind.INTERFACE || owner !in context.reifiedGenericInterfaces) return null
        val contract = context.publishedGenericInterfaceFamilies[owner]
            ?.takeIf { family ->
                family.kind == DotNetPublishedGenericInterfaceFamilyKind.ROOT &&
                        family.capabilityBindingKind ==
                        DotNetPublishedGenericInterfaceCapabilityBindingKind.OWNED
            } ?: return null
        // This first callable grammar admits exactly one declared member. The source symbol and
        // its capability slot are already creation-site authority inside this compilation, so a
        // pre-lowering linkage key is not required for executable-only producers (which do not
        // publish library linkage keys). Multi-member grammars must bind an explicit recorded
        // member relation rather than rediscovering one from names.
        if (contract.declaredMembers.singleOrNull()?.role !=
            DotNetPublishedGenericInterfaceMemberRole.PRODUCER
        ) return null
        val resultParameterIndex = owner.typeParameters.indexOfFirst { parameter ->
            source.returnType == parameter.defaultType
        }.takeIf { index -> index >= 0 } ?: return null
        if (source.isSuspend ||
            source.typeParameters.isNotEmpty() ||
            source.parameters.size != 1 ||
            source.parameters.single().kind != IrParameterKind.DispatchReceiver ||
            source.visibility != DescriptorVisibilities.PUBLIC ||
            source.modality != Modality.ABSTRACT ||
            source.body != null
        ) return null

        val capabilityOwner = context.genericOwnerCapabilityInterfaces[owner] ?: return null
        if (semanticSlot.parent !== capabilityOwner ||
            semanticSlot.isSuspend ||
            semanticSlot.typeParameters.isNotEmpty() ||
            semanticSlot.parameters.size != 1 ||
            semanticSlot.parameters.single().kind != IrParameterKind.DispatchReceiver ||
            !semanticSlot.returnType.isNullableAny() ||
            semanticSlot.visibility != DescriptorVisibilities.PUBLIC ||
            semanticSlot.modality != Modality.ABSTRACT ||
            semanticSlot.body != null
        ) return null

        val naturalOwnerIdentity = DotNetGenericOwnerPhysicalTypeDefIdentity.Local(
            owner.symbol,
            DotNetGenericInterfaceView.DECLARED,
        )
        val semanticOwnerIdentity = DotNetGenericOwnerPhysicalTypeDefIdentity.Local(
            capabilityOwner.symbol,
            view = null,
        )
        if (inputsByIdentity[naturalOwnerIdentity]?.role !=
            DotNetLocalGenericOwnerPhysicalTypeRole.NATURAL_INTERFACE ||
            inputsByIdentity[semanticOwnerIdentity]?.role !=
            DotNetLocalGenericOwnerPhysicalTypeRole.SEMANTIC_CAPABILITY
        ) return null
        val naturalResultCarrier = when (val binding = declarations.typeParameterOrError(
            naturalOwnerIdentity,
            resultParameterIndex,
        )) {
            is DotNetGenericOwnerPhysicalBindingResult.Bound -> binding.value
            is DotNetGenericOwnerPhysicalBindingResult.Conflict,
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            -> return null
        }
        val naturalMethodIdentity = DotNetGenericOwnerPhysicalMethodDefIdentity.Local(
            source.symbol,
            DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY,
        )
        val semanticMethodIdentity = DotNetGenericOwnerPhysicalMethodDefIdentity.Local(
            semanticSlot.symbol,
            // The public abstract capability-interface slot is not the private-final class
            // CAPABILITY_DISPATCHER member. Its generated IR symbol is already exact identity.
            role = null,
        )
        fun method(
            identity: DotNetGenericOwnerPhysicalMethodDefIdentity,
            declaringType: DotNetGenericOwnerPhysicalTypeDefIdentity,
            resultCarrier: DotNetGenericOwnerSymbolicCarrierReference,
        ) = DotNetGenericOwnerPhysicalMethodDefReference(
            identity = identity,
            declaringType = declaringType,
            visibility = DotNetGenericOwnerPhysicalMemberVisibility.PUBLIC,
            dispatch = DotNetGenericOwnerPhysicalMemberDispatch.ABSTRACT,
            signature = DotNetGenericOwnerPhysicalMethodSignatureReference(
                isInstance = true,
                genericArity = 0,
                resultLayout = DotNetGenericOwnerPhysicalCallableResultLayoutReference.Direct(
                    DotNetGenericOwnerPhysicalCallableValueSlotReference(
                        DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_OUTPUT,
                        resultCarrier,
                    ),
                ),
                parameterSlots = emptyList(),
            ),
        )
        return DirectProducerCallableSelection(
            methodDefinitions = listOf(
                method(naturalMethodIdentity, naturalOwnerIdentity, naturalResultCarrier),
                method(
                    semanticMethodIdentity,
                    semanticOwnerIdentity,
                    DotNetGenericOwnerSymbolicCarrierReference.objectCarrier(),
                ),
            ),
            callableFamily = DotNetLocalGenericOwnerPhysicalCallableFamilyInput(
                source.symbol,
                semanticSlot.symbol,
            ),
        )
    }

    /**
     * First non-empty complete liveness grammar: one final generic class directly implements one
     * root, output-only reified interface producer. The restriction is intentionally structural
     * and temporary; it gives the complete-set algebra real TypeDef edges and MethodImpl rows
     * without pretending that unrelated members of the implementation class belong to this
     * logical member family.
     */
    private fun bindCompleteDirectProducerImplementationOrNull(
        selection: DotNetLocalGenericOwnerPhysicalInterfaceCapabilityDispatcherSelection,
        directProducerSelections: IdentityHashMap<IrSimpleFunction, DirectProducerCallableSelection>,
        inputsByIdentity: Map<
                DotNetGenericOwnerPhysicalTypeDefIdentity.Local,
                DotNetLocalGenericOwnerPhysicalTypeInput,
                >,
        declarations: org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalDeclarationIndex,
    ): CompleteDirectProducerImplementationSelection? {
        val logicalMember = selection.logicalInterfaceMember.owner
        val directProducer = directProducerSelections[logicalMember] ?: return null
        val interfaceCapabilityMember = selection.interfaceCapabilityMember.owner
        if (directProducer.callableFamily.logicalMember !== logicalMember.symbol ||
            directProducer.callableFamily.semanticCapabilityMember !== interfaceCapabilityMember.symbol
        ) return null

        val implementation = selection.implementationMember.owner
        val interfaceDispatcher = selection.dispatcher.owner
        val logicalOwner = logicalMember.parent as? IrClass ?: return null
        val implementationOwner = implementation.parent as? IrClass ?: return null
        val interfaceCapabilityOwner = interfaceCapabilityMember.parent as? IrClass ?: return null
        val classCapabilityOwner = context.genericOwnerCapabilityInterfaces[implementationOwner] ?: return null
        val classCapabilityMember = context.genericOwnerCapabilitySlots[implementation] ?: return null
        val classDispatcher = context.genericOwnerCapabilityDispatchers[implementation] ?: return null
        if (classCapabilityMember.parent !== classCapabilityOwner ||
            interfaceDispatcher.parent !== implementationOwner ||
            classDispatcher.parent !== implementationOwner ||
            context.genericOwnerSemanticHooks[implementation] != null ||
            logicalOwner.typeParameters.size != 1 ||
            implementationOwner.typeParameters.size != 1 ||
            logicalOwner.typeParameters.single().variance != Variance.OUT_VARIANCE ||
            implementationOwner.typeParameters.single().variance != Variance.INVARIANT ||
            logicalOwner.typeParameters.single().superTypes.any { bound ->
                !bound.isAny() && !bound.isNullableAny()
            } ||
            implementationOwner.typeParameters.single().superTypes.any { bound ->
                !bound.isAny() && !bound.isNullableAny()
            } ||
            logicalOwner.dotNetDirectInterfaceTypes().isNotEmpty() ||
            interfaceCapabilityOwner.dotNetDirectInterfaceTypes().isNotEmpty() ||
            classCapabilityOwner.dotNetDirectInterfaceTypes().singleOrNull()
                ?.classifier != interfaceCapabilityOwner.symbol ||
            implementationOwner.modality != Modality.FINAL ||
            implementation.visibility != DescriptorVisibilities.PUBLIC ||
            implementation.parameters.size != 1 ||
            implementation.parameters.single().kind != IrParameterKind.DispatchReceiver ||
            implementation.returnType != implementationOwner.typeParameters.single().defaultType ||
            logicalMember !in implementation.overriddenSymbols.map { overridden -> overridden.owner } ||
            classDispatcher.overriddenSymbols.singleOrNull()?.owner !== classCapabilityMember ||
            interfaceDispatcher.overriddenSymbols.singleOrNull()?.owner !== interfaceCapabilityMember ||
            classCapabilityMember.visibility != DescriptorVisibilities.PUBLIC ||
            classCapabilityMember.modality != Modality.ABSTRACT ||
            classCapabilityMember.body != null ||
            classDispatcher.visibility != DescriptorVisibilities.PRIVATE ||
            classDispatcher.modality != Modality.FINAL ||
            interfaceDispatcher.visibility != DescriptorVisibilities.PRIVATE ||
            interfaceDispatcher.modality != Modality.FINAL ||
            listOf(classCapabilityMember, classDispatcher, interfaceDispatcher).any { member ->
                member.isSuspend || member.typeParameters.isNotEmpty() || member.parameters.size != 1 ||
                        member.parameters.single().kind != IrParameterKind.DispatchReceiver ||
                        !member.returnType.isNullableAny()
            }
        ) return null

        val naturalType = DotNetGenericOwnerPhysicalTypeDefIdentity.Local(
            logicalOwner.symbol,
            DotNetGenericInterfaceView.DECLARED,
        )
        val interfaceCapabilityType = DotNetGenericOwnerPhysicalTypeDefIdentity.Local(
            interfaceCapabilityOwner.symbol,
            view = null,
        )
        val implementationType = DotNetGenericOwnerPhysicalTypeDefIdentity.Local(
            implementationOwner.symbol,
            view = null,
        )
        val classCapabilityType = DotNetGenericOwnerPhysicalTypeDefIdentity.Local(
            classCapabilityOwner.symbol,
            view = null,
        )
        val types = linkedMapOf(
            DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind.NATURAL_INTERFACE to naturalType,
            DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind.INTERFACE_SEMANTIC_CAPABILITY to
                    interfaceCapabilityType,
            DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind.IMPLEMENTATION_CLASS to
                    implementationType,
            DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind.CLASS_SEMANTIC_CAPABILITY to
                    classCapabilityType,
        )
        if (types.values.any { identity -> identity !in inputsByIdentity }) return null
        val typeAliases = types.mapValuesTo(linkedMapOf()) { entry ->
            if (entry.key == DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind.NATURAL_INTERFACE) {
                listOf(
                    DotNetGenericOwnerPhysicalTypeDefIdentity.Local(
                        logicalOwner.symbol,
                        DotNetGenericInterfaceView.CANONICAL,
                    ),
                    entry.value,
                )
            } else {
                listOf(entry.value)
            }
        }
        val typeParameters = linkedMapOf(
            DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind.NATURAL_INTERFACE to listOf(
                DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeParameterReference(
                    DotNetGenericOwnerPhysicalTypeParameterVariance.COVARIANT,
                    constraints = emptyList(),
                ),
            ),
            DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind.INTERFACE_SEMANTIC_CAPABILITY to
                    emptyList(),
            DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind.IMPLEMENTATION_CLASS to listOf(
                DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeParameterReference(
                    DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT,
                    constraints = emptyList(),
                ),
            ),
            DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind.CLASS_SEMANTIC_CAPABILITY to
                    emptyList(),
        )

        val implementationParameter = when (val binding = declarations.typeParameterOrError(
            implementationType,
            0,
        )) {
            is DotNetGenericOwnerPhysicalBindingResult.Bound -> binding.value
            is DotNetGenericOwnerPhysicalBindingResult.Conflict,
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            -> return null
        }
        fun directResult(carrier: DotNetGenericOwnerSymbolicCarrierReference) =
            DotNetGenericOwnerPhysicalCallableResultLayoutReference.Direct(
                DotNetGenericOwnerPhysicalCallableValueSlotReference(
                    DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_OUTPUT,
                    carrier,
                ),
            )
        fun method(
            identity: DotNetGenericOwnerPhysicalMethodDefIdentity.Local,
            declaringType: DotNetGenericOwnerPhysicalTypeDefIdentity.Local,
            visibility: DotNetGenericOwnerPhysicalMemberVisibility,
            dispatch: DotNetGenericOwnerPhysicalMemberDispatch,
            resultCarrier: DotNetGenericOwnerSymbolicCarrierReference,
        ) = DotNetGenericOwnerPhysicalMethodDefReference(
            identity = identity,
            declaringType = declaringType,
            visibility = visibility,
            dispatch = dispatch,
            signature = DotNetGenericOwnerPhysicalMethodSignatureReference(
                isInstance = true,
                genericArity = 0,
                resultLayout = directResult(resultCarrier),
                parameterSlots = emptyList(),
            ),
        )

        val naturalIdentity = DotNetGenericOwnerPhysicalMethodDefIdentity.Local(
            logicalMember.symbol,
            DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY,
        )
        val interfaceCapabilityIdentity = DotNetGenericOwnerPhysicalMethodDefIdentity.Local(
            interfaceCapabilityMember.symbol,
            role = null,
        )
        val implementationIdentity = DotNetGenericOwnerPhysicalMethodDefIdentity.Local(
            implementation.symbol,
            DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY,
        )
        val classCapabilityIdentity = DotNetGenericOwnerPhysicalMethodDefIdentity.Local(
            classCapabilityMember.symbol,
            role = null,
        )
        val classDispatcherIdentity = DotNetGenericOwnerPhysicalMethodDefIdentity.Local(
            implementation.symbol,
            DotNetGenericOwnerMemberFamilyRole.CAPABILITY_DISPATCHER,
        )
        val interfaceDispatcherIdentity = DotNetGenericOwnerPhysicalMethodDefIdentity.Local(
            interfaceDispatcher.symbol,
            role = null,
        )
        val objectCarrier = DotNetGenericOwnerSymbolicCarrierReference.objectCarrier()
        val additionalMethods = listOf(
            method(
                implementationIdentity,
                implementationType,
                DotNetGenericOwnerPhysicalMemberVisibility.PUBLIC,
                DotNetGenericOwnerPhysicalMemberDispatch.OVERRIDABLE,
                implementationParameter,
            ),
            method(
                classCapabilityIdentity,
                classCapabilityType,
                DotNetGenericOwnerPhysicalMemberVisibility.PUBLIC,
                DotNetGenericOwnerPhysicalMemberDispatch.ABSTRACT,
                objectCarrier,
            ),
            method(
                classDispatcherIdentity,
                implementationType,
                DotNetGenericOwnerPhysicalMemberVisibility.PRIVATE,
                DotNetGenericOwnerPhysicalMemberDispatch.FINAL,
                objectCarrier,
            ),
            method(
                interfaceDispatcherIdentity,
                implementationType,
                DotNetGenericOwnerPhysicalMemberVisibility.PRIVATE,
                DotNetGenericOwnerPhysicalMemberDispatch.FINAL,
                objectCarrier,
            ),
        )
        val methodInputs = listOf(
            DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodInput(
                DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodKind.NATURAL_INTERFACE_SLOT,
                logicalMember.symbol,
                naturalIdentity,
            ),
            DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodInput(
                DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodKind.INTERFACE_SEMANTIC_CAPABILITY_SLOT,
                interfaceCapabilityMember.symbol,
                interfaceCapabilityIdentity,
            ),
            DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodInput(
                DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodKind.IMPLEMENTATION_TYPED_ENTRY,
                implementation.symbol,
                implementationIdentity,
            ),
            DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodInput(
                DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodKind.CLASS_SEMANTIC_CAPABILITY_SLOT,
                classCapabilityMember.symbol,
                classCapabilityIdentity,
            ),
            DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodInput(
                DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodKind.CLASS_SEMANTIC_CAPABILITY_DISPATCHER,
                classDispatcher.symbol,
                classDispatcherIdentity,
            ),
            DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodInput(
                DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodKind.INTERFACE_SEMANTIC_CAPABILITY_DISPATCHER,
                interfaceDispatcher.symbol,
                interfaceDispatcherIdentity,
            ),
        )
        fun nonGenericConstruction(type: DotNetGenericOwnerPhysicalTypeDefIdentity.Local) =
            when (val binding = declarations.constructTypeOrError(type, emptyList())) {
                is DotNetGenericOwnerPhysicalBindingResult.Bound -> binding.value
                is DotNetGenericOwnerPhysicalBindingResult.Conflict,
                DotNetGenericOwnerPhysicalBindingResult.Unavailable,
                -> null
            }
        val interfaceCapabilityConstruction = nonGenericConstruction(interfaceCapabilityType)
            ?: return null
        val classCapabilityConstruction = nonGenericConstruction(classCapabilityType)
            ?: return null
        val methodImpls = listOf(
            DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodImplReference(
                DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodImplKind.CLASS_SEMANTIC_CAPABILITY_IMPLEMENTATION,
                implementationType,
                classDispatcherIdentity,
                classCapabilityConstruction,
                classCapabilityIdentity,
            ),
            DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodImplReference(
                DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodImplKind.INTERFACE_SEMANTIC_CAPABILITY_IMPLEMENTATION,
                implementationType,
                interfaceDispatcherIdentity,
                interfaceCapabilityConstruction,
                interfaceCapabilityIdentity,
            ),
        )
        val edgeSets = listOf(
            DotNetGenericOwnerPhysicalDirectSupertypeEdgeSet(naturalType, emptyList()),
            DotNetGenericOwnerPhysicalDirectSupertypeEdgeSet(interfaceCapabilityType, emptyList()),
            DotNetGenericOwnerPhysicalDirectSupertypeEdgeSet(
                classCapabilityType,
                listOf(DotNetGenericOwnerPhysicalDirectSupertypeEdgeReference(
                    DotNetGenericOwnerDirectSupertypeKind.INTERFACE,
                    interfaceCapabilityConstruction,
                )),
            ),
        )
        return CompleteDirectProducerImplementationSelection(
            methodDefinitions = directProducer.methodDefinitions + additionalMethods,
            edgeSets = edgeSets,
            family = DotNetLocalGenericOwnerPhysicalCompleteEmissionFamilyInput(
                logicalMember.symbol,
                implementation.symbol,
                types,
                typeAliases,
                typeParameters,
                methodInputs,
                methodImpls,
            ),
        )
    }

    private fun bindRecordedEdgeSetOrUnavailable(
        plan: DotNetLocalGenericOwnerPhysicalClassEdgePlan,
        inputsByIdentity: Map<
                DotNetGenericOwnerPhysicalTypeDefIdentity.Local,
                DotNetLocalGenericOwnerPhysicalTypeInput,
                >,
        declarations: org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalDeclarationIndex,
    ): DotNetGenericOwnerPhysicalBindingResult<DotNetGenericOwnerPhysicalDirectSupertypeEdgeSet> {
        val source = inputsByIdentity[plan.source]
            ?.takeIf { input -> input.role == DotNetLocalGenericOwnerPhysicalTypeRole.GENERIC_CLASS }
            ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
        val edges = mutableListOf(
            DotNetGenericOwnerPhysicalDirectSupertypeEdgeReference(
                DotNetGenericOwnerDirectSupertypeKind.BASE_CLASS,
                DotNetGenericOwnerSymbolicCarrierReference.objectCarrier(),
            ),
        )
        for (interfaceEdge in plan.interfaces) {
            val target = inputsByIdentity[interfaceEdge.target]
                ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            if (target.role == DotNetLocalGenericOwnerPhysicalTypeRole.GENERIC_CLASS) {
                return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                    "a physical InterfaceImpl target was classified as a class",
                )
            }
            if (interfaceEdge.sourceParameterIndices.size != target.genericArity) {
                return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                    "a recorded InterfaceImpl argument count contradicts its selected TypeDef arity",
                )
            }
            val targetArguments = mutableListOf<DotNetGenericOwnerSymbolicCarrierReference>()
            for (parameterIndex in interfaceEdge.sourceParameterIndices) {
                when (val result = declarations.typeParameterOrError(source.identity, parameterIndex)) {
                    is DotNetGenericOwnerPhysicalBindingResult.Bound -> targetArguments += result.value
                    is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                        return DotNetGenericOwnerPhysicalBindingResult.Conflict(result.reason)
                    DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                        return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                }
            }
            val targetConstruction = when (val result = declarations.constructTypeOrError(
                target.identity,
                targetArguments,
            )) {
                is DotNetGenericOwnerPhysicalBindingResult.Bound -> result.value
                is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                    return DotNetGenericOwnerPhysicalBindingResult.Conflict(result.reason)
                DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                    return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            }
            edges += DotNetGenericOwnerPhysicalDirectSupertypeEdgeReference(
                DotNetGenericOwnerDirectSupertypeKind.INTERFACE,
                targetConstruction,
            )
        }
        return DotNetGenericOwnerPhysicalBindingResult.Bound(
            DotNetGenericOwnerPhysicalDirectSupertypeEdgeSet(plan.source, edges),
        )
    }
}
