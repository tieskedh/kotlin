/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

/** IR-free identity of one logical alias recorded on a physical TypeDef. */
@JvmInline
internal value class DotNetGenericOwnerCompleteEmissionTypeDefAliasKey(val value: Int)

/** One exact BaseType or InterfaceImpl row on a physical TypeDef. */
internal data class DotNetGenericOwnerCompleteEmissionTypeDefEdgeRow(
    val kind: DotNetGenericOwnerDirectSupertypeKind,
    val target: DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape,
)

internal data class DotNetGenericOwnerCompleteEmissionGenericParameterRow(
    val variance: DotNetGenericOwnerPhysicalTypeParameterVariance,
    val constraints: List<DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape>,
) {
    init {
        require(constraints.size == constraints.toSet().size) {
            "a complete-emission GenericParam cannot repeat a constraint row"
        }
    }
}

/** One physical TypeDef row and its complete direct physical ancestry. */
internal data class DotNetGenericOwnerCompleteEmissionTypeDefRow(
    val identityKey: DotNetGenericOwnerPhysicalMethodDefEmissionTypeKey,
    val aliases: List<DotNetGenericOwnerCompleteEmissionTypeDefAliasKey>,
    val genericArity: Int,
    val category: DotNetGenericOwnerPhysicalNamedTypeCategory,
    val genericParameters: List<DotNetGenericOwnerCompleteEmissionGenericParameterRow>,
    val directEdges: List<DotNetGenericOwnerCompleteEmissionTypeDefEdgeRow>,
) {
    init {
        require(genericArity >= 0 && genericParameters.size == genericArity) {
            "a complete-emission TypeDef row requires a coherent GenericParam set"
        }
    }
}

/** One physical MethodDef row, keyed independently from its complete header shape. */
internal data class DotNetGenericOwnerCompleteEmissionMethodDefRow(
    val identityKey: DotNetGenericOwnerPhysicalMethodDefEmissionMethodKey,
    val header: DotNetGenericOwnerPhysicalMethodDefEmissionHeaderShape,
    val genericParameters: List<DotNetGenericOwnerCompleteEmissionGenericParameterRow>,
) {
    init {
        require(genericParameters.size == header.genericArity) {
            "a complete-emission MethodDef row requires a coherent GenericParam set"
        }
        require(genericParameters.all { parameter ->
            parameter.variance == DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT
        }) {
            "a complete-emission MethodDef GenericParam must be invariant"
        }
    }
}

/** One exact MethodImpl row. */
internal data class DotNetGenericOwnerCompleteEmissionMethodImplRow(
    val implementingTypeDefKey: DotNetGenericOwnerPhysicalMethodDefEmissionTypeKey,
    val bodyMethodDefKey: DotNetGenericOwnerPhysicalMethodDefEmissionMethodKey,
    val declarationOwner: DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape,
    val declarationMethodDefKey: DotNetGenericOwnerPhysicalMethodDefEmissionMethodKey,
)

/** Complete bounded physical rows expected from, or observed in, one final emission scope. */
internal data class DotNetGenericOwnerCompleteEmissionManifest(
    val typeDefs: List<DotNetGenericOwnerCompleteEmissionTypeDefRow>,
    val methodDefs: List<DotNetGenericOwnerCompleteEmissionMethodDefRow>,
    val methodImpls: List<DotNetGenericOwnerCompleteEmissionMethodImplRow>,
)

/** Completeness evidence for one physical metadata row kind. */
internal sealed interface DotNetGenericOwnerCompleteEmissionRowsEvidence<out T> {
    data class Known<T>(val rows: List<T>) : DotNetGenericOwnerCompleteEmissionRowsEvidence<T>

    data class Unavailable(val reason: String) : DotNetGenericOwnerCompleteEmissionRowsEvidence<Nothing> {
        init {
            require(reason.isNotEmpty()) { "unavailable complete-emission evidence requires a reason" }
        }
    }

    data class Conflict(val reason: String) : DotNetGenericOwnerCompleteEmissionRowsEvidence<Nothing> {
        init {
            require(reason.isNotEmpty()) { "conflicting complete-emission evidence requires a reason" }
        }
    }
}

