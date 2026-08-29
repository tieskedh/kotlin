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
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * Final declaration-level authority for one Kotlin-produced natural CLR interface slot.
 *
 * Unlike [DotNetProducerGenericOwnerSealedFamilyPublication], this record does not require a
 * Kotlin implementation class, semantic hook, dispatcher, or MethodImpl.  An interface-only
 * producer still emits one authoritative TypeDef and MethodDef, so those two rows are sealed as
 * one atomic declaration fact together with the KLIB-owned value-position domains.
 */
internal data class DotNetProducerGenericOwnerNaturalMethodDefPublication(
    val logicalOwnerKey: String,
    val logicalMemberKey: String,
    val naturalType: DotNetGenericOwnerSealedEmissionTypeDefRow,
    val naturalMethod: DotNetProducerGenericOwnerSealedMethodDef,
) {
    init {
        require(logicalOwnerKey.isNotEmpty() && logicalMemberKey.isNotEmpty() &&
                '\u0000' !in logicalOwnerKey && '\u0000' !in logicalMemberKey) {
            "a natural MethodDef publication requires exact NUL-free logical owner and member keys"
        }
        require(naturalMethod.role ==
                DotNetProducerGenericOwnerSealedMethodDefRole.NATURAL_INTERFACE_SLOT) {
            "a natural MethodDef publication requires the natural-interface slot role"
        }
        require(naturalMethod.row.structural.header.owner == naturalType.structural.identityKey) {
            "a natural MethodDef publication requires its MethodDef to belong to its sealed TypeDef"
        }
        require(hasBoundedDotNetProducerGenericOwnerNaturalMethodDefGrammar(naturalType, naturalMethod)) {
            "a natural MethodDef publication requires the bounded public root-interface producer grammar"
        }
        require(naturalMethod.logicalParameterDomains.size ==
                naturalMethod.row.structural.header.ordinaryParameterCarriers.size) {
            "a natural MethodDef publication requires one logical domain per ordinary parameter"
        }
        val hasValueResult = naturalMethod.row.structural.header.result !=
                DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.Void
        require(hasValueResult == (naturalMethod.logicalResultDomain != null)) {
            "a natural MethodDef publication requires exactly one logical domain for a value result"
        }
    }
}

internal fun hasBoundedDotNetProducerGenericOwnerNaturalMethodDefGrammar(
    naturalType: DotNetGenericOwnerSealedEmissionTypeDefRow,
    naturalMethod: DotNetProducerGenericOwnerSealedMethodDef,
): Boolean {
    val type = naturalType.structural
    val typeFlags = naturalType.flags
    if (type.category != DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE ||
        type.genericArity <= 0 || type.directEdges.isNotEmpty() ||
        type.genericParameters.any { parameter -> parameter.constraints.isNotEmpty() } ||
        typeFlags.visibility != DotNetIlRawTypeDefVisibility.PUBLIC ||
        typeFlags.layout != DotNetIlRawTypeDefLayout.AUTO ||
        typeFlags.stringFormat != DotNetIlRawTypeDefStringFormat.ANSI ||
        !typeFlags.isInterface || !typeFlags.isAbstract || typeFlags.isSealed ||
        typeFlags.isBeforeFieldInit
    ) {
        return false
    }
    val method = naturalMethod.row
    val header = method.structural.header
    if (header.ownerCategory != DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE ||
        header.visibility != DotNetGenericOwnerPhysicalMethodDefEmissionVisibility.PUBLIC ||
        header.dispatch != DotNetGenericOwnerPhysicalMemberDispatch.ABSTRACT ||
        method.visibility != DotNetIlRawMethodDefVisibility.PUBLIC ||
        !method.dispatch.isInstance || !method.dispatch.isVirtual || !method.dispatch.isNewSlot ||
        !method.dispatch.isAbstract || method.dispatch.isFinal || !method.isHideBySig ||
        method.isSpecialName || method.isRuntimeSpecialName ||
        method.structural.genericParameters.any { parameter -> parameter.constraints.isNotEmpty() }
    ) {
        return false
    }
    val receiver = header.receiverCarrier as?
            DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.Construction
        ?: return false
    if (receiver.definition != type.identityKey || receiver.arguments.size != type.genericArity ||
        receiver.arguments.mapIndexed { index, argument ->
            argument is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.OwnerParameter &&
                    argument.binder == type.identityKey && argument.index == index
        }.any { matches -> !matches }
    ) {
        return false
    }
    val resultCarrier = when (val result = header.result) {
        DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.Void -> null
        is DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.Direct -> result.carrier
        is DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.SplitNullable -> result.payload
    }
    return resultCarrier is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.OwnerParameter &&
            resultCarrier.binder == type.identityKey && resultCarrier.index in 0 until type.genericArity
}

