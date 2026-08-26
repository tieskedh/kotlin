/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

/** Logical receiver policy selected without consulting physical-value provenance. */
enum class DotNetGenericOwnerPhysicalOperationLogicalSelectorSnapshot {
    EXACT_NATURAL,
    BROAD_UNIVERSAL,
    OPEN_NULLABLE,
}

/** Physical invocation entry predicted from the already selected logical policy. */
enum class DotNetGenericOwnerPhysicalOperationRouteKindSnapshot {
    NATURAL_INTERFACE,
    SEMANTIC_CAPABILITY_INTERFACE_SLOT,
}

/** Existing final router product observed without changing that product. */
enum class DotNetGenericOwnerPhysicalOperationActualRouteSnapshot {
    DIRECT_NATURAL,
    DIRECT_SEMANTIC_CAPABILITY_SLOT,
    GUARDED_SEMANTIC_CAPABILITY_WITH_NATURAL_FALLBACK,
    PARTIAL_OR_INCONSISTENT_SEMANTIC_ROUTE,
}

enum class DotNetGenericOwnerPhysicalOperationResultLayoutSnapshot {
    DIRECT,
    SPLIT_NULLABLE,
    VOID,
}

/** Small symbolic result-carrier vocabulary needed by the first callable shadow. */
enum class DotNetGenericOwnerPhysicalOperationResultCarrierKindSnapshot {
    OBJECT,
    OWNER_PARAMETER,
    OTHER,
}

enum class DotNetGenericOwnerPhysicalOperationRouteShadowStatus {
    BOUND,
    UNAVAILABLE,
    CONFLICT,
}

/** Relation between the read-only prediction and the pre-existing final-routing maps. */
enum class DotNetGenericOwnerPhysicalOperationRouteShadowRelation {
    MATCH,
    DIFFERENT,
    PREDICTION_UNAVAILABLE,
    DECLARATION_CONFLICT,
}

/**
 * IR-free architecture evidence for one operation at the final routing boundary.
 *
 * This snapshot is never serialized or consumed by lowering or emission. Diagnostic names do not
 * participate in logical selection, physical proof, MethodDef identity, or route comparison.
 */
data class DotNetGenericOwnerPhysicalOperationRouteShadowSnapshot(
    val ownerName: String,
    val physicalFunctionName: String,
    val receiverVariableName: String,
    val logicalMemberName: String,
    val logicalSelector: DotNetGenericOwnerPhysicalOperationLogicalSelectorSnapshot,
    val status: DotNetGenericOwnerPhysicalOperationRouteShadowStatus,
    val predictedRouteKind: DotNetGenericOwnerPhysicalOperationRouteKindSnapshot?,
    val requiredReceiverCarrier: DotNetGenericOwnerPhysicalValueShadowCarrierSnapshot,
    val resultLayout: DotNetGenericOwnerPhysicalOperationResultLayoutSnapshot?,
    val resultSlotDomain: DotNetGenericOwnerPhysicalSlotDomain?,
    val resultCarrierKind: DotNetGenericOwnerPhysicalOperationResultCarrierKindSnapshot?,
    val resultCarrierParameterBinderOwnerName: String?,
    val resultCarrierParameterIndex: Int?,
    val actualRoute: DotNetGenericOwnerPhysicalOperationActualRouteSnapshot,
    val relation: DotNetGenericOwnerPhysicalOperationRouteShadowRelation,
    val diagnostic: String?,
) {
    init {
        require(ownerName.isNotEmpty() && physicalFunctionName.isNotEmpty() &&
                receiverVariableName.isNotEmpty() && logicalMemberName.isNotEmpty()) {
            "a physical-operation shadow requires stable non-empty diagnostic names"
        }
        require(resultCarrierParameterIndex == null || resultCarrierParameterIndex >= 0) {
            "a physical-operation result cannot reference a negative owner-parameter index"
        }
        require((resultCarrierParameterBinderOwnerName == null) ==
                (resultCarrierParameterIndex == null)) {
            "a physical-operation owner parameter requires both binder and index"
        }
        require(
            when (status) {
                DotNetGenericOwnerPhysicalOperationRouteShadowStatus.BOUND ->
                    predictedRouteKind != null &&
                            requiredReceiverCarrier.kind !=
                            DotNetGenericOwnerPhysicalValueShadowCarrierKind.UNKNOWN &&
                            resultLayout != null &&
                            relation in setOf(
                                DotNetGenericOwnerPhysicalOperationRouteShadowRelation.MATCH,
                                DotNetGenericOwnerPhysicalOperationRouteShadowRelation.DIFFERENT,
                            )
                DotNetGenericOwnerPhysicalOperationRouteShadowStatus.UNAVAILABLE ->
                    predictedRouteKind == null && resultLayout == null &&
                            relation ==
                            DotNetGenericOwnerPhysicalOperationRouteShadowRelation.PREDICTION_UNAVAILABLE
                DotNetGenericOwnerPhysicalOperationRouteShadowStatus.CONFLICT ->
                    predictedRouteKind == null && resultLayout == null &&
                            relation ==
                            DotNetGenericOwnerPhysicalOperationRouteShadowRelation.DECLARATION_CONFLICT &&
                            !diagnostic.isNullOrEmpty()
            }
        ) { "physical-operation status and route evidence must describe the same outcome" }
        require(resultLayout == DotNetGenericOwnerPhysicalOperationResultLayoutSnapshot.VOID ||
                status != DotNetGenericOwnerPhysicalOperationRouteShadowStatus.BOUND ||
                resultSlotDomain != null && resultCarrierKind != null) {
            "a non-void physical-operation result requires a domain and carrier"
        }
        require(status == DotNetGenericOwnerPhysicalOperationRouteShadowStatus.BOUND ||
                resultSlotDomain == null && resultCarrierKind == null &&
                resultCarrierParameterBinderOwnerName == null && resultCarrierParameterIndex == null) {
            "an unbound physical-operation result cannot publish slot evidence"
        }
        require(resultLayout != DotNetGenericOwnerPhysicalOperationResultLayoutSnapshot.VOID ||
                resultSlotDomain == null && resultCarrierKind == null &&
                resultCarrierParameterBinderOwnerName == null && resultCarrierParameterIndex == null) {
            "a void physical-operation result cannot publish slot evidence"
        }
        require(resultCarrierKind ==
                DotNetGenericOwnerPhysicalOperationResultCarrierKindSnapshot.OWNER_PARAMETER ||
                resultCarrierParameterIndex == null) {
            "only an owner-parameter result may publish a parameter binder"
        }
        require((resultCarrierKind ==
                DotNetGenericOwnerPhysicalOperationResultCarrierKindSnapshot.OWNER_PARAMETER) ==
                (resultCarrierParameterIndex != null)) {
            "an owner-parameter result requires its physical binder and index"
        }
        require(diagnostic == null || diagnostic.isNotEmpty()) {
            "a physical-operation shadow diagnostic cannot be empty"
        }
    }
}
