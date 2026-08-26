/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

/** Physical member role observed by the generic-owner value-provenance shadow. */
enum class DotNetGenericOwnerPhysicalValueShadowFunctionRole {
    TYPED_ENTRY,
    SEMANTIC_HOOK,
    OTHER,
}

/** The immutable IR epoch observed by one production-inert shadow snapshot. */
enum class DotNetGenericOwnerPhysicalValueShadowPhase {
    PRE_SEMANTIC_REMAP,
    POST_FINAL_ROUTING,
}

/** Whether the shadow completed its deliberately bounded analysis for one value. */
enum class DotNetGenericOwnerPhysicalValueShadowStatus {
    ANALYZED,
    UNSUPPORTED,
}

/** IR-free carrier vocabulary exposed only as in-memory architecture evidence. */
enum class DotNetGenericOwnerPhysicalValueShadowCarrierKind {
    OBJECT,
    LOCAL_OWNER_CONSTRUCTION,
    SEMANTIC_CAPABILITY,
    UNKNOWN,
}

/** Which physical TypeDef of one local Kotlin interface owns a construction. */
enum class DotNetGenericOwnerPhysicalValueShadowTypeDefView {
    CANONICAL,
    DECLARED,
    EXACT,
}

/** Unknown proof differs from a known, possibly empty, guaranteed-view set. */
enum class DotNetGenericOwnerPhysicalValueShadowGuaranteeState {
    UNKNOWN,
    KNOWN,
}

/** Public diagnostic mirror of every internal physical-view evidence source. */
enum class DotNetGenericOwnerPhysicalValueShadowEvidence {
    CURRENT_PHYSICAL_RECEIVER,
    FROZEN_PARAMETER_OR_RESULT,
    FROZEN_FIELD,
    CONSTRUCTOR_ALLOCATION,
    RECORDED_INTERFACE_EDGE,
    PRODUCER_ABI,
    RETAINED_FOREIGN_METADATA,
    CHECKED_RUNTIME_BARRIER,
    IDENTITY_PRESERVING_TRANSFER,
    STORAGE_READ,
}

/** The shadow adds UNKNOWN for values outside its current bounded transfer grammar. */
enum class DotNetGenericOwnerPhysicalValueShadowNullState {
    NON_NULL,
    NULL,
    MAYBE_NULL,
    UNKNOWN,
}

/** One verifier-visible carrier without retaining an IR or metadata handle. */
data class DotNetGenericOwnerPhysicalValueShadowCarrierSnapshot(
    val kind: DotNetGenericOwnerPhysicalValueShadowCarrierKind,
    val localOwnerName: String? = null,
    val ownerParameterIndices: List<Int> = emptyList(),
    val localTypeDefView: DotNetGenericOwnerPhysicalValueShadowTypeDefView? = null,
    val parameterBinderOwnerName: String? = null,
    val parameterBinderTypeDefView: DotNetGenericOwnerPhysicalValueShadowTypeDefView? = null,
) {
    init {
        require(ownerParameterIndices.all { index -> index >= 0 }) {
            "a physical-value shadow carrier cannot reference a negative owner-parameter index"
        }
        require(
            when (kind) {
                DotNetGenericOwnerPhysicalValueShadowCarrierKind.LOCAL_OWNER_CONSTRUCTION ->
                    !localOwnerName.isNullOrEmpty() && ownerParameterIndices.isNotEmpty() &&
                            !parameterBinderOwnerName.isNullOrEmpty()
                DotNetGenericOwnerPhysicalValueShadowCarrierKind.SEMANTIC_CAPABILITY ->
                    !localOwnerName.isNullOrEmpty() && ownerParameterIndices.isEmpty() &&
                            localTypeDefView == null && parameterBinderOwnerName == null &&
                            parameterBinderTypeDefView == null
                DotNetGenericOwnerPhysicalValueShadowCarrierKind.OBJECT,
                DotNetGenericOwnerPhysicalValueShadowCarrierKind.UNKNOWN,
                -> localOwnerName == null && ownerParameterIndices.isEmpty() &&
                        localTypeDefView == null && parameterBinderOwnerName == null &&
                        parameterBinderTypeDefView == null
            }
        ) {
            "a physical-value shadow carrier has incoherent owner, parameter, or view data"
        }
    }
}

