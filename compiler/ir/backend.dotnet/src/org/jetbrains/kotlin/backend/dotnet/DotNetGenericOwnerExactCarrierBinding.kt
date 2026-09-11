/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.isArray
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.types.isNullableArray
import org.jetbrains.kotlin.types.Variance

/** Logical invariant-array shape only; its element still needs independent physical authority. */
internal fun IrType.genericOwnerInvariantArrayElementOrNull(): IrType? {
    if (!isArray() && !isNullableArray()) return null
    val projection = (this as? IrSimpleType)?.arguments?.singleOrNull() as? IrTypeProjection ?: return null
    return projection.type.takeIf { projection.variance == Variance.INVARIANT }
}

/** Output projection selects System.Array; it never proves an exact element vector. */
internal fun IrType.genericOwnerOutputProjectedArrayElementOrNull(): IrType? {
    if (!isArray() && !isNullableArray()) return null
    val projection = (this as? IrSimpleType)?.arguments?.singleOrNull() as? IrTypeProjection ?: return null
    return projection.type.takeIf { projection.variance == Variance.OUT_VARIANCE }
}

internal fun genericOwnerSystemArrayIdentity() =
    DotNetGenericOwnerPhysicalTypeDefIdentity.CoreLibrary(listOf("System", "Array"))

/**
 * One exact carrier selected recursively from a frozen local declaration index.
 *
 * [view] exists only when the outermost carrier is a constructed class/interface view. A caller
 * may use it to select an already-proven view, but it is never an independent source of TypeDef
 * authority.
 */
internal data class DotNetGenericOwnerExactCarrierBinding(
    val carrier: DotNetGenericOwnerPhysicalCarrier,
    val view: DotNetGenericOwnerPhysicalView?,
)

/**
 * Binds an owner-dependent carrier without consulting the general IL type mapper.
 *
 * The current owner's parameters come only from [physicalOwnerIdentity]. Every local construction
 * must be selected by [localDefinitionOrNull] and already exist in [declarations]. A native
 * invariant SZ array recursively binds its element carrier, not a nominal Array TypeDef. An
 * output-projected array instead binds its recorded fixed System.Array, with no element view.
 * Projections and stars remain unavailable for physical generic constructions; a selected
 * canonical class has no physical arguments to bind. Nullable owner parameters/value carriers, foreign
 * constructions, and unresolved classifiers remain unavailable. CLR-reference nullability does
 * not change a fixed leaf or constructed carrier; a declaration-index contradiction is a conflict.
 */
