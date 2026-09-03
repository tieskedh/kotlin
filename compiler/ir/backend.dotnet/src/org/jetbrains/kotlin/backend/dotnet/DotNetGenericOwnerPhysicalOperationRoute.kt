/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.isMarkedNullable

/** Shared logical admission rule; callers still bind its physical result independently. */
internal fun IrSimpleFunction.genericOwnerDirectNonNullOwnerResultParameterIndexOrNull(): Int? {
    val owner = parent as? IrClass ?: return null
    val result = returnType as? IrSimpleType ?: return null
    if (result.isMarkedNullable()) return null
    val parameter = result.classifier as? IrTypeParameterSymbol ?: return null
    return owner.typeParameters.indexOf(parameter.owner).takeIf { index -> index >= 0 }
}

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
    /** Result supplied by the instantiated MethodDef, before logical materialization or storage. */
    val producedResult: DotNetGenericOwnerProducedValueFact?,
)

/** Exact open callee grammar for the first current-MethodDef MethodSpec consumer. */
internal fun DotNetGenericOwnerPhysicalMethodDefReference
        .isDirectCallerMethodParameterProducer(
            declarations: DotNetGenericOwnerPhysicalDeclarationIndex?,
        ): Boolean {
    declarations ?: return false
    val ownerIdentity = declaringType as? DotNetGenericOwnerPhysicalTypeDefIdentity.Local
        ?: return false
    if (ownerIdentity.view != DotNetGenericInterfaceView.DECLARED) return false
    val owner = declarations.typeDescriptionOrNull(ownerIdentity) ?: return false
    if (owner.category != DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE ||
        owner.genericParameters.singleOrNull()?.isUnconstrained != true
    ) return false
    if (!signature.isInstance || signature.genericArity != 1 ||
        genericParameters.singleOrNull()?.isUnconstrained != true
    ) return false
    val input = signature.parameterSlots.singleOrNull() ?: return false
    if (input.domain != DotNetGenericOwnerPhysicalSlotDomain.DECLARATION_INDEPENDENT) return false
    val inputParameter = input.carrier as?
            DotNetGenericOwnerSymbolicCarrierReference.Parameter ?: return false
    val inputBinder = inputParameter.binder as?
            DotNetGenericOwnerPhysicalGenericBinderReference.Method ?: return false
    if (inputBinder.definition != identity || inputParameter.index != 0) return false

    val result = signature.resultLayout as?
            DotNetGenericOwnerPhysicalCallableResultLayoutReference.Direct ?: return false
    if (result.slot.domain != DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_OUTPUT) return false
    val resultParameter = result.slot.carrier as?
            DotNetGenericOwnerSymbolicCarrierReference.Parameter ?: return false
    val resultBinder = resultParameter.binder as?
            DotNetGenericOwnerPhysicalGenericBinderReference.Type ?: return false
    return resultBinder.definition == declaringType && resultParameter.index == 0
}

/**
 * Recognizes the one already-proven operation whose MethodSpec is the current caller's `!!0`.
 *
 * This is a classifier over declaration and operation authority, not a new route selector. The
 * selected lineage may identify the intended receiver view, but it cannot manufacture that view;
 * the complete route was authenticated before this predicate is queried.
 */
