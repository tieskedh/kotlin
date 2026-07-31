package org.jetbrains.kotlin.load.dotnet

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

data class DotNetClrResolvedGenericParameterContextBinding(
    val kind: DotNetClrGenericParameterKind,
    val parameter: DotNetClrGenericParameterDefinition,
    val constraints: List<DotNetClrResolvedGenericParameterConstraint>,
)

/**
 * The declaration-qualified meaning of `!n` and `!!n` for one selected CLR location.
 *
 * Parameter indices are meaningful only inside this object. Callers select the exact declaring
 * location; validators never consult a declaration-independent `(kind, index)` map.
 */
data class DotNetClrResolvedGenericParameterContext(
    val declaringType: DotNetClrResolvedTypeView,
    val method: DotNetClrMethodDefinition?,
    val typeParameters: List<DotNetClrResolvedGenericParameterContextBinding>,
    val methodParameters: List<DotNetClrResolvedGenericParameterContextBinding>,
) {
    private val hasIdentityTypeParameterView: Boolean =
        declaringType.arguments.size == typeParameters.size &&
                typeParameters.indices.all { index ->
                    declaringType.arguments[index] ==
                            DotNetClrResolvedTypeSignature.GenericParameter(
                                DotNetClrGenericParameterKind.TYPE,
                                index,
                            )
                }

    fun binding(
        parameter: DotNetClrResolvedTypeSignature.GenericParameter,
    ): DotNetClrResolvedGenericParameterContextBinding? =
        when (parameter.kind) {
            DotNetClrGenericParameterKind.TYPE ->
                typeParameters.getOrNull(parameter.index)
                    ?.takeIf { hasIdentityTypeParameterView }

            DotNetClrGenericParameterKind.METHOD ->
                methodParameters.getOrNull(parameter.index)
        }?.takeIf { binding ->
            binding.kind == parameter.kind &&
                    binding.parameter.number == parameter.index &&
                    binding.parameter.owner == when (parameter.kind) {
                        DotNetClrGenericParameterKind.TYPE ->
                            declaringType.type.definition.handle

                        DotNetClrGenericParameterKind.METHOD ->
                            method?.handle
                    }
        }
}

enum class DotNetClrGenericParameterContextResolutionFailure {
    TYPE_CONSTRAINT_RESOLUTION_FAILED,
    TYPE_CONSTRAINT_PARAMETER_OUT_OF_SCOPE,
    METHOD_NOT_DECLARED_BY_TYPE,
    INVALID_METHOD_GENERIC_PARAMETER_NUMBERING,
    METHOD_GENERIC_ARITY_MISMATCH,
    METHOD_CONSTRAINT_RESOLUTION_FAILED,
    METHOD_CONSTRAINT_PARAMETER_OUT_OF_SCOPE,
}

sealed interface DotNetClrGenericParameterContextResolution {
    data class Resolved(
        val context: DotNetClrResolvedGenericParameterContext,
    ) : DotNetClrGenericParameterContextResolution

    data class Invalid(
        val failure: DotNetClrGenericParameterContextResolutionFailure,
        val parameterKind: DotNetClrGenericParameterKind? = null,
        val parameterIndex: Int? = null,
        val constraint: DotNetClrGenericParameterConstraint? = null,
        val outOfScopeParameter: DotNetClrResolvedTypeSignature.GenericParameter? = null,
        val constraintResolution: DotNetClrConstructedTypeConstraintResolution.Invalid? = null,
    ) : DotNetClrGenericParameterContextResolution
}

/**
 * Resolves and substitutes the declared constraints of one constructed CLR type.
 *
 * This layer preserves constraint meaning but does not yet decide whether each argument satisfies
 * its special or nominal constraints. That later assignability check is shared import policy, not
 * metadata decoding and not custom-attribute-specific behaviour.
 */
class DotNetClrConstructedTypeConstraintResolver(
    typeResolver: DotNetClrTypeResolver,
) {
    private val constraintTypeResolver =
        DotNetClrGenericConstraintTypeResolver(typeResolver)

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
                    val resolution =
                        constraintTypeResolver.resolve(
                            view.type.assembly,
                            row.constraint,
                            view.arguments,
                        )
                ) {
                    is DotNetClrGenericConstraintTypeResolution.Resolved ->
                        resolution.type

                    is DotNetClrGenericConstraintTypeResolution.Invalid ->
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
}

/**
 * Resolves the open type- and method-parameter spaces of one declaration location.
 *
 * The selected declaring type supplies any owner substitution. Method parameters remain open and
 * are validated in their distinct `!!n` space.
 */
