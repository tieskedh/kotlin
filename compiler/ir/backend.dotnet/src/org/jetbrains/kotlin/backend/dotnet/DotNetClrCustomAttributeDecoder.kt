package org.jetbrains.kotlin.backend.dotnet

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

enum class DotNetClrCustomAttributeConstructorFailure {
    INVALID_CONSTRUCTOR_HANDLE,
    INVALID_MEMBER_REFERENCE_KIND,
    UNSUPPORTED_MEMBER_REFERENCE_PARENT,
    NOT_INSTANCE_CONSTRUCTOR,
    INVALID_CONSTRUCTOR_SIGNATURE,
    ATTRIBUTE_TYPE_IS_INTERFACE,
    ATTRIBUTE_TYPE_IS_ABSTRACT,
    ATTRIBUTE_TYPE_DOES_NOT_DERIVE_FROM_SYSTEM_ATTRIBUTE,
    ATTRIBUTE_TYPE_RESOLUTION_FAILED,
    ATTRIBUTE_TYPE_INHERITANCE_CYCLE,
    ATTRIBUTE_TYPE_INHERITANCE_LIMIT_EXCEEDED,
}

data class DotNetClrResolvedCustomAttributeConstructor(
    val sourceAssembly: DotNetClrAssemblyMetadata,
    val attributeType: DotNetClrResolvedTypeDefinition,
    val signature: DotNetClrMethodSignature,
    val constructor: DotNetClrMetadataHandle,
)

sealed interface DotNetClrCustomAttributeConstructorResolution {
    data class Resolved(
        val constructor: DotNetClrResolvedCustomAttributeConstructor,
    ) : DotNetClrCustomAttributeConstructorResolution

    data class Invalid(
        val failure: DotNetClrCustomAttributeConstructorFailure,
        val typeResolution: DotNetClrTypeResolution.Unresolved? = null,
    ) : DotNetClrCustomAttributeConstructorResolution
}

sealed interface DotNetClrCustomAttributeValue {
    data class BooleanValue(
        val value: Boolean,
    ) : DotNetClrCustomAttributeValue

    data class CharValue(
        val value: Char,
    ) : DotNetClrCustomAttributeValue

    /**
     * Exact two's-complement bits. [type] supplies the signedness and width.
     */
    data class IntegralValue(
        val type: DotNetClrPrimitiveType,
        val bits: ULong,
    ) : DotNetClrCustomAttributeValue {
        init {
            val bitWidth = when (type) {
                DotNetClrPrimitiveType.INT8,
                DotNetClrPrimitiveType.UINT8,
                -> 8

                DotNetClrPrimitiveType.INT16,
                DotNetClrPrimitiveType.UINT16,
                -> 16

                DotNetClrPrimitiveType.INT32,
                DotNetClrPrimitiveType.UINT32,
                -> 32

                DotNetClrPrimitiveType.INT64,
                DotNetClrPrimitiveType.UINT64,
                -> 64

                else -> error("Custom-attribute integral value cannot have type $type")
            }
            require(bitWidth == 64 || bits < (1uL shl bitWidth)) {
                "Custom-attribute $type value does not fit in $bitWidth bits"
            }
        }
    }

    /**
     * Exact IEEE-754 payload bits, including signed zero and NaN payloads.
     */
    data class Float32Value(
        val bits: Int,
    ) : DotNetClrCustomAttributeValue

    /**
     * Exact IEEE-754 payload bits, including signed zero and NaN payloads.
     */
    data class Float64Value(
        val bits: Long,
    ) : DotNetClrCustomAttributeValue

    data class StringValue(
        val value: String?,
    ) : DotNetClrCustomAttributeValue
}

data class DotNetClrDecodedCustomAttribute(
    val constructor: DotNetClrResolvedCustomAttributeConstructor,
    val fixedArguments: List<DotNetClrCustomAttributeValue>,
)

enum class DotNetClrCustomAttributeValueFailure {
    CONSTRUCTOR_MISMATCH,
    MISSING_VALUE_BLOB,
    INVALID_PROLOG,
    TRUNCATED_VALUE,
    INVALID_BOOLEAN,
    INVALID_SERIALIZED_STRING,
    INVALID_UTF8,
    TRAILING_DATA,
}

