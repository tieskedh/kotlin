/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrTry
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.expressions.IrWhen
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import java.util.IdentityHashMap

internal enum class DotNetGenericOwnerPhysicalValueEmitterValidation {
    WHOLE_EXPRESSION_CARRIER,
    DIRECT_STORAGE_READ_CARRIER,
    DIRECT_CALL_RESULT_CARRIER,
    CONTROL_FLOW_BRANCHES,
}

/**
 * One operation-scoped permission to retain the carrier which an initializer already produces.
 *
 * The destination is not reconstructed from its logical Kotlin type. The token exists only when
 * final value flow independently selected exactly the same direct carrier for production and
 * storage. The bounded vocabulary admits either a local owner-bound constructed reference or one
 * direct parameter of that same owner. It therefore authorizes no cast, variance conversion,
 * semantic adaptation, boxing, nullable materialization, field/state choice, or ABI change.
 */
internal class DotNetGenericOwnerPhysicalValueRetainedProducedCarrier internal constructor(
    val carrier: DotNetGenericOwnerPhysicalCarrier,
    private val emitterValidation: DotNetGenericOwnerPhysicalValueEmitterValidation,
) {
    /**
     * Joins symbolic authority with the live emitter mapping without trusting either alone.
     *
     * The physical MethodDef owner authenticates every `!n` binder. A direct initializer must
     * independently report exactly that reconstructed verifier-visible construction. An owner
     * parameter additionally requires either the live `ldarg`/`ldloc` source carrier or the live
     * resolved MethodDef result carrier, according to its recorded initializer shape. An [IrWhen]
     * must remain a live control-flow initializer; the variable emitter then supplies the selected
     * local type as a fixed boundary and independently validates every branch during emission. A
     * changed or evicted mapping fails closed instead of silently selecting another carrier.
     */
    fun bindEmitterCarrierOrNull(
        typeMapper: DotNetIlTypeMapper,
        physicalMethodOwner: DotNetIlClassInfo,
        initializerCarrier: DotNetIlValueType?,
        initializerDirectStorageReadCarrier: DotNetIlValueType?,
        initializerDirectCallResultCarrier: DotNetIlValueType?,
        initializerUsesControlFlowBranches: Boolean,
    ): DotNetIlValueType? {
        val parameter = carrier.type as? DotNetGenericOwnerSymbolicCarrierReference.Parameter
        if (parameter != null) {
            if (carrier.nullEncoding !=
                DotNetGenericOwnerPhysicalNullEncoding.SUBSTITUTION_DEPENDENT ||
                (emitterValidation !=
                    DotNetGenericOwnerPhysicalValueEmitterValidation.DIRECT_STORAGE_READ_CARRIER &&
                        emitterValidation !=
                        DotNetGenericOwnerPhysicalValueEmitterValidation.DIRECT_CALL_RESULT_CARRIER)
            ) return null
            val expected = parameter.bindOwnerParameterOrNull(typeMapper, physicalMethodOwner)
                ?: return null
            val actual = when (emitterValidation) {
                DotNetGenericOwnerPhysicalValueEmitterValidation.DIRECT_STORAGE_READ_CARRIER ->
                    initializerDirectStorageReadCarrier
                DotNetGenericOwnerPhysicalValueEmitterValidation.DIRECT_CALL_RESULT_CARRIER ->
                    initializerDirectCallResultCarrier
                DotNetGenericOwnerPhysicalValueEmitterValidation.WHOLE_EXPRESSION_CARRIER,
                DotNetGenericOwnerPhysicalValueEmitterValidation.CONTROL_FLOW_BRANCHES,
                -> null
            }
            return actual?.takeIf { candidate -> candidate == expected }
        }

        // Preserve the already-proven constructed-reference consumer verbatim. The parameter
        // extension above must not alter which semantic-hook locals this earlier rule admits.
        if (carrier.nullEncoding != DotNetGenericOwnerPhysicalNullEncoding.NULL_REFERENCE) return null
        val construction = carrier.type as?
                DotNetGenericOwnerSymbolicCarrierReference.Constructed ?: return null
        val definition = construction.definition as?
                DotNetGenericOwnerPhysicalTypeDefIdentity.Local ?: return null
        val classInfo = definition.classInfoOrNull(typeMapper) ?: return null
        if (classInfo.typeParameterCount == 0 ||
            classInfo.typeParameterCount != construction.arguments.size
        ) return null
        val arguments = construction.arguments.map { argument ->
            val ownerParameter = argument as?
                    DotNetGenericOwnerSymbolicCarrierReference.Parameter ?: return null
            val binder = (ownerParameter.binder as?
                    DotNetGenericOwnerPhysicalGenericBinderReference.Type)
                ?.definition as? DotNetGenericOwnerPhysicalTypeDefIdentity.Local ?: return null
            if (binder.classInfoOrNull(typeMapper) !== physicalMethodOwner ||
                ownerParameter.index !in 0 until physicalMethodOwner.typeParameterCount
            ) return null
            DotNetIlValueType.TypeParameter(ownerParameter.index, isMethodParameter = false)
        }
        val expected = DotNetIlValueType.GenericInstance(classInfo, arguments)
        if (!expected.isDotNetReferenceShaped()) return null
        return when (emitterValidation) {
            DotNetGenericOwnerPhysicalValueEmitterValidation.WHOLE_EXPRESSION_CARRIER ->
                initializerCarrier?.takeIf { actual -> actual == expected }
            DotNetGenericOwnerPhysicalValueEmitterValidation.CONTROL_FLOW_BRANCHES ->
                expected.takeIf { initializerUsesControlFlowBranches }
            DotNetGenericOwnerPhysicalValueEmitterValidation.DIRECT_STORAGE_READ_CARRIER,
            DotNetGenericOwnerPhysicalValueEmitterValidation.DIRECT_CALL_RESULT_CARRIER,
            -> null
        }
    }

}