class DotNetClrGenericParameterContextResolver(
    typeResolver: DotNetClrTypeResolver,
) {
    private val constructedTypeResolver =
        DotNetClrConstructedTypeConstraintResolver(typeResolver)
    private val constraintTypeResolver =
        DotNetClrGenericConstraintTypeResolver(typeResolver)

    fun resolve(
        declaringType: DotNetClrResolvedTypeView,
        method: DotNetClrMethodDefinition? = null,
    ): DotNetClrGenericParameterContextResolution {
        val typeConstraints = when (
            val resolution = constructedTypeResolver.resolve(declaringType)
        ) {
            is DotNetClrConstructedTypeConstraintResolution.Resolved ->
                resolution.constraints

            is DotNetClrConstructedTypeConstraintResolution.Invalid ->
                return DotNetClrGenericParameterContextResolution.Invalid(
                    DotNetClrGenericParameterContextResolutionFailure
                        .TYPE_CONSTRAINT_RESOLUTION_FAILED,
                    parameterKind = DotNetClrGenericParameterKind.TYPE,
                    parameterIndex = resolution.parameterIndex,
                    constraint = resolution.constraint,
                    constraintResolution = resolution,
                )
        }
        val typeParameterCount = typeConstraints.parameters.size
        typeConstraints.parameters.forEachIndexed { parameterIndex, binding ->
            binding.constraints.forEach { constraint ->
                val outOfScope = constraint.type.firstOutOfScopeGenericParameter(
                    typeParameterCount,
                    methodParameterCount = 0,
                )
                if (outOfScope != null) {
                    return DotNetClrGenericParameterContextResolution.Invalid(
                        DotNetClrGenericParameterContextResolutionFailure
                            .TYPE_CONSTRAINT_PARAMETER_OUT_OF_SCOPE,
                        parameterKind = DotNetClrGenericParameterKind.TYPE,
                        parameterIndex = parameterIndex,
                        constraint = constraint.row,
                        outOfScopeParameter = outOfScope,
                    )
                }
            }
        }
        val typeBindings = typeConstraints.parameters.map { binding ->
            DotNetClrResolvedGenericParameterContextBinding(
                DotNetClrGenericParameterKind.TYPE,
                binding.parameter,
                binding.constraints,
            )
        }

        if (method == null) {
            return DotNetClrGenericParameterContextResolution.Resolved(
                DotNetClrResolvedGenericParameterContext(
                    declaringType,
                    method = null,
                    typeParameters = typeBindings,
                    methodParameters = emptyList(),
                )
            )
        }
        if (
            declaringType.type.assembly.methodDefinitions.none { candidate ->
                candidate === method
            } ||
            method.declaringType != declaringType.type.definition.handle
        ) {
            return DotNetClrGenericParameterContextResolution.Invalid(
                DotNetClrGenericParameterContextResolutionFailure
                    .METHOD_NOT_DECLARED_BY_TYPE
            )
        }
        val methodParameters = declaringType.type.assembly.genericParameterDefinitions
            .filter { parameter -> parameter.owner == method.handle }
            .sortedBy(DotNetClrGenericParameterDefinition::number)
        if (methodParameters.map(DotNetClrGenericParameterDefinition::number) !=
            methodParameters.indices.toList()
        ) {
            return DotNetClrGenericParameterContextResolution.Invalid(
                DotNetClrGenericParameterContextResolutionFailure
                    .INVALID_METHOD_GENERIC_PARAMETER_NUMBERING,
                parameterKind = DotNetClrGenericParameterKind.METHOD,
            )
        }
        if (methodParameters.size != method.signature.genericParameterCount) {
            return DotNetClrGenericParameterContextResolution.Invalid(
                DotNetClrGenericParameterContextResolutionFailure
                    .METHOD_GENERIC_ARITY_MISMATCH,
                parameterKind = DotNetClrGenericParameterKind.METHOD,
            )
        }

        val methodBindings =
            ArrayList<DotNetClrResolvedGenericParameterContextBinding>(
                methodParameters.size
            )
        methodParameters.forEachIndexed { parameterIndex, parameter ->
            val constraintRows =
                declaringType.type.assembly.genericParameterConstraints.filter { row ->
                    row.owner == parameter.handle
                }
            val constraints =
                ArrayList<DotNetClrResolvedGenericParameterConstraint>(
                    constraintRows.size
                )
            for (row in constraintRows) {
                val type = when (
                    val resolution =
                        constraintTypeResolver.resolve(
                            declaringType.type.assembly,
                            row.constraint,
                            declaringType.arguments,
                        )
                ) {
                    is DotNetClrGenericConstraintTypeResolution.Resolved ->
                        resolution.type

                    is DotNetClrGenericConstraintTypeResolution.Invalid ->
                        return DotNetClrGenericParameterContextResolution.Invalid(
                            DotNetClrGenericParameterContextResolutionFailure
                                .METHOD_CONSTRAINT_RESOLUTION_FAILED,
                            parameterKind = DotNetClrGenericParameterKind.METHOD,
                            parameterIndex = parameterIndex,
                            constraint = row,
                            constraintResolution =
                                resolution.toConstructedInvalid(
                                    parameterIndex,
                                    row,
                                ),
                        )
                }
                val outOfScope = type.firstOutOfScopeGenericParameter(
                    typeParameterCount,
                    methodParameters.size,
                )
                if (outOfScope != null) {
                    return DotNetClrGenericParameterContextResolution.Invalid(
                        DotNetClrGenericParameterContextResolutionFailure
                            .METHOD_CONSTRAINT_PARAMETER_OUT_OF_SCOPE,
                        parameterKind = DotNetClrGenericParameterKind.METHOD,
                        parameterIndex = parameterIndex,
                        constraint = row,
                        outOfScopeParameter = outOfScope,
                    )
                }
                constraints += DotNetClrResolvedGenericParameterConstraint(
                    row,
                    type,
                )
            }
            methodBindings += DotNetClrResolvedGenericParameterContextBinding(
                DotNetClrGenericParameterKind.METHOD,
                parameter,
                constraints.toList(),
            )
        }

        return DotNetClrGenericParameterContextResolution.Resolved(
            DotNetClrResolvedGenericParameterContext(
                declaringType,
                method,
                typeBindings,
                methodBindings.toList(),
            )
        )
    }
}

