/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.load.dotnet

data class DotNetClrNullableGenericConstraintEvidence(
    val constraint: DotNetClrResolvedGenericParameterConstraint,
    val application: DotNetClrNullableEvidenceApplication,
)

sealed interface DotNetClrNullableGenericParameterDeclarationEvidence {
    val evidence: DotNetClrNullableDeclarationEvidence

    data class Selected(
        val annotation: DotNetClrNullableAnnotation,
        override val evidence: DotNetClrNullableDeclarationEvidence.Selected,
    ) : DotNetClrNullableGenericParameterDeclarationEvidence

    data class Oblivious(
        override val evidence: DotNetClrNullableDeclarationEvidence.Oblivious,
    ) : DotNetClrNullableGenericParameterDeclarationEvidence

    data class Suppressed(
        override val evidence: DotNetClrNullableDeclarationEvidence.Suppressed,
    ) : DotNetClrNullableGenericParameterDeclarationEvidence

    sealed interface DiagnosticFallback :
            DotNetClrNullableGenericParameterDeclarationEvidence {
        data class InvalidDeclaration(
            override val evidence: DotNetClrNullableDeclarationEvidence.Invalid,
        ) : DiagnosticFallback

        data class InvalidTransform(
            override val evidence: DotNetClrNullableDeclarationEvidence.Selected,
        ) : DiagnosticFallback
    }
}

data class DotNetClrNullableGenericParameterEvidence(
    val binding: DotNetClrResolvedGenericParameterContextBinding,
    val declaration: DotNetClrNullableGenericParameterDeclarationEvidence,
    val constraints: List<DotNetClrNullableGenericConstraintEvidence>,
)

enum class DotNetClrNullableGenericParameterEvidenceFailure {
    CONTEXT_BINDING_NOT_FOUND,
}

sealed interface DotNetClrNullableGenericParameterEvidenceResolution {
    data class Resolved(
        val evidence: DotNetClrNullableGenericParameterEvidence,
    ) : DotNetClrNullableGenericParameterEvidenceResolution

    data class Invalid(
        val failure: DotNetClrNullableGenericParameterEvidenceFailure,
        val parameter: DotNetClrResolvedTypeSignature.GenericParameter,
    ) : DotNetClrNullableGenericParameterEvidenceResolution
}

/**
 * Collects declaration evidence and independently applied constraint-row evidence for one
 * declaration-qualified CLR generic parameter.
 *
 * [DotNetClrResolvedGenericParameterContext.binding] deliberately exposes bindings only from an
 * identity type view. That keeps each nullable transform aligned with its original constraint
 * tree rather than a substituted tree with a potentially different preorder. The declaration
 * marker remains separate, accepts only Roslyn's scalar form, and is not interpreted as a
 * Kotlin bound or copied to constraints.
 */
class DotNetClrNullableGenericParameterEvidenceResolver(
    private val declarationResolver: DotNetClrNullableDeclarationResolver,
    private val evidenceApplicator: DotNetClrNullableEvidenceApplicator,
) {
    fun resolve(
        assembly: DotNetClrAssemblyMetadata,
        context: DotNetClrResolvedGenericParameterContext,
        parameter: DotNetClrResolvedTypeSignature.GenericParameter,
    ): DotNetClrNullableGenericParameterEvidenceResolution {
        val binding = context.binding(parameter)
            ?: return DotNetClrNullableGenericParameterEvidenceResolution.Invalid(
                DotNetClrNullableGenericParameterEvidenceFailure
                    .CONTEXT_BINDING_NOT_FOUND,
                parameter,
            )
        val declaration = declarationResolver.resolve(
            assembly,
            DotNetClrNullableDeclarationTarget.GenericParameter(binding.parameter),
        ).asGenericParameterDeclarationEvidence()
        val constraints = binding.constraints.map { constraint ->
            val type = constraint.type.asResolvedSignature()
            val evidence = declarationResolver.resolve(
                assembly,
                DotNetClrNullableDeclarationTarget.GenericParameterConstraint(
                    constraint.row
                ),
            )
            DotNetClrNullableGenericConstraintEvidence(
                constraint,
                evidenceApplicator.apply(type, evidence),
            )
        }
        return DotNetClrNullableGenericParameterEvidenceResolution.Resolved(
            DotNetClrNullableGenericParameterEvidence(
                binding,
                declaration,
                constraints,
            )
        )
    }

    private fun DotNetClrNullableDeclarationEvidence.asGenericParameterDeclarationEvidence():
            DotNetClrNullableGenericParameterDeclarationEvidence =
        when (this) {
            is DotNetClrNullableDeclarationEvidence.Selected ->
                when (val transform = transform) {
                    is DotNetClrNullableTransform.Uniform ->
                        DotNetClrNullableGenericParameterDeclarationEvidence
                            .Selected(transform.annotation, this)

                    is DotNetClrNullableTransform.Sequence ->
                        DotNetClrNullableGenericParameterDeclarationEvidence
                            .DiagnosticFallback.InvalidTransform(this)
                }

            is DotNetClrNullableDeclarationEvidence.Oblivious ->
                DotNetClrNullableGenericParameterDeclarationEvidence
                    .Oblivious(this)

            is DotNetClrNullableDeclarationEvidence.Suppressed ->
                DotNetClrNullableGenericParameterDeclarationEvidence
                    .Suppressed(this)

            is DotNetClrNullableDeclarationEvidence.Invalid ->
                DotNetClrNullableGenericParameterDeclarationEvidence
                    .DiagnosticFallback.InvalidDeclaration(this)
        }
}
