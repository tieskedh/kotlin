/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DotNetProducerGenericOwnerSealedFamilyTest {
    @Test
    fun rejectsEmptyOrNulBearingLogicalKeys() {
        assertFailsWith<IllegalArgumentException> {
            DotNetProducerGenericOwnerSealedFamilyKey("", "demo/Store|class", "demo/Store.read|function")
        }
        assertFailsWith<IllegalArgumentException> {
            DotNetProducerGenericOwnerSealedFamilyKey(
                "demo/Source.read|function\u0000demo/Store|class",
                "demo/Store|class",
                "demo/Store.read|function",
            )
        }
    }

    @Test
    fun deterministicallyRoundTripsEverySupportedPhysicalRowAndLogicalDomain() {
        val publication = producerSealedFamilyPublicationFixture()

        val first = DotNetProducerGenericOwnerSealedFamilyCodec.encode(publication)
        val second = DotNetProducerGenericOwnerSealedFamilyCodec.encode(publication)
        assertContentEquals(first, second)

        val decoded = assertIs<DotNetProducerGenericOwnerSealedFamilyDecodeResult.Success>(
            DotNetProducerGenericOwnerSealedFamilyCodec.decode(first),
        ).publication
        assertEquals(publication, decoded)

        val authority = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<*>>(
            inspectDotNetProducerGenericOwnerSealedFamily(decoded),
        ).value as DotNetProducerGenericOwnerSealedFamilyAuthority
        assertEquals(DotNetGenericOwnerPhysicalAuthorityEpoch.SEALED_EMISSION_SIGNATURE_INDEX, authority.epoch)
        val implementation = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<*>>(
            authority.typeDef(DotNetProducerGenericOwnerSealedTypeDefRole.IMPLEMENTATION_CLASS),
        ).value as DotNetGenericOwnerSealedEmissionTypeDefRow
        assertEquals(listOf("demo.Store`1"), implementation.physicalPath)
        assertTrue(implementation.flags.isSealed)
        assertEquals(4, implementation.structural.directEdges.size)

        val natural = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<*>>(
            authority.methodDef(DotNetProducerGenericOwnerSealedMethodDefRole.NATURAL_INTERFACE_SLOT),
        ).value as DotNetGenericOwnerSealedEmissionMethodDefRow
        assertEquals("Read", natural.physicalName)
        assertEquals(listOf("R"), natural.physicalGenericParameterNames)
        assertIs<DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.SplitNullable>(
            natural.structural.header.result,
        )
        assertEquals(
            DotNetProducerGenericOwnerSealedMethodImplRole.entries.map { role -> authority.methodImpl(role) },
            decoded.body.methodImpls.map { row -> row.row },
        )
        assertTrue(decoded.body.methodDefs.all { row ->
            row.logicalParameterDomains ==
                    listOf(DotNetGenericOwnerPhysicalSlotDomain.DECLARATION_INDEPENDENT) &&
                    row.logicalResultDomain == DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_OUTPUT
        })
    }

    @Test
    fun canonicalizesPhysicallyEquivalentSetOrdering() {
        val publication = producerSealedFamilyPublicationFixture()
        val permuted = publication.copy(body = publication.body.copy(
            typeDefs = publication.body.typeDefs.asReversed().map { typeDef ->
                typeDef.copy(row = typeDef.row.copy(
                    structural = typeDef.row.structural.copy(
                        aliases = typeDef.row.structural.aliases.asReversed(),
                        directEdges = typeDef.row.structural.directEdges.asReversed(),
                    ),
                ))
            },
            methodDefs = publication.body.methodDefs.asReversed(),
            methodImpls = publication.body.methodImpls.asReversed(),
        ))

        assertContentEquals(
            DotNetProducerGenericOwnerSealedFamilyCodec.encode(publication),
            DotNetProducerGenericOwnerSealedFamilyCodec.encode(permuted),
        )
    }

    @Test
    fun canonicalizesBijectivelyRenumberedFamilyLocalIdentities() {
        val publication = producerSealedFamilyPublicationFixture()
        val renumbered = publication.withBijectivelyRenumberedPhysicalKeys()

        assertContentEquals(
            DotNetProducerGenericOwnerSealedFamilyCodec.encode(publication),
            DotNetProducerGenericOwnerSealedFamilyCodec.encode(renumbered),
        )
    }

    @Test
    fun rejectsTruncationUnknownHeaderAndTrailingBytes() {
        val encoded = DotNetProducerGenericOwnerSealedFamilyCodec.encode(
            producerSealedFamilyPublicationFixture(),
        )

        assertIs<DotNetProducerGenericOwnerSealedFamilyDecodeResult.Malformed>(
            DotNetProducerGenericOwnerSealedFamilyCodec.decode(encoded.dropLast(1).toByteArray()),
        )
        assertIs<DotNetProducerGenericOwnerSealedFamilyDecodeResult.Malformed>(
            DotNetProducerGenericOwnerSealedFamilyCodec.decode(encoded.copyOf(encoded.size / 2)),
        )
        assertIs<DotNetProducerGenericOwnerSealedFamilyDecodeResult.Malformed>(
            DotNetProducerGenericOwnerSealedFamilyCodec.decode(encoded.copyOf().also { bytes -> bytes[0] = 0 }),
        )
        assertIs<DotNetProducerGenericOwnerSealedFamilyDecodeResult.Malformed>(
            DotNetProducerGenericOwnerSealedFamilyCodec.decode(encoded + byteArrayOf(0)),
        )
    }

    @Test
    fun rejectsMissingAndDuplicateRoleRowsAtomically() {
        val publication = producerSealedFamilyPublicationFixture()
        val missing = publication.copy(body = publication.body.copy(
            methodImpls = publication.body.methodImpls.dropLast(1),
        ))
        val duplicate = publication.copy(body = publication.body.copy(
            typeDefs = publication.body.typeDefs.dropLast(1) + publication.body.typeDefs.first().copy(
                row = publication.body.typeDefs.last().row,
            ),
        ))

        val missingConflict = assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            inspectDotNetProducerGenericOwnerSealedFamily(missing),
        )
        assertTrue(missingConflict.reason.contains("every MethodImpl role"))
        val duplicateConflict = assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            inspectDotNetProducerGenericOwnerSealedFamily(duplicate),
        )
        assertTrue(duplicateConflict.reason.contains("every TypeDef role"))
        assertFailsWith<IllegalArgumentException> {
            DotNetProducerGenericOwnerSealedFamilyCodec.encode(missing)
        }
        assertIs<DotNetGenericOwnerPhysicalBindingResult.Unavailable>(
            inspectDotNetProducerGenericOwnerSealedFamily(null),
        )
    }

    @Test
    fun rejectsDuplicatePhysicalRowsAndCrossMethodBinders() {
        val publication = producerSealedFamilyPublicationFixture()
        val duplicatePath = publication.copy(body = publication.body.copy(
            typeDefs = publication.body.typeDefs.mapIndexed { index, type ->
                if (index == 1) type.copy(row = type.row.copy(
                    physicalPath = publication.body.typeDefs.first().row.physicalPath,
                )) else type
            },
        ))
        val duplicateConflict = assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            inspectDotNetProducerGenericOwnerSealedFamily(duplicatePath),
        )
        assertTrue(duplicateConflict.reason.contains("TypeDef path"))

        val natural = publication.body.methodDefs.first { row ->
            row.role == DotNetProducerGenericOwnerSealedMethodDefRole.NATURAL_INTERFACE_SLOT
        }
        val sibling = publication.body.methodDefs.first { row ->
            row.role == DotNetProducerGenericOwnerSealedMethodDefRole.INTERFACE_SEMANTIC_CAPABILITY_SLOT
        }
        val hostileParameter = DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.MethodParameter(
            sibling.row.structural.identityKey,
            0,
        )
        val crossBinder = publication.copy(body = publication.body.copy(
            methodDefs = publication.body.methodDefs.map { method ->
                if (method.role == natural.role) method.copy(row = method.row.copy(
                    structural = method.row.structural.copy(
                        header = method.row.structural.header.copy(
                            ordinaryParameterCarriers = listOf(hostileParameter),
                        ),
                    ),
                )) else method
            },
        ))
        val binderConflict = assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            inspectDotNetProducerGenericOwnerSealedFamily(crossBinder),
        )
        assertTrue(binderConflict.reason.contains("parameter grammar"))
    }

    @Test
    fun rejectsNonConstructionMethodImplOwnerAsAConflict() {
        val publication = producerSealedFamilyPublicationFixture()
        val hostile = publication.copy(body = publication.body.copy(
            methodImpls = publication.body.methodImpls.mapIndexed { index, methodImpl ->
                if (index == 0) methodImpl.copy(row = methodImpl.row.copy(
                    declarationOwner = DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.Leaf(
                        DotNetGenericOwnerPhysicalTypeKind.OBJECT,
                    ),
                )) else methodImpl
            },
        ))

        val conflict = assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            inspectDotNetProducerGenericOwnerSealedFamily(hostile),
        )
        assertTrue(conflict.reason.contains("MethodImpl endpoints"))
        assertFailsWith<IllegalArgumentException> {
            DotNetProducerGenericOwnerSealedFamilyCodec.encode(hostile)
        }
    }

    @Test
    fun requiresTheNaturalAndTypedEntryToShareTheirResultLayout() {
        val publication = producerSealedFamilyPublicationFixture()
        val typedRole = DotNetProducerGenericOwnerSealedMethodDefRole.IMPLEMENTATION_TYPED_ENTRY
        val hostile = publication.copy(body = publication.body.copy(
            methodDefs = publication.body.methodDefs.map { method ->
                if (method.role == typedRole) method.copy(row = method.row.copy(
                    structural = method.row.structural.copy(
                        header = method.row.structural.header.copy(
                            result = DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.Direct(
                                (method.row.structural.header.result as
                                        DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.SplitNullable).payload,
                            ),
                        ),
                    ),
                )) else method
            },
        ))

        val conflict = assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            inspectDotNetProducerGenericOwnerSealedFamily(hostile),
        )
        assertTrue(conflict.reason.contains("matching natural and typed-entry result layouts"))
    }

    @Test
    fun requiresSemanticMethodsToReturnObjectDirectly() {
        val publication = producerSealedFamilyPublicationFixture()
        val semanticRole = DotNetProducerGenericOwnerSealedMethodDefRole
            .INTERFACE_SEMANTIC_CAPABILITY_SLOT
        val hostile = publication.copy(body = publication.body.copy(
            methodDefs = publication.body.methodDefs.map { method ->
                if (method.role == semanticRole) method.copy(row = method.row.copy(
                    structural = method.row.structural.copy(
                        header = method.row.structural.header.copy(
                            result = DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.SplitNullable(
                                DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.Leaf(
                                    DotNetGenericOwnerPhysicalTypeKind.OBJECT,
                                ),
                            ),
                        ),
                    ),
                )) else method
            },
        ))

        val conflict = assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            inspectDotNetProducerGenericOwnerSealedFamily(hostile),
        )
        assertTrue(conflict.reason.contains("semantic MethodDef to return object directly"))
    }

    @Test
    fun rejectsRoleFlagAndReceiverMutationsIndependentlyOfSelfConsistentRows() {
        val publication = producerSealedFamilyPublicationFixture()
        val unsealedImplementation = publication.copy(body = publication.body.copy(
            typeDefs = publication.body.typeDefs.map { type ->
                if (type.role == DotNetProducerGenericOwnerSealedTypeDefRole.IMPLEMENTATION_CLASS) {
                    type.copy(row = type.row.copy(flags = type.row.flags.copy(
                        isSealed = false,
                    )))
                } else {
                    type
                }
            },
        ))
        val typeConflict = assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            inspectDotNetProducerGenericOwnerSealedFamily(unsealedImplementation),
        )
        assertTrue(typeConflict.reason.contains("bounded TypeDef flags"))

        val naturalRole = DotNetProducerGenericOwnerSealedMethodDefRole.NATURAL_INTERFACE_SLOT
        val nonHideBySig = publication.copy(body = publication.body.copy(
            methodDefs = publication.body.methodDefs.map { method ->
                if (method.role == naturalRole) {
                    method.copy(row = method.row.copy(isHideBySig = false))
                } else {
                    method
                }
            },
        ))
        val flagConflict = assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            inspectDotNetProducerGenericOwnerSealedFamily(nonHideBySig),
        )
        assertTrue(flagConflict.reason.contains("bounded role flags and receiver"))

        val wrongReceiver = publication.copy(body = publication.body.copy(
            methodDefs = publication.body.methodDefs.map { method ->
                if (method.role == naturalRole) method.copy(row = method.row.copy(
                    structural = method.row.structural.copy(
                        header = method.row.structural.header.copy(
                            receiverCarrier = DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.Leaf(
                                DotNetGenericOwnerPhysicalTypeKind.OBJECT,
                            ),
                        ),
                    ),
                )) else method
            },
        ))
        val receiverConflict = assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            inspectDotNetProducerGenericOwnerSealedFamily(wrongReceiver),
        )
        assertTrue(receiverConflict.reason.contains("bounded role flags and receiver"))

        val staticNatural = publication.copy(body = publication.body.copy(
            methodDefs = publication.body.methodDefs.map { method ->
                if (method.role == naturalRole) method.copy(row = method.row.copy(
                    structural = method.row.structural.copy(
                        header = method.row.structural.header.copy(
                            isInstance = false,
                            receiverCarrier = null,
                        ),
                    ),
                    dispatch = method.row.dispatch.copy(isInstance = false),
                )) else method
            },
        ))
        val instanceConflict = assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            inspectDotNetProducerGenericOwnerSealedFamily(staticNatural),
        )
        assertTrue(instanceConflict.reason.contains("bounded role flags and receiver"))
    }

    @Test
    fun rejectsIncompatibleSignaturesAtBothMethodImpls() {
        val publication = producerSealedFamilyPublicationFixture()
        val declarationRoles = mapOf(
            DotNetProducerGenericOwnerSealedMethodImplRole.CLASS_SEMANTIC_CAPABILITY_IMPLEMENTATION to
                    DotNetProducerGenericOwnerSealedMethodDefRole.CLASS_SEMANTIC_CAPABILITY_SLOT,
            DotNetProducerGenericOwnerSealedMethodImplRole.INTERFACE_SEMANTIC_CAPABILITY_IMPLEMENTATION to
                    DotNetProducerGenericOwnerSealedMethodDefRole.INTERFACE_SEMANTIC_CAPABILITY_SLOT,
        )
        val objectCarrier = DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.Leaf(
            DotNetGenericOwnerPhysicalTypeKind.OBJECT,
        )
        declarationRoles.forEach { [methodImplRole, declarationRole] ->
            val hostile = publication.copy(body = publication.body.copy(
                methodDefs = publication.body.methodDefs.map { method ->
                    if (method.role == declarationRole) method.copy(row = method.row.copy(
                        structural = method.row.structural.copy(
                            header = method.row.structural.header.copy(
                                result = DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.SplitNullable(
                                    objectCarrier,
                                ),
                            ),
                        ),
                    )) else method
                },
            ))

            val conflict = assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
                inspectDotNetProducerGenericOwnerSealedFamily(hostile),
            )
            assertTrue(
                conflict.reason.contains(
                    "a producer-sealed $methodImplRole MethodImpl has incompatible body and declaration signatures",
                ),
                conflict.reason,
            )
        }
    }

    @Test
    fun rejectsUnsupportedCarriersInsteadOfSerializingAnObjectApproximation() {
        val publication = producerSealedFamilyPublicationFixture()
        val natural = publication.body.methodDefs.first()
        val unsupported = publication.copy(body = publication.body.copy(
            methodDefs = publication.body.methodDefs.map { method ->
                if (method.role == natural.role) method.copy(row = method.row.copy(
                    structural = method.row.structural.copy(
                        header = method.row.structural.header.copy(
                            result = DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.Direct(
                                DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.Other,
                            ),
                        ),
                    ),
                )) else method
            },
        ))

        val conflict = assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            inspectDotNetProducerGenericOwnerSealedFamily(unsupported),
        )
        assertTrue(conflict.reason.contains("result grammar"))
        assertFailsWith<IllegalArgumentException> {
            DotNetProducerGenericOwnerSealedFamilyCodec.encode(unsupported)
        }
    }
}

