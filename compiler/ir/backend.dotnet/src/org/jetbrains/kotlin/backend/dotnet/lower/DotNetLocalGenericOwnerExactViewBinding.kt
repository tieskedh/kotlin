/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet.lower

import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalBindingResult
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalView
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerSymbolicCarrierReference
import org.jetbrains.kotlin.backend.dotnet.DotNetLocalGenericOwnerPhysicalAuthority
import org.jetbrains.kotlin.ir.declarations.IrClass
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

/**
 * Binds an exact logical interface application only through already selected local CLR authority.
 * Logical type equality locates owner parameters; it never proves a TypeDef or InterfaceImpl row.
 */
internal fun bindExactLocalGenericOwnerNaturalViewOrError(
    type: IrType,
    physicalOwner: IrClass,
    authority: DotNetLocalGenericOwnerPhysicalAuthority,
): DotNetGenericOwnerPhysicalBindingResult<DotNetGenericOwnerPhysicalView> {
    val declarations = authority.boundDeclarations
        ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
    val physicalOwnerIdentity = authority.genericClassIdentityOrNull(physicalOwner.symbol)
        ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
    val simple = type as? IrSimpleType
        ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
    val targetOwner = (simple.classifier as? IrClassSymbol)?.owner
        ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
    val targetIdentity = authority.naturalInterfaceIdentityOrNull(targetOwner.symbol)
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
