package org.jetbrains.kotlin.backend.dotnet

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

enum class DotNetClrCustomAttributeConstructorFailure {
    INVALID_CONSTRUCTOR_HANDLE,
    INVALID_MEMBER_REFERENCE_KIND,
    UNSUPPORTED_MEMBER_REFERENCE_PARENT,
    INVALID_ATTRIBUTE_TYPE_SIGNATURE,
    ATTRIBUTE_TYPE_IS_OPEN_GENERIC,
    NOT_INSTANCE_CONSTRUCTOR,
    INVALID_CONSTRUCTOR_SIGNATURE,
    CONSTRUCTOR_SIGNATURE_RESOLUTION_FAILED,
    CONSTRUCTOR_SIGNATURE_SUBSTITUTION_FAILED,
    CONSTRUCTOR_SIGNATURE_IS_OPEN_GENERIC,
    ATTRIBUTE_TYPE_IS_INTERFACE,
    ATTRIBUTE_TYPE_IS_ABSTRACT,
    ATTRIBUTE_TYPE_DOES_NOT_DERIVE_FROM_SYSTEM_ATTRIBUTE,
    ATTRIBUTE_TYPE_RESOLUTION_FAILED,
    ATTRIBUTE_TYPE_INHERITANCE_CYCLE,
    ATTRIBUTE_TYPE_INHERITANCE_LIMIT_EXCEEDED,
}

data class DotNetClrResolvedCustomAttributeConstructor(
    val sourceAssembly: DotNetClrAssemblyMetadata,
    val attributeType: DotNetClrResolvedTypeView,
    val signature: DotNetClrResolvedMethodSignature,
    val constructor: DotNetClrMetadataHandle,
)

data class DotNetClrCustomAttributeCoreTypes(
    val systemAttribute: DotNetClrResolvedTypeDefinition,
    val systemEnum: DotNetClrResolvedTypeDefinition,
    val systemType: DotNetClrResolvedTypeDefinition,
)

sealed interface DotNetClrCustomAttributeConstructorResolution {
    data class Resolved(
        val constructor: DotNetClrResolvedCustomAttributeConstructor,
    ) : DotNetClrCustomAttributeConstructorResolution

    data class Invalid(
        val failure: DotNetClrCustomAttributeConstructorFailure,
        val typeResolution: DotNetClrTypeResolution.Unresolved? = null,
        val signatureResolution: DotNetClrResolvedSignatureResolution.Invalid? = null,
        val signatureSubstitution: DotNetClrResolvedSignatureSubstitution.Invalid? = null,
    ) : DotNetClrCustomAttributeConstructorResolution
}

sealed interface DotNetClrCustomAttributeValueType {
    data class Primitive(
        val type: DotNetClrPrimitiveType,
    ) : DotNetClrCustomAttributeValueType {
        init {
            require(
                type != DotNetClrPrimitiveType.NATIVE_INT &&
                        type != DotNetClrPrimitiveType.NATIVE_UINT &&
                        type != DotNetClrPrimitiveType.OBJECT
            ) {
                "Unsupported custom-attribute primitive value type: $type"
            }
        }
    }

    data object TaggedObject : DotNetClrCustomAttributeValueType

    data object SystemType : DotNetClrCustomAttributeValueType

    data class EnumType(
        val type: DotNetClrResolvedSerializedType,
        val storageType: DotNetClrPrimitiveType,
    ) : DotNetClrCustomAttributeValueType {
        init {
            customAttributeIntegralBitWidth(storageType)
        }
    }

    data class SzArray(
        val elementType: DotNetClrCustomAttributeValueType,
    ) : DotNetClrCustomAttributeValueType {
        init {
            require(elementType !is SzArray) {
                "Custom-attribute arrays cannot be jagged"
            }
        }
    }
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
            val bitWidth = customAttributeIntegralBitWidth(type)
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

    data class TypeValue(
        val value: DotNetClrResolvedSerializedType?,
    ) : DotNetClrCustomAttributeValue

    data class ArrayValue(
        val type: DotNetClrCustomAttributeValueType.SzArray,
        val elements: List<DotNetClrCustomAttributeValue>?,
    ) : DotNetClrCustomAttributeValue

    data class EnumValue(
        val type: DotNetClrCustomAttributeValueType.EnumType,
        val storageValue: IntegralValue,
    ) : DotNetClrCustomAttributeValue {
        init {
            require(storageValue.type == type.storageType) {
                "Custom-attribute enum storage value does not match its declared storage type"
            }
        }
    }
}

enum class DotNetClrCustomAttributeNamedArgumentKind {
    FIELD,
    PROPERTY,
}

data class DotNetClrCustomAttributeNamedArgument(
    val kind: DotNetClrCustomAttributeNamedArgumentKind,
    val name: String,
    val type: DotNetClrCustomAttributeValueType,
    val value: DotNetClrCustomAttributeValue,
)

data class DotNetClrDecodedCustomAttribute(
    val constructor: DotNetClrResolvedCustomAttributeConstructor,
    val fixedArguments: List<DotNetClrCustomAttributeValue>,
    val namedArguments: List<DotNetClrCustomAttributeNamedArgument>,
)

