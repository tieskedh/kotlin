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
        assertEquals("61", DotNetLibraryAbiCodec.ABI_VERSION)

        val decoded = DotNetLibraryAbiCodec.decode(first.toProperties())
        assertEquals(declarations, decoded)
        val decodedFamily = decoded.values
            .filterIsInstance<DotNetPhysicalDeclaration.GenericOwnerSealedFamily>()
            .single()
        assertEquals(publication, decodedFamily.publication())
        assertEquals(listOf("demo.Store`1"), decodedFamily.ownerPath)
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
    fun rejectsCrossWiredClassFunctionMemberFamilyAndPublishedFamilyRecords() {
        val declarations = producerSealedFamilyAbiFixture()
        val publication = producerSealedFamilyPublicationFixture()
        val key = publication.key
        val logicalInterfaceOwnerKey = interfaceOwnerKey(publication)
        val interfaceFamilyKey = "H:$logicalInterfaceOwnerKey"

        val hostileIndexes = listOf(
            declarations.replacing(logicalInterfaceOwnerKey) { declaration ->
                (declaration as DotNetPhysicalDeclaration.Class).copy(physicalTypeParameterCount = 2)
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
        assertEquals(1, differences.size)
        assertEquals(publication.toPhysicalDeclaration().indexKey(), differences.single().logicalKey)
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
                sealed.indexKey() to sealed,
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
