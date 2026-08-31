/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.dotnet

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirDeclarationDataKey
import org.jetbrains.kotlin.fir.declarations.FirDeclarationDataRegistry
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.resolve.fullyExpandedType
import org.jetbrains.kotlin.fir.resolve.toRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.ConeDefinitelyNotNullType
import org.jetbrains.kotlin.fir.types.ConeFlexibleType
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeTypeParameterType
import org.jetbrains.kotlin.fir.types.ProjectionKind
import org.jetbrains.kotlin.fir.types.isAnyOrNullableAny
import org.jetbrains.kotlin.fir.types.isPrimitiveOrNullablePrimitive
import org.jetbrains.kotlin.fir.types.lowerBoundIfFlexible
import org.jetbrains.kotlin.fir.types.type
import org.jetbrains.kotlin.fir.types.typeContext
import org.jetbrains.kotlin.load.dotnet.DotNetClrGenericParameterVariance
import org.jetbrains.kotlin.load.dotnet.DotNetClrImportedDeclarationGraph
import org.jetbrains.kotlin.load.dotnet.DotNetClrImportedTypeSource
import org.jetbrains.kotlin.load.dotnet.DotNetClrResolvedGenericParameterContext
import org.jetbrains.kotlin.types.AbstractTypeChecker

/**
 * The source-level result of checking one implicit conversion between retained foreign CLR types.
 *
 * [UNKNOWN] deliberately does not diagnose. Final emission still has to validate the selected
 * physical carrier after lowering, substitution, inherited-view selection, and local placement.
 */
enum class DotNetClrForeignVarianceConversionResult {
    NOT_APPLICABLE,
    VERIFIER_VALID,
    REQUIRES_REFERENCE_ARGUMENTS,
    UNKNOWN,
}

private data class DotNetClrImportedTypeAuthority(
    val source: DotNetClrImportedTypeSource,
    val genericContext: DotNetClrResolvedGenericParameterContext,
) {
    init {
        require(genericContext.method == null) {
            "Imported CLR TypeDef authority cannot use a method generic context"
        }
        require(genericContext.declaringType.type.hasSameIdentityAs(source.declaringHierarchy.type.type)) {
            "Imported CLR TypeDef authority has a mismatched generic context"
        }
        require(genericContext.declaringType == source.declaringHierarchy.type) {
            "Imported CLR TypeDef authority has a mismatched open declaration view"
        }
    }
}

private object DotNetClrImportedTypeAuthorityKey : FirDeclarationDataKey()

private var FirRegularClass.dotNetClrImportedTypeAuthority: DotNetClrImportedTypeAuthority?
        by FirDeclarationDataRegistry.data(DotNetClrImportedTypeAuthorityKey)

@OptIn(SymbolInternals::class)
private val FirRegularClassSymbol.dotNetClrImportedTypeAuthority: DotNetClrImportedTypeAuthority?
    get() = fir.dotNetClrImportedTypeAuthority

internal fun FirRegularClass.recordDotNetClrImportedTypeAuthority(
    graph: DotNetClrImportedDeclarationGraph,
    genericContext: DotNetClrResolvedGenericParameterContext,
) {
    check(dotNetClrImportedTypeAuthority == null) {
        "Imported CLR TypeDef authority was recorded more than once"
    }
    dotNetClrImportedTypeAuthority = DotNetClrImportedTypeAuthority(
        DotNetClrImportedTypeSource(
            graph.assemblyOrNull(genericContext.declaringType.type.assembly)
                ?: error("Imported CLR TypeDef authority lost its selected assembly"),
            genericContext.declaringType.type.definition,
            graph.hierarchyOrNull(genericContext.declaringType.type)
                ?: error("Imported CLR TypeDef authority lost its selected hierarchy"),
            graph,
        ),
        genericContext,
    )
}

/** Exact TypeDef carrier selected by the CLR importer, independent of declared members. */
fun FirRegularClass.dotNetClrImportedTypeSourceOrNull(): DotNetClrImportedTypeSource? =
    dotNetClrImportedTypeAuthority?.source

/**
 * Checks the CLR reference-only restriction on a conversion already accepted by Kotlin typing.
 *
 * This bounded FIR query handles two constructions of the same retained foreign TypeDef. It does
 * not infer a physical identity from a Kotlin name, FIR variance, or library origin. More complex
 * inherited, projected, captured, or ambiguous views remain [DotNetClrForeignVarianceConversionResult.UNKNOWN]
 * and are owned by the mandatory final-emission validator.
 */
