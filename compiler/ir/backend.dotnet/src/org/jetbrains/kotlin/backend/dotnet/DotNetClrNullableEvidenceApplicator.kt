/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

sealed interface DotNetClrNullableEvidenceApplication {
    val type: DotNetClrResolvedTypeSignature

    val evidence: DotNetClrNullableDeclarationEvidence

    data class Applied(
        override val evidence: DotNetClrNullableDeclarationEvidence.Selected,
        val application: DotNetClrNullableTypeApplication.Applied,
    ) : DotNetClrNullableEvidenceApplication {
        override val type: DotNetClrResolvedTypeSignature
            get() = application.type
    }

    data class Oblivious(
        override val type: DotNetClrResolvedTypeSignature,
        override val evidence: DotNetClrNullableDeclarationEvidence.Oblivious,
    ) : DotNetClrNullableEvidenceApplication

    data class Suppressed(
        override val type: DotNetClrResolvedTypeSignature,
        override val evidence: DotNetClrNullableDeclarationEvidence.Suppressed,
    ) : DotNetClrNullableEvidenceApplication

    sealed interface DiagnosticFallback : DotNetClrNullableEvidenceApplication {
        data class InvalidDeclaration(
            override val type: DotNetClrResolvedTypeSignature,
            override val evidence: DotNetClrNullableDeclarationEvidence.Invalid,
        ) : DiagnosticFallback

        data class InvalidTypeApplication(
            override val evidence: DotNetClrNullableDeclarationEvidence.Selected,
            val application: DotNetClrNullableTypeApplication.Invalid,
        ) : DiagnosticFallback {
            override val type: DotNetClrResolvedTypeSignature
                get() = application.type
        }
    }
}

/**
 * Applies selected declaration-level Roslyn evidence to an exact resolved CLR type.
 *
 * Oblivious, suppressed, or invalid evidence retains the supplied type unchanged. Invalid declaration
 * evidence and invalid type alignment remain explicit diagnostic fallbacks so a later FIR policy
 * cannot accidentally treat malformed metadata as ordinary obliviousness. This class does not
 * resolve the declaration signature, assign diagnostic severity, or construct a Kotlin type.
 */
class DotNetClrNullableEvidenceApplicator(
    private val typeApplicator: DotNetClrNullableTypeTransformApplicator,
) {
    fun apply(
        type: DotNetClrResolvedTypeSignature,
        evidence: DotNetClrNullableDeclarationEvidence,
    ): DotNetClrNullableEvidenceApplication =
        when (evidence) {
            is DotNetClrNullableDeclarationEvidence.Selected ->
                when (val application = typeApplicator.apply(type, evidence.transform)) {
                    is DotNetClrNullableTypeApplication.Applied ->
                        DotNetClrNullableEvidenceApplication.Applied(
                            evidence,
                            application,
                        )

                    is DotNetClrNullableTypeApplication.Invalid ->
                        DotNetClrNullableEvidenceApplication.DiagnosticFallback
                            .InvalidTypeApplication(
                                evidence,
                                application,
                            )
                }

            is DotNetClrNullableDeclarationEvidence.Oblivious ->
                DotNetClrNullableEvidenceApplication.Oblivious(type, evidence)

            is DotNetClrNullableDeclarationEvidence.Suppressed ->
                DotNetClrNullableEvidenceApplication.Suppressed(type, evidence)

            is DotNetClrNullableDeclarationEvidence.Invalid ->
                DotNetClrNullableEvidenceApplication.DiagnosticFallback
                    .InvalidDeclaration(type, evidence)
        }
}
