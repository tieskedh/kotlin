/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

/** The required physical view is a proof goal and never a source of receiver evidence. */
internal data class DotNetGenericOwnerPhysicalOperationRouteRequest(
    val requiredReceiverView: DotNetGenericOwnerPhysicalView,
    /** The selected MethodDef's MethodSpec vector; it never defines that MethodDef's arity. */
    val methodArguments: List<DotNetGenericOwnerSymbolicCarrierReference> = emptyList(),
)

/** One read-only route decision; it neither rewrites the call nor enters emitter state. */
internal data class DotNetGenericOwnerPhysicalOperationRoute(
    val method: DotNetGenericOwnerPhysicalMethodDefReference,
    val requiredReceiverView: DotNetGenericOwnerPhysicalView,
    val methodArguments: List<DotNetGenericOwnerSymbolicCarrierReference>,
    val instantiatedSignature: DotNetGenericOwnerPhysicalMethodSignatureReference,
)

/**
 * Proves only the MethodDef already selected by authoritative logical-family policy.
 * A missing endpoint or receiver view never falls back to another MethodDef, and provenance can
 * support but never replace the requested view. TypeDef and MethodDef binders are substituted
 * independently from their authority-owned vectors. Broad-candidate inputs remain unavailable in
 * this slice.
 */
internal fun selectDotNetGenericOwnerPhysicalOperationRoute(
    declarations: DotNetGenericOwnerPhysicalDeclarationIndex,
    selectedMethod: DotNetGenericOwnerPhysicalMethodDefIdentity,
    request: DotNetGenericOwnerPhysicalOperationRouteRequest,
    receiver: DotNetGenericOwnerProducedValueFact,
    arguments: List<DotNetGenericOwnerProducedValueFact>,
): DotNetGenericOwnerPhysicalBindingResult<DotNetGenericOwnerPhysicalOperationRoute> {
    val method = declarations.methodDescriptionOrNull(selectedMethod)
        ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
    if (!method.signature.isInstance ||
        declarations.typeDescriptionOrNull(method.declaringType)?.category !=
        DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE ||
        request.requiredReceiverView.family != method.declaringType
    ) {
        return DotNetGenericOwnerPhysicalBindingResult.Unavailable
    }
    if (request.methodArguments.size != method.signature.genericArity) {
        return DotNetGenericOwnerPhysicalBindingResult.Conflict(
            "selected physical MethodDef has generic arity ${method.signature.genericArity}, " +
                    "but its MethodSpec vector has arity ${request.methodArguments.size}",
        )
    }
    if (method.genericParameters.any { parameter -> parameter.constraints.isNotEmpty() }) {
        // Constraint satisfaction is a separate nominal type proof. Until that proof consumes
        // the selected MethodDef's recorded rows, an unconstrained substitution must not silently
        // authorize a constrained MethodSpec.
        return DotNetGenericOwnerPhysicalBindingResult.Unavailable
    }
    for (argument in request.methodArguments) {
        when (val validation = declarations.carrierOrError(argument)) {
            is DotNetGenericOwnerPhysicalBindingResult.Bound -> Unit
            is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                return DotNetGenericOwnerPhysicalBindingResult.Conflict(validation.reason)
            DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                return DotNetGenericOwnerPhysicalBindingResult.Unavailable
        }
    }
    when (val proof = receiver.proveRequiredPhysicalView(
        declarations,
        request.requiredReceiverView,
    )) {
        is DotNetGenericOwnerPhysicalBindingResult.Bound -> Unit
        is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
            return DotNetGenericOwnerPhysicalBindingResult.Conflict(proof.reason)
        DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
            return DotNetGenericOwnerPhysicalBindingResult.Unavailable
    }
    val instantiatedSignature = when (val binding = method.signature.instantiateForCallOrError(
        declarations,
        method.declaringType,
        method.identity,
        request.requiredReceiverView.construction,
        request.methodArguments,
    )) {
        is DotNetGenericOwnerPhysicalBindingResult.Bound -> binding.value
        is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
            return DotNetGenericOwnerPhysicalBindingResult.Conflict(binding.reason)
        DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
            return DotNetGenericOwnerPhysicalBindingResult.Unavailable
    }
    if (arguments.size != instantiatedSignature.parameterSlots.size) {
        // The declaration remains coherent; this logical operation simply does not match it.
        return DotNetGenericOwnerPhysicalBindingResult.Unavailable
    }
    for (index in arguments.indices) {
        val slot = instantiatedSignature.parameterSlots[index]
        if (slot.domain == DotNetGenericOwnerPhysicalSlotDomain.BROAD_CANDIDATE_INPUT) {
            return DotNetGenericOwnerPhysicalBindingResult.Unavailable
        }
        when (val admission = arguments[index].canEnterCallableSlotIdentityPreserving(
            declarations,
            slot,
        )) {
            is DotNetGenericOwnerPhysicalBindingResult.Bound -> Unit
            is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                return DotNetGenericOwnerPhysicalBindingResult.Conflict(admission.reason)
            DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                return DotNetGenericOwnerPhysicalBindingResult.Unavailable
        }
    }
    return DotNetGenericOwnerPhysicalBindingResult.Bound(
        DotNetGenericOwnerPhysicalOperationRoute(
            method,
            request.requiredReceiverView,
            request.methodArguments.toList(),
            instantiatedSignature,
        ),
    )
}

