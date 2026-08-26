/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

/** Result of the read-only BOUND-to-final-emission MethodDef comparison. */
enum class DotNetGenericOwnerPhysicalMethodDefEmissionComparisonStatus {
    MATCH,
    UNAVAILABLE,
    CONFLICT,
}

/** The two physical entries in the currently bounded producer family. */
enum class DotNetGenericOwnerPhysicalMethodDefEmissionEntryKind {
    NATURAL_INTERFACE,
    SEMANTIC_CAPABILITY_INTERFACE_SLOT,
}

/** Complete visibility vocabulary observed in a CLI MethodDef header. */
enum class DotNetGenericOwnerPhysicalMethodDefEmissionVisibility {
    PUBLIC,
    FAMILY,
    ASSEMBLY,
    FAMILY_OR_ASSEMBLY,
    FAMILY_AND_ASSEMBLY,
    PRIVATE,
}

/** Recursive, IR-free carrier vocabulary used only by final-emission diagnostics. */
enum class DotNetGenericOwnerPhysicalMethodDefEmissionCarrierKind {
    VOID,
    BOOLEAN,
    INT32,
    STRING,
    OBJECT,
    OWNER_PARAMETER,
    METHOD_PARAMETER,
    LOCAL_CONSTRUCTION,
    SZ_ARRAY,
    BY_REFERENCE,
    OTHER,
    UNKNOWN,
}

enum class DotNetGenericOwnerPhysicalMethodDefEmissionResultLayout {
    VOID,
    DIRECT,
    SPLIT_NULLABLE,
    INVALID,
}

/** One independently identified local TypeDef, stripped of its IR correlation handle. */
data class DotNetGenericOwnerPhysicalMethodDefEmissionTypeDefSnapshot(
    val ownerName: String,
    val typeDefView: DotNetGenericOwnerPhysicalValueShadowTypeDefView?,
    val genericArity: Int,
    val category: DotNetGenericOwnerPhysicalNamedTypeCategory,
) {
    init {
        require(ownerName.isNotEmpty() && genericArity >= 0) {
            "a MethodDef-emission TypeDef snapshot requires a name and non-negative arity"
        }
    }
}

/** One physical signature carrier; names are diagnostics and never participate in comparison. */
data class DotNetGenericOwnerPhysicalMethodDefEmissionCarrierSnapshot(
    val kind: DotNetGenericOwnerPhysicalMethodDefEmissionCarrierKind,
    val typeDef: DotNetGenericOwnerPhysicalMethodDefEmissionTypeDefSnapshot? = null,
    val parameterIndex: Int? = null,
    val methodNameForDiagnostics: String? = null,
    val arguments: List<DotNetGenericOwnerPhysicalMethodDefEmissionCarrierSnapshot> = emptyList(),
    val physicalDescription: String? = null,
) {
    init {
        require(parameterIndex == null || parameterIndex >= 0) {
            "a MethodDef-emission parameter snapshot requires a non-negative index"
        }
        require(methodNameForDiagnostics == null || methodNameForDiagnostics.isNotEmpty()) {
            "a MethodDef-emission method diagnostic cannot be empty"
        }
        require(physicalDescription == null || physicalDescription.isNotEmpty()) {
            "a MethodDef-emission carrier diagnostic cannot be empty"
        }
        when (kind) {
            DotNetGenericOwnerPhysicalMethodDefEmissionCarrierKind.OWNER_PARAMETER ->
                require(typeDef != null && parameterIndex != null && arguments.isEmpty() &&
                        methodNameForDiagnostics == null && physicalDescription == null) {
                    "an owner-parameter snapshot requires only its TypeDef and index"
                }
            DotNetGenericOwnerPhysicalMethodDefEmissionCarrierKind.METHOD_PARAMETER ->
                require(typeDef != null && parameterIndex != null &&
                        methodNameForDiagnostics != null && arguments.isEmpty() &&
                        physicalDescription == null) {
                    "a method-parameter snapshot requires its owner, method, and index"
                }
            DotNetGenericOwnerPhysicalMethodDefEmissionCarrierKind.LOCAL_CONSTRUCTION ->
                require(typeDef != null && parameterIndex == null &&
                        methodNameForDiagnostics == null && physicalDescription == null &&
                        arguments.size == typeDef.genericArity) {
                    "a local-construction snapshot must satisfy its observed TypeDef arity"
                }
            DotNetGenericOwnerPhysicalMethodDefEmissionCarrierKind.SZ_ARRAY,
            DotNetGenericOwnerPhysicalMethodDefEmissionCarrierKind.BY_REFERENCE,
            -> require(typeDef == null && parameterIndex == null && arguments.size == 1 &&
                    methodNameForDiagnostics == null && physicalDescription == null) {
                "an array or managed-pointer snapshot requires one element carrier"
            }
            DotNetGenericOwnerPhysicalMethodDefEmissionCarrierKind.OTHER,
            DotNetGenericOwnerPhysicalMethodDefEmissionCarrierKind.UNKNOWN,
            -> require(typeDef == null && parameterIndex == null && arguments.isEmpty() &&
                    methodNameForDiagnostics == null && physicalDescription != null) {
                "an other or unknown carrier snapshot requires only a diagnostic"
            }
            else -> require(typeDef == null && parameterIndex == null && arguments.isEmpty() &&
                    methodNameForDiagnostics == null && physicalDescription == null) {
                "a leaf carrier snapshot cannot contain structural data"
            }
        }
    }
}

