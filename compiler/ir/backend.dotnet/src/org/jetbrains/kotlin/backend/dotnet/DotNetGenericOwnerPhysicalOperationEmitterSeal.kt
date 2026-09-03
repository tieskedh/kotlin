/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

/**
 * Exact binder environment in which one symbolic carrier is compared with live emitter IL.
 *
 * A `!n` belongs to [typeOwner]. A `!!n` belongs to [method] and is valid only within its
 * recorded [methodGenericArity]. Keeping both owners explicit prevents equal TypeDef and
 * MethodDef parameter indices from becoming interchangeable.
 */
internal data class DotNetGenericOwnerEmitterBinderScope(
    val typeOwner: DotNetIlClassInfo,
    val method: DotNetGenericOwnerPhysicalMethodDefIdentity? = null,
    val methodGenericArity: Int = 0,
) {
    init {
        require(methodGenericArity >= 0) { "an emitter binder scope cannot have negative arity" }
        require(method != null || methodGenericArity == 0) {
            "an emitter MethodDef binder requires an exact physical MethodDef identity"
        }
    }
}

/** Compares symbolic physical truth with one verifier-visible carrier without logical remapping. */
internal fun DotNetGenericOwnerSymbolicCarrierReference.matchesEmitterCarrier(
    actual: DotNetIlValueType,
    scope: DotNetGenericOwnerEmitterBinderScope,
    classInfo: (DotNetGenericOwnerPhysicalTypeDefIdentity) -> DotNetIlClassInfo?,
): Boolean = when (this) {
    is DotNetGenericOwnerSymbolicCarrierReference.Leaf -> when (kind) {
        DotNetGenericOwnerPhysicalTypeKind.BOOLEAN -> actual == DotNetIlValueType.Boolean
        DotNetGenericOwnerPhysicalTypeKind.INT32 -> actual == DotNetIlValueType.Int32
        DotNetGenericOwnerPhysicalTypeKind.STRING -> actual == DotNetIlValueType.String
        DotNetGenericOwnerPhysicalTypeKind.OBJECT -> actual == DotNetIlValueType.Object
        DotNetGenericOwnerPhysicalTypeKind.VOID,
        DotNetGenericOwnerPhysicalTypeKind.OWNER_TYPE_PARAMETER,
        DotNetGenericOwnerPhysicalTypeKind.METHOD_TYPE_PARAMETER,
        DotNetGenericOwnerPhysicalTypeKind.NAMED,
        DotNetGenericOwnerPhysicalTypeKind.SZ_ARRAY,
        -> false
    }
    is DotNetGenericOwnerSymbolicCarrierReference.Parameter -> {
        val parameter = actual as? DotNetIlValueType.TypeParameter ?: return false
        when (val physicalBinder = binder) {
            is DotNetGenericOwnerPhysicalGenericBinderReference.Type ->
                !parameter.isMethodParameter &&
                        index in 0 until scope.typeOwner.typeParameterCount &&
                        parameter.index == index &&
                        classInfo(physicalBinder.definition) === scope.typeOwner
            is DotNetGenericOwnerPhysicalGenericBinderReference.Method ->
                parameter.isMethodParameter &&
                        index in 0 until scope.methodGenericArity &&
                        parameter.index == index &&
                        scope.method.samePhysicalMethodIdentityAs(physicalBinder.definition)
        }
    }
    is DotNetGenericOwnerSymbolicCarrierReference.Constructed -> {
        val expectedClass = classInfo(definition) ?: return false
        if (arguments.isEmpty()) {
            actual == DotNetIlValueType.UserClass(expectedClass)
        } else {
            val instance = actual as? DotNetIlValueType.GenericInstance ?: return false
            instance.classInfo === expectedClass &&
                    instance.arguments.size == arguments.size &&
                    arguments.indices.all { index ->
                        arguments[index].matchesEmitterCarrier(
                            instance.arguments[index],
                            scope,
                            classInfo,
                        )
                    }
        }
    }
    is DotNetGenericOwnerSymbolicCarrierReference.SzArray -> {
        val array = actual as? DotNetIlValueType.GenericArray ?: return false
        element.matchesEmitterCarrier(array.elementType, scope, classInfo)
    }
}

