/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.load.dotnet

enum class DotNetClrAllowNullMetadataFailure {
    DUPLICATE_ATTRIBUTE,
    VALUE_DECODING_FAILED,
    INVALID_VALUE_SHAPE,
}

sealed interface DotNetClrAllowNullMetadataResolution {
    data object Absent : DotNetClrAllowNullMetadataResolution

    data class Decoded(
        val attribute: DotNetClrMetadataHandle,
    ) : DotNetClrAllowNullMetadataResolution

    data class Invalid(
        val failure: DotNetClrAllowNullMetadataFailure,
        val attributes: List<DotNetClrMetadataHandle>,
        val valueDecoding: DotNetClrCustomAttributeValueDecoding? = null,
    ) : DotNetClrAllowNullMetadataResolution
}

enum class DotNetClrDisallowNullMetadataFailure {
    DUPLICATE_ATTRIBUTE,
    VALUE_DECODING_FAILED,
    INVALID_VALUE_SHAPE,
}

sealed interface DotNetClrDisallowNullMetadataResolution {
    data object Absent : DotNetClrDisallowNullMetadataResolution

    data class Decoded(
        val attribute: DotNetClrMetadataHandle,
    ) : DotNetClrDisallowNullMetadataResolution

    data class Invalid(
        val failure: DotNetClrDisallowNullMetadataFailure,
        val attributes: List<DotNetClrMetadataHandle>,
        val valueDecoding: DotNetClrCustomAttributeValueDecoding? = null,
    ) : DotNetClrDisallowNullMetadataResolution
}

enum class DotNetClrDoesNotReturnMetadataFailure {
    DUPLICATE_ATTRIBUTE,
    VALUE_DECODING_FAILED,
    INVALID_VALUE_SHAPE,
}

sealed interface DotNetClrDoesNotReturnMetadataResolution {
    data object Absent : DotNetClrDoesNotReturnMetadataResolution

    data class Decoded(
        val attribute: DotNetClrMetadataHandle,
    ) : DotNetClrDoesNotReturnMetadataResolution

    data class Invalid(
        val failure: DotNetClrDoesNotReturnMetadataFailure,
        val attributes: List<DotNetClrMetadataHandle>,
        val valueDecoding: DotNetClrCustomAttributeValueDecoding? = null,
    ) : DotNetClrDoesNotReturnMetadataResolution
}

enum class DotNetClrDoesNotReturnIfMetadataFailure {
    DUPLICATE_ATTRIBUTE,
    VALUE_DECODING_FAILED,
    INVALID_VALUE_SHAPE,
}

sealed interface DotNetClrDoesNotReturnIfMetadataResolution {
    data object Absent : DotNetClrDoesNotReturnIfMetadataResolution

    data class Decoded(
        val attribute: DotNetClrMetadataHandle,
        val parameterValue: Boolean,
    ) : DotNetClrDoesNotReturnIfMetadataResolution

    data class Invalid(
        val failure: DotNetClrDoesNotReturnIfMetadataFailure,
        val attributes: List<DotNetClrMetadataHandle>,
        val valueDecoding: DotNetClrCustomAttributeValueDecoding? = null,
    ) : DotNetClrDoesNotReturnIfMetadataResolution
}

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

enum class DotNetClrMaybeNullMetadataFailure {
    DUPLICATE_ATTRIBUTE,
    VALUE_DECODING_FAILED,
    INVALID_VALUE_SHAPE,
}

sealed interface DotNetClrMaybeNullMetadataResolution {
    data object Absent : DotNetClrMaybeNullMetadataResolution

    data class Decoded(
        val attribute: DotNetClrMetadataHandle,
    ) : DotNetClrMaybeNullMetadataResolution

