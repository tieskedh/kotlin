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

/** Verifier-visible direct-call shape rebound from one retained authoritative operation. */
internal data class DotNetGenericOwnerPhysicalValueBoundDirectCall(
    val methodIdentity: DotNetGenericOwnerPhysicalMethodDefIdentity.Local,
    val declaredReceiverType: DotNetIlValueType.GenericInstance,
    val declaredParameterTypes: List<DotNetIlValueType>,
    val declaredResultType: DotNetIlValueType,
    val receiverType: DotNetIlValueType.GenericInstance,
    val parameterTypes: List<DotNetIlValueType>,
    val methodArgumentTypes: List<DotNetIlValueType>,
    val resultType: DotNetIlValueType,
) {
    init {
        require(declaredParameterTypes.size == parameterTypes.size) {
            "a bounded direct-result call must retain both parameter vectors"
        }
        require(methodArgumentTypes.size <= 1) {
            "the bounded direct-result grammar admits at most one MethodSpec argument"
        }
        require(methodArgumentTypes.isEmpty() == parameterTypes.isEmpty()) {
            "a bounded direct-result MethodSpec requires exactly one ordinary input"
        }
    }
}

internal enum class DotNetGenericOwnerPhysicalDirectResultCallKind {
    PARAMETERLESS,
    CURRENT_CALLER_METHOD_PARAMETER,
}

/** One exact result-path leaf and the complete operation selected for that IR identity. */
internal data class RetainedDirectResultCallSite(
    val call: IrCall,
    val operation: DotNetGenericOwnerPhysicalOperationRoute,
    val kind: DotNetGenericOwnerPhysicalDirectResultCallKind,
)

/** One exact live result leaf plus the declaration operation from which it was rebound. */
internal data class DotNetGenericOwnerPhysicalValueBoundDirectResultCallSite(
    val call: IrCall,
    val operation: DotNetGenericOwnerPhysicalOperationRoute,
    val boundCall: DotNetGenericOwnerPhysicalValueBoundDirectCall,
)

/** One exact prefix definition and its independently retained direct-storage authority. */
internal data class DotNetGenericOwnerPhysicalValueRetainedSequentialPrefix(
    val variable: IrVariable,
    val placement: DotNetGenericOwnerPhysicalValueRetainedProducedCarrier,
)

/** One prefix definition rebound before emission creates its own local slot. */
internal data class DotNetGenericOwnerPhysicalValueBoundSequentialPrefix(
    val variable: IrVariable,
    val placement: DotNetGenericOwnerPhysicalValueRetainedProducedCarrier,
    val storageType: DotNetIlValueType,
)

/** Ordered emitter work retained before any prefix local has a verifier-visible slot. */
internal data class DotNetGenericOwnerPhysicalValueBoundSequentialPrefixInitializer(
    val prefixesInEvaluationOrder: List<DotNetGenericOwnerPhysicalValueBoundSequentialPrefix>,
    val physicalResultAfterPrefixes: IrExpression,
    val resultCallsInEvaluationOrder:
            List<DotNetGenericOwnerPhysicalValueBoundDirectResultCallSite>,
    val resultType: DotNetIlValueType,
) {
    init {
        require(prefixesInEvaluationOrder.isNotEmpty()) {
            "an ordered prefix initializer requires at least one prefix definition"
        }
        require(resultCallsInEvaluationOrder.singleOrNull()
            ?.boundCall?.methodArgumentTypes?.size == 1) {
            "the first ordered prefix gate requires one caller-MethodDef MethodSpec result"
        }
    }
}

/**
 * Identity-bound direct-result plan whose leaves retain declaration authority, not just a type.
 *
 * The structural plan decides which calls actually supply the initializer result. Each retained
 * site then carries the exact post-routing MethodDef operation selected for that call. Late
 * binding reconstructs verifier types from those operations before the ordinary emitter resolver
 * is allowed to confirm them; an unrelated MethodDef with the same result carrier cannot satisfy
 * this plan.
 */