/** Shared exact producer fixture for the physical-ABI envelope tests in this package. */
internal fun producerSealedFamilyPublicationFixture():
        DotNetProducerGenericOwnerSealedFamilyPublication {
    fun typeKey(value: Int) = DotNetGenericOwnerPhysicalMethodDefEmissionTypeKey(value)
    fun methodKey(value: Int) = DotNetGenericOwnerPhysicalMethodDefEmissionMethodKey(value)
    fun aliasKey(value: Int) = DotNetGenericOwnerCompleteEmissionTypeDefAliasKey(value)
    fun objectCarrier() = DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.Leaf(
        DotNetGenericOwnerPhysicalTypeKind.OBJECT,
    )
    fun ownerParameter(type: Int) = DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.OwnerParameter(
        typeKey(type),
        0,
    )
    fun methodParameter(method: Int) = DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.MethodParameter(
        methodKey(method),
        0,
    )
    fun construction(
        type: Int,
        vararg arguments: DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape,
    ) = DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.Construction(
        typeKey(type),
        arguments.toList(),
    )
    fun genericParameter(
        variance: DotNetGenericOwnerPhysicalTypeParameterVariance,
        vararg constraints: DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape,
    ) = DotNetGenericOwnerCompleteEmissionGenericParameterRow(variance, constraints.toList())
    fun typeDef(
        role: DotNetProducerGenericOwnerSealedTypeDefRole,
        key: Int,
        aliases: List<Int>,
        path: String,
        category: DotNetGenericOwnerPhysicalNamedTypeCategory,
        genericParameters: List<DotNetGenericOwnerCompleteEmissionGenericParameterRow>,
        edges: List<DotNetGenericOwnerCompleteEmissionTypeDefEdgeRow>,
    ): DotNetProducerGenericOwnerSealedTypeDef {
        val isInterface = category == DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE
        return DotNetProducerGenericOwnerSealedTypeDef(
            role,
            DotNetGenericOwnerSealedEmissionTypeDefRow(
                DotNetGenericOwnerCompleteEmissionTypeDefRow(
                    typeKey(key),
                    aliases.map { alias -> aliasKey(alias) },
                    genericParameters.size,
                    category,
                    genericParameters,
                    edges,
                ),
                listOf(path),
                DotNetIlRawTypeDefFlags(
                    if (role == DotNetProducerGenericOwnerSealedTypeDefRole.IMPLEMENTATION_CLASS) {
                        DotNetIlRawTypeDefVisibility.NOT_PUBLIC
                    } else {
                        DotNetIlRawTypeDefVisibility.PUBLIC
                    },
                    DotNetIlRawTypeDefLayout.AUTO,
                    DotNetIlRawTypeDefStringFormat.ANSI,
                    isInterface,
                    isInterface,
                    !isInterface,
                    !isInterface,
                ),
            ),
        )
    }
    val objectCarrier = objectCarrier()
    val types = listOf(
        typeDef(
            DotNetProducerGenericOwnerSealedTypeDefRole.NATURAL_INTERFACE,
            0,
            listOf(0, 1),
            "demo.Source`1",
            DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE,
            listOf(genericParameter(DotNetGenericOwnerPhysicalTypeParameterVariance.COVARIANT)),
            emptyList(),
        ),
        typeDef(
            DotNetProducerGenericOwnerSealedTypeDefRole.INTERFACE_SEMANTIC_CAPABILITY,
            1,
            listOf(2),
            "demo.SourceSemantic",
            DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE,
            emptyList(),
            emptyList(),
        ),
        typeDef(
            DotNetProducerGenericOwnerSealedTypeDefRole.IMPLEMENTATION_CLASS,
            2,
            listOf(3),
            "demo.Store`1",
            DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS,
            listOf(genericParameter(DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT)),
            listOf(
                DotNetGenericOwnerCompleteEmissionTypeDefEdgeRow(
                    DotNetGenericOwnerDirectSupertypeKind.BASE_CLASS,
                    objectCarrier,
                ),
                DotNetGenericOwnerCompleteEmissionTypeDefEdgeRow(
                    DotNetGenericOwnerDirectSupertypeKind.INTERFACE,
                    construction(0, ownerParameter(2)),
                ),
                DotNetGenericOwnerCompleteEmissionTypeDefEdgeRow(
                    DotNetGenericOwnerDirectSupertypeKind.INTERFACE,
                    construction(1),
                ),
                DotNetGenericOwnerCompleteEmissionTypeDefEdgeRow(
                    DotNetGenericOwnerDirectSupertypeKind.INTERFACE,
                    construction(3),
                ),
            ),
        ),
        typeDef(
            DotNetProducerGenericOwnerSealedTypeDefRole.CLASS_SEMANTIC_CAPABILITY,
            3,
            listOf(4),
            "demo.StoreSemantic",
            DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE,
            emptyList(),
            listOf(DotNetGenericOwnerCompleteEmissionTypeDefEdgeRow(
                DotNetGenericOwnerDirectSupertypeKind.INTERFACE,
                construction(1),
            )),
        ),
    )

    fun method(
        role: DotNetProducerGenericOwnerSealedMethodDefRole,
        key: Int,
        owner: Int,
        ownerCategory: DotNetGenericOwnerPhysicalNamedTypeCategory,
        physicalName: String,
        result: DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape,
        visibility: DotNetGenericOwnerPhysicalMethodDefEmissionVisibility,
        rawVisibility: DotNetIlRawMethodDefVisibility,
        dispatch: DotNetGenericOwnerPhysicalMemberDispatch,
        rawDispatch: DotNetIlRawMethodDefDispatch,
    ): DotNetProducerGenericOwnerSealedMethodDef {
        val ownerArity = types.single { type -> type.row.structural.identityKey == typeKey(owner) }
            .row.structural.genericArity
        val receiver = construction(
            owner,
            *(0 until ownerArity).map { ownerParameter(owner) }.toTypedArray(),
        )
        val structural = DotNetGenericOwnerCompleteEmissionMethodDefRow(
            methodKey(key),
            DotNetGenericOwnerPhysicalMethodDefEmissionHeaderShape(
                typeKey(owner),
                ownerArity,
                ownerCategory,
                visibility,
                dispatch,
                true,
                1,
                receiver,
                listOf(methodParameter(key)),
                if (role == DotNetProducerGenericOwnerSealedMethodDefRole.NATURAL_INTERFACE_SLOT ||
                    role == DotNetProducerGenericOwnerSealedMethodDefRole.IMPLEMENTATION_TYPED_ENTRY
                ) {
                    DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.SplitNullable(result)
                } else {
                    DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.Direct(result)
                },
            ),
            listOf(genericParameter(DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT)),
        )
        return DotNetProducerGenericOwnerSealedMethodDef(
            role,
            DotNetGenericOwnerSealedEmissionMethodDefRow(
                structural,
                physicalName,
                listOf("R"),
                rawVisibility,
                rawDispatch,
                isHideBySig = true,
                isSpecialName = false,
                isRuntimeSpecialName = false,
            ),
            listOf(DotNetGenericOwnerPhysicalSlotDomain.DECLARATION_INDEPENDENT),
            DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_OUTPUT,
        )
    }
    val abstractDispatch = DotNetIlRawMethodDefDispatch(true, true, true, true, false)
    val overrideDispatch = DotNetIlRawMethodDefDispatch(true, true, true, false, false)
    val finalDispatch = DotNetIlRawMethodDefDispatch(true, true, true, false, true)
    val methods = listOf(
        method(
            DotNetProducerGenericOwnerSealedMethodDefRole.NATURAL_INTERFACE_SLOT,
            0, 0, DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE, "Read", ownerParameter(0),
            DotNetGenericOwnerPhysicalMethodDefEmissionVisibility.PUBLIC,
            DotNetIlRawMethodDefVisibility.PUBLIC,
            DotNetGenericOwnerPhysicalMemberDispatch.ABSTRACT,
            abstractDispatch,
        ),
        method(
            DotNetProducerGenericOwnerSealedMethodDefRole.INTERFACE_SEMANTIC_CAPABILITY_SLOT,
            1, 1, DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE, "ReadSemantic", objectCarrier,
            DotNetGenericOwnerPhysicalMethodDefEmissionVisibility.PUBLIC,
            DotNetIlRawMethodDefVisibility.PUBLIC,
            DotNetGenericOwnerPhysicalMemberDispatch.ABSTRACT,
            abstractDispatch,
        ),
        method(
            DotNetProducerGenericOwnerSealedMethodDefRole.IMPLEMENTATION_TYPED_ENTRY,
            2, 2, DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS, "Read", ownerParameter(2),
            DotNetGenericOwnerPhysicalMethodDefEmissionVisibility.PUBLIC,
            DotNetIlRawMethodDefVisibility.PUBLIC,
            DotNetGenericOwnerPhysicalMemberDispatch.OVERRIDABLE,
            overrideDispatch,
        ),
        method(
            DotNetProducerGenericOwnerSealedMethodDefRole.CLASS_SEMANTIC_CAPABILITY_SLOT,
            3, 3, DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE, "ReadClassSemantic", objectCarrier,
            DotNetGenericOwnerPhysicalMethodDefEmissionVisibility.PUBLIC,
            DotNetIlRawMethodDefVisibility.PUBLIC,
            DotNetGenericOwnerPhysicalMemberDispatch.ABSTRACT,
            abstractDispatch,
        ),
        method(
            DotNetProducerGenericOwnerSealedMethodDefRole.CLASS_SEMANTIC_CAPABILITY_DISPATCHER,
            4, 2, DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS, "ReadClassDispatch", objectCarrier,
            DotNetGenericOwnerPhysicalMethodDefEmissionVisibility.PRIVATE,
            DotNetIlRawMethodDefVisibility.PRIVATE,
            DotNetGenericOwnerPhysicalMemberDispatch.FINAL,
            finalDispatch,
        ),
        method(
            DotNetProducerGenericOwnerSealedMethodDefRole.INTERFACE_SEMANTIC_CAPABILITY_DISPATCHER,
            5, 2, DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS, "ReadInterfaceDispatch", objectCarrier,
            DotNetGenericOwnerPhysicalMethodDefEmissionVisibility.PRIVATE,
            DotNetIlRawMethodDefVisibility.PRIVATE,
            DotNetGenericOwnerPhysicalMemberDispatch.FINAL,
            finalDispatch,
        ),
    )
    fun methodImpl(
        role: DotNetProducerGenericOwnerSealedMethodImplRole,
        body: Int,
        declarationOwner: Int,
        declaration: Int,
    ) = DotNetProducerGenericOwnerSealedMethodImpl(
        role,
        DotNetGenericOwnerCompleteEmissionMethodImplRow(
            typeKey(2),
            methodKey(body),
            construction(declarationOwner),
            methodKey(declaration),
        ),
    )
    return DotNetProducerGenericOwnerSealedFamilyPublication(
        DotNetProducerGenericOwnerSealedFamilyKey(
            "demo/Source.read|function",
            "demo/Store|class",
            "demo/Store.read|function",
        ),
        DotNetProducerGenericOwnerSealedFamilyBody(
            types,
            methods,
            listOf(
                methodImpl(
                    DotNetProducerGenericOwnerSealedMethodImplRole.CLASS_SEMANTIC_CAPABILITY_IMPLEMENTATION,
                    4, 3, 3,
                ),
                methodImpl(
                    DotNetProducerGenericOwnerSealedMethodImplRole.INTERFACE_SEMANTIC_CAPABILITY_IMPLEMENTATION,
                    5, 1, 1,
                ),
            ),
        ),
    )
}

