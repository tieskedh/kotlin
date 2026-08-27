/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.types.Variance

/** A verifier-visible destination whose physical carrier has already been selected. */
internal data class DotNetIlFixedPhysicalBoundary(
    val description: String,
)

/**
 * A soundness failure, not an unsupported prototype feature. Ordinary declaration eviction
 * would otherwise leave a successfully produced assembly with a silently missing API.
 */
internal class DotNetIlInvalidPhysicalBoundaryException(
    val reason: String,
) : RuntimeException(reason)

/**
 * Rejects a definite closed retained-CLR variance mismatch at a selected physical destination.
 *
 * Logical FIR/IR subtyping is deliberately not consulted here. It cannot prove a CLR carrier,
 * and this query runs only after normal physical assignability and explicit coercions failed.
 * Open argument trees, invariant differences, ambiguous owner views, and unrelated conversions
 * stay on the ordinary unsupported path until producer-recorded physical facts can classify them.
 */
internal fun DotNetIlTypeMapper.requireValidRetainedForeignClrBoundary(
    produced: DotNetIlValueType,
    required: DotNetIlValueType,
    boundary: DotNetIlFixedPhysicalBoundary,
) {
    if (produced.isDotNetAssignableTo(required)) return

    val requiredConstruction = required as? DotNetIlValueType.GenericInstance ?: return
    if (!isRetainedForeignClrClassInfo(requiredConstruction.classInfo)) return

    val guaranteedOwnerViews = produced.retainedViewsOf(requiredConstruction.classInfo)
        .distinctBy { view -> view.nameInSignature }
        .toList()
    if (guaranteedOwnerViews.isEmpty() ||
        guaranteedOwnerViews.any { view -> view.isDotNetAssignableTo(required) }
    ) return
    if (guaranteedOwnerViews.any { view ->
            !view.hasProvenClosedValueVarianceViolationFrom(requiredConstruction)
        }
    ) return

    val producedViews = guaranteedOwnerViews.joinToString { view -> view.nameInSignature }
    throw DotNetIlInvalidPhysicalBoundaryException(
        "Invalid CLR physical conversion at ${boundary.description}: " +
                "${produced.nameInSignature} (retained view${if (guaranteedOwnerViews.size == 1) "" else "s"} " +
                "$producedViews) cannot satisfy ${required.nameInSignature}. " +
                "The selected CLR constructions contain a closed value-type variance step, " +
                "but CLR variance applies only to physically compatible reference arguments."
    )
}

private fun DotNetIlValueType.retainedViewsOf(
    owner: DotNetIlClassInfo,
): Sequence<DotNetIlValueType.GenericInstance> {
    val roots = when (this) {
        is DotNetIlValueType.TypeParameter -> upperBounds.asSequence()
        else -> sequenceOf(this)
    }
    return roots.flatMap { root -> sequenceOf(root) + root.dotNetAllSupertypes() }
        .filterIsInstance<DotNetIlValueType.GenericInstance>()
        .filter { view -> view.classInfo === owner }
}

/**
 * Proves only the CLR rule this gate owns: a declaration-site variance conversion crosses a
 * closed value-type argument. A missing reference/reference relation is deliberately unknown;
 * [isDotNetAssignableTo] is sound but not a complete model of every BCL reference hierarchy.
 */
private fun DotNetIlValueType.GenericInstance.hasProvenClosedValueVarianceViolationFrom(
    required: DotNetIlValueType.GenericInstance,
): Boolean {
    if (classInfo !== required.classInfo || arguments.size != required.arguments.size) return false
    if (classInfo.typeParameterVariances.size != arguments.size) return false

    return arguments.indices.any { index ->
        val actualArgument = arguments[index]
        val requiredArgument = required.arguments[index]
        if (actualArgument == requiredArgument) return@any false
        when (classInfo.typeParameterVariances[index]) {
            Variance.OUT_VARIANCE ->
                actualArgument.hasProvenClosedValueVarianceViolationFrom(requiredArgument)
            Variance.IN_VARIANCE ->
                requiredArgument.hasProvenClosedValueVarianceViolationFrom(actualArgument)
            Variance.INVARIANT -> false
        }
    }
}

private fun DotNetIlValueType.hasProvenClosedValueVarianceViolationFrom(
    required: DotNetIlValueType,
): Boolean {
    if (this == required || !isClosedPhysicalType() || !required.isClosedPhysicalType()) return false
    if (isKnownClrValueType() || required.isKnownClrValueType()) return true
    if (!isDotNetReferenceShaped() || !required.isDotNetReferenceShaped()) return false
    if (isDotNetAssignableTo(required)) return false

    return when {
        this is DotNetIlValueType.GenericInstance && required is DotNetIlValueType.GenericInstance ->
            hasProvenClosedValueVarianceViolationFrom(required)
        this is DotNetIlValueType.GenericArray && required is DotNetIlValueType.GenericArray ->
            elementType.hasProvenClosedValueVarianceViolationFrom(required.elementType)
        else -> false
    }
}

private fun DotNetIlValueType.isKnownClrValueType(): Boolean = when (this) {
    DotNetIlValueType.Boolean,
    DotNetIlValueType.Char,
    DotNetIlValueType.Float32,
    DotNetIlValueType.Float64,
    DotNetIlValueType.Int8,
    DotNetIlValueType.Int16,
    DotNetIlValueType.Int32,
    DotNetIlValueType.Int64,
    is DotNetIlValueType.NullableValue,
        -> true
    else -> false
}

private fun DotNetIlValueType.isClosedPhysicalType(): Boolean = when (this) {
    is DotNetIlValueType.TypeParameter -> false
    is DotNetIlValueType.GenericInstance -> arguments.all { argument -> argument.isClosedPhysicalType() }
    is DotNetIlValueType.GenericArray -> elementType.isClosedPhysicalType()
    is DotNetIlValueType.PrimitiveArray -> elementType.isClosedPhysicalType()
    is DotNetIlValueType.NullableValue -> elementType.isClosedPhysicalType()
    // Managed pointers are calling-convention carriers, never closed generic arguments.
    is DotNetIlValueType.ByReference -> false
    DotNetIlValueType.Boolean,
    DotNetIlValueType.Char,
    DotNetIlValueType.Float32,
    DotNetIlValueType.Float64,
    DotNetIlValueType.Int8,
    DotNetIlValueType.Int16,
    DotNetIlValueType.Int32,
    DotNetIlValueType.Int64,
    DotNetIlValueType.Object,
    DotNetIlValueType.String,
    is DotNetIlValueType.ErasedGenericArray,
    is DotNetIlValueType.MappedClass,
    is DotNetIlValueType.UserClass,
        -> true
}
