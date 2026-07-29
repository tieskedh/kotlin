/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

enum class DotNetClrNotNullMetadataFailure {
    DUPLICATE_ATTRIBUTE,
    VALUE_DECODING_FAILED,
    INVALID_VALUE_SHAPE,
}

sealed interface DotNetClrNotNullMetadataResolution {
    data object Absent : DotNetClrNotNullMetadataResolution

    data class Decoded(
        val attribute: DotNetClrMetadataHandle,
    ) : DotNetClrNotNullMetadataResolution

    data class Invalid(
        val failure: DotNetClrNotNullMetadataFailure,
        val attributes: List<DotNetClrMetadataHandle>,
        val valueDecoding: DotNetClrCustomAttributeValueDecoding? = null,
    ) : DotNetClrNotNullMetadataResolution
}

enum class DotNetClrNotNullIfNotNullMetadataFailure {
    VALUE_DECODING_FAILED,
    INVALID_VALUE_SHAPE,
}

sealed interface DotNetClrNotNullIfNotNullMetadataResolution {
    data object Absent : DotNetClrNotNullIfNotNullMetadataResolution

    data class DecodedAttribute(
        val attribute: DotNetClrMetadataHandle,
        val parameterName: String,
    )

    data class Decoded(
        val attributes: List<DecodedAttribute>,
    ) : DotNetClrNotNullIfNotNullMetadataResolution

    data class Invalid(
        val failure: DotNetClrNotNullIfNotNullMetadataFailure,
        val attributes: List<DotNetClrMetadataHandle>,
        val valueDecoding: DotNetClrCustomAttributeValueDecoding? = null,
    ) : DotNetClrNotNullIfNotNullMetadataResolution
}

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
 * Decodes Roslyn's unconditional parameter postcondition without projecting a Kotlin contract.
 *
 * The resolved attribute must be the exact top-level standard name, derive from
 * [System.Attribute], and use its parameterless instance constructor. See the conditional decoder
 * below for the shared selected-graph recognition policy.
 */
class DotNetClrNotNullMetadataDecoder(
    private val customAttributeDecoder: DotNetClrCustomAttributeDecoder,
) {
    fun decode(
        assembly: DotNetClrAssemblyMetadata,
        parameter: DotNetClrMetadataHandle,
    ): DotNetClrNotNullMetadataResolution {
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
                    constructor.signature.parameterTypes.isNotEmpty()
                ) {
                    return@mapNotNull null
                }
                RecognizedAttribute(attribute, constructor)
            }
            .toList()
        if (candidates.isEmpty()) return DotNetClrNotNullMetadataResolution.Absent
        if (candidates.size != 1) {
            return DotNetClrNotNullMetadataResolution.Invalid(
                failure = DotNetClrNotNullMetadataFailure.DUPLICATE_ATTRIBUTE,
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
                return DotNetClrNotNullMetadataResolution.Invalid(
                    failure = DotNetClrNotNullMetadataFailure.VALUE_DECODING_FAILED,
                    attributes = listOf(candidate.attribute.handle),
                    valueDecoding = valueDecoding,
                )
            is DotNetClrCustomAttributeValueDecoding.Unsupported ->
                return DotNetClrNotNullMetadataResolution.Invalid(
                    failure = DotNetClrNotNullMetadataFailure.VALUE_DECODING_FAILED,
                    attributes = listOf(candidate.attribute.handle),
                    valueDecoding = valueDecoding,
                )
        }
        if (
            decoded.fixedArguments.isNotEmpty() ||
            decoded.namedArguments.isNotEmpty()
        ) {
            return DotNetClrNotNullMetadataResolution.Invalid(
                failure = DotNetClrNotNullMetadataFailure.INVALID_VALUE_SHAPE,
                attributes = listOf(candidate.attribute.handle),
            )
        }
        return DotNetClrNotNullMetadataResolution.Decoded(candidate.attribute.handle)
    }

    private data class RecognizedAttribute(
        val attribute: DotNetClrCustomAttribute,
        val constructor: DotNetClrResolvedCustomAttributeConstructor,
    )

    private companion object {
        const val ATTRIBUTE_NAMESPACE = "System.Diagnostics.CodeAnalysis"
        const val ATTRIBUTE_NAME = "NotNullAttribute"
    }
}

