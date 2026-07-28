package org.jetbrains.kotlin.backend.dotnet

data class DotNetClrPhysicalTypeCoreTypes(
    val systemValueType: DotNetClrResolvedTypeDefinition,
    val systemEnum: DotNetClrResolvedTypeDefinition,
    val systemNullable: DotNetClrResolvedTypeDefinition,
)

enum class DotNetClrPhysicalTypeKind {
    REFERENCE,
    NON_NULLABLE_VALUE,
    NULLABLE_VALUE,
}

enum class DotNetClrPhysicalTypeClassificationUnsupported {
    VOID,
    TYPED_REFERENCE,
    GENERIC_PARAMETER,
    POINTER,
    BY_REFERENCE,
    FUNCTION_POINTER,
}

enum class DotNetClrPhysicalTypeClassificationFailure {
    GENERIC_ARITY_MISMATCH,
    SIGNATURE_KIND_MISMATCH,
}

sealed interface DotNetClrPhysicalTypeClassification {
    data class Classified(
        val kind: DotNetClrPhysicalTypeKind,
    ) : DotNetClrPhysicalTypeClassification

    data class Unsupported(
        val reason: DotNetClrPhysicalTypeClassificationUnsupported,
        val type: DotNetClrResolvedTypeSignature,
    ) : DotNetClrPhysicalTypeClassification

    data class Invalid(
        val failure: DotNetClrPhysicalTypeClassificationFailure,
        val type: DotNetClrResolvedTypeSignature,
        val expectedGenericArity: Int? = null,
        val actualGenericArity: Int? = null,
        val encodedAsValueType: Boolean? = null,
        val definitionIsValueType: Boolean? = null,
    ) : DotNetClrPhysicalTypeClassification

    data class InvalidHierarchy(
        val type: DotNetClrResolvedTypeSignature,
        val resolution: DotNetClrTypeAssignability,
    ) : DotNetClrPhysicalTypeClassification
}

/**
 * Classifies the physical storage category of one resolved CLR signature.
 *
 * The class/value bit in a signature is validated against the selected definition hierarchy.
 * By-ref-like status is an independent custom-attribute dimension and is deliberately not
 * inferred here.
 */
