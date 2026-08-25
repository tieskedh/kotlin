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

/** Whether the shadow completed its deliberately bounded analysis for one value. */
enum class DotNetGenericOwnerPhysicalValueShadowStatus {
    ANALYZED,
    UNSUPPORTED,
}

/** IR-free carrier vocabulary exposed only as in-memory architecture evidence. */
enum class DotNetGenericOwnerPhysicalValueShadowCarrierKind {
    OBJECT,
    LOCAL_OWNER_CONSTRUCTION,
    UNKNOWN,
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
) {
    init {
        require(ownerParameterIndices.all { index -> index >= 0 }) {
            "a physical-value shadow carrier cannot reference a negative owner-parameter index"
        }
        require(
            if (kind == DotNetGenericOwnerPhysicalValueShadowCarrierKind.LOCAL_OWNER_CONSTRUCTION) {
                !localOwnerName.isNullOrEmpty() && ownerParameterIndices.isNotEmpty()
            } else {
                localOwnerName == null && ownerParameterIndices.isEmpty()
            }
        ) {
            "only a local-owner carrier may name an owner or its parameter indices"
        }
    }
}

/** One independently guaranteed constructed view and its diagnostic evidence. */
data class DotNetGenericOwnerPhysicalValueShadowViewSnapshot(
    val carrier: DotNetGenericOwnerPhysicalValueShadowCarrierSnapshot,
    val evidence: Set<DotNetGenericOwnerPhysicalValueShadowEvidence>,
) {
    init {
        require(carrier.kind == DotNetGenericOwnerPhysicalValueShadowCarrierKind.LOCAL_OWNER_CONSTRUCTION &&
                evidence.isNotEmpty()) {
            "a guaranteed physical-value shadow view requires a constructed carrier and evidence"
        }
    }
}

/** A selector over an independently guaranteed family view; never a proof source. */
data class DotNetGenericOwnerPhysicalValueShadowSelectedViewSnapshot(
    val familyOwnerName: String,
    val view: DotNetGenericOwnerPhysicalValueShadowViewSnapshot,
) {
    init {
        require(familyOwnerName.isNotEmpty() && familyOwnerName == view.carrier.localOwnerName) {
            "selected physical-value shadow lineage must name the selected view family"
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
                selectedViewLineage.map { selection -> selection.familyOwnerName }.distinct().size ==
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