internal fun DotNetGenericOwnerPhysicalOperationRoute
        .isDirectCallerMethodParameterProducerOperation(
            currentMethod: DotNetGenericOwnerPhysicalMethodDefIdentity.Local?,
            declarations: DotNetGenericOwnerPhysicalDeclarationIndex?,
        ): Boolean {
    currentMethod ?: return false
    declarations ?: return false
    if (!method.isDirectCallerMethodParameterProducer(declarations) ||
        method.declaringType != requiredReceiverView.family
    ) return false
    val currentDescription = declarations.methodDescriptionOrNull(currentMethod) ?: return false
    if (currentDescription.signature.genericArity != 1 ||
        currentDescription.genericParameters.singleOrNull()?.isUnconstrained != true
    ) return false
    val methodArgument = methodArguments.singleOrNull() as?
            DotNetGenericOwnerSymbolicCarrierReference.Parameter ?: return false
    val methodArgumentBinder = methodArgument.binder as?
            DotNetGenericOwnerPhysicalGenericBinderReference.Method ?: return false
    val callerBinder = methodArgumentBinder.definition as?
            DotNetGenericOwnerPhysicalMethodDefIdentity.Local ?: return false
    if (!callerBinder.sameLocalMethodIdentityAs(currentMethod) || methodArgument.index != 0) {
        return false
    }
    if (instantiatedSignature.genericArity != 1) return false
    val input = instantiatedSignature.parameterSlots.singleOrNull() ?: return false
    if (input.domain != DotNetGenericOwnerPhysicalSlotDomain.DECLARATION_INDEPENDENT ||
        input.carrier != methodArgument
    ) return false
    val result = instantiatedSignature.resultLayout as?
            DotNetGenericOwnerPhysicalCallableResultLayoutReference.Direct ?: return false
    if (result.slot.domain != DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_OUTPUT) return false
    val produced = producedResult?.layout as? DotNetGenericOwnerProducedValueLayout.Direct
        ?: return false
    return produced.carrier.type == result.slot.carrier
}

/**
 * Identity-scoped final-emitter witness for a broad call promoted by semantic equivalence.
 *
 * The operation route authenticates the natural interface MethodDef. [directReceiverCarrier]
 * separately retains the exact implementation carrier which justified rerouting the broad
 * semantic operation; an arbitrary object which merely implements the same interface must not
 * inherit that proof. Final emission rebinds both facts against the live `ldarg`/`ldloc` carrier.
 */
internal sealed interface DotNetGenericOwnerSemanticEquivalentOperationEmitterAuthority {
    val implementationType: DotNetGenericOwnerPhysicalTypeDefIdentity

    data class Local(
        override val implementationType: DotNetGenericOwnerPhysicalTypeDefIdentity.Local,
    ) : DotNetGenericOwnerSemanticEquivalentOperationEmitterAuthority {
        init {
            require(implementationType.view == null) {
                "a local semantic-equivalence witness requires the implementation TypeDef"
            }
        }
    }

    /**
     * Exact external KLIB endpoints plus the PE-stamped `K`/`J` authority used by routing.
     *
     * This retained object is not sufficient at emission time: codegen must query the same three
     * logical declarations again and rebind the returned certificate through [physicalAuthority].
     * Keeping both values here makes a cross-family or raw-certificate substitution observable.
     */
    data class External(
        val logicalInterfaceMember: IrSimpleFunctionSymbol,
        val implementationOwner: IrClassSymbol,
        val implementationMember: IrSimpleFunctionSymbol,
        val certificate: DotNetBoundGenericOwnerSemanticEquivalenceCertificate,
        val physicalAuthority: DotNetProducerGenericOwnerSemanticEquivalencePhysicalAuthority,
    ) : DotNetGenericOwnerSemanticEquivalentOperationEmitterAuthority {
        override val implementationType: DotNetGenericOwnerPhysicalTypeDefIdentity.KotlinProducer =
            physicalAuthority.typeDefinition(
                DotNetProducerGenericOwnerSealedTypeDefRole.IMPLEMENTATION_CLASS,
            )
        val naturalType: DotNetGenericOwnerPhysicalTypeDefIdentity.KotlinProducer =
            physicalAuthority.typeDefinition(
                DotNetProducerGenericOwnerSealedTypeDefRole.NATURAL_INTERFACE,
            )

        init {
            require(physicalAuthority.epoch ==
                    DotNetGenericOwnerPhysicalAuthorityEpoch.SEALED_EMISSION_SIGNATURE_INDEX &&
                    physicalAuthority.familyKey == certificate.sealedFamily.publication.key &&
                    implementationType.artifact == certificate.library.artifact &&
                    naturalType.artifact == certificate.library.artifact
            ) {
                "an external semantic-equivalence witness requires its exact PE-stamped K/J family"
            }
        }
    }
}