    data class Invalid(
        val failure: DotNetClrMaybeNullMetadataFailure,
        val attributes: List<DotNetClrMetadataHandle>,
        val valueDecoding: DotNetClrCustomAttributeValueDecoding? = null,
    ) : DotNetClrMaybeNullMetadataResolution
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
 * Decodes Roslyn's unconditional non-return contract without changing a physical signature.
 */
class DotNetClrDoesNotReturnMetadataDecoder(
    private val customAttributeDecoder: DotNetClrCustomAttributeDecoder,
) {
    fun decode(
        assembly: DotNetClrAssemblyMetadata,
        method: DotNetClrMetadataHandle,
    ): DotNetClrDoesNotReturnMetadataResolution {
        val candidates = assembly.customAttributes
            .asSequence()
            .filter { attribute -> attribute.parent == method }
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
        if (candidates.isEmpty()) {
            return DotNetClrDoesNotReturnMetadataResolution.Absent
        }
        if (candidates.size != 1) {
            return DotNetClrDoesNotReturnMetadataResolution.Invalid(
                failure = DotNetClrDoesNotReturnMetadataFailure.DUPLICATE_ATTRIBUTE,
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
                return DotNetClrDoesNotReturnMetadataResolution.Invalid(
                    failure =
                        DotNetClrDoesNotReturnMetadataFailure.VALUE_DECODING_FAILED,
                    attributes = listOf(candidate.attribute.handle),
                    valueDecoding = valueDecoding,
                )
            is DotNetClrCustomAttributeValueDecoding.Unsupported ->
                return DotNetClrDoesNotReturnMetadataResolution.Invalid(
                    failure =
                        DotNetClrDoesNotReturnMetadataFailure.VALUE_DECODING_FAILED,
                    attributes = listOf(candidate.attribute.handle),
                    valueDecoding = valueDecoding,
                )
        }
        if (
            decoded.fixedArguments.isNotEmpty() ||
            decoded.namedArguments.isNotEmpty()
        ) {
            return DotNetClrDoesNotReturnMetadataResolution.Invalid(
                failure = DotNetClrDoesNotReturnMetadataFailure.INVALID_VALUE_SHAPE,
                attributes = listOf(candidate.attribute.handle),
            )
        }
        return DotNetClrDoesNotReturnMetadataResolution.Decoded(
            candidate.attribute.handle,
        )
    }

    private data class RecognizedAttribute(
        val attribute: DotNetClrCustomAttribute,
        val constructor: DotNetClrResolvedCustomAttributeConstructor,
    )

    private companion object {
        const val ATTRIBUTE_NAMESPACE = "System.Diagnostics.CodeAnalysis"
        const val ATTRIBUTE_NAME = "DoesNotReturnAttribute"
    }
}

/**
 * Decodes Roslyn's conditional non-return contract without projecting a Kotlin effect.
 */
class DotNetClrDoesNotReturnIfMetadataDecoder(
    private val customAttributeDecoder: DotNetClrCustomAttributeDecoder,
) {
    fun decode(
        assembly: DotNetClrAssemblyMetadata,
        parameter: DotNetClrMetadataHandle,
    ): DotNetClrDoesNotReturnIfMetadataResolution {
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
        if (candidates.isEmpty()) {
            return DotNetClrDoesNotReturnIfMetadataResolution.Absent
        }
        if (candidates.size != 1) {
            return DotNetClrDoesNotReturnIfMetadataResolution.Invalid(
                failure = DotNetClrDoesNotReturnIfMetadataFailure.DUPLICATE_ATTRIBUTE,
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
                return DotNetClrDoesNotReturnIfMetadataResolution.Invalid(
                    failure =
                        DotNetClrDoesNotReturnIfMetadataFailure.VALUE_DECODING_FAILED,
                    attributes = listOf(candidate.attribute.handle),
                    valueDecoding = valueDecoding,
                )
            is DotNetClrCustomAttributeValueDecoding.Unsupported ->
                return DotNetClrDoesNotReturnIfMetadataResolution.Invalid(
                    failure =
                        DotNetClrDoesNotReturnIfMetadataFailure.VALUE_DECODING_FAILED,
                    attributes = listOf(candidate.attribute.handle),
                    valueDecoding = valueDecoding,
                )
        }
        val argument = decoded.fixedArguments.singleOrNull()
        if (
            argument !is DotNetClrCustomAttributeValue.BooleanValue ||
            decoded.namedArguments.isNotEmpty()
        ) {
            return DotNetClrDoesNotReturnIfMetadataResolution.Invalid(
                failure = DotNetClrDoesNotReturnIfMetadataFailure.INVALID_VALUE_SHAPE,
                attributes = listOf(candidate.attribute.handle),
            )
        }
        return DotNetClrDoesNotReturnIfMetadataResolution.Decoded(
            candidate.attribute.handle,
            argument.value,
        )
    }

    private data class RecognizedAttribute(
        val attribute: DotNetClrCustomAttribute,
        val constructor: DotNetClrResolvedCustomAttributeConstructor,
    )

    private companion object {
        const val ATTRIBUTE_NAMESPACE = "System.Diagnostics.CodeAnalysis"
        const val ATTRIBUTE_NAME = "DoesNotReturnIfAttribute"
        val BOOLEAN_TYPE =
            DotNetClrResolvedTypeSignature.Primitive(DotNetClrPrimitiveType.BOOLEAN)
    }
}

/**
 * Decodes Roslyn's unconditional output postcondition without projecting a Kotlin contract or
 * result qualifier.
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
        output: DotNetClrMetadataHandle,
    ): DotNetClrNotNullMetadataResolution {
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
 * Decodes Roslyn's unconditional maybe-null output postcondition without projecting a result
 * qualifier.
 *
 * This deliberately mirrors [DotNetClrNotNullMetadataDecoder]. The declaration layer applies
 * their CLR call-result precedence and chooses a safe fallback for invalid weakening evidence.
 */
class DotNetClrMaybeNullMetadataDecoder(
    private val customAttributeDecoder: DotNetClrCustomAttributeDecoder,
) {
    fun decode(
        assembly: DotNetClrAssemblyMetadata,
        output: DotNetClrMetadataHandle,
    ): DotNetClrMaybeNullMetadataResolution {
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
                    constructor.signature.parameterTypes.isNotEmpty()
                ) {
                    return@mapNotNull null
                }
                RecognizedAttribute(attribute, constructor)
            }
            .toList()
        if (candidates.isEmpty()) return DotNetClrMaybeNullMetadataResolution.Absent
        if (candidates.size != 1) {
            return DotNetClrMaybeNullMetadataResolution.Invalid(
                failure = DotNetClrMaybeNullMetadataFailure.DUPLICATE_ATTRIBUTE,
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
                return DotNetClrMaybeNullMetadataResolution.Invalid(
                    failure = DotNetClrMaybeNullMetadataFailure.VALUE_DECODING_FAILED,
                    attributes = listOf(candidate.attribute.handle),
                    valueDecoding = valueDecoding,
                )
            is DotNetClrCustomAttributeValueDecoding.Unsupported ->
                return DotNetClrMaybeNullMetadataResolution.Invalid(
                    failure = DotNetClrMaybeNullMetadataFailure.VALUE_DECODING_FAILED,
                    attributes = listOf(candidate.attribute.handle),
                    valueDecoding = valueDecoding,
                )
        }
        if (
            decoded.fixedArguments.isNotEmpty() ||
            decoded.namedArguments.isNotEmpty()
        ) {
            return DotNetClrMaybeNullMetadataResolution.Invalid(
                failure = DotNetClrMaybeNullMetadataFailure.INVALID_VALUE_SHAPE,
                attributes = listOf(candidate.attribute.handle),
            )
        }
        return DotNetClrMaybeNullMetadataResolution.Decoded(candidate.attribute.handle)
    }

    private data class RecognizedAttribute(
        val attribute: DotNetClrCustomAttribute,
        val constructor: DotNetClrResolvedCustomAttributeConstructor,
    )

    private companion object {
        const val ATTRIBUTE_NAMESPACE = "System.Diagnostics.CodeAnalysis"
        const val ATTRIBUTE_NAME = "MaybeNullAttribute"
    }
}

/**
 * Decodes Roslyn's parameter input-weakening precondition without projecting a result qualifier.
 */
class DotNetClrAllowNullMetadataDecoder(
    private val customAttributeDecoder: DotNetClrCustomAttributeDecoder,
) {
    fun decode(
        assembly: DotNetClrAssemblyMetadata,
        input: DotNetClrMetadataHandle,
    ): DotNetClrAllowNullMetadataResolution {
        val candidates = assembly.customAttributes
            .asSequence()
            .filter { attribute -> attribute.parent == input }
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
        if (candidates.isEmpty()) return DotNetClrAllowNullMetadataResolution.Absent
        if (candidates.size != 1) {
            return DotNetClrAllowNullMetadataResolution.Invalid(
                failure = DotNetClrAllowNullMetadataFailure.DUPLICATE_ATTRIBUTE,
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
                return DotNetClrAllowNullMetadataResolution.Invalid(
                    failure = DotNetClrAllowNullMetadataFailure.VALUE_DECODING_FAILED,
                    attributes = listOf(candidate.attribute.handle),
                    valueDecoding = valueDecoding,
                )
            is DotNetClrCustomAttributeValueDecoding.Unsupported ->
                return DotNetClrAllowNullMetadataResolution.Invalid(
                    failure = DotNetClrAllowNullMetadataFailure.VALUE_DECODING_FAILED,
                    attributes = listOf(candidate.attribute.handle),
                    valueDecoding = valueDecoding,
                )
        }
        if (
            decoded.fixedArguments.isNotEmpty() ||
            decoded.namedArguments.isNotEmpty()
        ) {
            return DotNetClrAllowNullMetadataResolution.Invalid(
                failure = DotNetClrAllowNullMetadataFailure.INVALID_VALUE_SHAPE,
                attributes = listOf(candidate.attribute.handle),
            )
        }
        return DotNetClrAllowNullMetadataResolution.Decoded(candidate.attribute.handle)
    }

    private data class RecognizedAttribute(
        val attribute: DotNetClrCustomAttribute,
        val constructor: DotNetClrResolvedCustomAttributeConstructor,
    )

    private companion object {
        const val ATTRIBUTE_NAMESPACE = "System.Diagnostics.CodeAnalysis"
        const val ATTRIBUTE_NAME = "AllowNullAttribute"
    }
}

/**
 * Decodes Roslyn's parameter input-strengthening precondition without projecting a result
 * qualifier.
 */
class DotNetClrDisallowNullMetadataDecoder(
    private val customAttributeDecoder: DotNetClrCustomAttributeDecoder,
) {
    fun decode(
        assembly: DotNetClrAssemblyMetadata,
        input: DotNetClrMetadataHandle,
    ): DotNetClrDisallowNullMetadataResolution {
        val candidates = assembly.customAttributes
            .asSequence()
            .filter { attribute -> attribute.parent == input }
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
        if (candidates.isEmpty()) return DotNetClrDisallowNullMetadataResolution.Absent
        if (candidates.size != 1) {
            return DotNetClrDisallowNullMetadataResolution.Invalid(
                failure = DotNetClrDisallowNullMetadataFailure.DUPLICATE_ATTRIBUTE,
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
                return DotNetClrDisallowNullMetadataResolution.Invalid(
                    failure = DotNetClrDisallowNullMetadataFailure.VALUE_DECODING_FAILED,
                    attributes = listOf(candidate.attribute.handle),
                    valueDecoding = valueDecoding,
                )
            is DotNetClrCustomAttributeValueDecoding.Unsupported ->
                return DotNetClrDisallowNullMetadataResolution.Invalid(
                    failure = DotNetClrDisallowNullMetadataFailure.VALUE_DECODING_FAILED,
                    attributes = listOf(candidate.attribute.handle),
                    valueDecoding = valueDecoding,
                )
        }
        if (
            decoded.fixedArguments.isNotEmpty() ||
            decoded.namedArguments.isNotEmpty()
        ) {
            return DotNetClrDisallowNullMetadataResolution.Invalid(
                failure = DotNetClrDisallowNullMetadataFailure.INVALID_VALUE_SHAPE,
                attributes = listOf(candidate.attribute.handle),
            )
        }
        return DotNetClrDisallowNullMetadataResolution.Decoded(candidate.attribute.handle)
    }

    private data class RecognizedAttribute(
        val attribute: DotNetClrCustomAttribute,
        val constructor: DotNetClrResolvedCustomAttributeConstructor,
    )

    private companion object {
        const val ATTRIBUTE_NAMESPACE = "System.Diagnostics.CodeAnalysis"
        const val ATTRIBUTE_NAME = "DisallowNullAttribute"
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
