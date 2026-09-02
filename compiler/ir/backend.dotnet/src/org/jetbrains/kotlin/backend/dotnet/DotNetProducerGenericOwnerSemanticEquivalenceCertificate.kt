/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException

/**
 * Producer-side proof vocabulary for a semantic route which is observationally the natural route.
 *
 * This is declaration authority, not value provenance.  A consumer still needs an exact concrete
 * receiver fact before it may use the certificate to select a natural operation.
 */
internal enum class DotNetProducerGenericOwnerSemanticEquivalenceProofKind {
    /**
     * A final concrete implementation whose generated semantic dispatchers form the exact direct
     * chain recorded by [DotNetProducerGenericOwnerSemanticEquivalenceCertificate.roleEdges].
     */
    FINAL_CONCRETE_DIRECT_TYPED_ENTRY_CHAIN,
}

/** One producer-proved direct body delegation between two MethodDef roles of the referenced `J`. */
internal data class DotNetProducerGenericOwnerSemanticEquivalenceRoleEdge(
    val source: DotNetProducerGenericOwnerSealedMethodDefRole,
    val target: DotNetProducerGenericOwnerSealedMethodDefRole,
)

/**
 * Orthogonal semantic-equivalence certificate for one exact producer-sealed `J` family.
 *
 * The certificate deliberately contains no TypeDef, MethodDef, or MethodImpl row.  Its exact `J`
 * index key is joined to those rows in the containing physical-library ABI before this fact can be
 * exposed to a consumer.  The role edges state body delegation; they are not inferred from equal
 * signatures or from Kotlin override relationships.
 */
internal data class DotNetProducerGenericOwnerSemanticEquivalenceCertificate(
    val sealedFamilyIndexKey: String,
    val proofKind: DotNetProducerGenericOwnerSemanticEquivalenceProofKind,
    val roleEdges: List<DotNetProducerGenericOwnerSemanticEquivalenceRoleEdge>,
) {
    init {
        require(SEALED_FAMILY_INDEX_KEY.matches(sealedFamilyIndexKey)) {
            "a semantic-equivalence certificate requires one exact producer-sealed J index key"
        }
        require(roleEdges == proofKind.requiredRoleEdges) {
            "a semantic-equivalence certificate requires the complete ordered role-edge proof"
        }
    }

    companion object {
        private val SEALED_FAMILY_INDEX_KEY = Regex("J:[0-9a-f]{32}")

        fun finalConcreteDirectTypedEntryChain(
            sealedFamilyIndexKey: String,
        ): DotNetProducerGenericOwnerSemanticEquivalenceCertificate {
            val proofKind = DotNetProducerGenericOwnerSemanticEquivalenceProofKind
                .FINAL_CONCRETE_DIRECT_TYPED_ENTRY_CHAIN
            return DotNetProducerGenericOwnerSemanticEquivalenceCertificate(
                sealedFamilyIndexKey,
                proofKind,
                proofKind.requiredRoleEdges,
            )
        }
    }
}

internal val DotNetProducerGenericOwnerSemanticEquivalenceProofKind.requiredRoleEdges:
        List<DotNetProducerGenericOwnerSemanticEquivalenceRoleEdge>
    get() = when (this) {
        DotNetProducerGenericOwnerSemanticEquivalenceProofKind
            .FINAL_CONCRETE_DIRECT_TYPED_ENTRY_CHAIN -> listOf(
            DotNetProducerGenericOwnerSemanticEquivalenceRoleEdge(
                DotNetProducerGenericOwnerSealedMethodDefRole.CLASS_SEMANTIC_CAPABILITY_DISPATCHER,
                DotNetProducerGenericOwnerSealedMethodDefRole.IMPLEMENTATION_TYPED_ENTRY,
            ),
            DotNetProducerGenericOwnerSemanticEquivalenceRoleEdge(
                DotNetProducerGenericOwnerSealedMethodDefRole.INTERFACE_SEMANTIC_CAPABILITY_DISPATCHER,
                DotNetProducerGenericOwnerSealedMethodDefRole.CLASS_SEMANTIC_CAPABILITY_DISPATCHER,
            ),
        )
    }

