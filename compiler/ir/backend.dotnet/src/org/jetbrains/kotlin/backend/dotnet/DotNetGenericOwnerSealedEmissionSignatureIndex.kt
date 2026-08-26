/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

/** One final TypeDef row, including physical facts which do not belong to the BOUND model. */
internal data class DotNetGenericOwnerSealedEmissionTypeDefRow(
    val structural: DotNetGenericOwnerCompleteEmissionTypeDefRow,
    val physicalPath: List<String>,
    val flags: DotNetIlRawTypeDefFlags,
) {
    init {
        require(physicalPath.isNotEmpty() && physicalPath.all(String::isNotEmpty)) {
            "a final-emission TypeDef requires a non-empty physical path"
        }
    }
}

/** One final MethodDef row with its exact emitted name and complete supported flag decisions. */
internal data class DotNetGenericOwnerSealedEmissionMethodDefRow(
    val structural: DotNetGenericOwnerCompleteEmissionMethodDefRow,
    val physicalName: String,
    val visibility: DotNetIlRawMethodDefVisibility,
    val dispatch: DotNetIlRawMethodDefDispatch,
    val isHideBySig: Boolean,
    val isSpecialName: Boolean,
    val isRuntimeSpecialName: Boolean,
) {
    init {
        require(physicalName.isNotEmpty()) {
            "a final-emission MethodDef requires a non-empty physical name"
        }
    }
}

/** Transactional actual-emission input. No BOUND declaration can occur in this evidence. */
internal sealed interface DotNetGenericOwnerSealedEmissionManifestEvidence {
    data class Known(
        val typeDefs: List<DotNetGenericOwnerSealedEmissionTypeDefRow>,
        val methodDefs: List<DotNetGenericOwnerSealedEmissionMethodDefRow>,
        val methodImpls: List<DotNetGenericOwnerCompleteEmissionMethodImplRow>,
    ) : DotNetGenericOwnerSealedEmissionManifestEvidence

    data class Unavailable(val reason: String) : DotNetGenericOwnerSealedEmissionManifestEvidence {
        init {
            require(reason.isNotEmpty()) { "unavailable sealed-emission evidence requires a reason" }
        }
    }

    data class Conflict(val reason: String) : DotNetGenericOwnerSealedEmissionManifestEvidence {
        init {
            require(reason.isNotEmpty()) { "conflicting sealed-emission evidence requires a reason" }
        }
    }
}

/**
 * Actual-only final physical authority for one bounded complete-emission family.
 *
 * The index deliberately exposes no enumeration or name lookup. A caller must already possess a
 * selected physical key; neither a physical name nor an earlier declaration index can create one.
 */