/** Compares Direct/Void/SplitNullable independently from all parameter-domain policy. */
internal fun DotNetGenericOwnerPhysicalCallableResultLayoutReference.matchesEmitterResult(
    returnType: DotNetIlReturnType,
    hasSplitNullableResult: Boolean,
    scope: DotNetGenericOwnerEmitterBinderScope,
    classInfo: (DotNetGenericOwnerPhysicalTypeDefIdentity) -> DotNetIlClassInfo?,
): Boolean = when (this) {
    DotNetGenericOwnerPhysicalCallableResultLayoutReference.Void ->
        !hasSplitNullableResult && returnType == DotNetIlReturnType.Void
    is DotNetGenericOwnerPhysicalCallableResultLayoutReference.Direct ->
        !hasSplitNullableResult &&
                (returnType as? DotNetIlReturnType.Value)?.type?.let { actual ->
                    slot.carrier.matchesEmitterCarrier(actual, scope, classInfo)
                } == true
    is DotNetGenericOwnerPhysicalCallableResultLayoutReference.SplitNullable ->
        hasSplitNullableResult &&
                (returnType as? DotNetIlReturnType.Value)?.type?.let { actual ->
                    payloadSlot.carrier.matchesEmitterCarrier(actual, scope, classInfo)
                } == true
}

/** Identity-complete final call edge which later physical consumers may safely retain. */
internal data class DotNetGenericOwnerPhysicalOperationSealedCallEdge(
    val method: DotNetGenericOwnerPhysicalMethodDefIdentity.Local,
    val receiverType: DotNetIlValueType,
    val methodArguments: List<DotNetIlValueType>,
    val parameterTypes: List<DotNetIlValueType>,
    val returnType: DotNetIlReturnType,
    val hasSplitNullableResult: Boolean,
) {
    init {
        require(methodArguments.isNotEmpty()) {
            "the first shared operation seal is restricted to a non-empty MethodSpec"
        }
    }
}

/**
 * Seals one already-authoritative local natural MethodSpec operation against final call emission.
 *
 * This query selects nothing. Its caller invokes it only for a BOUND local operation with a
 * non-empty MethodSpec vector. Every comparison happens before argument coercion, so a stale
 * route cannot be made plausible by boxing, casting, or a coincidentally equal instantiated
 * carrier. A mismatch is a declaration/emission conflict and never permission to fall back.
 */
