/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

enum class DotNetClrParamArrayMetadataFailure {
    DUPLICATE_ATTRIBUTE,
    INVALID_CONSTRUCTOR,
    VALUE_DECODING_FAILED,
    INVALID_VALUE_SHAPE,
}

sealed interface DotNetClrParamArrayMetadataResolution {
    data object Absent : DotNetClrParamArrayMetadataResolution

    data class Decoded(
        val attribute: DotNetClrMetadataHandle,
    ) : DotNetClrParamArrayMetadataResolution

    data class Invalid(
        val failure: DotNetClrParamArrayMetadataFailure,
        val attributes: List<DotNetClrMetadataHandle>,
        val valueDecoding: DotNetClrCustomAttributeValueDecoding? = null,
    ) : DotNetClrParamArrayMetadataResolution
}

/**
 * Decodes the selected core library's parameter-level `System.ParamArrayAttribute` marker.
 *
 * The selected core type, parameterless constructor, and empty value are all part of the
 * evidence. Placement and physical-array compatibility remain importer policy.
 */
class DotNetClrParamArrayMetadataDecoder(
    private val customAttributeDecoder: DotNetClrCustomAttributeDecoder,
    private val paramArrayType: DotNetClrResolvedTypeDefinition,
) {
    fun decode(
        assembly: DotNetClrAssemblyMetadata,
        parent: DotNetClrMetadataHandle,
    ): DotNetClrParamArrayMetadataResolution {
        val candidates = assembly.customAttributes
            .asSequence()
            .filter { attribute -> attribute.parent == parent }
            .mapNotNull { attribute ->
                val constructor = when (
                    val resolution =
                        customAttributeDecoder.resolveConstructor(assembly, attribute)
                ) {
                    is DotNetClrCustomAttributeConstructorResolution.Resolved ->
                        resolution.constructor
                    is DotNetClrCustomAttributeConstructorResolution.Invalid ->
                        return@mapNotNull null
                }
                if (
                    !constructor.attributeType.type.hasSameIdentityAs(paramArrayType) ||
                    constructor.attributeType.arguments.isNotEmpty()
                ) {
                    return@mapNotNull null
                }
                RecognizedAttribute(attribute, constructor)
            }
            .toList()
        if (candidates.isEmpty()) {
            return DotNetClrParamArrayMetadataResolution.Absent
        }
        if (candidates.size != 1) {
            return DotNetClrParamArrayMetadataResolution.Invalid(
                failure = DotNetClrParamArrayMetadataFailure.DUPLICATE_ATTRIBUTE,
                attributes = candidates.map { candidate -> candidate.attribute.handle },
            )
        }

        val candidate = candidates.single()
        if (candidate.constructor.signature.parameterTypes.isNotEmpty()) {
            return DotNetClrParamArrayMetadataResolution.Invalid(
                failure = DotNetClrParamArrayMetadataFailure.INVALID_CONSTRUCTOR,
                attributes = listOf(candidate.attribute.handle),
            )
        }
        val decoded = when (
            val valueDecoding = customAttributeDecoder.decodeValue(
                assembly,
                candidate.attribute,
                candidate.constructor,
            )
        ) {
            is DotNetClrCustomAttributeValueDecoding.Decoded -> valueDecoding.attribute
            is DotNetClrCustomAttributeValueDecoding.Invalid ->
                return DotNetClrParamArrayMetadataResolution.Invalid(
                    failure = DotNetClrParamArrayMetadataFailure.VALUE_DECODING_FAILED,
                    attributes = listOf(candidate.attribute.handle),
                    valueDecoding = valueDecoding,
                )
            is DotNetClrCustomAttributeValueDecoding.Unsupported ->
                return DotNetClrParamArrayMetadataResolution.Invalid(
                    failure = DotNetClrParamArrayMetadataFailure.VALUE_DECODING_FAILED,
                    attributes = listOf(candidate.attribute.handle),
                    valueDecoding = valueDecoding,
                )
        }
        if (decoded.fixedArguments.isNotEmpty() || decoded.namedArguments.isNotEmpty()) {
            return DotNetClrParamArrayMetadataResolution.Invalid(
                failure = DotNetClrParamArrayMetadataFailure.INVALID_VALUE_SHAPE,
                attributes = listOf(candidate.attribute.handle),
            )
        }
        return DotNetClrParamArrayMetadataResolution.Decoded(candidate.attribute.handle)
    }

    private data class RecognizedAttribute(
        val attribute: DotNetClrCustomAttribute,
        val constructor: DotNetClrResolvedCustomAttributeConstructor,
    )
}