/** Verifier-visible shape independently reconstructed from one authoritative exact operation. */
internal data class DotNetGenericOwnerPhysicalValueBoundSplitNullableCall(
    val methodIdentity: DotNetGenericOwnerPhysicalMethodDefIdentity.Local,
    val declaredReceiverType: DotNetIlValueType.GenericInstance,
    val declaredParameterTypes: List<DotNetIlValueType>,
    val declaredPayloadType: DotNetIlValueType,
    val receiverType: DotNetIlValueType.GenericInstance,
    val parameterTypes: List<DotNetIlValueType>,
    val methodArgumentTypes: List<DotNetIlValueType>,
    val payloadType: DotNetIlValueType.TypeParameter,
) {
    init {
        require(declaredParameterTypes.size == parameterTypes.size) {
            "a bounded split-local call must retain both its open and instantiated parameter vectors"
        }
        require(methodArgumentTypes.size <= 1) {
            "the bounded split-local call grammar admits at most one MethodSpec argument"
        }
        require(
            if (methodArgumentTypes.isEmpty()) parameterTypes.size <= 1
            else parameterTypes.size == 2
        ) {
            "the bounded split-local call grammar received too many ordinary parameters"
        }
    }
}

/** One exact initializer call paired with the independently bound operation it may emit. */
internal data class DotNetGenericOwnerPhysicalValueBoundSplitNullableCallSite(
    val call: IrCall,
    val boundCall: DotNetGenericOwnerPhysicalValueBoundSplitNullableCall,
)

/**
 * Late-bound, identity-keyed initializer plan for one retained payload/null-flag pair.
 *
 * [callsInEvaluationOrder] preserves branch order for emission. [boundCallForExactCallOrNull]
 * deliberately uses reference identity: a later structurally similar call cannot inherit an
 * operation decision made for a different IR node.
 */
internal sealed class DotNetGenericOwnerPhysicalValueBoundSplitNullableInitializer(
    callsInEvaluationOrder: List<DotNetGenericOwnerPhysicalValueBoundSplitNullableCallSite>,
) {
    val callsInEvaluationOrder = callsInEvaluationOrder.toList()
    private val callsByIdentity = IdentityHashMap<
            IrCall,
            DotNetGenericOwnerPhysicalValueBoundSplitNullableCallSite,
            >()

    init {
        require(this.callsInEvaluationOrder.isNotEmpty()) {
            "a split-nullable initializer plan requires at least one call"
        }
        this.callsInEvaluationOrder.forEach { site ->
            require(callsByIdentity.put(site.call, site) == null) {
                "a split-nullable initializer plan cannot reuse one IrCall node"
            }
        }
    }

    fun boundCallForExactCallOrNull(
        call: IrCall,
    ): DotNetGenericOwnerPhysicalValueBoundSplitNullableCall? =
        callsByIdentity[call]?.boundCall

    class DirectCall internal constructor(
        val callSite: DotNetGenericOwnerPhysicalValueBoundSplitNullableCallSite,
    ) : DotNetGenericOwnerPhysicalValueBoundSplitNullableInitializer(listOf(callSite))

    class FlatExhaustiveWhen internal constructor(
        val expression: IrWhen,
        callsInEvaluationOrder: List<DotNetGenericOwnerPhysicalValueBoundSplitNullableCallSite>,
    ) : DotNetGenericOwnerPhysicalValueBoundSplitNullableInitializer(callsInEvaluationOrder) {
        init {
            require(callsInEvaluationOrder.size >= 2) {
                "a split-nullable control-flow initializer requires at least two reachable calls"
            }
        }
    }
}