enum class DotNetClrCustomAttributeValueFailure {
    CONSTRUCTOR_MISMATCH,
    MISSING_VALUE_BLOB,
    INVALID_PROLOG,
    TRUNCATED_VALUE,
    INVALID_BOOLEAN,
    INVALID_SERIALIZED_STRING,
    INVALID_UTF8,
    INVALID_SERIALIZATION_TYPE_CODE,
    INVALID_NAMED_ARGUMENT_KIND,
    INVALID_NAMED_ARGUMENT_NAME,
    INVALID_ARRAY_LENGTH,
    TYPE_RESOLUTION_FAILED,
    INVALID_ENUM_TYPE,
    VALUE_LIMIT_EXCEEDED,
    TRAILING_DATA,
}

enum class DotNetClrCustomAttributeValueUnsupported {
    FIXED_ARGUMENT_TYPE,
}

sealed interface DotNetClrCustomAttributeValueDecoding {
    data class Decoded(
        val attribute: DotNetClrDecodedCustomAttribute,
    ) : DotNetClrCustomAttributeValueDecoding

    data class Invalid(
        val failure: DotNetClrCustomAttributeValueFailure,
        val fixedArgumentIndex: Int? = null,
        val namedArgumentIndex: Int? = null,
        val typeResolution: DotNetClrTypeResolution.Unresolved? = null,
        val serializedTypeResolution: DotNetClrSerializedTypeResolution? = null,
        val enumStorageFailure: DotNetClrEnumStorageFailure? = null,
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
    private val serializedTypeResolver: DotNetClrSerializedTypeResolver,
    private val coreTypes: DotNetClrCustomAttributeCoreTypes,
) {
    private val signatureResolver = DotNetClrSignatureResolver(typeResolver)

    fun resolveConstructor(
        assembly: DotNetClrAssemblyMetadata,
        attribute: DotNetClrCustomAttribute,
    ): DotNetClrCustomAttributeConstructorResolution {
        val constructorName: String
        val rawSignature: DotNetClrMethodSignature
        val attributeType: DotNetClrResolvedTypeView
        when (attribute.constructor.table) {
            METHOD_DEF_TABLE -> {
                val method = assembly.methodDefinitions.singleOrNull { candidate ->
                    candidate.handle == attribute.constructor
                } ?: return invalid(
                    DotNetClrCustomAttributeConstructorFailure.INVALID_CONSTRUCTOR_HANDLE
                )
                constructorName = method.name
                rawSignature = method.signature
                if (method.isStatic ||
                    !method.isSpecialName ||
                    !method.isRuntimeSpecialName
                ) {
                    return invalid(
                        DotNetClrCustomAttributeConstructorFailure.NOT_INSTANCE_CONSTRUCTOR
                    )
                }
                val resolvedAttributeType = when (
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
                attributeType = when (
                    val view = attributeTypeView(resolvedAttributeType, emptyList())
                ) {
                    is CustomAttributeTypeViewResolution.Resolved -> view.view
                    is CustomAttributeTypeViewResolution.Invalid ->
                        return invalid(
                            view.failure,
                            signatureResolution = view.signatureResolution,
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
                rawSignature = when (val memberSignature = memberReference.signature) {
                    is DotNetClrMemberReferenceSignature.Method -> memberSignature.signature
                    is DotNetClrMemberReferenceSignature.Field ->
                        return invalid(
                            DotNetClrCustomAttributeConstructorFailure.INVALID_MEMBER_REFERENCE_KIND
                        )
                }
                attributeType = when (
                    val view = resolveAttributeTypeView(assembly, memberReference.parent)
                ) {
                    is CustomAttributeTypeViewResolution.Resolved -> view.view
                    is CustomAttributeTypeViewResolution.Invalid ->
                        return invalid(
                            view.failure,
                            typeResolution = view.typeResolution,
                            signatureResolution = view.signatureResolution,
                        )
                }
            }

            else -> return invalid(
                DotNetClrCustomAttributeConstructorFailure.INVALID_CONSTRUCTOR_HANDLE
            )
        }
        if (constructorName != ".ctor" || !rawSignature.hasThis) {
            return invalid(DotNetClrCustomAttributeConstructorFailure.NOT_INSTANCE_CONSTRUCTOR)
        }
        if (rawSignature.callingConvention != DotNetClrSignatureCallingConvention.DEFAULT ||
            rawSignature.hasExplicitThis ||
            rawSignature.genericParameterCount != 0 ||
            rawSignature.returnType != DotNetClrTypeSignature.Void ||
            rawSignature.varargParameterStart != null
        ) {
            return invalid(DotNetClrCustomAttributeConstructorFailure.INVALID_CONSTRUCTOR_SIGNATURE)
        }
        val resolvedSignature = when (
            val resolution = signatureResolver.resolve(assembly, rawSignature)
        ) {
            is DotNetClrResolvedMethodSignatureResolution.Resolved -> resolution.signature
            is DotNetClrResolvedMethodSignatureResolution.UnresolvedType ->
                return invalid(
                    DotNetClrCustomAttributeConstructorFailure
                        .CONSTRUCTOR_SIGNATURE_RESOLUTION_FAILED,
                    typeResolution = resolution.resolution,
                )

            is DotNetClrResolvedMethodSignatureResolution.Invalid ->
                return invalid(
                    DotNetClrCustomAttributeConstructorFailure
                        .CONSTRUCTOR_SIGNATURE_RESOLUTION_FAILED,
                    signatureResolution = resolution.resolution,
                )
        }
        val effectiveSignature = when (
            val substitution =
                resolvedSignature.substituteClrTypeArguments(attributeType.arguments)
        ) {
            is DotNetClrResolvedMethodSignatureSubstitution.Substituted ->
                substitution.signature

            is DotNetClrResolvedMethodSignatureSubstitution.Invalid ->
                return invalid(
                    DotNetClrCustomAttributeConstructorFailure
                        .CONSTRUCTOR_SIGNATURE_SUBSTITUTION_FAILED,
                    signatureSubstitution = substitution.resolution,
                )
        }
        if (effectiveSignature.parameterTypes.any(
                DotNetClrResolvedTypeSignature::containsGenericParameter
            )
        ) {
            return invalid(
                DotNetClrCustomAttributeConstructorFailure
                    .CONSTRUCTOR_SIGNATURE_IS_OPEN_GENERIC
            )
        }
        if (attributeType.type.definition.isInterface) {
            return invalid(DotNetClrCustomAttributeConstructorFailure.ATTRIBUTE_TYPE_IS_INTERFACE)
        }
        if (attributeType.type.definition.isAbstract) {
            return invalid(DotNetClrCustomAttributeConstructorFailure.ATTRIBUTE_TYPE_IS_ABSTRACT)
        }
        when (
            val hierarchy =
                typeResolver.isSameOrDerivedFrom(attributeType.type, coreTypes.systemAttribute)
        ) {
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
                signature = effectiveSignature,
                constructor = attribute.constructor,
            )
        )
    }

    /**
     * Decodes primitive, string, array, tagged-object, type, and enum fixed and named arguments
     * supported by ECMA-335.
     */
    fun decodeValue(
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
                decodedValue(constructor, emptyList(), emptyList())
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
                    val valueType = fixedArgumentType(parameterType)
                        ?: return unsupportedValue(
                            DotNetClrCustomAttributeValueUnsupported.FIXED_ARGUMENT_TYPE,
                            index,
                        )
                    decodeArgument(
                        assembly,
                        reader,
                        valueType,
                        nestingDepth = 0,
                    )
                } catch (failure: CustomAttributeBlobFailure) {
                    return invalidValue(failure.failure, index)
                } catch (failure: CustomAttributeTypeResolutionFailure) {
                    return invalidValue(
                        DotNetClrCustomAttributeValueFailure.TYPE_RESOLUTION_FAILED,
                        fixedArgumentIndex = index,
                        typeResolution = failure.resolution,
                    )
                } catch (failure: CustomAttributeSerializedTypeResolutionFailure) {
                    return invalidValue(
                        DotNetClrCustomAttributeValueFailure.TYPE_RESOLUTION_FAILED,
                        fixedArgumentIndex = index,
                        serializedTypeResolution = failure.resolution,
                    )
                } catch (failure: CustomAttributeEnumFailure) {
                    return invalidValue(
                        DotNetClrCustomAttributeValueFailure.INVALID_ENUM_TYPE,
                        fixedArgumentIndex = index,
                        enumStorageFailure = failure.failure,
                    )
                }
                fixedArguments += value
            }
            val namedArgumentCount = reader.readUnsigned(2).toInt()
            val namedArguments =
                ArrayList<DotNetClrCustomAttributeNamedArgument>(namedArgumentCount)
            repeat(namedArgumentCount) { index ->
                val namedArgument = try {
                    decodeNamedArgument(assembly, reader)
                } catch (failure: CustomAttributeBlobFailure) {
                    return invalidValue(
                        failure.failure,
                        namedArgumentIndex = index,
                    )
                } catch (failure: CustomAttributeTypeResolutionFailure) {
                    return invalidValue(
                        DotNetClrCustomAttributeValueFailure.TYPE_RESOLUTION_FAILED,
                        namedArgumentIndex = index,
                        typeResolution = failure.resolution,
                    )
                } catch (failure: CustomAttributeSerializedTypeResolutionFailure) {
                    return invalidValue(
                        DotNetClrCustomAttributeValueFailure.TYPE_RESOLUTION_FAILED,
                        namedArgumentIndex = index,
                        serializedTypeResolution = failure.resolution,
                    )
                } catch (failure: CustomAttributeEnumFailure) {
                    return invalidValue(
                        DotNetClrCustomAttributeValueFailure.INVALID_ENUM_TYPE,
                        namedArgumentIndex = index,
                        enumStorageFailure = failure.failure,
                    )
                }
                namedArguments += namedArgument
            }
            if (!reader.isAtEnd) {
                return invalidValue(DotNetClrCustomAttributeValueFailure.TRAILING_DATA)
            }
            decodedValue(constructor, fixedArguments, namedArguments)
        } catch (failure: CustomAttributeBlobFailure) {
            invalidValue(failure.failure, failure.fixedArgumentIndex)
        }
    }