internal class DotNetGenericOwnerSealedEmissionSignatureIndex private constructor(
    typeDefs: List<DotNetGenericOwnerSealedEmissionTypeDefRow>,
    methodDefs: List<DotNetGenericOwnerSealedEmissionMethodDefRow>,
    methodImpls: List<DotNetGenericOwnerCompleteEmissionMethodImplRow>,
) {
    val epoch: DotNetGenericOwnerPhysicalAuthorityEpoch
        get() = DotNetGenericOwnerPhysicalAuthorityEpoch.SEALED_EMISSION_SIGNATURE_INDEX

    private val typeDefsByKey = typeDefs.associateByTo(linkedMapOf()) { row ->
        row.structural.identityKey
    }
    private val methodDefsByKey = methodDefs.associateByTo(linkedMapOf()) { row ->
        row.structural.identityKey
    }
    private val stableMethodImpls = methodImpls.toList()

    val typeDefCount: Int
        get() = typeDefsByKey.size

    val methodDefCount: Int
        get() = methodDefsByKey.size

    val methodImplCount: Int
        get() = stableMethodImpls.size

    fun typeDef(
        key: DotNetGenericOwnerPhysicalMethodDefEmissionTypeKey,
    ): DotNetGenericOwnerPhysicalBindingResult<DotNetGenericOwnerSealedEmissionTypeDefRow> =
        typeDefsByKey[key]?.let { row -> DotNetGenericOwnerPhysicalBindingResult.Bound(row) }
            ?: DotNetGenericOwnerPhysicalBindingResult.Unavailable

    fun methodDef(
        key: DotNetGenericOwnerPhysicalMethodDefEmissionMethodKey,
    ): DotNetGenericOwnerPhysicalBindingResult<DotNetGenericOwnerSealedEmissionMethodDefRow> =
        methodDefsByKey[key]?.let { row -> DotNetGenericOwnerPhysicalBindingResult.Bound(row) }
            ?: DotNetGenericOwnerPhysicalBindingResult.Unavailable

    fun methodImpls(
        implementingType: DotNetGenericOwnerPhysicalMethodDefEmissionTypeKey,
        body: DotNetGenericOwnerPhysicalMethodDefEmissionMethodKey,
    ): List<DotNetGenericOwnerCompleteEmissionMethodImplRow> = stableMethodImpls.filter { row ->
        row.implementingTypeDefKey == implementingType && row.bodyMethodDefKey == body
    }

    companion object {
        /**
         * Builds the sealed index only from [actual]. [expectedStructural] is an independent
         * BOUND contract used for validation; none of its rows are copied into the result.
         */
        fun bind(
            expectedStructural: DotNetGenericOwnerCompleteEmissionManifest,
            actual: DotNetGenericOwnerSealedEmissionManifestEvidence,
        ): DotNetGenericOwnerPhysicalBindingResult<DotNetGenericOwnerSealedEmissionSignatureIndex> =
            inspect(expectedStructural, actual).binding

        fun inspect(
            expectedStructural: DotNetGenericOwnerCompleteEmissionManifest,
            actual: DotNetGenericOwnerSealedEmissionManifestEvidence,
        ): DotNetGenericOwnerSealedEmissionSignatureIndexInspection {
            val rows = inspectDotNetGenericOwnerSealedEmissionRows(expectedStructural, actual)
            val binding = when (val result = rows.binding) {
                is DotNetGenericOwnerPhysicalBindingResult.Bound ->
                    DotNetGenericOwnerPhysicalBindingResult.Bound(
                        DotNetGenericOwnerSealedEmissionSignatureIndex(
                            typeDefs = result.value.typeDefs,
                            methodDefs = result.value.methodDefs,
                            methodImpls = result.value.methodImpls,
                        ),
                    )
                is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                    DotNetGenericOwnerPhysicalBindingResult.Conflict(result.reason)
                DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                    DotNetGenericOwnerPhysicalBindingResult.Unavailable
            }
            return DotNetGenericOwnerSealedEmissionSignatureIndexInspection(binding, rows.diagnostics)
        }
    }
}

internal data class DotNetGenericOwnerSealedEmissionSignatureIndexInspection(
    val binding: DotNetGenericOwnerPhysicalBindingResult<DotNetGenericOwnerSealedEmissionSignatureIndex>,
    val diagnostics: List<String>,
) {
    init {
        require(diagnostics.all(String::isNotEmpty)) {
            "sealed-emission diagnostics must be non-empty"
        }
        require(binding is DotNetGenericOwnerPhysicalBindingResult.Bound || diagnostics.isNotEmpty()) {
            "an unavailable or conflicting sealed-emission result requires diagnostics"
        }
    }
}

internal fun bindDotNetGenericOwnerSealedEmissionSignatureIndex(
    expectedStructural: DotNetGenericOwnerCompleteEmissionManifest,
    actual: DotNetGenericOwnerSealedEmissionManifestEvidence,
): DotNetGenericOwnerPhysicalBindingResult<DotNetGenericOwnerSealedEmissionSignatureIndex> =
    DotNetGenericOwnerSealedEmissionSignatureIndex.bind(expectedStructural, actual)

