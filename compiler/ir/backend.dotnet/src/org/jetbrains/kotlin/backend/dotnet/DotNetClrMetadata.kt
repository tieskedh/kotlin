package org.jetbrains.kotlin.backend.dotnet

/**
 * Raw ECMA-335 metadata handle. It identifies one physical row and deliberately carries no
 * Kotlin or C# source meaning.
 */
data class DotNetClrMetadataHandle(
    val table: Int,
    val row: Int,
) {
    init {
        require(table in 0 until 64) { "CLR metadata table must be in 0..63: $table" }
        require(row in 1..0x00ff_ffff) { "CLR metadata row must be in 1..0x00ffffff: $row" }
    }

    val token: Int
        get() = table shl 24 or row
}

enum class DotNetClrTypeVisibility {
    NOT_PUBLIC,
    PUBLIC,
    NESTED_PUBLIC,
    NESTED_PRIVATE,
    NESTED_FAMILY,
    NESTED_ASSEMBLY,
    NESTED_FAMILY_AND_ASSEMBLY,
    NESTED_FAMILY_OR_ASSEMBLY,
}

data class DotNetClrAssemblyReference(
    val handle: DotNetClrMetadataHandle,
    val name: String,
    val version: String,
    val culture: String,
    val flags: Long,
    val publicKeyOrToken: List<Int>,
    val hashValue: List<Int>,
)

data class DotNetClrTypeReference(
    val handle: DotNetClrMetadataHandle,
    val namespaceName: String,
    val metadataName: String,
    val resolutionScope: DotNetClrMetadataHandle?,
)

data class DotNetClrTypeDefinition(
    val handle: DotNetClrMetadataHandle,
    val namespaceName: String,
    val metadataName: String,
    val attributes: Long,
    val baseType: DotNetClrMetadataHandle?,
    val declaringType: DotNetClrMetadataHandle?,
) {
    val visibility: DotNetClrTypeVisibility
        get() = DotNetClrTypeVisibility.entries[(attributes and TYPE_VISIBILITY_MASK).toInt()]

    val isInterface: Boolean
        get() = attributes and INTERFACE_ATTRIBUTE != 0L

    val isAbstract: Boolean
        get() = attributes and ABSTRACT_ATTRIBUTE != 0L

    val isSealed: Boolean
        get() = attributes and SEALED_ATTRIBUTE != 0L

    private companion object {
        const val TYPE_VISIBILITY_MASK = 0x7L
        const val INTERFACE_ATTRIBUTE = 0x20L
        const val ABSTRACT_ATTRIBUTE = 0x80L
        const val SEALED_ATTRIBUTE = 0x100L
    }
}

/**
 * One physical InterfaceImpl row.
 *
 * [interfaceType] remains its exact TypeDefOrRef handle. In particular, a TypeSpec handle retains
 * the implemented generic instantiation instead of erasing it to the interface TypeDef.
 */
data class DotNetClrInterfaceImplementation(
    val handle: DotNetClrMetadataHandle,
    val implementingType: DotNetClrMetadataHandle,
    val interfaceType: DotNetClrMetadataHandle,
)

/**
 * Physical ExportedType row. [typeDefinitionId] is only the ECMA-335 hint into another module's
 * TypeDef table; it is not a metadata token in this PE image.
 */
data class DotNetClrExportedType(
    val handle: DotNetClrMetadataHandle,
    val attributes: Long,
    val typeDefinitionId: Long,
    val namespaceName: String,
    val metadataName: String,
    val implementation: DotNetClrMetadataHandle,
) {
    val visibility: DotNetClrTypeVisibility
        get() = DotNetClrTypeVisibility.entries[(attributes and TYPE_VISIBILITY_MASK).toInt()]

    val isForwarder: Boolean
        get() = attributes and FORWARDER_ATTRIBUTE != 0L

    private companion object {
        const val TYPE_VISIBILITY_MASK = 0x7L
        const val FORWARDER_ATTRIBUTE = 0x0020_0000L
    }
}

enum class DotNetClrPrimitiveType {
    BOOLEAN,
    CHAR,
    INT8,
    UINT8,
    INT16,
    UINT16,
    INT32,
    UINT32,
    INT64,
    UINT64,
    FLOAT32,
    FLOAT64,
    STRING,
    NATIVE_INT,
    NATIVE_UINT,
    OBJECT,
}