internal data class DotNetGenericOwnerCompleteEmissionManifestEvidence(
    val typeDefs: DotNetGenericOwnerCompleteEmissionRowsEvidence<DotNetGenericOwnerCompleteEmissionTypeDefRow>,
    val methodDefs: DotNetGenericOwnerCompleteEmissionRowsEvidence<DotNetGenericOwnerCompleteEmissionMethodDefRow>,
    val methodImpls: DotNetGenericOwnerCompleteEmissionRowsEvidence<DotNetGenericOwnerCompleteEmissionMethodImplRow>,
)

internal fun DotNetGenericOwnerCompleteEmissionManifest.asKnownEvidence() =
    DotNetGenericOwnerCompleteEmissionManifestEvidence(
        DotNetGenericOwnerCompleteEmissionRowsEvidence.Known(typeDefs),
        DotNetGenericOwnerCompleteEmissionRowsEvidence.Known(methodDefs),
        DotNetGenericOwnerCompleteEmissionRowsEvidence.Known(methodImpls),
    )

internal data class DotNetGenericOwnerCompleteEmissionRowsComparison(
    val status: DotNetGenericOwnerPhysicalMethodDefEmissionComparisonStatus,
    val diagnostics: List<String>,
) {
    init {
        require((status == DotNetGenericOwnerPhysicalMethodDefEmissionComparisonStatus.MATCH) == diagnostics.isEmpty()) {
            "only matching complete-emission rows may omit diagnostics"
        }
        require(diagnostics.none(String::isEmpty)) {
            "complete-emission diagnostics cannot be empty"
        }
    }
}

internal data class DotNetGenericOwnerCompleteEmissionManifestComparison(
    val status: DotNetGenericOwnerPhysicalMethodDefEmissionComparisonStatus,
    val typeDefs: DotNetGenericOwnerCompleteEmissionRowsComparison,
    val methodDefs: DotNetGenericOwnerCompleteEmissionRowsComparison,
    val methodImpls: DotNetGenericOwnerCompleteEmissionRowsComparison,
) {
    init {
        require(status == joinDotNetGenericOwnerCompleteEmissionComparisonStatuses(
            listOf(typeDefs.status, methodDefs.status, methodImpls.status),
        )) { "a complete-emission manifest status must be the fail-closed join of all row kinds" }
    }
}

/** Conflict dominates unavailable evidence, and unavailable evidence dominates a match. */
internal fun joinDotNetGenericOwnerCompleteEmissionComparisonStatuses(
    statuses: Iterable<DotNetGenericOwnerPhysicalMethodDefEmissionComparisonStatus>,
): DotNetGenericOwnerPhysicalMethodDefEmissionComparisonStatus {
    var joined = DotNetGenericOwnerPhysicalMethodDefEmissionComparisonStatus.MATCH
    for (status in statuses) {
        when (status) {
            DotNetGenericOwnerPhysicalMethodDefEmissionComparisonStatus.CONFLICT -> return status
            DotNetGenericOwnerPhysicalMethodDefEmissionComparisonStatus.UNAVAILABLE -> joined = status
            DotNetGenericOwnerPhysicalMethodDefEmissionComparisonStatus.MATCH -> Unit
        }
    }
    return joined
}

/** Pure comparison of two bounded complete metadata manifests. */
internal fun compareDotNetGenericOwnerCompleteEmissionManifest(
    expected: DotNetGenericOwnerCompleteEmissionManifest,
    actual: DotNetGenericOwnerCompleteEmissionManifestEvidence,
): DotNetGenericOwnerCompleteEmissionManifestComparison {
    val typeDefs = compareCompleteEmissionTypeDefs(expected.typeDefs, actual.typeDefs)
    val methodDefs = compareCompleteEmissionMethodDefs(expected.methodDefs, actual.methodDefs)
    val methodImpls = compareCompleteEmissionMethodImpls(expected.methodImpls, actual.methodImpls)
    return DotNetGenericOwnerCompleteEmissionManifestComparison(
        joinDotNetGenericOwnerCompleteEmissionComparisonStatuses(
            listOf(typeDefs.status, methodDefs.status, methodImpls.status),
        ),
        typeDefs,
        methodDefs,
        methodImpls,
    )
}

