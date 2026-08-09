/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.load.dotnet

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

enum class DotNetClrAssemblyMetadataFailure {
    INVALID_CONSTRUCTOR,
    VALUE_DECODING_FAILED,
}

data class DotNetClrAssemblyMetadataEntry(
    val attribute: DotNetClrMetadataHandle,
    val key: String?,
    val value: String?,
)

sealed interface DotNetClrAssemblyMetadataResolution {
    data class Decoded(
        val entries: List<DotNetClrAssemblyMetadataEntry>,
    ) : DotNetClrAssemblyMetadataResolution

    data class Invalid(
        val failure: DotNetClrAssemblyMetadataFailure,
        val attributes: List<DotNetClrMetadataHandle>,
    ) : DotNetClrAssemblyMetadataResolution
}

/**
 * Decodes a compiler-owned assembly's standard `AssemblyMetadataAttribute` values.
 *
 * Unlike foreign-annotation import, platform-pair authentication also runs when a Kotlin-only
 * `-no-stdlib` consumer has not selected physical BCL reference assemblies. The decoder therefore
 * proves the exact ECMA-335 MemberRef, top-level TypeRef, AssemblyRef owner, constructor signature,
 * and value blob from the runtime image itself. [attributeAssemblyName] is the already-selected
 * target-profile contract supplied by the caller; a local or differently scoped name look-alike
 * cannot contribute metadata.
 */
fun decodeDotNetClrAssemblyMetadata(
    assembly: DotNetClrAssemblyMetadata,
    attributeAssemblyName: String,
): DotNetClrAssemblyMetadataResolution {
    val entries = mutableListOf<DotNetClrAssemblyMetadataEntry>()
    for (attribute in assembly.customAttributes) {
        if (attribute.parent != ASSEMBLY_DEFINITION_HANDLE) continue
        val member = assembly.memberReferences.singleOrNull { candidate ->
            candidate.handle == attribute.constructor
        } ?: continue
        val type = assembly.typeReferences.singleOrNull { candidate ->
            candidate.handle == member.parent
        } ?: continue
        if (
            type.namespaceName != ASSEMBLY_METADATA_NAMESPACE ||
            type.metadataName != ASSEMBLY_METADATA_NAME
        ) {
            continue
        }
        val owner = assembly.assemblyReferences.singleOrNull { candidate ->
            candidate.handle == type.resolutionScope
        }
        if (owner?.name != attributeAssemblyName || !member.isAssemblyMetadataConstructor()) {
            return DotNetClrAssemblyMetadataResolution.Invalid(
                failure = DotNetClrAssemblyMetadataFailure.INVALID_CONSTRUCTOR,
                attributes = listOf(attribute.handle),
            )
        }
        val values = attribute.rawValue?.toByteArray()?.let(::decodeAssemblyMetadataBlob)
            ?: return DotNetClrAssemblyMetadataResolution.Invalid(
                failure = DotNetClrAssemblyMetadataFailure.VALUE_DECODING_FAILED,
                attributes = listOf(attribute.handle),
            )
        entries += DotNetClrAssemblyMetadataEntry(attribute.handle, values.first, values.second)
    }
    return DotNetClrAssemblyMetadataResolution.Decoded(entries)
}

private fun DotNetClrMemberReference.isAssemblyMetadataConstructor(): Boolean {
    val method = (signature as? DotNetClrMemberReferenceSignature.Method)?.signature ?: return false
    return name == ".ctor" &&
            method.callingConvention == DotNetClrSignatureCallingConvention.DEFAULT &&
            method.hasThis &&
            !method.hasExplicitThis &&
            method.genericParameterCount == 0 &&
            method.returnType == DotNetClrTypeSignature.Void &&
            method.parameterTypes == ASSEMBLY_METADATA_CONSTRUCTOR_PARAMETERS &&
            method.varargParameterStart == null
}

private fun decodeAssemblyMetadataBlob(bytes: ByteArray): Pair<String?, String?>? = try {
    val reader = AssemblyMetadataBlobReader(bytes)
    if (reader.readByte() != 0x01 || reader.readByte() != 0x00) return null
    val key = reader.readSerializedString()
    val value = reader.readSerializedString()
    if (reader.readByte() != 0x00 || reader.readByte() != 0x00 || !reader.isAtEnd) return null
    key to value
} catch (_: AssemblyMetadataBlobFailure) {
    null
}

private class AssemblyMetadataBlobReader(
    private val bytes: ByteArray,
) {
    private var offset = 0

    val isAtEnd: Boolean
        get() = offset == bytes.size

    fun readByte(): Int {
        if (offset >= bytes.size) throw AssemblyMetadataBlobFailure()
        return bytes[offset++].toInt() and 0xff
    }

    fun readSerializedString(): String? {
        val first = readByte()
        if (first == 0xff) return null
        val length = when {
            first and 0x80 == 0 -> first
            first and 0xc0 == 0x80 ->
                ((first and 0x3f) shl 8) or readByte()
            first and 0xe0 == 0xc0 ->
                ((first and 0x1f) shl 24) or
                        (readByte() shl 16) or
                        (readByte() shl 8) or
                        readByte()
            else -> throw AssemblyMetadataBlobFailure()
        }
        if (length < 0 || length > bytes.size - offset) throw AssemblyMetadataBlobFailure()
        val value = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, length))
                .toString()
        } catch (_: CharacterCodingException) {
            throw AssemblyMetadataBlobFailure()
        }
        offset += length
        return value
    }
}

private class AssemblyMetadataBlobFailure : Exception()

private const val ASSEMBLY_METADATA_NAMESPACE = "System.Reflection"
private const val ASSEMBLY_METADATA_NAME = "AssemblyMetadataAttribute"
private val ASSEMBLY_DEFINITION_HANDLE = DotNetClrMetadataHandle(table = 32, row = 1)
private val ASSEMBLY_METADATA_CONSTRUCTOR_PARAMETERS = listOf(
    DotNetClrTypeSignature.Primitive(DotNetClrPrimitiveType.STRING),
    DotNetClrTypeSignature.Primitive(DotNetClrPrimitiveType.STRING),
)
