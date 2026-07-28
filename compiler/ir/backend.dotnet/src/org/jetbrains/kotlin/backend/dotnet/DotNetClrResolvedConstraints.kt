package org.jetbrains.kotlin.backend.dotnet

/**
 * A resolved GenericParamConstraint target.
 *
 * Direct TypeDef/TypeRef constraints do not encode a class/value-type discriminator, so they
 * cannot truthfully be represented as [DotNetClrResolvedTypeSignature.Named]. TypeSpec-backed
 * constraints retain their complete resolved and owner-substituted structural signature.
 */
sealed interface DotNetClrResolvedGenericConstraintType {
    data class Nominal(
        val type: DotNetClrResolvedTypeDefinition,
    ) : DotNetClrResolvedGenericConstraintType

    data class Specification(
        val type: DotNetClrResolvedTypeSignature,
    ) : DotNetClrResolvedGenericConstraintType
}

data class DotNetClrResolvedGenericParameterConstraint(
    val row: DotNetClrGenericParameterConstraint,
    val type: DotNetClrResolvedGenericConstraintType,
)

data class DotNetClrResolvedGenericParameterBinding(
    val parameter: DotNetClrGenericParameterDefinition,
    val argument: DotNetClrResolvedTypeSignature,
    val constraints: List<DotNetClrResolvedGenericParameterConstraint>,
)

data class DotNetClrResolvedConstructedTypeConstraints(
    val view: DotNetClrResolvedTypeView,
    val parameters: List<DotNetClrResolvedGenericParameterBinding>,
)

enum class DotNetClrConstructedTypeConstraintResolutionFailure {
    INVALID_GENERIC_PARAMETER_NUMBERING,
    GENERIC_ARITY_MISMATCH,
    CONSTRAINT_TYPE_RESOLUTION_FAILED,
    INVALID_CONSTRAINT_SIGNATURE,
    CONSTRAINT_SUBSTITUTION_FAILED,
}

sealed interface DotNetClrConstructedTypeConstraintResolution {
    data class Resolved(
        val constraints: DotNetClrResolvedConstructedTypeConstraints,
    ) : DotNetClrConstructedTypeConstraintResolution

    data class Invalid(
        val failure: DotNetClrConstructedTypeConstraintResolutionFailure,
        val parameterIndex: Int? = null,
        val constraint: DotNetClrGenericParameterConstraint? = null,
        val typeResolution: DotNetClrTypeResolution.Unresolved? = null,
        val signatureResolution: DotNetClrResolvedSignatureResolution.Invalid? = null,
        val signatureSubstitution: DotNetClrResolvedSignatureSubstitution.Invalid? = null,
    ) : DotNetClrConstructedTypeConstraintResolution
}

/**
 * Resolves and substitutes the declared constraints of one constructed CLR type.
 *
 * This layer preserves constraint meaning but does not yet decide whether each argument satisfies
 * its special or nominal constraints. That later assignability check is shared import policy, not
 * metadata decoding and not custom-attribute-specific behaviour.
 */