internal data class DotNetGenericOwnerSemanticEquivalentOperationEmitterWitness(
    val route: DotNetGenericOwnerPhysicalOperationRoute,
    val directReceiverCarrier: DotNetGenericOwnerPhysicalCarrier,
    val authority: DotNetGenericOwnerSemanticEquivalentOperationEmitterAuthority,
) {
    /** Preserves the existing local-witness construction contract without weakening its type. */
    constructor(
        route: DotNetGenericOwnerPhysicalOperationRoute,
        directReceiverCarrier: DotNetGenericOwnerPhysicalCarrier,
        implementationType: DotNetGenericOwnerPhysicalTypeDefIdentity.Local,
    ) : this(
        route,
        directReceiverCarrier,
        DotNetGenericOwnerSemanticEquivalentOperationEmitterAuthority.Local(implementationType),
    )

    init {
        require(directReceiverCarrier.nullEncoding ==
                DotNetGenericOwnerPhysicalNullEncoding.NULL_REFERENCE) {
            "a semantic-equivalence emitter witness requires one non-boxed reference carrier"
        }
        val construction = directReceiverCarrier.type as?
                DotNetGenericOwnerSymbolicCarrierReference.Constructed
        require(construction?.definition == authority.implementationType) {
            "a semantic-equivalence emitter witness must retain its exact implementation TypeDef"
        }
        if (authority is DotNetGenericOwnerSemanticEquivalentOperationEmitterAuthority.External) {
            require(route.method.identity == authority.physicalAuthority.naturalMethodDefinition &&
                    route.method.declaringType == authority.naturalType &&
                    route.requiredReceiverView.family == authority.naturalType &&
                    route.method.signature.genericArity == 0 &&
                    route.methodArguments.isEmpty() &&
                    route.instantiatedSignature.parameterSlots.isEmpty() &&
                    route.instantiatedSignature.resultLayout is
                    DotNetGenericOwnerPhysicalCallableResultLayoutReference.Direct
            ) {
                "the bounded external semantic-equivalence witness requires the exact arity-zero J slot"
            }
        }
    }

    companion object {
        fun external(
            route: DotNetGenericOwnerPhysicalOperationRoute,
            directReceiverCarrier: DotNetGenericOwnerPhysicalCarrier,
            logicalInterfaceMember: IrSimpleFunctionSymbol,
            implementationOwner: IrClassSymbol,
            implementationMember: IrSimpleFunctionSymbol,
            certificate: DotNetBoundGenericOwnerSemanticEquivalenceCertificate,
            physicalAuthority: DotNetProducerGenericOwnerSemanticEquivalencePhysicalAuthority,
        ): DotNetGenericOwnerSemanticEquivalentOperationEmitterWitness =
            DotNetGenericOwnerSemanticEquivalentOperationEmitterWitness(
                route,
                directReceiverCarrier,
                DotNetGenericOwnerSemanticEquivalentOperationEmitterAuthority.External(
                    logicalInterfaceMember,
                    implementationOwner,
                    implementationMember,
                    certificate,
                    physicalAuthority,
                ),
            )
    }
}

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
    val receiverAuthority = when (val proof = DotNetGenericOwnerAuthenticatedPhysicalView.prove(
        receiver,
        declarations,
        request.requiredReceiverView,
    )) {
        is DotNetGenericOwnerPhysicalBindingResult.Bound -> proof.value
        is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
            return DotNetGenericOwnerPhysicalBindingResult.Conflict(proof.reason)
        DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
            return DotNetGenericOwnerPhysicalBindingResult.Unavailable
    }
    val instantiatedSignature = when (val binding = method.signature.instantiateForCallOrError(
        declarations,
        method.declaringType,
        method.identity,
        receiverAuthority,
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
            receiverAuthority,
        )) {
            is DotNetGenericOwnerPhysicalBindingResult.Bound -> Unit
            is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                return DotNetGenericOwnerPhysicalBindingResult.Conflict(admission.reason)
            DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                return DotNetGenericOwnerPhysicalBindingResult.Unavailable
        }
    }
    val producedResult = when (val production = instantiatedSignature.resultLayout
        .produceCallResultOrError(
            declarations,
            receiverAuthority,
        )
    ) {
        is DotNetGenericOwnerPhysicalBindingResult.Bound -> production.value
        is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
            return DotNetGenericOwnerPhysicalBindingResult.Conflict(production.reason)
        DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
            return DotNetGenericOwnerPhysicalBindingResult.Unavailable
    }
    return DotNetGenericOwnerPhysicalBindingResult.Bound(
        DotNetGenericOwnerPhysicalOperationRoute(
            method,
            request.requiredReceiverView,
            request.methodArguments.toList(),
            instantiatedSignature,
            producedResult,
        ),
    )
}

