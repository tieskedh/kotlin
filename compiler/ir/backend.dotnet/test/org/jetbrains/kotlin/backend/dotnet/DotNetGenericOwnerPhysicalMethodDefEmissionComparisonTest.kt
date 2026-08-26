/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.ir.symbols.impl.IrClassSymbolImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrSimpleFunctionSymbolImpl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DotNetGenericOwnerPhysicalMethodDefEmissionComparisonTest {
    @Test
    fun matchesOneExactFinalMethodDefObservation() {
        val expected = directHeaderShape()
        val expectedSnapshot = headerSnapshot("expected")
        val actualSnapshot = headerSnapshot("emitted")

        val comparison = compare(
            expected,
            expectedSnapshot,
            listOf(known(expected, actualSnapshot)),
        )

        assertEquals(DotNetGenericOwnerPhysicalMethodDefEmissionComparisonStatus.MATCH, comparison.status)
        assertEquals(1, comparison.observationCount)
        assertEquals(actualSnapshot, comparison.actual)
        assertNull(comparison.diagnostic)
    }

    @Test
    fun reportsMissingFinalMethodDefAsUnavailable() {
        val comparison = compare(
            directHeaderShape(),
            headerSnapshot("expected"),
            emptyList(),
        )

        assertEquals(DotNetGenericOwnerPhysicalMethodDefEmissionComparisonStatus.UNAVAILABLE, comparison.status)
        assertEquals(0, comparison.observationCount)
        assertNull(comparison.actual)
        assertNotNull(comparison.diagnostic)
    }

    @Test
    fun rejectsIdenticalDuplicateFinalMethodDefEvidence() {
        val expected = directHeaderShape()
        val duplicate = known(expected, headerSnapshot("emitted"))

        val comparison = compare(
            expected,
            headerSnapshot("expected"),
            listOf(duplicate, duplicate),
        )

        assertEquals(DotNetGenericOwnerPhysicalMethodDefEmissionComparisonStatus.CONFLICT, comparison.status)
        assertEquals(2, comparison.observationCount)
        assertNull(comparison.actual)
        assertNotNull(comparison.diagnostic)
    }

    @Test
    fun rejectsDifferentDuplicateFinalMethodDefEvidence() {
        val expected = directHeaderShape()
        val drifted = expected.copy(result = direct(objectShape))

        val comparison = compare(
            expected,
            headerSnapshot("expected"),
            listOf(
                known(expected, headerSnapshot("first")),
                known(drifted, headerSnapshot("second", result = directResult(objectSnapshot))),
            ),
        )

        assertEquals(DotNetGenericOwnerPhysicalMethodDefEmissionComparisonStatus.CONFLICT, comparison.status)
        assertEquals(2, comparison.observationCount)
        assertNull(comparison.actual)
        assertNotNull(comparison.diagnostic)
    }

    @Test
    fun rejectsEveryCoveredStructuralHeaderDrift() {
        val expected = directHeaderShape()
        val drifts = listOf(
            "owner identity" to expected.copy(owner = otherOwnerKey),
            "owner generic arity" to expected.copy(ownerGenericArity = 1),
            "owner category" to expected.copy(ownerCategory = DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS),
            "visibility" to expected.copy(visibility = DotNetGenericOwnerPhysicalMethodDefEmissionVisibility.FAMILY),
            "dispatch" to expected.copy(dispatch = DotNetGenericOwnerPhysicalMemberDispatch.OVERRIDABLE),
            "instance and receiver presence" to expected.copy(isInstance = false, receiverCarrier = null),
            "receiver carrier" to expected.copy(receiverCarrier = objectShape),
            "method generic arity" to expected.copy(genericArity = 1),
            "ordinary parameter count" to expected.copy(ordinaryParameterCarriers = emptyList()),
            "ordinary parameter carrier" to expected.copy(ordinaryParameterCarriers = listOf(objectShape)),
            "direct result carrier" to expected.copy(result = direct(objectShape)),
        )

        for (drift in drifts) {
            val label = drift.first
            val actual = drift.second
            val comparison = compare(
                expected,
                headerSnapshot("expected"),
                listOf(known(actual, headerSnapshot("emitted"))),
            )
            assertEquals(
                DotNetGenericOwnerPhysicalMethodDefEmissionComparisonStatus.CONFLICT,
                comparison.status,
                label,
            )
            assertEquals(1, comparison.observationCount, label)
            assertNotNull(comparison.diagnostic, label)
        }
    }

    @Test
    fun ignoresTheDiagnosticPhysicalMethodName() {
        val expected = directHeaderShape()
        val expectedSnapshot = headerSnapshot("source-name")
        val actualSnapshot = headerSnapshot("emitter-chosen-name")

        val comparison = compare(
            expected,
            expectedSnapshot,
            listOf(known(expected, actualSnapshot)),
        )

        assertEquals(DotNetGenericOwnerPhysicalMethodDefEmissionComparisonStatus.MATCH, comparison.status)
        assertEquals("emitter-chosen-name", comparison.actual?.physicalMethodNameForDiagnostics)
    }

    @Test
    fun rejectsAHeaderObservedUnderAnotherPhysicalMethodRole() {
        val expected = directHeaderShape()
        val comparison = compare(
            expected,
            headerSnapshot("expected"),
            listOf(known(
                expected,
                headerSnapshot(
                    "same-header-wrong-role",
                    methodRole = DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK,
                ),
            )),
        )

        assertEquals(DotNetGenericOwnerPhysicalMethodDefEmissionComparisonStatus.CONFLICT, comparison.status)
        assertNotNull(comparison.diagnostic)
    }

    @Test
    fun distinguishesDirectAndSplitNullableResultLayouts() {
        val direct = directHeaderShape()
        val split = direct.copy(result = splitNullable(valueShape))

        assertEquals(
            DotNetGenericOwnerPhysicalMethodDefEmissionComparisonStatus.CONFLICT,
            compare(
                direct,
                headerSnapshot("expected-direct"),
                listOf(known(split, headerSnapshot("emitted-split", result = splitNullableResult(valueSnapshot)))),
            ).status,
        )
        assertEquals(
            DotNetGenericOwnerPhysicalMethodDefEmissionComparisonStatus.CONFLICT,
            compare(
                split,
                headerSnapshot("expected-split", result = splitNullableResult(valueSnapshot)),
                listOf(known(direct, headerSnapshot("emitted-direct"))),
            ).status,
        )
    }

    @Test
    fun composesOwnerDependentInputWithAnIndependentSplitNullableResult() {
        val expected = directHeaderShape().copy(result = splitNullable(valueShape))
        val snapshot = headerSnapshot(
            "lookup",
            result = splitNullableResult(valueSnapshot),
        )

        val comparison = compare(expected, snapshot, listOf(known(expected, snapshot)))

        assertEquals(DotNetGenericOwnerPhysicalMethodDefEmissionComparisonStatus.MATCH, comparison.status)
        assertEquals(listOf(keyShape), expected.ordinaryParameterCarriers)
        assertEquals(splitNullable(valueShape), expected.result)
        assertEquals(listOf(keySnapshot), comparison.actual?.ordinaryParameterCarriers)
        assertEquals(
            DotNetGenericOwnerPhysicalMethodDefEmissionResultLayout.SPLIT_NULLABLE,
            comparison.actual?.result?.layout,
        )
        assertEquals(valueSnapshot, comparison.actual?.result?.carrier)
        assertEquals(true, comparison.actual?.result?.hasCanonicalOutBooleanNullFlag)
    }

    @Test
    fun recognizesOnlyTheExpectedEndpointAndItsLegitimateExactSibling() {
        val logicalOwner = IrClassSymbolImpl()
        val expectedOwner = DotNetGenericOwnerPhysicalTypeDefIdentity.Local(
            logicalOwner,
            DotNetGenericInterfaceView.DECLARED,
        )

        assertEquals(
            DotNetGenericOwnerPhysicalMethodDefEmissionOwnerRelation.MATCHING_ENDPOINT,
            classifyDotNetGenericOwnerPhysicalMethodDefEmissionOwner(
                DotNetGenericOwnerPhysicalMethodDefEmissionEntryKind.NATURAL_INTERFACE,
                expectedOwner,
                expectedOwnerArity = 1,
                observedOwner(expectedOwner),
            ),
        )
        assertEquals(
            DotNetGenericOwnerPhysicalMethodDefEmissionOwnerRelation.LEGITIMATE_EXACT_SIBLING,
            classifyDotNetGenericOwnerPhysicalMethodDefEmissionOwner(
                DotNetGenericOwnerPhysicalMethodDefEmissionEntryKind.NATURAL_INTERFACE,
                expectedOwner,
                expectedOwnerArity = 1,
                observedOwner(
                    DotNetGenericOwnerPhysicalTypeDefIdentity.Local(
                        logicalOwner,
                        DotNetGenericInterfaceView.EXACT,
                    ),
                ),
            ),
        )
    }

    @Test
    fun distinguishesMissingExplicitNullAndNamedMethodDefRoles() {
        val function = IrSimpleFunctionSymbolImpl()
        val typed = DotNetGenericOwnerPhysicalMethodDefIdentity.Local(
            function,
            DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY,
        )
        val semantic = DotNetGenericOwnerPhysicalMethodDefIdentity.Local(
            function,
            DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK,
        )
        val roleless = DotNetGenericOwnerPhysicalMethodDefIdentity.Local(function, role = null)
        val family = listOf(typed, semantic)

        assertEquals(
            DotNetGenericOwnerPhysicalMethodDefEmissionIdentityRelation.MATCHING_ENDPOINT,
            classifyDotNetGenericOwnerPhysicalMethodDefEmissionIdentity(
                typed,
                family,
                function,
                typed,
            ),
        )
        assertEquals(
            true,
            isDotNetGenericOwnerPhysicalMethodDefEmissionIdentityEvidenceForEndpoint(
                typed,
                family,
                function,
                typed,
            ),
        )
        assertEquals(
            DotNetGenericOwnerPhysicalMethodDefEmissionIdentityRelation.OTHER_FAMILY_ENDPOINT,
            classifyDotNetGenericOwnerPhysicalMethodDefEmissionIdentity(
                typed,
                family,
                function,
                semantic,
            ),
        )
        assertEquals(
            DotNetGenericOwnerPhysicalMethodDefEmissionIdentityRelation.UNEXPECTED,
            classifyDotNetGenericOwnerPhysicalMethodDefEmissionIdentity(
                typed,
                family,
                function,
                roleless,
            ),
        )
        assertEquals(
            DotNetGenericOwnerPhysicalMethodDefEmissionIdentityRelation.IRRELEVANT,
            classifyDotNetGenericOwnerPhysicalMethodDefEmissionIdentity(
                typed,
                family,
                IrSimpleFunctionSymbolImpl(),
                DotNetGenericOwnerPhysicalMethodDefIdentity.Local(
                    function,
                    DotNetGenericOwnerMemberFamilyRole.CAPABILITY_DISPATCHER,
                ),
            ),
        )
        assertEquals(
            DotNetGenericOwnerPhysicalMethodDefEmissionIdentityRelation.UNEXPECTED,
            classifyDotNetGenericOwnerPhysicalMethodDefEmissionIdentity(
                typed,
                family,
                function,
                DotNetGenericOwnerPhysicalMethodDefIdentity.Local(
                    IrSimpleFunctionSymbolImpl(),
                    DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY,
                ),
            ),
        )
        assertEquals(
            DotNetGenericOwnerPhysicalMethodDefEmissionIdentityRelation.UNAVAILABLE,
            classifyDotNetGenericOwnerPhysicalMethodDefEmissionIdentity(
                typed,
                family,
                function,
                actual = null,
            ),
        )
        assertEquals(
            DotNetGenericOwnerPhysicalMethodDefEmissionIdentityRelation.MATCHING_ENDPOINT,
            classifyDotNetGenericOwnerPhysicalMethodDefEmissionIdentity(
                roleless,
                listOf(roleless),
                function,
                roleless,
            ),
        )
        val capabilitySlot = IrSimpleFunctionSymbolImpl()
        val rolelessCapability = DotNetGenericOwnerPhysicalMethodDefIdentity.Local(
            capabilitySlot,
            role = null,
        )
        assertEquals(
            DotNetGenericOwnerPhysicalMethodDefEmissionIdentityRelation.IRRELEVANT,
            classifyDotNetGenericOwnerPhysicalMethodDefEmissionIdentity(
                rolelessCapability,
                listOf(typed, rolelessCapability),
                IrSimpleFunctionSymbolImpl(),
                semantic,
            ),
        )
        assertEquals(
            DotNetGenericOwnerPhysicalMethodDefEmissionIdentityRelation.UNEXPECTED,
            classifyDotNetGenericOwnerPhysicalMethodDefEmissionIdentity(
                rolelessCapability,
                listOf(typed, rolelessCapability),
                capabilitySlot,
                DotNetGenericOwnerPhysicalMethodDefIdentity.Local(
                    capabilitySlot,
                    DotNetGenericOwnerMemberFamilyRole.CAPABILITY_DISPATCHER,
                ),
            ),
        )
        assertEquals(
            false,
            isDotNetGenericOwnerPhysicalMethodDefEmissionIdentityEvidenceForEndpoint(
                typed,
                family,
                IrSimpleFunctionSymbolImpl(),
                semantic,
            ),
        )
        val allocator = EmissionIdentityAllocator()
        assertEquals(false, allocator.method(typed) == allocator.method(semantic))
        assertEquals(allocator.method(typed), allocator.method(typed))
    }

    @Test
    fun retainsOnlyTheTruthfulCanonicalDeclaredTypeDefAliasPair() {
        val owner = IrClassSymbolImpl()
        fun candidate(view: DotNetGenericInterfaceView?) =
            DotNetGenericOwnerObservedLocalTypeDefCandidate(
                DotNetGenericOwnerPhysicalTypeDefIdentity.Local(owner, view),
                genericArity = 1,
                category = DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE,
            )

        val resolved = resolveDotNetGenericOwnerObservedLocalTypeDef(
            DotNetGenericOwnerObservedPhysicalTypeDefKey(7),
            listOf(
                candidate(DotNetGenericInterfaceView.CANONICAL),
                candidate(DotNetGenericInterfaceView.DECLARED),
            ),
        ) as DotNetGenericOwnerObservedLocalTypeDefResolution.Known
        assertEquals(DotNetGenericInterfaceView.DECLARED, resolved.typeDef.identity.view)
        assertEquals(
            listOf(DotNetGenericInterfaceView.CANONICAL, DotNetGenericInterfaceView.DECLARED),
            resolved.typeDef.aliases.map { alias -> alias.view },
        )
        val reversed = resolveDotNetGenericOwnerObservedLocalTypeDef(
            DotNetGenericOwnerObservedPhysicalTypeDefKey(10),
            listOf(
                candidate(DotNetGenericInterfaceView.DECLARED),
                candidate(DotNetGenericInterfaceView.CANONICAL),
            ),
        ) as DotNetGenericOwnerObservedLocalTypeDefResolution.Known
        assertEquals(
            resolved.typeDef.aliases.map { alias -> alias.view },
            reversed.typeDef.aliases.map { alias -> alias.view },
        )

        assertEquals(
            DotNetGenericOwnerPhysicalMethodDefEmissionOwnerRelation.MATCHING_ENDPOINT,
            classifyDotNetGenericOwnerPhysicalMethodDefEmissionOwner(
                DotNetGenericOwnerPhysicalMethodDefEmissionEntryKind.NATURAL_INTERFACE,
                DotNetGenericOwnerPhysicalTypeDefIdentity.Local(
                    owner,
                    DotNetGenericInterfaceView.DECLARED,
                ),
                expectedOwnerArity = 1,
                resolved.typeDef,
            ),
        )
        assertEquals(
            DotNetGenericOwnerPhysicalMethodDefEmissionOwnerRelation.UNEXPECTED,
            classifyDotNetGenericOwnerPhysicalMethodDefEmissionOwner(
                DotNetGenericOwnerPhysicalMethodDefEmissionEntryKind.NATURAL_INTERFACE,
                DotNetGenericOwnerPhysicalTypeDefIdentity.Local(
                    owner,
                    DotNetGenericInterfaceView.EXACT,
                ),
                expectedOwnerArity = 1,
                resolved.typeDef,
            ),
        )
        assertEquals(
            DotNetGenericOwnerPhysicalMethodDefEmissionOwnerRelation.UNEXPECTED,
            classifyDotNetGenericOwnerPhysicalMethodDefEmissionOwner(
                DotNetGenericOwnerPhysicalMethodDefEmissionEntryKind.SEMANTIC_CAPABILITY_INTERFACE_SLOT,
                DotNetGenericOwnerPhysicalTypeDefIdentity.Local(owner, view = null),
                expectedOwnerArity = 0,
                resolved.typeDef,
            ),
        )
        assertEquals(
            true,
            resolveDotNetGenericOwnerObservedLocalTypeDef(
                DotNetGenericOwnerObservedPhysicalTypeDefKey(8),
                listOf(
                    candidate(DotNetGenericInterfaceView.CANONICAL),
                    candidate(DotNetGenericInterfaceView.EXACT),
                ),
            ) is DotNetGenericOwnerObservedLocalTypeDefResolution.Conflict,
        )
        assertEquals(
            true,
            resolveDotNetGenericOwnerObservedLocalTypeDef(
                DotNetGenericOwnerObservedPhysicalTypeDefKey(11),
                listOf(
                    candidate(null),
                    candidate(DotNetGenericInterfaceView.DECLARED),
                ),
            ) is DotNetGenericOwnerObservedLocalTypeDefResolution.Conflict,
        )
        assertEquals(
            true,
            resolveDotNetGenericOwnerObservedLocalTypeDef(
                DotNetGenericOwnerObservedPhysicalTypeDefKey(9),
                listOf(
                    candidate(DotNetGenericInterfaceView.CANONICAL),
                    DotNetGenericOwnerObservedLocalTypeDefCandidate(
                        DotNetGenericOwnerPhysicalTypeDefIdentity.Local(
                            IrClassSymbolImpl(),
                            DotNetGenericInterfaceView.DECLARED,
                        ),
                        genericArity = 1,
                        category = DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE,
                    ),
                ),
            ) is DotNetGenericOwnerObservedLocalTypeDefResolution.Conflict,
        )
    }

    @Test
    fun bindsActualAliasesOnlyToAlreadyRegisteredBoundTypeDefs() {
        val owner = IrClassSymbolImpl()
        val canonical = DotNetGenericOwnerPhysicalTypeDefIdentity.Local(
            owner,
            DotNetGenericInterfaceView.CANONICAL,
        )
        val declared = DotNetGenericOwnerPhysicalTypeDefIdentity.Local(
            owner,
            DotNetGenericInterfaceView.DECLARED,
        )
        fun description(identity: DotNetGenericOwnerPhysicalTypeDefIdentity.Local, arity: Int = 1) =
            DotNetGenericOwnerPhysicalTypeDefReference(
                identity,
                genericArity = arity,
                category = DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE,
            )
        fun observed(
            key: Int,
            aliases: List<DotNetGenericOwnerPhysicalTypeDefIdentity.Local>,
            arity: Int = 1,
        ) = DotNetGenericOwnerObservedLocalTypeDef(
            physicalKey = DotNetGenericOwnerObservedPhysicalTypeDefKey(key),
            identity = aliases.singleOrNull { alias ->
                alias.view == DotNetGenericInterfaceView.DECLARED
            } ?: aliases.single(),
            aliases = aliases,
            genericArity = arity,
            category = DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE,
        )

        val declaredOnlyAuthority = EmissionIdentityAllocator()
        val expectedDeclaredKey = declaredOnlyAuthority.expectedType(declared, description(declared))
        val matching = declaredOnlyAuthority.actualType(observed(0, listOf(canonical, declared)))
        assertEquals(
            expectedDeclaredKey,
            (matching as EmissionIdentityAllocator.ActualType.Bound).key,
        )
        val canonicalOnly = declaredOnlyAuthority.actualType(observed(1, listOf(canonical)))
        assertEquals(true, canonicalOnly is EmissionIdentityAllocator.ActualType.Bound)
        assertEquals(
            false,
            (canonicalOnly as EmissionIdentityAllocator.ActualType.Bound).key == expectedDeclaredKey,
        )
        assertEquals(
            true,
            declaredOnlyAuthority.actualType(
                observed(2, listOf(canonical, declared), arity = 2),
            ) is EmissionIdentityAllocator.ActualType.Conflict,
        )

        val twoExpectedAliases = EmissionIdentityAllocator().apply {
            expectedType(canonical, description(canonical))
            expectedType(declared, description(declared))
        }
        assertEquals(
            true,
            twoExpectedAliases.actualType(
                observed(3, listOf(canonical, declared)),
            ) is EmissionIdentityAllocator.ActualType.Conflict,
        )

        val groupedAliases = EmissionIdentityAllocator()
        val expectedAliasGroupKey = groupedAliases.expectedTypeAliasGroup(
            listOf(canonical, declared),
            description(declared),
        )
        assertEquals(
            expectedAliasGroupKey,
            (groupedAliases.actualType(
                observed(4, listOf(canonical, declared)),
            ) as EmissionIdentityAllocator.ActualType.Bound).key,
        )
        assertFailsWith<IllegalStateException> {
            EmissionIdentityAllocator().apply {
                expectedType(canonical, description(canonical))
                expectedTypeAliasGroup(listOf(canonical, declared), description(declared))
            }
        }
        assertEquals(
            setOf(
                DotNetGenericOwnerObservedPhysicalTypeDefKey(5),
                DotNetGenericOwnerObservedPhysicalTypeDefKey(6),
            ),
            conflictingDotNetGenericOwnerObservedPhysicalTypeDefKeys(
                listOf(
                    observed(5, listOf(declared)),
                    observed(6, listOf(declared)),
                ),
            ),
        )
    }

    @Test
    fun rejectsEveryOtherSameFunctionOwnerAsUnexpected() {
        val logicalOwner = IrClassSymbolImpl()
        val expectedOwner = DotNetGenericOwnerPhysicalTypeDefIdentity.Local(
            logicalOwner,
            DotNetGenericInterfaceView.DECLARED,
        )
        val exactOwner = DotNetGenericOwnerPhysicalTypeDefIdentity.Local(
            logicalOwner,
            DotNetGenericInterfaceView.EXACT,
        )
        val cases = listOf(
            "different logical owner" to OwnerClassificationCase(
                DotNetGenericOwnerPhysicalMethodDefEmissionEntryKind.NATURAL_INTERFACE,
                observedOwner(
                    DotNetGenericOwnerPhysicalTypeDefIdentity.Local(
                        IrClassSymbolImpl(),
                        DotNetGenericInterfaceView.DECLARED,
                    ),
                ),
            ),
            "canonical sibling" to OwnerClassificationCase(
                DotNetGenericOwnerPhysicalMethodDefEmissionEntryKind.NATURAL_INTERFACE,
                observedOwner(
                    DotNetGenericOwnerPhysicalTypeDefIdentity.Local(
                        logicalOwner,
                        DotNetGenericInterfaceView.CANONICAL,
                    ),
                ),
            ),
            "exact sibling with the wrong arity" to OwnerClassificationCase(
                DotNetGenericOwnerPhysicalMethodDefEmissionEntryKind.NATURAL_INTERFACE,
                observedOwner(exactOwner, genericArity = 2),
            ),
            "exact sibling emitted as a class" to OwnerClassificationCase(
                DotNetGenericOwnerPhysicalMethodDefEmissionEntryKind.NATURAL_INTERFACE,
                observedOwner(
                    exactOwner,
                    category = DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS,
                ),
            ),
            "exact sibling of a semantic endpoint" to OwnerClassificationCase(
                DotNetGenericOwnerPhysicalMethodDefEmissionEntryKind.SEMANTIC_CAPABILITY_INTERFACE_SLOT,
                observedOwner(exactOwner),
            ),
        )

        for (case in cases) {
            assertEquals(
                DotNetGenericOwnerPhysicalMethodDefEmissionOwnerRelation.UNEXPECTED,
                classifyDotNetGenericOwnerPhysicalMethodDefEmissionOwner(
                    case.second.entryKind,
                    expectedOwner,
                    expectedOwnerArity = 1,
                    case.second.actualOwner,
                ),
                case.first,
            )
        }
    }

    private fun compare(
        expectedShape: DotNetGenericOwnerPhysicalMethodDefEmissionHeaderShape,
        expectedSnapshot: DotNetGenericOwnerPhysicalMethodDefEmissionHeaderSnapshot,
        actualCandidates: List<DotNetGenericOwnerPhysicalMethodDefEmissionHeaderEvidence>,
    ): DotNetGenericOwnerPhysicalMethodDefEmissionEndpointComparisonSnapshot =
        compareDotNetGenericOwnerPhysicalMethodDefEmissionEndpoint(
            DotNetGenericOwnerPhysicalMethodDefEmissionEntryKind.NATURAL_INTERFACE,
            DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY,
            expectedShape,
            expectedSnapshot,
            actualCandidates,
        )

    private fun known(
        shape: DotNetGenericOwnerPhysicalMethodDefEmissionHeaderShape,
        snapshot: DotNetGenericOwnerPhysicalMethodDefEmissionHeaderSnapshot,
    ) = DotNetGenericOwnerPhysicalMethodDefEmissionHeaderEvidence.Known(shape, snapshot)

    private fun observedOwner(
        identity: DotNetGenericOwnerPhysicalTypeDefIdentity.Local,
        genericArity: Int = 1,
        category: DotNetGenericOwnerPhysicalNamedTypeCategory =
            DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE,
    ) = DotNetGenericOwnerObservedLocalTypeDef(
        physicalKey = DotNetGenericOwnerObservedPhysicalTypeDefKey(0),
        identity = identity,
        genericArity = genericArity,
        category = category,
    )

    private data class OwnerClassificationCase(
        val entryKind: DotNetGenericOwnerPhysicalMethodDefEmissionEntryKind,
        val actualOwner: DotNetGenericOwnerObservedLocalTypeDef,
    )

    private fun directHeaderShape() = DotNetGenericOwnerPhysicalMethodDefEmissionHeaderShape(
        owner = ownerKey,
        ownerGenericArity = 2,
        ownerCategory = DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE,
        visibility = DotNetGenericOwnerPhysicalMethodDefEmissionVisibility.PUBLIC,
        dispatch = DotNetGenericOwnerPhysicalMemberDispatch.ABSTRACT,
        isInstance = true,
        genericArity = 0,
        receiverCarrier = receiverShape,
        ordinaryParameterCarriers = listOf(keyShape),
        result = direct(valueShape),
    )

    private fun headerSnapshot(
        physicalMethodName: String,
        result: DotNetGenericOwnerPhysicalMethodDefEmissionResultSnapshot = directResult(valueSnapshot),
        methodRole: DotNetGenericOwnerMemberFamilyRole? =
            DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY,
    ) = DotNetGenericOwnerPhysicalMethodDefEmissionHeaderSnapshot(
        methodIdentity = DotNetGenericOwnerPhysicalMethodDefEmissionIdentitySnapshot(
            methodRole,
        ),
        owner = ownerSnapshot,
        physicalMethodNameForDiagnostics = physicalMethodName,
        visibility = DotNetGenericOwnerPhysicalMethodDefEmissionVisibility.PUBLIC,
        dispatch = DotNetGenericOwnerPhysicalMethodDefEmissionDispatchSnapshot(
            category = DotNetGenericOwnerPhysicalMemberDispatch.ABSTRACT,
            isVirtual = true,
            isNewSlot = true,
            isAbstract = true,
            isFinal = false,
        ),
        isInstance = true,
        genericArity = 0,
        receiverCarrier = receiverSnapshot,
        ordinaryParameterCarriers = listOf(keySnapshot),
        result = result,
    )

    private fun direct(
        carrier: DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape,
    ) = DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.Direct(carrier)

    private fun splitNullable(
        payload: DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape,
    ) = DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.SplitNullable(payload)

    private fun directResult(
        carrier: DotNetGenericOwnerPhysicalMethodDefEmissionCarrierSnapshot,
    ) = DotNetGenericOwnerPhysicalMethodDefEmissionResultSnapshot(
        DotNetGenericOwnerPhysicalMethodDefEmissionResultLayout.DIRECT,
        carrier,
        hasCanonicalOutBooleanNullFlag = false,
    )

    private fun splitNullableResult(
        payload: DotNetGenericOwnerPhysicalMethodDefEmissionCarrierSnapshot,
    ) = DotNetGenericOwnerPhysicalMethodDefEmissionResultSnapshot(
        DotNetGenericOwnerPhysicalMethodDefEmissionResultLayout.SPLIT_NULLABLE,
        payload,
        hasCanonicalOutBooleanNullFlag = true,
    )

    private val ownerKey = DotNetGenericOwnerPhysicalMethodDefEmissionTypeKey(0)
    private val otherOwnerKey = DotNetGenericOwnerPhysicalMethodDefEmissionTypeKey(1)
    private val keyShape = DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.OwnerParameter(ownerKey, 0)
    private val valueShape = DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.OwnerParameter(ownerKey, 1)
    private val objectShape = DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.Leaf(
        DotNetGenericOwnerPhysicalTypeKind.OBJECT,
    )
    private val receiverShape = DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.Construction(
        ownerKey,
        listOf(keyShape, valueShape),
    )

    private val ownerSnapshot = DotNetGenericOwnerPhysicalMethodDefEmissionTypeDefSnapshot(
        ownerName = "Fixture.Owner`2",
        typeDefView = null,
        genericArity = 2,
        category = DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE,
    )
    private val keySnapshot = DotNetGenericOwnerPhysicalMethodDefEmissionCarrierSnapshot(
        kind = DotNetGenericOwnerPhysicalMethodDefEmissionCarrierKind.OWNER_PARAMETER,
        typeDef = ownerSnapshot,
        parameterIndex = 0,
    )
    private val valueSnapshot = DotNetGenericOwnerPhysicalMethodDefEmissionCarrierSnapshot(
        kind = DotNetGenericOwnerPhysicalMethodDefEmissionCarrierKind.OWNER_PARAMETER,
        typeDef = ownerSnapshot,
        parameterIndex = 1,
    )
    private val objectSnapshot = DotNetGenericOwnerPhysicalMethodDefEmissionCarrierSnapshot(
        DotNetGenericOwnerPhysicalMethodDefEmissionCarrierKind.OBJECT,
    )
    private val receiverSnapshot = DotNetGenericOwnerPhysicalMethodDefEmissionCarrierSnapshot(
        kind = DotNetGenericOwnerPhysicalMethodDefEmissionCarrierKind.LOCAL_CONSTRUCTION,
        typeDef = ownerSnapshot,
        arguments = listOf(keySnapshot, valueSnapshot),
    )
}