data class DotNetGenericOwnerPhysicalMethodDefEmissionDispatchSnapshot(
    val category: DotNetGenericOwnerPhysicalMemberDispatch,
    /** Null on the BOUND side because that authority does not yet seal exact CLI flags. */
    val isVirtual: Boolean? = null,
    val isNewSlot: Boolean? = null,
    val isAbstract: Boolean? = null,
    val isFinal: Boolean? = null,
) {
    init {
        val flags = listOf(isVirtual, isNewSlot, isAbstract, isFinal)
        require(flags.all { it == null } || flags.all { it != null }) {
            "exact MethodDef dispatch flags are either wholly observed or wholly absent"
        }
    }
}

data class DotNetGenericOwnerPhysicalMethodDefEmissionResultSnapshot(
    val layout: DotNetGenericOwnerPhysicalMethodDefEmissionResultLayout,
    val carrier: DotNetGenericOwnerPhysicalMethodDefEmissionCarrierSnapshot?,
    val hasCanonicalOutBooleanNullFlag: Boolean,
) {
    init {
        require(when (layout) {
            DotNetGenericOwnerPhysicalMethodDefEmissionResultLayout.VOID ->
                carrier == null && !hasCanonicalOutBooleanNullFlag
            DotNetGenericOwnerPhysicalMethodDefEmissionResultLayout.DIRECT ->
                carrier != null &&
                        carrier.kind != DotNetGenericOwnerPhysicalMethodDefEmissionCarrierKind.VOID &&
                        !hasCanonicalOutBooleanNullFlag
            DotNetGenericOwnerPhysicalMethodDefEmissionResultLayout.SPLIT_NULLABLE ->
                carrier != null &&
                        carrier.kind != DotNetGenericOwnerPhysicalMethodDefEmissionCarrierKind.VOID &&
                        hasCanonicalOutBooleanNullFlag
            DotNetGenericOwnerPhysicalMethodDefEmissionResultLayout.INVALID -> true
        }) { "a MethodDef-emission result snapshot has an incoherent physical layout" }
    }
}