/** Physical TypeDef identity of a selected family, without treating its construction as proof. */
data class DotNetGenericOwnerPhysicalValueShadowFamilySnapshot(
    val kind: DotNetGenericOwnerPhysicalValueShadowCarrierKind,
    val localOwnerName: String,
    val localTypeDefView: DotNetGenericOwnerPhysicalValueShadowTypeDefView? = null,
) {
    init {
        require(localOwnerName.isNotEmpty() && when (kind) {
            DotNetGenericOwnerPhysicalValueShadowCarrierKind.LOCAL_OWNER_CONSTRUCTION -> true
            DotNetGenericOwnerPhysicalValueShadowCarrierKind.SEMANTIC_CAPABILITY ->
                localTypeDefView == null
            DotNetGenericOwnerPhysicalValueShadowCarrierKind.OBJECT,
            DotNetGenericOwnerPhysicalValueShadowCarrierKind.UNKNOWN,
            -> false
        }) { "selected physical-value lineage requires one constructed physical family" }
    }
}

/** Existing reason why the emitter selected one verifier-visible local carrier. */
enum class DotNetGenericOwnerPhysicalValueLocalSelectionKind {
    DECLARED_TYPE,
    EXACT_ARRAY_OVERRIDE,
    EXACT_GENERIC_OWNER_OVERRIDE,
    OPEN_NULLABLE_ARRAY_OVERRIDE,
    NESTED_GENERIC_CONSTRUCTION_OVERRIDE,
}

/** Whether an optional pre-remap record remained the same value fact at final routing. */
enum class DotNetGenericOwnerPhysicalValuePlacementContinuity {
    NOT_OBSERVED,
    STABLE,
    DIVERGED,
}

/** Read-only relation between predicted storage and the final emitted local slot. */
enum class DotNetGenericOwnerPhysicalValuePlacementRelation {
    MATCH,
    DIFFERENT,
    PREDICTION_UNSUPPORTED,
    PRE_FINAL_DIVERGENCE,
    NOT_EMITTED,
    AMBIGUOUS,
    ACTUAL_UNBINDABLE,
}

/**
 * IR-free comparison produced only after the final successful emitter fixpoint.
 *
 * It observes local placement; it does not prove the carrier produced by the initializer or the
 * conversions used to place it, and no compiler decision may consume it.
 */
data class DotNetGenericOwnerPhysicalValuePlacementComparisonSnapshot(
    val prediction: DotNetGenericOwnerPhysicalValueShadowSnapshot,
    val actualPhysicalMethodOwnerName: String?,
    val actualPhysicalMethodOwnerTypeDefView: DotNetGenericOwnerPhysicalValueShadowTypeDefView?,
    val actualStorageCarrier: DotNetGenericOwnerPhysicalValueShadowCarrierSnapshot,
    val actualSelectionKind: DotNetGenericOwnerPhysicalValueLocalSelectionKind?,
    val continuity: DotNetGenericOwnerPhysicalValuePlacementContinuity,
    val relation: DotNetGenericOwnerPhysicalValuePlacementRelation,
    val diagnostic: String?,
) {
    init {
        require(prediction.phase == DotNetGenericOwnerPhysicalValueShadowPhase.POST_FINAL_ROUTING) {
            "an emitted local placement must compare with the final-routing prediction"
        }
        require(actualPhysicalMethodOwnerName != null ||
                actualPhysicalMethodOwnerTypeDefView == null) {
            "an emitted local placement view requires its physical MethodDef owner"
        }
        require(relation != DotNetGenericOwnerPhysicalValuePlacementRelation.MATCH ||
                prediction.storageCarrier == actualStorageCarrier &&
                prediction.storageCarrier.kind != DotNetGenericOwnerPhysicalValueShadowCarrierKind.UNKNOWN) {
            "a matching physical-value placement requires equal known carriers"
        }
        require(diagnostic == null || diagnostic.isNotEmpty()) {
            "a physical-value placement diagnostic cannot be empty"
        }
    }
}

/** One independently guaranteed constructed view and its diagnostic evidence. */
data class DotNetGenericOwnerPhysicalValueShadowViewSnapshot(
    val carrier: DotNetGenericOwnerPhysicalValueShadowCarrierSnapshot,
    val evidence: Set<DotNetGenericOwnerPhysicalValueShadowEvidence>,
) {
    init {
        require(carrier.kind in setOf(
            DotNetGenericOwnerPhysicalValueShadowCarrierKind.LOCAL_OWNER_CONSTRUCTION,
            DotNetGenericOwnerPhysicalValueShadowCarrierKind.SEMANTIC_CAPABILITY,
        ) &&
                evidence.isNotEmpty()) {
            "a guaranteed physical-value shadow view requires a constructed carrier and evidence"
        }
    }
}

