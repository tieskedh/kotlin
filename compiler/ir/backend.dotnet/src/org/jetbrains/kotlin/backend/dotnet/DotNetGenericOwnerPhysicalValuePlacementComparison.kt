/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithName
import org.jetbrains.kotlin.ir.declarations.IrPackageFragment
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable

/** IR-bearing correlation retained only until one backend invocation has emitted its final IL. */
internal data class DotNetGenericOwnerPhysicalValueShadowRecord(
    val physicalFunction: IrSimpleFunctionSymbol,
    val variable: IrValueSymbol,
    val snapshot: DotNetGenericOwnerPhysicalValueShadowSnapshot,
    val predictedStorage: DotNetGenericOwnerPhysicalStorageFact?,
)

/** Neutral structural form of one emitter-selected local carrier in the current compilation. */
internal sealed interface DotNetGenericOwnerObservedLocalCarrier {
    data object Object : DotNetGenericOwnerObservedLocalCarrier

    data class LocalConstruction(
        val definition: DotNetGenericOwnerPhysicalTypeDefIdentity.Local,
        val parameterBinder: DotNetGenericOwnerPhysicalTypeDefIdentity.Local,
        val ownerParameterIndices: List<Int>,
    ) : DotNetGenericOwnerObservedLocalCarrier {
        init {
            require(ownerParameterIndices.isNotEmpty() && ownerParameterIndices.all { index -> index >= 0 }) {
                "an observed local construction requires non-negative owner-parameter arguments"
            }
        }
    }

    data class SemanticCapability(
        val owner: IrClassSymbol,
    ) : DotNetGenericOwnerObservedLocalCarrier

    data class Unbindable(
        val reason: String,
    ) : DotNetGenericOwnerObservedLocalCarrier {
        init {
            require(reason.isNotEmpty()) { "an unbindable emitted local carrier requires a reason" }
        }
    }
}

/** One ordinary `IrVariable`-backed local which survived the final successful render fixpoint. */
internal data class DotNetGenericOwnerPhysicalValueLocalPlacementObservation(
    val physicalFunction: IrFunctionSymbol,
    val physicalMethodOwner: DotNetGenericOwnerPhysicalTypeDefIdentity.Local?,
    val variable: IrValueSymbol,
    val slotIndex: Int,
    val carrier: DotNetGenericOwnerObservedLocalCarrier,
    val selectionKind: DotNetGenericOwnerPhysicalValueLocalSelectionKind,
) {
    init {
        require(slotIndex >= 0) { "an emitted local placement requires a non-negative slot index" }
    }
}

/**
 * Joins shadow predictions and actual local slots only after emission has completed.
 *
 * This function is diagnostic: neither its inputs nor its result are available to local-slot
 * selection, expression conversion, routing, state planning, or ABI publication.
 */