private class DotNetClrGenericConstraintTypeResolver(
    private val typeResolver: DotNetClrTypeResolver,
) {
    private val signatureResolver = DotNetClrSignatureResolver(typeResolver)

    fun resolve(
        assembly: DotNetClrAssemblyMetadata,
        handle: DotNetClrMetadataHandle,
        typeArguments: List<DotNetClrResolvedTypeSignature>,
    ): DotNetClrGenericConstraintTypeResolution =
        if (handle.table == TYPE_SPEC_TABLE) {
            val specification = assembly.typeSpecifications.singleOrNull { candidate ->
                candidate.handle == handle
            } ?: return when (
                val resolution =
                    typeResolver.resolveTypeDefinition(assembly, handle)
            ) {
                is DotNetClrTypeResolution.Resolved ->
                    DotNetClrGenericConstraintTypeResolution.Invalid(
                        DotNetClrConstructedTypeConstraintResolutionFailure
                            .INVALID_CONSTRAINT_SIGNATURE
                    )

                is DotNetClrTypeResolution.Unresolved ->
                    DotNetClrGenericConstraintTypeResolution.Invalid(
                        DotNetClrConstructedTypeConstraintResolutionFailure
                            .CONSTRAINT_TYPE_RESOLUTION_FAILED,
                        typeResolution = resolution,
                    )
            }
            val resolved = when (
                val resolution =
                    signatureResolver.resolve(assembly, specification.signature)
            ) {
                is DotNetClrResolvedSignatureResolution.Resolved -> resolution.signature
                is DotNetClrResolvedSignatureResolution.UnresolvedType ->
                    return DotNetClrGenericConstraintTypeResolution.Invalid(
                        DotNetClrConstructedTypeConstraintResolutionFailure
                            .CONSTRAINT_TYPE_RESOLUTION_FAILED,
                        typeResolution = resolution.resolution,
                    )

                is DotNetClrResolvedSignatureResolution.Invalid ->
                    return DotNetClrGenericConstraintTypeResolution.Invalid(
                        DotNetClrConstructedTypeConstraintResolutionFailure
                            .INVALID_CONSTRAINT_SIGNATURE,
                        signatureResolution = resolution,
                    )
            }
            when (val substitution = resolved.substituteClrTypeArguments(typeArguments)) {
                is DotNetClrResolvedSignatureSubstitution.Substituted ->
                    DotNetClrGenericConstraintTypeResolution.Resolved(
                        DotNetClrResolvedGenericConstraintType.Specification(
                            substitution.signature
                        )
                    )

                is DotNetClrResolvedSignatureSubstitution.Invalid ->
                    DotNetClrGenericConstraintTypeResolution.Invalid(
                        DotNetClrConstructedTypeConstraintResolutionFailure
                            .CONSTRAINT_SUBSTITUTION_FAILED,
                        signatureSubstitution = substitution,
                    )
            }
        } else {
            when (
                val resolution =
                    typeResolver.resolveTypeDefinition(assembly, handle)
            ) {
                is DotNetClrTypeResolution.Resolved ->
                    DotNetClrGenericConstraintTypeResolution.Resolved(
                        DotNetClrResolvedGenericConstraintType.Nominal(resolution.type)
                    )

                is DotNetClrTypeResolution.Unresolved ->
                    DotNetClrGenericConstraintTypeResolution.Invalid(
                        DotNetClrConstructedTypeConstraintResolutionFailure
                            .CONSTRAINT_TYPE_RESOLUTION_FAILED,
                        typeResolution = resolution,
                    )
            }
        }

    private companion object {
        const val TYPE_SPEC_TABLE = 27
    }
}

