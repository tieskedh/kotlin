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

class DotNetProducerGenericOwnerSemanticEquivalenceCertificateTest {
    @Test
    fun deterministicallyRoundTripsTheBoundedRoleChain() {
        val family = producerSealedFamilyPublicationFixture().toPhysicalDeclaration()
        val certificate = DotNetProducerGenericOwnerSemanticEquivalenceCertificate
            .finalConcreteDirectTypedEntryChain(family.indexKey())

        val first = DotNetProducerGenericOwnerSemanticEquivalenceCertificateCodec.encode(certificate)
        val second = DotNetProducerGenericOwnerSemanticEquivalenceCertificateCodec.encode(certificate)
        assertContentEquals(first, second)
        val decoded = assertIs<
                DotNetProducerGenericOwnerSemanticEquivalenceCertificateDecodeResult.Success>(
            DotNetProducerGenericOwnerSemanticEquivalenceCertificateCodec.decode(first),
        ).certificate
        assertEquals(certificate, decoded)
        assertEquals(
            listOf(
                DotNetProducerGenericOwnerSemanticEquivalenceRoleEdge(
                    DotNetProducerGenericOwnerSealedMethodDefRole
                        .CLASS_SEMANTIC_CAPABILITY_DISPATCHER,
                    DotNetProducerGenericOwnerSealedMethodDefRole.IMPLEMENTATION_TYPED_ENTRY,
                ),
                DotNetProducerGenericOwnerSemanticEquivalenceRoleEdge(
                    DotNetProducerGenericOwnerSealedMethodDefRole
                        .INTERFACE_SEMANTIC_CAPABILITY_DISPATCHER,
                    DotNetProducerGenericOwnerSealedMethodDefRole
                        .CLASS_SEMANTIC_CAPABILITY_DISPATCHER,
                ),
            ),
            decoded.roleEdges,
        )
    }

    @Test
    fun rejectsMissingReorderedOrCrossWiredRoleEdges() {
        val family = producerSealedFamilyPublicationFixture().toPhysicalDeclaration()
        val valid = DotNetProducerGenericOwnerSemanticEquivalenceCertificate
            .finalConcreteDirectTypedEntryChain(family.indexKey())

        listOf(
            valid.roleEdges.dropLast(1),
            valid.roleEdges.asReversed(),
            valid.roleEdges + valid.roleEdges.first(),
            valid.roleEdges.mapIndexed { index, edge ->
                if (index == 0) edge.copy(
                    target = DotNetProducerGenericOwnerSealedMethodDefRole.NATURAL_INTERFACE_SLOT,
                ) else edge
            },
        ).forEachIndexed { index, edges ->
            val failure = assertFailsWith<IllegalArgumentException>("hostile edge set $index was accepted") {
                valid.copy(roleEdges = edges)
            }
            assertTrue(failure.message.orEmpty().contains("complete ordered role-edge proof"))
        }
    }

    @Test
    fun bindsOnlyToTheExactProducerSealedFamily() {
        val splitPublication = producerSealedFamilyPublicationFixture()
        val splitSealed = splitPublication.toPhysicalDeclaration()
        val splitCertificate = DotNetProducerGenericOwnerSemanticEquivalenceCertificate
            .finalConcreteDirectTypedEntryChain(splitSealed.indexKey())
        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            inspectDotNetProducerGenericOwnerSemanticEquivalenceCertificate(
                splitCertificate,
                splitSealed.indexKey(),
                splitPublication,
            ),
        )

        val publication = producerSealedFamilyPublicationFixture().withDirectResults()
        val sealed = publication.toPhysicalDeclaration()
        val certificate = DotNetProducerGenericOwnerSemanticEquivalenceCertificate
            .finalConcreteDirectTypedEntryChain(sealed.indexKey())

        val authority = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<*>>(
            inspectDotNetProducerGenericOwnerSemanticEquivalenceCertificate(
                certificate,
                sealed.indexKey(),
                publication,
            ),
        ).value as DotNetProducerGenericOwnerSemanticEquivalenceAuthority
        assertEquals(DotNetGenericOwnerPhysicalAuthorityEpoch.SEALED_EMISSION_SIGNATURE_INDEX, authority.epoch)
        assertEquals(publication, authority.sealedFamily.publication)

        assertIs<DotNetGenericOwnerPhysicalBindingResult.Unavailable>(
            inspectDotNetProducerGenericOwnerSemanticEquivalenceCertificate(null, null, null),
        )
        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            inspectDotNetProducerGenericOwnerSemanticEquivalenceCertificate(
                certificate,
                "J:${"0".repeat(32)}",
                publication,
            ),
        )
        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            inspectDotNetProducerGenericOwnerSemanticEquivalenceCertificate(
                certificate,
                null,
                null,
            ),
        )
    }

    @Test
    fun rejectsTruncatedUnknownAndTrailingPayloads() {
        val certificate = DotNetProducerGenericOwnerSemanticEquivalenceCertificate
            .finalConcreteDirectTypedEntryChain(
                producerSealedFamilyPublicationFixture().toPhysicalDeclaration().indexKey(),
            )
        val encoded = DotNetProducerGenericOwnerSemanticEquivalenceCertificateCodec.encode(certificate)

        assertIs<DotNetProducerGenericOwnerSemanticEquivalenceCertificateDecodeResult.Malformed>(
            DotNetProducerGenericOwnerSemanticEquivalenceCertificateCodec.decode(
                encoded.dropLast(1).toByteArray(),
            ),
        )
        assertIs<DotNetProducerGenericOwnerSemanticEquivalenceCertificateDecodeResult.Malformed>(
            DotNetProducerGenericOwnerSemanticEquivalenceCertificateCodec.decode(
                encoded.copyOf().also { bytes -> bytes[0] = 0 },
            ),
        )
        assertIs<DotNetProducerGenericOwnerSemanticEquivalenceCertificateDecodeResult.Malformed>(
            DotNetProducerGenericOwnerSemanticEquivalenceCertificateCodec.decode(
                encoded + byteArrayOf(0),
            ),
        )
    }

    private fun DotNetProducerGenericOwnerSealedFamilyPublication.withDirectResults():
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
}