private fun compareCompleteEmissionTypeDefs(
    expected: List<DotNetGenericOwnerCompleteEmissionTypeDefRow>,
    actualEvidence: DotNetGenericOwnerCompleteEmissionRowsEvidence<DotNetGenericOwnerCompleteEmissionTypeDefRow>,
): DotNetGenericOwnerCompleteEmissionRowsComparison {
    val expectedConflicts = completeEmissionTypeDefStructuralConflicts("expected", expected)
    return compareCompleteEmissionKeyedRows(
        "TypeDef",
        expected,
        actualEvidence,
        DotNetGenericOwnerCompleteEmissionTypeDefRow::identityKey,
        expectedConflicts,
        ::completeEmissionTypeDefStructuralConflicts,
    ) { expectedRow, actualRow ->
        expectedRow.identityKey == actualRow.identityKey &&
                expectedRow.aliases.toSet() == actualRow.aliases.toSet() &&
                expectedRow.genericArity == actualRow.genericArity &&
                expectedRow.category == actualRow.category &&
                completeEmissionGenericParametersMatch(expectedRow.genericParameters, actualRow.genericParameters) &&
                expectedRow.directEdges.toSet() == actualRow.directEdges.toSet()
    }
}

private fun completeEmissionGenericParametersMatch(
    expected: List<DotNetGenericOwnerCompleteEmissionGenericParameterRow>,
    actual: List<DotNetGenericOwnerCompleteEmissionGenericParameterRow>,
): Boolean = expected.size == actual.size && expected.indices.all { index ->
    val expectedParameter = expected[index]
    val actualParameter = actual[index]
    expectedParameter.variance == actualParameter.variance &&
            expectedParameter.constraints.toSet() == actualParameter.constraints.toSet()
}

private fun completeEmissionTypeDefStructuralConflicts(
    side: String,
    rows: List<DotNetGenericOwnerCompleteEmissionTypeDefRow>,
): List<String> = buildList {
    if (rows.hasDuplicateKeys(DotNetGenericOwnerCompleteEmissionTypeDefRow::identityKey)) {
        add("the $side complete-emission manifest contains duplicate physical TypeDef rows")
    }
    if (rows.any { it.aliases.isEmpty() || it.aliases.size != it.aliases.toSet().size }) {
        add("the $side complete-emission manifest contains an empty or duplicate TypeDef alias set")
    }
    val physicalOwnersByAlias = rows.flatMap { row ->
        row.aliases.toSet().map { alias -> alias to row.identityKey }
    }.groupBy({ it.first }, { it.second })
    if (physicalOwnersByAlias.values.any { physicalOwners -> physicalOwners.toSet().size > 1 }) {
        add("the $side complete-emission manifest assigns one TypeDef alias to multiple physical rows")
    }
    if (rows.any { it.directEdges.size != it.directEdges.toSet().size }) {
        add("the $side complete-emission manifest contains duplicate direct TypeDef edges")
    }
}

private fun compareCompleteEmissionMethodDefs(
    expected: List<DotNetGenericOwnerCompleteEmissionMethodDefRow>,
    actualEvidence: DotNetGenericOwnerCompleteEmissionRowsEvidence<DotNetGenericOwnerCompleteEmissionMethodDefRow>,
): DotNetGenericOwnerCompleteEmissionRowsComparison = compareCompleteEmissionKeyedRows(
    "MethodDef",
    expected,
    actualEvidence,
    DotNetGenericOwnerCompleteEmissionMethodDefRow::identityKey,
    expectedStructuralConflicts = if (expected.hasDuplicateKeys(
            DotNetGenericOwnerCompleteEmissionMethodDefRow::identityKey,
        )) {
        listOf("the expected complete-emission manifest contains duplicate physical MethodDef rows")
    } else {
        emptyList()
    },
    actualStructuralConflicts = { side, rows ->
        if (rows.hasDuplicateKeys(DotNetGenericOwnerCompleteEmissionMethodDefRow::identityKey)) {
            listOf("the $side complete-emission manifest contains duplicate physical MethodDef rows")
        } else {
            emptyList()
        }
    },
) { expectedRow, actualRow ->
    expectedRow.identityKey == actualRow.identityKey &&
            expectedRow.header == actualRow.header &&
            completeEmissionGenericParametersMatch(
                expectedRow.genericParameters,
                actualRow.genericParameters,
            )
}

