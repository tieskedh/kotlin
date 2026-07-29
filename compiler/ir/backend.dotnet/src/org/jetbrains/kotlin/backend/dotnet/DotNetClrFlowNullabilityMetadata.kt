/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

enum class DotNetClrNotNullWhenMetadataFailure {
    DUPLICATE_ATTRIBUTE,
    VALUE_DECODING_FAILED,
    INVALID_VALUE_SHAPE,
}

sealed interface DotNetClrNotNullWhenMetadataResolution {
    data object Absent : DotNetClrNotNullWhenMetadataResolution

    data class Decoded(
        val attribute: DotNetClrMetadataHandle,
        val returnValue: Boolean,
    ) : DotNetClrNotNullWhenMetadataResolution

    data class Invalid(
        val failure: DotNetClrNotNullWhenMetadataFailure,
        val attributes: List<DotNetClrMetadataHandle>,
        val valueDecoding: DotNetClrCustomAttributeValueDecoding? = null,
    ) : DotNetClrNotNullWhenMetadataResolution
}

/**
 * Decodes the standard Roslyn conditional postcondition without projecting a Kotlin contract.
 *
 * Recognition follows the same selected-graph rule as nullable declaration metadata: exact
 * top-level namespace/name and Boolean constructor after ordinary attribute ancestry and
 * signature resolution. The framework assembly is not fixed because reference profiles and
 * down-level producers may supply the standard attribute type from different assemblies.
 */
class DotNetClrNotNullWhenMetadataDecoder(
    private val customAttributeDecoder: DotNetClrCustomAttributeDecoder,
) {
    fun decode(
        assembly: DotNetClrAssemblyMetadata,
        parameter: DotNetClrMetadataHandle,
    ): DotNetClrNotNullWhenMetadataResolution {
        val candidates = assembly.customAttributes
            .asSequence()
            .filter { attribute -> attribute.parent == parameter }
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
                val definition = constructor.attributeType.type.definition
                if (
                    definition.declaringType != null ||
                    definition.namespaceName != ATTRIBUTE_NAMESPACE ||
                    definition.metadataName != ATTRIBUTE_NAME ||
                    constructor.attributeType.arguments.isNotEmpty() ||
                    constructor.signature.parameterTypes.singleOrNull() != BOOLEAN_TYPE
                ) {
                    return@mapNotNull null
                }
                RecognizedAttribute(attribute, constructor)
            }
            .toList()
        if (candidates.isEmpty()) return DotNetClrNotNullWhenMetadataResolution.Absent
        if (candidates.size != 1) {
            return DotNetClrNotNullWhenMetadataResolution.Invalid(
                failure = DotNetClrNotNullWhenMetadataFailure.DUPLICATE_ATTRIBUTE,
                attributes = candidates.map { candidate -> candidate.attribute.handle },
            )
        }

        val candidate = candidates.single()
        val decoded = when (
            val valueDecoding = customAttributeDecoder.decodeValue(
                assembly,
                candidate.attribute,
                candidate.constructor,
            )
        ) {
            is DotNetClrCustomAttributeValueDecoding.Decoded -> valueDecoding.attribute
            is DotNetClrCustomAttributeValueDecoding.Invalid ->
                return DotNetClrNotNullWhenMetadataResolution.Invalid(
                    failure = DotNetClrNotNullWhenMetadataFailure.VALUE_DECODING_FAILED,
                    attributes = listOf(candidate.attribute.handle),
                    valueDecoding = valueDecoding,
                )
            is DotNetClrCustomAttributeValueDecoding.Unsupported ->
                return DotNetClrNotNullWhenMetadataResolution.Invalid(
                    failure = DotNetClrNotNullWhenMetadataFailure.VALUE_DECODING_FAILED,
                    attributes = listOf(candidate.attribute.handle),
                    valueDecoding = valueDecoding,
                )
        }
        val argument = decoded.fixedArguments.singleOrNull()
        if (
            argument !is DotNetClrCustomAttributeValue.BooleanValue ||
            decoded.namedArguments.isNotEmpty()
        ) {
            return DotNetClrNotNullWhenMetadataResolution.Invalid(
                failure = DotNetClrNotNullWhenMetadataFailure.INVALID_VALUE_SHAPE,
                attributes = listOf(candidate.attribute.handle),
            )
        }
        return DotNetClrNotNullWhenMetadataResolution.Decoded(
            attribute = candidate.attribute.handle,
            returnValue = argument.value,
        )
    }

    private data class RecognizedAttribute(
        val attribute: DotNetClrCustomAttribute,
        val constructor: DotNetClrResolvedCustomAttributeConstructor,
    )

    private companion object {
        const val ATTRIBUTE_NAMESPACE = "System.Diagnostics.CodeAnalysis"
        const val ATTRIBUTE_NAME = "NotNullWhenAttribute"
        val BOOLEAN_TYPE =
            DotNetClrResolvedTypeSignature.Primitive(DotNetClrPrimitiveType.BOOLEAN)
    }
}
