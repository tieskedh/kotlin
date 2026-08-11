/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin.reflect

import kotlin.internal.UsedFromCompilerGeneratedCode

private class DotNetKTypeParameter(
    override val name: String,
    override val variance: KVariance,
    override val isReified: Boolean,
    override val containerFqName: String,
) : KTypeParameterBase() {
    private var upperBoundsStorage: Array<KType>? = null

    override val upperBounds: List<KType>
        get() = upperBoundsStorage?.asList()
            ?: throw IllegalStateException("KTypeParameter upper bounds have not been initialized")

    fun initializeUpperBounds(upperBounds: Array<KType>) {
        if (upperBoundsStorage != null) {
            throw IllegalStateException("KTypeParameter upper bounds have already been initialized")
        }
        upperBoundsStorage = upperBounds
    }
}

@PublishedApi
@UsedFromCompilerGeneratedCode
internal fun dotNetCreateKType(
    classifier: KClassifier?,
    arguments: Array<KTypeProjection>,
    isMarkedNullable: Boolean,
    annotations: List<Annotation>,
): KType = KTypeImpl(classifier, arguments.asList(), isMarkedNullable, annotations)

@PublishedApi
@UsedFromCompilerGeneratedCode
internal fun dotNetCreateKTypeParameter(
    name: String,
    variance: String,
    isReified: Boolean,
    containerKey: String,
): KTypeParameter = DotNetKTypeParameter(
    name,
    when (variance) {
        "in" -> KVariance.IN
        "out" -> KVariance.OUT
        else -> KVariance.INVARIANT
    },
    isReified,
    containerKey,
)

@PublishedApi
@UsedFromCompilerGeneratedCode
internal fun dotNetInitializeKTypeParameterUpperBounds(
    parameter: KTypeParameter,
    upperBounds: Array<KType>,
) {
    (parameter as DotNetKTypeParameter).initializeUpperBounds(upperBounds)
}

@PublishedApi
@UsedFromCompilerGeneratedCode
internal fun dotNetStarKTypeProjection(): KTypeProjection = KTypeProjection.STAR

@PublishedApi
@UsedFromCompilerGeneratedCode
internal fun dotNetInvariantKTypeProjection(type: KType): KTypeProjection = KTypeProjection.invariant(type)

@PublishedApi
@UsedFromCompilerGeneratedCode
internal fun dotNetContravariantKTypeProjection(type: KType): KTypeProjection = KTypeProjection.contravariant(type)

@PublishedApi
@UsedFromCompilerGeneratedCode
internal fun dotNetCovariantKTypeProjection(type: KType): KTypeProjection = KTypeProjection.covariant(type)