internal data class RetainedSplitNullableCallSite(
    val call: IrCall,
    val operation: DotNetGenericOwnerPhysicalOperationRoute,
)

internal sealed interface RetainedSplitNullableInitializer {
    data class DirectCall(
        val callSite: RetainedSplitNullableCallSite,
    ) : RetainedSplitNullableInitializer

    data class FlatExhaustiveWhen(
        val expression: IrWhen,
        val callsInEvaluationOrder: List<RetainedSplitNullableCallSite>,
    ) : RetainedSplitNullableInitializer
}

/**
 * Permission to retain one authoritative split initializer as its typed payload plus null flag.
 *
 * A direct call remains the smallest plan. A flat exhaustive [IrWhen] may name several mutually
 * exclusive calls, but each exact call identity owns an independent final operation witness.
 */
internal class DotNetGenericOwnerPhysicalValueRetainedSplitNullable internal constructor(
    val payloadCarrier: DotNetGenericOwnerPhysicalCarrier,
    private val initializer: RetainedSplitNullableInitializer,
) {
    /**
     * Revalidates the complete live initializer before the emitter declares either pair local.
     *
     * Both the initializer node and every reachable call must be the exact nodes retained by
     * final placement authority. Wrappers, nested containers, changed reachability, reordering,
     * duplicate call nodes, and missing or additional branches therefore fail closed.
     */
    fun bindEmitterInitializerOrNull(
        typeMapper: DotNetIlTypeMapper,
        physicalMethodOwner: DotNetIlClassInfo,
        liveInitializer: IrExpression,
    ): DotNetGenericOwnerPhysicalValueBoundSplitNullableInitializer? {
        return when (val plan = initializer) {
            is RetainedSplitNullableInitializer.DirectCall -> {
                if (liveInitializer !== plan.callSite.call) return null
                val bound = plan.callSite.operation.bindEmitterCallOrNull(
                    typeMapper,
                    physicalMethodOwner,
                ) ?: return null
                DotNetGenericOwnerPhysicalValueBoundSplitNullableInitializer.DirectCall(
                    DotNetGenericOwnerPhysicalValueBoundSplitNullableCallSite(
                        plan.callSite.call,
                        bound,
                    ),
                )
            }
            is RetainedSplitNullableInitializer.FlatExhaustiveWhen -> {
                if (liveInitializer !== plan.expression) return null
                val liveCalls = plan.expression.dotNetFlatExhaustiveSplitOperationCallsOrNull()
                    ?: return null
                if (liveCalls.size != plan.callsInEvaluationOrder.size ||
                    liveCalls.indices.any { index ->
                        liveCalls[index] !== plan.callsInEvaluationOrder[index].call
                    }
                ) return null
                val boundSites = plan.callsInEvaluationOrder.map { site ->
                    val bound = site.operation.bindEmitterCallOrNull(
                        typeMapper,
                        physicalMethodOwner,
                    ) ?: return null
                    DotNetGenericOwnerPhysicalValueBoundSplitNullableCallSite(site.call, bound)
                }
                DotNetGenericOwnerPhysicalValueBoundSplitNullableInitializer.FlatExhaustiveWhen(
                    plan.expression,
                    boundSites,
                )
            }
        }
    }

    private fun DotNetGenericOwnerPhysicalOperationRoute.bindEmitterCallOrNull(
        typeMapper: DotNetIlTypeMapper,
        physicalMethodOwner: DotNetIlClassInfo,
    ): DotNetGenericOwnerPhysicalValueBoundSplitNullableCall? {
        val operation = this
        val methodIdentity = operation.method.identity as?
                DotNetGenericOwnerPhysicalMethodDefIdentity.Local ?: return null
        val methodArity = operation.method.signature.genericArity
        if (!operation.method.signature.isInstance ||
            methodArity !in 0..1 ||
            operation.instantiatedSignature.genericArity != methodArity ||
            operation.methodArguments.size != methodArity ||
            operation.method.genericParameters.size != methodArity ||
            operation.method.genericParameters.any { parameter -> !parameter.isUnconstrained } ||
            operation.method.declaringType != operation.requiredReceiverView.family
        ) return null
        val declaredResult = operation.method.signature.resultLayout as?
                DotNetGenericOwnerPhysicalCallableResultLayoutReference.SplitNullable
            ?: return null
        val instantiatedResult = operation.instantiatedSignature.resultLayout as?
                DotNetGenericOwnerPhysicalCallableResultLayoutReference.SplitNullable
            ?: return null
        if (declaredResult.nullFlag != instantiatedResult.nullFlag ||
            declaredResult.payloadSlot.domain !=
            DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_OUTPUT ||
            instantiatedResult.payloadSlot.domain !=
            DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_OUTPUT ||
            instantiatedResult.payloadSlot.carrier != payloadCarrier.type ||
            operation.producedResult?.layout !=
            DotNetGenericOwnerProducedValueLayout.SplitNullable(payloadCarrier)
        ) return null
        val receiverType = operation.requiredReceiverView.construction
            .bindCurrentOwnerConstructionOrNull(typeMapper, physicalMethodOwner)
            ?: return null
        val declaredReceiverType = DotNetIlValueType.GenericInstance(
            receiverType.classInfo,
            List(receiverType.classInfo.typeParameterCount) { index ->
                DotNetIlValueType.TypeParameter(index, isMethodParameter = false)
            },
        )
        val declaredSlots = operation.method.signature.parameterSlots
        val instantiatedSlots = operation.instantiatedSignature.parameterSlots
        if (declaredSlots.size != instantiatedSlots.size) return null
        val admittedDomains = when (methodArity) {
            0 -> declaredSlots.size <= 1 && declaredSlots.all { slot ->
                slot.domain == DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_INPUT
            }
            1 -> declaredSlots.map { slot -> slot.domain } == listOf(
                DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_INPUT,
                DotNetGenericOwnerPhysicalSlotDomain.DECLARATION_INDEPENDENT,
            )
            else -> false
        }
        if (!admittedDomains || declaredSlots.map { slot -> slot.domain } !=
            instantiatedSlots.map { slot -> slot.domain }
        ) return null
        val declaredParameterTypes = declaredSlots.map { slot ->
            val parameter = slot.carrier as?
                    DotNetGenericOwnerSymbolicCarrierReference.Parameter ?: return null
            parameter.bindDeclaredParameterOrNull(
                operation.method,
                receiverType.classInfo,
            ) ?: return null
        }
        if (declaredParameterTypes.withIndex().any { indexed ->
                val index = indexed.index
                val type = indexed.value
                if (methodArity == 1 && index == 1) {
                    !type.isMethodParameter || type.index != 0
                } else {
                    type.isMethodParameter
                }
            }
        ) return null
        val parameterTypes = operation.instantiatedSignature.parameterSlots.map { slot ->
            val parameter = slot.carrier as?
                    DotNetGenericOwnerSymbolicCarrierReference.Parameter ?: return null
            parameter.bindOwnerParameterOrNull(typeMapper, physicalMethodOwner) ?: return null
        }
        val methodArgumentTypes = operation.methodArguments.map { argument ->
            val parameter = argument as?
                    DotNetGenericOwnerSymbolicCarrierReference.Parameter ?: return null
            parameter.bindOwnerParameterOrNull(typeMapper, physicalMethodOwner) ?: return null
        }
        if (methodArity == 1 &&
            (declaredParameterTypes.lastOrNull() !=
                    DotNetIlValueType.TypeParameter(0, isMethodParameter = true) ||
                    parameterTypes.lastOrNull() != methodArgumentTypes.single())
        ) return null
        val declaredPayload = declaredResult.payloadSlot.carrier as?
                DotNetGenericOwnerSymbolicCarrierReference.Parameter ?: return null
        val declaredPayloadType = declaredPayload.bindDeclaredParameterOrNull(
            operation.method,
            receiverType.classInfo,
        ) ?: return null
        if (declaredPayloadType.isMethodParameter) return null
        val payloadType = payloadCarrier.bindCurrentOwnerParameterOrNull(
            typeMapper,
            physicalMethodOwner,
        ) ?: return null
        return DotNetGenericOwnerPhysicalValueBoundSplitNullableCall(
            methodIdentity,
            declaredReceiverType,
            declaredParameterTypes,
            declaredPayloadType,
            receiverType,
            parameterTypes,
            methodArgumentTypes,
            payloadType,
        )
    }
}