    private fun fixedArgumentType(
        type: DotNetClrResolvedTypeSignature,
        isArrayElement: Boolean = false,
    ): DotNetClrCustomAttributeValueType? =
        when (type) {
            is DotNetClrResolvedTypeSignature.Primitive -> {
                when (type.type) {
                    DotNetClrPrimitiveType.NATIVE_INT,
                    DotNetClrPrimitiveType.NATIVE_UINT,
                    -> null

                    DotNetClrPrimitiveType.OBJECT ->
                        DotNetClrCustomAttributeValueType.TaggedObject

                    else -> DotNetClrCustomAttributeValueType.Primitive(type.type)
                }
            }

            is DotNetClrResolvedTypeSignature.SzArray -> {
                if (isArrayElement) {
                    null
                } else {
                    fixedArgumentType(
                        type.elementType,
                        isArrayElement = true,
                    )?.let { elementType ->
                        DotNetClrCustomAttributeValueType.SzArray(elementType)
                    }
                }
            }

            is DotNetClrResolvedTypeSignature.Named ->
                resolvedNominalFixedArgumentType(type)

            is DotNetClrResolvedTypeSignature.GenericInstance ->
                resolvedNominalFixedArgumentType(type)

            is DotNetClrResolvedTypeSignature.Modified ->
                fixedArgumentType(type.unmodifiedType, isArrayElement)

            else -> null
        }

