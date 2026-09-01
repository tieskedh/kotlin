/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.config.DotNetTarget
import org.jetbrains.kotlin.load.dotnet.DotNetClrImportedMethodSource
import org.jetbrains.kotlin.load.dotnet.DotNetClrImportedTypeSource
import org.jetbrains.kotlin.load.dotnet.DotNetClrMethodDefinition

/**
 * Selects one imported CLR operation from exact retained metadata and existing value evidence.
 *
 * The imported MethodDef selects its physical owner family. The receiver construction is then
 * chosen only from views which value provenance already guarantees: selected lineage wins, an
 * exact current carrier is next, and otherwise there must be one unique guaranteed construction.
 * No logical Kotlin type or caller-supplied desired construction participates in this query.
 */
internal fun selectDotNetRetainedForeignGenericOwnerPhysicalOperationRoute(
    source: DotNetClrImportedMethodSource,
    method: DotNetClrMethodDefinition,
    receiver: DotNetGenericOwnerProducedValueFact,
    arguments: List<DotNetGenericOwnerProducedValueFact>,
    methodArguments: List<DotNetGenericOwnerSymbolicCarrierReference> = emptyList(),
    inheritedReceiverSource: DotNetClrImportedTypeSource? = null,
    target: DotNetTarget? = null,
): DotNetGenericOwnerPhysicalBindingResult<DotNetGenericOwnerPhysicalOperationRoute> {
    val declarations = when (
        val binding = if (inheritedReceiverSource == null) {
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeign(
                source,
                method,
                target,
            )
        } else {
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeignInheritedReceiver(
                source,
                method,
                inheritedReceiverSource,
                target,
            )
        }
    ) {
        is DotNetGenericOwnerPhysicalBindingResult.Bound -> binding.value
        is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
            return DotNetGenericOwnerPhysicalBindingResult.Conflict(binding.reason)
        DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
            return DotNetGenericOwnerPhysicalBindingResult.Unavailable
    }
    val selectedMethod = DotNetGenericOwnerPhysicalMethodDefIdentity.ForeignClr.retained(
        source,
        method,
    )
    val methodDescription = declarations.methodDescriptionOrNull(selectedMethod)
        ?: return DotNetGenericOwnerPhysicalBindingResult.Conflict(
            "retained foreign declaration authority omitted its selected MethodDef",
        )
    val requiredView = when (val selection = receiver.selectRetainedForeignMethodOwnerViewOrError(
        declarations,
        methodDescription.declaringType,
    )) {
        is DotNetGenericOwnerPhysicalBindingResult.Bound -> selection.value
        is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return selection
        DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
            return DotNetGenericOwnerPhysicalBindingResult.Unavailable
    }
    return selectDotNetGenericOwnerPhysicalOperationRoute(
        declarations = declarations,
        selectedMethod = selectedMethod,
        request = DotNetGenericOwnerPhysicalOperationRouteRequest(
            requiredReceiverView = requiredView,
            methodArguments = methodArguments,
        ),
        receiver = receiver,
        arguments = arguments,
    )
}

private fun DotNetGenericOwnerProducedValueFact.selectRetainedForeignMethodOwnerViewOrError(
    declarations: DotNetGenericOwnerPhysicalDeclarationIndex,
    owner: DotNetGenericOwnerPhysicalTypeDefIdentity,
): DotNetGenericOwnerPhysicalBindingResult<DotNetGenericOwnerPhysicalView> {
    if (!nullState.canBeNonNull) return DotNetGenericOwnerPhysicalBindingResult.Unavailable
    provenance.selectedViewLineage[owner]?.let { selected ->
        return DotNetGenericOwnerPhysicalBindingResult.Bound(selected)
    }

    val currentConstruction = (layout as? DotNetGenericOwnerProducedValueLayout.Direct)
        ?.carrier?.type as? DotNetGenericOwnerSymbolicCarrierReference.Constructed
    if (currentConstruction?.definition == owner) {
        return DotNetGenericOwnerPhysicalBindingResult.Bound(
            DotNetGenericOwnerPhysicalView(currentConstruction),
        )
    }

    val knownViews = (provenance.guaranteedViews as? DotNetGenericOwnerGuaranteedViews.Known)
        ?.views ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
    val candidates = knownViews.filterTo(linkedSetOf()) { view -> view.family == owner }
    val sourceConstructions = linkedSetOf<DotNetGenericOwnerSymbolicCarrierReference.Constructed>()
    currentConstruction?.let(sourceConstructions::add)
    knownViews.mapTo(sourceConstructions) { view -> view.construction }
    for (sourceConstruction in sourceConstructions) {
        when (val closure = declarations.physicalInterfaceViewClosureOrError(sourceConstruction)) {
            is DotNetGenericOwnerPhysicalBindingResult.Bound ->
                closure.value.interfaceViews.filterTo(candidates) { view -> view.family == owner }
            is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return closure
            DotNetGenericOwnerPhysicalBindingResult.Unavailable -> Unit
        }
    }
    return candidates.singleOrNull()?.let {
        DotNetGenericOwnerPhysicalBindingResult.Bound(it)
    } ?: DotNetGenericOwnerPhysicalBindingResult.Unavailable
}
