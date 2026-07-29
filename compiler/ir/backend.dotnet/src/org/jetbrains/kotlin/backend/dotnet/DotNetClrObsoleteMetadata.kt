/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

enum class DotNetClrObsoleteMetadataFailure {
    DUPLICATE_ATTRIBUTE,
    INVALID_CONSTRUCTOR,
    VALUE_DECODING_FAILED,
    INVALID_VALUE_SHAPE,
}

sealed interface DotNetClrObsoleteMetadataResolution {
    data object Absent : DotNetClrObsoleteMetadataResolution

    data class Decoded(
        val attribute: DotNetClrMetadataHandle,
        val message: String?,
        val isError: Boolean,
        val diagnosticId: String?,
        val urlFormat: String?,
    ) : DotNetClrObsoleteMetadataResolution

    data class Invalid(
        val failure: DotNetClrObsoleteMetadataFailure,
        val attributes: List<DotNetClrMetadataHandle>,
        val valueDecoding: DotNetClrCustomAttributeValueDecoding? = null,
    ) : DotNetClrObsoleteMetadataResolution
}

/**
 * Decodes the selected core library's method-level `System.ObsoleteAttribute` contract.
 *
 * Unlike the embedded CodeAnalysis attributes, ObsoleteAttribute has one platform-owned identity.
 * [obsoleteType] is resolved from the same selected core assembly as System.Attribute, so a
 * source-defined namespace/name look-alike cannot manufacture Kotlin deprecation diagnostics.
 */
class DotNetClrObsoleteMetadataDecoder(
    private val customAttributeDecoder: DotNetClrCustomAttributeDecoder,
    private val obsoleteType: DotNetClrResolvedTypeDefinition,
) {
    fun decode(
        assembly: DotNetClrAssemblyMetadata,
        method: DotNetClrMetadataHandle,
    ): DotNetClrObsoleteMetadataResolution {
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
                if (
                    !constructor.attributeType.type.hasSameIdentityAs(obsoleteType) ||
                    constructor.attributeType.arguments.isNotEmpty()
                ) {
                    return@mapNotNull null
                }
                RecognizedAttribute(attribute, constructor)
            }
            .toList()
        if (candidates.isEmpty()) {
            return DotNetClrObsoleteMetadataResolution.Absent
        }
        if (candidates.size != 1) {
            return DotNetClrObsoleteMetadataResolution.Invalid(
                failure = DotNetClrObsoleteMetadataFailure.DUPLICATE_ATTRIBUTE,
                attributes = candidates.map { candidate -> candidate.attribute.handle },
            )
        }

        val candidate = candidates.single()
        val constructorKind = when (candidate.constructor.signature.parameterTypes) {
            emptyList<DotNetClrResolvedTypeSignature>() -> ConstructorKind.EMPTY
            listOf(STRING_TYPE) -> ConstructorKind.MESSAGE
            listOf(STRING_TYPE, BOOLEAN_TYPE) -> ConstructorKind.MESSAGE_AND_ERROR
            else -> return DotNetClrObsoleteMetadataResolution.Invalid(
                failure = DotNetClrObsoleteMetadataFailure.INVALID_CONSTRUCTOR,
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
                return DotNetClrObsoleteMetadataResolution.Invalid(
                    failure = DotNetClrObsoleteMetadataFailure.VALUE_DECODING_FAILED,
                    attributes = listOf(candidate.attribute.handle),
                    valueDecoding = valueDecoding,
                )
            is DotNetClrCustomAttributeValueDecoding.Unsupported ->
                return DotNetClrObsoleteMetadataResolution.Invalid(
                    failure = DotNetClrObsoleteMetadataFailure.VALUE_DECODING_FAILED,
                    attributes = listOf(candidate.attribute.handle),
                    valueDecoding = valueDecoding,
                )
        }
        val fixedValues = when (constructorKind) {
            ConstructorKind.EMPTY ->
                if (decoded.fixedArguments.isEmpty()) {
                    FixedValues(message = null, isError = false)
                } else {
                    null
                }
            ConstructorKind.MESSAGE -> {
                val message =
                    (decoded.fixedArguments.singleOrNull() as? DotNetClrCustomAttributeValue.StringValue)
                        ?.value
                if (
                    decoded.fixedArguments.singleOrNull()
                        is DotNetClrCustomAttributeValue.StringValue
                ) {
                    FixedValues(message, isError = false)
                } else {
                    null
                }
            }
            ConstructorKind.MESSAGE_AND_ERROR -> {
                val message =
                    (decoded.fixedArguments.getOrNull(0) as? DotNetClrCustomAttributeValue.StringValue)
                        ?.value
                val isError =
                    (decoded.fixedArguments.getOrNull(1) as? DotNetClrCustomAttributeValue.BooleanValue)
                        ?.value
                if (
                    decoded.fixedArguments.size == 2 &&
                    decoded.fixedArguments[0] is DotNetClrCustomAttributeValue.StringValue &&
                    isError != null
                ) {
                    FixedValues(message, isError)
                } else {
                    null
                }
            }
        } ?: return DotNetClrObsoleteMetadataResolution.Invalid(
            failure = DotNetClrObsoleteMetadataFailure.INVALID_VALUE_SHAPE,
            attributes = listOf(candidate.attribute.handle),
        )

        var diagnosticId: String? = null
        var urlFormat: String? = null
        val namedProperties = hashSetOf<String>()
        for (named in decoded.namedArguments) {
            val value = (named.value as? DotNetClrCustomAttributeValue.StringValue)?.value
            if (
                named.kind != DotNetClrCustomAttributeNamedArgumentKind.PROPERTY ||
                named.type != STRING_VALUE_TYPE ||
                !namedProperties.add(named.name)
            ) {
                return DotNetClrObsoleteMetadataResolution.Invalid(
                    failure = DotNetClrObsoleteMetadataFailure.INVALID_VALUE_SHAPE,
                    attributes = listOf(candidate.attribute.handle),
                )
            }
            when (named.name) {
                DIAGNOSTIC_ID_PROPERTY -> diagnosticId = value
                URL_FORMAT_PROPERTY -> urlFormat = value
                else -> return DotNetClrObsoleteMetadataResolution.Invalid(
                    failure = DotNetClrObsoleteMetadataFailure.INVALID_VALUE_SHAPE,
                    attributes = listOf(candidate.attribute.handle),
                )
            }
        }

        return DotNetClrObsoleteMetadataResolution.Decoded(
            attribute = candidate.attribute.handle,
            message = fixedValues.message,
            isError = fixedValues.isError,
            diagnosticId = diagnosticId,
            urlFormat = urlFormat,
        )
    }

    private data class RecognizedAttribute(
        val attribute: DotNetClrCustomAttribute,
        val constructor: DotNetClrResolvedCustomAttributeConstructor,
    )

    private data class FixedValues(
        val message: String?,
        val isError: Boolean,
    )

    private enum class ConstructorKind {
        EMPTY,
        MESSAGE,
        MESSAGE_AND_ERROR,
    }

    private companion object {
        const val DIAGNOSTIC_ID_PROPERTY = "DiagnosticId"
        const val URL_FORMAT_PROPERTY = "UrlFormat"

        val STRING_TYPE =
            DotNetClrResolvedTypeSignature.Primitive(DotNetClrPrimitiveType.STRING)
        val BOOLEAN_TYPE =
            DotNetClrResolvedTypeSignature.Primitive(DotNetClrPrimitiveType.BOOLEAN)
        val STRING_VALUE_TYPE =
            DotNetClrCustomAttributeValueType.Primitive(DotNetClrPrimitiveType.STRING)
    }
}
