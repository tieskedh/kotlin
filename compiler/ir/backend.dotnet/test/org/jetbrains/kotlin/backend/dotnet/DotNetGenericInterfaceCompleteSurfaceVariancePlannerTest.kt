/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.ir.symbols.impl.IrClassSymbolImpl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

class DotNetGenericInterfaceCompleteSurfaceVariancePlannerTest {
    @Test
    fun preservesTheLogicalMaximumForAnOutputOnlyOrUnusedParameter() {
        val outputOnly = identity("OutputOnly")
        val unused = identity("Unused")

        val plan = plan(
            owner(
                outputOnly,
                COVARIANT,
                positions = listOf(position(OUT, parameter(0))),
            ),
            owner(unused, CONTRAVARIANT),
        ).boundValue()

        assertEquals(
            decision(COVARIANT, OUT, COVARIANT),
            plan.ownerOrNull(outputOnly)?.parameters?.single(),
        )
        assertEquals(
            decision(CONTRAVARIANT, NONE, CONTRAVARIANT),
            plan.ownerOrNull(unused)?.parameters?.single(),
        )
    }

    @Test
    fun weakensOnlyTheParameterUsedAgainstItsLogicalVariance() {
        val owner = identity("PairSurface")

        val plan = plan(
            owner(
                owner,
                COVARIANT,
                COVARIANT,
                positions = listOf(
                    position(OUT, parameter(0)),
                    position(IN, parameter(0)),
                    position(OUT, parameter(1)),
                ),
            ),
        ).boundValue()

        assertEquals(
            listOf(
                decision(COVARIANT, BOTH, INVARIANT, index = 0),
                decision(COVARIANT, OUT, COVARIANT, index = 1),
            ),
            plan.ownerOrNull(owner)?.parameters,
        )
    }

    @Test
    fun weakensAContravariantParameterWhenTheCompleteSurfaceProducesIt() {
        val owner = identity("ContravariantOutput")

        val plan = plan(
            owner(
                owner,
                CONTRAVARIANT,
                positions = listOf(
                    position(IN, parameter(0)),
                    position(OUT, parameter(0)),
                ),
            ),
        ).boundValue()

        assertEquals(
            decision(CONTRAVARIANT, BOTH, INVARIANT),
            plan.ownerOrNull(owner)?.parameters?.single(),
        )
    }

    @Test
    fun usesPhysicalNestedVarianceAndSupportsPolarityReversal() {
        val covariant = identity("Covariant")
        val contravariant = identity("Contravariant")
        val invariant = identity("Invariant")
        val throughCovariant = identity("ThroughCovariant")
        val throughContravariant = identity("ThroughContravariant")
        val throughDoubleContravariant = identity("ThroughDoubleContravariant")
        val throughInvariant = identity("ThroughInvariant")

        val plan = plan(
            owner(
                throughCovariant,
                COVARIANT,
                positions = listOf(position(OUT, construction(covariant, parameter(0)))),
            ),
            owner(
                throughContravariant,
                COVARIANT,
                positions = listOf(position(OUT, construction(contravariant, parameter(0)))),
            ),
            owner(
                throughDoubleContravariant,
                COVARIANT,
                positions = listOf(position(
                    OUT,
                    construction(contravariant, construction(contravariant, parameter(0))),
                )),
            ),
            owner(
                throughInvariant,
                COVARIANT,
                positions = listOf(position(OUT, construction(invariant, parameter(0)))),
            ),
            fixed = listOf(
                fixed(covariant, COVARIANT),
                fixed(contravariant, CONTRAVARIANT),
                fixed(invariant, INVARIANT),
            ),
        ).boundValue()

        assertSelected(plan, throughCovariant, COVARIANT)
        assertSelected(plan, throughContravariant, INVARIANT)
        assertSelected(plan, throughDoubleContravariant, COVARIANT)
        assertSelected(plan, throughInvariant, INVARIANT)
    }

    @Test
    fun reachesAFamilyFixpointWhenAParentLaterBecomesInvariant() {
        val parent = identity("Parent")
        val child = identity("Child")

        val plan = plan(
            // Deliberately put the child first. Correctness may not depend on iteration order.
            owner(
                child,
                COVARIANT,
                positions = listOf(position(OUT, construction(parent, parameter(0)))),
            ),
            owner(
                parent,
                COVARIANT,
                positions = listOf(position(IN, parameter(0))),
            ),
        ).boundValue()

        assertSelected(plan, parent, INVARIANT)
        assertSelected(plan, child, INVARIANT)
    }

    @Test
    fun treatsArraysAsCovariantSurfacePositions() {
        val owner = identity("ArrayProducer")

        val plan = plan(
            owner(
                owner,
                COVARIANT,
                positions = listOf(position(OUT, szArray(parameter(0)))),
            ),
        ).boundValue()

        assertSelected(plan, owner, COVARIANT)
    }

    @Test
    fun failsClosedWhenARelevantNestedPhysicalVarianceIsUnavailable() {
        val owner = identity("Owner")
        val unknown = identity("Unknown")

        val result = plan(
            owner(
                owner,
                COVARIANT,
                positions = listOf(position(OUT, construction(unknown, parameter(0)))),
            ),
        )

        assertSame(DotNetGenericOwnerPhysicalBindingResult.Unavailable, result)
    }

    @Test
    fun ignoresMissingAuthorityForATypeThatDoesNotCarryAnOwnerParameter() {
        val owner = identity("Owner")
        val unknown = identity("Unknown")

        val plan = plan(
            owner(
                owner,
                COVARIANT,
                positions = listOf(
                    position(OUT, construction(unknown, independentType())),
                    position(OUT, parameter(0)),
                ),
            ),
        ).boundValue()

        assertSelected(plan, owner, COVARIANT)
    }

