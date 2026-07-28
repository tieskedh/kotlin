package org.jetbrains.kotlin.backend.dotnet

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
                attributeType = attributeType,
                signature = signature,
                constructor = attribute.constructor,
            )
        )
    }

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
    }
}