private fun DotNetGenericOwnerPhysicalMethodSignatureReference.instantiateForCallOrError(
    declarations: DotNetGenericOwnerPhysicalDeclarationIndex,
    declaringType: DotNetGenericOwnerPhysicalTypeDefIdentity,
    selectedMethod: DotNetGenericOwnerPhysicalMethodDefIdentity,
    receiver: DotNetGenericOwnerSymbolicCarrierReference.Constructed,
    methodArguments: List<DotNetGenericOwnerSymbolicCarrierReference>,
): DotNetGenericOwnerPhysicalBindingResult<DotNetGenericOwnerPhysicalMethodSignatureReference> {
    if (receiver.definition != declaringType) {
        return DotNetGenericOwnerPhysicalBindingResult.Conflict(
            "physical operation receiver does not construct its selected MethodDef owner",
        )
    }
    val parameterSlots = mutableListOf<DotNetGenericOwnerPhysicalCallableValueSlotReference>()
    for (slot in this.parameterSlots) {
        when (val binding = slot.instantiateForCallOrError(
            declarations,
            selectedMethod,
            receiver,
            methodArguments,
        )) {
            is DotNetGenericOwnerPhysicalBindingResult.Bound -> parameterSlots += binding.value
            is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                return DotNetGenericOwnerPhysicalBindingResult.Conflict(binding.reason)
            DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                return DotNetGenericOwnerPhysicalBindingResult.Unavailable
        }
    }
    val resultLayout = when (val result = resultLayout) {
        DotNetGenericOwnerPhysicalCallableResultLayoutReference.Void ->
            DotNetGenericOwnerPhysicalBindingResult.Bound(result)
        is DotNetGenericOwnerPhysicalCallableResultLayoutReference.Direct ->
            result.slot.instantiateForCallOrError(
                declarations,
                selectedMethod,
                receiver,
                methodArguments,
            ).map { slot ->
                DotNetGenericOwnerPhysicalCallableResultLayoutReference.Direct(slot)
            }
        is DotNetGenericOwnerPhysicalCallableResultLayoutReference.SplitNullable ->
            result.payloadSlot.instantiateForCallOrError(
                declarations,
                selectedMethod,
                receiver,
                methodArguments,
            ).map { slot ->
                DotNetGenericOwnerPhysicalCallableResultLayoutReference.SplitNullable(
                    payloadSlot = slot,
                    nullFlag = result.nullFlag,
                )
            }
    }
    val boundResultLayout = when (resultLayout) {
        is DotNetGenericOwnerPhysicalBindingResult.Bound -> resultLayout.value
        is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
            return DotNetGenericOwnerPhysicalBindingResult.Conflict(resultLayout.reason)
        DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
            return DotNetGenericOwnerPhysicalBindingResult.Unavailable
    }
    return DotNetGenericOwnerPhysicalBindingResult.Bound(
        DotNetGenericOwnerPhysicalMethodSignatureReference(
            isInstance = isInstance,
            genericArity = genericArity,
            resultLayout = boundResultLayout,
            parameterSlots = parameterSlots,
        ),
    )
}