enum class DotNetClrGenericParameterKind {
    TYPE,
    METHOD,
}

data class DotNetClrCustomModifier(
    val isRequired: Boolean,
    val modifierType: DotNetClrMetadataHandle,
)

data class DotNetClrArrayShape(
    val rank: Int,
    val sizes: List<Int>,
    val lowerBounds: List<Int>,
)

/**
 * Lossless physical type signature. This is the ECMA-335 signature grammar, not a Kotlin type and
 * not an IL or C# display string.
 */
sealed interface DotNetClrTypeSignature {
    data object Void : DotNetClrTypeSignature

    data object TypedReference : DotNetClrTypeSignature

    data class Primitive(
        val type: DotNetClrPrimitiveType,
    ) : DotNetClrTypeSignature

    data class Named(
        val type: DotNetClrMetadataHandle,
        val isValueType: Boolean,
    ) : DotNetClrTypeSignature

    data class GenericParameter(
        val kind: DotNetClrGenericParameterKind,
        val index: Int,
    ) : DotNetClrTypeSignature

    data class Pointer(
        val elementType: DotNetClrTypeSignature,
    ) : DotNetClrTypeSignature

    data class ByReference(
        val elementType: DotNetClrTypeSignature,
    ) : DotNetClrTypeSignature

    data class SzArray(
        val elementType: DotNetClrTypeSignature,
    ) : DotNetClrTypeSignature

    data class Array(
        val elementType: DotNetClrTypeSignature,
        val shape: DotNetClrArrayShape,
    ) : DotNetClrTypeSignature

    data class GenericInstance(
        val genericType: Named,
        val arguments: List<DotNetClrTypeSignature>,
    ) : DotNetClrTypeSignature

    data class FunctionPointer(
        val signature: DotNetClrMethodSignature,
    ) : DotNetClrTypeSignature

    data class Modified(
        val modifiers: List<DotNetClrCustomModifier>,
        val unmodifiedType: DotNetClrTypeSignature,
    ) : DotNetClrTypeSignature
}

enum class DotNetClrSignatureCallingConvention {
    DEFAULT,
    C,
    STDCALL,
    THISCALL,
    FASTCALL,
    VARARG,
    UNMANAGED,
    NATIVE_VARARG,
}

data class DotNetClrMethodSignature(
    val callingConvention: DotNetClrSignatureCallingConvention,
    val hasThis: Boolean,
    val hasExplicitThis: Boolean,
    val genericParameterCount: Int,
    val returnType: DotNetClrTypeSignature,
    val parameterTypes: List<DotNetClrTypeSignature>,
    val varargParameterStart: Int?,
)

data class DotNetClrFieldSignature(
    val fieldType: DotNetClrTypeSignature,
)

enum class DotNetClrFieldVisibility {
    COMPILER_CONTROLLED,
    PRIVATE,
    FAMILY_AND_ASSEMBLY,
    ASSEMBLY,
    FAMILY,
    FAMILY_OR_ASSEMBLY,
    PUBLIC,
}

data class DotNetClrFieldDefinition(
    val handle: DotNetClrMetadataHandle,
    val declaringType: DotNetClrMetadataHandle,
    val name: String,
    val attributes: Int,
    val signature: DotNetClrFieldSignature,
    val rawSignature: List<Int>,
) {
    val visibility: DotNetClrFieldVisibility
        get() = DotNetClrFieldVisibility.entries[attributes and FIELD_ACCESS_MASK]

    val isStatic: Boolean
        get() = attributes and STATIC_ATTRIBUTE != 0

    val isInitOnly: Boolean
        get() = attributes and INIT_ONLY_ATTRIBUTE != 0

    val isLiteral: Boolean
        get() = attributes and LITERAL_ATTRIBUTE != 0

    val isSpecialName: Boolean
        get() = attributes and SPECIAL_NAME_ATTRIBUTE != 0

    val isRuntimeSpecialName: Boolean
        get() = attributes and RUNTIME_SPECIAL_NAME_ATTRIBUTE != 0

    val hasDefault: Boolean
        get() = attributes and HAS_DEFAULT_ATTRIBUTE != 0

    private companion object {
        const val FIELD_ACCESS_MASK = 0x0007
        const val STATIC_ATTRIBUTE = 0x0010
        const val INIT_ONLY_ATTRIBUTE = 0x0020
        const val LITERAL_ATTRIBUTE = 0x0040
        const val SPECIAL_NAME_ATTRIBUTE = 0x0200
        const val RUNTIME_SPECIAL_NAME_ATTRIBUTE = 0x0400
        const val HAS_DEFAULT_ATTRIBUTE = 0x8000
    }
}