internal fun inspectDotNetGenericOwnerSealedEmissionSignatureIndex(
    expectedStructural: DotNetGenericOwnerCompleteEmissionManifest,
    actual: DotNetGenericOwnerSealedEmissionManifestEvidence,
): DotNetGenericOwnerSealedEmissionSignatureIndexInspection =
    DotNetGenericOwnerSealedEmissionSignatureIndex.inspect(expectedStructural, actual)

private data class ValidatedSealedEmissionRows(
    val typeDefs: List<DotNetGenericOwnerSealedEmissionTypeDefRow>,
    val methodDefs: List<DotNetGenericOwnerSealedEmissionMethodDefRow>,
    val methodImpls: List<DotNetGenericOwnerCompleteEmissionMethodImplRow>,
)

private data class ValidatedSealedEmissionRowsInspection(
    val binding: DotNetGenericOwnerPhysicalBindingResult<ValidatedSealedEmissionRows>,
    val diagnostics: List<String>,
)

private fun inspectDotNetGenericOwnerSealedEmissionRows(
    expectedStructural: DotNetGenericOwnerCompleteEmissionManifest,
    actual: DotNetGenericOwnerSealedEmissionManifestEvidence,
): ValidatedSealedEmissionRowsInspection = when (actual) {
    is DotNetGenericOwnerSealedEmissionManifestEvidence.Unavailable ->
        ValidatedSealedEmissionRowsInspection(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            listOf(actual.reason),
        )
    is DotNetGenericOwnerSealedEmissionManifestEvidence.Conflict ->
        ValidatedSealedEmissionRowsInspection(
            DotNetGenericOwnerPhysicalBindingResult.Conflict(actual.reason),
            listOf(actual.reason),
        )
    is DotNetGenericOwnerSealedEmissionManifestEvidence.Known ->
        inspectKnownDotNetGenericOwnerSealedEmissionRows(expectedStructural, actual)
}

private fun inspectKnownDotNetGenericOwnerSealedEmissionRows(
    expectedStructural: DotNetGenericOwnerCompleteEmissionManifest,
    actual: DotNetGenericOwnerSealedEmissionManifestEvidence.Known,
): ValidatedSealedEmissionRowsInspection {
    val validation = validateActualSealedEmissionRows(actual)
    val actualStructural = DotNetGenericOwnerCompleteEmissionManifest(
        typeDefs = actual.typeDefs.map { row -> row.structural },
        methodDefs = actual.methodDefs.map { row -> row.structural },
        methodImpls = actual.methodImpls,
    )
    val structuralComparison = compareDotNetGenericOwnerCompleteEmissionManifest(
        expectedStructural,
        actualStructural.asKnownEvidence(),
    )
    val structuralDiagnostics = structuralComparison.diagnostics()
    val conflicts = buildList {
        addAll(validation.conflicts)
        if (structuralComparison.status ==
            DotNetGenericOwnerPhysicalMethodDefEmissionComparisonStatus.CONFLICT
        ) {
            addAll(structuralDiagnostics)
        }
    }.distinct()
    if (conflicts.isNotEmpty()) {
        val reason = conflicts.joinToString("; ")
        return ValidatedSealedEmissionRowsInspection(
            DotNetGenericOwnerPhysicalBindingResult.Conflict(reason),
            conflicts,
        )
    }

    val unavailable = buildList {
        addAll(validation.unavailable)
        if (structuralComparison.status ==
            DotNetGenericOwnerPhysicalMethodDefEmissionComparisonStatus.UNAVAILABLE
        ) {
            addAll(structuralDiagnostics)
        }
    }.distinct()
    if (unavailable.isNotEmpty()) {
        return ValidatedSealedEmissionRowsInspection(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            unavailable,
        )
    }

    return ValidatedSealedEmissionRowsInspection(
        DotNetGenericOwnerPhysicalBindingResult.Bound(
            ValidatedSealedEmissionRows(
                typeDefs = actual.typeDefs.map { row -> row.frozen() },
                methodDefs = actual.methodDefs.map { row -> row.frozen() },
                methodImpls = actual.methodImpls.map { row -> row.frozen() },
            ),
        ),
        emptyList(),
    )
}

