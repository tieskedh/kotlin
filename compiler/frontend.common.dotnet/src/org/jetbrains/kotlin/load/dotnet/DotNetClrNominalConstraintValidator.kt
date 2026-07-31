package org.jetbrains.kotlin.load.dotnet

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
    private val physicalTypeClassifier: DotNetClrPhysicalTypeClassifier,
    arrayRuntimeTypes: DotNetClrArrayRuntimeTypes,
    delegateRuntimeTypes: DotNetClrDelegateRuntimeTypes,
    resolutionLimit: Int = DEFAULT_RESOLUTION_LIMIT,
) {
    private val assignabilityResolver =
        DotNetClrSignatureTypeAssignabilityResolver(
            typeResolver,
            physicalTypeClassifier,
            primitiveTypes,
            arrayRuntimeTypes,
            delegateRuntimeTypes,
            resolutionLimit,
        )

    fun validate(
        constraints: DotNetClrResolvedConstructedTypeConstraints,
        genericParameterContext: DotNetClrResolvedGenericParameterContext? = null,
    ): DotNetClrConstructedTypeNominalConstraintValidation =
        DotNetClrConstructedTypeNominalConstraintValidation(
            constraints,
            constraints.parameters.map { binding ->
                DotNetClrNominalGenericParameterValidation(
                    binding,
                    binding.constraints.map { constraint ->
                        DotNetClrNominalConstraintValidation(
                            constraint,
                            validate(
                                binding.argument,
                                constraint.type,
                                genericParameterContext,
                            ),
                        )
                    },
                )
            },
        )

    private fun validate(
        argument: DotNetClrResolvedTypeSignature,
        constraint: DotNetClrResolvedGenericConstraintType,
        genericParameterContext: DotNetClrResolvedGenericParameterContext?,
    ): DotNetClrNominalConstraintSatisfaction {
        val constraintSignature = constraint.asResolvedSignature()
        return if (
            argument is DotNetClrResolvedTypeSignature.GenericParameter
        ) {
            validateGenericParameter(
                argument,
                constraintSignature,
                genericParameterContext,
                linkedSetOf(),
            )
        } else {
            validateConcrete(argument, constraintSignature)
        }
    }

    private fun validateConcrete(
        argument: DotNetClrResolvedTypeSignature,
        constraintSignature: DotNetClrResolvedTypeSignature,
    ): DotNetClrNominalConstraintSatisfaction {
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

    /**
     * Proves the boxed open parameter against one target constraint from its selected declaration
     * context. This is intentionally not part of global signature assignability, where an
     * unboxed open parameter remains assignable only to itself.
     */
    private fun validateGenericParameter(
        argument: DotNetClrResolvedTypeSignature.GenericParameter,
        constraintSignature: DotNetClrResolvedTypeSignature,
        genericParameterContext: DotNetClrResolvedGenericParameterContext?,
        active: MutableSet<GenericParameterConstraintPair>,
    ): DotNetClrNominalConstraintSatisfaction {
        val binding = genericParameterContext?.binding(argument)
            ?: return DotNetClrNominalConstraintSatisfaction.Unsupported(
                DotNetClrNominalConstraintUnsupported.DEPENDENT_GENERIC_PARAMETER,
                argument,
            )
        if (argument == constraintSignature ||
            constraintSignature.isSelectedSystemObject()
        ) {
            return DotNetClrNominalConstraintSatisfaction.Satisfied
        }
        if (binding.parameter.hasNotNullableValueTypeConstraint &&
            constraintSignature.isSelectedSystemValueType()
        ) {
            return DotNetClrNominalConstraintSatisfaction.Satisfied
        }

        val pair = GenericParameterConstraintPair(argument, constraintSignature)
        if (!active.add(pair)) {
            return DotNetClrNominalConstraintSatisfaction.InvalidAssignability(
                DotNetClrTypeAssignability.SignatureCycle(argument)
            )
        }
        return try {
            var firstInvalid:
                    DotNetClrNominalConstraintSatisfaction.InvalidAssignability? = null
            var firstUnsupported:
                    DotNetClrNominalConstraintSatisfaction.Unsupported? = null
            for (sourceConstraint in binding.constraints) {
                val sourceSignature = sourceConstraint.type.asResolvedSignature()
                val satisfaction =
                    if (
                        sourceSignature is
                                DotNetClrResolvedTypeSignature.GenericParameter
                    ) {
                        validateGenericParameter(
                            sourceSignature,
                            constraintSignature,
                            genericParameterContext,
                            active,
                        )
                    } else {
                        validateConcrete(sourceSignature, constraintSignature)
                    }
                when (satisfaction) {
                    DotNetClrNominalConstraintSatisfaction.Satisfied ->
                        return satisfaction

                    DotNetClrNominalConstraintSatisfaction.Violated -> Unit
                    is DotNetClrNominalConstraintSatisfaction.Unsupported ->
                        if (firstUnsupported == null) {
                            firstUnsupported = satisfaction
                        }

                    is DotNetClrNominalConstraintSatisfaction
                            .InvalidAssignability ->
                        if (firstInvalid == null) {
                            firstInvalid = satisfaction
                        }
                }
            }
            firstInvalid
                ?: firstUnsupported
                ?: DotNetClrNominalConstraintSatisfaction.Violated
        } finally {
            active.remove(pair)
        }
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

    private fun DotNetClrResolvedTypeSignature.isSelectedSystemObject(): Boolean =
        when (this) {
            is DotNetClrResolvedTypeSignature.Primitive ->
                type == DotNetClrPrimitiveType.OBJECT

            is DotNetClrResolvedTypeSignature.Named ->
                type.hasSameIdentityAs(
                    primitiveTypes[DotNetClrPrimitiveType.OBJECT]
                )

            else -> false
        }

    private fun DotNetClrResolvedTypeSignature.isSelectedSystemValueType():
            Boolean =
        this is DotNetClrResolvedTypeSignature.Named &&
                type.hasSameIdentityAs(physicalTypeClassifier.systemValueType)

    private data class GenericParameterConstraintPair(
        val argument: DotNetClrResolvedTypeSignature.GenericParameter,
        val constraint: DotNetClrResolvedTypeSignature,
    )

    private companion object {
        const val DEFAULT_RESOLUTION_LIMIT = 256
    }
}
