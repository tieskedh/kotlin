/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import java.io.File
import java.util.Base64
import java.util.Properties
import org.jetbrains.kotlin.load.dotnet.DotNetManagedAssemblyIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DotNetProducerGenericOwnerSealedFamilyLibraryAbiTest {
    @Test
    fun classifiesOnlyTheRehearsalEpochPhysicalRecords() {
        val publication = producerSealedFamilyPublicationFixture().withDirectResults()
        val declarations = producerSealedFamilyAbiFixture(
            publication,
            DotNetPublishedGenericInterfaceMemberResultLayout.DIRECT,
        ) +
                semanticEquivalenceCertificateEntry(publication) + implementationMethodDefAbiFixture()
        val records = declarations.genericOwnerRehearsalEpochRecords()

        assertEquals(
            setOf(
                DotNetGenericOwnerRehearsalEpochRecordKind.PUBLISHED_GENERIC_INTERFACE_FAMILY,
                DotNetGenericOwnerRehearsalEpochRecordKind.GENERIC_OWNER_NATURAL_METHOD_DEF,
                DotNetGenericOwnerRehearsalEpochRecordKind.GENERIC_OWNER_IMPLEMENTATION_METHOD_DEF,
                DotNetGenericOwnerRehearsalEpochRecordKind.GENERIC_OWNER_SEALED_FAMILY,
                DotNetGenericOwnerRehearsalEpochRecordKind
                    .GENERIC_OWNER_SEMANTIC_EQUIVALENCE_CERTIFICATE,
            ),
            records.mapTo(linkedSetOf(), DotNetGenericOwnerRehearsalEpochRecord::kind),
        )
        assertEquals(
            setOf("H", "N", "M", "J", "K"),
            records.mapTo(linkedSetOf()) { record -> record.kind.wireTag },
        )
        val productionDeclarations =
            declarations - records.mapTo(linkedSetOf(), DotNetGenericOwnerRehearsalEpochRecord::indexKey)
        assertTrue(productionDeclarations.genericOwnerRehearsalEpochRecords().isEmpty())
        assertTrue(mapOf(
            "H:not-actually-a-rehearsal-record" to productionDeclarations.values.first(),
        ).genericOwnerRehearsalEpochRecords().isEmpty())
        assertEquals(
            productionDeclarations,
            backendOutput(productionDeclarations, genericOwnerRehearsal = false).declarations,
        )
    }

    @Test
    fun backendOutputRejectsEveryRehearsalEpochRecordInProduction() {
        val publication = producerSealedFamilyPublicationFixture().withDirectResults()
        val declarations = producerSealedFamilyAbiFixture(
            publication,
            DotNetPublishedGenericInterfaceMemberResultLayout.DIRECT,
        ) +
                semanticEquivalenceCertificateEntry(publication) + implementationMethodDefAbiFixture()
        val records = declarations.genericOwnerRehearsalEpochRecords()

        records.forEach { record ->
            val failure = assertFailsWith<IllegalArgumentException>(
                "production output accepted ${record.kind.wireTag}",
            ) {
                backendOutput(
                    mapOf("not-a-wire-tag:${record.kind}" to declarations.getValue(record.indexKey)),
                    genericOwnerRehearsal = false,
                )
            }
            assertTrue(failure.message.orEmpty().contains("H/N/M/J/K"))
        }
        assertEquals(declarations, backendOutput(declarations, genericOwnerRehearsal = true).declarations)
    }

    @Test
    fun deterministicallyRoundTripsTheCompleteActualOnlyProducerIndex() {
        val publication = producerSealedFamilyPublicationFixture().withDirectResults()
        val declarations = producerSealedFamilyAbiFixture(
            publication,
            DotNetPublishedGenericInterfaceMemberResultLayout.DIRECT,
        ) +
                semanticEquivalenceCertificateEntry(publication)

        val first = DotNetLibraryAbiCodec.encode(declarations)
        val second = DotNetLibraryAbiCodec.encode(declarations.toList().asReversed().toMap())
        assertEquals(first, second)
        assertEquals("67", DotNetLibraryAbiCodec.ABI_VERSION)

        val decoded = DotNetLibraryAbiCodec.decode(first.toProperties())
        assertEquals(declarations, decoded)
        val decodedFamily = decoded.values
            .filterIsInstance<DotNetPhysicalDeclaration.GenericOwnerSealedFamily>()
            .single()
        assertEquals(publication, decodedFamily.publication())
        assertEquals(listOf("demo.Store`1"), decodedFamily.ownerPath)
        val certificate = decoded.values
            .filterIsInstance<
                    DotNetPhysicalDeclaration.GenericOwnerSemanticEquivalenceCertificate>()
            .single()
        assertEquals(decodedFamily.indexKey(), certificate.sealedFamilyIndexKey)
        assertEquals(
            DotNetProducerGenericOwnerSemanticEquivalenceProofKind
                .FINAL_CONCRETE_DIRECT_TYPED_ENTRY_CHAIN,
            certificate.certificate().proofKind,
        )
        val naturalMethod = decoded.values
            .filterIsInstance<DotNetPhysicalDeclaration.GenericOwnerNaturalMethodDef>()
            .single()
        assertEquals(
            publication.toNaturalMethodDefPhysicalDeclaration(interfaceOwnerKey(publication)),
            naturalMethod,
        )
        assertTrue(naturalMethod.physicalMethod.signature.resultLayout is
                DotNetGenericOwnerPhysicalCallableResultLayoutRecord.Direct)
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
    fun deterministicallyRoundTripsAnOpenClassImplementationMethodDefIndex() {
        val declarations = implementationMethodDefAbiFixture()
        val implementation = declarations.values
            .filterIsInstance<DotNetPhysicalDeclaration.GenericOwnerImplementationMethodDef>()
            .single()

        val first = DotNetLibraryAbiCodec.encode(declarations)
        val second = DotNetLibraryAbiCodec.encode(declarations.toList().asReversed().toMap())
        assertEquals(first, second)
        assertEquals(declarations, DotNetLibraryAbiCodec.decode(first.toProperties()))
        assertEquals(listOf("demo.ExternalStore`1"), implementation.ownerPath)
        assertEquals(
            listOf(DotNetGenericOwnerPhysicalTypeExpressionRecord.ownerParameter(0)),
            implementation.naturalInterfaceTypeArguments,
        )
        assertTrue(implementation.physicalMethod.signature.resultLayout is
                DotNetGenericOwnerPhysicalCallableResultLayoutRecord.SplitNullable)
        assertTrue(implementation.methodIntroducesSlot)
    }

    @Test
    fun rejectsMalformedImplementationMethodDefWirePayloads() {
        val valid = DotNetLibraryAbiCodec.encode(implementationMethodDefAbiFixture())
        val mutations = listOf<(MutableList<String>) -> Unit>(
            { fields -> fields += "trailing" },
            { fields -> fields[5] = "COVARIANT" },
            { fields -> fields[7] = "NOT_A_DISPATCH" },
            { fields -> fields[8] = "*" },
            { fields -> fields[9] = "malformed-signature" },
            { fields -> fields[12] = "not-a-boolean" },
            { fields ->
                fields[16] = "0"
                fields.removeAt(17)
            },
            { fields -> fields[16] = "1025" },
            { fields -> fields[16] = "+1" },
            { fields -> fields[17] = encodeText("malformed-type-expression") },
        )
        mutations.forEachIndexed { index, mutation ->
            val hostile = valid.mutateImplementationMethodDefDeclarationValue { encodedValue ->
                encodedValue.mutateEnvelopeFields(mutation)
            }
            assertFailsWith<IllegalArgumentException>("malformed M wire payload $index was accepted") {
                DotNetLibraryAbiCodec.decode(hostile.toProperties())
            }
        }

        val entry = valid.entries.single { candidate ->
            decodePropertyKey(candidate.key).startsWith("M:")
        }
        val wrongIndex = valid - entry.key + (
                encodedPropertyKey("M:${"0".repeat(32)}") to entry.value
                )
        assertFailsWith<IllegalArgumentException>("M accepted the wrong structural index key") {
            DotNetLibraryAbiCodec.decode(wrongIndex.toProperties())
        }
    }

    @Test
    fun boundsImplementationMethodDefPhysicalSignatureAndTypeDecoding() {
        val declarations = implementationMethodDefAbiFixture()
        val implementation = declarations.values
            .filterIsInstance<DotNetPhysicalDeclaration.GenericOwnerImplementationMethodDef>()
            .single()

        var depth65 = DotNetGenericOwnerPhysicalTypeExpressionRecord.ownerParameter(0)
        repeat(DotNetGenericOwnerPhysicalFamilyCodec.MAX_PHYSICAL_TYPE_DEPTH) {
            depth65 = DotNetGenericOwnerPhysicalTypeExpressionRecord.szArray(depth65)
        }
        val split = implementation.physicalMethod.signature.resultLayout as
                DotNetGenericOwnerPhysicalCallableResultLayoutRecord.SplitNullable
        val depthFailure = assertFailsWith<IllegalArgumentException> {
            implementation.copy(physicalMethod = implementation.physicalMethod.copy(
                signature = implementation.physicalMethod.signature.copy(
                    resultLayout = DotNetGenericOwnerPhysicalCallableResultLayoutRecord.SplitNullable(
                        DotNetGenericOwnerPhysicalValueSlotRecord(
                            domain = split.payloadSlot.domain,
                            type = depth65,
                        ),
                    ),
                ),
            ))
        }
        assertTrue(depthFailure.message.orEmpty().contains("nesting is too deep"))

        var depth4 = DotNetGenericOwnerPhysicalTypeExpressionRecord.ownerParameter(0)
        repeat(3) {
            depth4 = DotNetGenericOwnerPhysicalTypeExpressionRecord.szArray(depth4)
        }
        val encodedDepth4 = DotNetGenericOwnerPhysicalFamilyCodec.encodePhysicalTypeExpression(depth4)
        val decoderDepthFailure = assertFailsWith<IllegalArgumentException> {
            DotNetGenericOwnerPhysicalFamilyCodec.decodePhysicalTypeExpression(
                encodedDepth4,
                maximumDepth = 3,
            )
        }
        assertTrue(decoderDepthFailure.message.orEmpty().contains("nesting is too deep"))

        val fiveNodeType = DotNetGenericOwnerPhysicalTypeExpressionRecord.producerType(
            typePath = listOf("demo.FourArguments`4"),
            category = DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS,
            arguments = List(4) { DotNetGenericOwnerPhysicalTypeExpressionRecord.ownerParameter(0) },
        )
        val nodeFailure = assertFailsWith<IllegalArgumentException> {
            DotNetGenericOwnerPhysicalFamilyCodec.decodePhysicalTypeExpression(
                DotNetGenericOwnerPhysicalFamilyCodec.encodePhysicalTypeExpression(fiveNodeType),
                maximumNodes = 4,
            )
        }
        assertTrue(nodeFailure.message.orEmpty().contains("too many nodes"))

        val valid = DotNetLibraryAbiCodec.encode(declarations)
        val ownerParameter = encodeText(
            DotNetGenericOwnerPhysicalFamilyCodec.encodePhysicalTypeExpression(
                DotNetGenericOwnerPhysicalTypeExpressionRecord.ownerParameter(0),
            ),
        )
        val oversizedArgumentExpression = buildList {
            add(DotNetGenericOwnerPhysicalTypeKind.NAMED.name)
            add("-")
            add(DotNetGenericOwnerPhysicalTypeScope.PRODUCER.name)
            add("-")
            add(encodeText("demo.Hostile`1025"))
            add("1025")
            add(DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS.name)
            add("1025")
            repeat(1_025) { add(ownerParameter) }
        }.joinToString(";")
        val oversizedGenericArityExpression = listOf(
            DotNetGenericOwnerPhysicalTypeKind.OWNER_TYPE_PARAMETER.name,
            "0",
            "-",
            "-",
            "-",
            Int.MAX_VALUE.toString(),
            "-",
            "0",
        ).joinToString(";")

        val hostileMutations = listOf<Pair<String, (MutableList<String>) -> Unit>>(
            "oversized nested argument list" to { fields ->
                fields[17] = encodeText(oversizedArgumentExpression)
            },
            "huge nested generic arity" to { fields ->
                fields[17] = encodeText(oversizedGenericArityExpression)
            },
            "huge method generic arity" to { fields ->
                val signature = fields[9].split(';').toMutableList()
                signature[1] = Int.MAX_VALUE.toString()
                fields[9] = signature.joinToString(";")
            },
            "huge method parameter count" to { fields ->
                val signature = fields[9].split(';').toMutableList()
                signature[3] = Int.MAX_VALUE.toString()
                fields[9] = signature.joinToString(";")
            },
            "oversized signature field" to { fields ->
                fields[9] = "x".repeat(
                    DotNetGenericOwnerPhysicalFamilyCodec.MAX_SERIALIZED_PHYSICAL_FIELD_CHARS + 1,
                )
            },
            "oversized encoded type field" to { fields ->
                fields[17] = "A".repeat(
                    DotNetGenericOwnerPhysicalFamilyCodec.MAX_BASE64_PHYSICAL_FIELD_CHARS + 1,
                )
            },
        )
        hostileMutations.forEach { hostileMutation ->
            val description = hostileMutation.first
            val mutation = hostileMutation.second
            val hostile = valid.mutateImplementationMethodDefDeclarationValue { encodedValue ->
                encodedValue.mutateEnvelopeFields(mutation)
            }
            assertFailsWith<IllegalArgumentException>("M accepted $description") {
                DotNetLibraryAbiCodec.decode(hostile.toProperties())
            }
        }
    }

    @Test
    fun rejectsMissingAndCrossWiredImplementationMethodDefAuthority() {
        val declarations = implementationMethodDefAbiFixture()
        val implementation = declarations.values
            .filterIsInstance<DotNetPhysicalDeclaration.GenericOwnerImplementationMethodDef>()
            .single()
        listOf(
            implementation.implementationOwnerKey,
            implementation.implementationMemberKey,
            "N:${implementation.logicalInterfaceMemberKey}",
        ).forEach { missing ->
            assertFailsWith<IllegalArgumentException>("M accepted a missing '$missing' join") {
                DotNetLibraryAbiCodec.encode(declarations - missing)
            }
        }

        val hostileOwner = declarations.replacing(implementation.implementationOwnerKey) { declaration ->
            (declaration as DotNetPhysicalDeclaration.Class).copy(
                ownerPath = listOf("demo.OtherExternalStore`1"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            DotNetLibraryAbiCodec.encode(hostileOwner)
        }
        val hostileFunction = declarations.replacing(implementation.implementationMemberKey) { declaration ->
            (declaration as DotNetPhysicalDeclaration.Function).copy(methodName = "OtherRead")
        }
        assertFailsWith<IllegalArgumentException> {
            DotNetLibraryAbiCodec.encode(hostileFunction)
        }
        val twoParameterOwner = (declarations.getValue(implementation.implementationOwnerKey) as
                DotNetPhysicalDeclaration.Class).copy(
            physicalTypeParameterCount = 2,
            physicalTypeParameterVariances = List(2) {
                DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT
            },
        )
        val crossWiredConstruction = implementation.copy(
            ownerTypeParameterVariances = twoParameterOwner.physicalTypeParameterVariances,
            naturalInterfaceTypeArguments = listOf(
                DotNetGenericOwnerPhysicalTypeExpressionRecord.ownerParameter(1),
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            DotNetLibraryAbiCodec.encode(declarations + mapOf(
                implementation.implementationOwnerKey to twoParameterOwner,
                crossWiredConstruction.indexKey() to crossWiredConstruction,
            ))
        }
        val duplicateMemberKey = "demo/ExternalStore.otherRead|function"
        val duplicate = implementation.copy(implementationMemberKey = duplicateMemberKey)
        val originalFunction = declarations.getValue(implementation.implementationMemberKey) as
                DotNetPhysicalDeclaration.Function
        val duplicateClaim = declarations + mapOf(
            duplicateMemberKey to originalFunction,
            duplicate.indexKey() to duplicate,
        )
        val duplicateFailure = assertFailsWith<IllegalArgumentException> {
            DotNetLibraryAbiCodec.encode(duplicateClaim)
        }
        assertTrue(duplicateFailure.message.orEmpty().contains("claimed by multiple logical members"))
    }

    @Test
    fun rejectsOverlappingOpenImplementationAndCompleteSealedFamilyAuthority() {
        val publication = openImplementationPublicationFixture()
        val declarations = producerSealedFamilyAbiFixture(publication)
        val external = implementationMethodDefAbiFixture(publication).values
            .filterIsInstance<DotNetPhysicalDeclaration.GenericOwnerImplementationMethodDef>()
            .single()
        val implementationType = publication.body.typeDefs.single { type ->
            type.role == DotNetProducerGenericOwnerSealedTypeDefRole.IMPLEMENTATION_CLASS
        }.row
        val implementationMethod = publication.body.methodDefs.single { method ->
            method.role == DotNetProducerGenericOwnerSealedMethodDefRole.IMPLEMENTATION_TYPED_ENTRY
        }
        val overlapping = external.copy(
            implementationOwnerKey = publication.key.implementationOwnerKey,
            implementationMemberKey = publication.key.implementationMemberKey,
            ownerPath = implementationType.physicalPath,
            ownerTypeParameterVariances = implementationType.structural.genericParameters.map { parameter ->
                parameter.variance
            },
            physicalMethod = dotNetProducerSealedMethodDefPhysicalIdentity(
                publication.key.implementationMemberKey,
                implementationType,
                implementationMethod,
            ),
            methodIntroducesSlot = implementationMethod.row.dispatch.isNewSlot,
        )
        val failure = assertFailsWith<IllegalArgumentException> {
            DotNetLibraryAbiCodec.encode(declarations + (overlapping.indexKey() to overlapping))
        }
        assertTrue(failure.message.orEmpty().contains("bounded J and M owner grammars are disjoint"))

        val externalDeclarations = implementationMethodDefAbiFixture(publication)
        val aliasedOwner = (externalDeclarations.getValue(external.implementationOwnerKey) as
                DotNetPhysicalDeclaration.Class).copy(
            ownerPath = implementationType.physicalPath,
        )
        val aliasedFunction = (externalDeclarations.getValue(external.implementationMemberKey) as
                DotNetPhysicalDeclaration.Function).copy(
            ownerPath = implementationType.physicalPath,
            methodName = implementationMethod.row.physicalName,
        )
        val aliasedMethod = external.copy(
            ownerPath = implementationType.physicalPath,
            physicalMethod = dotNetProducerSealedMethodDefPhysicalIdentity(
                publication.key.implementationMemberKey,
                implementationType,
                implementationMethod,
            ),
            methodIntroducesSlot = implementationMethod.row.dispatch.isNewSlot,
        )
        val aliasFailure = assertFailsWith<IllegalArgumentException> {
            DotNetLibraryAbiCodec.encode(declarations + mapOf(
                external.implementationOwnerKey to aliasedOwner,
                external.implementationMemberKey to aliasedFunction,
                aliasedMethod.indexKey() to aliasedMethod,
            ))
        }
        assertTrue(aliasFailure.message.orEmpty().contains("same physical implementation MethodDef"))
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
            published.contract.declaredMembers.single().resultLayout,
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
                        DotNetPublishedGenericInterfaceMemberResultLayout.DIRECT,
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
    fun validatesThePublishedResultLayoutAgainstTheActualNaturalResultLayout() {
        val directPublication = producerSealedFamilyPublicationFixture().withDirectResults()
        val directDeclarations = producerSealedFamilyAbiFixture(
            directPublication,
            DotNetPublishedGenericInterfaceMemberResultLayout.DIRECT,
        )
        assertEquals(
            directDeclarations,
            DotNetLibraryAbiCodec.decode(DotNetLibraryAbiCodec.encode(directDeclarations).toProperties()),
        )

        assertFailsWith<IllegalArgumentException> {
            DotNetLibraryAbiCodec.encode(producerSealedFamilyAbiFixture(
                directPublication,
                DotNetPublishedGenericInterfaceMemberResultLayout.SPLIT_NULLABLE,
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
    fun semanticEquivalenceCertificateRequiresItsExactSameLibraryJFamily() {
        val splitPublication = producerSealedFamilyPublicationFixture()
        val splitCertificateEntry = semanticEquivalenceCertificateEntry(splitPublication)
        val splitFailure = assertFailsWith<IllegalArgumentException> {
            DotNetLibraryAbiCodec.encode(
                producerSealedFamilyAbiFixture(splitPublication) + splitCertificateEntry,
            )
        }
        assertTrue(splitFailure.message.orEmpty().contains("requires direct natural"))

        val publication = producerSealedFamilyPublicationFixture().withDirectResults()
        val declarations = producerSealedFamilyAbiFixture(
            publication,
            DotNetPublishedGenericInterfaceMemberResultLayout.DIRECT,
        )
        val certificateEntry = semanticEquivalenceCertificateEntry(publication)

        assertEquals(
            declarations + certificateEntry,
            DotNetLibraryAbiCodec.decode(
                DotNetLibraryAbiCodec.encode(declarations + certificateEntry).toProperties(),
            ),
        )

        val missingFamily = certificateEntry + (declarations - publication.toPhysicalDeclaration().indexKey())
        val missingFailure = assertFailsWith<IllegalArgumentException> {
            DotNetLibraryAbiCodec.encode(missingFamily)
        }
        assertTrue(missingFailure.message.orEmpty().contains("no same-library producer-sealed J family"))

        val certificate = certificateEntry.values
            .filterIsInstance<
                    DotNetPhysicalDeclaration.GenericOwnerSemanticEquivalenceCertificate>()
            .single()
        val otherFamilyKey = "J:${"0".repeat(32)}"
        val crossWired = assertFailsWith<IllegalArgumentException> {
            certificate.copy(sealedFamilyIndexKey = otherFamilyKey)
        }
        assertTrue(crossWired.message.orEmpty().contains("disagrees with its encoded"))
    }

    @Test
    fun externalIndexBindsCertificateAndJByLogicalIdentity() {
        val publication = producerSealedFamilyPublicationFixture().withDirectResults()
        val declarations = producerSealedFamilyAbiFixture(
            publication,
            DotNetPublishedGenericInterfaceMemberResultLayout.DIRECT,
        ) +
                semanticEquivalenceCertificateEntry(publication)
        val library = DotNetExternalLibrary(
            artifact = DotNetLibraryArtifact("Demo", "net10.0"),
            assemblyFile = File("Demo.dll"),
            declarations = declarations,
            friendAssemblies = emptySet(),
        )

        val index = DotNetExternalDeclarationIndex(listOf(library))
        val sealed = index.genericOwnerSealedFamiliesByKey.getValue(publication.key)
        val certificate = index.genericOwnerSemanticEquivalenceCertificatesByFamilyKey
            .getValue(publication.key)
        assertEquals(publication, sealed.publication)
        assertTrue(certificate.library === library)
        assertTrue(certificate.sealedFamily === sealed)
        assertEquals(
            DotNetGenericOwnerPhysicalAuthorityEpoch.SEALED_EMISSION_SIGNATURE_INDEX,
            certificate.authority.epoch,
        )
        assertTrue(
            index.genericOwnerSemanticEquivalenceCertificatesByLogicalEndpoint[
                publication.key.logicalInterfaceMemberKey to publication.key.implementationOwnerKey
            ] === certificate,
        )
        assertIs<DotNetGenericOwnerPhysicalBindingResult.Unavailable>(
            DotNetProducerGenericOwnerSemanticEquivalencePhysicalAuthority.bind(certificate),
        )
        assertTrue(index.peValidatedGenericOwnerSemanticEquivalenceCertificatesByFamilyKey.isEmpty())
        assertTrue(
            index.peValidatedGenericOwnerSemanticEquivalenceCertificatesByLogicalEndpoint.isEmpty(),
        )

        val familyDeclaration = declarations.getValue(publication.toPhysicalDeclaration().indexKey()) as
                DotNetPhysicalDeclaration.GenericOwnerSealedFamily
        val certificateDeclaration = declarations.values.filterIsInstance<
                DotNetPhysicalDeclaration.GenericOwnerSemanticEquivalenceCertificate>().single()
        val stamp = DotNetGenericOwnerPeValidationStamp(
            DotNetManagedAssemblyIdentity(
                name = library.artifact.assemblyName,
                version = library.artifact.assemblyVersion,
                culture = library.artifact.assemblyCulture,
                publicKey = emptyList(),
                publicKeyToken = emptyList(),
            ),
            library.assemblyFile.absoluteFile.normalize(),
            mapOf(
                certificateDeclaration.indexKey() to DotNetPeValidatedGenericOwnerCertificate(
                    certificateDeclaration,
                    familyDeclaration,
                ),
            ),
        )
        val stampedLibrary = library.copy(genericOwnerPeValidationStamp = stamp)
        val stampedIndex = DotNetExternalDeclarationIndex(listOf(stampedLibrary))
        val peValidated = stampedIndex.peValidatedGenericOwnerSemanticEquivalenceCertificatesByFamilyKey
            .getValue(publication.key)
        assertEquals(
            setOf(publication.key),
            stampedIndex.peValidatedGenericOwnerSemanticEquivalenceCertificatesByFamilyKey.keys,
        )
        assertTrue(peValidated.library === stampedLibrary)
        assertTrue(
            stampedIndex.peValidatedGenericOwnerSemanticEquivalenceCertificatesByLogicalEndpoint[
                publication.key.logicalInterfaceMemberKey to publication.key.implementationOwnerKey
            ] === peValidated,
        )

        val physicalAuthority = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<*>>(
            DotNetProducerGenericOwnerSemanticEquivalencePhysicalAuthority.bind(peValidated),
        ).value as DotNetProducerGenericOwnerSemanticEquivalencePhysicalAuthority
        assertEquals(
            DotNetGenericOwnerPhysicalAuthorityEpoch.SEALED_EMISSION_SIGNATURE_INDEX,
            physicalAuthority.epoch,
        )
        assertEquals(publication.key, physicalAuthority.familyKey)
        val physicalDeclarations = physicalAuthority.declarations
        val sealedTypes = publication.body.typeDefs.associateBy { typeDef -> typeDef.role }
        DotNetProducerGenericOwnerSealedTypeDefRole.entries.forEach { role ->
            val identity = physicalAuthority.typeDefinition(role)
            assertEquals(stampedLibrary.artifact, identity.artifact)
            assertEquals(sealedTypes.getValue(role).row.physicalPath, identity.ownerPath)
            val description = physicalDeclarations.typeDescriptionOrNull(identity)
            assertEquals(sealedTypes.getValue(role).row.structural.category, description?.category)
            assertEquals(
                sealedTypes.getValue(role).row.structural.genericParameters.map { parameter ->
                    parameter.variance
                },
                description?.genericParameters?.map { parameter -> parameter.variance },
            )
            val edges = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<*>>(
                physicalDeclarations.directSupertypeEdgesOrUnavailable(identity),
            ).value as Set<*>
            assertEquals(sealedTypes.getValue(role).row.structural.directEdges.size, edges.size)
        }

        val naturalType = physicalAuthority.typeDefinition(
            DotNetProducerGenericOwnerSealedTypeDefRole.NATURAL_INTERFACE,
        )
        val implementationType = physicalAuthority.typeDefinition(
            DotNetProducerGenericOwnerSealedTypeDefRole.IMPLEMENTATION_CLASS,
        )
        val implementationParameter = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<*>>(
            physicalDeclarations.typeParameterOrError(implementationType, 0),
        ).value as DotNetGenericOwnerSymbolicCarrierReference.Parameter
        val implementationConstruction = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<*>>(
            physicalDeclarations.constructTypeOrError(
                implementationType,
                listOf(implementationParameter),
            ),
        ).value as DotNetGenericOwnerSymbolicCarrierReference.Constructed
        val naturalConstruction = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<*>>(
            physicalDeclarations.constructTypeOrError(
                naturalType,
                listOf(implementationParameter),
            ),
        ).value as DotNetGenericOwnerSymbolicCarrierReference.Constructed
        val closure = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<*>>(
            physicalDeclarations.physicalInterfaceViewClosureOrError(implementationConstruction),
        ).value as DotNetGenericOwnerPhysicalInterfaceViewClosure
        assertTrue(closure.isComplete)
        assertTrue(DotNetGenericOwnerPhysicalView(naturalConstruction) in closure.interfaceViews)

        val methodIdentity = physicalAuthority.naturalMethodDefinition
        assertEquals(publication.naturalMethodDefPhysicalIdentity(), methodIdentity.method)
        val methodDescription = requireNotNull(
            physicalDeclarations.methodDescriptionOrNull(methodIdentity),
        )
        assertEquals(naturalType, methodDescription.declaringType)
        assertEquals(DotNetGenericOwnerPhysicalMemberVisibility.PUBLIC, methodDescription.visibility)
        assertEquals(DotNetGenericOwnerPhysicalMemberDispatch.ABSTRACT, methodDescription.dispatch)
        assertEquals(1, methodDescription.genericParameters.size)
        assertEquals(1, methodDescription.signature.genericArity)
        assertEquals(
            DotNetGenericOwnerSymbolicCarrierReference.Parameter.methodParameterReference(
                methodIdentity,
                0,
            ),
            methodDescription.signature.parameterSlots.single().carrier,
        )
        assertEquals(
            DotNetGenericOwnerSymbolicCarrierReference.Parameter.unboundTypeParameterReference(
                naturalType,
                0,
            ),
            (methodDescription.signature.resultLayout as
                    DotNetGenericOwnerPhysicalCallableResultLayoutReference.Direct).slot.carrier,
        )

        assertIs<DotNetGenericOwnerPhysicalBindingResult.Unavailable>(
            DotNetGenericOwnerPhysicalDeclarationIndex.bind(
                DotNetGenericOwnerPhysicalAuthorityEpoch.SEALED_EMISSION_SIGNATURE_INDEX,
                typeDefinitions = DotNetProducerGenericOwnerSealedTypeDefRole.entries.map { role ->
                    requireNotNull(
                        physicalDeclarations.typeDescriptionOrNull(
                            physicalAuthority.typeDefinition(role),
                        ),
                    )
                },
                methodDefinitions = listOf(methodDescription),
            ),
        )
    }

    @Test
    fun rejectsMalformedSemanticEquivalenceEnvelopeAndWrongIndex() {
        val publication = producerSealedFamilyPublicationFixture().withDirectResults()
        val valid = DotNetLibraryAbiCodec.encode(
            producerSealedFamilyAbiFixture(
                publication,
                DotNetPublishedGenericInterfaceMemberResultLayout.DIRECT,
            ) +
                    semanticEquivalenceCertificateEntry(publication),
        )
        val entry = valid.entries.single { candidate ->
            decodePropertyKey(candidate.key).startsWith("K:")
        }

        val invalidBase64 = valid + (entry.key to entry.value.mutateEnvelopeFields { fields ->
            fields[2] = "*"
        })
        val malformed = assertFailsWith<IllegalArgumentException> {
            DotNetLibraryAbiCodec.decode(invalidBase64.toProperties())
        }
        assertTrue(malformed.message.orEmpty().contains("invalid Base64"))

        val wrongIndex = valid - entry.key + (
                encodedPropertyKey("K:${"0".repeat(32)}") to entry.value
                )
        val identityFailure = assertFailsWith<IllegalArgumentException> {
            DotNetLibraryAbiCodec.decode(wrongIndex.toProperties())
        }
        assertTrue(identityFailure.message.orEmpty().contains("structured identity"))
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

        fun backendOutput(
            declarations: Map<String, DotNetPhysicalDeclaration>,
            genericOwnerRehearsal: Boolean,
        ): DotNetBackendOutput = DotNetBackendOutput(
            file = File("unused.dll"),
            declarations = declarations,
            genericOwnerPrototypes = emptyList(),
            genericOwnerCallRoutes = emptyList(),
            genericInterfaceCompleteSurfaceVarianceShadows = emptyList(),
            genericOwnerPhysicalValueShadows = emptyList(),
            genericOwnerPhysicalOperationRouteShadows = emptyList(),
            genericOwnerPhysicalValuePlacementComparisons = emptyList(),
            genericOwnerPhysicalMethodDefEmissionComparisons = emptyList(),
            genericOwnerCompleteEmissionComparisons = emptyList(),
            genericOwnerSealedEmissionFamilies = emptyList(),
            genericOwnerRehearsal = genericOwnerRehearsal,
        )

        fun interfaceOwnerKey(publication: DotNetProducerGenericOwnerSealedFamilyPublication): String =
            publication.key.logicalInterfaceMemberKey.substringBeforeLast('.')

        fun semanticEquivalenceCertificateEntry(
            publication: DotNetProducerGenericOwnerSealedFamilyPublication,
        ): Map<String, DotNetPhysicalDeclaration> {
            val sealed = publication.toPhysicalDeclaration()
            val certificate = DotNetProducerGenericOwnerSemanticEquivalenceCertificate
                .finalConcreteDirectTypedEntryChain(sealed.indexKey())
                .toPhysicalDeclaration()
            return mapOf(certificate.indexKey() to certificate)
        }

        fun producerSealedFamilyAbiFixture(
            publication: DotNetProducerGenericOwnerSealedFamilyPublication =
                producerSealedFamilyPublicationFixture(),
            publishedResultLayout: DotNetPublishedGenericInterfaceMemberResultLayout =
                DotNetPublishedGenericInterfaceMemberResultLayout.SPLIT_NULLABLE,
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
                        DotNetPublishedGenericInterfaceMemberRole.PRODUCER,
                        publishedResultLayout,
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

        fun implementationMethodDefAbiFixture(
            publication: DotNetProducerGenericOwnerSealedFamilyPublication =
                openImplementationPublicationFixture(),
        ): Map<String, DotNetPhysicalDeclaration> {
            val declarations = interfaceOnlyNaturalMethodDefAbiFixture(publication)
            val ownerKey = "demo/ExternalStore|class"
            val memberKey = "demo/ExternalStore.read|function"
            val ownerPath = listOf("demo.ExternalStore`1")
            val owner = DotNetPhysicalDeclaration.Class(
                ownerPath = ownerPath,
                physicalTypeParameterCount = 1,
                physicalTypeParameterVariances = listOf(
                    DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT,
                ),
            )
            val natural = declarations.values
                .filterIsInstance<DotNetPhysicalDeclaration.GenericOwnerNaturalMethodDef>()
                .single()
            val method = DotNetPhysicalDeclaration.Function(
                ownerPath = ownerPath,
                methodName = natural.physicalMethod.physicalMethodName,
                isInstance = true,
                methodGenericParameterCount = 0,
            )
            val implementation = DotNetPhysicalDeclaration.GenericOwnerImplementationMethodDef(
                logicalInterfaceMemberKey = natural.logicalMemberKey,
                implementationOwnerKey = ownerKey,
                implementationMemberKey = memberKey,
                ownerPath = ownerPath,
                ownerTypeParameterVariances = owner.physicalTypeParameterVariances,
                ownerVisibility = DotNetGenericOwnerPhysicalTypeVisibility.PUBLIC,
                ownerDispatch = DotNetGenericOwnerPhysicalTypeDispatch.OVERRIDABLE,
                naturalInterfaceTypeArguments = listOf(
                    DotNetGenericOwnerPhysicalTypeExpressionRecord.ownerParameter(0),
                ),
                physicalMethod = natural.physicalMethod.copy(physicalOwnerPath = ownerPath),
                methodVisibility = DotNetGenericOwnerPhysicalMemberVisibility.PUBLIC,
                methodDispatch = DotNetGenericOwnerPhysicalMemberDispatch.OVERRIDABLE,
                methodIntroducesSlot = true,
                methodIsHideBySig = true,
                methodIsSpecialName = false,
                methodIsRuntimeSpecialName = false,
            )
            return declarations + mapOf(
                ownerKey to owner,
                memberKey to method,
                implementation.indexKey() to implementation,
            )
        }

        fun openImplementationPublicationFixture():
                DotNetProducerGenericOwnerSealedFamilyPublication {
            val publication = producerSealedFamilyPublicationFixture()
            val physicalPaths = mapOf(
                DotNetProducerGenericOwnerSealedTypeDefRole.NATURAL_INTERFACE to
                        listOf("demo.OpenSource`1"),
                DotNetProducerGenericOwnerSealedTypeDefRole.INTERFACE_SEMANTIC_CAPABILITY to
                        listOf("demo.OpenSourceSemantic"),
                DotNetProducerGenericOwnerSealedTypeDefRole.IMPLEMENTATION_CLASS to
                        listOf("demo.OpenStore`1"),
                DotNetProducerGenericOwnerSealedTypeDefRole.CLASS_SEMANTIC_CAPABILITY to
                        listOf("demo.OpenStoreSemantic"),
            )
            return publication.copy(
                key = DotNetProducerGenericOwnerSealedFamilyKey(
                    "demo/OpenSource.read|function",
                    "demo/OpenStore|class",
                    "demo/OpenStore.read|function",
                ),
                body = publication.body.copy(
                    typeDefs = publication.body.typeDefs.map { type ->
                        type.copy(row = type.row.copy(physicalPath = physicalPaths.getValue(type.role)))
                    },
                    methodDefs = publication.body.methodDefs.map { method ->
                        method.copy(
                            row = method.row.copy(
                                structural = method.row.structural.copy(
                                    header = method.row.structural.header.copy(
                                        genericArity = 0,
                                        ordinaryParameterCarriers = emptyList(),
                                    ),
                                    genericParameters = emptyList(),
                                ),
                                physicalGenericParameterNames = emptyList(),
                            ),
                            logicalParameterDomains = emptyList(),
                        )
                    },
                ),
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

        fun Map<String, String>.mutateImplementationMethodDefDeclarationValue(
            transform: (String) -> String,
        ): Map<String, String> {
            val entry = entries.single { candidate -> decodePropertyKey(candidate.key).startsWith("M:") }
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