private fun DotNetGenericOwnerPhysicalCallableValueSlotReference.instantiateForCallOrError(
    declarations: DotNetGenericOwnerPhysicalDeclarationIndex,
    selectedMethod: DotNetGenericOwnerPhysicalMethodDefIdentity,
    receiver: DotNetGenericOwnerSymbolicCarrierReference.Constructed,
    methodArguments: List<DotNetGenericOwnerSymbolicCarrierReference>,
): DotNetGenericOwnerPhysicalBindingResult<DotNetGenericOwnerPhysicalCallableValueSlotReference> =
    carrier.instantiateForCallOrError(
        declarations,
        selectedMethod,
        receiver,
        methodArguments,
    ).map { instantiatedCarrier ->
        copy(carrier = instantiatedCarrier)
    }

private fun DotNetGenericOwnerSymbolicCarrierReference.instantiateForCallOrError(
    declarations: DotNetGenericOwnerPhysicalDeclarationIndex,
    selectedMethod: DotNetGenericOwnerPhysicalMethodDefIdentity,
    receiver: DotNetGenericOwnerSymbolicCarrierReference.Constructed,
    methodArguments: List<DotNetGenericOwnerSymbolicCarrierReference>,
): DotNetGenericOwnerPhysicalBindingResult<DotNetGenericOwnerSymbolicCarrierReference> {
    return when (this) {
        is DotNetGenericOwnerSymbolicCarrierReference.Leaf ->
            DotNetGenericOwnerPhysicalBindingResult.Bound(this)
        is DotNetGenericOwnerSymbolicCarrierReference.Parameter -> when (val binder = binder) {
            is DotNetGenericOwnerPhysicalGenericBinderReference.Type -> {
                if (binder.definition != receiver.definition) {
                    DotNetGenericOwnerPhysicalBindingResult.Conflict(
                        "physical MethodDef signature uses a TypeDef binder outside its declaring owner",
                    )
                } else {
                    receiver.arguments.getOrNull(index)?.let {
                        DotNetGenericOwnerPhysicalBindingResult.Bound(it)
                    } ?: DotNetGenericOwnerPhysicalBindingResult.Conflict(
                        "physical receiver construction omits required owner parameter $index",
                    )
                }
            }
            is DotNetGenericOwnerPhysicalGenericBinderReference.Method -> {
                if (binder.definition != selectedMethod) {
                    DotNetGenericOwnerPhysicalBindingResult.Conflict(
                        "physical MethodDef signature uses a MethodDef binder outside its selected owner",
                    )
                } else {
                    methodArguments.getOrNull(index)?.let {
                        DotNetGenericOwnerPhysicalBindingResult.Bound(it)
                    } ?: DotNetGenericOwnerPhysicalBindingResult.Conflict(
                        "physical MethodSpec omits required method parameter $index",
                    )
                }
            }
        }
        is DotNetGenericOwnerSymbolicCarrierReference.Constructed -> {
            val instantiatedArguments = mutableListOf<DotNetGenericOwnerSymbolicCarrierReference>()
            for (argument in arguments) {
                when (val binding = argument.instantiateForCallOrError(
                    declarations,
                    selectedMethod,
                    receiver,
                    methodArguments,
                )) {
                    is DotNetGenericOwnerPhysicalBindingResult.Bound -> instantiatedArguments += binding.value
                    is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return binding
                    DotNetGenericOwnerPhysicalBindingResult.Unavailable -> return binding
                }
            }
            declarations.constructTypeOrError(definition, instantiatedArguments).map { construction ->
                construction
            }
        }
        is DotNetGenericOwnerSymbolicCarrierReference.SzArray ->
            element.instantiateForCallOrError(
                declarations,
                selectedMethod,
                receiver,
                methodArguments,
            ).map { instantiatedElement ->
                DotNetGenericOwnerSymbolicCarrierReference.SzArray(instantiatedElement)
            }
    }
}