internal fun compareDotNetGenericOwnerPhysicalValueLocalPlacements(
    records: List<DotNetGenericOwnerPhysicalValueShadowRecord>,
    actuals: List<DotNetGenericOwnerPhysicalValueLocalPlacementObservation>,
): List<DotNetGenericOwnerPhysicalValuePlacementComparisonSnapshot> {
    val preByKey = records
        .filter { record ->
            record.snapshot.phase == DotNetGenericOwnerPhysicalValueShadowPhase.PRE_SEMANTIC_REMAP
        }
        .groupBy { record -> PhysicalLocalIdentity(record.physicalFunction, record.variable) }
    val actualsByKey = actuals.groupBy { actual ->
        PhysicalLocalIdentity(actual.physicalFunction, actual.variable)
    }

    return records.asSequence()
        .filter { record ->
            record.snapshot.phase == DotNetGenericOwnerPhysicalValueShadowPhase.POST_FINAL_ROUTING
        }
        .map { record ->
            val snapshot = record.snapshot
            val key = PhysicalLocalIdentity(record.physicalFunction, record.variable)
            val preRecords = preByKey[key].orEmpty()
            val continuity = when {
                preRecords.isEmpty() -> DotNetGenericOwnerPhysicalValuePlacementContinuity.NOT_OBSERVED
                preRecords.size == 1 && preRecords.single().sameValueFactAs(record) ->
                    DotNetGenericOwnerPhysicalValuePlacementContinuity.STABLE
                else -> DotNetGenericOwnerPhysicalValuePlacementContinuity.DIVERGED
            }
            val matchingActuals = actualsByKey[key].orEmpty()
            val uniqueActual = matchingActuals.singleOrNull()
            val comparison = when {
                continuity == DotNetGenericOwnerPhysicalValuePlacementContinuity.DIVERGED ->
                    PlacementComparison(
                        relation = DotNetGenericOwnerPhysicalValuePlacementRelation.PRE_FINAL_DIVERGENCE,
                        actual = uniqueActual,
                        diagnostic = "pre-remap and final-routing facts do not identify one stable value",
                    )
                record.predictedStorage == null -> PlacementComparison(
                    relation = DotNetGenericOwnerPhysicalValuePlacementRelation.PREDICTION_UNSUPPORTED,
                    actual = uniqueActual,
                    diagnostic = snapshot.unsupportedReason ?: "the shadow did not predict storage",
                )
                matchingActuals.isEmpty() -> PlacementComparison(
                    relation = DotNetGenericOwnerPhysicalValuePlacementRelation.NOT_EMITTED,
                    diagnostic = "the final emitter products contain no matching IR local",
                )
                matchingActuals.size != 1 -> PlacementComparison(
                    relation = DotNetGenericOwnerPhysicalValuePlacementRelation.AMBIGUOUS,
                    diagnostic = "the same IR local was emitted in more than one physical MethodDef",
                )
                else -> comparePlacement(record.predictedStorage, checkNotNull(uniqueActual))
            }
            DotNetGenericOwnerPhysicalValuePlacementComparisonSnapshot(
                prediction = snapshot,
                actualPhysicalMethodOwnerName = comparison.actual?.physicalMethodOwner
                    ?.owner?.owner?.dotNetPhysicalValueStableName(),
                actualPhysicalMethodOwnerTypeDefView = comparison.actual?.physicalMethodOwner
                    ?.view?.toShadowView(),
                actualStorageCarrier = comparison.actual?.carrier?.toSnapshot()
                    ?: unknownPhysicalValueCarrierSnapshot,
                actualSelectionKind = comparison.actual?.selectionKind,
                continuity = continuity,
                relation = comparison.relation,
                diagnostic = comparison.diagnostic,
            )
        }
        .sortedWith(
            compareBy(
                { comparison: DotNetGenericOwnerPhysicalValuePlacementComparisonSnapshot ->
                    comparison.prediction.ownerName
                },
                { comparison -> comparison.prediction.sourceFunctionName },
                { comparison -> comparison.prediction.physicalFunctionName },
                { comparison -> comparison.prediction.functionRole.ordinal },
                { comparison -> comparison.prediction.variableName },
            ),
        )
        .toList()
}

/** IR symbols are correlation handles; their object identity, not an incidental equals contract, is authority. */
private class PhysicalLocalIdentity(
    private val function: IrFunctionSymbol,
    private val variable: IrValueSymbol,
) {
    override fun equals(other: Any?): Boolean = other is PhysicalLocalIdentity &&
            function === other.function && variable === other.variable

    override fun hashCode(): Int =
        31 * System.identityHashCode(function) + System.identityHashCode(variable)
}

private data class PlacementComparison(
    val relation: DotNetGenericOwnerPhysicalValuePlacementRelation,
    val actual: DotNetGenericOwnerPhysicalValueLocalPlacementObservation? = null,
    val diagnostic: String? = null,
)

private fun comparePlacement(
    predicted: DotNetGenericOwnerPhysicalStorageFact,
    actual: DotNetGenericOwnerPhysicalValueLocalPlacementObservation,
): PlacementComparison {
    val predictedCarrier = predicted.storageCarrier.carrier.toObservedCarrierOrNull()
        ?: return PlacementComparison(
            DotNetGenericOwnerPhysicalValuePlacementRelation.PREDICTION_UNSUPPORTED,
            actual,
            "the predicted carrier is outside the bounded local-placement vocabulary",
        )
    val actualCarrier = actual.carrier
    if (actualCarrier is DotNetGenericOwnerObservedLocalCarrier.Unbindable) {
        return PlacementComparison(
            DotNetGenericOwnerPhysicalValuePlacementRelation.ACTUAL_UNBINDABLE,
            actual,
            actualCarrier.reason,
        )
    }
    if (predictedCarrier == actualCarrier) {
        return PlacementComparison(DotNetGenericOwnerPhysicalValuePlacementRelation.MATCH, actual)
    }
    return PlacementComparison(
        DotNetGenericOwnerPhysicalValuePlacementRelation.DIFFERENT,
        actual,
        "the predicted and emitted local-slot carriers differ; this observation does not classify their conversion",
    )
}

private fun DotNetGenericOwnerPhysicalValueShadowRecord.sameValueFactAs(
    other: DotNetGenericOwnerPhysicalValueShadowRecord,
): Boolean = predictedStorage == other.predictedStorage && snapshot.let { left ->
    other.snapshot.let { right ->
        left.status == right.status &&
                left.initializerProducedCarrier == right.initializerProducedCarrier &&
                left.storageCarrier == right.storageCarrier &&
                left.guaranteeState == right.guaranteeState &&
                left.guaranteedViews.map { view -> view.carrier }.toSet() ==
                right.guaranteedViews.map { view -> view.carrier }.toSet() &&
                left.selectedViewLineage.associate { selection ->
                    selection.familyOwnerName to selection.view.carrier
                } == right.selectedViewLineage.associate { selection ->
                    selection.familyOwnerName to selection.view.carrier
                } &&
                left.initializerNullState == right.initializerNullState &&
                left.contentsNullState == right.contentsNullState &&
                left.unsupportedReason == right.unsupportedReason
    }
}