enum class DotNetClrCustomAttributeValueUnsupported {
    FIXED_ARGUMENT_TYPE,
    NAMED_ARGUMENTS,
}

sealed interface DotNetClrCustomAttributeValueDecoding {
    data class Decoded(
        val attribute: DotNetClrDecodedCustomAttribute,
    ) : DotNetClrCustomAttributeValueDecoding

    data class Invalid(
        val failure: DotNetClrCustomAttributeValueFailure,
        val fixedArgumentIndex: Int? = null,
    ) : DotNetClrCustomAttributeValueDecoding

    data class Unsupported(
        val unsupported: DotNetClrCustomAttributeValueUnsupported,
        val fixedArgumentIndex: Int? = null,
    ) : DotNetClrCustomAttributeValueDecoding
}

/**
 * Resolves the semantic constructor edge of an ordinary CLR custom attribute.
 *
 * This layer still does not create a Kotlin annotation. It proves the actual attribute class and
 * constructor signature before the value blob is interpreted.
 */
class DotNetClrCustomAttributeDecoder(
    private val typeResolver: DotNetClrTypeResolver,
    private val systemAttribute: DotNetClrResolvedTypeDefinition,
) {
    fun resolveConstructor(
        assembly: DotNetClrAssemblyMetadata,
        attribute: DotNetClrCustomAttribute,
    ): DotNetClrCustomAttributeConstructorResolution {
        val constructorName: String
        val signature: DotNetClrMethodSignature
        val attributeType: DotNetClrResolvedTypeDefinition
        when (attribute.constructor.table) {
            METHOD_DEF_TABLE -> {
                val method = assembly.methodDefinitions.singleOrNull { candidate ->
                    candidate.handle == attribute.constructor
                } ?: return invalid(
                    DotNetClrCustomAttributeConstructorFailure.INVALID_CONSTRUCTOR_HANDLE
                )
                constructorName = method.name
                signature = method.signature
                if (method.isStatic ||
                    !method.isSpecialName ||
                    !method.isRuntimeSpecialName
                ) {
                    return invalid(
                        DotNetClrCustomAttributeConstructorFailure.NOT_INSTANCE_CONSTRUCTOR
                    )
                }
                attributeType = when (
                    val resolution =
                        typeResolver.resolveTypeDefinition(assembly, method.declaringType)
                ) {
                    is DotNetClrTypeResolution.Resolved -> resolution.type
                    is DotNetClrTypeResolution.Unresolved ->
                        return invalid(
                            DotNetClrCustomAttributeConstructorFailure.ATTRIBUTE_TYPE_RESOLUTION_FAILED,
                            resolution,
                        )
                }
            }

            MEMBER_REF_TABLE -> {
                val memberReference = assembly.memberReferences.singleOrNull { candidate ->
                    candidate.handle == attribute.constructor
                } ?: return invalid(
                    DotNetClrCustomAttributeConstructorFailure.INVALID_CONSTRUCTOR_HANDLE
                )
                constructorName = memberReference.name
                signature = when (val memberSignature = memberReference.signature) {
                    is DotNetClrMemberReferenceSignature.Method -> memberSignature.signature
                    is DotNetClrMemberReferenceSignature.Field ->
                        return invalid(
                            DotNetClrCustomAttributeConstructorFailure.INVALID_MEMBER_REFERENCE_KIND
                        )
                }
                if (memberReference.parent.table != TYPE_DEF_TABLE &&
                    memberReference.parent.table != TYPE_REF_TABLE
                ) {
                    return invalid(
                        DotNetClrCustomAttributeConstructorFailure.UNSUPPORTED_MEMBER_REFERENCE_PARENT
                    )
                }
                attributeType = when (
                    val resolution =
                        typeResolver.resolveTypeDefinition(assembly, memberReference.parent)
                ) {
                    is DotNetClrTypeResolution.Resolved -> resolution.type
                    is DotNetClrTypeResolution.Unresolved ->
                        return invalid(
                            DotNetClrCustomAttributeConstructorFailure.ATTRIBUTE_TYPE_RESOLUTION_FAILED,
                            resolution,
                        )
                }
            }

            else -> return invalid(
                DotNetClrCustomAttributeConstructorFailure.INVALID_CONSTRUCTOR_HANDLE
            )
        }
        if (constructorName != ".ctor" || !signature.hasThis) {
            return invalid(DotNetClrCustomAttributeConstructorFailure.NOT_INSTANCE_CONSTRUCTOR)
        }
        if (signature.callingConvention != DotNetClrSignatureCallingConvention.DEFAULT ||
            signature.hasExplicitThis ||
            signature.genericParameterCount != 0 ||
            signature.returnType != DotNetClrTypeSignature.Void ||
            signature.varargParameterStart != null
        ) {
            return invalid(DotNetClrCustomAttributeConstructorFailure.INVALID_CONSTRUCTOR_SIGNATURE)
        }
        if (attributeType.definition.isInterface) {
            return invalid(DotNetClrCustomAttributeConstructorFailure.ATTRIBUTE_TYPE_IS_INTERFACE)
        }
        if (attributeType.definition.isAbstract) {
            return invalid(DotNetClrCustomAttributeConstructorFailure.ATTRIBUTE_TYPE_IS_ABSTRACT)
        }
        when (val hierarchy = typeResolver.isSameOrDerivedFrom(attributeType, systemAttribute)) {
            DotNetClrTypeHierarchyResolution.Matches -> Unit
            DotNetClrTypeHierarchyResolution.DoesNotMatch ->
                return invalid(
                    DotNetClrCustomAttributeConstructorFailure
                        .ATTRIBUTE_TYPE_DOES_NOT_DERIVE_FROM_SYSTEM_ATTRIBUTE
                )

            is DotNetClrTypeHierarchyResolution.Unresolved ->
                return invalid(
                    DotNetClrCustomAttributeConstructorFailure.ATTRIBUTE_TYPE_RESOLUTION_FAILED,
                    hierarchy.resolution,
                )

            DotNetClrTypeHierarchyResolution.InheritanceCycle ->
                return invalid(
                    DotNetClrCustomAttributeConstructorFailure.ATTRIBUTE_TYPE_INHERITANCE_CYCLE
                )

            DotNetClrTypeHierarchyResolution.ResolutionLimitExceeded ->
                return invalid(
                    DotNetClrCustomAttributeConstructorFailure
                        .ATTRIBUTE_TYPE_INHERITANCE_LIMIT_EXCEEDED
                )
        }
        return DotNetClrCustomAttributeConstructorResolution.Resolved(
            DotNetClrResolvedCustomAttributeConstructor(
                sourceAssembly = assembly,
                attributeType = attributeType,
                signature = signature,
                constructor = attribute.constructor,
            )
        )
    }

    /**
     * Decodes primitive and string fixed arguments supported by ECMA-335.
     *
     * Arrays, tagged objects, System.Type, enums, and named arguments remain structured
     * unsupported results until their required type-resolution layers are available.
     */
    fun decodeScalarValue(
        assembly: DotNetClrAssemblyMetadata,
        attribute: DotNetClrCustomAttribute,
        constructor: DotNetClrResolvedCustomAttributeConstructor,
    ): DotNetClrCustomAttributeValueDecoding {
        if (assembly !== constructor.sourceAssembly ||
            attribute.constructor != constructor.constructor
        ) {
            return invalidValue(DotNetClrCustomAttributeValueFailure.CONSTRUCTOR_MISMATCH)
        }
        val rawValue = attribute.rawValue
        if (rawValue == null) {
            return if (constructor.signature.parameterTypes.isEmpty()) {
                decodedValue(constructor, emptyList())
            } else {
                invalidValue(DotNetClrCustomAttributeValueFailure.MISSING_VALUE_BLOB)
            }
        }
        val reader = CustomAttributeBlobReader(rawValue.toByteArray())
        return try {
            if (reader.readUnsigned(2).toInt() != CUSTOM_ATTRIBUTE_PROLOG) {
                return invalidValue(DotNetClrCustomAttributeValueFailure.INVALID_PROLOG)
            }
            val fixedArguments =
                ArrayList<DotNetClrCustomAttributeValue>(constructor.signature.parameterTypes.size)
            constructor.signature.parameterTypes.forEachIndexed { index, parameterType ->
                val value = try {
                    decodeScalarFixedArgument(reader, parameterType)
                } catch (failure: CustomAttributeBlobFailure) {
                    return invalidValue(failure.failure, index)
                }
                    ?: return unsupportedValue(
                        DotNetClrCustomAttributeValueUnsupported.FIXED_ARGUMENT_TYPE,
                        index,
                    )
                fixedArguments += value
            }
            val namedArgumentCount = reader.readUnsigned(2).toInt()
            if (namedArgumentCount != 0) {
                return unsupportedValue(
                    DotNetClrCustomAttributeValueUnsupported.NAMED_ARGUMENTS
                )
            }
            if (!reader.isAtEnd) {
                return invalidValue(DotNetClrCustomAttributeValueFailure.TRAILING_DATA)
            }
            decodedValue(constructor, fixedArguments)
        } catch (failure: CustomAttributeBlobFailure) {
            invalidValue(failure.failure, failure.fixedArgumentIndex)
        }
    }

    private fun decodeScalarFixedArgument(
        reader: CustomAttributeBlobReader,
        type: DotNetClrTypeSignature,
    ): DotNetClrCustomAttributeValue? {
        val primitive = (type as? DotNetClrTypeSignature.Primitive)?.type ?: return null
        return when (primitive) {
            DotNetClrPrimitiveType.BOOLEAN -> {
                when (val value = reader.readUnsigned(1).toInt()) {
                    0 -> DotNetClrCustomAttributeValue.BooleanValue(false)
                    1 -> DotNetClrCustomAttributeValue.BooleanValue(true)
                    else -> reader.fail(DotNetClrCustomAttributeValueFailure.INVALID_BOOLEAN)
                }
            }

            DotNetClrPrimitiveType.CHAR ->
                DotNetClrCustomAttributeValue.CharValue(
                    reader.readUnsigned(2).toInt().toChar()
                )

            DotNetClrPrimitiveType.INT8,
            DotNetClrPrimitiveType.UINT8,
            -> integralValue(primitive, reader, 1)

            DotNetClrPrimitiveType.INT16,
            DotNetClrPrimitiveType.UINT16,
            -> integralValue(primitive, reader, 2)

            DotNetClrPrimitiveType.INT32,
            DotNetClrPrimitiveType.UINT32,
            -> integralValue(primitive, reader, 4)

            DotNetClrPrimitiveType.INT64,
            DotNetClrPrimitiveType.UINT64,
            -> integralValue(primitive, reader, 8)

            DotNetClrPrimitiveType.FLOAT32 ->
                DotNetClrCustomAttributeValue.Float32Value(
                    reader.readUnsigned(4).toInt()
                )

            DotNetClrPrimitiveType.FLOAT64 ->
                DotNetClrCustomAttributeValue.Float64Value(
                    reader.readUnsigned(8).toLong()
                )

            DotNetClrPrimitiveType.STRING ->
                DotNetClrCustomAttributeValue.StringValue(reader.readSerializedString())

            DotNetClrPrimitiveType.NATIVE_INT,
            DotNetClrPrimitiveType.NATIVE_UINT,
            DotNetClrPrimitiveType.OBJECT,
            -> null
        }
    }

    private fun integralValue(
        type: DotNetClrPrimitiveType,
        reader: CustomAttributeBlobReader,
        byteCount: Int,
    ): DotNetClrCustomAttributeValue.IntegralValue =
        DotNetClrCustomAttributeValue.IntegralValue(type, reader.readUnsigned(byteCount))

    private fun decodedValue(
        constructor: DotNetClrResolvedCustomAttributeConstructor,
        fixedArguments: List<DotNetClrCustomAttributeValue>,
    ): DotNetClrCustomAttributeValueDecoding.Decoded =
        DotNetClrCustomAttributeValueDecoding.Decoded(
            DotNetClrDecodedCustomAttribute(constructor, fixedArguments)
        )

    private fun invalidValue(
        failure: DotNetClrCustomAttributeValueFailure,
        fixedArgumentIndex: Int? = null,
    ): DotNetClrCustomAttributeValueDecoding.Invalid =
        DotNetClrCustomAttributeValueDecoding.Invalid(failure, fixedArgumentIndex)

    private fun unsupportedValue(
        unsupported: DotNetClrCustomAttributeValueUnsupported,
        fixedArgumentIndex: Int? = null,
    ): DotNetClrCustomAttributeValueDecoding.Unsupported =
        DotNetClrCustomAttributeValueDecoding.Unsupported(unsupported, fixedArgumentIndex)

    private fun invalid(
        failure: DotNetClrCustomAttributeConstructorFailure,
        typeResolution: DotNetClrTypeResolution.Unresolved? = null,
    ): DotNetClrCustomAttributeConstructorResolution.Invalid =
        DotNetClrCustomAttributeConstructorResolution.Invalid(failure, typeResolution)

    private companion object {
        const val TYPE_REF_TABLE = 1
        const val TYPE_DEF_TABLE = 2
        const val METHOD_DEF_TABLE = 6
        const val MEMBER_REF_TABLE = 10
        const val CUSTOM_ATTRIBUTE_PROLOG = 1
    }
}