private data class SealedEmissionValidation(
    val unavailable: List<String>,
    val conflicts: List<String>,
)

private fun validateActualSealedEmissionRows(
    actual: DotNetGenericOwnerSealedEmissionManifestEvidence.Known,
): SealedEmissionValidation {
    val unavailable = mutableListOf<String>()
    val conflicts = mutableListOf<String>()
    val typeRowsByKey = actual.typeDefs.groupBy { row -> row.structural.identityKey }
    val methodRowsByKey = actual.methodDefs.groupBy { row -> row.structural.identityKey }

    if (typeRowsByKey.values.any { rows -> rows.size != 1 }) {
        conflicts += "the final-emission evidence contains duplicate physical TypeDef keys"
    }
    if (methodRowsByKey.values.any { rows -> rows.size != 1 }) {
        conflicts += "the final-emission evidence contains duplicate physical MethodDef keys"
    }
    if (actual.methodImpls.size != actual.methodImpls.toSet().size) {
        conflicts += "the final-emission evidence contains duplicate MethodImpl rows"
    }

    val physicalPaths = actual.typeDefs.map { row -> row.physicalPath }
    if (physicalPaths.size != physicalPaths.toSet().size) {
        conflicts += "the final-emission evidence assigns one physical TypeDef path more than once"
    }
    if (actual.typeDefs.any { row ->
            row.physicalPath.isEmpty() || row.physicalPath.any(String::isEmpty)
        }
    ) {
        conflicts += "the final-emission evidence contains an empty physical TypeDef path"
    }

    val aliasesByPhysicalType = actual.typeDefs.flatMap { row ->
        row.structural.aliases.map { alias -> alias to row.structural.identityKey }
    }.groupBy({ pair -> pair.first }, { pair -> pair.second })
    if (actual.typeDefs.any { row ->
            row.structural.aliases.isEmpty() ||
                    row.structural.aliases.size != row.structural.aliases.toSet().size
        } || aliasesByPhysicalType.values.any { keys -> keys.toSet().size != 1 }
    ) {
        conflicts += "the final-emission evidence contains an empty, duplicate, or split TypeDef alias set"
    }

    val uniqueTypes = typeRowsByKey.mapNotNull { entry -> entry.value.singleOrNull()?.let { entry.key to it } }.toMap()
    val uniqueMethods = methodRowsByKey.mapNotNull { entry ->
        entry.value.singleOrNull()?.let { entry.key to it }
    }.toMap()

    actual.typeDefs.forEach { row ->
        val structural = row.structural
        if (row.flags.visibility.isNested != (row.physicalPath.size > 1)) {
            conflicts += "a final TypeDef's exact nesting flags contradict its physical path"
        }
        if (row.flags.isInterface !=
            (structural.category == DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE)
        ) {
            conflicts += "a final TypeDef's exact interface flag contradicts its structural category"
        }
        structural.genericParameters.forEach { parameter ->
            parameter.constraints.forEach { constraint ->
                validateSealedCarrier(
                    constraint,
                    uniqueTypes,
                    uniqueMethods,
                    allowedTypeParameterBinder = structural.identityKey,
                    allowedMethodParameterBinder = null,
                    unavailable,
                    conflicts,
                )
            }
        }
        structural.directEdges.forEach { edge ->
            validateSealedCarrier(
                edge.target,
                uniqueTypes,
                uniqueMethods,
                allowedTypeParameterBinder = structural.identityKey,
                allowedMethodParameterBinder = null,
                unavailable,
                conflicts,
            )
        }
    }

    val coordinates = actual.methodDefs.map { row -> row.clrCoordinate() }
    if (coordinates.size != coordinates.toSet().size) {
        conflicts += "the final-emission evidence assigns one CLR MethodDef coordinate more than once"
    }
    actual.methodDefs.forEach { row ->
        val structural = row.structural
        val header = structural.header
        val owner = uniqueTypes[header.owner]?.structural
        if (owner == null) {
            unavailable += "a final MethodDef owner has no final TypeDef row"
        } else if (header.ownerGenericArity != owner.genericArity || header.ownerCategory != owner.category) {
            conflicts += "a final MethodDef header contradicts its final TypeDef owner"
        }
        if (row.visibility.toSealedVisibility() != header.visibility ||
            row.dispatch.toSealedDispatch() != header.dispatch ||
            row.dispatch.isInstance != header.isInstance
        ) {
            conflicts += "a final MethodDef's exact flags contradict its structural header"
        }
        if (row.isRuntimeSpecialName && !row.isSpecialName) {
            conflicts += "a final MethodDef cannot carry rtspecialname without specialname"
        }
        if (header.genericArity != 0) {
            unavailable += "the bounded sealed family does not capture MethodDef GenericParam rows"
        }
        listOfNotNull(header.receiverCarrier).plus(header.ordinaryParameterCarriers).forEach { carrier ->
            validateSealedCarrier(
                carrier,
                uniqueTypes,
                uniqueMethods,
                allowedTypeParameterBinder = header.owner,
                allowedMethodParameterBinder = structural.identityKey,
                unavailable,
                conflicts,
            )
        }
        when (val result = header.result) {
            is DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.Direct -> listOf(result.carrier)
            is DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.SplitNullable -> listOf(result.payload)
            DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.Void -> emptyList()
        }.forEach { carrier ->
            validateSealedCarrier(
                carrier,
                uniqueTypes,
                uniqueMethods,
                allowedTypeParameterBinder = header.owner,
                allowedMethodParameterBinder = structural.identityKey,
                unavailable,
                conflicts,
            )
        }
    }

    actual.methodImpls.forEach { row ->
        if (row.implementingTypeDefKey !in uniqueTypes) {
            unavailable += "a final MethodImpl implementing type has no final TypeDef row"
        }
        if (row.bodyMethodDefKey !in uniqueMethods || row.declarationMethodDefKey !in uniqueMethods) {
            unavailable += "a final MethodImpl endpoint has no final MethodDef row"
        }
        validateSealedCarrier(
            row.declarationOwner,
            uniqueTypes,
            uniqueMethods,
            allowedTypeParameterBinder = row.implementingTypeDefKey,
            allowedMethodParameterBinder = null,
            unavailable,
            conflicts,
        )
    }

    return SealedEmissionValidation(unavailable, conflicts)
}