private fun retainedSplitNullableInitializerOrNull(
    initializer: IrExpression,
    produced: DotNetGenericOwnerProducedValueFact,
    authoritativeOperationsByCall: IdentityHashMap<
            IrCall,
            DotNetGenericOwnerPhysicalOperationRoute,
            >,
): RetainedSplitNullableInitializer? {
    val calls = when (initializer) {
        is IrCall -> listOf(initializer)
        is IrWhen -> initializer.dotNetFlatExhaustiveSplitOperationCallsOrNull() ?: return null
        else -> return null
    }
    val sites = calls.map { call ->
        val operation = authoritativeOperationsByCall[call] ?: return null
        RetainedSplitNullableCallSite(call, operation)
    }
    val operationResults = sites.map { site ->
        site.operation.producedResult
            ?.takeIf { result ->
                result.layout is DotNetGenericOwnerProducedValueLayout.SplitNullable
            }
            ?: return null
    }
    val joinedOperationResult = operationResults.drop(1).fold(operationResults.first()) { joined, next ->
        joined.joinAtIdenticalSplitNullablePayloadOrNull(next) ?: return null
    }
    if (joinedOperationResult != produced) return null
    return when (initializer) {
        is IrCall -> RetainedSplitNullableInitializer.DirectCall(sites.single())
        is IrWhen -> RetainedSplitNullableInitializer.FlatExhaustiveWhen(initializer, sites)
        else -> error("handled above")
    }
}