internal class RetainedDirectResultInitializer internal constructor(
    private val structuralPlan: DotNetGenericOwnerPhysicalDirectResultInitializerPlan,
    val resultCarrier: DotNetGenericOwnerPhysicalCarrier,
    callSites: List<RetainedDirectResultCallSite>,
    sequentialPrefixes: List<DotNetGenericOwnerPhysicalValueRetainedSequentialPrefix> = emptyList(),
) {
    private val callSites = callSites.toList()
    private val sequentialPrefixes = sequentialPrefixes.toList()

    init {
        require(this.callSites.size == structuralPlan.callsInEvaluationOrder.size &&
                this.callSites.indices.all { index ->
                    this.callSites[index].call ===
                            structuralPlan.callsInEvaluationOrder[index].call
                }
        ) {
            "a retained direct-result plan requires one operation for every exact result leaf"
        }
        require(
            if (structuralPlan.hasSequentialPrefixes) {
                this.sequentialPrefixes.isEmpty() ||
                        (this.sequentialPrefixes.size ==
                                structuralPlan.sequentialPrefixVariablesInEvaluationOrder.size &&
                                this.sequentialPrefixes.indices.all { index ->
                                    this.sequentialPrefixes[index].variable ===
                                            structuralPlan
                                                .sequentialPrefixVariablesInEvaluationOrder[index]
                                })
            } else {
                this.sequentialPrefixes.isEmpty()
            },
        ) {
            "a retained direct-result plan requires each exact prefix placement in order"
        }
    }

    val hasSequentialPrefixes: Boolean
        get() = structuralPlan.hasSequentialPrefixes

    val sequentialPrefixVariablesInEvaluationOrder: List<IrVariable>
        get() = structuralPlan.sequentialPrefixVariablesInEvaluationOrder

    fun bindSequentialPrefixesOrNull(
        placement: (IrVariable) -> DotNetGenericOwnerPhysicalValueRetainedProducedCarrier?,
    ): RetainedDirectResultInitializer? {
        if (!structuralPlan.hasSequentialPrefixes) return this
        if (sequentialPrefixes.isNotEmpty()) return this
        val resultSite = callSites.singleOrNull()
        if (resultSite?.kind !=
            DotNetGenericOwnerPhysicalDirectResultCallKind.CURRENT_CALLER_METHOD_PARAMETER
        ) return null
        val variables = structuralPlan.sequentialPrefixVariablesInEvaluationOrder
        if (!structuralPlan.hasSequentialReceiverAndInputPrefixPair) return null
        val expectedReceiver = resultSite.operation.requiredReceiverView.construction
        val expectedInput = resultSite.operation.methodArguments.singleOrNull() ?: return null
        val instantiatedInput = resultSite.operation.instantiatedSignature.parameterSlots
            .singleOrNull()?.carrier ?: return null
        if (instantiatedInput != expectedInput) return null
        val retained = variables.mapIndexed { index, variable ->
            val token = placement(variable)
                ?.takeIf { candidate -> candidate.isDirectStorageReadAuthority }
                ?: return null
            val hasExpectedCarrier = when (index) {
                0 -> token.carrier.type == expectedReceiver &&
                        token.carrier.nullEncoding ==
                        DotNetGenericOwnerPhysicalNullEncoding.NULL_REFERENCE
                1 -> token.carrier.type == expectedInput &&
                        token.carrier.nullEncoding ==
                        DotNetGenericOwnerPhysicalNullEncoding.SUBSTITUTION_DEPENDENT
                else -> false
            }
            if (!hasExpectedCarrier) return null
            DotNetGenericOwnerPhysicalValueRetainedSequentialPrefix(variable, token)
        }
        return RetainedDirectResultInitializer(
            structuralPlan,
            resultCarrier,
            callSites,
            retained,
        )
    }

    fun bindEmitterCallsOrNull(
        typeMapper: DotNetIlTypeMapper,
        physicalMethodOwner: DotNetIlClassInfo,
        physicalMethodIdentity: DotNetGenericOwnerPhysicalMethodDefIdentity.Local?,
        physicalMethodGenericArity: Int,
        liveInitializer: IrExpression,
        expectedResultType: DotNetIlValueType,
    ): List<DotNetGenericOwnerPhysicalValueBoundDirectResultCallSite>? {
        if (!structuralPlan.matchesLiveInitializer(liveInitializer)) return null
        return callSites.map { site ->
            val bound = site.operation.bindEmitterDirectCallOrNull(
                typeMapper,
                physicalMethodOwner,
                physicalMethodIdentity,
                physicalMethodGenericArity,
                resultCarrier,
                expectedResultType,
                site.kind,
            ) ?: return null
            DotNetGenericOwnerPhysicalValueBoundDirectResultCallSite(
                site.call,
                site.operation,
                bound,
            )
        }
    }

    fun bindEmitterSequentialPrefixInitializerOrNull(
        typeMapper: DotNetIlTypeMapper,
        physicalMethodOwner: DotNetIlClassInfo,
        physicalMethodIdentity: DotNetGenericOwnerPhysicalMethodDefIdentity.Local?,
        physicalMethodGenericArity: Int,
        liveInitializer: IrExpression,
        expectedResultType: DotNetIlValueType,
    ): DotNetGenericOwnerPhysicalValueBoundSequentialPrefixInitializer? {
        if (!structuralPlan.hasSequentialPrefixes ||
            sequentialPrefixes.isEmpty()
        ) return null
        val boundCalls = bindEmitterCallsOrNull(
                typeMapper,
                physicalMethodOwner,
                physicalMethodIdentity,
                physicalMethodGenericArity,
                liveInitializer,
                expectedResultType,
            ) ?: return null
        if (boundCalls.singleOrNull()?.boundCall?.methodArgumentTypes?.size != 1) return null
        val boundPrefixes = sequentialPrefixes.map { prefix ->
            val storageType = prefix.placement.bindExpectedEmitterCarrierOrNull(
                typeMapper,
                physicalMethodOwner,
                physicalMethodIdentity,
                physicalMethodGenericArity,
            ) ?: return null
            DotNetGenericOwnerPhysicalValueBoundSequentialPrefix(
                prefix.variable,
                prefix.placement,
                storageType,
            )
        }
        val physicalResult = structuralPlan.physicalResultAfterSequentialPrefixes ?: return null
        return DotNetGenericOwnerPhysicalValueBoundSequentialPrefixInitializer(
            boundPrefixes,
            physicalResult,
            boundCalls,
            expectedResultType,
        )
    }
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
    private val directResultInitializer: RetainedDirectResultInitializer? = null,
    private val externalImplementationOwner: IrClassSymbol? = null,
) {
    init {
        val definition = (carrier.type as?
                DotNetGenericOwnerSymbolicCarrierReference.Constructed)?.definition
        require(externalImplementationOwner == null ||
                definition is DotNetGenericOwnerPhysicalTypeDefIdentity.KotlinProducer) {
            "an external retained carrier requires a producer-recorded implementation TypeDef"
        }
        require(
            (emitterValidation ==
                    DotNetGenericOwnerPhysicalValueEmitterValidation.DIRECT_CALL_RESULT_CARRIER) ==
                    (directResultInitializer != null),
        ) {
            "a direct call-result carrier requires exactly one identity-bound result-path plan"
        }
        require(directResultInitializer == null ||
                directResultInitializer.resultCarrier == carrier) {
            "a direct result-path plan must retain the destination's exact produced carrier"
        }
    }

    val hasSequentialPrefixInitializer: Boolean
        get() = directResultInitializer?.hasSequentialPrefixes == true

    internal val sequentialPrefixVariablesInEvaluationOrder: List<IrVariable>
        get() = directResultInitializer?.sequentialPrefixVariablesInEvaluationOrder.orEmpty()

    internal val isDirectStorageReadAuthority: Boolean
        get() = emitterValidation ==
                DotNetGenericOwnerPhysicalValueEmitterValidation.DIRECT_STORAGE_READ_CARRIER &&
                directResultInitializer == null

    internal fun bindSequentialPrefixesOrNull(
        placement: (IrVariable) -> DotNetGenericOwnerPhysicalValueRetainedProducedCarrier?,
    ): DotNetGenericOwnerPhysicalValueRetainedProducedCarrier? {
        val initializer = directResultInitializer ?: return this
        val rebound = initializer.bindSequentialPrefixesOrNull(placement) ?: return null
        return if (rebound === initializer) {
            this
        } else {
            DotNetGenericOwnerPhysicalValueRetainedProducedCarrier(
                carrier,
                emitterValidation,
                rebound,
                externalImplementationOwner,
            )
        }
    }

    /**
     * Binds an ordered prefix plan from declaration authority before its locals are emitted.
     *
     * This intentionally does not claim that any result call is live yet. Every prefix variable
     * must receive its own independent placement token from the enclosing authority, then normal
     * emission creates those slots in order. [bindEmitterCarrierOrNull] is called again only
     * afterwards and performs the existing live receiver/MethodDef/result validation.
     */
    fun bindEmitterSequentialPrefixInitializerOrNull(
        typeMapper: DotNetIlTypeMapper,
        physicalMethodOwner: DotNetIlClassInfo,
        physicalMethodIdentity: DotNetGenericOwnerPhysicalMethodDefIdentity.Local? = null,
        physicalMethodGenericArity: Int = 0,
        liveInitializer: IrExpression,
    ): DotNetGenericOwnerPhysicalValueBoundSequentialPrefixInitializer? {
        val plan = directResultInitializer ?: return null
        val expected = bindExpectedEmitterCarrierOrNull(
            typeMapper,
            physicalMethodOwner,
            physicalMethodIdentity,
            physicalMethodGenericArity,
        ) ?: return null
        return plan.bindEmitterSequentialPrefixInitializerOrNull(
            typeMapper,
            physicalMethodOwner,
            physicalMethodIdentity,
            physicalMethodGenericArity,
            liveInitializer,
            expected,
        )
    }

    /**
     * Joins symbolic authority with the live emitter mapping without trusting either alone.
     *
     * The physical MethodDef owner authenticates every `!n` binder. A constructed direct storage
     * read requires the live `ldarg`/`ldloc` source carrier, while a constructed direct call
     * requires the live result carrier selected by the ordinary physical-call resolver; other
     * admitted constructed definitions retain their legacy whole-expression comparison pending
     * their own live queries. A direct result-path plan rewalks the exact live initializer and
     * independently resolves every result-producing call; condition, receiver, and argument calls
     * cannot donate authority. An owner parameter likewise requires either the live source slot or
     * every live ordinary MethodDef result, according to its recorded initializer shape. An
     * unplanned [IrWhen] must remain a live control-flow initializer; the variable emitter then
     * supplies the selected local type as a fixed boundary and independently validates every
     * branch during emission. A changed or evicted mapping fails closed instead of silently
     * selecting another carrier.
     */
    fun bindEmitterCarrierOrNull(
        typeMapper: DotNetIlTypeMapper,
        physicalMethodOwner: DotNetIlClassInfo,
        physicalMethodIdentity: DotNetGenericOwnerPhysicalMethodDefIdentity.Local? = null,
        physicalMethodGenericArity: Int = 0,
        liveInitializer: IrExpression?,
        initializerCarrier: DotNetIlValueType?,
        initializerDirectStorageReadCarrier: DotNetIlValueType?,
        initializerDirectCallResultCarrier:
                ((IrCall, DotNetGenericOwnerPhysicalValueBoundDirectCall) ->
                        DotNetIlValueType?)?,
        initializerUsesControlFlowBranches: Boolean,
    ): DotNetIlValueType? {
        fun validateDirectCallResults(expected: DotNetIlValueType): DotNetIlValueType? {
            val plan = directResultInitializer ?: return null
            val initializer = liveInitializer ?: return null
            val resolveResult = initializerDirectCallResultCarrier ?: return null
            val boundCalls = plan.bindEmitterCallsOrNull(
                typeMapper,
                physicalMethodOwner,
                physicalMethodIdentity,
                physicalMethodGenericArity,
                initializer,
                expected,
            ) ?: return null
            return expected.takeIf {
                boundCalls.all { site ->
                    resolveResult(site.call, site.boundCall) == expected
                }
            }
        }

        val expected = bindExpectedEmitterCarrierOrNull(
            typeMapper,
            physicalMethodOwner,
            physicalMethodIdentity,
            physicalMethodGenericArity,
        ) ?: return null
        return when (emitterValidation) {
            DotNetGenericOwnerPhysicalValueEmitterValidation.WHOLE_EXPRESSION_CARRIER ->
                initializerCarrier?.takeIf { actual -> actual == expected }
            DotNetGenericOwnerPhysicalValueEmitterValidation.DIRECT_STORAGE_READ_CARRIER ->
                initializerDirectStorageReadCarrier?.takeIf { actual -> actual == expected }
            DotNetGenericOwnerPhysicalValueEmitterValidation.DIRECT_CALL_RESULT_CARRIER ->
                validateDirectCallResults(expected)
            DotNetGenericOwnerPhysicalValueEmitterValidation.CONTROL_FLOW_BRANCHES ->
                expected.takeIf { initializerUsesControlFlowBranches }
        }
    }

    /** Resolves only the authority-recorded destination carrier, never an initializer type. */
    internal fun bindExpectedEmitterCarrierOrNull(
        typeMapper: DotNetIlTypeMapper,
        physicalMethodOwner: DotNetIlClassInfo,
        physicalMethodIdentity: DotNetGenericOwnerPhysicalMethodDefIdentity.Local?,
        physicalMethodGenericArity: Int,
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
            return when (parameter.binder) {
                is DotNetGenericOwnerPhysicalGenericBinderReference.Type ->
                    parameter.bindOwnerParameterOrNull(typeMapper, physicalMethodOwner)
                is DotNetGenericOwnerPhysicalGenericBinderReference.Method -> {
                    if (emitterValidation !=
                        DotNetGenericOwnerPhysicalValueEmitterValidation.DIRECT_STORAGE_READ_CARRIER
                    ) return null
                    parameter.bindCurrentMethodParameterOrNull(
                        physicalMethodIdentity,
                        physicalMethodGenericArity,
                    )
                }
            }
        }

        // Preserve the already-proven constructed-reference consumer verbatim. The construction
        // is rebound from physical TypeDef/parameter authority, never from the logical Kotlin
        // type of either the initializer or destination.
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
        return DotNetIlValueType.GenericInstance(classInfo, arguments)
            .takeIf { expected -> expected.isDotNetReferenceShaped() }
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

/**
 * Rebinds the first direct-result grammar from frozen operation authority to live IL types.
 *
 * This is intentionally narrower than general call binding: one natural instance MethodDef,
 * either no inputs/MethodSpec or the already-proven current-caller `!!0` input, and one strict
 * owner-derived direct result. The emitter still resolves the call independently and compares
 * every field of the returned descriptor.
 */
private fun DotNetGenericOwnerPhysicalOperationRoute.bindEmitterDirectCallOrNull(
    typeMapper: DotNetIlTypeMapper,
    physicalMethodOwner: DotNetIlClassInfo,
    physicalMethodIdentity: DotNetGenericOwnerPhysicalMethodDefIdentity.Local?,
    physicalMethodGenericArity: Int,
    expectedCarrier: DotNetGenericOwnerPhysicalCarrier,
    expectedResultType: DotNetIlValueType,
    kind: DotNetGenericOwnerPhysicalDirectResultCallKind,
): DotNetGenericOwnerPhysicalValueBoundDirectCall? {
    val operation = this
    val methodIdentity = operation.method.identity as?
            DotNetGenericOwnerPhysicalMethodDefIdentity.Local ?: return null
    val declaringIdentity = operation.method.declaringType as?
            DotNetGenericOwnerPhysicalTypeDefIdentity.Local ?: return null
    if (!operation.method.signature.isInstance ||
        declaringIdentity.view != DotNetGenericInterfaceView.DECLARED ||
        operation.method.declaringType != operation.requiredReceiverView.family
    ) return null
    when (kind) {
        DotNetGenericOwnerPhysicalDirectResultCallKind.PARAMETERLESS ->
            if (operation.method.signature.genericArity != 0 ||
                operation.instantiatedSignature.genericArity != 0 ||
                operation.methodArguments.isNotEmpty() ||
                operation.method.genericParameters.isNotEmpty() ||
                operation.method.signature.parameterSlots.isNotEmpty() ||
                operation.instantiatedSignature.parameterSlots.isNotEmpty()
            ) return null
        DotNetGenericOwnerPhysicalDirectResultCallKind.CURRENT_CALLER_METHOD_PARAMETER -> {
            val caller = physicalMethodIdentity ?: return null
            if (physicalMethodGenericArity != 1 ||
                operation.method.signature.genericArity != 1 ||
                operation.instantiatedSignature.genericArity != 1 ||
                operation.method.genericParameters.singleOrNull()?.isUnconstrained != true ||
                operation.method.signature.parameterSlots.size != 1 ||
                operation.instantiatedSignature.parameterSlots.size != 1
            ) return null
            val methodArgument = operation.methodArguments.singleOrNull() as?
                    DotNetGenericOwnerSymbolicCarrierReference.Parameter ?: return null
            val binder = methodArgument.binder as?
                    DotNetGenericOwnerPhysicalGenericBinderReference.Method ?: return null
            val localBinder = binder.definition as?
                    DotNetGenericOwnerPhysicalMethodDefIdentity.Local ?: return null
            if (!localBinder.sameLocalMethodIdentityAs(caller) || methodArgument.index != 0) {
                return null
            }
            val declaredInput = operation.method.signature.parameterSlots.single()
            val instantiatedInput = operation.instantiatedSignature.parameterSlots.single()
            if (declaredInput.domain !=
                    DotNetGenericOwnerPhysicalSlotDomain.DECLARATION_INDEPENDENT ||
                instantiatedInput.domain != declaredInput.domain ||
                instantiatedInput.carrier != methodArgument
            ) return null
        }
    }
    val declaredResult = operation.method.signature.resultLayout as?
            DotNetGenericOwnerPhysicalCallableResultLayoutReference.Direct ?: return null
    val instantiatedResult = operation.instantiatedSignature.resultLayout as?
            DotNetGenericOwnerPhysicalCallableResultLayoutReference.Direct ?: return null
    if (declaredResult.slot.domain !=
            DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_OUTPUT ||
        instantiatedResult.slot.domain !=
            DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_OUTPUT ||
        instantiatedResult.slot.carrier != expectedCarrier.type ||
        operation.producedResult?.layout !=
            DotNetGenericOwnerProducedValueLayout.Direct(expectedCarrier)
    ) return null
    val boundExpected = expectedCarrier.bindCurrentOwnerCarrierOrNull(
        typeMapper,
        physicalMethodOwner,
    ) ?: return null
    if (boundExpected != expectedResultType) return null
    val receiverType = operation.requiredReceiverView.construction
        .bindCurrentOwnerConstructionOrNull(typeMapper, physicalMethodOwner)
        ?: return null
    val declaredReceiverType = DotNetIlValueType.GenericInstance(
        receiverType.classInfo,
        List(receiverType.classInfo.typeParameterCount) { index ->
            DotNetIlValueType.TypeParameter(index, isMethodParameter = false)
        },
    )
    val declaredResultType = declaredResult.slot.carrier.bindDeclaredCarrierOrNull(
        typeMapper,
        operation.method,
        receiverType.classInfo,
    ) ?: return null
    val substitutedDeclaredResult = try {
        declaredResultType.substituteDotNetTypeParameters(receiverType.arguments)
    } catch (_: IllegalStateException) {
        return null
    }
    if (substitutedDeclaredResult != expectedResultType) return null
    val declaredParameterTypes = operation.method.signature.parameterSlots.map { slot ->
        slot.carrier.bindDeclaredCarrierOrNull(
            typeMapper,
            operation.method,
            receiverType.classInfo,
        ) ?: return null
    }
    val parameterTypes = operation.instantiatedSignature.parameterSlots.map { slot ->
        slot.carrier.bindCurrentCallSiteCarrierOrNull(
            typeMapper,
            physicalMethodOwner,
            physicalMethodIdentity,
            physicalMethodGenericArity,
        ) ?: return null
    }
    val methodArgumentTypes = operation.methodArguments.map { argument ->
        argument.bindCurrentCallSiteCarrierOrNull(
            typeMapper,
            physicalMethodOwner,
            physicalMethodIdentity,
            physicalMethodGenericArity,
        ) ?: return null
    }
    if (kind == DotNetGenericOwnerPhysicalDirectResultCallKind.CURRENT_CALLER_METHOD_PARAMETER &&
        (declaredParameterTypes.singleOrNull() !=
                DotNetIlValueType.TypeParameter(0, isMethodParameter = true) ||
                parameterTypes.singleOrNull() != methodArgumentTypes.singleOrNull())
    ) return null
    return DotNetGenericOwnerPhysicalValueBoundDirectCall(
        methodIdentity,
        declaredReceiverType,
        declaredParameterTypes,
        declaredResultType,
        receiverType,
        parameterTypes,
        methodArgumentTypes,
        expectedResultType,
    )
}

/** Binds an already-recorded carrier in the current caller scope without logical remapping. */
private fun DotNetGenericOwnerSymbolicCarrierReference.bindCurrentCallSiteCarrierOrNull(
    typeMapper: DotNetIlTypeMapper,
    physicalMethodOwner: DotNetIlClassInfo,
    physicalMethodIdentity: DotNetGenericOwnerPhysicalMethodDefIdentity.Local?,
    physicalMethodGenericArity: Int,
): DotNetIlValueType? = when (this) {
    is DotNetGenericOwnerSymbolicCarrierReference.Leaf -> fixedLeafIlTypeOrNull()
    is DotNetGenericOwnerSymbolicCarrierReference.Parameter -> when (binder) {
        is DotNetGenericOwnerPhysicalGenericBinderReference.Type ->
            bindOwnerParameterOrNull(typeMapper, physicalMethodOwner)
        is DotNetGenericOwnerPhysicalGenericBinderReference.Method ->
            bindCurrentMethodParameterOrNull(
                physicalMethodIdentity,
                physicalMethodGenericArity,
            )
    }
    is DotNetGenericOwnerSymbolicCarrierReference.Constructed ->
        bindCurrentOwnerConstructionOrNull(typeMapper, physicalMethodOwner)
    is DotNetGenericOwnerSymbolicCarrierReference.SzArray -> null
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

private fun DotNetGenericOwnerPhysicalCarrier.bindCurrentOwnerCarrierOrNull(
    typeMapper: DotNetIlTypeMapper,
    physicalMethodOwner: DotNetIlClassInfo,
): DotNetIlValueType? {
    return when (type) {
        is DotNetGenericOwnerSymbolicCarrierReference.Parameter ->
            bindCurrentOwnerParameterOrNull(typeMapper, physicalMethodOwner)
        is DotNetGenericOwnerSymbolicCarrierReference.Constructed -> {
            if (nullEncoding != DotNetGenericOwnerPhysicalNullEncoding.NULL_REFERENCE) return null
            type.bindCurrentOwnerConstructionOrNull(typeMapper, physicalMethodOwner)
        }
        is DotNetGenericOwnerSymbolicCarrierReference.Leaf,
        is DotNetGenericOwnerSymbolicCarrierReference.SzArray,
        -> null
    }
}

/** Binds an open MethodDef signature without re-mapping its logical Kotlin result type. */
private fun DotNetGenericOwnerSymbolicCarrierReference.bindDeclaredCarrierOrNull(
    typeMapper: DotNetIlTypeMapper,
    method: DotNetGenericOwnerPhysicalMethodDefReference,
    declaringType: DotNetIlClassInfo,
): DotNetIlValueType? {
    return when (this) {
        is DotNetGenericOwnerSymbolicCarrierReference.Leaf -> fixedLeafIlTypeOrNull()
        is DotNetGenericOwnerSymbolicCarrierReference.Parameter ->
            bindDeclaredParameterOrNull(method, declaringType)
        is DotNetGenericOwnerSymbolicCarrierReference.Constructed -> {
            val localDefinition = definition as?
                    DotNetGenericOwnerPhysicalTypeDefIdentity.Local ?: return null
            val classInfo = localDefinition.classInfoOrNull(typeMapper) ?: return null
            if (classInfo.typeParameterCount == 0 ||
                classInfo.typeParameterCount != arguments.size
            ) return null
            DotNetIlValueType.GenericInstance(
                classInfo,
                arguments.map { argument ->
                    argument.bindDeclaredCarrierOrNull(typeMapper, method, declaringType)
                        ?: return null
                },
            )
        }
        is DotNetGenericOwnerSymbolicCarrierReference.SzArray -> null
    }
}

private fun retainedDirectResultInitializerOrNull(
    plan: DotNetGenericOwnerPhysicalDirectResultInitializerPlan,
    produced: DotNetGenericOwnerProducedValueFact,
    authoritativeOperationsByCall: IdentityHashMap<
            IrCall,
            DotNetGenericOwnerPhysicalOperationRoute,
            >,
    currentMethod: DotNetGenericOwnerPhysicalMethodDefIdentity.Local?,
    declarations: DotNetGenericOwnerPhysicalDeclarationIndex?,
): RetainedDirectResultInitializer? {
    if (plan.hasSequentialPrefixes && !plan.hasSequentialReceiverAndInputPrefixPair) return null
    val carrier = (produced.layout as?
            DotNetGenericOwnerProducedValueLayout.Direct)?.carrier ?: return null
    val sites = plan.callsInEvaluationOrder.map { site ->
        val operation = authoritativeOperationsByCall[site.call] ?: return null
        val kind = plan.directResultCallKindOrNull(
            site,
            operation,
            currentMethod,
            declarations,
        ) ?: return null
        if (operation.instantiatedSignature.resultLayout !is
                DotNetGenericOwnerPhysicalCallableResultLayoutReference.Direct ||
            !operation.producedResult.matchesRetainedInitializerResult(
                produced,
                site.hasImplicitNotNull,
            )
        ) return null
        RetainedDirectResultCallSite(site.call, operation, kind)
    }
    return RetainedDirectResultInitializer(plan, carrier, sites)
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
                carrier = carrier,
                emitterValidation =
                    DotNetGenericOwnerPhysicalValueEmitterValidation.DIRECT_STORAGE_READ_CARRIER,
                externalImplementationOwner = implementationOwner,
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
            localPhysicalAuthority: DotNetLocalGenericOwnerPhysicalAuthority? = null,
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
            val pendingSequentialByFunction = IdentityHashMap<
                    IrFunctionSymbol,
                    IdentityHashMap<
                            IrValueSymbol,
                            DotNetGenericOwnerPhysicalValueRetainedProducedCarrier,
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
            val dependentsByFunction = IdentityHashMap<
                    IrFunctionSymbol,
                    IdentityHashMap<IrValueSymbol, MutableSet<IrValueSymbol>>,
                    >()
            val sequentialPrefixesByFunction = IdentityHashMap<
                    IrFunctionSymbol,
                    IdentityHashMap<IrValueSymbol, List<IrVariable>>,
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
                dependentsByFunction[function] = dependentsByValue
                entry.value.values.forEach { record ->
                    val initializer = (record.variable.owner as? IrVariable)?.initializer
                        ?: return@forEach
                    initializer.dotNetPhysicalDirectResultInitializerPlanOrNull()
                        ?.takeIf { plan -> plan.hasSequentialReceiverAndInputPrefixPair }
                        ?.let { plan ->
                            val prefixesByOuter = sequentialPrefixesByFunction
                                .getOrPut(function) { IdentityHashMap() }
                            prefixesByOuter[record.variable] =
                                plan.sequentialPrefixVariablesInEvaluationOrder
                        }
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
                                                localPhysicalAuthority
                                                    ?.currentMethodOrNull(record.physicalFunction),
                                                localPhysicalAuthority?.boundDeclarations,
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

            // Prefix locals retain ordinary independent placement authority. Their identities
            // may nevertheless authorize only one ordered outer obligation: shared/repeated
            // prefix identities and an outer reused as its own prefix therefore make every
            // affected outer unavailable without revoking the prefix locals themselves.
            val invalidSequentialOutersByFunction = IdentityHashMap<
                    IrFunctionSymbol,
                    MutableSet<IrValueSymbol>,
                    >()
            sequentialPrefixesByFunction.forEach { functionEntry ->
                val invalidOuters = Collections.newSetFromMap(
                    IdentityHashMap<IrValueSymbol, Boolean>(),
                )
                val ownerByPrefix = IdentityHashMap<IrValueSymbol, IrValueSymbol>()
                functionEntry.value.forEach { outerEntry ->
                    val outer = outerEntry.key
                    val seenForOuter = Collections.newSetFromMap(
                        IdentityHashMap<IrValueSymbol, Boolean>(),
                    )
                    outerEntry.value.forEach { prefix ->
                        val prefixSymbol = prefix.symbol
                        if (!seenForOuter.add(prefixSymbol) || prefixSymbol === outer) {
                            invalidOuters += outer
                        }
                        val previousOwner = ownerByPrefix[prefixSymbol]
                        if (previousOwner == null) {
                            ownerByPrefix[prefixSymbol] = outer
                        } else {
                            invalidOuters += previousOwner
                            invalidOuters += outer
                        }
                    }
                }
                if (invalidOuters.isNotEmpty()) {
                    invalidSequentialOutersByFunction[functionEntry.key] = invalidOuters
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
                    val initializer = (record.variable.owner as? IrVariable)?.initializer
                        ?: return@forEach
                    val directResultInitializerPlan =
                        initializer.dotNetPhysicalDirectResultInitializerPlanOrNull()
                    val directResultInitializer = directResultInitializerPlan?.let { plan ->
                        retainedDirectResultInitializerOrNull(
                            plan,
                            produced,
                            authoritativeOperationsByCallIdentity,
                            localPhysicalAuthority
                                ?.currentMethodOrNull(record.physicalFunction),
                            localPhysicalAuthority?.boundDeclarations,
                        )
                    }
                    val emitterValidation = when (val symbolic = direct.carrier.type) {
                        is DotNetGenericOwnerSymbolicCarrierReference.Parameter -> {
                            if (direct.carrier.nullEncoding !=
                                DotNetGenericOwnerPhysicalNullEncoding.SUBSTITUTION_DEPENDENT
                            ) return@forEach
                            when (val parameterBinder = symbolic.binder) {
                                is DotNetGenericOwnerPhysicalGenericBinderReference.Type -> {
                                    val binder = parameterBinder.definition as?
                                            DotNetGenericOwnerPhysicalTypeDefIdentity.Local
                                            ?: return@forEach
                                    if (binder.owner !== physicalOwner ||
                                        symbolic.index !in physicalOwner.owner.typeParameters.indices ||
                                        (initializer is IrWhen && directResultInitializerPlan == null)
                                    ) return@forEach
                                    if (directResultInitializerPlan != null) {
                                        DotNetGenericOwnerPhysicalValueEmitterValidation
                                            .DIRECT_CALL_RESULT_CARRIER
                                    } else {
                                        initializer.directPhysicalValueEmitterValidationOrNull()
                                            ?: return@forEach
                                    }
                                }
                                is DotNetGenericOwnerPhysicalGenericBinderReference.Method -> {
                                    val binder = parameterBinder.definition as?
                                            DotNetGenericOwnerPhysicalMethodDefIdentity.Local
                                            ?: return@forEach
                                    val current = localPhysicalAuthority
                                        ?.currentMethodOrNull(record.physicalFunction)
                                        ?: return@forEach
                                    val method = localPhysicalAuthority.boundDeclarations
                                        ?.methodDescriptionOrNull(current) ?: return@forEach
                                    if (!binder.sameLocalMethodIdentityAs(current) ||
                                        binder.function !== record.physicalFunction ||
                                        binder.role != DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY ||
                                        method.declaringType !=
                                            DotNetGenericOwnerPhysicalTypeDefIdentity.Local(
                                                physicalOwner,
                                                view = null,
                                            ) ||
                                        symbolic.index !in 0 until method.signature.genericArity ||
                                        initializer.directPhysicalValueEmitterValidationOrNull() !=
                                            DotNetGenericOwnerPhysicalValueEmitterValidation
                                                .DIRECT_STORAGE_READ_CARRIER ||
                                        directResultInitializerPlan != null
                                    ) return@forEach
                                    DotNetGenericOwnerPhysicalValueEmitterValidation
                                        .DIRECT_STORAGE_READ_CARRIER
                                }
                            }
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
                            if (directResultInitializerPlan != null) {
                                DotNetGenericOwnerPhysicalValueEmitterValidation
                                    .DIRECT_CALL_RESULT_CARRIER
                            } else if (initializer is IrWhen) {
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
                    if (directResultInitializerPlan != null) {
                        if (directResultInitializer == null) return@forEach
                    } else if (record.variable in
                        callBearingValuesByFunction[record.physicalFunction].orEmpty()
                    ) {
                        // Containers and control-flow expressions need a per-result-path route
                        // plan before they may retain a call-produced carrier. The value shadow
                        // can predict their natural result, but that is not evidence that every
                        // live call will bypass its semantic/capability emitter.
                        return@forEach
                    }
                    val retained = DotNetGenericOwnerPhysicalValueRetainedProducedCarrier(
                        carrier = direct.carrier,
                        emitterValidation = emitterValidation,
                        directResultInitializer = directResultInitializer,
                    )
                    val destination = if (retained.hasSequentialPrefixInitializer) {
                        pendingSequentialByFunction
                    } else {
                        retainedByFunction
                    }
                    destination.getOrPut(record.physicalFunction) {
                        IdentityHashMap()
                    }[record.variable] = retained
                }

            // Prefix locals retain their independently selected direct-storage authority. Only
            // the outer caller-MethodSpec result is withheld until both exact prefix carriers are
            // present and correlated. Failure invalidates the outer and values derived from it;
            // it cannot degrade unrelated receiver/marker storage which was already proved exact.
            sequentialPrefixesByFunction.forEach { functionEntry ->
                val function = functionEntry.key
                val retained = retainedByFunction.getOrPut(function) { IdentityHashMap() }
                val pending = pendingSequentialByFunction[function].orEmpty()
                val invalidOuters = invalidSequentialOutersByFunction[function].orEmpty()
                val committedOuters = Collections.newSetFromMap(
                    IdentityHashMap<IrValueSymbol, Boolean>(),
                )
                functionEntry.value.forEach { outer ->
                    val outerSymbol = outer.key
                    val candidate = pending[outerSymbol]
                        ?.takeIf { outerSymbol !in invalidOuters }
                    val bound = candidate?.bindSequentialPrefixesOrNull { prefix ->
                        retained[prefix.symbol]
                    }
                    if (bound != null) {
                        check(retained.put(outerSymbol, bound) == null) {
                            "Internal .NET backend error: an ordered result escaped its " +
                                    "pending placement obligation"
                        }
                        committedOuters += outerSymbol
                    }
                }

                val denied = Collections.newSetFromMap(
                    IdentityHashMap<IrValueSymbol, Boolean>(),
                )
                functionEntry.value.forEach { outer ->
                    if (outer.key !in committedOuters) {
                        denied += outer.key
                    }
                }
                val work = ArrayDeque(denied)
                val dependents = dependentsByFunction[function]
                while (work.isNotEmpty()) {
                    dependents?.get(work.removeFirst()).orEmpty().forEach { dependent ->
                        if (denied.add(dependent)) work.addLast(dependent)
                    }
                }
                val retainedSplit = retainedSplitByFunction[function]
                denied.forEach { symbol ->
                    retained.remove(symbol)
                    retainedSplit?.remove(symbol)
                }
                if (retained.isEmpty()) retainedByFunction.remove(function)
                if (retainedSplit?.isEmpty() == true) {
                    retainedSplitByFunction.remove(function)
                }
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

            retainedByFunction.entries.removeIf { entry -> entry.value.isEmpty() }

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

/** `!!n` is meaningful only inside the exact MethodDef which owns that GenericParam row. */
private fun DotNetGenericOwnerSymbolicCarrierReference.Parameter.bindCurrentMethodParameterOrNull(
    physicalMethodIdentity: DotNetGenericOwnerPhysicalMethodDefIdentity.Local?,
    physicalMethodGenericArity: Int,
): DotNetIlValueType.TypeParameter? {
    val binder = (binder as? DotNetGenericOwnerPhysicalGenericBinderReference.Method)
        ?.definition as? DotNetGenericOwnerPhysicalMethodDefIdentity.Local ?: return null
    if (physicalMethodIdentity == null ||
        !binder.sameLocalMethodIdentityAs(physicalMethodIdentity) ||
        index !in 0 until physicalMethodGenericArity
    ) return null
    return DotNetIlValueType.TypeParameter(index, isMethodParameter = true)
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

/** The first retained direct-result grammar has no MethodSpec or non-dispatch input slots. */
internal fun IrCall.isDotNetParameterlessDirectResultPlacementCall(): Boolean =
    dispatchReceiver != null && symbol.owner.typeParameters.isEmpty() && typeArguments.isEmpty() &&
            symbol.owner.parameters.count { parameter ->
                parameter.kind == IrParameterKind.DispatchReceiver
            } == 1 &&
            symbol.owner.parameters.all { parameter ->
                parameter.kind == IrParameterKind.DispatchReceiver
            }

/** One direct-result leaf admitted by the already-bound operation grammar. */
internal fun IrCall.isDotNetDirectResultPlacementCall(
    operation: DotNetGenericOwnerPhysicalOperationRoute,
    currentMethod: DotNetGenericOwnerPhysicalMethodDefIdentity.Local?,
    declarations: DotNetGenericOwnerPhysicalDeclarationIndex?,
): Boolean {
    if (superQualifierSymbol != null || dispatchReceiver == null) return false
    if (isDotNetParameterlessDirectResultPlacementCall()) {
        return operation.method.signature.genericArity == 0 &&
                operation.instantiatedSignature.genericArity == 0 &&
                operation.methodArguments.isEmpty()
    }
    if (!operation.isDirectCallerMethodParameterProducerOperation(
            currentMethod,
            declarations,
        )
    ) return false
    return typeArguments.size == 1 && arguments.size == 2 &&
            arguments.all { argument -> argument != null }
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
    currentMethod: DotNetGenericOwnerPhysicalMethodDefIdentity.Local?,
    declarations: DotNetGenericOwnerPhysicalDeclarationIndex?,
): Boolean {
    val plan = dotNetPhysicalDirectResultInitializerPlanOrNull() ?: return true
    if (plan.hasSequentialPrefixes && !plan.hasSequentialReceiverAndInputPrefixPair) return true
    return plan.callsInEvaluationOrder.any { site ->
        val operation = operations[site.call] ?: return@any true
        if (plan.directResultCallKindOrNull(
                site,
                operation,
                currentMethod,
                declarations,
            ) == null
        ) return@any true
        operation.instantiatedSignature.resultLayout !is
                DotNetGenericOwnerPhysicalCallableResultLayoutReference.Direct ||
                !operation.producedResult.matchesRetainedInitializerResult(
                    produced,
                    site.hasImplicitNotNull,
                )
    }
}

private fun DotNetGenericOwnerPhysicalDirectResultInitializerPlan.directResultCallKindOrNull(
    site: DotNetGenericOwnerPhysicalDirectResultCallSite,
    operation: DotNetGenericOwnerPhysicalOperationRoute,
    currentMethod: DotNetGenericOwnerPhysicalMethodDefIdentity.Local?,
    declarations: DotNetGenericOwnerPhysicalDeclarationIndex?,
): DotNetGenericOwnerPhysicalDirectResultCallKind? = when {
    site.call.isDotNetParameterlessDirectResultPlacementCall() &&
            operation.method.signature.genericArity == 0 &&
            operation.instantiatedSignature.genericArity == 0 &&
            operation.methodArguments.isEmpty() ->
        DotNetGenericOwnerPhysicalDirectResultCallKind.PARAMETERLESS
    hasSequentialReceiverAndInputPrefixPair &&
            callsInEvaluationOrder.singleOrNull()?.call === site.call &&
            site.call.isDotNetDirectResultPlacementCall(
                operation,
                currentMethod,
                declarations,
            ) -> DotNetGenericOwnerPhysicalDirectResultCallKind.CURRENT_CALLER_METHOD_PARAMETER
    else -> null
}

private data class DotNetPhysicalValueDependencies(
    val reads: Set<IrValueSymbol>,
    val hasCall: Boolean,
)

private fun IrExpression.dotNetPhysicalValueDependencies(): DotNetPhysicalValueDependencies {
    val reads = Collections.newSetFromMap(IdentityHashMap<IrValueSymbol, Boolean>())
    var hasCall = false
    val visitor = object : IrVisitorVoid() {
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
    }
    val directResultPlan = dotNetPhysicalDirectResultInitializerPlanOrNull()
    if (directResultPlan == null) {
        acceptVoid(visitor)
    } else {
        // Result conditions choose a path but do not supply its value. Receiver and argument
        // reads still participate because the exact final operation and its late resolver depend
        // on their live slots. Start at each result call's children to keep those route
        // dependencies without making an incidental condition read a carrier dependency.
        hasCall = true
        directResultPlan.callsInEvaluationOrder.forEach { site ->
            site.call.acceptChildrenVoid(visitor)
        }
    }
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