/** Covered MethodDef facts; the physical name and exact flag vector remain diagnostic only. */
data class DotNetGenericOwnerPhysicalMethodDefEmissionHeaderSnapshot(
    val owner: DotNetGenericOwnerPhysicalMethodDefEmissionTypeDefSnapshot,
    val physicalMethodNameForDiagnostics: String?,
    val visibility: DotNetGenericOwnerPhysicalMethodDefEmissionVisibility,
    val dispatch: DotNetGenericOwnerPhysicalMethodDefEmissionDispatchSnapshot,
    val isInstance: Boolean,
    val genericArity: Int,
    val receiverCarrier: DotNetGenericOwnerPhysicalMethodDefEmissionCarrierSnapshot?,
    val ordinaryParameterCarriers: List<DotNetGenericOwnerPhysicalMethodDefEmissionCarrierSnapshot>,
    val result: DotNetGenericOwnerPhysicalMethodDefEmissionResultSnapshot,
) {
    init {
        require(genericArity >= 0 && (isInstance == (receiverCarrier != null))) {
            "a MethodDef-emission header requires coherent arity and receiver presence"
        }
        require(physicalMethodNameForDiagnostics == null || physicalMethodNameForDiagnostics.isNotEmpty()) {
            "a MethodDef-emission physical method diagnostic cannot be empty"
        }
    }
}

data class DotNetGenericOwnerPhysicalMethodDefEmissionEndpointComparisonSnapshot(
    val entryKind: DotNetGenericOwnerPhysicalMethodDefEmissionEntryKind,
    val methodRole: DotNetGenericOwnerMemberFamilyRole?,
    val status: DotNetGenericOwnerPhysicalMethodDefEmissionComparisonStatus,
    val expected: DotNetGenericOwnerPhysicalMethodDefEmissionHeaderSnapshot,
    val actual: DotNetGenericOwnerPhysicalMethodDefEmissionHeaderSnapshot?,
    val observationCount: Int,
    val diagnostic: String?,
) {
    init {
        require(observationCount >= 0 && (diagnostic == null || diagnostic.isNotEmpty())) {
            "a MethodDef-emission endpoint comparison has invalid evidence"
        }
        require(status != DotNetGenericOwnerPhysicalMethodDefEmissionComparisonStatus.MATCH ||
                observationCount == 1 && actual != null && diagnostic == null) {
            "a matching MethodDef-emission endpoint requires one exact observation"
        }
        require(status != DotNetGenericOwnerPhysicalMethodDefEmissionComparisonStatus.UNAVAILABLE ||
                actual == null) {
            "an unavailable MethodDef-emission endpoint cannot claim a bound actual header"
        }
        require((status == DotNetGenericOwnerPhysicalMethodDefEmissionComparisonStatus.MATCH) ==
                (diagnostic == null)) {
            "only a matching MethodDef-emission endpoint may omit its diagnostic"
        }
    }
}

/** Atomic comparison of the natural and semantic MethodDefs in one BOUND callable family. */
data class DotNetGenericOwnerPhysicalMethodDefEmissionFamilyComparisonSnapshot(
    val scope: DotNetIlEmissionScope,
    val ownerName: String,
    val logicalMemberName: String,
    val status: DotNetGenericOwnerPhysicalMethodDefEmissionComparisonStatus,
    val endpoints: List<DotNetGenericOwnerPhysicalMethodDefEmissionEndpointComparisonSnapshot>,
) {
    init {
        require(ownerName.isNotEmpty() && logicalMemberName.isNotEmpty() &&
                endpoints.map { it.entryKind }.toSet() ==
                DotNetGenericOwnerPhysicalMethodDefEmissionEntryKind.entries.toSet() &&
                endpoints.size == DotNetGenericOwnerPhysicalMethodDefEmissionEntryKind.entries.size) {
            "a MethodDef-emission family comparison requires both distinct physical entries"
        }
        val expectedStatus = when {
            endpoints.any { it.status == DotNetGenericOwnerPhysicalMethodDefEmissionComparisonStatus.CONFLICT } ->
                DotNetGenericOwnerPhysicalMethodDefEmissionComparisonStatus.CONFLICT
            endpoints.any { it.status == DotNetGenericOwnerPhysicalMethodDefEmissionComparisonStatus.UNAVAILABLE } ->
                DotNetGenericOwnerPhysicalMethodDefEmissionComparisonStatus.UNAVAILABLE
            else -> DotNetGenericOwnerPhysicalMethodDefEmissionComparisonStatus.MATCH
        }
        require(status == expectedStatus) {
            "a MethodDef-emission family status must be the fail-closed join of its endpoints"
        }
    }
}

@JvmInline
internal value class DotNetGenericOwnerPhysicalMethodDefEmissionTypeKey(val value: Int)