/**
 * Decodes Roslyn's conditional output postcondition without binding its parameter-name payload.
 *
 * The standard attribute deliberately allows multiple instances. All recognized instances are
 * decoded as one evidence set so the later declaration layer can reject an invalid name or
 * inapplicable target without partially strengthening the return value.
 */
class DotNetClrNotNullIfNotNullMetadataDecoder(
    private val customAttributeDecoder: DotNetClrCustomAttributeDecoder,
) {
    fun decode(
        assembly: DotNetClrAssemblyMetadata,
        output: DotNetClrMetadataHandle,
    ): DotNetClrNotNullIfNotNullMetadataResolution {
        val candidates = assembly.customAttributes
            .asSequence()
            .filter { attribute -> attribute.parent == output }
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
                    constructor.signature.parameterTypes.singleOrNull() != STRING_TYPE
                ) {
                    return@mapNotNull null
                }
                RecognizedAttribute(attribute, constructor)
            }
            .toList()
        if (candidates.isEmpty()) {
            return DotNetClrNotNullIfNotNullMetadataResolution.Absent
        }

        val decodedAttributes =
            mutableListOf<DotNetClrNotNullIfNotNullMetadataResolution.DecodedAttribute>()
        for (candidate in candidates) {
            val decoded = when (
                val valueDecoding = customAttributeDecoder.decodeValue(
                    assembly,
                    candidate.attribute,
                    candidate.constructor,
                )
            ) {
                is DotNetClrCustomAttributeValueDecoding.Decoded -> valueDecoding.attribute
                is DotNetClrCustomAttributeValueDecoding.Invalid ->
                    return DotNetClrNotNullIfNotNullMetadataResolution.Invalid(
                        failure =
                            DotNetClrNotNullIfNotNullMetadataFailure.VALUE_DECODING_FAILED,
                        attributes =
                            candidates.map { recognized -> recognized.attribute.handle },
                        valueDecoding = valueDecoding,
                    )
                is DotNetClrCustomAttributeValueDecoding.Unsupported ->
                    return DotNetClrNotNullIfNotNullMetadataResolution.Invalid(
                        failure =
                            DotNetClrNotNullIfNotNullMetadataFailure.VALUE_DECODING_FAILED,
                        attributes =
                            candidates.map { recognized -> recognized.attribute.handle },
                        valueDecoding = valueDecoding,
                    )
            }
            val argument = decoded.fixedArguments.singleOrNull()
            val parameterName =
                (argument as? DotNetClrCustomAttributeValue.StringValue)?.value
            if (parameterName == null || decoded.namedArguments.isNotEmpty()) {
                return DotNetClrNotNullIfNotNullMetadataResolution.Invalid(
                    failure =
                        DotNetClrNotNullIfNotNullMetadataFailure.INVALID_VALUE_SHAPE,
                    attributes =
                        candidates.map { recognized -> recognized.attribute.handle },
                )
            }
            decodedAttributes +=
                DotNetClrNotNullIfNotNullMetadataResolution.DecodedAttribute(
                    candidate.attribute.handle,
                    parameterName,
                )
        }
        return DotNetClrNotNullIfNotNullMetadataResolution.Decoded(decodedAttributes)
    }

    private data class RecognizedAttribute(
        val attribute: DotNetClrCustomAttribute,
        val constructor: DotNetClrResolvedCustomAttributeConstructor,
    )

    private companion object {
        const val ATTRIBUTE_NAMESPACE = "System.Diagnostics.CodeAnalysis"
        const val ATTRIBUTE_NAME = "NotNullIfNotNullAttribute"
        val STRING_TYPE =
            DotNetClrResolvedTypeSignature.Primitive(DotNetClrPrimitiveType.STRING)
    }
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
