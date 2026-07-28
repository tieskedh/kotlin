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

    private companion object {
        const val METHOD_ACCESS_MASK = 0x7
        const val STATIC_ATTRIBUTE = 0x10
        const val FINAL_ATTRIBUTE = 0x20
        const val VIRTUAL_ATTRIBUTE = 0x40
        const val ABSTRACT_ATTRIBUTE = 0x400
        const val SPECIAL_NAME_ATTRIBUTE = 0x800
    }
}

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

    private companion object {
        const val SPECIAL_NAME_ATTRIBUTE = 0x200
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
    val typeSpecifications: List<DotNetClrTypeSpecification>,
    val methodDefinitions: List<DotNetClrMethodDefinition>,
    val propertyDefinitions: List<DotNetClrPropertyDefinition>,
    val methodSemantics: List<DotNetClrMethodSemantics>,
    val genericParameterDefinitions: List<DotNetClrGenericParameterDefinition>,
    val genericParameterConstraints: List<DotNetClrGenericParameterConstraint>,
)