    private fun resolvedNominalFixedArgumentType(
        signature: DotNetClrResolvedTypeSignature,
    ): DotNetClrCustomAttributeValueType? {
        val namedType: DotNetClrResolvedTypeDefinition
        val isValueType: Boolean
        val serializedType: DotNetClrResolvedSerializedType
        when (signature) {
            is DotNetClrResolvedTypeSignature.Named -> {
                namedType = signature.type
                isValueType = signature.isValueType
                serializedType = DotNetClrResolvedSerializedType.Named(signature.type)
            }

            is DotNetClrResolvedTypeSignature.GenericInstance -> {
                namedType = signature.genericType.type
                isValueType = signature.genericType.isValueType
                serializedType = resolvedSignatureToSerializedType(signature) ?: return null
            }

            else -> error("Non-nominal custom-attribute fixed argument reached nominal mapping")
        }
        if (namedType.hasSameIdentityAs(coreTypes.systemType)) {
            return if (isValueType || signature !is DotNetClrResolvedTypeSignature.Named) {
                null
            } else {
                DotNetClrCustomAttributeValueType.SystemType
            }
        }
        return when (
            val enumStorage =
                typeResolver.resolveEnumStorage(namedType, coreTypes.systemEnum)
        ) {
            is DotNetClrEnumStorageResolution.Resolved -> {
                if (!isValueType) {
                    throw CustomAttributeEnumFailure()
                }
                DotNetClrCustomAttributeValueType.EnumType(
                    serializedType,
                    enumStorage.storageType,
                )
            }

            DotNetClrEnumStorageResolution.NotEnum -> null
            is DotNetClrEnumStorageResolution.UnresolvedBaseType ->
                throw CustomAttributeTypeResolutionFailure(enumStorage.resolution)

            is DotNetClrEnumStorageResolution.Invalid ->
                throw CustomAttributeEnumFailure(enumStorage.failure)
        }
    }

    private fun resolvedSignatureToSerializedType(
        signature: DotNetClrResolvedTypeSignature,
    ): DotNetClrResolvedSerializedType? =
        when (signature) {
            is DotNetClrResolvedTypeSignature.Named ->
                DotNetClrResolvedSerializedType.Named(signature.type)

            is DotNetClrResolvedTypeSignature.Primitive -> {
                val primitiveType = when (
                    val resolution = typeResolver.resolveTopLevelType(
                        coreTypes.systemType.assembly,
                        "System",
                        signature.type.systemTypeMetadataName,
                    )
                ) {
                    is DotNetClrTypeResolution.Resolved -> resolution.type
                    is DotNetClrTypeResolution.Unresolved ->
                        throw CustomAttributeTypeResolutionFailure(resolution)
                }
                DotNetClrResolvedSerializedType.Named(primitiveType)
            }

            is DotNetClrResolvedTypeSignature.GenericInstance -> {
                val arguments =
                    ArrayList<DotNetClrResolvedSerializedType>(signature.arguments.size)
                for (argument in signature.arguments) {
                    arguments += resolvedSignatureToSerializedType(argument) ?: return null
                }
                DotNetClrResolvedSerializedType.GenericInstance(
                    DotNetClrResolvedSerializedType.Named(signature.genericType.type),
                    arguments.toList(),
                )
            }

            is DotNetClrResolvedTypeSignature.Pointer ->
                resolvedSignatureToSerializedType(signature.elementType)?.let { element ->
                    DotNetClrResolvedSerializedType.Pointer(element)
                }

            is DotNetClrResolvedTypeSignature.ByReference ->
                resolvedSignatureToSerializedType(signature.elementType)?.let { element ->
                    DotNetClrResolvedSerializedType.ByReference(element)
                }

            is DotNetClrResolvedTypeSignature.SzArray ->
                resolvedSignatureToSerializedType(signature.elementType)?.let { element ->
                    DotNetClrResolvedSerializedType.SzArray(element)
                }

            is DotNetClrResolvedTypeSignature.Array -> {
                if (signature.shape.sizes.isNotEmpty() ||
                    signature.shape.lowerBounds.isNotEmpty()
                ) {
                    null
                } else {
                    resolvedSignatureToSerializedType(signature.elementType)?.let { element ->
                        DotNetClrResolvedSerializedType.MdArray(
                            element,
                            signature.shape.rank,
                        )
                    }
                }
            }

            is DotNetClrResolvedTypeSignature.Modified ->
                resolvedSignatureToSerializedType(signature.unmodifiedType)

            DotNetClrResolvedTypeSignature.Void,
            DotNetClrResolvedTypeSignature.TypedReference,
            is DotNetClrResolvedTypeSignature.GenericParameter,
            is DotNetClrResolvedTypeSignature.FunctionPointer,
            -> null
        }

