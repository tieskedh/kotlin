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
 * Native interface target for one operation, never new evidence about its receiver.
 *
 * These requests neither insert runtime checks nor change produced/storage facts. They apply only
 * to retained CLR interfaces, not Kotlin logical variance or conflicting Kotlin-owned families.
 */
internal sealed interface DotNetRetainedForeignInterfaceOperationTarget {
    val view: DotNetGenericOwnerPhysicalView

    /** The current selected source must itself permit the CLR reference conversion. */
    data class ReferenceConversion(
        override val view: DotNetGenericOwnerPhysicalView,
    ) : DotNetRetainedForeignInterfaceOperationTarget

    /** Prove existing target membership independently; do not assume a cast has succeeded. */
    data class CheckedMembership(
        override val view: DotNetGenericOwnerPhysicalView,
    ) : DotNetRetainedForeignInterfaceOperationTarget
}

/**
 * Selects one imported CLR operation from exact retained metadata and existing value evidence.
 *
 * Without [operationTarget], selected lineage wins, then the direct carrier, then a unique
 * guaranteed construction. An explicit target is a proof goal: reference conversion must follow
 * the current source, while checked membership may use any independently established physical
 * view. Both routes bind the target interface MethodDef, never a chosen implementation body.
 * No logical Kotlin type supplies physical authority. This query has no emitter consumer.
 */
internal fun selectDotNetRetainedForeignGenericOwnerPhysicalOperationRoute(
    source: DotNetClrImportedMethodSource,
    method: DotNetClrMethodDefinition,
    receiver: DotNetGenericOwnerProducedValueFact,
    arguments: List<DotNetGenericOwnerProducedValueFact>,
    methodArguments: List<DotNetGenericOwnerSymbolicCarrierReference> = emptyList(),
    inheritedReceiverSource: DotNetClrImportedTypeSource? = null,
    target: DotNetTarget? = null,
    operationTarget: DotNetRetainedForeignInterfaceOperationTarget? = null,
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
    val viewSelection = if (operationTarget == null) {
        receiver.selectDotNetGenericOwnerPhysicalMethodOwnerViewOrError(
            declarations,
            methodDescription.declaringType,
        )
    } else {
        selectRetainedInterfaceOperationTarget(
            declarations,
            methodDescription.declaringType,
            receiver,
            operationTarget,
        )
    }
    val requiredView = when (val selection = viewSelection) {
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

private fun selectRetainedInterfaceOperationTarget(
    declarations: DotNetGenericOwnerPhysicalDeclarationIndex,
    owner: DotNetGenericOwnerPhysicalTypeDefIdentity,
    receiver: DotNetGenericOwnerProducedValueFact,
    target: DotNetRetainedForeignInterfaceOperationTarget,
): DotNetGenericOwnerPhysicalBindingResult<DotNetGenericOwnerPhysicalView> {
    if (target.view.family != owner || owner !is DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr ||
        declarations.typeDescriptionOrNull(owner)?.category != DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE
    ) return DotNetGenericOwnerPhysicalBindingResult.Unavailable

    when (target) {
        is DotNetRetainedForeignInterfaceOperationTarget.ReferenceConversion -> {
            // Sibling membership does not repair an invalid conversion from the current source.
            // Without a selected/direct/unique source this bounded form remains unavailable;
            // it is not a general class-to-interface assignment analysis.
            val source = when (val selection = receiver.selectDotNetGenericOwnerPhysicalMethodOwnerViewOrError(
                declarations,
                owner,
            )) {
                is DotNetGenericOwnerPhysicalBindingResult.Bound -> selection.value
                is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return selection
                DotNetGenericOwnerPhysicalBindingResult.Unavailable -> return selection
            }
            when (val authority = DotNetGenericOwnerAuthenticatedPhysicalView.prove(receiver, declarations, source)) {
                is DotNetGenericOwnerPhysicalBindingResult.Bound -> Unit
                is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return authority
                DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                    return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            }
            if (source != target.view) {
                when (val conversion = declarations.proveClrReferenceVarianceConversionOrError(source, target.view)) {
                    is DotNetGenericOwnerPhysicalBindingResult.Bound -> Unit
                    is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return conversion
                    DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                        return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                }
            }
        }
        is DotNetRetainedForeignInterfaceOperationTarget.CheckedMembership -> Unit
    }
    // The common route still authenticates target membership, all signature substitutions and
    // ordinary arguments. Returning a proof goal here adds neither a guarantee nor a selector
    // to the original receiver and never turns an unknown object into an exact value.
    return DotNetGenericOwnerPhysicalBindingResult.Bound(target.view)
}