internal sealed interface DotNetProducerGenericOwnerSemanticEquivalenceCertificateDecodeResult {
    data class Success(
        val certificate: DotNetProducerGenericOwnerSemanticEquivalenceCertificate,
    ) : DotNetProducerGenericOwnerSemanticEquivalenceCertificateDecodeResult

    data class Malformed(
        val reason: String,
    ) : DotNetProducerGenericOwnerSemanticEquivalenceCertificateDecodeResult {
        init {
            require(reason.isNotEmpty()) { "a malformed semantic-equivalence certificate requires a reason" }
        }
    }
}

/**
 * A certificate admitted together with the exact sealed family whose physical rows it references.
 */
internal data class DotNetProducerGenericOwnerSemanticEquivalenceAuthority(
    val certificate: DotNetProducerGenericOwnerSemanticEquivalenceCertificate,
    val sealedFamily: DotNetProducerGenericOwnerSealedFamilyAuthority,
) {
    val epoch: DotNetGenericOwnerPhysicalAuthorityEpoch
        get() = DotNetGenericOwnerPhysicalAuthorityEpoch.SEALED_EMISSION_SIGNATURE_INDEX
}

/**
 * Joins a claimed certificate to one exact `J`.  Absence is optimization-unavailable; a claimed
 * malformed or cross-wired relationship is a physical-authority conflict.
 */
internal fun inspectDotNetProducerGenericOwnerSemanticEquivalenceCertificate(
    certificate: DotNetProducerGenericOwnerSemanticEquivalenceCertificate?,
    sealedFamilyIndexKey: String?,
    sealedFamilyPublication: DotNetProducerGenericOwnerSealedFamilyPublication?,
): DotNetGenericOwnerPhysicalBindingResult<DotNetProducerGenericOwnerSemanticEquivalenceAuthority> {
    certificate ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
    if (sealedFamilyIndexKey == null || sealedFamilyPublication == null) {
        return DotNetGenericOwnerPhysicalBindingResult.Conflict(
            "a claimed semantic-equivalence certificate has no same-library producer-sealed J family",
        )
    }
    if (certificate.sealedFamilyIndexKey != sealedFamilyIndexKey) {
        return DotNetGenericOwnerPhysicalBindingResult.Conflict(
            "a semantic-equivalence certificate is cross-wired to another producer-sealed J family",
        )
    }
    if (sealedFamilyPublication.key.physicalIndexKey() != sealedFamilyIndexKey) {
        return DotNetGenericOwnerPhysicalBindingResult.Conflict(
            "a semantic-equivalence certificate and its J publication have different exact identities",
        )
    }
    return when (val family = inspectDotNetProducerGenericOwnerSealedFamily(sealedFamilyPublication)) {
        is DotNetGenericOwnerPhysicalBindingResult.Bound -> {
            val incompatibleReason = certificate.proofKind
                .incompatibilityWithOrNull(family.value.publication)
            if (incompatibleReason != null) {
                DotNetGenericOwnerPhysicalBindingResult.Conflict(incompatibleReason)
            } else {
                DotNetGenericOwnerPhysicalBindingResult.Bound(
                    DotNetProducerGenericOwnerSemanticEquivalenceAuthority(certificate, family.value),
                )
            }
        }
        is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
            DotNetGenericOwnerPhysicalBindingResult.Conflict(
                "a semantic-equivalence certificate references a conflicting J family: ${family.reason}",
            )
        DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
            DotNetGenericOwnerPhysicalBindingResult.Conflict(
                "a semantic-equivalence certificate references an unavailable J family",
            )
    }
}

/** Rejects proof kinds which cannot describe the referenced J calling convention. */
private fun DotNetProducerGenericOwnerSemanticEquivalenceProofKind.incompatibilityWithOrNull(
    publication: DotNetProducerGenericOwnerSealedFamilyPublication,
): String? = when (this) {
    DotNetProducerGenericOwnerSemanticEquivalenceProofKind
        .FINAL_CONCRETE_DIRECT_TYPED_ENTRY_CHAIN -> {
        val methods = publication.body.methodDefs.associateBy { method -> method.role }
        val typedRoles = listOf(
            DotNetProducerGenericOwnerSealedMethodDefRole.NATURAL_INTERFACE_SLOT,
            DotNetProducerGenericOwnerSealedMethodDefRole.IMPLEMENTATION_TYPED_ENTRY,
        )
        if (typedRoles.any { role ->
                methods.getValue(role).row.structural.header.result !is
                        DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.Direct
            }
        ) {
            "the direct typed-entry semantic-equivalence proof requires direct natural and " +
                    "implementation result layouts"
        } else {
            null
        }
    }
}

