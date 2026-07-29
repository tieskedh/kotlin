package org.jetbrains.kotlin.backend.dotnet

enum class DotNetClrNominalConstraintUnsupported {
    NON_NOMINAL_ARGUMENT,
    NON_NOMINAL_CONSTRAINT,
    DEPENDENT_GENERIC_PARAMETER,
    VARIANT_CONVERSION_REQUIRED,
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
    resolutionLimit: Int = DEFAULT_RESOLUTION_LIMIT,
) {
    private val assignabilityResolver =
        DotNetClrTypeAssignabilityResolver(typeResolver, resolutionLimit)

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
        val actualView = argument.toNominalView()
            ?: return DotNetClrNominalConstraintSatisfaction.Unsupported(
                DotNetClrNominalConstraintUnsupported.NON_NOMINAL_ARGUMENT,
                argument,
            )
        val constraintSignature = constraint.asResolvedSignature()
        val expectedView = constraintSignature.toNominalView()
            ?: return DotNetClrNominalConstraintSatisfaction.Unsupported(
                DotNetClrNominalConstraintUnsupported.NON_NOMINAL_CONSTRAINT,
                constraintSignature,
            )
        return when (
            val resolution =
                assignabilityResolver.isAssignable(actualView, expectedView)
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

            is DotNetClrTypeAssignability.InvalidHierarchy,
            is DotNetClrTypeAssignability.InheritanceCycle,
            is DotNetClrTypeAssignability.ResolutionLimitExceeded,
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
