/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.ir.symbols.IrClassSymbol

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
) {
    private val inputsByIdentity = inputsByIdentity.toMap()

    fun inputOrNull(
        identity: DotNetGenericOwnerPhysicalTypeDefIdentity.Local,
    ): DotNetLocalGenericOwnerPhysicalTypeInput? = inputsByIdentity[identity]

    fun genericClassIdentityOrNull(owner: IrClassSymbol): DotNetGenericOwnerPhysicalTypeDefIdentity.Local? =
        DotNetGenericOwnerPhysicalTypeDefIdentity.Local(owner, view = null)
            .takeIf { identity -> inputsByIdentity[identity]?.role == DotNetLocalGenericOwnerPhysicalTypeRole.GENERIC_CLASS }

    fun naturalInterfaceIdentityOrNull(owner: IrClassSymbol): DotNetGenericOwnerPhysicalTypeDefIdentity.Local? =
        DotNetGenericOwnerPhysicalTypeDefIdentity.Local(owner, DotNetGenericInterfaceView.DECLARED)
            .takeIf { identity -> inputsByIdentity[identity]?.role == DotNetLocalGenericOwnerPhysicalTypeRole.NATURAL_INTERFACE }

    fun advanceBound(
        additionalInputs: Iterable<DotNetLocalGenericOwnerPhysicalTypeInput>,
        buildCompleteEdgeSets: (
            DotNetGenericOwnerPhysicalDeclarationIndex,
        ) -> DotNetGenericOwnerPhysicalBindingResult<List<DotNetGenericOwnerPhysicalDirectSupertypeEdgeSet>>,
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
        val edgeSets = when (val binding = buildCompleteEdgeSets(provisional)) {
            is DotNetGenericOwnerPhysicalBindingResult.Bound -> binding.value
            is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                return DotNetGenericOwnerPhysicalBindingResult.Conflict(binding.reason)
            DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                return DotNetGenericOwnerPhysicalBindingResult.Unavailable
        }
        val bound = when (val binding = earlyDeclarations.advance(
            nextEpoch = DotNetGenericOwnerPhysicalAuthorityEpoch.BOUND_DECLARATION_INDEX,
            typeDefinitions = additionalReferences,
            methodDefinitions = emptyList(),
            directSupertypeEdgeSets = edgeSets,
        )) {
            is DotNetGenericOwnerPhysicalBindingResult.Bound -> binding.value
            is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                return DotNetGenericOwnerPhysicalBindingResult.Conflict(binding.reason)
            DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                return DotNetGenericOwnerPhysicalBindingResult.Unavailable
        }
        return DotNetGenericOwnerPhysicalBindingResult.Bound(
            DotNetLocalGenericOwnerPhysicalAuthority(
                earlyDeclarations = earlyDeclarations,
                boundDeclarations = bound,
                inputsByIdentity = mergedInputs,
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
                ),
            )
        }
    }
}

private fun DotNetGenericInterfaceView.toShadowView(): DotNetGenericOwnerPhysicalValueShadowTypeDefView =
    DotNetGenericOwnerPhysicalValueShadowTypeDefView.valueOf(name)