    private fun decodeArgument(
        assembly: DotNetClrAssemblyMetadata,
        reader: CustomAttributeBlobReader,
        type: DotNetClrCustomAttributeValueType,
        nestingDepth: Int,
    ): DotNetClrCustomAttributeValue =
        when (type) {
            is DotNetClrCustomAttributeValueType.Primitive ->
                decodePrimitiveArgument(reader, type.type)

            DotNetClrCustomAttributeValueType.TaggedObject -> {
                if (nestingDepth >= MAX_VALUE_NESTING_DEPTH) {
                    reader.fail(DotNetClrCustomAttributeValueFailure.VALUE_LIMIT_EXCEEDED)
                }
                val taggedType = readSerializedArgumentType(assembly, reader)
                decodeArgument(assembly, reader, taggedType, nestingDepth + 1)
            }

            is DotNetClrCustomAttributeValueType.SzArray ->
                decodeArrayArgument(assembly, reader, type, nestingDepth)

            DotNetClrCustomAttributeValueType.SystemType ->
                decodeSystemTypeArgument(assembly, reader)

            is DotNetClrCustomAttributeValueType.EnumType ->
                decodeEnumArgument(reader, type)
        }

    private fun decodePrimitiveArgument(
        reader: CustomAttributeBlobReader,
        primitive: DotNetClrPrimitiveType,
    ): DotNetClrCustomAttributeValue =
        when (primitive) {
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
            -> error("Unsupported custom-attribute primitive reached value decoding: $primitive")
        }

    private fun readSerializedArgumentType(
        assembly: DotNetClrAssemblyMetadata,
        reader: CustomAttributeBlobReader,
        isArrayElement: Boolean = false,
    ): DotNetClrCustomAttributeValueType {
        val code = reader.readUnsigned(1).toInt()
        val primitive = SERIALIZATION_PRIMITIVE_TYPES[code]
        if (primitive != null) return DotNetClrCustomAttributeValueType.Primitive(primitive)
        return when (code) {
            SERIALIZATION_TYPE_TAGGED_OBJECT ->
                DotNetClrCustomAttributeValueType.TaggedObject

            SERIALIZATION_TYPE_SZARRAY -> {
                if (isArrayElement) {
                    reader.fail(
                        DotNetClrCustomAttributeValueFailure.INVALID_SERIALIZATION_TYPE_CODE
                    )
                }
                DotNetClrCustomAttributeValueType.SzArray(
                    readSerializedArgumentType(
                        assembly,
                        reader,
                        isArrayElement = true,
                    )
                )
            }

            SERIALIZATION_TYPE_SYSTEM_TYPE ->
                DotNetClrCustomAttributeValueType.SystemType

            SERIALIZATION_TYPE_ENUM ->
                serializedEnumType(
                    assembly,
                    reader.readSerializedString()
                        ?: throw CustomAttributeEnumFailure(),
                )

            else -> reader.fail(
                DotNetClrCustomAttributeValueFailure.INVALID_SERIALIZATION_TYPE_CODE
            )
        }
    }

    private fun decodeNamedArgument(
        assembly: DotNetClrAssemblyMetadata,
        reader: CustomAttributeBlobReader,
    ): DotNetClrCustomAttributeNamedArgument {
        val kind = when (reader.readUnsigned(1).toInt()) {
            SERIALIZATION_NAMED_ARGUMENT_FIELD ->
                DotNetClrCustomAttributeNamedArgumentKind.FIELD

            SERIALIZATION_NAMED_ARGUMENT_PROPERTY ->
                DotNetClrCustomAttributeNamedArgumentKind.PROPERTY

            else ->
                reader.fail(DotNetClrCustomAttributeValueFailure.INVALID_NAMED_ARGUMENT_KIND)
        }
        val type = readSerializedArgumentType(assembly, reader)
        val name = reader.readSerializedString()
        if (name.isNullOrEmpty()) {
            reader.fail(DotNetClrCustomAttributeValueFailure.INVALID_NAMED_ARGUMENT_NAME)
        }
        return DotNetClrCustomAttributeNamedArgument(
            kind,
            name,
            type,
            decodeArgument(assembly, reader, type, nestingDepth = 0),
        )
    }

    private fun decodeArrayArgument(
        assembly: DotNetClrAssemblyMetadata,
        reader: CustomAttributeBlobReader,
        type: DotNetClrCustomAttributeValueType.SzArray,
        nestingDepth: Int,
    ): DotNetClrCustomAttributeValue.ArrayValue {
        val encodedCount = reader.readUnsigned(4)
        if (encodedCount == NULL_ARRAY_LENGTH) {
            return DotNetClrCustomAttributeValue.ArrayValue(type, null)
        }
        if (encodedCount > Int.MAX_VALUE.toULong()) {
            reader.fail(DotNetClrCustomAttributeValueFailure.INVALID_ARRAY_LENGTH)
        }
        val count = encodedCount.toInt()
        if (count > MAX_ARRAY_ELEMENT_COUNT) {
            reader.fail(DotNetClrCustomAttributeValueFailure.VALUE_LIMIT_EXCEEDED)
        }
        val minimumElementSize = minimumEncodedSize(type.elementType)
        if (count > reader.remainingByteCount / minimumElementSize) {
            reader.fail(DotNetClrCustomAttributeValueFailure.TRUNCATED_VALUE)
        }
        val elements = ArrayList<DotNetClrCustomAttributeValue>(count)
        repeat(count) {
            elements += decodeArgument(
                assembly,
                reader,
                type.elementType,
                nestingDepth,
            )
        }
        return DotNetClrCustomAttributeValue.ArrayValue(type, elements.toList())
    }