/**
 * Final-IR local placement authority derived from the shared physical-value transfer model.
 *
 * Correlation is by IR symbol identity. Diagnostic snapshots and IR origins are deliberately not
 * inputs. Unsupported records merely contribute no authority; contradictory duplicate final
 * records are a compiler conflict rather than an arbitrary winner.
 */
internal class DotNetGenericOwnerPhysicalValueLocalPlacementAuthority private constructor(
    private val retainedProducedCarriersByFunction:
            IdentityHashMap<
                    IrFunctionSymbol,
                    IdentityHashMap<IrValueSymbol, DotNetGenericOwnerPhysicalValueRetainedProducedCarrier>,
                    >,
    private val retainedSplitNullableByFunction:
            IdentityHashMap<
                    IrFunctionSymbol,
                    IdentityHashMap<IrValueSymbol, DotNetGenericOwnerPhysicalValueRetainedSplitNullable>,
                    >,
) {
    fun retainedProducedCarrierOrNull(
        function: IrFunctionSymbol,
        variable: IrValueSymbol,
    ): DotNetGenericOwnerPhysicalValueRetainedProducedCarrier? =
        retainedProducedCarriersByFunction[function]?.get(variable)

    fun retainedSplitNullableOrNull(
        function: IrFunctionSymbol,
        variable: IrValueSymbol,
    ): DotNetGenericOwnerPhysicalValueRetainedSplitNullable? =
        retainedSplitNullableByFunction[function]?.get(variable)

    companion object {
        fun bind(
            records: List<DotNetGenericOwnerPhysicalValueShadowRecord>,
            authoritativeOperationsByCall:
                Map<IrCall, DotNetGenericOwnerPhysicalOperationRoute> = emptyMap(),
        ): DotNetGenericOwnerPhysicalBindingResult<DotNetGenericOwnerPhysicalValueLocalPlacementAuthority> {
            val authoritativeOperationsByCallIdentity = IdentityHashMap<
                    IrCall,
                    DotNetGenericOwnerPhysicalOperationRoute,
                    >().apply {
                authoritativeOperationsByCall.forEach { entry ->
                    put(entry.key, entry.value)
                }
            }
            val finalRecordsByFunction = IdentityHashMap<
                    IrFunctionSymbol,
                    IdentityHashMap<IrValueSymbol, DotNetGenericOwnerPhysicalValueShadowRecord>,
                    >()
            val retainedByFunction = IdentityHashMap<
                    IrFunctionSymbol,
                    IdentityHashMap<
                            IrValueSymbol,
                            DotNetGenericOwnerPhysicalValueRetainedProducedCarrier,
                            >,
                    >()
            val retainedSplitByFunction = IdentityHashMap<
                    IrFunctionSymbol,
                    IdentityHashMap<
                            IrValueSymbol,
                            DotNetGenericOwnerPhysicalValueRetainedSplitNullable,
                            >,
                    >()

            records.asSequence()
                .filter { record ->
                    record.snapshot.phase ==
                            DotNetGenericOwnerPhysicalValueShadowPhase.POST_FINAL_ROUTING
                }
                .forEach { record ->
                    val finalByValue = finalRecordsByFunction.getOrPut(record.physicalFunction) {
                        IdentityHashMap()
                    }
                    if (finalByValue.put(record.variable, record) != null) {
                        return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                            "one physical local received multiple final value-flow records",
                        )
                    }

                    val produced = record.predictedProducedValue ?: return@forEach
                    val storage = record.predictedStorage ?: return@forEach
                    val physicalOwner = (record.physicalFunction.owner.parent as? IrClass)?.symbol
                        ?: return@forEach
                    val split = produced.layout as?
                            DotNetGenericOwnerProducedValueLayout.SplitNullable
                    val splitStorage = storage.storageLayout as?
                            DotNetGenericOwnerPhysicalStorageLayout.SplitNullable
                    if (split != null && splitStorage != null) {
                        val variable = record.variable.owner as? IrVariable ?: return@forEach
                        val initializer = variable.initializer ?: return@forEach
                        val initializerPlan = retainedSplitNullableInitializerOrNull(
                            initializer,
                            produced,
                            authoritativeOperationsByCallIdentity,
                        ) ?: return@forEach
                        if (split.payloadCarrier != splitStorage.primaryCarrier.carrier ||
                            !variable.hasOnlyUnprotectedDirectFunctionReturnUsesIn(record.physicalFunction.owner)
                        ) return@forEach
                        val parameter = split.payloadCarrier.type as?
                                DotNetGenericOwnerSymbolicCarrierReference.Parameter
                            ?: return@forEach
                        val binder = (parameter.binder as?
                                DotNetGenericOwnerPhysicalGenericBinderReference.Type)
                            ?.definition as? DotNetGenericOwnerPhysicalTypeDefIdentity.Local
                            ?: return@forEach
                        if (split.payloadCarrier.nullEncoding !=
                            DotNetGenericOwnerPhysicalNullEncoding.SUBSTITUTION_DEPENDENT ||
                            binder.owner !== physicalOwner ||
                            parameter.index !in physicalOwner.owner.typeParameters.indices
                        ) return@forEach
                        retainedSplitByFunction.getOrPut(record.physicalFunction) {
                            IdentityHashMap()
                        }[record.variable] =
                            DotNetGenericOwnerPhysicalValueRetainedSplitNullable(
                                split.payloadCarrier,
                                initializerPlan,
                            )
                        return@forEach
                    }
                    val direct = produced.layout as? DotNetGenericOwnerProducedValueLayout.Direct
                        ?: return@forEach
                    val directStorage = storage.storageLayout as?
                            DotNetGenericOwnerPhysicalStorageLayout.Direct ?: return@forEach
                    if (direct.carrier != directStorage.primaryCarrier.carrier) return@forEach
                    val emitterValidation = when (val symbolic = direct.carrier.type) {
                        is DotNetGenericOwnerSymbolicCarrierReference.Parameter -> {
                            val binder = (symbolic.binder as?
                                    DotNetGenericOwnerPhysicalGenericBinderReference.Type)
                                ?.definition as? DotNetGenericOwnerPhysicalTypeDefIdentity.Local
                                ?: return@forEach
                            if (direct.carrier.nullEncoding !=
                                DotNetGenericOwnerPhysicalNullEncoding.SUBSTITUTION_DEPENDENT ||
                                binder.owner !== physicalOwner ||
                                symbolic.index !in physicalOwner.owner.typeParameters.indices ||
                                (record.variable.owner as? IrVariable)?.initializer is IrWhen
                            ) return@forEach
                            (record.variable.owner as? IrVariable)
                                ?.initializer
                                ?.directOwnerParameterEmitterValidationOrNull()
                                ?: return@forEach
                        }
                        is DotNetGenericOwnerSymbolicCarrierReference.Constructed -> {
                            if (symbolic.definition !is DotNetGenericOwnerPhysicalTypeDefIdentity.Local) {
                                return@forEach
                            }
                            if (symbolic.arguments.any { argument ->
                                    val ownerParameter = argument as?
                                            DotNetGenericOwnerSymbolicCarrierReference.Parameter
                                        ?: return@any true
                                    val binder = (ownerParameter.binder as?
                                            DotNetGenericOwnerPhysicalGenericBinderReference.Type)
                                        ?.definition as? DotNetGenericOwnerPhysicalTypeDefIdentity.Local
                                        ?: return@any true
                                    binder.owner !== physicalOwner ||
                                            ownerParameter.index !in
                                            physicalOwner.owner.typeParameters.indices
                                } || direct.carrier.nullEncoding !=
                                DotNetGenericOwnerPhysicalNullEncoding.NULL_REFERENCE
                            ) return@forEach
                            if ((record.variable.owner as? IrVariable)?.initializer is IrWhen) {
                                DotNetGenericOwnerPhysicalValueEmitterValidation.CONTROL_FLOW_BRANCHES
                            } else {
                                DotNetGenericOwnerPhysicalValueEmitterValidation.WHOLE_EXPRESSION_CARRIER
                            }
                        }
                        is DotNetGenericOwnerSymbolicCarrierReference.Leaf,
                        is DotNetGenericOwnerSymbolicCarrierReference.SzArray,
                        -> return@forEach
                    }
                    retainedByFunction.getOrPut(record.physicalFunction) {
                        IdentityHashMap()
                    }[record.variable] = DotNetGenericOwnerPhysicalValueRetainedProducedCarrier(
                        direct.carrier,
                        emitterValidation,
                    )
                }

            if (retainedByFunction.isEmpty() && retainedSplitByFunction.isEmpty()) {
                return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            }
            return DotNetGenericOwnerPhysicalBindingResult.Bound(
                DotNetGenericOwnerPhysicalValueLocalPlacementAuthority(
                    retainedByFunction,
                    retainedSplitByFunction,
                ),
            )
        }
    }
}