sealed interface DotNetClrMemberReferenceSignature {
    data class Method(
        val signature: DotNetClrMethodSignature,
    ) : DotNetClrMemberReferenceSignature

    data class Field(
        val signature: DotNetClrFieldSignature,
    ) : DotNetClrMemberReferenceSignature
}

data class DotNetClrMemberReference(
    val handle: DotNetClrMetadataHandle,
    val parent: DotNetClrMetadataHandle,
    val name: String,
    val signature: DotNetClrMemberReferenceSignature,
    val rawSignature: List<Int>,
)

class DotNetClrBlob private constructor(
    private val content: ByteArray,
) {
    val size: Int
        get() = content.size

    fun toByteArray(): ByteArray = content.copyOf()

    fun toUnsignedIntList(): List<Int> =
        content.map { byte -> byte.toInt() and 0xff }

    override fun equals(other: Any?): Boolean =
        other is DotNetClrBlob && content.contentEquals(other.content)

    override fun hashCode(): Int = content.contentHashCode()

    companion object {
        fun copyOf(bytes: ByteArray): DotNetClrBlob =
            DotNetClrBlob(bytes.copyOf())

        internal fun wrapOwned(bytes: ByteArray): DotNetClrBlob =
            DotNetClrBlob(bytes)
    }
}

data class DotNetClrCustomAttribute(
    val handle: DotNetClrMetadataHandle,
    val parent: DotNetClrMetadataHandle,
    val constructor: DotNetClrMetadataHandle,
    /**
     * Null is a nil Value index. An empty list is a present zero-length blob; ECMA-335 permits
     * both in specific constructor/member shapes, so the physical layer must distinguish them.
     */
    val rawValue: DotNetClrBlob?,
)

enum class DotNetClrMethodVisibility {
    COMPILER_CONTROLLED,
    PRIVATE,
    FAMILY_AND_ASSEMBLY,
    ASSEMBLY,
    FAMILY,
    FAMILY_OR_ASSEMBLY,
    PUBLIC,
}

data class DotNetClrMethodDefinition(
    val handle: DotNetClrMetadataHandle,
    val declaringType: DotNetClrMetadataHandle,
    val name: String,
    val relativeVirtualAddress: Long,
    val implementationAttributes: Int,
    val attributes: Int,
    val signature: DotNetClrMethodSignature,
    val rawSignature: List<Int>,
) {
    val visibility: DotNetClrMethodVisibility
        get() = DotNetClrMethodVisibility.entries[attributes and METHOD_ACCESS_MASK]

    val isStatic: Boolean
        get() = attributes and STATIC_ATTRIBUTE != 0

    val isFinal: Boolean
        get() = attributes and FINAL_ATTRIBUTE != 0

    val isVirtual: Boolean
        get() = attributes and VIRTUAL_ATTRIBUTE != 0

    val isAbstract: Boolean
        get() = attributes and ABSTRACT_ATTRIBUTE != 0

    val isSpecialName: Boolean
        get() = attributes and SPECIAL_NAME_ATTRIBUTE != 0

    val isRuntimeSpecialName: Boolean
        get() = attributes and RUNTIME_SPECIAL_NAME_ATTRIBUTE != 0

    private companion object {
        const val METHOD_ACCESS_MASK = 0x7
        const val STATIC_ATTRIBUTE = 0x10
        const val FINAL_ATTRIBUTE = 0x20
        const val VIRTUAL_ATTRIBUTE = 0x40
        const val ABSTRACT_ATTRIBUTE = 0x400
        const val SPECIAL_NAME_ATTRIBUTE = 0x800
        const val RUNTIME_SPECIAL_NAME_ATTRIBUTE = 0x1000
    }
}