fun dotNetClrForeignVarianceConversion(
    actual: ConeKotlinType,
    expected: ConeKotlinType,
    session: FirSession,
): DotNetClrForeignVarianceConversionResult {
    if (!AbstractTypeChecker.isSubtypeOf(session.typeContext, actual, expected)) {
        return DotNetClrForeignVarianceConversionResult.NOT_APPLICABLE
    }
    val relation = DotNetClrForeignVarianceRelation(session)
    return relation.evaluateRoot(
        actual.fullyExpandedType(session),
        expected.fullyExpandedType(session),
    )
}

private class DotNetClrForeignVarianceRelation(
    private val session: FirSession,
) {
    private enum class Relation {
        NOT_APPLICABLE,
        VALID,
        REQUIRES_REFERENCE_ARGUMENTS,
        UNKNOWN,
    }

    private enum class CarrierShape {
        REFERENCE,
        VALUE,
        UNKNOWN,
    }

    fun evaluateRoot(
        actual: ConeKotlinType,
        expected: ConeKotlinType,
    ): DotNetClrForeignVarianceConversionResult =
        when (evaluateSameForeignOwner(actual, expected, linkedSetOf())) {
            Relation.NOT_APPLICABLE -> DotNetClrForeignVarianceConversionResult.NOT_APPLICABLE
            Relation.VALID -> DotNetClrForeignVarianceConversionResult.VERIFIER_VALID
            Relation.REQUIRES_REFERENCE_ARGUMENTS ->
                DotNetClrForeignVarianceConversionResult.REQUIRES_REFERENCE_ARGUMENTS
            Relation.UNKNOWN -> DotNetClrForeignVarianceConversionResult.UNKNOWN
        }

    private fun evaluateSameForeignOwner(
        actual: ConeKotlinType,
        expected: ConeKotlinType,
        active: MutableSet<Pair<ConeKotlinType, ConeKotlinType>>,
    ): Relation {
        val actualClassType = actual.lowerBoundIfFlexible() as? ConeClassLikeType
            ?: return Relation.NOT_APPLICABLE
        val expectedClassType = expected.lowerBoundIfFlexible() as? ConeClassLikeType
            ?: return Relation.NOT_APPLICABLE
        val actualSymbol = actualClassType.toRegularClassSymbol(session)
            ?: return Relation.NOT_APPLICABLE
        val expectedSymbol = expectedClassType.toRegularClassSymbol(session)
            ?: return Relation.NOT_APPLICABLE
        val actualAuthority = actualSymbol.dotNetClrImportedTypeAuthority
            ?: return Relation.NOT_APPLICABLE
        val expectedAuthority = expectedSymbol.dotNetClrImportedTypeAuthority
            ?: return Relation.NOT_APPLICABLE
        if (!actualAuthority.source.declaringHierarchy.type.type.hasSameIdentityAs(
                expectedAuthority.source.declaringHierarchy.type.type,
            )
        ) {
            return Relation.NOT_APPLICABLE
        }
        if (actualAuthority.source.graph !== expectedAuthority.source.graph) {
            return Relation.UNKNOWN
        }
        val parameters = actualAuthority.genericContext.typeParameters
        if (parameters.size != expectedAuthority.genericContext.typeParameters.size ||
            parameters.size != actualClassType.typeArguments.size ||
            parameters.size != expectedClassType.typeArguments.size
        ) {
            return Relation.UNKNOWN
        }
        if (!active.add(actual to expected)) return Relation.UNKNOWN
        return try {
            var requiresReferenceArguments = false
            for (index in parameters.indices) {
                val actualProjection = actualClassType.typeArguments[index]
                val expectedProjection = expectedClassType.typeArguments[index]
                val actualArgument = actualProjection.type
                val expectedArgument = expectedProjection.type
                if (actualArgument != null && expectedArgument != null &&
                    hasSameSelectedCarrier(actualArgument, expectedArgument)
                ) {
                    continue
                }
                if (actualProjection.kind != ProjectionKind.INVARIANT ||
                    expectedProjection.kind != ProjectionKind.INVARIANT ||
                    actualArgument == null || expectedArgument == null
                ) {
                    return Relation.UNKNOWN
                }
                val parameter = parameters[index].parameter
                if (parameter.number != index) return Relation.UNKNOWN
                if (parameter.variance == DotNetClrGenericParameterVariance.INVARIANT) {
                    return Relation.UNKNOWN
                }
                val actualShape = referenceShape(actualArgument)
                val expectedShape = referenceShape(expectedArgument)
                when {
                    actualShape == CarrierShape.REFERENCE &&
                            expectedShape == CarrierShape.REFERENCE -> Unit
                    actualShape == CarrierShape.VALUE ||
                            expectedShape == CarrierShape.VALUE -> {
                        requiresReferenceArguments = true
                        continue
                    }
                    else -> return Relation.UNKNOWN
                }
                val source: ConeKotlinType
                val destination: ConeKotlinType
                when (parameter.variance) {
                    DotNetClrGenericParameterVariance.COVARIANT -> {
                        source = actualArgument
                        destination = expectedArgument
                    }
                    DotNetClrGenericParameterVariance.CONTRAVARIANT -> {
                        source = expectedArgument
                        destination = actualArgument
                    }
                    DotNetClrGenericParameterVariance.INVARIANT ->
                        error("Invariant CLR generic parameters were handled before routing")
                }
                when (evaluateReferenceAssignable(source, destination, active)) {
                    Relation.VALID -> Unit
                    Relation.REQUIRES_REFERENCE_ARGUMENTS ->
                        requiresReferenceArguments = true
                    Relation.NOT_APPLICABLE,
                    Relation.UNKNOWN,
                    -> return Relation.UNKNOWN
                }
            }
            if (requiresReferenceArguments) {
                Relation.REQUIRES_REFERENCE_ARGUMENTS
            } else {
                Relation.VALID
            }
        } finally {
            active.remove(actual to expected)
        }
    }

    private fun hasSameSelectedCarrier(
        actual: ConeKotlinType,
        expected: ConeKotlinType,
    ): Boolean =
        AbstractTypeChecker.equalTypes(session.typeContext, actual, expected) ||
                AbstractTypeChecker.equalTypes(
                    session.typeContext,
                    actual.lowerBoundIfFlexible(),
                    expected.lowerBoundIfFlexible(),
                )

    private fun evaluateReferenceAssignable(
        actual: ConeKotlinType,
        expected: ConeKotlinType,
        active: MutableSet<Pair<ConeKotlinType, ConeKotlinType>>,
    ): Relation {
        if (AbstractTypeChecker.equalTypes(session.typeContext, actual, expected)) {
            return Relation.VALID
        }
        return when (val nested = evaluateSameForeignOwner(actual, expected, active)) {
            Relation.NOT_APPLICABLE ->
                if (isKnownCoreReferenceConversion(actual, expected)) {
                    Relation.VALID
                } else {
                    Relation.UNKNOWN
                }
            else -> nested
        }
    }

    private fun referenceShape(type: ConeKotlinType): CarrierShape {
        val expanded = type.fullyExpandedType(session)
        return when (expanded) {
            is ConeFlexibleType -> {
                val lower = referenceShape(expanded.lowerBound)
                val upper = referenceShape(expanded.upperBound)
                if (lower == upper) lower else CarrierShape.UNKNOWN
            }
            is ConeDefinitelyNotNullType -> referenceShape(expanded.original)
            // A logical FIR bound is not yet a producer-recorded physical GenericParam fact.
            // String bounds, erased class bounds, concrete class constraints, and unconstrained
            // parameters deliberately select different CLR carriers. Defer all of them rather
            // than rejecting a verifier-valid construction from an approximation here.
            is ConeTypeParameterType -> CarrierShape.UNKNOWN
            is ConeClassLikeType ->
                if (expanded.isPrimitiveOrNullablePrimitive) {
                    CarrierShape.VALUE
                } else {
                    // Kotlin value classes are boxed nominal references in CLR generic arguments.
                    // Retained foreign structs are not part of the current importer grammar.
                    CarrierShape.REFERENCE
                }
            else -> CarrierShape.UNKNOWN
        }
    }

    /**
     * This source query may prove only CLR relations which do not depend on a Kotlin owner's
     * eventual generic representation. Every CLR reference is assignable to `object`; other
     * Kotlin nominal subtyping remains unknown unless retained-foreign authority proved it above.
     */
    private fun isKnownCoreReferenceConversion(
        actual: ConeKotlinType,
        expected: ConeKotlinType,
    ): Boolean =
        expected.fullyExpandedType(session).isAnyOrNullableAny &&
                referenceShape(actual) == CarrierShape.REFERENCE
}