private class CustomAttributeBlobReader(
    private val bytes: ByteArray,
) {
    private var offset = 0

    val isAtEnd: Boolean
        get() = offset == bytes.size

    fun readUnsigned(byteCount: Int): ULong {
        if (byteCount !in 1..8 || bytes.size - offset < byteCount) {
            fail(DotNetClrCustomAttributeValueFailure.TRUNCATED_VALUE)
        }
        var result = 0uL
        repeat(byteCount) { byteIndex ->
            result = result or
                    ((bytes[offset++].toInt() and 0xff).toULong() shl (byteIndex * 8))
        }
        return result
    }

    fun readSerializedString(): String? {
        val first = readUnsigned(1).toInt()
        if (first == NULL_SERIALIZED_STRING) return null
        val length = when {
            first and ONE_BYTE_LENGTH_MASK == 0 -> first
            first and TWO_BYTE_LENGTH_MASK == TWO_BYTE_LENGTH_PREFIX -> {
                val decoded = ((first and TWO_BYTE_LENGTH_VALUE_MASK) shl 8) or
                        readUnsigned(1).toInt()
                if (decoded < TWO_BYTE_LENGTH_MINIMUM) {
                    fail(DotNetClrCustomAttributeValueFailure.INVALID_SERIALIZED_STRING)
                }
                decoded
            }

            first and FOUR_BYTE_LENGTH_MASK == FOUR_BYTE_LENGTH_PREFIX -> {
                val decoded =
                    ((first and FOUR_BYTE_LENGTH_VALUE_MASK) shl 24) or
                            (readUnsigned(1).toInt() shl 16) or
                            (readUnsigned(1).toInt() shl 8) or
                            readUnsigned(1).toInt()
                if (decoded < FOUR_BYTE_LENGTH_MINIMUM ||
                    decoded > MAX_SERIALIZED_STRING_LENGTH
                ) {
                    fail(DotNetClrCustomAttributeValueFailure.INVALID_SERIALIZED_STRING)
                }
                decoded
            }

            else -> fail(DotNetClrCustomAttributeValueFailure.INVALID_SERIALIZED_STRING)
        }
        if (bytes.size - offset < length) {
            fail(DotNetClrCustomAttributeValueFailure.TRUNCATED_VALUE)
        }
        val value = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, length))
                .toString()
        } catch (_: CharacterCodingException) {
            fail(DotNetClrCustomAttributeValueFailure.INVALID_UTF8)
        }
        offset += length
        return value
    }

    fun fail(
        failure: DotNetClrCustomAttributeValueFailure,
        fixedArgumentIndex: Int? = null,
    ): Nothing = throw CustomAttributeBlobFailure(failure, fixedArgumentIndex)

    private companion object {
        const val NULL_SERIALIZED_STRING = 0xff
        const val ONE_BYTE_LENGTH_MASK = 0x80
        const val TWO_BYTE_LENGTH_MASK = 0xc0
        const val TWO_BYTE_LENGTH_PREFIX = 0x80
        const val TWO_BYTE_LENGTH_VALUE_MASK = 0x3f
        const val TWO_BYTE_LENGTH_MINIMUM = 0x80
        const val FOUR_BYTE_LENGTH_MASK = 0xe0
        const val FOUR_BYTE_LENGTH_PREFIX = 0xc0
        const val FOUR_BYTE_LENGTH_VALUE_MASK = 0x1f
        const val FOUR_BYTE_LENGTH_MINIMUM = 0x4000
        const val MAX_SERIALIZED_STRING_LENGTH = 0x1fff_ffff
    }
}

private class CustomAttributeBlobFailure(
    val failure: DotNetClrCustomAttributeValueFailure,
    val fixedArgumentIndex: Int?,
) : Exception()