/**
 * Selects one already-guaranteed construction of an authority-selected MethodDef owner.
 *
 * The selected MethodDef supplies only the owner family. Lineage may select an existing view but
 * cannot create one; otherwise the direct carrier or one unique recorded/guaranteed construction
 * must supply it. This rule is identical for retained foreign and locally emitted CLR owners.
 */
internal fun DotNetGenericOwnerProducedValueFact.selectDotNetGenericOwnerPhysicalMethodOwnerViewOrError(
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

/** Produces exactly the layout fixed by the selected MethodDef, never its later logical view. */
private fun DotNetGenericOwnerPhysicalCallableResultLayoutReference.produceCallResultOrError(
    declarations: DotNetGenericOwnerPhysicalDeclarationIndex,
    authority: DotNetGenericOwnerAuthenticatedPhysicalView,
): DotNetGenericOwnerPhysicalBindingResult<DotNetGenericOwnerProducedValueFact?> {
    if (this == DotNetGenericOwnerPhysicalCallableResultLayoutReference.Void) {
        return DotNetGenericOwnerPhysicalBindingResult.Bound(null)
    }
    val carrierReference = when (this) {
        is DotNetGenericOwnerPhysicalCallableResultLayoutReference.Direct -> slot.carrier
        is DotNetGenericOwnerPhysicalCallableResultLayoutReference.SplitNullable ->
            payloadSlot.carrier
        DotNetGenericOwnerPhysicalCallableResultLayoutReference.Void -> error("handled above")
    }
    val carrier = when (val binding =
        declarations.carrierWithinAuthenticatedViewOrError(
            carrierReference,
            authority,
        )
    ) {
        is DotNetGenericOwnerPhysicalBindingResult.Bound -> binding.value
        is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
            return DotNetGenericOwnerPhysicalBindingResult.Conflict(binding.reason)
        DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
            return DotNetGenericOwnerPhysicalBindingResult.Unavailable
    }
    val resultView = (carrier.type as? DotNetGenericOwnerSymbolicCarrierReference.Constructed)
        ?.let(::DotNetGenericOwnerPhysicalView)
    val provenance = if (resultView == null) {
        DotNetGenericOwnerPhysicalValueProvenance.noNonNullViews()
    } else {
        DotNetGenericOwnerPhysicalValueProvenance(
            DotNetGenericOwnerGuaranteedViews.Known(
                mapOf(
                    resultView to setOf(
                        DotNetGenericOwnerPhysicalViewEvidence.FROZEN_PARAMETER_OR_RESULT,
                    )
                ),
            ),
        )
    }
    val nullState = when (this) {
        is DotNetGenericOwnerPhysicalCallableResultLayoutReference.Direct ->
            if (carrier.canRepresentNull) {
                DotNetGenericOwnerPhysicalNullState.MAYBE_NULL
            } else {
                DotNetGenericOwnerPhysicalNullState.NON_NULL
            }
        is DotNetGenericOwnerPhysicalCallableResultLayoutReference.SplitNullable ->
            DotNetGenericOwnerPhysicalNullState.MAYBE_NULL
        DotNetGenericOwnerPhysicalCallableResultLayoutReference.Void -> error("handled above")
    }
    val layout = when (this) {
        is DotNetGenericOwnerPhysicalCallableResultLayoutReference.Direct ->
            DotNetGenericOwnerProducedValueLayout.Direct(carrier)
        is DotNetGenericOwnerPhysicalCallableResultLayoutReference.SplitNullable ->
            DotNetGenericOwnerProducedValueLayout.SplitNullable(carrier)
        DotNetGenericOwnerPhysicalCallableResultLayoutReference.Void -> error("handled above")
    }
    return DotNetGenericOwnerPhysicalBindingResult.Bound(
        DotNetGenericOwnerProducedValueFact(layout, provenance, nullState),
    )
}

private fun DotNetGenericOwnerPhysicalMethodSignatureReference.instantiateForCallOrError(
    declarations: DotNetGenericOwnerPhysicalDeclarationIndex,
    declaringType: DotNetGenericOwnerPhysicalTypeDefIdentity,
    selectedMethod: DotNetGenericOwnerPhysicalMethodDefIdentity,
    receiverAuthority: DotNetGenericOwnerAuthenticatedPhysicalView,
    methodArguments: List<DotNetGenericOwnerSymbolicCarrierReference>,
): DotNetGenericOwnerPhysicalBindingResult<DotNetGenericOwnerPhysicalMethodSignatureReference> {
    val receiver = receiverAuthority.construction
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
            receiverAuthority,
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
                receiverAuthority,
                methodArguments,
            ).map { slot ->
                DotNetGenericOwnerPhysicalCallableResultLayoutReference.Direct(slot)
            }
        is DotNetGenericOwnerPhysicalCallableResultLayoutReference.SplitNullable ->
            result.payloadSlot.instantiateForCallOrError(
                declarations,
                selectedMethod,
                receiverAuthority,
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
    receiverAuthority: DotNetGenericOwnerAuthenticatedPhysicalView,
    methodArguments: List<DotNetGenericOwnerSymbolicCarrierReference>,
): DotNetGenericOwnerPhysicalBindingResult<DotNetGenericOwnerPhysicalCallableValueSlotReference> =
    carrier.instantiateForCallOrError(
        declarations,
        selectedMethod,
        receiverAuthority,
        methodArguments,
    ).map { instantiatedCarrier ->
        copy(carrier = instantiatedCarrier)
    }

private fun DotNetGenericOwnerSymbolicCarrierReference.instantiateForCallOrError(
    declarations: DotNetGenericOwnerPhysicalDeclarationIndex,
    selectedMethod: DotNetGenericOwnerPhysicalMethodDefIdentity,
    receiverAuthority: DotNetGenericOwnerAuthenticatedPhysicalView,
    methodArguments: List<DotNetGenericOwnerSymbolicCarrierReference>,
): DotNetGenericOwnerPhysicalBindingResult<DotNetGenericOwnerSymbolicCarrierReference> {
    val receiver = receiverAuthority.construction
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
                    receiverAuthority,
                    methodArguments,
                )) {
                    is DotNetGenericOwnerPhysicalBindingResult.Bound -> instantiatedArguments += binding.value
                    is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return binding
                    DotNetGenericOwnerPhysicalBindingResult.Unavailable -> return binding
                }
            }
            declarations.constructTypeWithinAuthenticatedViewOrError(
                definition,
                instantiatedArguments,
                receiverAuthority,
            ).map { construction -> construction }
        }
        is DotNetGenericOwnerSymbolicCarrierReference.SzArray ->
            element.instantiateForCallOrError(
                declarations,
                selectedMethod,
                receiverAuthority,
                methodArguments,
            ).map { instantiatedElement ->
                DotNetGenericOwnerSymbolicCarrierReference.SzArray(instantiatedElement)
            }
    }
}

private fun DotNetGenericOwnerProducedValueFact.canEnterCallableSlotIdentityPreserving(
    declarations: DotNetGenericOwnerPhysicalDeclarationIndex,
    slot: DotNetGenericOwnerPhysicalCallableValueSlotReference,
    authority: DotNetGenericOwnerAuthenticatedPhysicalView,
): DotNetGenericOwnerPhysicalBindingResult<Unit> {
    val expected = when (val binding =
        declarations.carrierWithinAuthenticatedViewOrError(
            slot.carrier,
            authority,
        )
    ) {
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
): DotNetGenericOwnerPhysicalBindingResult<Unit> = when (
    val proof = DotNetGenericOwnerAuthenticatedPhysicalView.prove(
        this,
        declarations,
        requiredView,
    )
) {
    is DotNetGenericOwnerPhysicalBindingResult.Bound ->
        DotNetGenericOwnerPhysicalBindingResult.Bound(Unit)
    is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
        DotNetGenericOwnerPhysicalBindingResult.Conflict(proof.reason)
    DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
        DotNetGenericOwnerPhysicalBindingResult.Unavailable
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