/**
 * A retained split local may be forwarded by any positive number of unprotected direct returns
 * from the same physical function. Each executed return terminates its path, so these static use
 * sites never become sequential consumers of the pair. Any comparison, argument, capture, copy,
 * protected return, or other read keeps using the established materializing path until its own
 * transfer is proven.
 */
internal data class DotNetGenericOwnerPhysicalSplitLocalUseSummary(
    val readCount: Int,
    val directFunctionReturnCount: Int,
    val directOtherReturnCount: Int,
    val protectedRegionReturnCount: Int,
    val returnValueKinds: Set<String>,
) {
    val hasOnlyUnprotectedDirectFunctionReturnUses: Boolean
        get() = readCount > 0 && readCount == directFunctionReturnCount &&
                directOtherReturnCount == 0 && protectedRegionReturnCount == 0
}

internal fun IrVariable.splitLocalUseSummaryIn(
    function: IrSimpleFunction,
): DotNetGenericOwnerPhysicalSplitLocalUseSummary {
    var readCount = 0
    var directFunctionReturnCount = 0
    var directOtherReturnCount = 0
    var protectedRegionReturnCount = 0
    var protectedRegionDepth = 0
    val returnValueKinds = linkedSetOf<String>()
    function.body?.acceptVoid(object : IrVisitorVoid() {
        override fun visitElement(element: IrElement) {
            element.acceptChildrenVoid(this)
        }

        override fun visitGetValue(expression: IrGetValue) {
            if (expression.symbol === symbol) readCount++
            expression.acceptChildrenVoid(this)
        }

        override fun visitReturn(expression: IrReturn) {
            returnValueKinds += expression.value.javaClass.simpleName
            if ((expression.value as? IrGetValue)?.symbol === symbol) {
                if (expression.returnTargetSymbol === function.symbol) {
                    directFunctionReturnCount++
                    if (protectedRegionDepth > 0) protectedRegionReturnCount++
                } else {
                    directOtherReturnCount++
                }
            }
            expression.acceptChildrenVoid(this)
        }

        override fun visitTry(aTry: IrTry) {
            protectedRegionDepth++
            try {
                aTry.acceptChildrenVoid(this)
            } finally {
                protectedRegionDepth--
            }
        }
    })
    return DotNetGenericOwnerPhysicalSplitLocalUseSummary(
        readCount,
        directFunctionReturnCount,
        directOtherReturnCount,
        protectedRegionReturnCount,
        returnValueKinds,
    )
}