private data class SealedMethodDefCoordinate(
    val owner: DotNetGenericOwnerPhysicalMethodDefEmissionTypeKey,
    val physicalName: String,
    val isInstance: Boolean,
    val genericArity: Int,
    val parameters: List<SealedMethodDefCoordinateCarrier>,
)

/** Structured CLI spelling used only for overload coordinates; parameter binder identities do not print. */
private sealed interface SealedMethodDefCoordinateCarrier {
    data class Leaf(val kind: DotNetGenericOwnerPhysicalTypeKind) : SealedMethodDefCoordinateCarrier
    data class OwnerParameter(val index: Int) : SealedMethodDefCoordinateCarrier
    data class MethodParameter(val index: Int) : SealedMethodDefCoordinateCarrier
    data class Construction(
        val definition: DotNetGenericOwnerPhysicalMethodDefEmissionTypeKey,
        val arguments: List<SealedMethodDefCoordinateCarrier>,
    ) : SealedMethodDefCoordinateCarrier
    data class SzArray(val element: SealedMethodDefCoordinateCarrier) : SealedMethodDefCoordinateCarrier
    data class ByReference(val element: SealedMethodDefCoordinateCarrier) : SealedMethodDefCoordinateCarrier
    data object Other : SealedMethodDefCoordinateCarrier
}