internal sealed interface DotNetProducerGenericOwnerNaturalMethodDefDecodeResult {
    data class Success(
        val publication: DotNetProducerGenericOwnerNaturalMethodDefPublication,
    ) : DotNetProducerGenericOwnerNaturalMethodDefDecodeResult

    data class Malformed(val reason: String) : DotNetProducerGenericOwnerNaturalMethodDefDecodeResult {
        init {
            require(reason.isNotEmpty()) { "a malformed natural MethodDef publication requires a reason" }
        }
    }
}

/** A decoded declaration seal is admitted atomically or exposes no physical MethodDef. */
internal fun inspectDotNetProducerGenericOwnerNaturalMethodDef(
    publication: DotNetProducerGenericOwnerNaturalMethodDefPublication?,
): DotNetGenericOwnerPhysicalBindingResult<DotNetProducerGenericOwnerNaturalMethodDefPublication> {
    publication ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
    val actual = DotNetGenericOwnerSealedEmissionManifestEvidence.Known(
        typeDefs = listOf(publication.naturalType),
        methodDefs = listOf(publication.naturalMethod.row),
        methodImpls = emptyList(),
    )
    val structural = DotNetGenericOwnerCompleteEmissionManifest(
        typeDefs = actual.typeDefs.map { row -> row.structural },
        methodDefs = actual.methodDefs.map { row -> row.structural },
        methodImpls = emptyList(),
    )
    val inspection = inspectDotNetGenericOwnerSealedEmissionSignatureIndex(structural, actual)
    return when (val binding = inspection.binding) {
        is DotNetGenericOwnerPhysicalBindingResult.Bound ->
            DotNetGenericOwnerPhysicalBindingResult.Bound(publication)
        is DotNetGenericOwnerPhysicalBindingResult.Conflict -> binding
        DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
            DotNetGenericOwnerPhysicalBindingResult.Conflict(
                inspection.diagnostics.ifEmpty {
                    listOf("a claimed natural MethodDef publication is incomplete")
                }.joinToString("; "),
            )
    }
}

/** Versioned deterministic payload used by the ABI-63 `N` declaration envelope. */
internal object DotNetProducerGenericOwnerNaturalMethodDefCodec {
    private const val MAGIC = 0x4B_44_4E_4D // KDNM
    private const val VERSION = 1
    private const val MAX_BYTES = 16 * 1_048_576
    private const val MAX_STRING_BYTES = 1_048_576
    private const val MAX_DOMAINS = 1_024

