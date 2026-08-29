/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import java.util.Base64
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DotNetProducerGenericOwnerSealedFamilyLibraryAbiTest {
    @Test
    fun deterministicallyRoundTripsTheCompleteActualOnlyProducerIndex() {
        val publication = producerSealedFamilyPublicationFixture()
        val declarations = producerSealedFamilyAbiFixture(publication)

        val first = DotNetLibraryAbiCodec.encode(declarations)
        val second = DotNetLibraryAbiCodec.encode(declarations.toList().asReversed().toMap())
        assertEquals(first, second)
        assertEquals("63", DotNetLibraryAbiCodec.ABI_VERSION)

        val decoded = DotNetLibraryAbiCodec.decode(first.toProperties())
        assertEquals(declarations, decoded)
        val decodedFamily = decoded.values
            .filterIsInstance<DotNetPhysicalDeclaration.GenericOwnerSealedFamily>()
            .single()
        assertEquals(publication, decodedFamily.publication())
        assertEquals(listOf("demo.Store`1"), decodedFamily.ownerPath)
        val naturalMethod = decoded.values
            .filterIsInstance<DotNetPhysicalDeclaration.GenericOwnerNaturalMethodDef>()
            .single()
        assertEquals(
            publication.toNaturalMethodDefPhysicalDeclaration(interfaceOwnerKey(publication)),
            naturalMethod,
        )
        assertTrue(naturalMethod.physicalMethod.signature.resultLayout is
                DotNetGenericOwnerPhysicalCallableResultLayoutRecord.SplitNullable)
        assertEquals(
            listOf(DotNetGenericOwnerPhysicalTypeParameterVariance.COVARIANT),
            decoded.values
                .filterIsInstance<DotNetPhysicalDeclaration.PublishedGenericInterfaceFamily>()
                .single()
                .naturalTypeParameterVariances,
        )
    }

    @Test
    fun deterministicallyRoundTripsAnInterfaceOnlyNaturalMethodDefIndex() {
        val publication = producerSealedFamilyPublicationFixture()
        val declarations = interfaceOnlyNaturalMethodDefAbiFixture(publication)

        assertEquals(5, declarations.size)
        assertEquals(1, declarations.values.count { declaration ->
            declaration is DotNetPhysicalDeclaration.Class
        })
        assertEquals(1, declarations.values.count { declaration ->
            declaration is DotNetPhysicalDeclaration.Function
        })
        assertEquals(1, declarations.values.count { declaration ->
            declaration is DotNetPhysicalDeclaration.GenericOwnerMemberFamily
        })
        assertEquals(1, declarations.values.count { declaration ->
            declaration is DotNetPhysicalDeclaration.PublishedGenericInterfaceFamily
        })
        assertEquals(1, declarations.values.count { declaration ->
            declaration is DotNetPhysicalDeclaration.GenericOwnerNaturalMethodDef
        })
        assertTrue(declarations.values.none { declaration ->
            declaration is DotNetPhysicalDeclaration.GenericOwnerSealedFamily
        })

        val first = DotNetLibraryAbiCodec.encode(declarations)
        val second = DotNetLibraryAbiCodec.encode(declarations.toList().asReversed().toMap())
        assertEquals(first, second)
        assertEquals(declarations, DotNetLibraryAbiCodec.decode(first.toProperties()))
    }

    @Test
    fun rejectsInterfaceOnlyNaturalMethodDefLogicalDomainDivergence() {
        val declarations = interfaceOnlyNaturalMethodDefAbiFixture()
        val natural = declarations.values
            .filterIsInstance<DotNetPhysicalDeclaration.GenericOwnerNaturalMethodDef>()
            .single()
        val publication = natural.publication()
        val changedInput = publication.copy(naturalMethod = publication.naturalMethod.copy(
            logicalParameterDomains = listOf(
                DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_INPUT,
            ),
        )).toPhysicalDeclaration()
        val failure = assertFailsWith<IllegalArgumentException> {
            DotNetLibraryAbiCodec.encode(
                declarations + (changedInput.indexKey() to changedInput),
            )
        }
        assertTrue(failure.message.orEmpty().contains("logical input domains"))
    }

    @Test
    fun derivesTheIndexOnlyFromAllThreeLengthDelimitedLogicalKeys() {
        val fixture = producerSealedFamilyPublicationFixture()
        val first = fixture.copy(
            key = DotNetProducerGenericOwnerSealedFamilyKey("1:a", "bc", "d"),
        ).toPhysicalDeclaration()
        val second = fixture.copy(
            key = DotNetProducerGenericOwnerSealedFamilyKey("1", "a:bc", "d"),
        ).toPhysicalDeclaration()
        val changedOwner = fixture.copy(
            key = fixture.key.copy(implementationOwnerKey = "demo/OtherStore|class"),
        ).toPhysicalDeclaration()
        val changedMember = fixture.copy(
            key = fixture.key.copy(implementationMemberKey = "demo/Store.other|function"),
        ).toPhysicalDeclaration()

        assertNotEquals(first.indexKey(), second.indexKey())
        assertNotEquals(fixture.toPhysicalDeclaration().indexKey(), changedOwner.indexKey())
        assertNotEquals(fixture.toPhysicalDeclaration().indexKey(), changedMember.indexKey())
    }

    @Test
    fun rejectsEveryMissingCrossIndexAuthorityOnEncodeAndDecode() {
        val declarations = producerSealedFamilyAbiFixture()
        val sealedKey = producerSealedFamilyPublicationFixture().toPhysicalDeclaration().indexKey()
        val requiredKeys = declarations.keys - sealedKey

        requiredKeys.forEach { missingKey ->
            assertFailsWith<IllegalArgumentException>("encode accepted a J record without '$missingKey'") {
                DotNetLibraryAbiCodec.encode(declarations - missingKey)
            }
        }

        val encoded = DotNetLibraryAbiCodec.encode(declarations)
        requiredKeys.forEach { missingKey ->
            assertFailsWith<IllegalArgumentException>("decode accepted a J record without '$missingKey'") {
                DotNetLibraryAbiCodec.decode((encoded - encodedPropertyKey(missingKey)).toProperties())
            }
        }
    }

    @Test
    fun rejectsOrphanOrCrossWiredNaturalMethodDefRecords() {
        val publication = producerSealedFamilyPublicationFixture()
        val declarations = producerSealedFamilyAbiFixture(publication)
        val natural = publication.toNaturalMethodDefPhysicalDeclaration(interfaceOwnerKey(publication))

        assertFailsWith<IllegalArgumentException> {
            DotNetLibraryAbiCodec.encode(mapOf(natural.indexKey() to natural))
        }
        val naturalPublication = natural.publication()
        val changedName = naturalPublication.copy(
            naturalMethod = naturalPublication.naturalMethod.copy(
                row = naturalPublication.naturalMethod.row.copy(physicalName = "OtherRead"),
            ),
        ).toPhysicalDeclaration()
        assertFailsWith<IllegalArgumentException> {
            DotNetLibraryAbiCodec.encode(declarations + (natural.indexKey() to changedName))
        }
        val splitResult = naturalPublication.naturalMethod.row.structural.header.result as
                DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.SplitNullable
        val changedLayout = naturalPublication.copy(
            naturalMethod = naturalPublication.naturalMethod.copy(
                row = naturalPublication.naturalMethod.row.copy(
                    structural = naturalPublication.naturalMethod.row.structural.copy(
                        header = naturalPublication.naturalMethod.row.structural.header.copy(
                            result = DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.Direct(
                                splitResult.payload,
                            ),
                        ),
                    ),
                ),
            ),
        ).toPhysicalDeclaration()
        assertFailsWith<IllegalArgumentException> {
            DotNetLibraryAbiCodec.encode(declarations + (natural.indexKey() to changedLayout))
        }

        val hostileWireIndex = DotNetLibraryAbiCodec.encode(declarations)
            .mutateNaturalMethodDefDeclarationValue { encodedValue ->
                encodedValue.mutateEnvelopeFields { fields -> fields[1] = "other-owner" }
            }
        assertFailsWith<IllegalArgumentException> {
            DotNetLibraryAbiCodec.decode(hostileWireIndex.toProperties())
        }
    }

    @Test
    fun rejectsTwoLogicalNaturalMethodDefsClaimingOnePhysicalMethodDef() {
        val publication = producerSealedFamilyPublicationFixture()
        val declarations = interfaceOnlyNaturalMethodDefAbiFixture(publication)
        val originalKey = publication.key.logicalInterfaceMemberKey
        val duplicateKey = "$originalKey#duplicate"
        val natural = declarations.getValue("N:$originalKey") as
                DotNetPhysicalDeclaration.GenericOwnerNaturalMethodDef
        val naturalPublication = natural.publication()
        val duplicateNatural = naturalPublication.copy(
            logicalMemberKey = duplicateKey,
            naturalMethod = naturalPublication.naturalMethod.copy(
                logicalParameterDomains = listOf(
                    DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_INPUT,
                ),
            ),
        ).toPhysicalDeclaration()
        val function = declarations.getValue(originalKey) as DotNetPhysicalDeclaration.Function
        val memberFamily = declarations.getValue("G:$originalKey") as
                DotNetPhysicalDeclaration.GenericOwnerMemberFamily
        val published = declarations.values
            .filterIsInstance<DotNetPhysicalDeclaration.PublishedGenericInterfaceFamily>()
            .single()
        val duplicateMember = DotNetPublishedGenericInterfaceMemberContract(
            duplicateKey,
            published.contract.declaredMembers.single().role,
        )
        val hostile = declarations + mapOf(
            duplicateKey to function,
            "G:$duplicateKey" to memberFamily.copy(logicalMemberKey = duplicateKey),
            duplicateNatural.indexKey() to duplicateNatural,
            published.indexKey() to published.copy(
                contract = published.contract.copy(
                    declaredMembers = (published.contract.declaredMembers + duplicateMember)
                        .sortedBy { member -> member.logicalMemberKey },
                ),
            ),
        )

        val failure = assertFailsWith<IllegalArgumentException> {
            DotNetLibraryAbiCodec.encode(hostile)
        }
        assertTrue(failure.message.orEmpty().contains("claimed by multiple logical members"))
    }

    @Test
    fun rejectsSealedFamilyAndNaturalMethodDefGenericParameterNameDivergence() {
        val publication = producerSealedFamilyPublicationFixture()
        val declarations = producerSealedFamilyAbiFixture(publication)
        val natural = publication.toNaturalMethodDefPublication(interfaceOwnerKey(publication))
        val changedNatural = natural.copy(
            naturalMethod = natural.naturalMethod.copy(
                row = natural.naturalMethod.row.copy(
                    physicalGenericParameterNames = listOf("Changed"),
                ),
            ),
        ).toPhysicalDeclaration()

        val failure = assertFailsWith<IllegalArgumentException> {
            DotNetLibraryAbiCodec.encode(
                declarations + (changedNatural.indexKey() to changedNatural),
            )
        }
        assertTrue(failure.message.orEmpty().contains(
            "disagrees with its declaration-level natural MethodDef seal",
        ))
    }

    @Test
    fun rejectsCrossWiredClassFunctionMemberFamilyAndPublishedFamilyRecords() {
        val declarations = producerSealedFamilyAbiFixture()
        val publication = producerSealedFamilyPublicationFixture()
        val key = publication.key
        val logicalInterfaceOwnerKey = interfaceOwnerKey(publication)
        val interfaceFamilyKey = "H:$logicalInterfaceOwnerKey"

        val hostileIndexes = listOf(
            declarations.replacing(logicalInterfaceOwnerKey) { declaration ->
                (declaration as DotNetPhysicalDeclaration.Class).copy(
                    physicalTypeParameterCount = 2,
                    physicalTypeParameterVariances = listOf(
                        DotNetGenericOwnerPhysicalTypeParameterVariance.COVARIANT,
                        DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT,
                    ),
                )
            },
            declarations.replacing(logicalInterfaceOwnerKey) { declaration ->
                (declaration as DotNetPhysicalDeclaration.Class).copy(
                    physicalTypeParameterVariances = listOf(
                        DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT,
                    ),
                )
            },
            declarations.replacing(logicalInterfaceOwnerKey) { declaration ->
                val type = declaration as DotNetPhysicalDeclaration.Class
                type.copy(genericOwnerAbi = type.genericOwnerAbi?.copy(capabilityAssemblyName = "Other"))
            },
            declarations.replacing(key.logicalInterfaceMemberKey) { declaration ->
                (declaration as DotNetPhysicalDeclaration.Function).copy(methodName = "OtherRead")
            },
            declarations.replacing(key.implementationOwnerKey) { declaration ->
                (declaration as DotNetPhysicalDeclaration.Class).copy(ownerPath = listOf("demo.OtherStore`1"))
            },
            declarations.replacing(key.implementationOwnerKey) { declaration ->
                (declaration as DotNetPhysicalDeclaration.Class).copy(
                    physicalTypeParameterVariances = listOf(
                        DotNetGenericOwnerPhysicalTypeParameterVariance.COVARIANT,
                    ),
                )
            },
            declarations.replacing(key.implementationMemberKey) { declaration ->
                (declaration as DotNetPhysicalDeclaration.Function).copy(isInstance = false)
            },
            declarations.replacing("G:${key.logicalInterfaceMemberKey}") { declaration ->
                (declaration as DotNetPhysicalDeclaration.GenericOwnerMemberFamily).copy(
                    capabilityMethodName = "OtherSemantic",
                )
            },
            declarations.replacing("G:${key.implementationMemberKey}") { declaration ->
                (declaration as DotNetPhysicalDeclaration.GenericOwnerMemberFamily).copy(
                    capabilityMethodGenericParameterCount = 0,
                )
            },
            declarations.replacing("G:${key.implementationMemberKey}") { declaration ->
                (declaration as DotNetPhysicalDeclaration.GenericOwnerMemberFamily).copy(
                    semanticHookOwnerPath = listOf("demo.Store`1"),
                    semanticHookMethodName = "ReadSemanticHook",
                    semanticHookMethodGenericParameterCount = 1,
                )
            },
            declarations.replacing(interfaceFamilyKey) { declaration ->
                val family = declaration as DotNetPhysicalDeclaration.PublishedGenericInterfaceFamily
                family.copy(capabilityOwnerPath = listOf("demo.OtherSourceSemantic"))
            },
            declarations.replacing(interfaceFamilyKey) { declaration ->
                val family = declaration as DotNetPhysicalDeclaration.PublishedGenericInterfaceFamily
                family.copy(naturalTypeParameterVariances = listOf(
                    DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT,
                ))
            },
            declarations.replacing(interfaceFamilyKey) { declaration ->
                val family = declaration as DotNetPhysicalDeclaration.PublishedGenericInterfaceFamily
                family.copy(contract = family.contract.copy(
                    kind = DotNetPublishedGenericInterfaceFamilyKind.DERIVED,
                    rootLogicalOwnerKeys = listOf("demo/Base"),
                    directParents = listOf(DotNetPublishedGenericInterfaceParentContract(
                        "demo/Base",
                        listOf(0),
                    )),
                    lineageDepth = 1,
                ))
            },
            declarations.replacing(interfaceFamilyKey) { declaration ->
                val family = declaration as DotNetPhysicalDeclaration.PublishedGenericInterfaceFamily
                family.copy(contract = family.contract.copy(
                    declaredMembers = listOf(DotNetPublishedGenericInterfaceMemberContract(
                        key.logicalInterfaceMemberKey,
                        DotNetPublishedGenericInterfaceMemberRole.PRODUCER,
                    )),
                ))
            },
        )
        hostileIndexes.forEachIndexed { index, hostile ->
            assertFailsWith<IllegalArgumentException>("cross-wired producer index $index was accepted") {
                DotNetLibraryAbiCodec.encode(hostile)
            }
        }

        val encoded = DotNetLibraryAbiCodec.encode(declarations)
        val hostileInterfaceFunction = (declarations.getValue(key.logicalInterfaceMemberKey) as
                DotNetPhysicalDeclaration.Function).copy(methodGenericParameterCount = 0)
        val encodedHostileRecord = DotNetLibraryAbiCodec.encode(
            mapOf(key.logicalInterfaceMemberKey to hostileInterfaceFunction),
        )
        assertFailsWith<IllegalArgumentException> {
            DotNetLibraryAbiCodec.decode((encoded + encodedHostileRecord).toProperties())
        }
    }

    @Test
    fun derivesThePublishedMemberRoleFromTheActualNaturalResultLayout() {
        val directPublication = producerSealedFamilyPublicationFixture().withDirectResults()
        val directDeclarations = producerSealedFamilyAbiFixture(
            directPublication,
            DotNetPublishedGenericInterfaceMemberRole.PRODUCER,
        )
        assertEquals(
            directDeclarations,
            DotNetLibraryAbiCodec.decode(DotNetLibraryAbiCodec.encode(directDeclarations).toProperties()),
        )

        assertFailsWith<IllegalArgumentException> {
            DotNetLibraryAbiCodec.encode(producerSealedFamilyAbiFixture(
                directPublication,
                DotNetPublishedGenericInterfaceMemberRole.SPLIT_NULLABLE_PRODUCER,
            ))
        }
    }

    @Test
    fun rejectsMalformedOrPreAbi62PublishedPhysicalVariancePayloads() {
        val valid = DotNetLibraryAbiCodec.encode(producerSealedFamilyAbiFixture())

        val invalidVariance = valid.mutatePublishedInterfaceDeclarationValue { encodedValue ->
            encodedValue.mutateEnvelopeFields { fields ->
                fields[physicalVarianceOffset(fields)] = "BIVARIANT"
            }
        }
        val invalidVarianceFailure = assertFailsWith<IllegalArgumentException> {
            DotNetLibraryAbiCodec.decode(invalidVariance.toProperties())
        }
        assertTrue(invalidVarianceFailure.message.orEmpty().contains("invalid physical variance"))

        val preAbi62Payload = valid.mutatePublishedInterfaceDeclarationValue { encodedValue ->
            encodedValue.mutateEnvelopeFields { fields ->
                fields.removeAt(physicalVarianceOffset(fields))
                fields.removeAt(11)
            }
        }
        assertFailsWith<IllegalArgumentException> {
            DotNetLibraryAbiCodec.decode(preAbi62Payload.toProperties())
        }
    }

    @Test
    fun rejectsMalformedInnerBytesAndEnvelopeKeyDisagreement() {
        val valid = DotNetLibraryAbiCodec.encode(producerSealedFamilyAbiFixture())

        val truncated = valid.mutateSealedDeclarationValue { encodedValue ->
            encodedValue.mutateEnvelopeFields { fields ->
                val publicationBytes = decoder.decode(fields[4])
                fields[4] = encoder.encodeToString(publicationBytes.copyOf(publicationBytes.size - 1))
            }
        }
        val malformed = assertFailsWith<IllegalArgumentException> {
            DotNetLibraryAbiCodec.decode(truncated.toProperties())
        }
        assertTrue(malformed.message.orEmpty().contains("malformed publication"))

        val crossWired = valid.mutateSealedDeclarationValue { encodedValue ->
            encodedValue.mutateEnvelopeFields { fields ->
                fields[1] = "F:demo/OtherSource.read|function"
            }
        }
        val disagreement = assertFailsWith<IllegalArgumentException> {
            DotNetLibraryAbiCodec.decode(crossWired.toProperties())
        }
        assertTrue(disagreement.message.orEmpty().contains("encoded logical declaration keys"))
    }

    @Test
    fun rejectsTruncatedAndTrailingNaturalMethodDefPublicationBytes() {
        val valid = DotNetLibraryAbiCodec.encode(interfaceOnlyNaturalMethodDefAbiFixture())

        val truncated = valid.mutateNaturalMethodDefDeclarationValue { encodedValue ->
            encodedValue.mutateEnvelopeFields { fields ->
                val publicationBytes = decoder.decode(fields[3])
                fields[3] = encoder.encodeToString(publicationBytes.copyOf(publicationBytes.size - 1))
            }
        }
        val truncatedFailure = assertFailsWith<IllegalArgumentException> {
            DotNetLibraryAbiCodec.decode(truncated.toProperties())
        }
        assertTrue(truncatedFailure.message.orEmpty().contains("truncated natural MethodDef publication"))

        val trailing = valid.mutateNaturalMethodDefDeclarationValue { encodedValue ->
            encodedValue.mutateEnvelopeFields { fields ->
                val publicationBytes = decoder.decode(fields[3])
                fields[3] = encoder.encodeToString(publicationBytes + 0.toByte())
            }
        }
        val trailingFailure = assertFailsWith<IllegalArgumentException> {
            DotNetLibraryAbiCodec.decode(trailing.toProperties())
        }
        assertTrue(trailingFailure.message.orEmpty().contains("trailing natural MethodDef publication bytes"))
    }

    @Test
    fun rejectsAnOversizedEncodedPublicationBeforeBase64Decoding() {
        val key = producerSealedFamilyPublicationFixture().key
        val failure = assertFailsWith<IllegalArgumentException> {
            DotNetPhysicalDeclaration.GenericOwnerSealedFamily(
                key.logicalInterfaceMemberKey,
                key.implementationOwnerKey,
                key.implementationMemberKey,
                "A".repeat(
                    DotNetLibraryAbiCodec.MAX_PRODUCER_GENERIC_OWNER_SEALED_FAMILY_BASE64_CHARS + 1,
                ),
            )
        }
        assertTrue(failure.message.orEmpty().contains("bounded encoded-publication size"))
    }

    @Test
    fun rejectsInvalidBase64TrailingFieldsAndTheWrongStructuralIndexKey() {
        val valid = DotNetLibraryAbiCodec.encode(producerSealedFamilyAbiFixture())

        val invalidBase64 = valid.mutateSealedDeclarationValue { encodedValue ->
            encodedValue.mutateEnvelopeFields { fields -> fields[4] = "*" }
        }
        val invalidBase64Failure = assertFailsWith<IllegalArgumentException> {
            DotNetLibraryAbiCodec.decode(invalidBase64.toProperties())
        }
        assertTrue(invalidBase64Failure.message.orEmpty().contains("invalid Base64"))

        val trailing = valid.mutateSealedDeclarationValue { encodedValue ->
            encodedValue.mutateEnvelopeFields { fields -> fields += "trailing" }
        }
        val trailingFailure = assertFailsWith<IllegalArgumentException> {
            DotNetLibraryAbiCodec.decode(trailing.toProperties())
        }
        assertTrue(trailingFailure.message.orEmpty().contains("trailing envelope"))

        val sealedEntry = valid.entries.single { entry -> decodePropertyKey(entry.key).startsWith("J:") }
        val wrongIndex = valid - sealedEntry.key + (
                encodedPropertyKey("J:${"0".repeat(32)}") to sealedEntry.value
                )
        val identityFailure = assertFailsWith<IllegalArgumentException> {
            DotNetLibraryAbiCodec.decode(wrongIndex.toProperties())
        }
        assertTrue(identityFailure.message.orEmpty().contains("structured identity"))
    }

    @Test
    fun participatesInPortableAbiComparisonWithoutChangingItsRules() {
        val publication = producerSealedFamilyPublicationFixture()
        val declarations = producerSealedFamilyAbiFixture(publication)
        val platformWithAddition = declarations + (
                "C:demo/PlatformOnly" to DotNetPhysicalDeclaration.Class(listOf("demo.PlatformOnly"))
                )

        assertTrue(DotNetLibraryAbiCodec.portablePhysicalAbiDifferences(
            declarations,
            platformWithAddition,
        ).isEmpty())

        val changedPublication = publication.copy(body = publication.body.copy(
            methodDefs = publication.body.methodDefs.map { methodDef ->
                if (methodDef.role == DotNetProducerGenericOwnerSealedMethodDefRole.NATURAL_INTERFACE_SLOT) {
                    methodDef.copy(row = methodDef.row.copy(
                        physicalGenericParameterNames = listOf("Changed"),
                    ))
                } else {
                    methodDef
                }
            },
        ))
        val changedDeclarations = producerSealedFamilyAbiFixture(changedPublication)
        val differences = DotNetLibraryAbiCodec.portablePhysicalAbiDifferences(
            declarations,
            changedDeclarations,
        )
        assertEquals(2, differences.size)
        assertEquals(
            setOf(
                publication.toPhysicalDeclaration().indexKey(),
                publication.toNaturalMethodDefPhysicalDeclaration(interfaceOwnerKey(publication)).indexKey(),
            ),
            differences.mapTo(linkedSetOf()) { difference -> difference.logicalKey },
        )
    }

    private companion object {
        val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
        val decoder: Base64.Decoder = Base64.getUrlDecoder()

        fun interfaceOwnerKey(publication: DotNetProducerGenericOwnerSealedFamilyPublication): String =
            publication.key.logicalInterfaceMemberKey.substringBeforeLast('.')

        fun producerSealedFamilyAbiFixture(
            publication: DotNetProducerGenericOwnerSealedFamilyPublication =
                producerSealedFamilyPublicationFixture(),
            publishedMemberRole: DotNetPublishedGenericInterfaceMemberRole =
                DotNetPublishedGenericInterfaceMemberRole.SPLIT_NULLABLE_PRODUCER,
        ): Map<String, DotNetPhysicalDeclaration> {
            val key = publication.key
            val logicalInterfaceOwnerKey = interfaceOwnerKey(publication)
            val types = publication.body.typeDefs.associateBy { row -> row.role }
            val methods = publication.body.methodDefs.associateBy { row -> row.role }
            fun type(role: DotNetProducerGenericOwnerSealedTypeDefRole) = types.getValue(role).row
            fun method(role: DotNetProducerGenericOwnerSealedMethodDefRole) = methods.getValue(role).row
            fun function(
                typeRole: DotNetProducerGenericOwnerSealedTypeDefRole,
                methodRole: DotNetProducerGenericOwnerSealedMethodDefRole,
            ): DotNetPhysicalDeclaration.Function {
                val type = type(typeRole)
                val method = method(methodRole)
                return DotNetPhysicalDeclaration.Function(
                    type.physicalPath,
                    method.physicalName,
                    method.structural.header.isInstance,
                    method.structural.header.genericArity,
                )
            }
            val naturalType = type(DotNetProducerGenericOwnerSealedTypeDefRole.NATURAL_INTERFACE)
            val interfaceCapabilityType = type(
                DotNetProducerGenericOwnerSealedTypeDefRole.INTERFACE_SEMANTIC_CAPABILITY,
            )
            val implementationType = type(
                DotNetProducerGenericOwnerSealedTypeDefRole.IMPLEMENTATION_CLASS,
            )
            val classCapabilityType = type(
                DotNetProducerGenericOwnerSealedTypeDefRole.CLASS_SEMANTIC_CAPABILITY,
            )
            val interfaceCapabilityMethod = method(
                DotNetProducerGenericOwnerSealedMethodDefRole.INTERFACE_SEMANTIC_CAPABILITY_SLOT,
            )
            val classCapabilityMethod = method(
                DotNetProducerGenericOwnerSealedMethodDefRole.CLASS_SEMANTIC_CAPABILITY_SLOT,
            )
            val interfaceClass = DotNetPhysicalDeclaration.Class(
                naturalType.physicalPath,
                naturalType.structural.genericArity,
                physicalTypeParameterVariances = naturalType.structural.genericParameters.map { parameter ->
                    parameter.variance
                },
                genericOwnerAbi = DotNetGenericOwnerAbi(
                    "Demo",
                    interfaceCapabilityType.physicalPath,
                ),
            )
            val interfaceMemberFamily = DotNetPhysicalDeclaration.GenericOwnerMemberFamily(
                ownerPath = interfaceCapabilityType.physicalPath,
                ownerLogicalKey = logicalInterfaceOwnerKey,
                logicalMemberKey = key.logicalInterfaceMemberKey,
                capabilityMethodName = interfaceCapabilityMethod.physicalName,
                capabilityMethodGenericParameterCount = interfaceCapabilityMethod.structural.header.genericArity,
                defaultCapabilityMethodName = null,
                defaultCapabilityMethodGenericParameterCount = null,
                semanticHookOwnerPath = null,
                semanticHookMethodName = null,
                semanticHookMethodGenericParameterCount = null,
                foreignOverrideProbeMethodName = null,
                foreignOverrideProbeMethodGenericParameterCount = null,
            )
            val implementationMemberFamily = DotNetPhysicalDeclaration.GenericOwnerMemberFamily(
                ownerPath = classCapabilityType.physicalPath,
                ownerLogicalKey = key.implementationOwnerKey,
                logicalMemberKey = key.implementationMemberKey,
                capabilityMethodName = classCapabilityMethod.physicalName,
                capabilityMethodGenericParameterCount = classCapabilityMethod.structural.header.genericArity,
                defaultCapabilityMethodName = null,
                defaultCapabilityMethodGenericParameterCount = null,
                semanticHookOwnerPath = null,
                semanticHookMethodName = null,
                semanticHookMethodGenericParameterCount = null,
                foreignOverrideProbeMethodName = null,
                foreignOverrideProbeMethodGenericParameterCount = null,
            )
            val publishedInterface = DotNetPhysicalDeclaration.PublishedGenericInterfaceFamily(
                naturalType.physicalPath,
                "Demo",
                interfaceCapabilityType.physicalPath,
                naturalTypeParameterVariances = naturalType.structural.genericParameters.map { parameter ->
                    parameter.variance
                },
                contract = DotNetPublishedGenericInterfaceFamilyContract(
                    logicalInterfaceOwnerKey,
                    naturalType.structural.genericArity,
                    DotNetPublishedGenericInterfaceFamilyKind.ROOT,
                    listOf(logicalInterfaceOwnerKey),
                    emptyList(),
                    0,
                    listOf(DotNetPublishedGenericInterfaceMemberContract(
                        key.logicalInterfaceMemberKey,
                        publishedMemberRole,
                    )),
                    DotNetPublishedGenericInterfaceCapabilityBindingKind.OWNED,
                    null,
                ),
            )
            val implementationClass = DotNetPhysicalDeclaration.Class(
                implementationType.physicalPath,
                implementationType.structural.genericArity,
                genericOwnerAbi = DotNetGenericOwnerAbi(
                    "Demo",
                    classCapabilityType.physicalPath,
                    listOf(DotNetGenericOwnerCapabilitySuperInterfaceAbi(
                        "Demo",
                        interfaceCapabilityType.physicalPath,
                    )),
                ),
            )
            val sealed = publication.toPhysicalDeclaration()
            val naturalMethod = publication.toNaturalMethodDefPhysicalDeclaration(logicalInterfaceOwnerKey)
            return linkedMapOf(
                logicalInterfaceOwnerKey to interfaceClass,
                key.logicalInterfaceMemberKey to function(
                    DotNetProducerGenericOwnerSealedTypeDefRole.NATURAL_INTERFACE,
                    DotNetProducerGenericOwnerSealedMethodDefRole.NATURAL_INTERFACE_SLOT,
                ),
                key.implementationOwnerKey to implementationClass,
                key.implementationMemberKey to function(
                    DotNetProducerGenericOwnerSealedTypeDefRole.IMPLEMENTATION_CLASS,
                    DotNetProducerGenericOwnerSealedMethodDefRole.IMPLEMENTATION_TYPED_ENTRY,
                ),
                interfaceMemberFamily.indexKey() to interfaceMemberFamily,
                implementationMemberFamily.indexKey() to implementationMemberFamily,
                publishedInterface.indexKey() to publishedInterface,
                naturalMethod.indexKey() to naturalMethod,
                sealed.indexKey() to sealed,
            )
        }

        fun interfaceOnlyNaturalMethodDefAbiFixture(
            publication: DotNetProducerGenericOwnerSealedFamilyPublication =
                producerSealedFamilyPublicationFixture(),
        ): Map<String, DotNetPhysicalDeclaration> {
            val complete = producerSealedFamilyAbiFixture(publication)
            val key = publication.key
            return complete - setOf(
                key.implementationOwnerKey,
                key.implementationMemberKey,
                "G:${key.implementationMemberKey}",
                publication.toPhysicalDeclaration().indexKey(),
            )
        }

        fun DotNetProducerGenericOwnerSealedFamilyPublication.withDirectResults():
                DotNetProducerGenericOwnerSealedFamilyPublication = copy(body = body.copy(
            methodDefs = body.methodDefs.map { method ->
                if (method.role in setOf(
                        DotNetProducerGenericOwnerSealedMethodDefRole.NATURAL_INTERFACE_SLOT,
                        DotNetProducerGenericOwnerSealedMethodDefRole.IMPLEMENTATION_TYPED_ENTRY,
                    )
                ) {
                    val result = method.row.structural.header.result as
                            DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.SplitNullable
                    method.copy(row = method.row.copy(structural = method.row.structural.copy(
                        header = method.row.structural.header.copy(
                            result = DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.Direct(result.payload),
                        ),
                    )))
                } else {
                    method
                }
            },
        ))

        fun Map<String, DotNetPhysicalDeclaration>.replacing(
            key: String,
            transform: (DotNetPhysicalDeclaration) -> DotNetPhysicalDeclaration,
        ): Map<String, DotNetPhysicalDeclaration> = this + (key to transform(getValue(key)))

        fun Map<String, String>.toProperties(): Properties = Properties().also { properties ->
            for (entry in entries) properties.setProperty(entry.key, entry.value)
        }

        fun Map<String, String>.mutateSealedDeclarationValue(
            transform: (String) -> String,
        ): Map<String, String> {
            val entry = entries.single { candidate -> decodePropertyKey(candidate.key).startsWith("J:") }
            return this + (entry.key to transform(entry.value))
        }

        fun Map<String, String>.mutatePublishedInterfaceDeclarationValue(
            transform: (String) -> String,
        ): Map<String, String> {
            val entry = entries.single { candidate -> decodePropertyKey(candidate.key).startsWith("H:") }
            return this + (entry.key to transform(entry.value))
        }

        fun Map<String, String>.mutateNaturalMethodDefDeclarationValue(
            transform: (String) -> String,
        ): Map<String, String> {
            val entry = entries.single { candidate -> decodePropertyKey(candidate.key).startsWith("N:") }
            return this + (entry.key to transform(entry.value))
        }

        fun physicalVarianceOffset(fields: List<String>): Int =
            15 + fields[7].toInt() + fields[9].toInt() + fields[10].toInt()

        fun String.mutateEnvelopeFields(transform: (MutableList<String>) -> Unit): String {
            val fields = decoder.decode(this).toString(Charsets.UTF_8).split('\u0000').toMutableList()
            transform(fields)
            return encoder.encodeToString(fields.joinToString("\u0000").toByteArray(Charsets.UTF_8))
        }

        fun encodedPropertyKey(logicalKey: String): String =
            DotNetLibraryAbiCodec.DECLARATION_PROPERTY_PREFIX + encodeText(logicalKey)

        fun decodePropertyKey(propertyKey: String): String =
            decoder.decode(propertyKey.removePrefix(DotNetLibraryAbiCodec.DECLARATION_PROPERTY_PREFIX))
                .toString(Charsets.UTF_8)

        fun encodeText(value: String): String =
            encoder.encodeToString(value.toByteArray(Charsets.UTF_8))
    }
}