    private fun decodeSystemTypeArgument(
        assembly: DotNetClrAssemblyMetadata,
        reader: CustomAttributeBlobReader,
    ): DotNetClrCustomAttributeValue.TypeValue {
        val serializedName = reader.readSerializedString()
            ?: return DotNetClrCustomAttributeValue.TypeValue(null)
        val type = when (
            val resolution = serializedTypeResolver.resolve(assembly, serializedName)
        ) {
            is DotNetClrSerializedTypeResolution.Resolved -> resolution.type
            else -> throw CustomAttributeSerializedTypeResolutionFailure(resolution)
        }
        return DotNetClrCustomAttributeValue.TypeValue(type)
    }

    private fun serializedEnumType(
        assembly: DotNetClrAssemblyMetadata,
        serializedName: String,
    ): DotNetClrCustomAttributeValueType.EnumType {
        val resolvedType = when (
            val resolution = serializedTypeResolver.resolve(assembly, serializedName)
        ) {
            is DotNetClrSerializedTypeResolution.Resolved -> resolution.type

            else -> throw CustomAttributeSerializedTypeResolutionFailure(resolution)
        }
        val enumDefinition = when (resolvedType) {
            is DotNetClrResolvedSerializedType.Named -> resolvedType.type
            is DotNetClrResolvedSerializedType.GenericInstance ->
                resolvedType.genericType.type

            is DotNetClrResolvedSerializedType.Pointer,
            is DotNetClrResolvedSerializedType.ByReference,
            is DotNetClrResolvedSerializedType.SzArray,
            is DotNetClrResolvedSerializedType.MdArray,
            -> throw CustomAttributeEnumFailure()
        }
        return when (
            val enumStorage =
                typeResolver.resolveEnumStorage(enumDefinition, coreTypes.systemEnum)
        ) {
            is DotNetClrEnumStorageResolution.Resolved ->
                DotNetClrCustomAttributeValueType.EnumType(
                    resolvedType,
                    enumStorage.storageType,
                )

            DotNetClrEnumStorageResolution.NotEnum ->
                throw CustomAttributeEnumFailure()

            is DotNetClrEnumStorageResolution.UnresolvedBaseType ->
                throw CustomAttributeTypeResolutionFailure(enumStorage.resolution)

            is DotNetClrEnumStorageResolution.Invalid ->
                throw CustomAttributeEnumFailure(enumStorage.failure)
        }
    }

    private fun decodeEnumArgument(
        reader: CustomAttributeBlobReader,
        type: DotNetClrCustomAttributeValueType.EnumType,
    ): DotNetClrCustomAttributeValue.EnumValue {
        val byteCount = customAttributeIntegralBitWidth(type.storageType) / 8
        return DotNetClrCustomAttributeValue.EnumValue(
            type,
            integralValue(type.storageType, reader, byteCount),
        )
    }

    private fun minimumEncodedSize(type: DotNetClrCustomAttributeValueType): Int =
        when (type) {
            is DotNetClrCustomAttributeValueType.Primitive -> {
                when (type.type) {
                    DotNetClrPrimitiveType.INT64,
                    DotNetClrPrimitiveType.UINT64,
                    DotNetClrPrimitiveType.FLOAT64,
                    -> 8

                    DotNetClrPrimitiveType.INT32,
                    DotNetClrPrimitiveType.UINT32,
                    DotNetClrPrimitiveType.FLOAT32,
                    -> 4

                    DotNetClrPrimitiveType.CHAR,
                    DotNetClrPrimitiveType.INT16,
                    DotNetClrPrimitiveType.UINT16,
                    -> 2

                    DotNetClrPrimitiveType.BOOLEAN,
                    DotNetClrPrimitiveType.INT8,
                    DotNetClrPrimitiveType.UINT8,
                    DotNetClrPrimitiveType.STRING,
                    -> 1

                    DotNetClrPrimitiveType.NATIVE_INT,
                    DotNetClrPrimitiveType.NATIVE_UINT,
                    DotNetClrPrimitiveType.OBJECT,
                    -> error("Unsupported custom-attribute primitive has no encoded size")
                }
            }

            DotNetClrCustomAttributeValueType.TaggedObject -> 2
            DotNetClrCustomAttributeValueType.SystemType -> 1
            is DotNetClrCustomAttributeValueType.EnumType ->
                customAttributeIntegralBitWidth(type.storageType) / 8
            is DotNetClrCustomAttributeValueType.SzArray -> 4
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
        namedArguments: List<DotNetClrCustomAttributeNamedArgument>,
    ): DotNetClrCustomAttributeValueDecoding.Decoded =
        DotNetClrCustomAttributeValueDecoding.Decoded(
            DotNetClrDecodedCustomAttribute(
                constructor,
                fixedArguments.toList(),
                namedArguments.toList(),
            )
        )

