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
    val requiredView = when (val selection = receiver.selectDotNetGenericOwnerPhysicalMethodOwnerViewOrError(
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