internal fun IrVariable.hasOnlyUnprotectedDirectFunctionReturnUsesIn(function: IrSimpleFunction): Boolean =
    splitLocalUseSummaryIn(function).hasOnlyUnprotectedDirectFunctionReturnUses

private fun DotNetGenericOwnerPhysicalCarrier.bindCurrentOwnerParameterOrNull(
    typeMapper: DotNetIlTypeMapper,
    physicalMethodOwner: DotNetIlClassInfo,
): DotNetIlValueType.TypeParameter? {
    if (nullEncoding != DotNetGenericOwnerPhysicalNullEncoding.SUBSTITUTION_DEPENDENT) return null
    val parameter = type as? DotNetGenericOwnerSymbolicCarrierReference.Parameter ?: return null
    return parameter.bindOwnerParameterOrNull(typeMapper, physicalMethodOwner)
}

private fun DotNetGenericOwnerSymbolicCarrierReference.Constructed
        .bindCurrentOwnerConstructionOrNull(
            typeMapper: DotNetIlTypeMapper,
            physicalMethodOwner: DotNetIlClassInfo,
        ): DotNetIlValueType.GenericInstance? {
    val definition = definition as? DotNetGenericOwnerPhysicalTypeDefIdentity.Local ?: return null
    val classInfo = definition.classInfoOrNull(typeMapper) ?: return null
    if (classInfo.typeParameterCount == 0 || classInfo.typeParameterCount != arguments.size) {
        return null
    }
    val boundArguments = arguments.map { argument ->
        val parameter = argument as?
                DotNetGenericOwnerSymbolicCarrierReference.Parameter ?: return null
        parameter.bindOwnerParameterOrNull(typeMapper, physicalMethodOwner) ?: return null
    }
    return DotNetIlValueType.GenericInstance(classInfo, boundArguments)
}

