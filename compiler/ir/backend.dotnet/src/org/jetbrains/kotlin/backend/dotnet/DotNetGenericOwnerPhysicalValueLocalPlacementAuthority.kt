/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
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
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import java.util.ArrayDeque
import java.util.Collections
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
 * storage. The bounded vocabulary admits a local owner-bound construction, one direct parameter
 * of that owner, or an independently rebound external producer construction with fixed leaf
 * arguments. It therefore authorizes no cast, variance conversion, semantic adaptation, boxing,
 * nullable materialization, field/state choice, or ABI change.
 */
internal class DotNetGenericOwnerPhysicalValueRetainedProducedCarrier internal constructor(
    val carrier: DotNetGenericOwnerPhysicalCarrier,
    private val emitterValidation: DotNetGenericOwnerPhysicalValueEmitterValidation,
    private val externalImplementationOwner: IrClassSymbol? = null,
) {
    init {
        val definition = (carrier.type as?
                DotNetGenericOwnerSymbolicCarrierReference.Constructed)?.definition
        require(externalImplementationOwner == null ||
                definition is DotNetGenericOwnerPhysicalTypeDefIdentity.KotlinProducer) {
            "an external retained carrier requires a producer-recorded implementation TypeDef"
        }
    }

    /**
     * Joins symbolic authority with the live emitter mapping without trusting either alone.
     *
     * The physical MethodDef owner authenticates every `!n` binder. A constructed direct storage
     * read requires the live `ldarg`/`ldloc` source carrier, while a constructed direct call
     * requires the live result carrier selected by the ordinary physical-call resolver; other
     * admitted constructed definitions retain their legacy whole-expression comparison pending
     * their own live queries. An owner parameter likewise requires either the live source slot or
     * the live ordinary MethodDef result, according to its recorded initializer shape. An [IrWhen]
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

        // Preserve the already-proven constructed-reference consumer verbatim. A direct storage
        // read or call is checked against its live slot/ordinary MethodDef rather than a carrier
        // reconstructed from the expression's logical Kotlin type. Other admitted initializer
        // shapes retain their existing boundary until each has an equally independent live query.
        if (carrier.nullEncoding != DotNetGenericOwnerPhysicalNullEncoding.NULL_REFERENCE) return null
        val construction = carrier.type as?
                DotNetGenericOwnerSymbolicCarrierReference.Constructed ?: return null
        val definition = construction.definition
        val classInfo = when (definition) {
            is DotNetGenericOwnerPhysicalTypeDefIdentity.Local -> {
                if (externalImplementationOwner != null) return null
                definition.classInfoOrNull(typeMapper) ?: return null
            }
            is DotNetGenericOwnerPhysicalTypeDefIdentity.KotlinProducer -> {
                val logicalOwner = externalImplementationOwner?.owner ?: return null
                typeMapper.externalGenericOwnerPhysicalClassInfoOrNull(logicalOwner, definition)
                    ?: return null
            }
            is DotNetGenericOwnerPhysicalTypeDefIdentity.CoreLibrary,
            is DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr,
            -> return null
        }
        if (classInfo.typeParameterCount == 0 ||
            classInfo.typeParameterCount != construction.arguments.size
        ) return null
        val arguments = construction.arguments.map { argument ->
            when (definition) {
                is DotNetGenericOwnerPhysicalTypeDefIdentity.Local -> {
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
                is DotNetGenericOwnerPhysicalTypeDefIdentity.KotlinProducer ->
                    argument.fixedLeafIlTypeOrNull() ?: return null
                is DotNetGenericOwnerPhysicalTypeDefIdentity.CoreLibrary,
                is DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr,
                -> return null
            }
        }
        val expected = DotNetIlValueType.GenericInstance(classInfo, arguments)
        if (!expected.isDotNetReferenceShaped()) return null
        return when (emitterValidation) {
            DotNetGenericOwnerPhysicalValueEmitterValidation.WHOLE_EXPRESSION_CARRIER ->
                initializerCarrier?.takeIf { actual -> actual == expected }
            DotNetGenericOwnerPhysicalValueEmitterValidation.DIRECT_STORAGE_READ_CARRIER ->
                initializerDirectStorageReadCarrier?.takeIf { actual -> actual == expected }
            DotNetGenericOwnerPhysicalValueEmitterValidation.DIRECT_CALL_RESULT_CARRIER ->
                initializerDirectCallResultCarrier?.takeIf { actual -> actual == expected }
            DotNetGenericOwnerPhysicalValueEmitterValidation.CONTROL_FLOW_BRANCHES ->
                expected.takeIf { initializerUsesControlFlowBranches }
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
        require(methodArgumentTypes.isEmpty() || parameterTypes.size == 2) {
            "the bounded MethodSpec split-local call has an incompatible ordinary parameter vector"
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
            0 -> declaredSlots.all { slot ->
                slot.domain == DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_INPUT ||
                        (slot.domain ==
                            DotNetGenericOwnerPhysicalSlotDomain.DECLARATION_INDEPENDENT &&
                                slot.carrier.fixedLeafIlTypeOrNull() != null)
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
            if (methodArity == 0 &&
                slot.domain == DotNetGenericOwnerPhysicalSlotDomain.DECLARATION_INDEPENDENT
            ) {
                slot.carrier.fixedLeafIlTypeOrNull() ?: return null
            } else {
                val parameter = slot.carrier as?
                        DotNetGenericOwnerSymbolicCarrierReference.Parameter ?: return null
                parameter.bindDeclaredParameterOrNull(
                    operation.method,
                    receiverType.classInfo,
                ) ?: return null
            }
        }
        if (declaredParameterTypes.withIndex().any { indexed ->
                val index = indexed.index
                val type = indexed.value
                when {
                    methodArity == 1 && index == 1 -> {
                        val parameter = type as? DotNetIlValueType.TypeParameter
                        parameter == null || !parameter.isMethodParameter || parameter.index != 0
                    }
                    declaredSlots[index].domain ==
                            DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_INPUT -> {
                        val parameter = type as? DotNetIlValueType.TypeParameter
                        parameter == null || parameter.isMethodParameter
                    }
                    else -> type != declaredSlots[index].carrier.fixedLeafIlTypeOrNull()
                }
            }
        ) return null
        val parameterTypes = operation.instantiatedSignature.parameterSlots.mapIndexed { index, slot ->
            val declared = declaredSlots[index]
            if (methodArity == 0 &&
                declared.domain == DotNetGenericOwnerPhysicalSlotDomain.DECLARATION_INDEPENDENT
            ) {
                val declaredLeaf = declared.carrier.fixedLeafIlTypeOrNull() ?: return null
                slot.carrier.fixedLeafIlTypeOrNull()?.takeIf { type -> type == declaredLeaf }
                    ?: return null
            } else {
                val parameter = slot.carrier as?
                        DotNetGenericOwnerSymbolicCarrierReference.Parameter ?: return null
                parameter.bindOwnerParameterOrNull(typeMapper, physicalMethodOwner) ?: return null
            }
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

private fun DotNetGenericOwnerSymbolicCarrierReference.fixedLeafIlTypeOrNull(): DotNetIlValueType? {
    val kind = (this as? DotNetGenericOwnerSymbolicCarrierReference.Leaf)?.kind ?: return null
    return when (kind) {
        DotNetGenericOwnerPhysicalTypeKind.BOOLEAN -> DotNetIlValueType.Boolean
        DotNetGenericOwnerPhysicalTypeKind.INT32 -> DotNetIlValueType.Int32
        DotNetGenericOwnerPhysicalTypeKind.STRING -> DotNetIlValueType.String
        DotNetGenericOwnerPhysicalTypeKind.OBJECT -> DotNetIlValueType.Object
        DotNetGenericOwnerPhysicalTypeKind.VOID,
        DotNetGenericOwnerPhysicalTypeKind.OWNER_TYPE_PARAMETER,
        DotNetGenericOwnerPhysicalTypeKind.METHOD_TYPE_PARAMETER,
        DotNetGenericOwnerPhysicalTypeKind.NAMED,
        DotNetGenericOwnerPhysicalTypeKind.SZ_ARRAY,
        -> null
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
 * Final-IR local placement authority from the shared physical-value transfer model and bounded
 * independently authenticated external placement tokens.
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
        fun externalExactStorageRead(
            carrier: DotNetGenericOwnerPhysicalCarrier,
            implementationOwner: IrClassSymbol,
        ): DotNetGenericOwnerPhysicalValueRetainedProducedCarrier =
            DotNetGenericOwnerPhysicalValueRetainedProducedCarrier(
                carrier,
                DotNetGenericOwnerPhysicalValueEmitterValidation.DIRECT_STORAGE_READ_CARRIER,
                implementationOwner,
            )

        fun bind(
            records: List<DotNetGenericOwnerPhysicalValueShadowRecord>,
            authoritativeOperationsByCall:
                Map<IrCall, DotNetGenericOwnerPhysicalOperationRoute> = emptyMap(),
            externalSemanticEquivalentReceiverPlacements:
                Map<
                        IrSimpleFunctionSymbol,
                        Map<IrValueSymbol, DotNetGenericOwnerPhysicalValueRetainedProducedCarrier>,
                        > = emptyMap(),
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
                }

            // A denied call result is not merely unavailable for its own local. Any later alias
            // whose exact prediction was derived from that local must also lose the prediction;
            // otherwise its late slot check would observe the call local's real object carrier
            // and turn valid Kotlin into a compiler failure. This is the smallest acyclic
            // placement dependency closure; calls admitted below remain rooted in their exact
            // identity-bound final operation witness.
            val unavailableCallDependenciesByFunction = IdentityHashMap<
                    IrFunctionSymbol,
                    MutableSet<IrValueSymbol>,
                    >()
            val callBearingValuesByFunction = IdentityHashMap<
                    IrFunctionSymbol,
                    MutableSet<IrValueSymbol>,
                    >()
            finalRecordsByFunction.forEach { entry ->
                val function = entry.key
                val unavailable = Collections.newSetFromMap(
                    IdentityHashMap<IrValueSymbol, Boolean>(),
                )
                val callBearing = Collections.newSetFromMap(
                    IdentityHashMap<IrValueSymbol, Boolean>(),
                )
                val dependentsByValue = IdentityHashMap<
                        IrValueSymbol,
                        MutableSet<IrValueSymbol>,
                        >()
                entry.value.values.forEach { record ->
                    val initializer = (record.variable.owner as? IrVariable)?.initializer
                        ?: return@forEach
                    val dependencies = initializer.dotNetPhysicalValueDependencies()
                    dependencies.reads.forEach { source ->
                        dependentsByValue.getOrPut(source) {
                            Collections.newSetFromMap(
                                IdentityHashMap<IrValueSymbol, Boolean>(),
                            )
                        } += record.variable
                    }
                    if (dependencies.hasCall) callBearing += record.variable
                    if (dependencies.hasCall &&
                        record.predictedProducedValue.let { produced ->
                            produced == null ||
                                    (produced.layout is
                                            DotNetGenericOwnerProducedValueLayout.Direct &&
                                            initializer.hasUnavailablePhysicalCallDependency(
                                                produced,
                                                authoritativeOperationsByCallIdentity,
                                            ))
                        }
                    ) {
                        unavailable += record.variable
                    }
                }
                val work = ArrayDeque(unavailable)
                while (work.isNotEmpty()) {
                    dependentsByValue[work.removeFirst()].orEmpty().forEach { dependent ->
                        if (unavailable.add(dependent)) work.addLast(dependent)
                    }
                }
                if (unavailable.isNotEmpty()) {
                    unavailableCallDependenciesByFunction[function] = unavailable
                }
                if (callBearing.isNotEmpty()) {
                    callBearingValuesByFunction[function] = callBearing
                }
            }

            records.asSequence()
                .filter { record ->
                    record.snapshot.phase ==
                            DotNetGenericOwnerPhysicalValueShadowPhase.POST_FINAL_ROUTING
                }
                .forEach { record ->
                    if (record.variable in
                        unavailableCallDependenciesByFunction[record.physicalFunction].orEmpty()
                    ) return@forEach

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
                                ?.directPhysicalValueEmitterValidationOrNull()
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
                            val initializer = (record.variable.owner as? IrVariable)?.initializer
                                ?: return@forEach
                            if (initializer is IrWhen) {
                                DotNetGenericOwnerPhysicalValueEmitterValidation
                                    .CONTROL_FLOW_BRANCHES
                            } else {
                                initializer.directPhysicalValueEmitterValidationOrNull()
                                    ?: DotNetGenericOwnerPhysicalValueEmitterValidation
                                        .WHOLE_EXPRESSION_CARRIER
                            }
                        }
                        is DotNetGenericOwnerSymbolicCarrierReference.Leaf,
                        is DotNetGenericOwnerSymbolicCarrierReference.SzArray,
                        -> return@forEach
                    }
                    val initializer = (record.variable.owner as? IrVariable)?.initializer
                        ?: return@forEach
                    val initializerCall = initializer.identityPhysicalCallWitnessOrNull()
                    if (initializerCall != null) {
                        if (!initializerCall.call.isDotNetParameterlessDirectResultPlacementCall()) {
                            return@forEach
                        }
                        val operation = authoritativeOperationsByCallIdentity[initializerCall.call]
                            ?: return@forEach
                        if (!operation.producedResult.matchesRetainedInitializerResult(
                                produced,
                                initializerCall.hasImplicitNotNull,
                            ) ||
                            operation.instantiatedSignature.resultLayout !is
                                DotNetGenericOwnerPhysicalCallableResultLayoutReference.Direct
                        ) return@forEach
                    } else if (record.variable in
                        callBearingValuesByFunction[record.physicalFunction].orEmpty()
                    ) {
                        // Containers and control-flow expressions need a per-result-path route
                        // plan before they may retain a call-produced carrier. The value shadow
                        // can predict their natural result, but that is not evidence that every
                        // live call will bypass its semantic/capability emitter.
                        return@forEach
                    }
                    retainedByFunction.getOrPut(record.physicalFunction) {
                        IdentityHashMap()
                    }[record.variable] = DotNetGenericOwnerPhysicalValueRetainedProducedCarrier(
                        direct.carrier,
                        emitterValidation,
                    )
                }

            externalSemanticEquivalentReceiverPlacements.forEach { functionEntry ->
                val retainedForFunction = retainedByFunction.getOrPut(functionEntry.key) {
                    IdentityHashMap()
                }
                functionEntry.value.forEach { valueEntry ->
                    if (retainedForFunction.put(valueEntry.key, valueEntry.value) != null) {
                        return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                            "one physical local received both local and external placement authority",
                        )
                    }
                }
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

private fun IrExpression.directPhysicalValueEmitterValidationOrNull():
        DotNetGenericOwnerPhysicalValueEmitterValidation? = when (this) {
    is IrTypeOperatorCall -> if (
        operator == IrTypeOperator.IMPLICIT_CAST ||
        operator == IrTypeOperator.IMPLICIT_NOTNULL
    ) {
        argument.directPhysicalValueEmitterValidationOrNull()
    } else {
        null
    }
    is IrGetValue -> DotNetGenericOwnerPhysicalValueEmitterValidation.DIRECT_STORAGE_READ_CARRIER
    is IrCall -> DotNetGenericOwnerPhysicalValueEmitterValidation.DIRECT_CALL_RESULT_CARRIER
    else -> null
}

private data class DotNetGenericOwnerIdentityPhysicalCallWitness(
    val call: IrCall,
    val hasImplicitNotNull: Boolean,
)

/** The first retained direct-result grammar has no MethodSpec or non-dispatch input slots. */
internal fun IrCall.isDotNetParameterlessDirectResultPlacementCall(): Boolean =
    dispatchReceiver != null && symbol.owner.typeParameters.isEmpty() && typeArguments.isEmpty() &&
            symbol.owner.parameters.count { parameter ->
                parameter.kind == IrParameterKind.DispatchReceiver
            } == 1 &&
            symbol.owner.parameters.all { parameter ->
                parameter.kind == IrParameterKind.DispatchReceiver
            }

private fun IrExpression.identityPhysicalCallWitnessOrNull():
        DotNetGenericOwnerIdentityPhysicalCallWitness? = when (this) {
    is IrCall -> DotNetGenericOwnerIdentityPhysicalCallWitness(this, false)
    is IrTypeOperatorCall -> if (
        operator == IrTypeOperator.IMPLICIT_CAST ||
        operator == IrTypeOperator.IMPLICIT_NOTNULL
    ) {
        argument.identityPhysicalCallWitnessOrNull()?.let { witness ->
            witness.copy(
                hasImplicitNotNull = witness.hasImplicitNotNull ||
                        operator == IrTypeOperator.IMPLICIT_NOTNULL,
            )
        }
    } else {
        null
    }
    else -> null
}

private fun DotNetGenericOwnerProducedValueFact?.matchesRetainedInitializerResult(
    retained: DotNetGenericOwnerProducedValueFact,
    hasImplicitNotNull: Boolean,
): Boolean {
    val routed = this ?: return false
    if (routed.layout != retained.layout ||
        !retained.provenance.isMonotoneRefinementOf(routed.provenance)
    ) return false
    return routed.nullState == retained.nullState ||
            (hasImplicitNotNull &&
                    routed.nullState == DotNetGenericOwnerPhysicalNullState.MAYBE_NULL &&
                    retained.nullState == DotNetGenericOwnerPhysicalNullState.NON_NULL)
}

/**
 * A destination may select an already-guaranteed physical view or learn additional views from
 * recorded CLR ancestry without changing the value's carrier. Lineage remains a selector and is
 * never authority: every selected view must occur in the refined guaranteed-view set, and an
 * existing selection may not silently change.
 */
private fun DotNetGenericOwnerPhysicalValueProvenance.isMonotoneRefinementOf(
    source: DotNetGenericOwnerPhysicalValueProvenance,
): Boolean {
    val refinedViews = guaranteedViews
    val sourceViews = source.guaranteedViews
    when (sourceViews) {
        DotNetGenericOwnerGuaranteedViews.Unknown ->
            if (refinedViews !is DotNetGenericOwnerGuaranteedViews.Unknown) return false
        is DotNetGenericOwnerGuaranteedViews.Known -> {
            val refinedKnown = refinedViews as? DotNetGenericOwnerGuaranteedViews.Known
                ?: return false
            if (!refinedKnown.views.containsAll(sourceViews.views)) return false
        }
    }
    if (source.selectedViewLineage.any { entry ->
        selectedViewLineage[entry.key] != entry.value
    }) return false
    val knownRefinedViews = (refinedViews as? DotNetGenericOwnerGuaranteedViews.Known)
        ?.views.orEmpty()
    return selectedViewLineage.all { entry ->
        entry.value.family == entry.key && entry.value in knownRefinedViews
    }
}

private fun IrExpression.hasUnavailablePhysicalCallDependency(
    produced: DotNetGenericOwnerProducedValueFact,
    operations: IdentityHashMap<IrCall, DotNetGenericOwnerPhysicalOperationRoute>,
): Boolean {
    val witness = identityPhysicalCallWitnessOrNull() ?: return true
    if (!witness.call.isDotNetParameterlessDirectResultPlacementCall()) return true
    val operation = operations[witness.call] ?: return true
    return operation.instantiatedSignature.resultLayout !is
            DotNetGenericOwnerPhysicalCallableResultLayoutReference.Direct ||
            !operation.producedResult.matchesRetainedInitializerResult(
                produced,
                witness.hasImplicitNotNull,
            )
}

private data class DotNetPhysicalValueDependencies(
    val reads: Set<IrValueSymbol>,
    val hasCall: Boolean,
)

private fun IrExpression.dotNetPhysicalValueDependencies(): DotNetPhysicalValueDependencies {
    val reads = Collections.newSetFromMap(IdentityHashMap<IrValueSymbol, Boolean>())
    var hasCall = false
    acceptVoid(object : IrVisitorVoid() {
        override fun visitElement(element: IrElement) {
            element.acceptChildrenVoid(this)
        }

        override fun visitClass(declaration: IrClass) = Unit

        override fun visitFunction(declaration: IrFunction) = Unit

        override fun visitGetValue(expression: IrGetValue) {
            reads += expression.symbol
        }

        override fun visitCall(expression: IrCall) {
            hasCall = true
            expression.acceptChildrenVoid(this)
        }
    })
    return DotNetPhysicalValueDependencies(reads, hasCall)
}

/**
 * Conservatively identifies method calls whose final physical route must participate in local
 * placement. Nested declarations are separate emission scopes and therefore do not contaminate
 * the enclosing initializer.
 */
internal fun IrExpression.containsDotNetPhysicalCall(): Boolean {
    var found = false
    acceptVoid(object : IrVisitorVoid() {
        override fun visitElement(element: IrElement) {
            if (!found) element.acceptChildrenVoid(this)
        }

        override fun visitClass(declaration: IrClass) = Unit

        override fun visitFunction(declaration: IrFunction) = Unit

        override fun visitCall(expression: IrCall) {
            found = true
        }
    })
    return found
}