/**
 * One physical Param-table row owned by [declaringMethod].
 *
 * Param rows are optional CLR metadata. A method's signature remains authoritative for its
 * parameter count and types; this row preserves names, flags, custom-attribute attachment, and
 * other metadata for the return value ([sequence] 0) or a value parameter ([sequence] 1 onwards).
 */
data class DotNetClrParameterDefinition(
    val handle: DotNetClrMetadataHandle,
    val declaringMethod: DotNetClrMetadataHandle,
    val sequence: Int,
    val name: String?,
    val attributes: Int,
) {
    val isReturn: Boolean
        get() = sequence == 0

    val parameterIndex: Int?
        get() = if (isReturn) null else sequence - 1

    val isIn: Boolean
        get() = attributes and IN_ATTRIBUTE != 0

    val isOut: Boolean
        get() = attributes and OUT_ATTRIBUTE != 0

    val isOptional: Boolean
        get() = attributes and OPTIONAL_ATTRIBUTE != 0

    val hasDefault: Boolean
        get() = attributes and HAS_DEFAULT_ATTRIBUTE != 0

    val hasFieldMarshal: Boolean
        get() = attributes and HAS_FIELD_MARSHAL_ATTRIBUTE != 0

    private companion object {
        const val IN_ATTRIBUTE = 0x0001
        const val OUT_ATTRIBUTE = 0x0002
        const val OPTIONAL_ATTRIBUTE = 0x0010
        const val HAS_DEFAULT_ATTRIBUTE = 0x1000
        const val HAS_FIELD_MARSHAL_ATTRIBUTE = 0x2000
    }
}

/**
 * A semantic view of one physical Constant-table value.
 *
 * Integral and floating variants retain exact bits rather than a host-language numeric
 * approximation. The owning [DotNetClrConstantDefinition] separately retains the entire raw
 * blob, including bytes beyond the scalar prefix consumed by the CLR metadata reader.
 */
sealed interface DotNetClrConstantValue {
    data class BooleanValue(
        val value: Boolean,
    ) : DotNetClrConstantValue

    data class CharValue(
        val value: Char,
    ) : DotNetClrConstantValue

    data class IntegralValue(
        val type: DotNetClrPrimitiveType,
        val bits: ULong,
    ) : DotNetClrConstantValue {
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

                else -> error("Constant integral value cannot have type $type")
            }
            require(bitWidth == 64 || bits < (1uL shl bitWidth)) {
                "Constant $type value does not fit in $bitWidth bits"
            }
        }
    }

    data class Float32Value(
        val bits: Int,
    ) : DotNetClrConstantValue

    data class Float64Value(
        val bits: Long,
    ) : DotNetClrConstantValue

    data class StringValue(
        val value: String,
    ) : DotNetClrConstantValue

    data object NullReference : DotNetClrConstantValue
}

data class DotNetClrConstantDefinition(
    val handle: DotNetClrMetadataHandle,
    val parent: DotNetClrMetadataHandle,
    val value: DotNetClrConstantValue,
    val rawValue: DotNetClrBlob,
)

data class DotNetClrPropertySignature(
    val hasThis: Boolean,
    val propertyType: DotNetClrTypeSignature,
    val indexParameterTypes: List<DotNetClrTypeSignature>,
)

data class DotNetClrPropertyDefinition(
    val handle: DotNetClrMetadataHandle,
    val declaringType: DotNetClrMetadataHandle,
    val name: String,
    val attributes: Int,
    val signature: DotNetClrPropertySignature,
    val rawSignature: List<Int>,
) {
    val isSpecialName: Boolean
        get() = attributes and SPECIAL_NAME_ATTRIBUTE != 0

    val hasDefault: Boolean
        get() = attributes and HAS_DEFAULT_ATTRIBUTE != 0

    private companion object {
        const val SPECIAL_NAME_ATTRIBUTE = 0x200
        const val HAS_DEFAULT_ATTRIBUTE = 0x1000
    }
}

enum class DotNetClrMethodSemanticsKind {
    SETTER,
    GETTER,
    OTHER,
    ADD_ON,
    REMOVE_ON,
    FIRE,
}