private fun compareCompleteEmissionMethodImpls(
    expected: List<DotNetGenericOwnerCompleteEmissionMethodImplRow>,
    actualEvidence: DotNetGenericOwnerCompleteEmissionRowsEvidence<DotNetGenericOwnerCompleteEmissionMethodImplRow>,
): DotNetGenericOwnerCompleteEmissionRowsComparison {
    val expectedConflicts = if (expected.hasDuplicates()) {
        listOf("the expected complete-emission manifest contains duplicate MethodImpl rows")
    } else {
        emptyList()
    }
    if (expectedConflicts.isNotEmpty()) return conflictCompleteEmissionRows(expectedConflicts)
    return when (actualEvidence) {
        is DotNetGenericOwnerCompleteEmissionRowsEvidence.Conflict ->
            conflictCompleteEmissionRows(listOf(actualEvidence.reason))
        is DotNetGenericOwnerCompleteEmissionRowsEvidence.Unavailable ->
            unavailableCompleteEmissionRows(actualEvidence.reason)
        is DotNetGenericOwnerCompleteEmissionRowsEvidence.Known -> {
            val actual = actualEvidence.rows
            when {
                actual.hasDuplicates() -> conflictCompleteEmissionRows(
                    listOf("the actual complete-emission manifest contains duplicate MethodImpl rows"),
                )
                (actual.toSet() - expected.toSet()).isNotEmpty() -> conflictCompleteEmissionRows(
                    listOf("the actual complete-emission manifest contains extra or structurally different MethodImpl rows"),
                )
                (expected.toSet() - actual.toSet()).isNotEmpty() -> unavailableCompleteEmissionRows(
                    "the actual complete-emission manifest is missing expected MethodImpl rows",
                )
                else -> matchingCompleteEmissionRows()
            }
        }
    }
}

private inline fun <K, R> compareCompleteEmissionKeyedRows(
    rowKind: String,
    expected: List<R>,
    actualEvidence: DotNetGenericOwnerCompleteEmissionRowsEvidence<R>,
    key: (R) -> K,
    expectedStructuralConflicts: List<String>,
    actualStructuralConflicts: (String, List<R>) -> List<String>,
    rowsMatch: (R, R) -> Boolean,
): DotNetGenericOwnerCompleteEmissionRowsComparison {
    if (expectedStructuralConflicts.isNotEmpty()) return conflictCompleteEmissionRows(expectedStructuralConflicts)
    return when (actualEvidence) {
        is DotNetGenericOwnerCompleteEmissionRowsEvidence.Conflict ->
            conflictCompleteEmissionRows(listOf(actualEvidence.reason))
        is DotNetGenericOwnerCompleteEmissionRowsEvidence.Unavailable ->
            unavailableCompleteEmissionRows(actualEvidence.reason)
        is DotNetGenericOwnerCompleteEmissionRowsEvidence.Known -> {
            val actual = actualEvidence.rows
            val actualConflicts = actualStructuralConflicts("actual", actual)
            if (actualConflicts.isNotEmpty()) return conflictCompleteEmissionRows(actualConflicts)
            val expectedByKey = expected.associateBy(key)
            val actualByKey = actual.associateBy(key)
            when {
                (actualByKey.keys - expectedByKey.keys).isNotEmpty() -> conflictCompleteEmissionRows(
                    listOf("the actual complete-emission manifest contains extra $rowKind rows"),
                )
                expectedByKey.keys.intersect(actualByKey.keys).any { identity ->
                    !rowsMatch(expectedByKey.getValue(identity), actualByKey.getValue(identity))
                } -> conflictCompleteEmissionRows(
                    listOf("the expected and actual complete-emission $rowKind shapes differ for the same identity"),
                )
                (expectedByKey.keys - actualByKey.keys).isNotEmpty() -> unavailableCompleteEmissionRows(
                    "the actual complete-emission manifest is missing expected $rowKind rows",
                )
                else -> matchingCompleteEmissionRows()
            }
        }
    }
}

private fun matchingCompleteEmissionRows() = DotNetGenericOwnerCompleteEmissionRowsComparison(
    DotNetGenericOwnerPhysicalMethodDefEmissionComparisonStatus.MATCH,
    diagnostics = emptyList(),
)

private fun unavailableCompleteEmissionRows(reason: String) = DotNetGenericOwnerCompleteEmissionRowsComparison(
    DotNetGenericOwnerPhysicalMethodDefEmissionComparisonStatus.UNAVAILABLE,
    diagnostics = listOf(reason),
)

private fun conflictCompleteEmissionRows(reasons: List<String>) = DotNetGenericOwnerCompleteEmissionRowsComparison(
    DotNetGenericOwnerPhysicalMethodDefEmissionComparisonStatus.CONFLICT,
    diagnostics = reasons,
)

private inline fun <T, K> List<T>.hasDuplicateKeys(key: (T) -> K): Boolean =
    map(key).hasDuplicates()

private fun <T> List<T>.hasDuplicates(): Boolean = size != toSet().size
