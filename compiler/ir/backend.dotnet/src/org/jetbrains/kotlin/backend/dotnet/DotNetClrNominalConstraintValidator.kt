package org.jetbrains.kotlin.backend.dotnet

enum class DotNetClrNominalConstraintUnsupported {
    NON_NOMINAL_ARGUMENT,
    NON_NOMINAL_CONSTRAINT,
    DEPENDENT_GENERIC_PARAMETER,
    VARIANT_CONVERSION_REQUIRED,
    NESTED_SIGNATURE_CONVERSION_REQUIRED,
}

sealed interface DotNetClrNominalConstraintSatisfaction {
    data object Satisfied : DotNetClrNominalConstraintSatisfaction

    data object Violated : DotNetClrNominalConstraintSatisfaction

    data class Unsupported(
        val reason: DotNetClrNominalConstraintUnsupported,
        val type: DotNetClrResolvedTypeSignature,
    ) : DotNetClrNominalConstraintSatisfaction

    data class InvalidAssignability(
        val resolution: DotNetClrTypeAssignability,
    ) : DotNetClrNominalConstraintSatisfaction
}

data class DotNetClrNominalConstraintValidation(
    val constraint: DotNetClrResolvedGenericParameterConstraint,
    val satisfaction: DotNetClrNominalConstraintSatisfaction,
)

data class DotNetClrNominalGenericParameterValidation(
    val binding: DotNetClrResolvedGenericParameterBinding,
    val constraints: List<DotNetClrNominalConstraintValidation>,
)

data class DotNetClrConstructedTypeNominalConstraintValidation(
    val constraints: DotNetClrResolvedConstructedTypeConstraints,
    val parameters: List<DotNetClrNominalGenericParameterValidation>,
)

/**
 * Validates only nominal GenericParamConstraint rows.
 *
 * Reference/value/default-constructor flags and by-ref-like eligibility are deliberately not
 * included. Consumers must not interpret an all-[DotNetClrNominalConstraintSatisfaction.Satisfied]
 * result as complete CLR generic-constraint satisfaction.
 */