private fun DotNetProducerGenericOwnerSealedFamilyPublication.withBijectivelyRenumberedPhysicalKeys():
        DotNetProducerGenericOwnerSealedFamilyPublication {
    val typeKeys = body.typeDefs.associate { type ->
        type.row.structural.identityKey to DotNetGenericOwnerPhysicalMethodDefEmissionTypeKey(
            101 + (DotNetProducerGenericOwnerSealedTypeDefRole.entries.lastIndex - type.role.ordinal) * 17,
        )
    }
    val methodKeys = body.methodDefs.associate { method ->
        method.row.structural.identityKey to DotNetGenericOwnerPhysicalMethodDefEmissionMethodKey(
            503 + (DotNetProducerGenericOwnerSealedMethodDefRole.entries.lastIndex - method.role.ordinal) * 19,
        )
    }
    val originalAliases = body.typeDefs.flatMap { type -> type.row.structural.aliases }
        .distinct()
        .sortedBy { alias -> alias.value }
    val aliasKeys = originalAliases.mapIndexed { index, alias ->
        alias to DotNetGenericOwnerCompleteEmissionTypeDefAliasKey(
            1_003 + (originalAliases.lastIndex - index) * 23,
        )
    }.toMap()

    fun remapCarrier(
        carrier: DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape,
    ): DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape = when (carrier) {
        is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.Leaf -> carrier
        is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.OwnerParameter -> carrier.copy(
            binder = typeKeys.getValue(carrier.binder),
        )
        is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.MethodParameter -> carrier.copy(
            binder = methodKeys.getValue(carrier.binder),
        )
        is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.Construction -> carrier.copy(
            definition = typeKeys.getValue(carrier.definition),
            arguments = carrier.arguments.map(::remapCarrier),
        )
        is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.SzArray -> carrier.copy(
            element = remapCarrier(carrier.element),
        )
        is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.ByReference -> carrier.copy(
            element = remapCarrier(carrier.element),
        )
        DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.Other -> carrier
    }
    fun remapParameter(
        parameter: DotNetGenericOwnerCompleteEmissionGenericParameterRow,
    ) = parameter.copy(constraints = parameter.constraints.map(::remapCarrier))
    fun remapResult(
        result: DotNetGenericOwnerPhysicalMethodDefEmissionResultShape,
    ): DotNetGenericOwnerPhysicalMethodDefEmissionResultShape = when (result) {
        DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.Void -> result
        is DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.Direct ->
            result.copy(carrier = remapCarrier(result.carrier))
        is DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.SplitNullable ->
            result.copy(payload = remapCarrier(result.payload))
    }

    return copy(body = body.copy(
        typeDefs = body.typeDefs.map { type ->
            val structural = type.row.structural
            type.copy(row = type.row.copy(structural = structural.copy(
                identityKey = typeKeys.getValue(structural.identityKey),
                aliases = structural.aliases.map { alias -> aliasKeys.getValue(alias) },
                genericParameters = structural.genericParameters.map(::remapParameter),
                directEdges = structural.directEdges.map { edge ->
                    edge.copy(target = remapCarrier(edge.target))
                },
            )))
        },
        methodDefs = body.methodDefs.map { method ->
            val structural = method.row.structural
            val header = structural.header
            method.copy(row = method.row.copy(structural = structural.copy(
                identityKey = methodKeys.getValue(structural.identityKey),
                header = header.copy(
                    owner = typeKeys.getValue(header.owner),
                    receiverCarrier = header.receiverCarrier?.let(::remapCarrier),
                    ordinaryParameterCarriers = header.ordinaryParameterCarriers.map(::remapCarrier),
                    result = remapResult(header.result),
                ),
                genericParameters = structural.genericParameters.map(::remapParameter),
            )))
        },
        methodImpls = body.methodImpls.map { methodImpl ->
            val row = methodImpl.row
            methodImpl.copy(row = row.copy(
                implementingTypeDefKey = typeKeys.getValue(row.implementingTypeDefKey),
                bodyMethodDefKey = methodKeys.getValue(row.bodyMethodDefKey),
                declarationOwner = remapCarrier(row.declarationOwner),
                declarationMethodDefKey = methodKeys.getValue(row.declarationMethodDefKey),
            ))
        },
    ))
}
