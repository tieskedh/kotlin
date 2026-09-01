/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.load.dotnet

/** One ordered argument obligation for a possible CLR reference-variance conversion. */
sealed interface DotNetClrReferenceVarianceStep<out T> {
    data class Assignment<T>(
        val actual: T,
        val expected: T,
        val source: T,
        val destination: T,
    ) : DotNetClrReferenceVarianceStep<T>

    /** A differing invariant argument makes the candidate non-convertible at this position. */
    data object InvariantMismatch : DotNetClrReferenceVarianceStep<Nothing>
}

sealed interface DotNetClrReferenceVariancePlan<out T> {
    data class Planned<T>(
        val steps: List<DotNetClrReferenceVarianceStep<T>>,
    ) : DotNetClrReferenceVariancePlan<T>

    data object InvalidGenericParameterLayout : DotNetClrReferenceVariancePlan<Nothing>
}

/**
 * Plans CLR variance direction without classifying or resolving either argument representation.
 *
 * Equal arguments create no obligation, including equal value arguments. Every differing variant
 * argument is left to the caller as a reference-only assignment proof. Keeping this ordering
 * shared makes retained backend authority and importer signature assignability observe the same
 * first failing GenericParam row without coupling their distinct metadata representations.
 */
fun <T> planDotNetClrReferenceVariance(
    variances: List<DotNetClrGenericParameterVariance>,
    actualArguments: List<T>,
    expectedArguments: List<T>,
): DotNetClrReferenceVariancePlan<T> {
    if (variances.size != actualArguments.size ||
        variances.size != expectedArguments.size
    ) {
        return DotNetClrReferenceVariancePlan.InvalidGenericParameterLayout
    }
    val steps = mutableListOf<DotNetClrReferenceVarianceStep<T>>()
    for (index in variances.indices) {
        val actual = actualArguments[index]
        val expected = expectedArguments[index]
        if (actual == expected) continue
        steps += when (variances[index]) {
            DotNetClrGenericParameterVariance.INVARIANT ->
                DotNetClrReferenceVarianceStep.InvariantMismatch
            DotNetClrGenericParameterVariance.COVARIANT ->
                DotNetClrReferenceVarianceStep.Assignment(
                    actual = actual,
                    expected = expected,
                    source = actual,
                    destination = expected,
                )
            DotNetClrGenericParameterVariance.CONTRAVARIANT ->
                DotNetClrReferenceVarianceStep.Assignment(
                    actual = actual,
                    expected = expected,
                    source = expected,
                    destination = actual,
                )
        }
    }
    return DotNetClrReferenceVariancePlan.Planned(steps.toList())
}