class DotNetClrPhysicalTypeClassifier(
    typeResolver: DotNetClrTypeResolver,
    private val coreTypes: DotNetClrPhysicalTypeCoreTypes,
    resolutionLimit: Int = DEFAULT_RESOLUTION_LIMIT,
) {
    private val assignabilityResolver =
        DotNetClrTypeAssignabilityResolver(typeResolver, resolutionLimit)

    fun classify(
        type: DotNetClrResolvedTypeSignature,
    ): DotNetClrPhysicalTypeClassification =
        when (type) {
            DotNetClrResolvedTypeSignature.Void ->
                unsupported(
                    DotNetClrPhysicalTypeClassificationUnsupported.VOID,
                    type,
                )

            DotNetClrResolvedTypeSignature.TypedReference ->
                unsupported(
                    DotNetClrPhysicalTypeClassificationUnsupported.TYPED_REFERENCE,
                    type,
                )

            is DotNetClrResolvedTypeSignature.Primitive ->
                classified(
                    if (type.type.isSystemValueType) {
                        DotNetClrPhysicalTypeKind.NON_NULLABLE_VALUE
                    } else {
                        DotNetClrPhysicalTypeKind.REFERENCE
                    }
                )

            is DotNetClrResolvedTypeSignature.Named ->
                classifyNominal(
                    originalType = type,
                    definition = type.type,
                    arguments = emptyList(),
                    encodedAsValueType = type.isValueType,
                )

            is DotNetClrResolvedTypeSignature.GenericInstance ->
                classifyNominal(
                    originalType = type,
                    definition = type.genericType.type,
                    arguments = type.arguments,
                    encodedAsValueType = type.genericType.isValueType,
                )

            is DotNetClrResolvedTypeSignature.GenericParameter ->
                unsupported(
                    DotNetClrPhysicalTypeClassificationUnsupported.GENERIC_PARAMETER,
                    type,
                )

            is DotNetClrResolvedTypeSignature.Pointer ->
                unsupported(
                    DotNetClrPhysicalTypeClassificationUnsupported.POINTER,
                    type,
                )

            is DotNetClrResolvedTypeSignature.ByReference ->
                unsupported(
                    DotNetClrPhysicalTypeClassificationUnsupported.BY_REFERENCE,
                    type,
                )

            is DotNetClrResolvedTypeSignature.SzArray,
            is DotNetClrResolvedTypeSignature.Array,
            -> classified(DotNetClrPhysicalTypeKind.REFERENCE)

            is DotNetClrResolvedTypeSignature.FunctionPointer ->
                unsupported(
                    DotNetClrPhysicalTypeClassificationUnsupported.FUNCTION_POINTER,
                    type,
                )

            is DotNetClrResolvedTypeSignature.Modified ->
                classify(type.unmodifiedType)
        }

    private fun classifyNominal(
        originalType: DotNetClrResolvedTypeSignature,
        definition: DotNetClrResolvedTypeDefinition,
        arguments: List<DotNetClrResolvedTypeSignature>,
        encodedAsValueType: Boolean,
    ): DotNetClrPhysicalTypeClassification {
        val expectedGenericArity = definition.genericArity()
        if (arguments.size != expectedGenericArity) {
            return DotNetClrPhysicalTypeClassification.Invalid(
                DotNetClrPhysicalTypeClassificationFailure.GENERIC_ARITY_MISMATCH,
                originalType,
                expectedGenericArity = expectedGenericArity,
                actualGenericArity = arguments.size,
            )
        }
        val view = DotNetClrResolvedTypeView(definition, arguments)
        val definitionIsValueType = when {
            definition.definition.isInterface -> false
            definition.hasSameIdentityAs(coreTypes.systemValueType) -> false
            definition.hasSameIdentityAs(coreTypes.systemEnum) -> false
            else -> when (
                val resolution = assignabilityResolver.isAssignable(
                    view,
                    DotNetClrResolvedTypeView(
                        coreTypes.systemValueType,
                        emptyList(),
                    ),
                )
            ) {
                DotNetClrTypeAssignability.Assignable -> true
                DotNetClrTypeAssignability.NotAssignable -> false
                is DotNetClrTypeAssignability.InvalidHierarchy,
                is DotNetClrTypeAssignability.InheritanceCycle,
                is DotNetClrTypeAssignability.ResolutionLimitExceeded,
                -> return DotNetClrPhysicalTypeClassification.InvalidHierarchy(
                    originalType,
                    resolution,
                )
            }
        }
        if (encodedAsValueType != definitionIsValueType) {
            return DotNetClrPhysicalTypeClassification.Invalid(
                DotNetClrPhysicalTypeClassificationFailure.SIGNATURE_KIND_MISMATCH,
                originalType,
                encodedAsValueType = encodedAsValueType,
                definitionIsValueType = definitionIsValueType,
            )
        }
        return classified(
            when {
                !definitionIsValueType -> DotNetClrPhysicalTypeKind.REFERENCE
                definition.hasSameIdentityAs(coreTypes.systemNullable) ->
                    DotNetClrPhysicalTypeKind.NULLABLE_VALUE

                else -> DotNetClrPhysicalTypeKind.NON_NULLABLE_VALUE
            }
        )
    }

    private fun classified(
        kind: DotNetClrPhysicalTypeKind,
    ): DotNetClrPhysicalTypeClassification.Classified =
        DotNetClrPhysicalTypeClassification.Classified(kind)

    private fun unsupported(
        reason: DotNetClrPhysicalTypeClassificationUnsupported,
        type: DotNetClrResolvedTypeSignature,
    ): DotNetClrPhysicalTypeClassification.Unsupported =
        DotNetClrPhysicalTypeClassification.Unsupported(reason, type)

    private companion object {
        const val DEFAULT_RESOLUTION_LIMIT = 256
    }
}