data class DotNetClrMethodSemantics(
    val handle: DotNetClrMetadataHandle,
    val kind: DotNetClrMethodSemanticsKind,
    val method: DotNetClrMetadataHandle,
    val association: DotNetClrMetadataHandle,
)

enum class DotNetClrGenericParameterVariance {
    INVARIANT,
    COVARIANT,
    CONTRAVARIANT,
}

data class DotNetClrGenericParameterDefinition(
    val handle: DotNetClrMetadataHandle,
    val number: Int,
    val attributes: Int,
    val owner: DotNetClrMetadataHandle,
    val name: String,
) {
    val variance: DotNetClrGenericParameterVariance
        get() = when (attributes and VARIANCE_MASK) {
            COVARIANT_ATTRIBUTE -> DotNetClrGenericParameterVariance.COVARIANT
            CONTRAVARIANT_ATTRIBUTE -> DotNetClrGenericParameterVariance.CONTRAVARIANT
            else -> DotNetClrGenericParameterVariance.INVARIANT
        }

    val hasReferenceTypeConstraint: Boolean
        get() = attributes and REFERENCE_TYPE_CONSTRAINT_ATTRIBUTE != 0

    val hasNotNullableValueTypeConstraint: Boolean
        get() = attributes and NOT_NULLABLE_VALUE_TYPE_CONSTRAINT_ATTRIBUTE != 0

    val hasDefaultConstructorConstraint: Boolean
        get() = attributes and DEFAULT_CONSTRUCTOR_CONSTRAINT_ATTRIBUTE != 0

    val allowsByRefLike: Boolean
        get() = attributes and ALLOW_BY_REF_LIKE_ATTRIBUTE != 0

    private companion object {
        const val VARIANCE_MASK = 0x0003
        const val COVARIANT_ATTRIBUTE = 0x0001
        const val CONTRAVARIANT_ATTRIBUTE = 0x0002
        const val REFERENCE_TYPE_CONSTRAINT_ATTRIBUTE = 0x0004
        const val NOT_NULLABLE_VALUE_TYPE_CONSTRAINT_ATTRIBUTE = 0x0008
        const val DEFAULT_CONSTRUCTOR_CONSTRAINT_ATTRIBUTE = 0x0010
        const val ALLOW_BY_REF_LIKE_ATTRIBUTE = 0x0020
    }
}

data class DotNetClrGenericParameterConstraint(
    val handle: DotNetClrMetadataHandle,
    val owner: DotNetClrMetadataHandle,
    val constraint: DotNetClrMetadataHandle,
)

data class DotNetClrTypeSpecification(
    val handle: DotNetClrMetadataHandle,
    val signature: DotNetClrTypeSignature,
    val rawSignature: List<Int>,
)

/**
 * Physical CLR assembly metadata before any Kotlin import policy is applied.
 *
 * The importer layer may later interpret these rows as Kotlin declarations. This model itself
 * retains CLR names, tokens, flags, nesting, signatures, and references without inventing Kotlin
 * identities.
 */
data class DotNetClrAssemblyMetadata(
    val identity: DotNetManagedAssemblyIdentity,
    val assemblyReferences: List<DotNetClrAssemblyReference>,
    val typeReferences: List<DotNetClrTypeReference>,
    val typeDefinitions: List<DotNetClrTypeDefinition>,
    val interfaceImplementations: List<DotNetClrInterfaceImplementation>,
    val exportedTypes: List<DotNetClrExportedType>,
    val typeSpecifications: List<DotNetClrTypeSpecification>,
    val fieldDefinitions: List<DotNetClrFieldDefinition>,
    val methodDefinitions: List<DotNetClrMethodDefinition>,
    val parameterDefinitions: List<DotNetClrParameterDefinition>,
    val constantDefinitions: List<DotNetClrConstantDefinition>,
    val memberReferences: List<DotNetClrMemberReference>,
    val customAttributes: List<DotNetClrCustomAttribute>,
    val propertyDefinitions: List<DotNetClrPropertyDefinition>,
    val methodSemantics: List<DotNetClrMethodSemantics>,
    val genericParameterDefinitions: List<DotNetClrGenericParameterDefinition>,
    val genericParameterConstraints: List<DotNetClrGenericParameterConstraint>,
)