private fun DotNetGenericOwnerPhysicalCarrier.toObservedCarrierOrNull():
        DotNetGenericOwnerObservedLocalCarrier? = when {
    type == DotNetGenericOwnerSymbolicCarrierReference.objectCarrier() ->
        DotNetGenericOwnerObservedLocalCarrier.Object
    type is DotNetGenericOwnerSymbolicCarrierReference.Constructed ->
        type.toObservedCarrierOrNull()
    else -> null
}

private fun DotNetGenericOwnerSymbolicCarrierReference.Constructed.toObservedCarrierOrNull():
        DotNetGenericOwnerObservedLocalCarrier.LocalConstruction? {
    val localDefinition = definition as? DotNetGenericOwnerPhysicalTypeDefIdentity.Local ?: return null
    val parameters = arguments.map { argument ->
        argument as? DotNetGenericOwnerSymbolicCarrierReference.Parameter ?: return null
    }
    val binders = parameters.map { parameter ->
        (parameter.binder as? DotNetGenericOwnerPhysicalGenericBinderReference.Type)?.definition as?
                DotNetGenericOwnerPhysicalTypeDefIdentity.Local ?: return null
    }.distinct()
    val binder = binders.singleOrNull() ?: return null
    return DotNetGenericOwnerObservedLocalCarrier.LocalConstruction(
        localDefinition,
        binder,
        parameters.map { parameter -> parameter.index },
    )
}

internal fun DotNetGenericOwnerObservedLocalCarrier.toSnapshot():
        DotNetGenericOwnerPhysicalValueShadowCarrierSnapshot = when (this) {
    DotNetGenericOwnerObservedLocalCarrier.Object -> DotNetGenericOwnerPhysicalValueShadowCarrierSnapshot(
        DotNetGenericOwnerPhysicalValueShadowCarrierKind.OBJECT,
    )
    is DotNetGenericOwnerObservedLocalCarrier.LocalConstruction ->
        DotNetGenericOwnerPhysicalValueShadowCarrierSnapshot(
            kind = DotNetGenericOwnerPhysicalValueShadowCarrierKind.LOCAL_OWNER_CONSTRUCTION,
            localOwnerName = definition.owner.owner.dotNetPhysicalValueStableName(),
            ownerParameterIndices = ownerParameterIndices,
            localTypeDefView = definition.view?.toShadowView(),
            parameterBinderOwnerName = parameterBinder.owner.owner.dotNetPhysicalValueStableName(),
            parameterBinderTypeDefView = parameterBinder.view?.toShadowView(),
        )
    is DotNetGenericOwnerObservedLocalCarrier.SemanticCapability ->
        DotNetGenericOwnerPhysicalValueShadowCarrierSnapshot(
            kind = DotNetGenericOwnerPhysicalValueShadowCarrierKind.SEMANTIC_CAPABILITY,
            localOwnerName = owner.owner.dotNetPhysicalValueStableName(),
        )
    is DotNetGenericOwnerObservedLocalCarrier.Unbindable -> unknownPhysicalValueCarrierSnapshot
}

internal val unknownPhysicalValueCarrierSnapshot =
    DotNetGenericOwnerPhysicalValueShadowCarrierSnapshot(
        DotNetGenericOwnerPhysicalValueShadowCarrierKind.UNKNOWN,
    )

private fun DotNetGenericInterfaceView.toShadowView():
        DotNetGenericOwnerPhysicalValueShadowTypeDefView =
    DotNetGenericOwnerPhysicalValueShadowTypeDefView.valueOf(name)

internal fun IrClass.dotNetPhysicalValueStableName(): String {
    fqNameWhenAvailable?.asString()?.takeIf(String::isNotEmpty)?.let { return it }
    val components = mutableListOf<String>()
    var current: IrDeclarationParent? = this
    while (current != null) {
        when (current) {
            is IrDeclarationWithName -> current.name.asString()
                .takeIf(String::isNotEmpty)
                ?.let(components::add)
            is IrPackageFragment -> current.packageFqName.asString()
                .takeIf(String::isNotEmpty)
                ?.let(components::add)
        }
        current = (current as? IrDeclaration)?.parent
    }
    return components.asReversed().joinToString(".").ifEmpty { "<anonymous-owner>" }
}