    private fun invalidValue(
        failure: DotNetClrCustomAttributeValueFailure,
        fixedArgumentIndex: Int? = null,
        namedArgumentIndex: Int? = null,
        typeResolution: DotNetClrTypeResolution.Unresolved? = null,
        serializedTypeResolution: DotNetClrSerializedTypeResolution? = null,
        enumStorageFailure: DotNetClrEnumStorageFailure? = null,
    ): DotNetClrCustomAttributeValueDecoding.Invalid =
        DotNetClrCustomAttributeValueDecoding.Invalid(
            failure,
            fixedArgumentIndex,
            namedArgumentIndex,
            typeResolution,
            serializedTypeResolution,
            enumStorageFailure,
        )

    private fun unsupportedValue(
        unsupported: DotNetClrCustomAttributeValueUnsupported,
        fixedArgumentIndex: Int? = null,
    ): DotNetClrCustomAttributeValueDecoding.Unsupported =
        DotNetClrCustomAttributeValueDecoding.Unsupported(unsupported, fixedArgumentIndex)

    private fun resolveAttributeTypeView(
        assembly: DotNetClrAssemblyMetadata,
        handle: DotNetClrMetadataHandle,
    ): CustomAttributeTypeViewResolution =
        when (handle.table) {
            TYPE_DEF_TABLE,
            TYPE_REF_TABLE,
            -> {
                when (
                    val resolution = typeResolver.resolveTypeDefinition(assembly, handle)
                ) {
                    is DotNetClrTypeResolution.Resolved ->
                        attributeTypeView(resolution.type, emptyList())

                    is DotNetClrTypeResolution.Unresolved ->
                        CustomAttributeTypeViewResolution.Invalid(
                            DotNetClrCustomAttributeConstructorFailure
                                .ATTRIBUTE_TYPE_RESOLUTION_FAILED,
                            typeResolution = resolution,
                        )
                }
            }

            TYPE_SPEC_TABLE -> {
                val specification = assembly.typeSpecifications.singleOrNull { candidate ->
                    candidate.handle == handle
                }
                if (specification == null) {
                    when (
                        val resolution = typeResolver.resolveTypeDefinition(assembly, handle)
                    ) {
                        is DotNetClrTypeResolution.Resolved ->
                            CustomAttributeTypeViewResolution.Invalid(
                                DotNetClrCustomAttributeConstructorFailure
                                    .INVALID_ATTRIBUTE_TYPE_SIGNATURE
                            )

                        is DotNetClrTypeResolution.Unresolved ->
                            CustomAttributeTypeViewResolution.Invalid(
                                DotNetClrCustomAttributeConstructorFailure
                                    .ATTRIBUTE_TYPE_RESOLUTION_FAILED,
                                typeResolution = resolution,
                            )
                    }
                } else {
                    when (
                        val resolution = signatureResolver.resolve(
                            assembly,
                            specification.signature,
                        )
                    ) {
                        is DotNetClrResolvedSignatureResolution.Resolved -> {
                            val signature = resolution.signature
                            if (signature !is DotNetClrResolvedTypeSignature.GenericInstance ||
                                signature.genericType.isValueType
                            ) {
                                CustomAttributeTypeViewResolution.Invalid(
                                    DotNetClrCustomAttributeConstructorFailure
                                        .INVALID_ATTRIBUTE_TYPE_SIGNATURE
                                )
                            } else {
                                attributeTypeView(
                                    signature.genericType.type,
                                    signature.arguments,
                                )
                            }
                        }

                        is DotNetClrResolvedSignatureResolution.UnresolvedType ->
                            CustomAttributeTypeViewResolution.Invalid(
                                DotNetClrCustomAttributeConstructorFailure
                                    .ATTRIBUTE_TYPE_RESOLUTION_FAILED,
                                typeResolution = resolution.resolution,
                            )

                        is DotNetClrResolvedSignatureResolution.Invalid ->
                            CustomAttributeTypeViewResolution.Invalid(
                                DotNetClrCustomAttributeConstructorFailure
                                    .INVALID_ATTRIBUTE_TYPE_SIGNATURE,
                                signatureResolution = resolution,
                            )
                    }
                }
            }

            else ->
                CustomAttributeTypeViewResolution.Invalid(
                    DotNetClrCustomAttributeConstructorFailure
                        .UNSUPPORTED_MEMBER_REFERENCE_PARENT
                )
        }

    private fun attributeTypeView(
        type: DotNetClrResolvedTypeDefinition,
        arguments: List<DotNetClrResolvedTypeSignature>,
    ): CustomAttributeTypeViewResolution {
        val genericArity = type.assembly.genericParameterDefinitions.count { parameter ->
            parameter.owner == type.definition.handle
        }
        if (arguments.size != genericArity ||
            arguments.any(DotNetClrResolvedTypeSignature::containsGenericParameter)
        ) {
            return CustomAttributeTypeViewResolution.Invalid(
                DotNetClrCustomAttributeConstructorFailure.ATTRIBUTE_TYPE_IS_OPEN_GENERIC
            )
        }
        return CustomAttributeTypeViewResolution.Resolved(
            DotNetClrResolvedTypeView(type, arguments.toList())
        )
    }