private fun DotNetGenericOwnerSealedEmissionMethodDefRow.clrCoordinate(): SealedMethodDefCoordinate =
    structural.header.let { header ->
        SealedMethodDefCoordinate(
            header.owner,
            physicalName,
            header.isInstance,
            header.genericArity,
            buildList {
                addAll(header.ordinaryParameterCarriers.map { carrier -> carrier.coordinateCarrier() })
                if (header.result is DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.SplitNullable) {
                    add(SealedMethodDefCoordinateCarrier.ByReference(
                        SealedMethodDefCoordinateCarrier.Leaf(DotNetGenericOwnerPhysicalTypeKind.BOOLEAN),
                    ))
                }
            },
        )
    }

private fun DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.coordinateCarrier():
        SealedMethodDefCoordinateCarrier = when (this) {
    is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.Leaf ->
        SealedMethodDefCoordinateCarrier.Leaf(kind)
    is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.OwnerParameter ->
        SealedMethodDefCoordinateCarrier.OwnerParameter(index)
    is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.MethodParameter ->
        SealedMethodDefCoordinateCarrier.MethodParameter(index)
    is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.Construction ->
        SealedMethodDefCoordinateCarrier.Construction(
            definition,
            arguments.map { argument -> argument.coordinateCarrier() },
        )
    is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.SzArray ->
        SealedMethodDefCoordinateCarrier.SzArray(element.coordinateCarrier())
    is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.ByReference ->
        SealedMethodDefCoordinateCarrier.ByReference(element.coordinateCarrier())
    DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.Other -> SealedMethodDefCoordinateCarrier.Other
}

private fun validateSealedCarrier(
    carrier: DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape,
    typeDefs: Map<
            DotNetGenericOwnerPhysicalMethodDefEmissionTypeKey,
            DotNetGenericOwnerSealedEmissionTypeDefRow,
            >,
    methodDefs: Map<
            DotNetGenericOwnerPhysicalMethodDefEmissionMethodKey,
            DotNetGenericOwnerSealedEmissionMethodDefRow,
            >,
    allowedTypeParameterBinder: DotNetGenericOwnerPhysicalMethodDefEmissionTypeKey?,
    allowedMethodParameterBinder: DotNetGenericOwnerPhysicalMethodDefEmissionMethodKey?,
    unavailable: MutableList<String>,
    conflicts: MutableList<String>,
) {
    when (carrier) {
        is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.Leaf -> Unit
        is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.OwnerParameter -> {
            val binder = typeDefs[carrier.binder]?.structural
            when {
                binder == null -> unavailable += "a final carrier's TypeDef binder has no final row"
                carrier.binder != allowedTypeParameterBinder ->
                    conflicts += "a final carrier references a TypeDef parameter outside its physical scope"
                carrier.index !in 0 until binder.genericArity ->
                    conflicts += "a final carrier references a TypeDef parameter outside its arity"
            }
        }
        is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.MethodParameter -> {
            val binder = methodDefs[carrier.binder]?.structural
            when {
                binder == null -> unavailable += "a final carrier's MethodDef binder has no final row"
                carrier.binder != allowedMethodParameterBinder ->
                    conflicts += "a final carrier references a MethodDef parameter outside its physical scope"
                carrier.index !in 0 until binder.header.genericArity ->
                    conflicts += "a final carrier references a MethodDef parameter outside its arity"
            }
        }
        is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.Construction -> {
            val definition = typeDefs[carrier.definition]?.structural
            if (definition == null) {
                unavailable += "a final construction has no final TypeDef row"
            } else if (carrier.arguments.size != definition.genericArity) {
                conflicts += "a final construction contradicts its TypeDef arity"
            }
            carrier.arguments.forEach { argument ->
                validateSealedCarrier(
                    argument,
                    typeDefs,
                    methodDefs,
                    allowedTypeParameterBinder,
                    allowedMethodParameterBinder,
                    unavailable,
                    conflicts,
                )
            }
        }
        is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.SzArray ->
            validateSealedCarrier(
                carrier.element,
                typeDefs,
                methodDefs,
                allowedTypeParameterBinder,
                allowedMethodParameterBinder,
                unavailable,
                conflicts,
            )
        is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.ByReference ->
            validateSealedCarrier(
                carrier.element,
                typeDefs,
                methodDefs,
                allowedTypeParameterBinder,
                allowedMethodParameterBinder,
                unavailable,
                conflicts,
            )
        DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.Other ->
            unavailable += "a final carrier is outside the bounded sealed vocabulary"
    }
}

