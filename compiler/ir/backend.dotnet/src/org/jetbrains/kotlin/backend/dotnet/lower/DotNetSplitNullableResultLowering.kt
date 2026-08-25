/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet.lower

import org.jetbrains.kotlin.backend.common.ModuleLoweringPass
import org.jetbrains.kotlin.backend.dotnet.DotNetBackendContext
import org.jetbrains.kotlin.backend.dotnet.DotNetPublishedGenericInterfaceMemberRole
import org.jetbrains.kotlin.backend.dotnet.dotNetGenericOwnerRehearsal
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.types.AbstractIrTypeSubstitutor
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.defaultType as typeParameterDefaultType
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.util.allOverridden
import org.jetbrains.kotlin.ir.util.defaultType as classDefaultType
import org.jetbrains.kotlin.ir.util.isFakeOverride
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid

/**
 * Selects the natural members of a producer-published open-nullable interface family.
 *
 * Kotlin IR and KLIB keep the logical `T?` signature. The IL mapper consumes only this identity
 * set and renders the natural CLR slot as `T (..., out bool isNull)`. Semantic capability slots
 * deliberately do not override the logical interface member and therefore remain object-returning.
 * Selection follows the recorded member role through [allOverridden], so local implementations
 * and separately compiled interface roots use the identical producer-selected convention.
 */
internal class DotNetSplitNullableResultLowering(
    private val context: DotNetBackendContext,
) : ModuleLoweringPass {
    private val externalDeclarations = context.externalDeclarationsForLowering()

    override fun lower(irModule: IrModuleFragment) {
        if (!context.configuration.dotNetGenericOwnerRehearsal) return

        val functions = mutableListOf<IrSimpleFunction>()
        irModule.acceptVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitClass(declaration: IrClass) {
                declaration.declarations.filterIsInstanceTo(functions)
                declaration.acceptChildrenVoid(this)
            }
        })

        functions.asSequence()
            .filterNot(IrSimpleFunction::isFakeOverride)
            .filter { function -> function.returnType.isMarkedNullable() }
            .forEach { function ->
                val family = sequenceOf(function) + function.allOverridden()
                val roots = family.filter { candidate ->
                    candidate.hasSplitNullableProducerRole()
                }.toList()
                if (roots.isNotEmpty()) {
                    val payloadTypes = roots.map { root ->
                        root.splitPayloadTypeAt(function)
                    }.distinct()
                    check(payloadTypes.size == 1) {
                        "Internal .NET backend error: split-nullable override '${function.name}' " +
                                "reaches incompatible producer payloads"
                    }
                    context.splitNullableResultPayloadTypes[function] = payloadTypes.single()
                    // A separate consumer must render MethodRefs from the producer declaration
                    // with the same hidden parameter as its local MethodDefs. Retain those
                    // external roots in the identity set as well as the local implementation.
                    roots.forEach { root ->
                        context.splitNullableResultPayloadTypes.putIfAbsent(
                            root,
                            root.producerSplitPayloadType(),
                        )
                    }
                }
            }
    }

    private fun IrSimpleFunction.splitPayloadTypeAt(implementation: IrSimpleFunction): IrType {
        val payloadType = producerSplitPayloadType()
        val producerOwner = parent as? IrClass ?: return payloadType
        val implementationOwner = implementation.parent as? IrClass ?: return payloadType
        if (producerOwner == implementationOwner) return payloadType
        val substitutor = AbstractIrTypeSubstitutor.forSuperClass(
            producerOwner.symbol,
            implementationOwner.classDefaultType,
        ) ?: error(
            "Internal .NET backend error: split-nullable implementation '${implementation.name}' " +
                    "is not a subtype of producer '${producerOwner.name}'"
        )
        return substitutor.substitute(payloadType)
    }

    private fun IrSimpleFunction.producerSplitPayloadType(): IrType {
        val parameter = ((returnType as? IrSimpleType)?.classifier as? IrTypeParameterSymbol)?.owner
            ?: error(
                "Internal .NET backend error: split-nullable producer '${name}' does not return an owner parameter"
            )
        return parameter.typeParameterDefaultType
    }

    private fun IrSimpleFunction.hasSplitNullableProducerRole(): Boolean {
        val owner = parent as? IrClass ?: return false
        val contract = context.publishedGenericInterfaceFamilies[owner]
            ?: externalDeclarations.publishedGenericInterfaceFamilyOrNull(owner)
            ?: return false
        val logicalMemberKey = context.preLoweringDeclarationKeys[this]
            ?: externalDeclarations.genericOwnerMemberFamilyOrNull(this)?.family?.logicalMemberKey
            ?: return false
        return contract.declaredMembers.singleOrNull { member ->
            member.logicalMemberKey == logicalMemberKey
        }?.role == DotNetPublishedGenericInterfaceMemberRole.SPLIT_NULLABLE_PRODUCER
    }
}