class DotNetClrNominalConstraintValidator(
    typeResolver: DotNetClrTypeResolver,
    private val primitiveTypes: DotNetClrPrimitiveTypeCatalog,
    physicalTypeClassifier: DotNetClrPhysicalTypeClassifier,
    arrayRuntimeTypes: DotNetClrArrayRuntimeTypes,
    resolutionLimit: Int = DEFAULT_RESOLUTION_LIMIT,
) {
    private val assignabilityResolver =
        DotNetClrSignatureTypeAssignabilityResolver(
            typeResolver,
            physicalTypeClassifier,
            primitiveTypes,
            arrayRuntimeTypes,
            resolutionLimit,
        )

    fun validate(
        constraints: DotNetClrResolvedConstructedTypeConstraints,
    ): DotNetClrConstructedTypeNominalConstraintValidation =
        DotNetClrConstructedTypeNominalConstraintValidation(
            constraints,
            constraints.parameters.map { binding ->
                DotNetClrNominalGenericParameterValidation(
                    binding,
                    binding.constraints.map { constraint ->
                        DotNetClrNominalConstraintValidation(
                            constraint,
                            validate(binding.argument, constraint.type),
                        )
                    },
                )
            },
        )

    private fun validate(
        argument: DotNetClrResolvedTypeSignature,
        constraint: DotNetClrResolvedGenericConstraintType,
    ): DotNetClrNominalConstraintSatisfaction {
        if (argument is DotNetClrResolvedTypeSignature.GenericParameter) {
            return DotNetClrNominalConstraintSatisfaction.Unsupported(
                DotNetClrNominalConstraintUnsupported.DEPENDENT_GENERIC_PARAMETER,
                argument,
            )
        }
        val constraintSignature = constraint.asResolvedSignature()
        val actualView = argument.toNominalView()
        val expectedView = constraintSignature.toNominalView()
        return when (
            val resolution =
                if (actualView != null && expectedView != null) {
                    assignabilityResolver.isAssignable(actualView, expectedView)
                } else {
                    assignabilityResolver.isAssignable(argument, constraintSignature)
                }
        ) {
            DotNetClrTypeAssignability.Assignable ->
                DotNetClrNominalConstraintSatisfaction.Satisfied

            DotNetClrTypeAssignability.NotAssignable ->
                DotNetClrNominalConstraintSatisfaction.Violated

            is DotNetClrTypeAssignability.VariantConversionRequired ->
                DotNetClrNominalConstraintSatisfaction.Unsupported(
                    DotNetClrNominalConstraintUnsupported
                        .VARIANT_CONVERSION_REQUIRED,
                    constraintSignature,
                )

            is DotNetClrTypeAssignability.UnsupportedSignatureConversion ->
                DotNetClrNominalConstraintSatisfaction.Unsupported(
                    when (resolution.reason) {
                        DotNetClrSignatureConversionUnsupported.NOMINAL_TO_ARRAY ->
                            DotNetClrNominalConstraintUnsupported
                                .NON_NOMINAL_CONSTRAINT

                        DotNetClrSignatureConversionUnsupported.OPEN_GENERIC_PARAMETER ->
                            DotNetClrNominalConstraintUnsupported
                                .DEPENDENT_GENERIC_PARAMETER

                        DotNetClrSignatureConversionUnsupported.NON_NOMINAL_SIGNATURE ->
                            when {
                                resolution.actual == argument ->
                                    DotNetClrNominalConstraintUnsupported
                                        .NON_NOMINAL_ARGUMENT

                                resolution.expected == constraintSignature ->
                                    DotNetClrNominalConstraintUnsupported
                                        .NON_NOMINAL_CONSTRAINT

                                else ->
                                    DotNetClrNominalConstraintUnsupported
                                        .NESTED_SIGNATURE_CONVERSION_REQUIRED
                            }
                    },
                    constraintSignature,
                )

            is DotNetClrTypeAssignability.InvalidVariance,
            is DotNetClrTypeAssignability.InvalidTypeClassification,
            is DotNetClrTypeAssignability.InvalidEnumStorage,
            is DotNetClrTypeAssignability.InvalidHierarchy,
            is DotNetClrTypeAssignability.InheritanceCycle,
            is DotNetClrTypeAssignability.SignatureCycle,
            is DotNetClrTypeAssignability.ResolutionLimitExceeded,
            is DotNetClrTypeAssignability.SignatureResolutionLimitExceeded,
            -> DotNetClrNominalConstraintSatisfaction.InvalidAssignability(resolution)
        }
    }

    private fun DotNetClrResolvedGenericConstraintType.asResolvedSignature():
            DotNetClrResolvedTypeSignature =
        when (this) {
            is DotNetClrResolvedGenericConstraintType.Nominal ->
                DotNetClrResolvedTypeSignature.Named(
                    type,
                    isValueType = false,
                )

            is DotNetClrResolvedGenericConstraintType.Specification -> type
        }

    private fun DotNetClrResolvedTypeSignature.toNominalView():
            DotNetClrResolvedTypeView? =
        when (this) {
            is DotNetClrResolvedTypeSignature.Primitive ->
                DotNetClrResolvedTypeView(primitiveTypes[type], emptyList())

            is DotNetClrResolvedTypeSignature.Named ->
                DotNetClrResolvedTypeView(type, emptyList())
                    .takeIf { type.genericArity() == 0 }

            is DotNetClrResolvedTypeSignature.GenericInstance ->
                DotNetClrResolvedTypeView(
                    genericType.type,
                    arguments,
                ).takeIf { arguments.size == genericType.type.genericArity() }

            else -> null
        }

    private companion object {
        const val DEFAULT_RESOLUTION_LIMIT = 256
    }
}
