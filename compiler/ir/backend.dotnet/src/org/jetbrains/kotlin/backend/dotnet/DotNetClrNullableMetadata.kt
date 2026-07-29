/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

enum class DotNetClrNullableAnnotation(
    val metadataValue: Int,
) {
    OBLIVIOUS(0),
    NOT_ANNOTATED(1),
    ANNOTATED(2),
    ;

    companion object {
        fun fromMetadataValue(value: Int): DotNetClrNullableAnnotation? =
            entries.singleOrNull { annotation -> annotation.metadataValue == value }
    }
}

sealed interface DotNetClrNullableTransform {
    /**
     * The single-byte NullableAttribute form applies one default flag throughout a type tree.
     */
    data class Uniform(
        val annotation: DotNetClrNullableAnnotation,
    ) : DotNetClrNullableTransform

    /**
     * The byte-array NullableAttribute form applies flags positionally in Roslyn preorder.
     */
    data class Sequence(
        val annotations: List<DotNetClrNullableAnnotation>,
    ) : DotNetClrNullableTransform
}

enum class DotNetClrNullableMetadataFailure {
    DUPLICATE_ATTRIBUTE,
    VALUE_DECODING_FAILED,
    INVALID_VALUE_SHAPE,
    INVALID_ANNOTATION_FLAG,
}

sealed interface DotNetClrNullableMetadataResolution<out T> {
    data object Absent : DotNetClrNullableMetadataResolution<Nothing>

    data class Decoded<T>(
        val attribute: DotNetClrMetadataHandle,
        val value: T,
    ) : DotNetClrNullableMetadataResolution<T>

    data class Invalid(
        val failure: DotNetClrNullableMetadataFailure,
        val attributes: List<DotNetClrMetadataHandle>,
        val valueDecoding: DotNetClrCustomAttributeValueDecoding? = null,
        val invalidFlag: Int? = null,
    ) : DotNetClrNullableMetadataResolution<Nothing>
}

/**
 * Decodes the three well-known Roslyn nullable-metadata attributes without projecting a Kotlin
 * type.
 *
 * Roslyn embeds these attribute classes privately in producer assemblies when the selected
 * framework does not provide them. Recognition therefore follows the CLR metadata convention:
 * exact top-level namespace/name and constructor signature after ordinary attribute ancestry and
 * signature resolution. It deliberately does not bind the attribute to one framework assembly.
 */
