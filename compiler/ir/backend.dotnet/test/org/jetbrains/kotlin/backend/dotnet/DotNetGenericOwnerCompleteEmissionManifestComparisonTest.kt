/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DotNetGenericOwnerCompleteEmissionManifestComparisonTest {
    @Test
    fun matchesTheSameCompleteMultisetsInAnyOrder() {
        val actual = expected.copy(
            typeDefs = expected.typeDefs.reversed().map { row ->
                row.copy(aliases = row.aliases.reversed(), directEdges = row.directEdges.reversed())
            },
            methodDefs = expected.methodDefs.reversed(),
            methodImpls = expected.methodImpls.reversed(),
        )

        val comparison = compare(actual)

        assertEquals(match, comparison.status)
        assertEquals(match, comparison.typeDefs.status)
        assertEquals(match, comparison.methodDefs.status)
        assertEquals(match, comparison.methodImpls.status)
    }

    @Test
    fun matchesGenericParameterConstraintsRegardlessOfMetadataRowOrder() {
        val constrainedTypeDef = expected.typeDefs.first().copy(
            genericParameters = listOf(
                typeParameter(
                    DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT,
                    objectCarrier,
                    construction(typeKey(1)),
                ),
            ),
        )
        val constrainedExpected = expected.copy(
            typeDefs = listOf(constrainedTypeDef) + expected.typeDefs.drop(1),
        )
        val constrainedActual = constrainedExpected.copy(
            typeDefs = listOf(
                constrainedTypeDef.copy(
                    genericParameters = constrainedTypeDef.genericParameters.map { parameter ->
                        parameter.copy(constraints = parameter.constraints.reversed())
                    },
                ),
            ) + constrainedExpected.typeDefs.drop(1),
        )

        val comparison = compareDotNetGenericOwnerCompleteEmissionManifest(
            constrainedExpected,
            constrainedActual.asKnownEvidence(),
        )

        assertEquals(match, comparison.typeDefs.status)
        assertEquals(match, comparison.status)
    }

    @Test
    fun rejectsGenericParameterPositionVarianceAndConstraintDrift() {
        val firstParameter = typeParameter(
            DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT,
            objectCarrier,
        )
        val secondParameter = typeParameter(
            DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT,
            construction(typeKey(1)),
        )
        val constrainedTypeDef = expected.typeDefs.first().copy(
            genericArity = 2,
            genericParameters = listOf(firstParameter, secondParameter),
        )
        val constrainedExpected = expected.copy(
            typeDefs = listOf(constrainedTypeDef) + expected.typeDefs.drop(1),
        )
        val hostileTypeDefs = listOf(
            constrainedTypeDef.copy(genericParameters = listOf(secondParameter, firstParameter)),
            constrainedTypeDef.copy(
                genericParameters = listOf(
                    firstParameter.copy(variance = DotNetGenericOwnerPhysicalTypeParameterVariance.COVARIANT),
                    secondParameter,
                ),
            ),
            constrainedTypeDef.copy(
                genericParameters = listOf(
                    firstParameter.copy(constraints = listOf(construction(typeKey(0), objectCarrier))),
                    secondParameter,
                ),
            ),
        )

        for (hostileTypeDef in hostileTypeDefs) {
            val hostileActual = constrainedExpected.copy(
                typeDefs = listOf(hostileTypeDef) + constrainedExpected.typeDefs.drop(1),
            )
            val comparison = compareDotNetGenericOwnerCompleteEmissionManifest(
                constrainedExpected,
                hostileActual.asKnownEvidence(),
            )

            assertEquals(conflict, comparison.typeDefs.status)
            assertEquals(conflict, comparison.status)
        }
    }

    @Test
    fun reportsAMissingRowOfEachKindAsUnavailable() {
        val comparisons = listOf(
            compare(expected.copy(typeDefs = expected.typeDefs.dropLast(1))),
            compare(expected.copy(methodDefs = expected.methodDefs.dropLast(1))),
            compare(expected.copy(methodImpls = expected.methodImpls.dropLast(1))),
        )

        assertEquals(listOf(unavailable, unavailable, unavailable), comparisons.map { it.status })
        assertEquals(unavailable, comparisons[0].typeDefs.status)
        assertEquals(unavailable, comparisons[1].methodDefs.status)
        assertEquals(unavailable, comparisons[2].methodImpls.status)
    }

    @Test
    fun rejectsAnExtraRowOfEachKind() {
        val extraTypeDef = typeDef(
            identity = typeKey(2),
            aliases = listOf(aliasKey(3)),
            arity = 0,
            category = DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE,
        )
        val extraMethodDef = methodDef(methodKey(2), header(owner = typeKey(1)))
        val extraMethodImpl = methodImpl(
            implementingType = typeKey(1),
            body = methodKey(1),
            declarationOwner = construction(typeKey(1)),
            declaration = methodKey(1),
        )
        val comparisons = listOf(
            compare(expected.copy(typeDefs = expected.typeDefs + extraTypeDef)),
            compare(expected.copy(methodDefs = expected.methodDefs + extraMethodDef)),
            compare(expected.copy(methodImpls = expected.methodImpls + extraMethodImpl)),
        )

        assertEquals(listOf(conflict, conflict, conflict), comparisons.map { it.status })
        assertEquals(conflict, comparisons[0].typeDefs.status)
        assertEquals(conflict, comparisons[1].methodDefs.status)
        assertEquals(conflict, comparisons[2].methodImpls.status)
    }

    @Test
    fun conflictEvidenceDominatesAMissingFactWithinOneRowKind() {
        val actualTypeDefs = listOf(
            expected.typeDefs.first(),
            typeDef(
                identity = typeKey(2),
                aliases = listOf(aliasKey(3)),
                arity = 0,
                category = DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE,
            ),
        )

        val comparison = compare(expected.copy(typeDefs = actualTypeDefs))

        assertEquals(conflict, comparison.typeDefs.status)
        assertEquals(conflict, comparison.status)
    }

    @Test
    fun rejectsADuplicateRowOfEachKind() {
        val comparisons = listOf(
            compare(expected.copy(typeDefs = expected.typeDefs + expected.typeDefs.first())),
            compare(expected.copy(methodDefs = expected.methodDefs + expected.methodDefs.first())),
            compare(expected.copy(methodImpls = expected.methodImpls + expected.methodImpls.first())),
        )

        assertEquals(listOf(conflict, conflict, conflict), comparisons.map { it.status })
        assertTrue(comparisons[0].typeDefs.diagnostics.any { it.contains("duplicate") })
        assertTrue(comparisons[1].methodDefs.diagnostics.single().contains("duplicate"))
        assertTrue(comparisons[2].methodImpls.diagnostics.single().contains("duplicate"))
    }

    @Test
    fun rejectsTypeDefAliasArityAndCategoryDriftUnderTheSameIdentity() {
        val original = expected.typeDefs.first()
        val drifts = listOf(
            original.copy(aliases = listOf(aliasKey(4))),
            original.copy(
                genericArity = 2,
                genericParameters = List(2) {
                    typeParameter(DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT)
                },
            ),
            original.copy(category = DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE),
            original.copy(
                genericParameters = listOf(typeParameter(DotNetGenericOwnerPhysicalTypeParameterVariance.COVARIANT)),
            ),
        )

        for (drift in drifts) {
            val comparison = compare(expected.copy(typeDefs = listOf(drift) + expected.typeDefs.drop(1)))
            assertEquals(conflict, comparison.typeDefs.status)
            assertEquals(conflict, comparison.status)
        }
    }

    @Test
    fun rejectsDirectEdgeDriftAndDuplicateEdges() {
        val original = expected.typeDefs.first()
        val drifted = original.copy(directEdges = listOf(interfaceEdge(construction(typeKey(1)))))
        val wrongKind = original.copy(
            directEdges = listOf(baseEdge(original.directEdges.first().target)) + original.directEdges.drop(1),
        )
        val duplicated = original.copy(directEdges = original.directEdges + original.directEdges.first())

        assertEquals(
            conflict,
            compare(expected.copy(typeDefs = listOf(drifted) + expected.typeDefs.drop(1))).typeDefs.status,
        )
        assertEquals(
            conflict,
            compare(expected.copy(typeDefs = listOf(wrongKind) + expected.typeDefs.drop(1))).typeDefs.status,
        )
        assertEquals(
            conflict,
            compare(expected.copy(typeDefs = listOf(duplicated) + expected.typeDefs.drop(1))).typeDefs.status,
        )
    }

    @Test
    fun rejectsMethodDefHeaderDriftUnderTheSameIdentity() {
        val original = expected.methodDefs.first()
        val drifted = original.copy(header = original.header.copy(result = direct(objectCarrier)))
        val comparison = compare(expected.copy(methodDefs = listOf(drifted) + expected.methodDefs.drop(1)))

        assertEquals(conflict, comparison.methodDefs.status)
        assertEquals(conflict, comparison.status)
    }

    @Test
    fun rejectsSwappedMethodImplEndpointsAndWrongOwners() {
        val original = expected.methodImpls.first()
        val hostileRows = listOf(
            original.copy(
                bodyMethodDefKey = original.declarationMethodDefKey,
                declarationMethodDefKey = original.bodyMethodDefKey,
            ),
            original.copy(implementingTypeDefKey = typeKey(1)),
            original.copy(declarationOwner = construction(typeKey(0), objectCarrier)),
        )

        for (hostile in hostileRows) {
            val comparison = compare(expected.copy(methodImpls = listOf(hostile) + expected.methodImpls.drop(1)))
            assertEquals(conflict, comparison.methodImpls.status)
            assertEquals(conflict, comparison.status)
        }
    }

    @Test
    fun rejectsDuplicateAliasesAcrossPhysicalTypeDefs() {
        val second = expected.typeDefs[1].copy(aliases = listOf(expected.typeDefs.first().aliases.first()))
        val comparison = compare(expected.copy(typeDefs = listOf(expected.typeDefs.first(), second)))

        assertEquals(conflict, comparison.typeDefs.status)
        assertTrue(comparison.typeDefs.diagnostics.single().contains("multiple physical rows"))
    }

    @Test
    fun joinsStatusesFailClosedRegardlessOfOrder() {
        val permutations = listOf(
            listOf(match, unavailable, conflict),
            listOf(conflict, match, unavailable),
            listOf(unavailable, conflict, match),
        )

        assertEquals(
            listOf(conflict, conflict, conflict),
            permutations.map(::joinDotNetGenericOwnerCompleteEmissionComparisonStatuses),
        )
        assertEquals(
            unavailable,
            joinDotNetGenericOwnerCompleteEmissionComparisonStatuses(listOf(match, unavailable)),
        )
        assertEquals(
            match,
            joinDotNetGenericOwnerCompleteEmissionComparisonStatuses(listOf(match, match)),
        )
    }

    @Test
    fun explicitConflictDominatesUnavailableAndMissingFacts() {
        val evidence = DotNetGenericOwnerCompleteEmissionManifestEvidence(
            typeDefs = DotNetGenericOwnerCompleteEmissionRowsEvidence.Unavailable("TypeDef capture was unavailable"),
            methodDefs = DotNetGenericOwnerCompleteEmissionRowsEvidence.Conflict("MethodDef capture conflicted"),
            methodImpls = DotNetGenericOwnerCompleteEmissionRowsEvidence.Known(emptyList()),
        )

        val comparison = compareDotNetGenericOwnerCompleteEmissionManifest(expected, evidence)

        assertEquals(unavailable, comparison.typeDefs.status)
        assertEquals(conflict, comparison.methodDefs.status)
        assertEquals(unavailable, comparison.methodImpls.status)
        assertEquals(conflict, comparison.status)
    }

    private fun compare(actual: DotNetGenericOwnerCompleteEmissionManifest) =
        compareDotNetGenericOwnerCompleteEmissionManifest(expected, actual.asKnownEvidence())

    private fun typeDef(
        identity: DotNetGenericOwnerPhysicalMethodDefEmissionTypeKey,
        aliases: List<DotNetGenericOwnerCompleteEmissionTypeDefAliasKey>,
        arity: Int,
        category: DotNetGenericOwnerPhysicalNamedTypeCategory,
        genericParameters: List<DotNetGenericOwnerCompleteEmissionTypeParameterRow> =
            List(arity) { typeParameter(DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT) },
        edges: List<DotNetGenericOwnerCompleteEmissionTypeDefEdgeRow> = emptyList(),
    ) = DotNetGenericOwnerCompleteEmissionTypeDefRow(
        identity,
        aliases,
        arity,
        category,
        genericParameters,
        edges,
    )

    private fun methodDef(
        identity: DotNetGenericOwnerPhysicalMethodDefEmissionMethodKey,
        header: DotNetGenericOwnerPhysicalMethodDefEmissionHeaderShape,
    ) = DotNetGenericOwnerCompleteEmissionMethodDefRow(identity, header)

    private fun methodImpl(
        implementingType: DotNetGenericOwnerPhysicalMethodDefEmissionTypeKey,
        body: DotNetGenericOwnerPhysicalMethodDefEmissionMethodKey,
        declarationOwner: DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape,
        declaration: DotNetGenericOwnerPhysicalMethodDefEmissionMethodKey,
    ) = DotNetGenericOwnerCompleteEmissionMethodImplRow(
        implementingType,
        body,
        declarationOwner,
        declaration,
    )

    private fun header(
        owner: DotNetGenericOwnerPhysicalMethodDefEmissionTypeKey,
        result: DotNetGenericOwnerPhysicalMethodDefEmissionResultShape = direct(objectCarrier),
    ) = DotNetGenericOwnerPhysicalMethodDefEmissionHeaderShape(
        owner = owner,
        ownerGenericArity = if (owner == typeKey(0)) 1 else 0,
        ownerCategory = DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS,
        visibility = DotNetGenericOwnerPhysicalMethodDefEmissionVisibility.PUBLIC,
        dispatch = DotNetGenericOwnerPhysicalMemberDispatch.FINAL,
        isInstance = true,
        genericArity = 0,
        receiverCarrier = if (owner == typeKey(0)) {
            construction(owner, ownerParameter)
        } else {
            construction(owner)
        },
        ordinaryParameterCarriers = emptyList(),
        result = result,
    )

    private fun direct(carrier: DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape) =
        DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.Direct(carrier)

    private fun construction(
        definition: DotNetGenericOwnerPhysicalMethodDefEmissionTypeKey,
        vararg arguments: DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape,
    ) = DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.Construction(definition, arguments.toList())

    private fun interfaceEdge(target: DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape) =
        DotNetGenericOwnerCompleteEmissionTypeDefEdgeRow(
            DotNetGenericOwnerDirectSupertypeKind.INTERFACE,
            target,
        )

    private fun baseEdge(target: DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape) =
        DotNetGenericOwnerCompleteEmissionTypeDefEdgeRow(
            DotNetGenericOwnerDirectSupertypeKind.BASE_CLASS,
            target,
        )

    private fun typeParameter(
        variance: DotNetGenericOwnerPhysicalTypeParameterVariance,
        vararg constraints: DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape,
    ) = DotNetGenericOwnerCompleteEmissionTypeParameterRow(variance, constraints.toList())

    private fun typeKey(value: Int) = DotNetGenericOwnerPhysicalMethodDefEmissionTypeKey(value)
    private fun aliasKey(value: Int) = DotNetGenericOwnerCompleteEmissionTypeDefAliasKey(value)
    private fun methodKey(value: Int) = DotNetGenericOwnerPhysicalMethodDefEmissionMethodKey(value)

    private val match = DotNetGenericOwnerPhysicalMethodDefEmissionComparisonStatus.MATCH
    private val unavailable = DotNetGenericOwnerPhysicalMethodDefEmissionComparisonStatus.UNAVAILABLE
    private val conflict = DotNetGenericOwnerPhysicalMethodDefEmissionComparisonStatus.CONFLICT
    private val objectCarrier = DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.Leaf(
        DotNetGenericOwnerPhysicalTypeKind.OBJECT,
    )
    private val ownerParameter = DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.OwnerParameter(
        typeKey(0),
        index = 0,
    )
    private val expected = DotNetGenericOwnerCompleteEmissionManifest(
        typeDefs = listOf(
            typeDef(
                identity = typeKey(0),
                aliases = listOf(aliasKey(0), aliasKey(1)),
                arity = 1,
                category = DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS,
                edges = listOf(interfaceEdge(construction(typeKey(1))), baseEdge(objectCarrier)),
            ),
            typeDef(
                identity = typeKey(1),
                aliases = listOf(aliasKey(2)),
                arity = 0,
                category = DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS,
            ),
        ),
        methodDefs = listOf(
            methodDef(methodKey(0), header(typeKey(0), direct(ownerParameter))),
            methodDef(methodKey(1), header(typeKey(1))),
        ),
        methodImpls = listOf(
            methodImpl(typeKey(0), methodKey(0), construction(typeKey(1)), methodKey(1)),
            methodImpl(typeKey(1), methodKey(1), construction(typeKey(0), objectCarrier), methodKey(0)),
        ),
    )
}