internal fun sealDotNetLocalGenericOwnerPhysicalOperationCallEdge(
    route: DotNetGenericOwnerPhysicalOperationRoute,
    edge: DotNetIlRawForwardingCallEdge,
    ownerToken: String,
    declaredSignature: DotNetIlMethodSignature,
    liveSourceCarriers: List<DotNetIlValueType?>,
    currentTypeOwner: DotNetIlClassInfo,
    currentMethod: DotNetGenericOwnerPhysicalMethodDefIdentity.Local?,
    currentMethodGenericArity: Int,
    classInfo: (DotNetGenericOwnerPhysicalTypeDefIdentity) -> DotNetIlClassInfo?,
): DotNetGenericOwnerPhysicalBindingResult<DotNetGenericOwnerPhysicalOperationSealedCallEdge> {
    fun conflict(reason: String):
            DotNetGenericOwnerPhysicalBindingResult<DotNetGenericOwnerPhysicalOperationSealedCallEdge> =
        DotNetGenericOwnerPhysicalBindingResult.Conflict(reason)

    val selectedMethod = route.method.identity as?
            DotNetGenericOwnerPhysicalMethodDefIdentity.Local
        ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
    val declaringType = route.method.declaringType as?
            DotNetGenericOwnerPhysicalTypeDefIdentity.Local
        ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
    if (route.methodArguments.isEmpty()) return DotNetGenericOwnerPhysicalBindingResult.Unavailable
    if (declaringType.view != DotNetGenericInterfaceView.DECLARED) {
        return conflict("the authoritative MethodSpec route does not select a natural interface TypeDef")
    }
    val emittedMethod = edge.targetIdentity
        ?: return conflict("the emitted MethodSpec target has no physical MethodDef identity")
    if (!emittedMethod.sameLocalMethodIdentityAs(selectedMethod) ||
        edge.targetFunction !== selectedMethod.function
    ) {
        return conflict("final call emission selected a different physical MethodDef")
    }
    val emittedDeclaringType = classInfo(declaringType)
        ?: return conflict("the selected physical MethodDef owner was evicted before emission")
    if (edge.targetPhysicalOwner !== emittedDeclaringType) {
        return conflict("final call emission selected a different physical TypeDef")
    }
    if (emittedDeclaringType.typeParameterCount !=
        route.requiredReceiverView.construction.arguments.size
    ) {
        return conflict("the emitted physical TypeDef arity contradicts operation authority")
    }
    if (!route.method.signature.isInstance || !declaredSignature.hasThis || !edge.isVirtual) {
        return conflict("the authoritative natural operation did not emit as an instance callvirt")
    }
    if (route.method.declaringType != route.requiredReceiverView.family) {
        return conflict("the authoritative operation's MethodDef and receiver view disagree")
    }
    if (route.method.signature.genericArity != declaredSignature.methodGenericParameterCount ||
        route.instantiatedSignature.genericArity != route.method.signature.genericArity ||
        route.methodArguments.size != declaredSignature.methodGenericParameterCount
    ) {
        return conflict("the emitted MethodDef or MethodSpec arity contradicts operation authority")
    }

    val openReceiver = if (emittedDeclaringType.typeParameterCount == 0) {
        DotNetIlValueType.UserClass(emittedDeclaringType)
    } else {
        DotNetIlValueType.GenericInstance(
            emittedDeclaringType,
            List(emittedDeclaringType.typeParameterCount) { index ->
                DotNetIlValueType.TypeParameter(index, isMethodParameter = false)
            },
        )
    }
    val openScope = DotNetGenericOwnerEmitterBinderScope(
        emittedDeclaringType,
        selectedMethod,
        route.method.signature.genericArity,
    )
    val openParameters = declaredSignature.parameterTypes
    if (openParameters.firstOrNull() != openReceiver ||
        openParameters.drop(1).size != route.method.signature.parameterSlots.size ||
        route.method.signature.parameterSlots.indices.any { index ->
            !route.method.signature.parameterSlots[index].carrier.matchesEmitterCarrier(
                openParameters[index + 1],
                openScope,
                classInfo,
            )
        } ||
        !route.method.signature.resultLayout.matchesEmitterResult(
            declaredSignature.returnType,
            declaredSignature.hasSplitNullableResult,
            openScope,
            classInfo,
        )
    ) {
        return conflict("the emitted open MethodDef signature contradicts operation authority")
    }

    val callerScope = DotNetGenericOwnerEmitterBinderScope(
        currentTypeOwner,
        currentMethod,
        currentMethod?.let { currentMethodGenericArity } ?: 0,
    )
    if (!route.requiredReceiverView.construction.matchesEmitterCarrier(
            edge.targetOwner,
            callerScope,
            classInfo,
        )
    ) {
        return conflict("the emitted MemberRef owner contradicts the required receiver construction")
    }
    if (ownerToken != edge.targetOwner.nameInSignature) {
        return conflict("the rendered MemberRef owner token contradicts the sealed receiver construction")
    }
    if (edge.methodInstantiation.size != route.methodArguments.size ||
        route.methodArguments.indices.any { index ->
            !route.methodArguments[index].matchesEmitterCarrier(
                edge.methodInstantiation[index],
                callerScope,
                classInfo,
            )
        }
    ) {
        return conflict("the emitted MethodSpec vector contradicts operation authority")
    }
    if (edge.parameterTypes.firstOrNull() != edge.targetOwner ||
        edge.parameterTypes.drop(1).size != route.instantiatedSignature.parameterSlots.size ||
        route.instantiatedSignature.parameterSlots.indices.any { index ->
            !route.instantiatedSignature.parameterSlots[index].carrier.matchesEmitterCarrier(
                edge.parameterTypes[index + 1],
                callerScope,
                classInfo,
            )
        } ||
        !route.instantiatedSignature.resultLayout.matchesEmitterResult(
            edge.returnType,
            edge.hasSplitNullableResult,
            callerScope,
            classInfo,
        )
    ) {
        return conflict("the emitted instantiated call signature contradicts operation authority")
    }
    val producedLayoutMatches = when (val result = route.instantiatedSignature.resultLayout) {
        DotNetGenericOwnerPhysicalCallableResultLayoutReference.Void -> route.producedResult == null
        is DotNetGenericOwnerPhysicalCallableResultLayoutReference.Direct -> {
            val produced = route.producedResult
            val carrier = (produced?.layout as?
                    DotNetGenericOwnerProducedValueLayout.Direct)?.carrier
            carrier != null && carrier.type == result.slot.carrier &&
                    produced.nullState == if (carrier.canRepresentNull) {
                        DotNetGenericOwnerPhysicalNullState.MAYBE_NULL
                    } else {
                        DotNetGenericOwnerPhysicalNullState.NON_NULL
                    }
        }
        is DotNetGenericOwnerPhysicalCallableResultLayoutReference.SplitNullable -> {
            val produced = route.producedResult
            val carrier = (produced?.layout as?
                    DotNetGenericOwnerProducedValueLayout.SplitNullable)?.payloadCarrier
            carrier != null && carrier.type == result.payloadSlot.carrier &&
                    produced.nullState == DotNetGenericOwnerPhysicalNullState.MAYBE_NULL
        }
    }
    if (!producedLayoutMatches) {
        return conflict("the operation's produced-value fact contradicts its instantiated result layout")
    }

    if (liveSourceCarriers.size != edge.parameterTypes.size) {
        return conflict("the live call argument vector changed after operation binding")
    }
    val liveReceiver = liveSourceCarriers.firstOrNull()
        ?: return conflict("the authoritative receiver is no longer one direct physical storage read")
    if (liveReceiver.dotNetUniqueViewAsGenericOwner(emittedDeclaringType) != edge.targetOwner) {
        return conflict("the live receiver no longer supplies the required exact natural construction")
    }
    liveSourceCarriers.drop(1).zip(edge.parameterTypes.drop(1)).forEachIndexed { index, pair ->
        if (pair.first != pair.second) {
            return conflict("live ordinary argument $index no longer has its authenticated carrier")
        }
    }
    return DotNetGenericOwnerPhysicalBindingResult.Bound(
        DotNetGenericOwnerPhysicalOperationSealedCallEdge(
            selectedMethod,
            edge.targetOwner,
            edge.methodInstantiation.toList(),
            edge.parameterTypes.drop(1),
            edge.returnType,
            edge.hasSplitNullableResult,
        ),
    )
}

private fun DotNetGenericOwnerPhysicalMethodDefIdentity?.samePhysicalMethodIdentityAs(
    other: DotNetGenericOwnerPhysicalMethodDefIdentity,
): Boolean = when {
    this is DotNetGenericOwnerPhysicalMethodDefIdentity.Local &&
            other is DotNetGenericOwnerPhysicalMethodDefIdentity.Local ->
        sameLocalMethodIdentityAs(other)
    else -> this == other
}