internal fun bindExactLocalGenericOwnerDependentCarrierOrError(
    type: IrType,
    physicalOwner: IrClass,
    physicalOwnerIdentity: DotNetGenericOwnerPhysicalTypeDefIdentity.Local,
    declarations: DotNetGenericOwnerPhysicalDeclarationIndex,
    localDefinitionOrNull: (IrClassSymbol) -> DotNetGenericOwnerPhysicalTypeDefIdentity.Local?,
): DotNetGenericOwnerPhysicalBindingResult<DotNetGenericOwnerExactCarrierBinding> {
    if (physicalOwnerIdentity.owner !== physicalOwner.symbol || physicalOwnerIdentity.view != null) {
        return DotNetGenericOwnerPhysicalBindingResult.Conflict(
            "an exact owner-dependent carrier received an unrelated physical owner identity",
        )
    }
    val simple = type as? IrSimpleType
        ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable

    if (type.genericOwnerOutputProjectedArrayElementOrNull() != null) {
        val definition = declarations.typeDescriptionOrNull(genericOwnerSystemArrayIdentity())
            ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
        if (definition.genericParameters.isNotEmpty() ||
            definition.category != DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS
        ) {
            return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                "the fixed System.Array carrier contradicts its physical TypeDef shape",
            )
        }
        val construction = when (val binding = declarations.constructTypeOrError(
            genericOwnerSystemArrayIdentity(), emptyList(),
        )) {
            is DotNetGenericOwnerPhysicalBindingResult.Bound -> binding.value
            is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return binding
            DotNetGenericOwnerPhysicalBindingResult.Unavailable -> return DotNetGenericOwnerPhysicalBindingResult.Unavailable
        }
        return when (val binding = declarations.carrierOrError(construction)) {
            is DotNetGenericOwnerPhysicalBindingResult.Bound -> DotNetGenericOwnerPhysicalBindingResult.Bound(
                DotNetGenericOwnerExactCarrierBinding(binding.value, view = null),
            )
            is DotNetGenericOwnerPhysicalBindingResult.Conflict -> binding
            DotNetGenericOwnerPhysicalBindingResult.Unavailable -> DotNetGenericOwnerPhysicalBindingResult.Unavailable
        }
    }

    type.genericOwnerDeclarationIndependentLeafPrototypeOrNull()
        ?.declarationIndependentLeafCarrierOrNull()
        ?.let { leaf ->
            return when (val binding = declarations.carrierOrError(leaf)) {
                is DotNetGenericOwnerPhysicalBindingResult.Bound ->
                    DotNetGenericOwnerPhysicalBindingResult.Bound(
                        DotNetGenericOwnerExactCarrierBinding(binding.value, view = null),
                    )
                is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                    DotNetGenericOwnerPhysicalBindingResult.Conflict(binding.reason)
                DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                    DotNetGenericOwnerPhysicalBindingResult.Unavailable
            }
        }

    val parameter = simple.classifier as? IrTypeParameterSymbol
    if (parameter != null) {
        if (simple.isMarkedNullable() || simple.arguments.isNotEmpty()) {
            return DotNetGenericOwnerPhysicalBindingResult.Unavailable
        }
        val parameterIndex = physicalOwner.typeParameters.indexOfFirst { candidate ->
            candidate.symbol === parameter && type == candidate.defaultType
        }.takeIf { index -> index >= 0 }
            ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
        val reference = when (val binding = declarations.typeParameterOrError(
            physicalOwnerIdentity,
            parameterIndex,
        )) {
            is DotNetGenericOwnerPhysicalBindingResult.Bound -> binding.value
            is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                return DotNetGenericOwnerPhysicalBindingResult.Conflict(binding.reason)
            DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                return DotNetGenericOwnerPhysicalBindingResult.Unavailable
        }
        return when (val binding = declarations.carrierOrError(reference)) {
            is DotNetGenericOwnerPhysicalBindingResult.Bound ->
                DotNetGenericOwnerPhysicalBindingResult.Bound(
                    DotNetGenericOwnerExactCarrierBinding(binding.value, view = null),
                )
            is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                DotNetGenericOwnerPhysicalBindingResult.Conflict(binding.reason)
            DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                DotNetGenericOwnerPhysicalBindingResult.Unavailable
        }
    }

    val classifier = (simple.classifier as? IrClassSymbol)?.owner
        ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
    type.genericOwnerInvariantArrayElementOrNull()?.let { elementType ->
        val element = when (val binding = bindExactLocalGenericOwnerDependentCarrierOrError(
            elementType,
            physicalOwner,
            physicalOwnerIdentity,
            declarations,
            localDefinitionOrNull,
        )) {
            is DotNetGenericOwnerPhysicalBindingResult.Bound -> binding.value.carrier.type
            is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                return DotNetGenericOwnerPhysicalBindingResult.Conflict(binding.reason)
            DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                return DotNetGenericOwnerPhysicalBindingResult.Unavailable
        }
        return when (val binding = declarations.carrierOrError(
            DotNetGenericOwnerSymbolicCarrierReference.SzArray(element),
        )) {
            is DotNetGenericOwnerPhysicalBindingResult.Bound ->
                DotNetGenericOwnerPhysicalBindingResult.Bound(
                    DotNetGenericOwnerExactCarrierBinding(binding.value, view = null),
                )
            is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                DotNetGenericOwnerPhysicalBindingResult.Conflict(binding.reason)
            DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                DotNetGenericOwnerPhysicalBindingResult.Unavailable
        }
    }
    // A logical value-class construction does not identify its verifier-visible carrier. Its
    // use site may contain the wrapper, its unboxed payload, or a nullable payload convention;
    // only dedicated value-class physical authority may choose between those representations.
    if (classifier.isValue) {
        return DotNetGenericOwnerPhysicalBindingResult.Unavailable
    }
    if (simple.arguments.isEmpty() || simple.arguments.size != classifier.typeParameters.size) {
        return DotNetGenericOwnerPhysicalBindingResult.Unavailable
    }
    val identity = localDefinitionOrNull(classifier.symbol)
        ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
    if (identity.owner !== classifier.symbol) {
        return DotNetGenericOwnerPhysicalBindingResult.Conflict(
            "an exact local carrier selector returned an unrelated physical TypeDef",
        )
    }
    val definition = declarations.typeDescriptionOrNull(identity)
        ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
    if (definition.category !in setOf(
            DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS,
            DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE,
        )) {
        return DotNetGenericOwnerPhysicalBindingResult.Unavailable
    }
    val arguments = mutableListOf<DotNetGenericOwnerSymbolicCarrierReference>()
    // Only the selected physical declaration can discard logical arguments. A frozen
    // canonical class has no GenericParams: all its Kotlin projections denote that same
    // nominal reference. A generic class/interface still requires the complete invariant
    // vector below; neither the source type nor this binder may invent C<object>.
    val retainsLogicalArguments = definition.category != DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS ||
            definition.genericParameters.isNotEmpty()
    for (argument in if (retainsLogicalArguments) simple.arguments else emptyList()) {
        val projection = argument as? IrTypeProjection
            ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
        if (projection.variance != Variance.INVARIANT) {
            return DotNetGenericOwnerPhysicalBindingResult.Unavailable
        }
        when (val binding = bindExactLocalGenericOwnerDependentCarrierOrError(
            projection.type,
            physicalOwner,
            physicalOwnerIdentity,
            declarations,
            localDefinitionOrNull,
        )) {
            is DotNetGenericOwnerPhysicalBindingResult.Bound -> arguments += binding.value.carrier.type
            is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                return DotNetGenericOwnerPhysicalBindingResult.Conflict(binding.reason)
            DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                return DotNetGenericOwnerPhysicalBindingResult.Unavailable
        }
    }
    val construction = when (val binding = declarations.constructTypeOrError(identity, arguments)) {
        is DotNetGenericOwnerPhysicalBindingResult.Bound -> binding.value
        is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
            return DotNetGenericOwnerPhysicalBindingResult.Conflict(binding.reason)
        DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
            return DotNetGenericOwnerPhysicalBindingResult.Unavailable
    }
    return when (val binding = declarations.carrierOrError(construction)) {
        is DotNetGenericOwnerPhysicalBindingResult.Bound ->
            DotNetGenericOwnerPhysicalBindingResult.Bound(
                DotNetGenericOwnerExactCarrierBinding(
                    binding.value,
                    DotNetGenericOwnerPhysicalView(construction),
                ),
            )
        is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
            DotNetGenericOwnerPhysicalBindingResult.Conflict(binding.reason)
        DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
            DotNetGenericOwnerPhysicalBindingResult.Unavailable
    }
}