    fun encode(publication: DotNetProducerGenericOwnerNaturalMethodDefPublication): ByteArray {
        val canonical = publication.canonicalizedForWire()
        require(inspectDotNetProducerGenericOwnerNaturalMethodDef(canonical) is
                DotNetGenericOwnerPhysicalBindingResult.Bound) {
            "cannot encode an invalid natural MethodDef publication"
        }
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { sink ->
            sink.writeInt(MAGIC)
            sink.writeInt(VERSION)
            sink.writeNaturalString(canonical.logicalOwnerKey)
            sink.writeNaturalString(canonical.logicalMemberKey)
            DotNetProducerGenericOwnerSealedFamilyCodec.run {
                sink.writeTypeDef(canonical.naturalType)
                sink.writeMethodDef(canonical.naturalMethod.row)
            }
            sink.writeInt(canonical.naturalMethod.logicalParameterDomains.size)
            canonical.naturalMethod.logicalParameterDomains.forEach { domain ->
                sink.writeInt(domain.ordinal)
            }
            sink.writeInt(canonical.naturalMethod.logicalResultDomain?.ordinal ?: -1)
        }
        return output.toByteArray()
    }

    fun decode(bytes: ByteArray): DotNetProducerGenericOwnerNaturalMethodDefDecodeResult {
        if (bytes.size > MAX_BYTES) {
            return DotNetProducerGenericOwnerNaturalMethodDefDecodeResult.Malformed(
                "the natural MethodDef publication exceeds its bounded size",
            )
        }
        return try {
            val source = DataInputStream(ByteArrayInputStream(bytes))
            require(source.readInt() == MAGIC) { "wrong natural MethodDef publication magic" }
            require(source.readInt() == VERSION) { "unsupported natural MethodDef publication version" }
            val logicalOwnerKey = source.readNaturalString()
            val logicalMemberKey = source.readNaturalString()
            val naturalType: DotNetGenericOwnerSealedEmissionTypeDefRow
            val naturalMethod: DotNetGenericOwnerSealedEmissionMethodDefRow
            DotNetProducerGenericOwnerSealedFamilyCodec.run {
                naturalType = source.readTypeDef()
                naturalMethod = source.readMethodDef()
            }
            val domainCount = source.readInt()
            require(domainCount in 0..MAX_DOMAINS) {
                "invalid natural MethodDef parameter-domain count"
            }
            val domains = List(domainCount) {
                source.readNaturalEnum<DotNetGenericOwnerPhysicalSlotDomain>()
            }
            val resultDomainOrdinal = source.readInt()
            val resultDomain = when (resultDomainOrdinal) {
                -1 -> null
                else -> enumValues<DotNetGenericOwnerPhysicalSlotDomain>()
                    .getOrNull(resultDomainOrdinal)
                    ?: throw IllegalArgumentException("invalid natural MethodDef result domain")
            }
            require(source.available() == 0) { "trailing natural MethodDef publication bytes" }
            val publication = DotNetProducerGenericOwnerNaturalMethodDefPublication(
                logicalOwnerKey,
                logicalMemberKey,
                naturalType,
                DotNetProducerGenericOwnerSealedMethodDef(
                    DotNetProducerGenericOwnerSealedMethodDefRole.NATURAL_INTERFACE_SLOT,
                    naturalMethod,
                    domains,
                    resultDomain,
                ),
            )
            when (val inspection = inspectDotNetProducerGenericOwnerNaturalMethodDef(publication)) {
                is DotNetGenericOwnerPhysicalBindingResult.Bound ->
                    DotNetProducerGenericOwnerNaturalMethodDefDecodeResult.Success(publication)
                is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                    DotNetProducerGenericOwnerNaturalMethodDefDecodeResult.Malformed(inspection.reason)
                DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                    DotNetProducerGenericOwnerNaturalMethodDefDecodeResult.Malformed(
                        "a decoded natural MethodDef publication unexpectedly lacked authority",
                    )
            }
        } catch (failure: Exception) {
            val reason = when (failure) {
                is EOFException -> "truncated natural MethodDef publication"
                else -> failure.message ?: failure::class.java.simpleName
            }
            DotNetProducerGenericOwnerNaturalMethodDefDecodeResult.Malformed(reason)
        }
    }

