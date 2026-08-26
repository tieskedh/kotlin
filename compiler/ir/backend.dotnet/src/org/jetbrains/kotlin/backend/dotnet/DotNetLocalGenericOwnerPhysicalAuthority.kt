/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.defaultType

/** Bounded compilation-local role of one selected CLR TypeDef. */
internal enum class DotNetLocalGenericOwnerPhysicalTypeRole {
    GENERIC_CLASS,
    NATURAL_INTERFACE,
    SEMANTIC_CAPABILITY,
}

/**
 * One physical TypeDef selected by lowering, plus only the diagnostic name needed by the shadow.
 * The name does not participate in identity, ancestry, construction, or placement decisions.
 */
internal data class DotNetLocalGenericOwnerPhysicalTypeInput(
    val identity: DotNetGenericOwnerPhysicalTypeDefIdentity.Local,
    val logicalOwnerName: String,
    val genericArity: Int,
    val role: DotNetLocalGenericOwnerPhysicalTypeRole,
) {
    init {
        require(logicalOwnerName.isNotEmpty() && when (role) {
            DotNetLocalGenericOwnerPhysicalTypeRole.GENERIC_CLASS ->
                identity.view == null && genericArity > 0
            DotNetLocalGenericOwnerPhysicalTypeRole.NATURAL_INTERFACE ->
                identity.view == DotNetGenericInterfaceView.DECLARED && genericArity > 0
            DotNetLocalGenericOwnerPhysicalTypeRole.SEMANTIC_CAPABILITY ->
                identity.view == null && genericArity == 0
        }) { "a local physical TypeDef input has an incoherent role, identity, or arity" }
    }

    val category: DotNetGenericOwnerPhysicalNamedTypeCategory
        get() = when (role) {
            DotNetLocalGenericOwnerPhysicalTypeRole.GENERIC_CLASS ->
                DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS
            DotNetLocalGenericOwnerPhysicalTypeRole.NATURAL_INTERFACE,
            DotNetLocalGenericOwnerPhysicalTypeRole.SEMANTIC_CAPABILITY,
            -> DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE
        }

    fun asReference() = DotNetGenericOwnerPhysicalTypeDefReference(
        identity = identity,
        genericArity = genericArity,
        category = category,
    )
}

/** One admitted InterfaceImpl target expressed only in the source TypeDef's physical parameters. */
internal data class DotNetLocalGenericOwnerPhysicalInterfaceEdgeInput(
    val target: DotNetGenericOwnerPhysicalTypeDefIdentity.Local,
    val sourceParameterIndices: List<Int>,
) {
    init {
        require(sourceParameterIndices.all { index -> index >= 0 }) {
            "a local physical InterfaceImpl edge requires non-negative source-parameter mappings"
        }
    }
}

/**
 * Complete bounded BaseType/InterfaceImpl selection for one local generic class.
 *
 * The current slice admits only the canonical System.Object base. Absence of this record is
 * unavailable authority; it must never be interpreted as a recorded empty interface list.
 */
internal data class DotNetLocalGenericOwnerPhysicalClassEdgePlan(
    val source: DotNetGenericOwnerPhysicalTypeDefIdentity.Local,
    val interfaces: List<DotNetLocalGenericOwnerPhysicalInterfaceEdgeInput>,
) {
    init {
        require(source.view == null && interfaces.distinct().size == interfaces.size) {
            "a local physical class edge plan requires one source and unique InterfaceImpl rows"
        }
    }
}

internal data class DotNetLocalGenericOwnerPhysicalCallableFamilyInput(
    val logicalMember: IrSimpleFunctionSymbol,
    val semanticCapabilityMember: IrSimpleFunctionSymbol,
)

internal enum class DotNetLocalGenericOwnerPhysicalCallableEntryKind {
    NATURAL_INTERFACE,
    SEMANTIC_CAPABILITY_INTERFACE_SLOT,
}

/**
 * Opaque logical-member-to-MethodDef relation admitted only by the BOUND local authority.
 * Physical operation proof consumes one already selected endpoint and cannot invent a family.
 */