private fun DotNetGenericOwnerSymbolicCarrierReference.Parameter.bindOwnerParameterOrNull(
    typeMapper: DotNetIlTypeMapper,
    physicalMethodOwner: DotNetIlClassInfo,
): DotNetIlValueType.TypeParameter? {
    val binder = (binder as? DotNetGenericOwnerPhysicalGenericBinderReference.Type)
        ?.definition as? DotNetGenericOwnerPhysicalTypeDefIdentity.Local ?: return null
    if (binder.classInfoOrNull(typeMapper) !== physicalMethodOwner ||
        index !in 0 until physicalMethodOwner.typeParameterCount
    ) return null
    return DotNetIlValueType.TypeParameter(index, isMethodParameter = false)
}

private fun DotNetGenericOwnerSymbolicCarrierReference.Parameter.bindDeclaredParameterOrNull(
    method: DotNetGenericOwnerPhysicalMethodDefReference,
    declaringType: DotNetIlClassInfo,
): DotNetIlValueType.TypeParameter? = when (val parameterBinder = binder) {
    is DotNetGenericOwnerPhysicalGenericBinderReference.Type ->
        DotNetIlValueType.TypeParameter(index, isMethodParameter = false).takeIf {
            parameterBinder.definition == method.declaringType &&
                    index in 0 until declaringType.typeParameterCount
        }
    is DotNetGenericOwnerPhysicalGenericBinderReference.Method ->
        DotNetIlValueType.TypeParameter(index, isMethodParameter = true).takeIf {
            parameterBinder.definition == method.identity &&
                    index in 0 until method.signature.genericArity
        }
}

private fun DotNetGenericOwnerPhysicalTypeDefIdentity.Local.classInfoOrNull(
    typeMapper: DotNetIlTypeMapper,
): DotNetIlClassInfo? = if (view == null) {
    typeMapper.classInfoOrNull(owner.owner)
} else {
    typeMapper.genericInterfaceInfoOrNull(owner.owner)?.classInfo(view)
}

private fun IrExpression.directOwnerParameterEmitterValidationOrNull():
        DotNetGenericOwnerPhysicalValueEmitterValidation? {
    val source = when (this) {
        is IrTypeOperatorCall -> if (
            operator == IrTypeOperator.IMPLICIT_CAST ||
            operator == IrTypeOperator.IMPLICIT_NOTNULL
        ) {
            argument
        } else {
            this
        }
        else -> this
    }
    return when (source) {
        is IrGetValue -> DotNetGenericOwnerPhysicalValueEmitterValidation.DIRECT_STORAGE_READ_CARRIER
        is IrCall -> DotNetGenericOwnerPhysicalValueEmitterValidation.DIRECT_CALL_RESULT_CARRIER
        else -> null
    }
}
