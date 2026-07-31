package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.load.dotnet.DotNetClrByRefLikeClassification
import org.jetbrains.kotlin.load.dotnet.DotNetClrByRefLikeClassifier
import org.jetbrains.kotlin.load.dotnet.DotNetClrByRefLikeStatus
import org.jetbrains.kotlin.load.dotnet.DotNetClrMethodDefinition
import org.jetbrains.kotlin.load.dotnet.DotNetClrMethodVisibility
import org.jetbrains.kotlin.load.dotnet.DotNetClrPhysicalTypeClassification
import org.jetbrains.kotlin.load.dotnet.DotNetClrPhysicalTypeClassificationUnsupported
import org.jetbrains.kotlin.load.dotnet.DotNetClrPhysicalTypeCoreTypes
import org.jetbrains.kotlin.load.dotnet.DotNetClrPhysicalTypeKind
import org.jetbrains.kotlin.load.dotnet.DotNetClrPrimitiveType
import org.jetbrains.kotlin.load.dotnet.DotNetClrPrimitiveTypeCatalog
import org.jetbrains.kotlin.load.dotnet.DotNetClrResolvedConstructedTypeConstraints
import org.jetbrains.kotlin.load.dotnet.DotNetClrResolvedGenericParameterBinding
import org.jetbrains.kotlin.load.dotnet.DotNetClrResolvedGenericParameterContext
import org.jetbrains.kotlin.load.dotnet.DotNetClrResolvedGenericParameterContextBinding
import org.jetbrains.kotlin.load.dotnet.DotNetClrResolvedTypeDefinition
import org.jetbrains.kotlin.load.dotnet.DotNetClrResolvedTypeSignature
import org.jetbrains.kotlin.load.dotnet.DotNetClrSignatureCallingConvention
import org.jetbrains.kotlin.load.dotnet.DotNetClrTypeSignature
import org.jetbrains.kotlin.load.dotnet.asResolvedSignature

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
    val argumentGenericParameter:
            DotNetClrResolvedGenericParameterContextBinding?,
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
    private val physicalTypeCoreTypes: DotNetClrPhysicalTypeCoreTypes,
    private val primitiveTypes: DotNetClrPrimitiveTypeCatalog,
) {
    fun validate(
        constraints: DotNetClrResolvedConstructedTypeConstraints,
        genericParameterContext: DotNetClrResolvedGenericParameterContext? = null,
    ): DotNetClrConstructedTypeSpecialConstraintValidation =
        DotNetClrConstructedTypeSpecialConstraintValidation(
            constraints,
            constraints.parameters.map { binding ->
                validate(binding, genericParameterContext)
            },
        )

    private fun validate(
        binding: DotNetClrResolvedGenericParameterBinding,
        genericParameterContext: DotNetClrResolvedGenericParameterContext?,
    ): DotNetClrSpecialGenericParameterValidation {
        val classification = byRefLikeClassifier.classify(binding.argument)
        val argumentGenericParameter =
            (binding.argument as?
                    DotNetClrResolvedTypeSignature.GenericParameter)?.let {
                    parameter ->
                genericParameterContext?.binding(parameter)
            }
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
            argumentGenericParameter,
            kinds.map { kind ->
                DotNetClrSpecialConstraintValidation(
                    kind,
                    validate(
                        kind,
                        binding,
                        classification,
                        argumentGenericParameter,
                    ),
                )
            },
        )
    }

    private fun validate(
        kind: DotNetClrSpecialConstraintKind,
        binding: DotNetClrResolvedGenericParameterBinding,
        classification: DotNetClrByRefLikeClassification,
        argumentGenericParameter:
                DotNetClrResolvedGenericParameterContextBinding?,
    ): DotNetClrSpecialConstraintSatisfaction {
        if (
            binding.argument is
                    DotNetClrResolvedTypeSignature.GenericParameter
        ) {
            return if (argumentGenericParameter == null) {
                DotNetClrSpecialConstraintSatisfaction.Unsupported(
                    DotNetClrSpecialConstraintUnsupported
                        .DEPENDENT_GENERIC_PARAMETER
                )
            } else {
                validateGenericParameter(
                    kind,
                    binding,
                    argumentGenericParameter,
                )
            }
        }
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

    private fun validateGenericParameter(
        kind: DotNetClrSpecialConstraintKind,
        targetBinding: DotNetClrResolvedGenericParameterBinding,
        argumentBinding: DotNetClrResolvedGenericParameterContextBinding,
    ): DotNetClrSpecialConstraintSatisfaction =
        when (kind) {
            DotNetClrSpecialConstraintKind.REFERENCE_TYPE ->
                validateGenericParameterReferenceType(argumentBinding)

            DotNetClrSpecialConstraintKind.NON_NULLABLE_VALUE_TYPE ->
                if (
                    argumentBinding.parameter
                        .hasNotNullableValueTypeConstraint
                ) {
                    DotNetClrSpecialConstraintSatisfaction.Satisfied
                } else {
                    violated(
                        DotNetClrSpecialConstraintViolation
                            .REQUIRES_NON_NULLABLE_VALUE_TYPE
                    )
                }

            DotNetClrSpecialConstraintKind.DEFAULT_CONSTRUCTOR ->
                if (
                    argumentBinding.parameter
                        .hasDefaultConstructorConstraint ||
                    argumentBinding.parameter
                        .hasNotNullableValueTypeConstraint
                ) {
                    DotNetClrSpecialConstraintSatisfaction.Satisfied
                } else {
                    violated(
                        DotNetClrSpecialConstraintViolation
                            .REQUIRES_PUBLIC_PARAMETERLESS_CONSTRUCTOR
                    )
                }

            DotNetClrSpecialConstraintKind.BY_REF_LIKE_ELIGIBILITY ->
                when {
                    !argumentBinding.parameter.allowsByRefLike ->
                        DotNetClrSpecialConstraintSatisfaction.Satisfied

                    target != DotNetTarget.NET10_0 ->
                        violated(
                            DotNetClrSpecialConstraintViolation
                                .BY_REF_LIKE_NOT_SUPPORTED_BY_TARGET
                        )

                    !targetBinding.parameter.allowsByRefLike ->
                        violated(
                            DotNetClrSpecialConstraintViolation
                                .BY_REF_LIKE_NOT_ALLOWED_BY_PARAMETER
                        )

                    else ->
                        DotNetClrSpecialConstraintSatisfaction.Satisfied
                }
        }

    private fun validateGenericParameterReferenceType(
        binding: DotNetClrResolvedGenericParameterContextBinding,
    ): DotNetClrSpecialConstraintSatisfaction {
        if (binding.parameter.hasReferenceTypeConstraint) {
            return DotNetClrSpecialConstraintSatisfaction.Satisfied
        }
        if (binding.parameter.hasNotNullableValueTypeConstraint) {
            return violated(
                DotNetClrSpecialConstraintViolation.REQUIRES_REFERENCE_TYPE
            )
        }

        var firstInvalid: DotNetClrByRefLikeClassification? = null
        for (constraint in binding.constraints) {
            val signature = constraint.type.asResolvedSignature()
            if (
                signature is
                        DotNetClrResolvedTypeSignature.GenericParameter
            ) {
                continue
            }
            val definition = signature.nominalDefinitionOrNull()
            if (definition?.definition?.isInterface == true ||
                definition?.hasSameIdentityAs(
                    primitiveTypes[DotNetClrPrimitiveType.OBJECT]
                ) == true ||
                definition?.hasSameIdentityAs(
                    physicalTypeCoreTypes.systemValueType
                ) == true ||
                definition?.hasSameIdentityAs(
                    physicalTypeCoreTypes.systemEnum
                ) == true
            ) {
                continue
            }
            when (val classification = byRefLikeClassifier.classify(signature)) {
                is DotNetClrByRefLikeClassification.Classified ->
                    if (
                        classification.physicalKind ==
                        DotNetClrPhysicalTypeKind.REFERENCE
                    ) {
                        return DotNetClrSpecialConstraintSatisfaction.Satisfied
                    }

                is DotNetClrByRefLikeClassification.PhysicalTypeFailure,
                is DotNetClrByRefLikeClassification.InvalidAttributeConstructor,
                is DotNetClrByRefLikeClassification.InvalidMarkerValue,
                is DotNetClrByRefLikeClassification.Invalid,
                -> if (firstInvalid == null) {
                    firstInvalid = classification
                }
            }
        }
        return firstInvalid?.let {
            DotNetClrSpecialConstraintSatisfaction.InvalidClassification(it)
        } ?: violated(
            DotNetClrSpecialConstraintViolation.REQUIRES_REFERENCE_TYPE
        )
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

    private fun DotNetClrResolvedTypeSignature.nominalDefinitionOrNull():
            DotNetClrResolvedTypeDefinition? =
        when (this) {
            is DotNetClrResolvedTypeSignature.Primitive ->
                primitiveTypes[type]

            is DotNetClrResolvedTypeSignature.Named -> type
            is DotNetClrResolvedTypeSignature.GenericInstance ->
                genericType.type

            is DotNetClrResolvedTypeSignature.Modified ->
                unmodifiedType.nominalDefinitionOrNull()

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
