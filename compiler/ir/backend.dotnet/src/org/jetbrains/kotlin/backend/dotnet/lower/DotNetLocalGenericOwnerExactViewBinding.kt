/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet.lower

import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalBindingResult
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerMemberFamilyRole
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalTypeDefIdentity
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalView
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerSymbolicCarrierReference
import org.jetbrains.kotlin.backend.dotnet.DotNetLocalGenericOwnerPhysicalAuthority
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.isAny
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.types.isNullableAny
import org.jetbrains.kotlin.types.Variance

/** Binds one exact non-null type use to an already recorded current-owner CLR parameter. */
internal fun bindExactLocalGenericOwnerParameterCarrierOrError(
    type: IrType,
    physicalOwner: IrClass,
    authority: DotNetLocalGenericOwnerPhysicalAuthority,
): DotNetGenericOwnerPhysicalBindingResult<DotNetGenericOwnerSymbolicCarrierReference> {
    val declarations = authority.boundDeclarations
        ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
    val physicalOwnerIdentity = authority.genericClassIdentityOrNull(physicalOwner.symbol)
        ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
    val simple = type as? IrSimpleType
        ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
    if (simple.isMarkedNullable() || simple.arguments.isNotEmpty()) {
        return DotNetGenericOwnerPhysicalBindingResult.Unavailable
    }
    val parameter = simple.classifier as? IrTypeParameterSymbol
        ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
    val parameterIndex = physicalOwner.typeParameters.indexOfFirst { candidate ->
        candidate.symbol === parameter
    }.takeIf { index -> index >= 0 }
        ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
    return declarations.typeParameterOrError(physicalOwnerIdentity, parameterIndex)
}

/**
 * Binds one bare type use to a parameter of the exact BOUND current MethodDef.
 *
 * The logical classifier only locates the parameter. The current-function catalogue supplies the
 * MethodDef identity and its GenericParam row; an equal index from the owner or a sibling method
 * is unrelated and cannot create a `!!n` carrier.
 */
internal fun bindExactLocalCurrentMethodParameterCarrierOrError(
    type: IrType,
    physicalFunction: IrSimpleFunction,
    authority: DotNetLocalGenericOwnerPhysicalAuthority,
): DotNetGenericOwnerPhysicalBindingResult<DotNetGenericOwnerSymbolicCarrierReference> {
    val declarations = authority.boundDeclarations
        ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
    val currentMethod = authority.currentMethodOrNull(physicalFunction.symbol)
        ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
    if (currentMethod.function !== physicalFunction.symbol ||
        currentMethod.role != DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY
    ) return DotNetGenericOwnerPhysicalBindingResult.Unavailable
    val description = declarations.methodDescriptionOrNull(currentMethod)
        ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
    val simple = type as? IrSimpleType
        ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
    if (simple.isMarkedNullable() || simple.arguments.isNotEmpty()) {
        return DotNetGenericOwnerPhysicalBindingResult.Unavailable
    }
    val parameter = simple.classifier as? IrTypeParameterSymbol
        ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
    val parameterIndex = physicalFunction.typeParameters.indexOfFirst { candidate ->
        candidate.symbol === parameter && type == candidate.defaultType
    }.takeIf { index -> index >= 0 }
        ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
    if (description.signature.genericArity != physicalFunction.typeParameters.size ||
        description.genericParameters.size != physicalFunction.typeParameters.size
    ) {
        return DotNetGenericOwnerPhysicalBindingResult.Conflict(
            "BOUND current MethodDef generic arity disagrees with its final IR function",
        )
    }
    return declarations.methodParameterOrError(currentMethod, parameterIndex)
}

/**
 * Binds an exact logical interface application only through already selected local CLR authority.
 * Logical type equality locates owner parameters; it never proves a TypeDef or InterfaceImpl row.
 */
internal fun bindExactLocalGenericOwnerNaturalViewOrError(
    type: IrType,
    physicalOwner: IrClass,
    authority: DotNetLocalGenericOwnerPhysicalAuthority,
): DotNetGenericOwnerPhysicalBindingResult<DotNetGenericOwnerPhysicalView> =
    bindExactLocalGenericOwnerViewOrError(type, physicalOwner, authority) { targetOwner ->
        authority.naturalInterfaceIdentityOrNull(targetOwner.symbol)
    }

/** Exact local class or natural-interface construction selected by existing TypeDef authority. */
internal fun bindExactLocalGenericOwnerConstructedViewOrError(
    type: IrType,
    physicalOwner: IrClass,
    authority: DotNetLocalGenericOwnerPhysicalAuthority,
): DotNetGenericOwnerPhysicalBindingResult<DotNetGenericOwnerPhysicalView> =
    bindExactLocalGenericOwnerViewOrError(type, physicalOwner, authority) { targetOwner ->
        authority.genericClassIdentityOrNull(targetOwner.symbol)
            ?: authority.naturalInterfaceIdentityOrNull(targetOwner.symbol)
    }

private inline fun bindExactLocalGenericOwnerViewOrError(
    type: IrType,
    physicalOwner: IrClass,
    authority: DotNetLocalGenericOwnerPhysicalAuthority,
    selectTargetIdentity: (IrClass) ->
        DotNetGenericOwnerPhysicalTypeDefIdentity.Local?,
): DotNetGenericOwnerPhysicalBindingResult<DotNetGenericOwnerPhysicalView> {
    val declarations = authority.boundDeclarations
        ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
    val physicalOwnerIdentity = authority.genericClassIdentityOrNull(physicalOwner.symbol)
        ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
    val simple = type as? IrSimpleType
        ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
    val targetOwner = (simple.classifier as? IrClassSymbol)?.owner
        ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
    val targetIdentity = selectTargetIdentity(targetOwner)
        ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
    if (simple.arguments.size != targetOwner.typeParameters.size) {
        return DotNetGenericOwnerPhysicalBindingResult.Unavailable
    }

    val arguments = mutableListOf<DotNetGenericOwnerSymbolicCarrierReference>()
    for (argument in simple.arguments) {
        val projection = argument as? IrTypeProjection
            ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
        if (projection.variance != Variance.INVARIANT || projection.type.isMarkedNullable()) {
            return DotNetGenericOwnerPhysicalBindingResult.Unavailable
        }
        val parameter = (projection.type as? IrSimpleType)?.classifier as? IrTypeParameterSymbol
            ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
        val parameterIndex = physicalOwner.typeParameters.indexOfFirst { candidate ->
            candidate.symbol == parameter && projection.type == candidate.defaultType &&
                    candidate.superTypes.all { bound -> bound.isAny() || bound.isNullableAny() }
        }.takeIf { index -> index >= 0 }
            ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
        when (val carrier = declarations.typeParameterOrError(
            physicalOwnerIdentity,
            parameterIndex,
        )) {
            is DotNetGenericOwnerPhysicalBindingResult.Bound -> arguments += carrier.value
            is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                return DotNetGenericOwnerPhysicalBindingResult.Conflict(carrier.reason)
            DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                return DotNetGenericOwnerPhysicalBindingResult.Unavailable
        }
    }
    return when (val construction = declarations.constructTypeOrError(targetIdentity, arguments)) {
        is DotNetGenericOwnerPhysicalBindingResult.Bound ->
            DotNetGenericOwnerPhysicalBindingResult.Bound(
                DotNetGenericOwnerPhysicalView(construction.value),
            )
        is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
            DotNetGenericOwnerPhysicalBindingResult.Conflict(construction.reason)
        DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
            DotNetGenericOwnerPhysicalBindingResult.Unavailable
    }
}