private sealed interface DotNetClrGenericConstraintTypeResolution {
    data class Resolved(
        val type: DotNetClrResolvedGenericConstraintType,
    ) : DotNetClrGenericConstraintTypeResolution

    data class Invalid(
        val failure: DotNetClrConstructedTypeConstraintResolutionFailure,
        val typeResolution: DotNetClrTypeResolution.Unresolved? = null,
        val signatureResolution: DotNetClrResolvedSignatureResolution.Invalid? = null,
        val signatureSubstitution: DotNetClrResolvedSignatureSubstitution.Invalid? = null,
    ) : DotNetClrGenericConstraintTypeResolution
}

private fun DotNetClrGenericConstraintTypeResolution.Invalid.toConstructedInvalid(
    parameterIndex: Int,
    constraint: DotNetClrGenericParameterConstraint,
): DotNetClrConstructedTypeConstraintResolution.Invalid =
    DotNetClrConstructedTypeConstraintResolution.Invalid(
        failure,
        parameterIndex,
        constraint,
        typeResolution,
        signatureResolution,
        signatureSubstitution,
    )

private fun DotNetClrResolvedGenericConstraintType.firstOutOfScopeGenericParameter(
    typeParameterCount: Int,
    methodParameterCount: Int,
): DotNetClrResolvedTypeSignature.GenericParameter? =
    when (this) {
        is DotNetClrResolvedGenericConstraintType.Nominal -> null
        is DotNetClrResolvedGenericConstraintType.Specification ->
            type.firstOutOfScopeGenericParameter(
                typeParameterCount,
                methodParameterCount,
            )
    }

/**
 * Restores the resolved signature view retained by one GenericParamConstraint row.
 *
 * A nominal constraint row is encoded as a class TypeDefOrRef even when the selected definition
 * is a value type; physical kind validation remains the classifier's responsibility.
 */
fun DotNetClrResolvedGenericConstraintType.asResolvedSignature():
        DotNetClrResolvedTypeSignature =
    when (this) {
        is DotNetClrResolvedGenericConstraintType.Nominal ->
            DotNetClrResolvedTypeSignature.Named(
                type,
                isValueType = false,
            )

        is DotNetClrResolvedGenericConstraintType.Specification -> type
    }

private fun DotNetClrResolvedTypeSignature.firstOutOfScopeGenericParameter(
    typeParameterCount: Int,
    methodParameterCount: Int,
): DotNetClrResolvedTypeSignature.GenericParameter? =
    when (this) {
        DotNetClrResolvedTypeSignature.Void,
        DotNetClrResolvedTypeSignature.TypedReference,
        is DotNetClrResolvedTypeSignature.Primitive,
        is DotNetClrResolvedTypeSignature.Named,
        -> null

        is DotNetClrResolvedTypeSignature.GenericParameter ->
            takeIf { parameter ->
                parameter.index !in when (parameter.kind) {
                    DotNetClrGenericParameterKind.TYPE ->
                        0 until typeParameterCount

                    DotNetClrGenericParameterKind.METHOD ->
                        0 until methodParameterCount
                }
            }

        is DotNetClrResolvedTypeSignature.Pointer ->
            elementType.firstOutOfScopeGenericParameter(
                typeParameterCount,
                methodParameterCount,
            )

        is DotNetClrResolvedTypeSignature.ByReference ->
            elementType.firstOutOfScopeGenericParameter(
                typeParameterCount,
                methodParameterCount,
            )

        is DotNetClrResolvedTypeSignature.SzArray ->
            elementType.firstOutOfScopeGenericParameter(
                typeParameterCount,
                methodParameterCount,
            )

        is DotNetClrResolvedTypeSignature.Array ->
            elementType.firstOutOfScopeGenericParameter(
                typeParameterCount,
                methodParameterCount,
            )

        is DotNetClrResolvedTypeSignature.GenericInstance ->
            arguments.firstNotNullOfOrNull { argument ->
                argument.firstOutOfScopeGenericParameter(
                    typeParameterCount,
                    methodParameterCount,
                )
            }

        is DotNetClrResolvedTypeSignature.FunctionPointer ->
            sequenceOf(signature.returnType)
                .plus(signature.parameterTypes.asSequence())
                .firstNotNullOfOrNull { type ->
                    type.firstOutOfScopeGenericParameter(
                        typeParameterCount,
                        methodParameterCount,
                    )
                }

        is DotNetClrResolvedTypeSignature.Modified ->
            unmodifiedType.firstOutOfScopeGenericParameter(
                typeParameterCount,
                methodParameterCount,
            )
    }