internal class DotNetLocalGenericOwnerPhysicalCallableFamily private constructor(
    private val naturalMethod: DotNetGenericOwnerPhysicalMethodDefIdentity,
    private val semanticCapabilityMethod: DotNetGenericOwnerPhysicalMethodDefIdentity,
) {
    fun selectedMethod(kind: DotNetLocalGenericOwnerPhysicalCallableEntryKind):
            DotNetGenericOwnerPhysicalMethodDefIdentity = when (kind) {
        DotNetLocalGenericOwnerPhysicalCallableEntryKind.NATURAL_INTERFACE -> naturalMethod
        DotNetLocalGenericOwnerPhysicalCallableEntryKind.SEMANTIC_CAPABILITY_INTERFACE_SLOT ->
            semanticCapabilityMethod
    }

    companion object {
        fun bindDirectProducerOrError(
            input: DotNetLocalGenericOwnerPhysicalCallableFamilyInput,
            declarations: DotNetGenericOwnerPhysicalDeclarationIndex,
            inputsByIdentity: Map<
                    DotNetGenericOwnerPhysicalTypeDefIdentity.Local,
                    DotNetLocalGenericOwnerPhysicalTypeInput,
                    >,
        ): DotNetGenericOwnerPhysicalBindingResult<DotNetLocalGenericOwnerPhysicalCallableFamily> {
            val logicalMember = input.logicalMember.owner
            val logicalOwner = logicalMember.parent as? IrClass
                ?: return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                    "a local callable family requires an interface-owned logical member",
                )
            val semanticMember = input.semanticCapabilityMember.owner
            val semanticOwner = semanticMember.parent as? IrClass
                ?: return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                    "a local callable family requires an interface-owned capability member",
                )
            if (logicalMember.isSuspend || semanticMember.isSuspend) {
                return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                    "a suspend callable cannot use the ordinary direct-producer MethodDef grammar",
                )
            }
            val naturalOwnerIdentity = DotNetGenericOwnerPhysicalTypeDefIdentity.Local(
                logicalOwner.symbol,
                DotNetGenericInterfaceView.DECLARED,
            )
            val semanticOwnerIdentity = DotNetGenericOwnerPhysicalTypeDefIdentity.Local(
                semanticOwner.symbol,
                view = null,
            )
            if (inputsByIdentity[naturalOwnerIdentity]?.role !=
                DotNetLocalGenericOwnerPhysicalTypeRole.NATURAL_INTERFACE ||
                inputsByIdentity[semanticOwnerIdentity]?.role !=
                DotNetLocalGenericOwnerPhysicalTypeRole.SEMANTIC_CAPABILITY
            ) {
                return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                    "a local callable family must use its selected natural and capability TypeDefs",
                )
            }
            val resultParameterIndex = logicalOwner.typeParameters.indexOfFirst { parameter ->
                logicalMember.returnType == parameter.defaultType
            }.takeIf { index -> index >= 0 }
                ?: return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                    "the bounded direct-producer family must return one exact owner parameter",
                )
            val naturalIdentity = DotNetGenericOwnerPhysicalMethodDefIdentity.Local(
                input.logicalMember,
                DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY,
            )
            val semanticIdentity = DotNetGenericOwnerPhysicalMethodDefIdentity.Local(
                input.semanticCapabilityMember,
                role = null,
            )
            if (naturalIdentity == semanticIdentity) {
                return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                    "natural and semantic callable entries require distinct MethodDefs",
                )
            }
            val natural = declarations.methodDescriptionOrNull(naturalIdentity)
                ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            val semantic = declarations.methodDescriptionOrNull(semanticIdentity)
                ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            fun hasDirectProducerShape(
                method: DotNetGenericOwnerPhysicalMethodDefReference,
                owner: DotNetGenericOwnerPhysicalTypeDefIdentity.Local,
            ): Boolean = method.declaringType == owner &&
                    method.visibility == DotNetGenericOwnerPhysicalMemberVisibility.PUBLIC &&
                    method.dispatch == DotNetGenericOwnerPhysicalMemberDispatch.ABSTRACT &&
                    method.signature.isInstance &&
                    method.signature.genericArity == 0 &&
                    method.signature.parameterSlots.isEmpty()
            if (!hasDirectProducerShape(natural, naturalOwnerIdentity) ||
                !hasDirectProducerShape(semantic, semanticOwnerIdentity)
            ) {
                return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                    "a bounded local callable family must contain public abstract instance producer slots",
                )
            }
            val naturalResult = natural.signature.resultLayout as?
                    DotNetGenericOwnerPhysicalCallableResultLayoutReference.Direct
                ?: return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                    "the bounded natural producer requires a direct result",
                )
            val semanticResult = semantic.signature.resultLayout as?
                    DotNetGenericOwnerPhysicalCallableResultLayoutReference.Direct
                ?: return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                    "the bounded semantic producer requires a direct result",
                )
            val expectedNaturalCarrier = when (val binding = declarations.typeParameterOrError(
                naturalOwnerIdentity,
                resultParameterIndex,
            )) {
                is DotNetGenericOwnerPhysicalBindingResult.Bound -> binding.value
                is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                    return DotNetGenericOwnerPhysicalBindingResult.Conflict(binding.reason)
                DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                    return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            }
            if (naturalResult.slot.domain != DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_OUTPUT ||
                naturalResult.slot.carrier != expectedNaturalCarrier ||
                semanticResult.slot.domain != DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_OUTPUT ||
                semanticResult.slot.carrier != DotNetGenericOwnerSymbolicCarrierReference.objectCarrier()
            ) {
                return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                    "a bounded local producer family has incompatible natural or semantic result authority",
                )
            }
            return DotNetGenericOwnerPhysicalBindingResult.Bound(
                DotNetLocalGenericOwnerPhysicalCallableFamily(naturalIdentity, semanticIdentity),
            )
        }
    }
}