    private fun invalid(
        failure: DotNetClrCustomAttributeConstructorFailure,
        typeResolution: DotNetClrTypeResolution.Unresolved? = null,
        signatureResolution: DotNetClrResolvedSignatureResolution.Invalid? = null,
        signatureSubstitution: DotNetClrResolvedSignatureSubstitution.Invalid? = null,
    ): DotNetClrCustomAttributeConstructorResolution.Invalid =
        DotNetClrCustomAttributeConstructorResolution.Invalid(
            failure,
            typeResolution,
            signatureResolution,
            signatureSubstitution,
        )

    private companion object {
        const val TYPE_REF_TABLE = 1
        const val TYPE_DEF_TABLE = 2
        const val METHOD_DEF_TABLE = 6
        const val MEMBER_REF_TABLE = 10
        const val TYPE_SPEC_TABLE = 27
        const val CUSTOM_ATTRIBUTE_PROLOG = 1
        const val SERIALIZATION_TYPE_SZARRAY = 0x1d
        const val SERIALIZATION_TYPE_SYSTEM_TYPE = 0x50
        const val SERIALIZATION_TYPE_TAGGED_OBJECT = 0x51
        const val SERIALIZATION_NAMED_ARGUMENT_FIELD = 0x53
        const val SERIALIZATION_NAMED_ARGUMENT_PROPERTY = 0x54
        const val SERIALIZATION_TYPE_ENUM = 0x55
        const val MAX_VALUE_NESTING_DEPTH = 32
        const val MAX_ARRAY_ELEMENT_COUNT = 1_000_000
        val NULL_ARRAY_LENGTH = UInt.MAX_VALUE.toULong()
        val SERIALIZATION_PRIMITIVE_TYPES = mapOf(
            0x02 to DotNetClrPrimitiveType.BOOLEAN,
            0x03 to DotNetClrPrimitiveType.CHAR,
            0x04 to DotNetClrPrimitiveType.INT8,
            0x05 to DotNetClrPrimitiveType.UINT8,
            0x06 to DotNetClrPrimitiveType.INT16,
            0x07 to DotNetClrPrimitiveType.UINT16,
            0x08 to DotNetClrPrimitiveType.INT32,
            0x09 to DotNetClrPrimitiveType.UINT32,
            0x0a to DotNetClrPrimitiveType.INT64,
            0x0b to DotNetClrPrimitiveType.UINT64,
            0x0c to DotNetClrPrimitiveType.FLOAT32,
            0x0d to DotNetClrPrimitiveType.FLOAT64,
            0x0e to DotNetClrPrimitiveType.STRING,
        )
    }
}

private sealed interface CustomAttributeTypeViewResolution {
    data class Resolved(
        val view: DotNetClrResolvedTypeView,
    ) : CustomAttributeTypeViewResolution

    data class Invalid(
        val failure: DotNetClrCustomAttributeConstructorFailure,
        val typeResolution: DotNetClrTypeResolution.Unresolved? = null,
        val signatureResolution: DotNetClrResolvedSignatureResolution.Invalid? = null,
    ) : CustomAttributeTypeViewResolution
}

private fun DotNetClrResolvedTypeSignature.containsGenericParameter(): Boolean =
    when (this) {
        is DotNetClrResolvedTypeSignature.GenericParameter -> true
        is DotNetClrResolvedTypeSignature.Pointer -> elementType.containsGenericParameter()
        is DotNetClrResolvedTypeSignature.ByReference -> elementType.containsGenericParameter()
        is DotNetClrResolvedTypeSignature.SzArray -> elementType.containsGenericParameter()
        is DotNetClrResolvedTypeSignature.Array -> elementType.containsGenericParameter()
        is DotNetClrResolvedTypeSignature.GenericInstance ->
            arguments.any(DotNetClrResolvedTypeSignature::containsGenericParameter)

        is DotNetClrResolvedTypeSignature.FunctionPointer ->
            signature.returnType.containsGenericParameter() ||
                    signature.parameterTypes.any(
                        DotNetClrResolvedTypeSignature::containsGenericParameter
                    )

        is DotNetClrResolvedTypeSignature.Modified ->
            unmodifiedType.containsGenericParameter()

        DotNetClrResolvedTypeSignature.Void,
        DotNetClrResolvedTypeSignature.TypedReference,
        is DotNetClrResolvedTypeSignature.Primitive,
        is DotNetClrResolvedTypeSignature.Named,
        -> false
    }

private class CustomAttributeBlobReader(
    private val bytes: ByteArray,
) {
    private var offset = 0

    val isAtEnd: Boolean
        get() = offset == bytes.size

    val remainingByteCount: Int
        get() = bytes.size - offset

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

private class CustomAttributeTypeResolutionFailure(
    val resolution: DotNetClrTypeResolution.Unresolved,
) : Exception()

private class CustomAttributeSerializedTypeResolutionFailure(
    val resolution: DotNetClrSerializedTypeResolution,
) : Exception()

private class CustomAttributeEnumFailure(
    val failure: DotNetClrEnumStorageFailure? = null,
) : Exception()

private fun customAttributeIntegralBitWidth(type: DotNetClrPrimitiveType): Int =
    when (type) {
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
