/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.expressions.IrWhen
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
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

    private fun DotNetGenericOwnerPhysicalTypeDefIdentity.Local.classInfoOrNull(
        typeMapper: DotNetIlTypeMapper,
    ): DotNetIlClassInfo? = if (view == null) {
        typeMapper.classInfoOrNull(owner.owner)
    } else {
        typeMapper.genericInterfaceInfoOrNull(owner.owner)?.classInfo(view)
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
) {
    fun retainedProducedCarrierOrNull(
        function: IrFunctionSymbol,
        variable: IrValueSymbol,
    ): DotNetGenericOwnerPhysicalValueRetainedProducedCarrier? =
        retainedProducedCarriersByFunction[function]?.get(variable)

    companion object {
        fun bind(
            records: List<DotNetGenericOwnerPhysicalValueShadowRecord>,
        ): DotNetGenericOwnerPhysicalBindingResult<DotNetGenericOwnerPhysicalValueLocalPlacementAuthority> {
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
                    val direct = produced.layout as? DotNetGenericOwnerProducedValueLayout.Direct
                        ?: return@forEach
                    val physicalOwner = (record.physicalFunction.owner.parent as? IrClass)?.symbol
                        ?: return@forEach
                    if (direct.carrier != storage.storageCarrier.carrier) {
                        return@forEach
                    }
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

            if (retainedByFunction.isEmpty()) {
                return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            }
            return DotNetGenericOwnerPhysicalBindingResult.Bound(
                DotNetGenericOwnerPhysicalValueLocalPlacementAuthority(retainedByFunction),
            )
        }
    }
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