private fun DotNetGenericOwnerProducedValueFact.canEnterCallableSlotIdentityPreserving(
    declarations: DotNetGenericOwnerPhysicalDeclarationIndex,
    slot: DotNetGenericOwnerPhysicalCallableValueSlotReference,
): DotNetGenericOwnerPhysicalBindingResult<Unit> {
    val expected = when (val binding = declarations.carrierOrError(slot.carrier)) {
        is DotNetGenericOwnerPhysicalBindingResult.Bound -> binding.value
        is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
            return DotNetGenericOwnerPhysicalBindingResult.Conflict(binding.reason)
        DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
            return DotNetGenericOwnerPhysicalBindingResult.Unavailable
    }
    if (layout == DotNetGenericOwnerProducedValueLayout.Null) {
        return if (expected.acceptsCarrierlessNull) {
            DotNetGenericOwnerPhysicalBindingResult.Bound(Unit)
        } else {
            DotNetGenericOwnerPhysicalBindingResult.Unavailable
        }
    }
    val produced = (layout as? DotNetGenericOwnerProducedValueLayout.Direct)?.carrier
        ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
    if (produced == expected) return DotNetGenericOwnerPhysicalBindingResult.Bound(Unit)
    if (expected.type == DotNetGenericOwnerSymbolicCarrierReference.objectCarrier() &&
        produced.nullEncoding == DotNetGenericOwnerPhysicalNullEncoding.NULL_REFERENCE
    ) {
        return DotNetGenericOwnerPhysicalBindingResult.Bound(Unit)
    }
    val expectedConstruction = expected.type as?
            DotNetGenericOwnerSymbolicCarrierReference.Constructed
        ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
    return proveRequiredPhysicalView(
        declarations,
        DotNetGenericOwnerPhysicalView(expectedConstruction),
    )
}

/**
 * Proves one already selected physical view without enriching provenance or influencing placement.
 * Any malformed recorded ancestry is a declaration conflict, even if another source could prove
 * the requested view independently.
 */
private fun DotNetGenericOwnerProducedValueFact.proveRequiredPhysicalView(
    declarations: DotNetGenericOwnerPhysicalDeclarationIndex,
    requiredView: DotNetGenericOwnerPhysicalView,
): DotNetGenericOwnerPhysicalBindingResult<Unit> {
    if (!nullState.canBeNonNull) return DotNetGenericOwnerPhysicalBindingResult.Unavailable
    when (val validation = declarations.carrierOrError(requiredView.construction)) {
        is DotNetGenericOwnerPhysicalBindingResult.Bound -> Unit
        is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
            return DotNetGenericOwnerPhysicalBindingResult.Conflict(validation.reason)
        DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
            return DotNetGenericOwnerPhysicalBindingResult.Unavailable
    }
    val knownViews = (provenance.guaranteedViews as? DotNetGenericOwnerGuaranteedViews.Known)
        ?.views ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
    if (requiredView in knownViews) return DotNetGenericOwnerPhysicalBindingResult.Bound(Unit)

    val sourceConstructions = linkedSetOf<DotNetGenericOwnerSymbolicCarrierReference.Constructed>()
    ((layout as? DotNetGenericOwnerProducedValueLayout.Direct)?.carrier?.type as?
            DotNetGenericOwnerSymbolicCarrierReference.Constructed)?.let(sourceConstructions::add)
    for (view in knownViews) sourceConstructions += view.construction
    var found = false
    for (source in sourceConstructions) {
        when (val closure = declarations.physicalInterfaceViewClosureOrError(source)) {
            is DotNetGenericOwnerPhysicalBindingResult.Bound -> {
                found = found || requiredView in closure.value.interfaceViews
            }
            is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return closure
            DotNetGenericOwnerPhysicalBindingResult.Unavailable -> Unit
        }
    }
    return if (found) {
        DotNetGenericOwnerPhysicalBindingResult.Bound(Unit)
    } else {
        DotNetGenericOwnerPhysicalBindingResult.Unavailable
    }
}

private inline fun <T, R> DotNetGenericOwnerPhysicalBindingResult<T>.map(
    transform: (T) -> R,
): DotNetGenericOwnerPhysicalBindingResult<R> = when (this) {
    is DotNetGenericOwnerPhysicalBindingResult.Bound ->
        DotNetGenericOwnerPhysicalBindingResult.Bound(transform(value))
    is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
        DotNetGenericOwnerPhysicalBindingResult.Conflict(reason)
    DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
        DotNetGenericOwnerPhysicalBindingResult.Unavailable
}
