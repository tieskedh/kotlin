package org.jetbrains.kotlin.backend.dotnet

enum class DotNetClrSpecialConstraintKind {
    REFERENCE_TYPE,
    NON_NULLABLE_VALUE_TYPE,
    DEFAULT_CONSTRUCTOR,
    BY_REF_LIKE_ELIGIBILITY,
}

enum class DotNetClrSpecialConstraintViolation {
    REQUIRES_REFERENCE_TYPE,
    REQUIRES_NON_NULLABLE_VALUE_TYPE,
    REQUIRES_CONCRETE_REFERENCE_TYPE,
    REQUIRES_PUBLIC_PARAMETERLESS_CONSTRUCTOR,
    BY_REF_LIKE_NOT_ALLOWED_BY_PARAMETER,
    BY_REF_LIKE_NOT_SUPPORTED_BY_TARGET,
}

enum class DotNetClrSpecialConstraintUnsupported {
    BY_REF_LIKE_MARKER_UNAVAILABLE,
    DEPENDENT_GENERIC_PARAMETER,
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
 * Validates the reference/value/default-constructor special constraints and implicit by-ref-like
 * eligibility of each argument in one resolved constructed CLR type.
 *
 * Nominal GenericParamConstraint rows remain a separate validator. In particular, callers must
 * not interpret this result as complete CLR generic-constraint satisfaction.
 */
class DotNetClrSpecialConstraintValidator(
    private val target: DotNetTarget,
    private val byRefLikeClassifier: DotNetClrByRefLikeClassifier,
    private val primitiveTypes: DotNetClrPrimitiveTypeCatalog,
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
            if (binding.parameter.hasDefaultConstructorConstraint) {
                add(DotNetClrSpecialConstraintKind.DEFAULT_CONSTRUCTOR)
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
        classification.dependentGenericParameterUnsupportedOrNull()?.let {
            return it
        }
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

            DotNetClrSpecialConstraintKind.DEFAULT_CONSTRUCTOR ->
                validateDefaultConstructor(binding.argument, classified.physicalKind)

            DotNetClrSpecialConstraintKind.BY_REF_LIKE_ELIGIBILITY ->
                validateByRefLikeEligibility(binding, classified.status)
        }
    }

    private fun validateDefaultConstructor(
        argument: DotNetClrResolvedTypeSignature,
        physicalKind: DotNetClrPhysicalTypeKind,
    ): DotNetClrSpecialConstraintSatisfaction {
        return when (physicalKind) {
            DotNetClrPhysicalTypeKind.NON_NULLABLE_VALUE,
            DotNetClrPhysicalTypeKind.NULLABLE_VALUE,
            -> DotNetClrSpecialConstraintSatisfaction.Satisfied

            DotNetClrPhysicalTypeKind.REFERENCE -> {
                val definition = argument.referenceTypeDefinitionOrNull()
                    ?: return violated(
                        DotNetClrSpecialConstraintViolation
                            .REQUIRES_PUBLIC_PARAMETERLESS_CONSTRUCTOR
                    )
                if (definition.definition.isAbstract) {
                    return violated(
                        DotNetClrSpecialConstraintViolation
                            .REQUIRES_CONCRETE_REFERENCE_TYPE
                    )
                }
                if (
                    definition.assembly.methodDefinitions.none { method ->
                        method.declaringType == definition.definition.handle &&
                                method.isPublicParameterlessInstanceConstructor()
                    }
                ) {
                    return violated(
                        DotNetClrSpecialConstraintViolation
                            .REQUIRES_PUBLIC_PARAMETERLESS_CONSTRUCTOR
                    )
                }
                DotNetClrSpecialConstraintSatisfaction.Satisfied
            }
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

    private fun DotNetClrByRefLikeClassification
            .dependentGenericParameterUnsupportedOrNull():
            DotNetClrSpecialConstraintSatisfaction.Unsupported? {
        val physicalFailure =
            this as? DotNetClrByRefLikeClassification.PhysicalTypeFailure
                ?: return null
        val unsupported =
            physicalFailure.classification as?
                    DotNetClrPhysicalTypeClassification.Unsupported
                ?: return null
        return if (
            unsupported.reason ==
            DotNetClrPhysicalTypeClassificationUnsupported.GENERIC_PARAMETER
        ) {
            DotNetClrSpecialConstraintSatisfaction.Unsupported(
                DotNetClrSpecialConstraintUnsupported.DEPENDENT_GENERIC_PARAMETER
            )
        } else {
            null
        }
    }

    private fun DotNetClrResolvedTypeSignature.referenceTypeDefinitionOrNull():
            DotNetClrResolvedTypeDefinition? =
        when (this) {
            is DotNetClrResolvedTypeSignature.Primitive -> primitiveTypes[type]

            is DotNetClrResolvedTypeSignature.Named -> type
            is DotNetClrResolvedTypeSignature.GenericInstance -> genericType.type
            is DotNetClrResolvedTypeSignature.Modified ->
                unmodifiedType.referenceTypeDefinitionOrNull()

            else -> null
        }
}

private fun DotNetClrMethodDefinition.isPublicParameterlessInstanceConstructor(): Boolean =
    name == ".ctor" &&
            visibility == DotNetClrMethodVisibility.PUBLIC &&
            !isStatic &&
            isSpecialName &&
            isRuntimeSpecialName &&
            signature.callingConvention == DotNetClrSignatureCallingConvention.DEFAULT &&
            signature.hasThis &&
            !signature.hasExplicitThis &&
            signature.genericParameterCount == 0 &&
            signature.returnType == DotNetClrTypeSignature.Void &&
            signature.parameterTypes.isEmpty() &&
            signature.varargParameterStart == null
