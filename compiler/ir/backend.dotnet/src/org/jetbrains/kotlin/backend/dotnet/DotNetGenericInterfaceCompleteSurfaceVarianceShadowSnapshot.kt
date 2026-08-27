/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

/** Public diagnostic mirror of the complete-surface planner's ECMA-335 position set. */
enum class DotNetGenericInterfaceCompleteSurfaceVarianceSnapshotPolarity {
    NONE,
    OUT,
    IN,
    BOTH,
}

/** Public diagnostic mirror of one logical maximum or selected physical GenericParam variance. */
enum class DotNetGenericInterfaceCompleteSurfaceVarianceSnapshotVariance {
    INVARIANT,
    COVARIANT,
    CONTRAVARIANT,
}

/** Whether one local natural interface obtained a complete, authoritative surface decision. */
enum class DotNetGenericInterfaceCompleteSurfaceVarianceShadowStatus {
    BOUND,
    UNAVAILABLE,
    CONFLICT,
}

/** One ordered owner-parameter decision, detached from IR and physical-metadata handles. */
data class DotNetGenericInterfaceCompleteSurfaceVarianceParameterSnapshot(
    val index: Int,
    val logicalMaximumVariance: DotNetGenericInterfaceCompleteSurfaceVarianceSnapshotVariance,
    val requiredPolarity: DotNetGenericInterfaceCompleteSurfaceVarianceSnapshotPolarity,
    val selectedPhysicalVariance: DotNetGenericInterfaceCompleteSurfaceVarianceSnapshotVariance,
) {
    init {
        require(index >= 0) {
            "a complete-surface variance shadow cannot reference a negative owner-parameter index"
        }
        require(
            when (logicalMaximumVariance) {
                DotNetGenericInterfaceCompleteSurfaceVarianceSnapshotVariance.INVARIANT ->
                    selectedPhysicalVariance ==
                            DotNetGenericInterfaceCompleteSurfaceVarianceSnapshotVariance.INVARIANT
                DotNetGenericInterfaceCompleteSurfaceVarianceSnapshotVariance.COVARIANT ->
                    selectedPhysicalVariance in setOf(
                        DotNetGenericInterfaceCompleteSurfaceVarianceSnapshotVariance.COVARIANT,
                        DotNetGenericInterfaceCompleteSurfaceVarianceSnapshotVariance.INVARIANT,
                    )
                DotNetGenericInterfaceCompleteSurfaceVarianceSnapshotVariance.CONTRAVARIANT ->
                    selectedPhysicalVariance in setOf(
                        DotNetGenericInterfaceCompleteSurfaceVarianceSnapshotVariance.CONTRAVARIANT,
                        DotNetGenericInterfaceCompleteSurfaceVarianceSnapshotVariance.INVARIANT,
                    )
            },
        ) { "complete-surface physical variance may only weaken its logical maximum" }
        require(
            selectedPhysicalVariance !=
                    DotNetGenericInterfaceCompleteSurfaceVarianceSnapshotVariance.COVARIANT ||
                    requiredPolarity !in setOf(
                        DotNetGenericInterfaceCompleteSurfaceVarianceSnapshotPolarity.IN,
                        DotNetGenericInterfaceCompleteSurfaceVarianceSnapshotPolarity.BOTH,
                    ),
        ) { "a covariant complete-surface parameter cannot occur in an input position" }
        require(
            selectedPhysicalVariance !=
                    DotNetGenericInterfaceCompleteSurfaceVarianceSnapshotVariance.CONTRAVARIANT ||
                    requiredPolarity !in setOf(
                        DotNetGenericInterfaceCompleteSurfaceVarianceSnapshotPolarity.OUT,
                        DotNetGenericInterfaceCompleteSurfaceVarianceSnapshotPolarity.BOTH,
                    ),
        ) { "a contravariant complete-surface parameter cannot occur in an output position" }
    }
}

/**
 * Immutable, IR-free evidence returned only to architecture tests during the generic-owner rehearsal.
 *
 * This snapshot is neither emitted nor serialized. Lowering, routing, storage selection, MethodImpl
 * materialization, and code generation must not consume it.
 */
data class DotNetGenericInterfaceCompleteSurfaceVarianceShadowSnapshot(
    val ownerName: String,
    val status: DotNetGenericInterfaceCompleteSurfaceVarianceShadowStatus,
    val parameters: List<DotNetGenericInterfaceCompleteSurfaceVarianceParameterSnapshot>,
    val blocker: String?,
) {
    init {
        require(ownerName.isNotEmpty()) {
            "a complete-surface variance shadow requires a stable non-empty diagnostic owner name"
        }
        require(parameters.map { parameter -> parameter.index } == parameters.indices.toList()) {
            "a complete-surface variance shadow requires ordered owner-parameter decisions"
        }
        require(
            when (status) {
                DotNetGenericInterfaceCompleteSurfaceVarianceShadowStatus.BOUND ->
                    parameters.isNotEmpty() && blocker == null
                DotNetGenericInterfaceCompleteSurfaceVarianceShadowStatus.UNAVAILABLE,
                DotNetGenericInterfaceCompleteSurfaceVarianceShadowStatus.CONFLICT,
                -> parameters.isEmpty() && !blocker.isNullOrEmpty()
            },
        ) { "complete-surface variance shadow status and evidence describe different outcomes" }
    }
}