/** A selector over an independently guaranteed family view; never a proof source. */
data class DotNetGenericOwnerPhysicalValueShadowSelectedViewSnapshot(
    val family: DotNetGenericOwnerPhysicalValueShadowFamilySnapshot,
    val view: DotNetGenericOwnerPhysicalValueShadowViewSnapshot,
) {
    init {
        require(family.kind == view.carrier.kind &&
                family.localOwnerName == view.carrier.localOwnerName &&
                family.localTypeDefView == view.carrier.localTypeDefView) {
            "selected physical-value shadow lineage must identify the selected physical TypeDef"
        }
    }
}

/**
 * Immutable, IR-free evidence returned by the backend pipeline for architecture tests.
 *
 * This snapshot is neither emitted nor serialized. It cannot affect routing, storage selection,
 * MethodImpl materialization, or any other production compiler decision.
 */
data class DotNetGenericOwnerPhysicalValueShadowSnapshot(
    val ownerName: String,
    val sourceFunctionName: String,
    val physicalFunctionName: String,
    val functionRole: DotNetGenericOwnerPhysicalValueShadowFunctionRole,
    val phase: DotNetGenericOwnerPhysicalValueShadowPhase,
    val variableName: String,
    val status: DotNetGenericOwnerPhysicalValueShadowStatus,
    val initializerProducedCarrier: DotNetGenericOwnerPhysicalValueShadowCarrierSnapshot,
    val storageCarrier: DotNetGenericOwnerPhysicalValueShadowCarrierSnapshot,
    val guaranteeState: DotNetGenericOwnerPhysicalValueShadowGuaranteeState,
    val guaranteedViews: List<DotNetGenericOwnerPhysicalValueShadowViewSnapshot>,
    val selectedViewLineage: List<DotNetGenericOwnerPhysicalValueShadowSelectedViewSnapshot>,
    val initializerNullState: DotNetGenericOwnerPhysicalValueShadowNullState,
    val contentsNullState: DotNetGenericOwnerPhysicalValueShadowNullState,
    val unsupportedReason: String?,
) {
    init {
        require(ownerName.isNotEmpty() && sourceFunctionName.isNotEmpty() &&
                physicalFunctionName.isNotEmpty() && variableName.isNotEmpty()) {
            "a physical-value shadow snapshot requires stable non-empty diagnostic names"
        }
        require(guaranteedViews.map { view -> view.carrier }.distinct().size == guaranteedViews.size &&
                selectedViewLineage.distinct().size == selectedViewLineage.size &&
                selectedViewLineage.map { selection -> selection.family }.distinct().size ==
                selectedViewLineage.size) {
            "a physical-value shadow snapshot cannot contain duplicate views or lineage entries"
        }
        require(
            guaranteeState == DotNetGenericOwnerPhysicalValueShadowGuaranteeState.KNOWN ||
                    guaranteedViews.isEmpty() && selectedViewLineage.isEmpty()
        ) { "unknown physical-value guarantees cannot publish views or lineage" }
        require(selectedViewLineage.all { selection -> selection.view in guaranteedViews }) {
            "selected physical-value lineage must select an independently guaranteed view"
        }
        require(
            when (status) {
                DotNetGenericOwnerPhysicalValueShadowStatus.ANALYZED ->
                    unsupportedReason == null &&
                            initializerProducedCarrier.kind !=
                            DotNetGenericOwnerPhysicalValueShadowCarrierKind.UNKNOWN &&
                            storageCarrier.kind != DotNetGenericOwnerPhysicalValueShadowCarrierKind.UNKNOWN &&
                            initializerNullState != DotNetGenericOwnerPhysicalValueShadowNullState.UNKNOWN &&
                            contentsNullState != DotNetGenericOwnerPhysicalValueShadowNullState.UNKNOWN
                DotNetGenericOwnerPhysicalValueShadowStatus.UNSUPPORTED ->
                    !unsupportedReason.isNullOrEmpty() &&
                            initializerProducedCarrier.kind ==
                            DotNetGenericOwnerPhysicalValueShadowCarrierKind.UNKNOWN &&
                            storageCarrier.kind == DotNetGenericOwnerPhysicalValueShadowCarrierKind.UNKNOWN &&
                            guaranteeState == DotNetGenericOwnerPhysicalValueShadowGuaranteeState.UNKNOWN &&
                            initializerNullState == DotNetGenericOwnerPhysicalValueShadowNullState.UNKNOWN &&
                            contentsNullState == DotNetGenericOwnerPhysicalValueShadowNullState.UNKNOWN
            }
        ) { "physical-value shadow status and evidence must describe the same analysis outcome" }
    }
}