class DotNetClrConstructedTypeConstraintResolver(
    private val typeResolver: DotNetClrTypeResolver,
) {
    private val signatureResolver = DotNetClrSignatureResolver(typeResolver)

    fun resolve(
        view: DotNetClrResolvedTypeView,
    ): DotNetClrConstructedTypeConstraintResolution {
        val parameters = view.type.assembly.genericParameterDefinitions
            .filter { parameter -> parameter.owner == view.type.definition.handle }
            .sortedBy(DotNetClrGenericParameterDefinition::number)
        if (parameters.map(DotNetClrGenericParameterDefinition::number) !=
            parameters.indices.toList()
        ) {
            return invalid(
                DotNetClrConstructedTypeConstraintResolutionFailure
                    .INVALID_GENERIC_PARAMETER_NUMBERING
            )
        }
        if (parameters.size != view.arguments.size) {
            return invalid(
                DotNetClrConstructedTypeConstraintResolutionFailure.GENERIC_ARITY_MISMATCH
            )
        }

        val bindings = ArrayList<DotNetClrResolvedGenericParameterBinding>(parameters.size)
        parameters.forEachIndexed { parameterIndex, parameter ->
            val constraintRows = view.type.assembly.genericParameterConstraints.filter { row ->
                row.owner == parameter.handle
            }
            val constraints =
                ArrayList<DotNetClrResolvedGenericParameterConstraint>(constraintRows.size)
            for (row in constraintRows) {
                val type = when (
                    val resolution = resolveConstraintType(view, row.constraint)
                ) {
                    is ConstraintTypeResolution.Resolved -> resolution.type
                    is ConstraintTypeResolution.Invalid ->
                        return invalid(
                            resolution.failure,
                            parameterIndex,
                            row,
                            resolution.typeResolution,
                            resolution.signatureResolution,
                            resolution.signatureSubstitution,
                        )
                }
                constraints += DotNetClrResolvedGenericParameterConstraint(row, type)
            }
            bindings += DotNetClrResolvedGenericParameterBinding(
                parameter,
                view.arguments[parameterIndex],
                constraints.toList(),
            )
        }
        return DotNetClrConstructedTypeConstraintResolution.Resolved(
            DotNetClrResolvedConstructedTypeConstraints(view, bindings.toList())
        )
    }

    private fun resolveConstraintType(
        view: DotNetClrResolvedTypeView,
        handle: DotNetClrMetadataHandle,
    ): ConstraintTypeResolution =
        if (handle.table == TYPE_SPEC_TABLE) {
            val specification = view.type.assembly.typeSpecifications.singleOrNull { candidate ->
                candidate.handle == handle
            } ?: return when (
                val resolution =
                    typeResolver.resolveTypeDefinition(view.type.assembly, handle)
            ) {
                is DotNetClrTypeResolution.Resolved ->
                    ConstraintTypeResolution.Invalid(
                        DotNetClrConstructedTypeConstraintResolutionFailure
                            .INVALID_CONSTRAINT_SIGNATURE
                    )

                is DotNetClrTypeResolution.Unresolved ->
                    ConstraintTypeResolution.Invalid(
                        DotNetClrConstructedTypeConstraintResolutionFailure
                            .CONSTRAINT_TYPE_RESOLUTION_FAILED,
                        typeResolution = resolution,
                    )
            }
            val resolved = when (
                val resolution =
                    signatureResolver.resolve(view.type.assembly, specification.signature)
            ) {
                is DotNetClrResolvedSignatureResolution.Resolved -> resolution.signature
                is DotNetClrResolvedSignatureResolution.UnresolvedType ->
                    return ConstraintTypeResolution.Invalid(
                        DotNetClrConstructedTypeConstraintResolutionFailure
                            .CONSTRAINT_TYPE_RESOLUTION_FAILED,
                        typeResolution = resolution.resolution,
                    )

                is DotNetClrResolvedSignatureResolution.Invalid ->
                    return ConstraintTypeResolution.Invalid(
                        DotNetClrConstructedTypeConstraintResolutionFailure
                            .INVALID_CONSTRAINT_SIGNATURE,
                        signatureResolution = resolution,
                    )
            }
            when (val substitution = resolved.substituteClrTypeArguments(view.arguments)) {
                is DotNetClrResolvedSignatureSubstitution.Substituted ->
                    ConstraintTypeResolution.Resolved(
                        DotNetClrResolvedGenericConstraintType.Specification(
                            substitution.signature
                        )
                    )

                is DotNetClrResolvedSignatureSubstitution.Invalid ->
                    ConstraintTypeResolution.Invalid(
                        DotNetClrConstructedTypeConstraintResolutionFailure
                            .CONSTRAINT_SUBSTITUTION_FAILED,
                        signatureSubstitution = substitution,
                    )
            }
        } else {
            when (
                val resolution =
                    typeResolver.resolveTypeDefinition(view.type.assembly, handle)
            ) {
                is DotNetClrTypeResolution.Resolved ->
                    ConstraintTypeResolution.Resolved(
                        DotNetClrResolvedGenericConstraintType.Nominal(resolution.type)
                    )

                is DotNetClrTypeResolution.Unresolved ->
                    ConstraintTypeResolution.Invalid(
                        DotNetClrConstructedTypeConstraintResolutionFailure
                            .CONSTRAINT_TYPE_RESOLUTION_FAILED,
                        typeResolution = resolution,
                    )
            }
        }

    private fun invalid(
        failure: DotNetClrConstructedTypeConstraintResolutionFailure,
        parameterIndex: Int? = null,
        constraint: DotNetClrGenericParameterConstraint? = null,
        typeResolution: DotNetClrTypeResolution.Unresolved? = null,
        signatureResolution: DotNetClrResolvedSignatureResolution.Invalid? = null,
        signatureSubstitution: DotNetClrResolvedSignatureSubstitution.Invalid? = null,
    ): DotNetClrConstructedTypeConstraintResolution.Invalid =
        DotNetClrConstructedTypeConstraintResolution.Invalid(
            failure,
            parameterIndex,
            constraint,
            typeResolution,
            signatureResolution,
            signatureSubstitution,
        )

    private companion object {
        const val TYPE_SPEC_TABLE = 27
    }
}

private sealed interface ConstraintTypeResolution {
    data class Resolved(
        val type: DotNetClrResolvedGenericConstraintType,
    ) : ConstraintTypeResolution

    data class Invalid(
        val failure: DotNetClrConstructedTypeConstraintResolutionFailure,
        val typeResolution: DotNetClrTypeResolution.Unresolved? = null,
        val signatureResolution: DotNetClrResolvedSignatureResolution.Invalid? = null,
        val signatureSubstitution: DotNetClrResolvedSignatureSubstitution.Invalid? = null,
    ) : ConstraintTypeResolution
}