@JvmInline
internal value class DotNetGenericOwnerPhysicalMethodDefEmissionMethodKey(val value: Int)

/** IR-free carrier used by the pure structural comparator. */
internal sealed interface DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape {
    data class Leaf(val kind: DotNetGenericOwnerPhysicalTypeKind) :
        DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape {
        init {
            require(kind in setOf(
                DotNetGenericOwnerPhysicalTypeKind.VOID,
                DotNetGenericOwnerPhysicalTypeKind.BOOLEAN,
                DotNetGenericOwnerPhysicalTypeKind.INT32,
                DotNetGenericOwnerPhysicalTypeKind.STRING,
                DotNetGenericOwnerPhysicalTypeKind.OBJECT,
            )) { "a comparison leaf must belong to the bounded physical vocabulary" }
        }
    }

    data class OwnerParameter(
        val binder: DotNetGenericOwnerPhysicalMethodDefEmissionTypeKey,
        val index: Int,
    ) : DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape {
        init {
            require(index >= 0) { "an owner-parameter comparison shape requires a non-negative index" }
        }
    }

    data class MethodParameter(
        val binder: DotNetGenericOwnerPhysicalMethodDefEmissionMethodKey,
        val index: Int,
    ) : DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape {
        init {
            require(index >= 0) { "a method-parameter comparison shape requires a non-negative index" }
        }
    }

    data class Construction(
        val definition: DotNetGenericOwnerPhysicalMethodDefEmissionTypeKey,
        val arguments: List<DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape>,
    ) : DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape {
        init {
            require(arguments.none { argument ->
                argument is Leaf && argument.kind == DotNetGenericOwnerPhysicalTypeKind.VOID
            }) { "a comparison construction cannot contain void" }
        }
    }

    data class SzArray(val element: DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape) :
        DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape

    data class ByReference(val element: DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape) :
        DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape

    /** Known structural kind outside this bounded vocabulary; its text never becomes identity. */
    data object Other : DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape
}

internal sealed interface DotNetGenericOwnerPhysicalMethodDefEmissionResultShape {
    data object Void : DotNetGenericOwnerPhysicalMethodDefEmissionResultShape
    data class Direct(val carrier: DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape) :
        DotNetGenericOwnerPhysicalMethodDefEmissionResultShape {
        init {
            require(carrier !is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.Leaf ||
                    carrier.kind != DotNetGenericOwnerPhysicalTypeKind.VOID) {
                "a direct comparison result requires a non-void carrier"
            }
        }
    }
    data class SplitNullable(val payload: DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape) :
        DotNetGenericOwnerPhysicalMethodDefEmissionResultShape {
        init {
            require(payload !is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.Leaf ||
                    payload.kind != DotNetGenericOwnerPhysicalTypeKind.VOID) {
                "a split-nullable comparison result requires a non-void payload"
            }
        }
    }
}

internal data class DotNetGenericOwnerPhysicalMethodDefEmissionHeaderShape(
    val owner: DotNetGenericOwnerPhysicalMethodDefEmissionTypeKey,
    val ownerGenericArity: Int,
    val ownerCategory: DotNetGenericOwnerPhysicalNamedTypeCategory,
    val visibility: DotNetGenericOwnerPhysicalMethodDefEmissionVisibility,
    val dispatch: DotNetGenericOwnerPhysicalMemberDispatch,
    val isInstance: Boolean,
    val genericArity: Int,
    val receiverCarrier: DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape?,
    val ordinaryParameterCarriers: List<DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape>,
    val result: DotNetGenericOwnerPhysicalMethodDefEmissionResultShape,
) {
    init {
        require(ownerGenericArity >= 0 && genericArity >= 0 &&
                isInstance == (receiverCarrier != null)) {
            "a MethodDef comparison shape requires coherent arities and receiver presence"
        }
    }
}