    @Test
    fun reportsContradictoryPhysicalArityAsAuthorityConflict() {
        val owner = identity("Owner")
        val fixed = identity("Fixed")

        val result = plan(
            owner(
                owner,
                COVARIANT,
                positions = listOf(position(OUT, construction(fixed, parameter(0)))),
            ),
            fixed = listOf(fixed(fixed, COVARIANT, CONTRAVARIANT)),
        )

        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(result)
    }

    @Test
    fun reportsAnEscapedOwnerParameterBeforeMissingNestedAuthority() {
        val owner = identity("Owner")
        val unknown = identity("Unknown")

        val result = plan(
            owner(
                owner,
                COVARIANT,
                positions = listOf(position(OUT, construction(unknown, parameter(1)))),
            ),
        )

        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(result)
    }

    @Test
    fun rejectsRetainedAuthorityAsALocalVarianceCandidate() {
        assertFailsWith<IllegalArgumentException> {
            owner(
                DotNetGenericOwnerPhysicalTypeDefIdentity.CoreLibrary(
                    listOf("CompleteSurfaceTest", "Retained"),
                ),
                COVARIANT,
            )
        }
    }

    @Test
    fun rejectsAManuallyConstructedIllegalDecision() {
        assertFailsWith<IllegalArgumentException> {
            decision(COVARIANT, IN, COVARIANT)
        }
    }

    private fun assertSelected(
        plan: DotNetGenericInterfaceCompleteSurfaceVariancePlan,
        owner: DotNetGenericOwnerPhysicalTypeDefIdentity,
        expected: DotNetGenericOwnerPhysicalTypeParameterVariance,
    ) {
        assertEquals(expected, plan.ownerOrNull(owner)?.parameters?.single()?.selectedPhysicalVariance)
    }

    private fun <T> DotNetGenericOwnerPhysicalBindingResult<T>.boundValue(): T = when (this) {
        is DotNetGenericOwnerPhysicalBindingResult.Bound -> value
        is DotNetGenericOwnerPhysicalBindingResult.Conflict -> error(reason)
        DotNetGenericOwnerPhysicalBindingResult.Unavailable -> error("expected bound complete-surface variance")
    }

    private fun plan(
        vararg owners: DotNetGenericInterfaceCompleteSurfaceOwnerInput,
        fixed: List<DotNetGenericInterfaceCompleteSurfaceFixedTypeInput> = emptyList(),
    ): DotNetGenericOwnerPhysicalBindingResult<DotNetGenericInterfaceCompleteSurfaceVariancePlan> =
        planDotNetGenericInterfaceCompleteSurfaceVariance(owners.toList(), fixed)

    private fun owner(
        identity: DotNetGenericOwnerPhysicalTypeDefIdentity,
        vararg logicalMaximumVariances: DotNetGenericOwnerPhysicalTypeParameterVariance,
        positions: List<DotNetGenericInterfaceCompleteSurfacePosition> = emptyList(),
    ) = DotNetGenericInterfaceCompleteSurfaceOwnerInput(
        identity,
        logicalMaximumVariances.toList(),
        positions,
    )

    private fun fixed(
        identity: DotNetGenericOwnerPhysicalTypeDefIdentity,
        vararg variances: DotNetGenericOwnerPhysicalTypeParameterVariance,
    ) = DotNetGenericInterfaceCompleteSurfaceFixedTypeInput(identity, variances.toList())

    private fun position(
        polarity: DotNetGenericInterfaceCompleteSurfacePolarity,
        type: DotNetGenericInterfaceCompleteSurfaceTypeReference,
    ) = DotNetGenericInterfaceCompleteSurfacePosition(polarity, type)

    private fun parameter(index: Int) =
        DotNetGenericInterfaceCompleteSurfaceTypeReference.OwnerParameter(index)

    private fun construction(
        identity: DotNetGenericOwnerPhysicalTypeDefIdentity,
        vararg arguments: DotNetGenericInterfaceCompleteSurfaceTypeReference,
    ) = DotNetGenericInterfaceCompleteSurfaceTypeReference.Constructed(identity, arguments.toList())

    private fun szArray(element: DotNetGenericInterfaceCompleteSurfaceTypeReference) =
        DotNetGenericInterfaceCompleteSurfaceTypeReference.SzArray(element)

    private fun independentType() = DotNetGenericInterfaceCompleteSurfaceTypeReference.Independent

    @Suppress("UNUSED_PARAMETER")
    private fun identity(name: String) = DotNetGenericOwnerPhysicalTypeDefIdentity.Local(
        IrClassSymbolImpl(),
        DotNetGenericInterfaceView.DECLARED,
    )

    private fun decision(
        logicalMaximum: DotNetGenericOwnerPhysicalTypeParameterVariance,
        requiredPolarity: DotNetGenericInterfaceCompleteSurfacePolarity,
        selectedPhysicalVariance: DotNetGenericOwnerPhysicalTypeParameterVariance,
        index: Int = 0,
    ) = DotNetGenericInterfaceCompleteSurfaceParameterDecision(
        index,
        logicalMaximum,
        requiredPolarity,
        selectedPhysicalVariance,
    )

    private companion object {
        val INVARIANT = DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT
        val COVARIANT = DotNetGenericOwnerPhysicalTypeParameterVariance.COVARIANT
        val CONTRAVARIANT = DotNetGenericOwnerPhysicalTypeParameterVariance.CONTRAVARIANT

        val NONE = DotNetGenericInterfaceCompleteSurfacePolarity.NONE
        val OUT = DotNetGenericInterfaceCompleteSurfacePolarity.OUT
        val IN = DotNetGenericInterfaceCompleteSurfacePolarity.IN
        val BOTH = DotNetGenericInterfaceCompleteSurfacePolarity.BOTH
    }
}
