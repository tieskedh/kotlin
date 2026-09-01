/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import java.util.IdentityHashMap

/**
 * One operation-scoped permission to retain the carrier which an initializer already produces.
 *
 * The destination is not reconstructed from its logical Kotlin type. The token exists only when
 * final value flow independently selected exactly the same direct, constructed, reference-shaped
 * carrier for production and storage. It therefore authorizes no cast, variance conversion,
 * semantic adaptation, boxing, nullable materialization, field/state choice, or ABI change.
 */
internal class DotNetGenericOwnerPhysicalValueRetainedProducedCarrier internal constructor(
    val carrier: DotNetGenericOwnerPhysicalCarrier,
) {
    /**
     * Joins symbolic authority with the live emitter mapping without trusting either alone.
     *
     * The physical MethodDef owner authenticates every `!n` binder. The initializer must then
     * report exactly the independently reconstructed verifier-visible construction; a changed or
     * evicted mapping fails closed instead of silently selecting another local carrier.
     */
    fun bindEmitterCarrierOrNull(
        typeMapper: DotNetIlTypeMapper,
        physicalMethodOwner: DotNetIlClassInfo,
        initializerCarrier: DotNetIlValueType?,
    ): DotNetIlValueType? {
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
            val parameter = argument as?
                    DotNetGenericOwnerSymbolicCarrierReference.Parameter ?: return null
            val binder = (parameter.binder as?
                    DotNetGenericOwnerPhysicalGenericBinderReference.Type)
                ?.definition as? DotNetGenericOwnerPhysicalTypeDefIdentity.Local ?: return null
            if (binder.classInfoOrNull(typeMapper) !== physicalMethodOwner ||
                parameter.index !in 0 until physicalMethodOwner.typeParameterCount
            ) return null
            DotNetIlValueType.TypeParameter(parameter.index, isMethodParameter = false)
        }
        val expected = DotNetIlValueType.GenericInstance(classInfo, arguments)
        return initializerCarrier?.takeIf { actual ->
            actual == expected && actual.isDotNetReferenceShaped()
        }
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
                    val construction = direct.carrier.type as?
                            DotNetGenericOwnerSymbolicCarrierReference.Constructed
                        ?: return@forEach
                    if (construction.definition !is DotNetGenericOwnerPhysicalTypeDefIdentity.Local) {
                        return@forEach
                    }
                    val physicalOwner = (record.physicalFunction.owner.parent as? IrClass)?.symbol
                        ?: return@forEach
                    if (construction.arguments.any { argument ->
                            val parameter = argument as?
                                    DotNetGenericOwnerSymbolicCarrierReference.Parameter
                                ?: return@any true
                            val binder = (parameter.binder as?
                                    DotNetGenericOwnerPhysicalGenericBinderReference.Type)
                                ?.definition as? DotNetGenericOwnerPhysicalTypeDefIdentity.Local
                                ?: return@any true
                            binder.owner !== physicalOwner ||
                                    parameter.index !in physicalOwner.owner.typeParameters.indices
                        }
                    ) {
                        return@forEach
                    }
                    if (direct.carrier != storage.storageCarrier.carrier ||
                        direct.carrier.nullEncoding !=
                        DotNetGenericOwnerPhysicalNullEncoding.NULL_REFERENCE
                    ) {
                        return@forEach
                    }
                    retainedByFunction.getOrPut(record.physicalFunction) {
                        IdentityHashMap()
                    }[record.variable] = DotNetGenericOwnerPhysicalValueRetainedProducedCarrier(
                        direct.carrier,
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