class DotNetClrNullableMetadataDecoder(
    private val customAttributeDecoder: DotNetClrCustomAttributeDecoder,
) {
    fun decodeTransform(
        assembly: DotNetClrAssemblyMetadata,
        parent: DotNetClrMetadataHandle,
    ): DotNetClrNullableMetadataResolution<DotNetClrNullableTransform> =
        decodeRecognizedAttribute(
            assembly = assembly,
            parent = parent,
            metadataName = NULLABLE_ATTRIBUTE_NAME,
            acceptedParameterTypes = listOf(
                DotNetClrResolvedTypeSignature.Primitive(DotNetClrPrimitiveType.UINT8),
                DotNetClrResolvedTypeSignature.SzArray(
                    DotNetClrResolvedTypeSignature.Primitive(DotNetClrPrimitiveType.UINT8)
                ),
            ),
        ) { decoded ->
            val argument = decoded.fixedArguments.singleOrNull()
                ?: return@decodeRecognizedAttribute invalidValueShape()
            when (argument) {
                is DotNetClrCustomAttributeValue.IntegralValue -> {
                    if (argument.type != DotNetClrPrimitiveType.UINT8) {
                        invalidValueShape()
                    } else {
                        decodeAnnotation(argument.bits.toInt()) { annotation ->
                            DotNetClrNullableTransform.Uniform(annotation)
                        }
                    }
                }
                is DotNetClrCustomAttributeValue.ArrayValue -> {
                    val elementType = argument.type.elementType
                    if (elementType !=
                        DotNetClrCustomAttributeValueType.Primitive(DotNetClrPrimitiveType.UINT8) ||
                        argument.elements == null
                    ) {
                        invalidValueShape()
                    } else {
                        val annotations = ArrayList<DotNetClrNullableAnnotation>(
                            argument.elements.size
                        )
                        for (element in argument.elements) {
                            if (element !is DotNetClrCustomAttributeValue.IntegralValue ||
                                element.type != DotNetClrPrimitiveType.UINT8
                            ) {
                                return@decodeRecognizedAttribute invalidValueShape()
                            }
                            val annotation =
                                DotNetClrNullableAnnotation.fromMetadataValue(element.bits.toInt())
                                    ?: return@decodeRecognizedAttribute invalidFlag(element.bits.toInt())
                            annotations += annotation
                        }
                        DecodedValue(DotNetClrNullableTransform.Sequence(annotations))
                    }
                }
                else -> invalidValueShape()
            }
        }

    fun decodeContext(
        assembly: DotNetClrAssemblyMetadata,
        parent: DotNetClrMetadataHandle,
    ): DotNetClrNullableMetadataResolution<DotNetClrNullableAnnotation> =
        decodeRecognizedAttribute(
            assembly = assembly,
            parent = parent,
            metadataName = NULLABLE_CONTEXT_ATTRIBUTE_NAME,
            acceptedParameterTypes = listOf(
                DotNetClrResolvedTypeSignature.Primitive(DotNetClrPrimitiveType.UINT8)
            ),
        ) { decoded ->
            val argument = decoded.fixedArguments.singleOrNull()
            if (argument !is DotNetClrCustomAttributeValue.IntegralValue ||
                argument.type != DotNetClrPrimitiveType.UINT8
            ) {
                invalidValueShape()
            } else {
                decodeAnnotation(argument.bits.toInt()) { annotation -> annotation }
            }
        }

    fun decodePublicOnly(
        assembly: DotNetClrAssemblyMetadata,
        module: DotNetClrMetadataHandle = DotNetClrMetadataHandle(MODULE_TABLE, 1),
    ): DotNetClrNullableMetadataResolution<Boolean> =
        decodeRecognizedAttribute(
            assembly = assembly,
            parent = module,
            metadataName = NULLABLE_PUBLIC_ONLY_ATTRIBUTE_NAME,
            acceptedParameterTypes = listOf(
                DotNetClrResolvedTypeSignature.Primitive(DotNetClrPrimitiveType.BOOLEAN)
            ),
        ) { decoded ->
            val argument = decoded.fixedArguments.singleOrNull()
            if (argument !is DotNetClrCustomAttributeValue.BooleanValue) {
                invalidValueShape()
            } else {
                DecodedValue(argument.value)
            }
        }

    private fun <T> decodeRecognizedAttribute(
        assembly: DotNetClrAssemblyMetadata,
        parent: DotNetClrMetadataHandle,
        metadataName: String,
        acceptedParameterTypes: List<DotNetClrResolvedTypeSignature>,
        decodeValue: (DotNetClrDecodedCustomAttribute) -> DecodedValue<T>,
    ): DotNetClrNullableMetadataResolution<T> {
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
                    is DotNetClrCustomAttributeConstructorResolution.Invalid -> return@mapNotNull null
                }
                val definition = constructor.attributeType.type.definition
                if (definition.declaringType != null ||
                    definition.namespaceName != NULLABLE_ATTRIBUTE_NAMESPACE ||
                    definition.metadataName != metadataName ||
                    constructor.attributeType.arguments.isNotEmpty() ||
                    constructor.signature.parameterTypes.singleOrNull() !in
                    acceptedParameterTypes
                ) {
                    return@mapNotNull null
                }
                RecognizedAttribute(attribute, constructor)
            }
            .toList()
        if (candidates.isEmpty()) return DotNetClrNullableMetadataResolution.Absent
        if (candidates.size != 1) {
            return DotNetClrNullableMetadataResolution.Invalid(
                failure = DotNetClrNullableMetadataFailure.DUPLICATE_ATTRIBUTE,
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
                return DotNetClrNullableMetadataResolution.Invalid(
                    failure = DotNetClrNullableMetadataFailure.VALUE_DECODING_FAILED,
                    attributes = listOf(candidate.attribute.handle),
                    valueDecoding = valueDecoding,
                )
            is DotNetClrCustomAttributeValueDecoding.Unsupported ->
                return DotNetClrNullableMetadataResolution.Invalid(
                    failure = DotNetClrNullableMetadataFailure.VALUE_DECODING_FAILED,
                    attributes = listOf(candidate.attribute.handle),
                    valueDecoding = valueDecoding,
                )
        }
        if (decoded.namedArguments.isNotEmpty()) {
            return DotNetClrNullableMetadataResolution.Invalid(
                failure = DotNetClrNullableMetadataFailure.INVALID_VALUE_SHAPE,
                attributes = listOf(candidate.attribute.handle),
            )
        }
        return when (val value = decodeValue(decoded)) {
            is DecodedValue.Value -> DotNetClrNullableMetadataResolution.Decoded(
                attribute = candidate.attribute.handle,
                value = value.value,
            )
            is DecodedValue.Invalid -> DotNetClrNullableMetadataResolution.Invalid(
                failure = value.failure,
                attributes = listOf(candidate.attribute.handle),
                invalidFlag = value.invalidFlag,
            )
        }
    }

    private fun <T> decodeAnnotation(
        value: Int,
        transform: (DotNetClrNullableAnnotation) -> T,
    ): DecodedValue<T> {
        val annotation = DotNetClrNullableAnnotation.fromMetadataValue(value)
            ?: return DecodedValue.Invalid(
                DotNetClrNullableMetadataFailure.INVALID_ANNOTATION_FLAG,
                value,
            )
        return DecodedValue.Value(transform(annotation))
    }

    private fun invalidValueShape(): DecodedValue.Invalid =
        DecodedValue.Invalid(DotNetClrNullableMetadataFailure.INVALID_VALUE_SHAPE)

    private fun invalidFlag(value: Int): DecodedValue.Invalid =
        DecodedValue.Invalid(
            DotNetClrNullableMetadataFailure.INVALID_ANNOTATION_FLAG,
            value,
        )

    private data class RecognizedAttribute(
        val attribute: DotNetClrCustomAttribute,
        val constructor: DotNetClrResolvedCustomAttributeConstructor,
    )

    private sealed interface DecodedValue<out T> {
        data class Value<T>(val value: T) : DecodedValue<T>

        data class Invalid(
            val failure: DotNetClrNullableMetadataFailure,
            val invalidFlag: Int? = null,
        ) : DecodedValue<Nothing>

        companion object {
            operator fun <T> invoke(value: T): DecodedValue<T> = Value(value)
        }
    }

    private companion object {
        const val MODULE_TABLE = 0
        const val NULLABLE_ATTRIBUTE_NAMESPACE = "System.Runtime.CompilerServices"
        const val NULLABLE_ATTRIBUTE_NAME = "NullableAttribute"
        const val NULLABLE_CONTEXT_ATTRIBUTE_NAME = "NullableContextAttribute"
        const val NULLABLE_PUBLIC_ONLY_ATTRIBUTE_NAME = "NullablePublicOnlyAttribute"
    }
}