internal sealed interface DotNetGenericOwnerPhysicalMethodDefEmissionHeaderEvidence {
    data class Known(
        val shape: DotNetGenericOwnerPhysicalMethodDefEmissionHeaderShape,
        val snapshot: DotNetGenericOwnerPhysicalMethodDefEmissionHeaderSnapshot,
    ) : DotNetGenericOwnerPhysicalMethodDefEmissionHeaderEvidence

    data class Unavailable(val reason: String) :
        DotNetGenericOwnerPhysicalMethodDefEmissionHeaderEvidence

    data class Conflict(val reason: String) :
        DotNetGenericOwnerPhysicalMethodDefEmissionHeaderEvidence
}

/** Pure, IR-free comparison; candidate correlation and identity allocation happen in adapters. */
internal fun compareDotNetGenericOwnerPhysicalMethodDefEmissionEndpoint(
    entryKind: DotNetGenericOwnerPhysicalMethodDefEmissionEntryKind,
    methodRole: DotNetGenericOwnerMemberFamilyRole?,
    expectedShape: DotNetGenericOwnerPhysicalMethodDefEmissionHeaderShape,
    expectedSnapshot: DotNetGenericOwnerPhysicalMethodDefEmissionHeaderSnapshot,
    actualCandidates: List<DotNetGenericOwnerPhysicalMethodDefEmissionHeaderEvidence>,
): DotNetGenericOwnerPhysicalMethodDefEmissionEndpointComparisonSnapshot {
    if (actualCandidates.isEmpty()) {
        return DotNetGenericOwnerPhysicalMethodDefEmissionEndpointComparisonSnapshot(
            entryKind,
            methodRole,
            DotNetGenericOwnerPhysicalMethodDefEmissionComparisonStatus.UNAVAILABLE,
            expectedSnapshot,
            actual = null,
            observationCount = 0,
            diagnostic = "the final emitter products contain no matching physical MethodDef",
        )
    }
    if (actualCandidates.size != 1) {
        return DotNetGenericOwnerPhysicalMethodDefEmissionEndpointComparisonSnapshot(
            entryKind,
            methodRole,
            DotNetGenericOwnerPhysicalMethodDefEmissionComparisonStatus.CONFLICT,
            expectedSnapshot,
            actual = (actualCandidates.singleOrNull() as?
                    DotNetGenericOwnerPhysicalMethodDefEmissionHeaderEvidence.Known)?.snapshot,
            observationCount = actualCandidates.size,
            diagnostic = "the final emitter products contain duplicate physical MethodDef evidence",
        )
    }
    return when (val actual = actualCandidates.single()) {
        is DotNetGenericOwnerPhysicalMethodDefEmissionHeaderEvidence.Unavailable ->
            DotNetGenericOwnerPhysicalMethodDefEmissionEndpointComparisonSnapshot(
                entryKind,
                methodRole,
                DotNetGenericOwnerPhysicalMethodDefEmissionComparisonStatus.UNAVAILABLE,
                expectedSnapshot,
                actual = null,
                observationCount = 1,
                diagnostic = actual.reason,
            )
        is DotNetGenericOwnerPhysicalMethodDefEmissionHeaderEvidence.Conflict ->
            DotNetGenericOwnerPhysicalMethodDefEmissionEndpointComparisonSnapshot(
                entryKind,
                methodRole,
                DotNetGenericOwnerPhysicalMethodDefEmissionComparisonStatus.CONFLICT,
                expectedSnapshot,
                actual = null,
                observationCount = 1,
                diagnostic = actual.reason,
            )
        is DotNetGenericOwnerPhysicalMethodDefEmissionHeaderEvidence.Known -> {
            val matches = expectedShape == actual.shape
            DotNetGenericOwnerPhysicalMethodDefEmissionEndpointComparisonSnapshot(
                entryKind,
                methodRole,
                if (matches) {
                    DotNetGenericOwnerPhysicalMethodDefEmissionComparisonStatus.MATCH
                } else {
                    DotNetGenericOwnerPhysicalMethodDefEmissionComparisonStatus.CONFLICT
                },
                expectedSnapshot,
                actual.snapshot,
                observationCount = 1,
                diagnostic = if (matches) null else
                    "the BOUND and final emitted MethodDef header structures differ",
            )
        }
    }
}