private fun DotNetIlRawMethodDefVisibility.toSealedVisibility():
        DotNetGenericOwnerPhysicalMethodDefEmissionVisibility = when (this) {
    DotNetIlRawMethodDefVisibility.PUBLIC -> DotNetGenericOwnerPhysicalMethodDefEmissionVisibility.PUBLIC
    DotNetIlRawMethodDefVisibility.FAMILY -> DotNetGenericOwnerPhysicalMethodDefEmissionVisibility.FAMILY
    DotNetIlRawMethodDefVisibility.ASSEMBLY -> DotNetGenericOwnerPhysicalMethodDefEmissionVisibility.ASSEMBLY
    DotNetIlRawMethodDefVisibility.FAMILY_OR_ASSEMBLY ->
        DotNetGenericOwnerPhysicalMethodDefEmissionVisibility.FAMILY_OR_ASSEMBLY
    DotNetIlRawMethodDefVisibility.FAMILY_AND_ASSEMBLY ->
        DotNetGenericOwnerPhysicalMethodDefEmissionVisibility.FAMILY_AND_ASSEMBLY
    DotNetIlRawMethodDefVisibility.PRIVATE -> DotNetGenericOwnerPhysicalMethodDefEmissionVisibility.PRIVATE
}

private fun DotNetIlRawMethodDefDispatch.toSealedDispatch(): DotNetGenericOwnerPhysicalMemberDispatch = when {
    isAbstract -> DotNetGenericOwnerPhysicalMemberDispatch.ABSTRACT
    isVirtual && !isFinal -> DotNetGenericOwnerPhysicalMemberDispatch.OVERRIDABLE
    else -> DotNetGenericOwnerPhysicalMemberDispatch.FINAL
}

private fun DotNetGenericOwnerCompleteEmissionManifestComparison.diagnostics(): List<String> =
    typeDefs.diagnostics + methodDefs.diagnostics + methodImpls.diagnostics

private fun DotNetGenericOwnerSealedEmissionTypeDefRow.frozen() = copy(
    structural = structural.frozen(),
    physicalPath = physicalPath.toList(),
)

private fun DotNetGenericOwnerSealedEmissionMethodDefRow.frozen() = copy(
    structural = structural.frozen(),
)

private fun DotNetGenericOwnerCompleteEmissionTypeDefRow.frozen() = copy(
    aliases = aliases.toList(),
    genericParameters = genericParameters.map { parameter ->
        parameter.copy(constraints = parameter.constraints.map { carrier -> carrier.frozen() })
    },
    directEdges = directEdges.map { edge -> edge.copy(target = edge.target.frozen()) },
)

private fun DotNetGenericOwnerCompleteEmissionMethodDefRow.frozen() = copy(
    header = header.copy(
        receiverCarrier = header.receiverCarrier?.frozen(),
        ordinaryParameterCarriers = header.ordinaryParameterCarriers.map { carrier -> carrier.frozen() },
        result = header.result.frozen(),
    ),
)

private fun DotNetGenericOwnerCompleteEmissionMethodImplRow.frozen() = copy(
    declarationOwner = declarationOwner.frozen(),
)

private fun DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.frozen():
        DotNetGenericOwnerPhysicalMethodDefEmissionResultShape = when (this) {
    is DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.Direct -> copy(carrier = carrier.frozen())
    is DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.SplitNullable -> copy(payload = payload.frozen())
    DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.Void -> this
}

private fun DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.frozen():
        DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape = when (this) {
    is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.Construction ->
        copy(arguments = arguments.map { argument -> argument.frozen() })
    is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.SzArray -> copy(element = element.frozen())
    is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.ByReference -> copy(element = element.frozen())
    else -> this
}