/** All declaration facts selected together for the one BOUND authority epoch. */
internal class DotNetLocalGenericOwnerPhysicalBoundInput(
    methodDefinitions: Iterable<DotNetGenericOwnerPhysicalMethodDefReference>,
    callableFamilies: Iterable<DotNetLocalGenericOwnerPhysicalCallableFamilyInput>,
    directSupertypeEdgeSets: Iterable<DotNetGenericOwnerPhysicalDirectSupertypeEdgeSet>,
) {
    val methodDefinitions = methodDefinitions.toList()
    val callableFamilies = callableFamilies.toList()
    val directSupertypeEdgeSets = directSupertypeEdgeSets.toList()
}

/**
 * One context-owned declaration-authority lineage for compilation-local generic owners.
 *
 * PRE analysis consumes [earlyDeclarations]. Later lowering advances that same immutable input to
 * [boundDeclarations]. Value flow may choose an epoch but can never advance or mutate one. The
 * emitter remains independent during this production-inert migration; a later checkpoint must
 * structurally cross-check or consume this index before it can become authoritative codegen data.
 */
internal class DotNetLocalGenericOwnerPhysicalAuthority private constructor(
    val earlyDeclarations: DotNetGenericOwnerPhysicalDeclarationIndex,
    val boundDeclarations: DotNetGenericOwnerPhysicalDeclarationIndex?,
    inputsByIdentity: Map<DotNetGenericOwnerPhysicalTypeDefIdentity.Local, DotNetLocalGenericOwnerPhysicalTypeInput>,
    callableFamiliesByLogicalMember:
            Map<IrSimpleFunctionSymbol, DotNetLocalGenericOwnerPhysicalCallableFamily>,
) {
    private val inputsByIdentity = inputsByIdentity.toMap()
    private val callableFamiliesByLogicalMember = callableFamiliesByLogicalMember.toMap()

    fun inputOrNull(
        identity: DotNetGenericOwnerPhysicalTypeDefIdentity.Local,
    ): DotNetLocalGenericOwnerPhysicalTypeInput? = inputsByIdentity[identity]

    fun genericClassIdentityOrNull(owner: IrClassSymbol): DotNetGenericOwnerPhysicalTypeDefIdentity.Local? =
        DotNetGenericOwnerPhysicalTypeDefIdentity.Local(owner, view = null)
            .takeIf { identity -> inputsByIdentity[identity]?.role == DotNetLocalGenericOwnerPhysicalTypeRole.GENERIC_CLASS }

    fun naturalInterfaceIdentityOrNull(owner: IrClassSymbol): DotNetGenericOwnerPhysicalTypeDefIdentity.Local? =
        DotNetGenericOwnerPhysicalTypeDefIdentity.Local(owner, DotNetGenericInterfaceView.DECLARED)
            .takeIf { identity -> inputsByIdentity[identity]?.role == DotNetLocalGenericOwnerPhysicalTypeRole.NATURAL_INTERFACE }

    fun callableMethodOrNull(
        logicalMember: IrSimpleFunctionSymbol,
        kind: DotNetLocalGenericOwnerPhysicalCallableEntryKind,
    ): DotNetGenericOwnerPhysicalMethodDefIdentity? =
        callableFamiliesByLogicalMember[logicalMember]?.selectedMethod(kind)

    /**
     * Compares only the opaque families already admitted by BOUND authority with one successful
     * final emitter scope. This is diagnostic and deliberately does not advance or mutate the
     * declaration index: absent emission evidence must remain absent rather than inheriting the
     * corresponding BOUND MethodDef through the index's additive epoch transition.
     */
    fun compareFinalMethodDefHeaders(
        scope: DotNetIlEmissionScope,
        observations: List<DotNetGenericOwnerPhysicalMethodDefHeaderObservation>,
        otherScopeObservations: List<DotNetGenericOwnerPhysicalMethodDefHeaderObservation> = emptyList(),
    ): List<DotNetGenericOwnerPhysicalMethodDefEmissionFamilyComparisonSnapshot> =
        callableFamiliesByLogicalMember.entries.mapNotNull { entry ->
            compareDotNetGenericOwnerPhysicalMethodDefEmissionFamily(
                authority = this,
                scope = scope,
                logicalMember = entry.key,
                family = entry.value,
                observations = observations,
                otherScopeObservations = otherScopeObservations,
            )
        }.sortedWith(compareBy(
            DotNetGenericOwnerPhysicalMethodDefEmissionFamilyComparisonSnapshot::ownerName,
            DotNetGenericOwnerPhysicalMethodDefEmissionFamilyComparisonSnapshot::logicalMemberName,
        ))

    fun advanceBound(
        additionalInputs: Iterable<DotNetLocalGenericOwnerPhysicalTypeInput>,
        buildBoundInput: (
            DotNetGenericOwnerPhysicalDeclarationIndex,
        ) -> DotNetGenericOwnerPhysicalBindingResult<DotNetLocalGenericOwnerPhysicalBoundInput>,
    ): DotNetGenericOwnerPhysicalBindingResult<DotNetLocalGenericOwnerPhysicalAuthority> {
        if (boundDeclarations != null) {
            return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                "local physical declaration authority was bound more than once",
            )
        }
        val stableAdditionalInputs = additionalInputs.toList()
        val mergedInputs = linkedMapOf<
                DotNetGenericOwnerPhysicalTypeDefIdentity.Local,
                DotNetLocalGenericOwnerPhysicalTypeInput,
                >().apply {
            putAll(inputsByIdentity)
            for (candidate in stableAdditionalInputs) {
                val existing = putIfAbsent(candidate.identity, candidate)
                if (existing != null && existing != candidate) {
                    return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                        "one local physical TypeDef received conflicting role descriptions",
                    )
                }
            }
        }
        val additionalReferences = stableAdditionalInputs.map(DotNetLocalGenericOwnerPhysicalTypeInput::asReference)
        val provisional = when (val binding = earlyDeclarations.advance(
            nextEpoch = DotNetGenericOwnerPhysicalAuthorityEpoch.BOUND_DECLARATION_INDEX,
            typeDefinitions = additionalReferences,
            methodDefinitions = emptyList(),
        )) {
            is DotNetGenericOwnerPhysicalBindingResult.Bound -> binding.value
            is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                return DotNetGenericOwnerPhysicalBindingResult.Conflict(binding.reason)
            DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                return DotNetGenericOwnerPhysicalBindingResult.Unavailable
        }
        val boundInput = when (val binding = buildBoundInput(provisional)) {
            is DotNetGenericOwnerPhysicalBindingResult.Bound -> binding.value
            is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                return DotNetGenericOwnerPhysicalBindingResult.Conflict(binding.reason)
            DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                return DotNetGenericOwnerPhysicalBindingResult.Unavailable
        }
        val bound = when (val binding = earlyDeclarations.advance(
            nextEpoch = DotNetGenericOwnerPhysicalAuthorityEpoch.BOUND_DECLARATION_INDEX,
            typeDefinitions = additionalReferences,
            methodDefinitions = boundInput.methodDefinitions,
            directSupertypeEdgeSets = boundInput.directSupertypeEdgeSets,
        )) {
            is DotNetGenericOwnerPhysicalBindingResult.Bound -> binding.value
            is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                return DotNetGenericOwnerPhysicalBindingResult.Conflict(binding.reason)
            DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                return DotNetGenericOwnerPhysicalBindingResult.Unavailable
        }
        val mergedCallableFamilies = linkedMapOf<
                IrSimpleFunctionSymbol,
                DotNetLocalGenericOwnerPhysicalCallableFamily,
                >()
        mergedCallableFamilies.putAll(callableFamiliesByLogicalMember)
        val seenCandidates = linkedSetOf<IrSimpleFunctionSymbol>()
        for (candidate in boundInput.callableFamilies) {
            if (!seenCandidates.add(candidate.logicalMember) ||
                mergedCallableFamilies.containsKey(candidate.logicalMember)
            ) {
                return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                    "one logical callable received duplicate physical MethodDef family authority",
                )
            }
            val family = when (val binding =
                DotNetLocalGenericOwnerPhysicalCallableFamily.bindDirectProducerOrError(
                    candidate,
                    bound,
                    mergedInputs,
                )) {
                is DotNetGenericOwnerPhysicalBindingResult.Bound -> binding.value
                is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                    return DotNetGenericOwnerPhysicalBindingResult.Conflict(binding.reason)
                DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                    return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            }
            mergedCallableFamilies[candidate.logicalMember] = family
        }
        return DotNetGenericOwnerPhysicalBindingResult.Bound(
            DotNetLocalGenericOwnerPhysicalAuthority(
                earlyDeclarations = earlyDeclarations,
                boundDeclarations = bound,
                inputsByIdentity = mergedInputs,
                callableFamiliesByLogicalMember = mergedCallableFamilies,
            ),
        )
    }

    fun carrierSnapshotOrNull(
        carrier: DotNetGenericOwnerPhysicalCarrier,
    ): DotNetGenericOwnerPhysicalValueShadowCarrierSnapshot? = when {
        carrier.type == DotNetGenericOwnerSymbolicCarrierReference.objectCarrier() ->
            DotNetGenericOwnerPhysicalValueShadowCarrierSnapshot(
                DotNetGenericOwnerPhysicalValueShadowCarrierKind.OBJECT,
            )
        carrier.type is DotNetGenericOwnerSymbolicCarrierReference.Constructed ->
            constructionSnapshotOrNull(carrier.type)
        else -> null
    }

    fun viewSnapshotOrNull(
        view: DotNetGenericOwnerPhysicalView,
        evidence: Set<DotNetGenericOwnerPhysicalViewEvidence>,
    ): DotNetGenericOwnerPhysicalValueShadowViewSnapshot? =
        constructionSnapshotOrNull(view.construction)?.let { carrier ->
            DotNetGenericOwnerPhysicalValueShadowViewSnapshot(
                carrier = carrier,
                evidence = evidence.mapTo(linkedSetOf()) { item ->
                    DotNetGenericOwnerPhysicalValueShadowEvidence.valueOf(item.name)
                },
            )
        }

    fun familySnapshotOrNull(
        identity: DotNetGenericOwnerPhysicalTypeDefIdentity,
    ): DotNetGenericOwnerPhysicalValueShadowFamilySnapshot? {
        val localIdentity = identity as? DotNetGenericOwnerPhysicalTypeDefIdentity.Local ?: return null
        val input = inputsByIdentity[localIdentity] ?: return null
        return DotNetGenericOwnerPhysicalValueShadowFamilySnapshot(
            kind = when (input.role) {
                DotNetLocalGenericOwnerPhysicalTypeRole.GENERIC_CLASS,
                DotNetLocalGenericOwnerPhysicalTypeRole.NATURAL_INTERFACE,
                -> DotNetGenericOwnerPhysicalValueShadowCarrierKind.LOCAL_OWNER_CONSTRUCTION
                DotNetLocalGenericOwnerPhysicalTypeRole.SEMANTIC_CAPABILITY ->
                    DotNetGenericOwnerPhysicalValueShadowCarrierKind.SEMANTIC_CAPABILITY
            },
            localOwnerName = input.logicalOwnerName,
            localTypeDefView = localIdentity.view?.toShadowView(),
        )
    }

    private fun constructionSnapshotOrNull(
        construction: DotNetGenericOwnerSymbolicCarrierReference.Constructed,
    ): DotNetGenericOwnerPhysicalValueShadowCarrierSnapshot? {
        val identity = construction.definition as? DotNetGenericOwnerPhysicalTypeDefIdentity.Local ?: return null
        val input = inputsByIdentity[identity] ?: return null
        if (input.role == DotNetLocalGenericOwnerPhysicalTypeRole.SEMANTIC_CAPABILITY) {
            if (construction.arguments.isNotEmpty()) return null
            return DotNetGenericOwnerPhysicalValueShadowCarrierSnapshot(
                kind = DotNetGenericOwnerPhysicalValueShadowCarrierKind.SEMANTIC_CAPABILITY,
                localOwnerName = input.logicalOwnerName,
            )
        }
        val parameters = construction.arguments.map { argument ->
            argument as? DotNetGenericOwnerSymbolicCarrierReference.Parameter ?: return null
        }
        val binders = parameters.map { parameter ->
            (parameter.binder as? DotNetGenericOwnerPhysicalGenericBinderReference.Type)
                ?.definition as? DotNetGenericOwnerPhysicalTypeDefIdentity.Local ?: return null
        }.distinct()
        val binder = binders.singleOrNull() ?: return null
        val binderInput = inputsByIdentity[binder] ?: return null
        if (binderInput.role == DotNetLocalGenericOwnerPhysicalTypeRole.SEMANTIC_CAPABILITY) return null
        return DotNetGenericOwnerPhysicalValueShadowCarrierSnapshot(
            kind = DotNetGenericOwnerPhysicalValueShadowCarrierKind.LOCAL_OWNER_CONSTRUCTION,
            localOwnerName = input.logicalOwnerName,
            ownerParameterIndices = parameters.map { parameter -> parameter.index },
            localTypeDefView = identity.view?.toShadowView(),
            parameterBinderOwnerName = binderInput.logicalOwnerName,
            parameterBinderTypeDefView = binder.view?.toShadowView(),
        )
    }

    companion object {
        fun bindEarly(
            inputs: Iterable<DotNetLocalGenericOwnerPhysicalTypeInput>,
        ): DotNetGenericOwnerPhysicalBindingResult<DotNetLocalGenericOwnerPhysicalAuthority> {
            val stableInputs = inputs.toList()
            if (stableInputs.isEmpty()) return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            if (stableInputs.any { input -> input.role != DotNetLocalGenericOwnerPhysicalTypeRole.GENERIC_CLASS }) {
                return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                    "early local physical authority may contain only selected generic classes",
                )
            }
            val byIdentity = linkedMapOf<
                    DotNetGenericOwnerPhysicalTypeDefIdentity.Local,
                    DotNetLocalGenericOwnerPhysicalTypeInput,
                    >()
            for (candidate in stableInputs) {
                val existing = byIdentity.putIfAbsent(candidate.identity, candidate)
                if (existing != null && existing != candidate) {
                    return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                        "one early local TypeDef received conflicting descriptions",
                    )
                }
            }
            val declarations = when (val binding = DotNetGenericOwnerPhysicalDeclarationIndex.bind(
                epoch = DotNetGenericOwnerPhysicalAuthorityEpoch.EARLY_REPRESENTATION_PLAN,
                typeDefinitions = byIdentity.values.map(DotNetLocalGenericOwnerPhysicalTypeInput::asReference),
                methodDefinitions = emptyList(),
            )) {
                is DotNetGenericOwnerPhysicalBindingResult.Bound -> binding.value
                is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                    return DotNetGenericOwnerPhysicalBindingResult.Conflict(binding.reason)
                DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                    return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            }
            return DotNetGenericOwnerPhysicalBindingResult.Bound(
                DotNetLocalGenericOwnerPhysicalAuthority(
                    earlyDeclarations = declarations,
                    boundDeclarations = null,
                    inputsByIdentity = byIdentity,
                    callableFamiliesByLogicalMember = emptyMap(),
                ),
            )
        }
    }
}

private fun DotNetGenericInterfaceView.toShadowView(): DotNetGenericOwnerPhysicalValueShadowTypeDefView =
    DotNetGenericOwnerPhysicalValueShadowTypeDefView.valueOf(name)