/** Versioned deterministic payload used by the physical-library `K` relationship record. */
internal object DotNetProducerGenericOwnerSemanticEquivalenceCertificateCodec {
    private const val MAGIC = 0x4B_44_53_45 // KDSE
    private const val VERSION = 1
    private const val MAX_BYTES = 16 * 1_024
    private const val MAX_INDEX_KEY_BYTES = 1_024
    private const val MAX_ROLE_EDGES = 16

    fun encode(certificate: DotNetProducerGenericOwnerSemanticEquivalenceCertificate): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { sink ->
            sink.writeInt(MAGIC)
            sink.writeInt(VERSION)
            sink.writeCertificateString(certificate.sealedFamilyIndexKey)
            sink.writeInt(certificate.proofKind.ordinal)
            sink.writeInt(certificate.roleEdges.size)
            certificate.roleEdges.forEach { edge ->
                sink.writeInt(edge.source.ordinal)
                sink.writeInt(edge.target.ordinal)
            }
        }
        return output.toByteArray()
    }

    fun decode(bytes: ByteArray): DotNetProducerGenericOwnerSemanticEquivalenceCertificateDecodeResult {
        if (bytes.size > MAX_BYTES) {
            return DotNetProducerGenericOwnerSemanticEquivalenceCertificateDecodeResult.Malformed(
                "the semantic-equivalence certificate exceeds its bounded size",
            )
        }
        return try {
            val source = DataInputStream(ByteArrayInputStream(bytes))
            require(source.readInt() == MAGIC) { "wrong semantic-equivalence certificate magic" }
            require(source.readInt() == VERSION) { "unsupported semantic-equivalence certificate version" }
            val sealedFamilyIndexKey = source.readCertificateString()
            val proofKind = source.readCertificateEnum<
                    DotNetProducerGenericOwnerSemanticEquivalenceProofKind>()
            val edgeCount = source.readInt()
            require(edgeCount in 0..MAX_ROLE_EDGES) {
                "invalid semantic-equivalence role-edge count"
            }
            val edges = List(edgeCount) {
                DotNetProducerGenericOwnerSemanticEquivalenceRoleEdge(
                    source.readCertificateEnum(),
                    source.readCertificateEnum(),
                )
            }
            require(source.available() == 0) { "trailing semantic-equivalence certificate bytes" }
            DotNetProducerGenericOwnerSemanticEquivalenceCertificateDecodeResult.Success(
                DotNetProducerGenericOwnerSemanticEquivalenceCertificate(
                    sealedFamilyIndexKey,
                    proofKind,
                    edges,
                ),
            )
        } catch (failure: Exception) {
            val reason = when (failure) {
                is EOFException -> "truncated semantic-equivalence certificate"
                else -> failure.message ?: failure::class.java.simpleName
            }
            DotNetProducerGenericOwnerSemanticEquivalenceCertificateDecodeResult.Malformed(reason)
        }
    }

    private fun DataOutputStream.writeCertificateString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_INDEX_KEY_BYTES) {
            "a semantic-equivalence certificate string is too large"
        }
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readCertificateString(): String {
        val size = readInt()
        require(size in 0..MAX_INDEX_KEY_BYTES) {
            "invalid semantic-equivalence certificate string size"
        }
        val bytes = ByteArray(size)
        readFully(bytes)
        return bytes.toString(Charsets.UTF_8)
    }

    private inline fun <reified E : Enum<E>> DataInputStream.readCertificateEnum(): E {
        val ordinal = readInt()
        return enumValues<E>().getOrNull(ordinal)
            ?: throw IllegalArgumentException("invalid ${E::class.simpleName} ordinal")
    }
}