    private fun DotNetProducerGenericOwnerNaturalMethodDefPublication.canonicalizedForWire():
            DotNetProducerGenericOwnerNaturalMethodDefPublication {
        val oldTypeKey = naturalType.structural.identityKey
        val oldMethodKey = naturalMethod.row.structural.identityKey
        val typeKey = DotNetGenericOwnerPhysicalMethodDefEmissionTypeKey(0)
        val methodKey = DotNetGenericOwnerPhysicalMethodDefEmissionMethodKey(0)

        fun carrier(
            value: DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape,
        ): DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape = when (value) {
            is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.Leaf -> value
            is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.OwnerParameter -> {
                require(value.binder == oldTypeKey) {
                    "a natural MethodDef publication cannot retain an external TypeDef-parameter binder"
                }
                value.copy(binder = typeKey)
            }
            is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.MethodParameter -> {
                require(value.binder == oldMethodKey) {
                    "a natural MethodDef publication cannot retain an external MethodDef-parameter binder"
                }
                value.copy(binder = methodKey)
            }
            is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.Construction -> {
                require(value.definition == oldTypeKey) {
                    "the bounded natural MethodDef publication cannot retain another local TypeDef"
                }
                value.copy(definition = typeKey, arguments = value.arguments.map(::carrier))
            }
            is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.SzArray ->
                value.copy(element = carrier(value.element))
            is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.ByReference ->
                value.copy(element = carrier(value.element))
            DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.Other -> value
        }
        fun parameter(
            value: DotNetGenericOwnerCompleteEmissionGenericParameterRow,
        ) = value.copy(constraints = value.constraints.map(::carrier))
        fun result(
            value: DotNetGenericOwnerPhysicalMethodDefEmissionResultShape,
        ): DotNetGenericOwnerPhysicalMethodDefEmissionResultShape = when (value) {
            DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.Void -> value
            is DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.Direct ->
                value.copy(carrier = carrier(value.carrier))
            is DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.SplitNullable ->
                value.copy(payload = carrier(value.payload))
        }

        val canonicalType = naturalType.copy(
            structural = naturalType.structural.copy(
                identityKey = typeKey,
                aliases = List(naturalType.structural.aliases.size) { index ->
                    DotNetGenericOwnerCompleteEmissionTypeDefAliasKey(index)
                },
                genericParameters = naturalType.structural.genericParameters.map(::parameter),
                directEdges = naturalType.structural.directEdges.map { edge ->
                    edge.copy(target = carrier(edge.target))
                },
            ),
        )
        val row = naturalMethod.row
        val header = row.structural.header
        val canonicalMethod = naturalMethod.copy(row = row.copy(
            structural = row.structural.copy(
                identityKey = methodKey,
                header = header.copy(
                    owner = typeKey,
                    receiverCarrier = header.receiverCarrier?.let(::carrier),
                    ordinaryParameterCarriers = header.ordinaryParameterCarriers.map(::carrier),
                    result = result(header.result),
                ),
                genericParameters = row.structural.genericParameters.map(::parameter),
            ),
        ))
        return copy(naturalType = canonicalType, naturalMethod = canonicalMethod)
    }

    private fun DataOutputStream.writeNaturalString(value: String) {
        require('\u0000' !in value) { "a natural MethodDef publication string cannot contain NUL" }
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_STRING_BYTES) { "a natural MethodDef publication string is too large" }
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readNaturalString(): String {
        val size = readInt()
        require(size in 0..MAX_STRING_BYTES) { "invalid natural MethodDef publication string size" }
        val bytes = ByteArray(size)
        readFully(bytes)
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return decoder.decode(ByteBuffer.wrap(bytes)).toString().also { value ->
            require('\u0000' !in value) { "a natural MethodDef publication string contains NUL" }
        }
    }

    private inline fun <reified T : Enum<T>> DataInputStream.readNaturalEnum(): T {
        val ordinal = readInt()
        return enumValues<T>().getOrNull(ordinal)
            ?: throw IllegalArgumentException("invalid ${T::class.simpleName} ordinal")
    }
}
