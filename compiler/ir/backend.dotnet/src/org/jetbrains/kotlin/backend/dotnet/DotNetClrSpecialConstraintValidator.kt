package org.jetbrains.kotlin.backend.dotnet

enum class DotNetClrSpecialConstraintKind {
    REFERENCE_TYPE,
    NON_NULLABLE_VALUE_TYPE,
    BY_REF_LIKE_ELIGIBILITY,
}

enum class DotNetClrSpecialConstraintViolation {
    REQUIRES_REFERENCE_TYPE,
    REQUIRES_NON_NULLABLE_VALUE_TYPE,
    BY_REF_LIKE_NOT_ALLOWED_BY_PARAMETER,
    BY_REF_LIKE_NOT_SUPPORTED_BY_TARGET,
}

enum class DotNetClrSpecialConstraintUnsupported {
    BY_REF_LIKE_MARKER_UNAVAILABLE,
}

sealed interface DotNetClrSpecialConstraintSatisfaction {
    data object Satisfied : DotNetClrSpecialConstraintSatisfaction

    data class Violated(
        val reason: DotNetClrSpecialConstraintViolation,
    ) : DotNetClrSpecialConstraintSatisfaction

    data class Unsupported(
        val reason: DotNetClrSpecialConstraintUnsupported,
    ) : DotNetClrSpecialConstraintSatisfaction

    data class InvalidClassification(
        val classification: DotNetClrByRefLikeClassification,
    ) : DotNetClrSpecialConstraintSatisfaction
}

data class DotNetClrSpecialConstraintValidation(
    val kind: DotNetClrSpecialConstraintKind,
    val satisfaction: DotNetClrSpecialConstraintSatisfaction,
)

data class DotNetClrSpecialGenericParameterValidation(
    val binding: DotNetClrResolvedGenericParameterBinding,
    val argumentClassification: DotNetClrByRefLikeClassification,
    val constraints: List<DotNetClrSpecialConstraintValidation>,
)

data class DotNetClrConstructedTypeSpecialConstraintValidation(
    val constraints: DotNetClrResolvedConstructedTypeConstraints,
    val parameters: List<DotNetClrSpecialGenericParameterValidation>,
)

/**
 * Validates the reference/value special constraints and implicit by-ref-like eligibility of each
 * argument in one resolved constructed CLR type.
 *
 * Nominal GenericParamConstraint rows and the default-constructor special constraint remain
 * separate validators. In particular, callers must not interpret this result as complete CLR
 * generic-constraint satisfaction.
 */
class DotNetClrSpecialConstraintValidator(
    private val target: DotNetTarget,
    private val byRefLikeClassifier: DotNetClrByRefLikeClassifier,
) {
    fun validate(
        constraints: DotNetClrResolvedConstructedTypeConstraints,
    ): DotNetClrConstructedTypeSpecialConstraintValidation =
        DotNetClrConstructedTypeSpecialConstraintValidation(
            constraints,
            constraints.parameters.map(::validate),
        )

    private fun validate(
        binding: DotNetClrResolvedGenericParameterBinding,
    ): DotNetClrSpecialGenericParameterValidation {
        val classification = byRefLikeClassifier.classify(binding.argument)
        val kinds = buildList {
            if (binding.parameter.hasReferenceTypeConstraint) {
                add(DotNetClrSpecialConstraintKind.REFERENCE_TYPE)
            }
            if (binding.parameter.hasNotNullableValueTypeConstraint) {
                add(DotNetClrSpecialConstraintKind.NON_NULLABLE_VALUE_TYPE)
            }
            add(DotNetClrSpecialConstraintKind.BY_REF_LIKE_ELIGIBILITY)
        }
        return DotNetClrSpecialGenericParameterValidation(
            binding,
            classification,
            kinds.map { kind ->
                DotNetClrSpecialConstraintValidation(
                    kind,
                    validate(kind, binding, classification),
                )
            },
        )
    }

    private fun validate(
        kind: DotNetClrSpecialConstraintKind,
        binding: DotNetClrResolvedGenericParameterBinding,
        classification: DotNetClrByRefLikeClassification,
    ): DotNetClrSpecialConstraintSatisfaction {
        val classified = classification as? DotNetClrByRefLikeClassification.Classified
            ?: return DotNetClrSpecialConstraintSatisfaction.InvalidClassification(
                classification
            )
        return when (kind) {
            DotNetClrSpecialConstraintKind.REFERENCE_TYPE ->
                if (classified.physicalKind == DotNetClrPhysicalTypeKind.REFERENCE) {
                    DotNetClrSpecialConstraintSatisfaction.Satisfied
                } else {
                    violated(
                        DotNetClrSpecialConstraintViolation.REQUIRES_REFERENCE_TYPE
                    )
                }

            DotNetClrSpecialConstraintKind.NON_NULLABLE_VALUE_TYPE ->
                if (
                    classified.physicalKind ==
                    DotNetClrPhysicalTypeKind.NON_NULLABLE_VALUE
                ) {
                    DotNetClrSpecialConstraintSatisfaction.Satisfied
                } else {
                    violated(
                        DotNetClrSpecialConstraintViolation
                            .REQUIRES_NON_NULLABLE_VALUE_TYPE
                    )
                }

            DotNetClrSpecialConstraintKind.BY_REF_LIKE_ELIGIBILITY ->
                validateByRefLikeEligibility(binding, classified.status)
        }
    }

    private fun validateByRefLikeEligibility(
        binding: DotNetClrResolvedGenericParameterBinding,
        status: DotNetClrByRefLikeStatus,
    ): DotNetClrSpecialConstraintSatisfaction =
        when (status) {
            DotNetClrByRefLikeStatus.NOT_BY_REF_LIKE ->
                DotNetClrSpecialConstraintSatisfaction.Satisfied

            DotNetClrByRefLikeStatus.MARKER_UNAVAILABLE ->
                DotNetClrSpecialConstraintSatisfaction.Unsupported(
                    DotNetClrSpecialConstraintUnsupported
                        .BY_REF_LIKE_MARKER_UNAVAILABLE
                )

            DotNetClrByRefLikeStatus.BY_REF_LIKE ->
                when {
                    target != DotNetTarget.NET10_0 ->
                        violated(
                            DotNetClrSpecialConstraintViolation
                                .BY_REF_LIKE_NOT_SUPPORTED_BY_TARGET
                        )

                    !binding.parameter.allowsByRefLike ->
                        violated(
                            DotNetClrSpecialConstraintViolation
                                .BY_REF_LIKE_NOT_ALLOWED_BY_PARAMETER
                        )

                    else -> DotNetClrSpecialConstraintSatisfaction.Satisfied
                }
        }

    private fun violated(
        reason: DotNetClrSpecialConstraintViolation,
    ): DotNetClrSpecialConstraintSatisfaction.Violated =
        DotNetClrSpecialConstraintSatisfaction.Violated(reason)
}
