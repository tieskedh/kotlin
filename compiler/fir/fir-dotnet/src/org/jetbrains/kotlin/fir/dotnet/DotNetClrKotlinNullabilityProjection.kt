/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.dotnet

import org.jetbrains.kotlin.load.dotnet.DotNetClrAllowNullMetadataResolution
import org.jetbrains.kotlin.load.dotnet.DotNetClrDisallowNullMetadataResolution
import org.jetbrains.kotlin.load.dotnet.DotNetClrMaybeNullMetadataResolution
import org.jetbrains.kotlin.load.dotnet.DotNetClrNotNullMetadataResolution
import org.jetbrains.kotlin.load.dotnet.DotNetClrNullableAnnotation
import org.jetbrains.kotlin.load.dotnet.DotNetClrNullableEvidenceApplication
import org.jetbrains.kotlin.load.dotnet.DotNetClrNullableTypeComponent
import org.jetbrains.kotlin.load.dotnet.DotNetClrNullableTypeComponentKind
import org.jetbrains.kotlin.load.dotnet.DotNetClrResolvedTypeSignature

/**
 * Kotlin's policy vocabulary for one enhanced foreign-type component.
 *
 * This mirrors the mature JVM foreign-type enhancement states without coupling the CLR importer
 * to the Java classfile model or CLI session composition.
 */
enum class DotNetClrKotlinNullabilityQualifier {
    FORCE_FLEXIBILITY,
    NULLABLE,
    NOT_NULL,
}

/**
 * Applies Roslyn's call-boundary precondition precedence to a Kotlin-facing parameter qualifier.
 */
class DotNetClrInputNullabilityEnhancer {
    fun enhance(
        declarationQualifier: DotNetClrKotlinNullabilityQualifier,
        allowNull: DotNetClrAllowNullMetadataResolution?,
        disallowNull: DotNetClrDisallowNullMetadataResolution?,
    ): DotNetClrKotlinNullabilityQualifier {
        if (allowNull is DotNetClrAllowNullMetadataResolution.Invalid) {
            return DotNetClrKotlinNullabilityQualifier.FORCE_FLEXIBILITY
        }
        if (disallowNull is DotNetClrDisallowNullMetadataResolution.Decoded) {
            return DotNetClrKotlinNullabilityQualifier.NOT_NULL
        }
        if (allowNull is DotNetClrAllowNullMetadataResolution.Decoded) {
            return DotNetClrKotlinNullabilityQualifier.NULLABLE
        }
        return declarationQualifier
    }
}

/**
 * Applies Roslyn's unconditional call-result precedence to a Kotlin-facing return qualifier.
 *
 * Invalid weakening evidence is deliberately different from absent evidence: retaining a rigid
 * non-null declaration qualifier would be unsafe. Invalid strengthening evidence cannot change
 * the declaration qualifier.
 */
class DotNetClrReturnNullabilityEnhancer {
    fun enhance(
        declarationQualifier: DotNetClrKotlinNullabilityQualifier,
        notNull: DotNetClrNotNullMetadataResolution?,
        maybeNull: DotNetClrMaybeNullMetadataResolution?,
    ): DotNetClrKotlinNullabilityQualifier {
        if (maybeNull is DotNetClrMaybeNullMetadataResolution.Invalid) {
            return DotNetClrKotlinNullabilityQualifier.FORCE_FLEXIBILITY
        }
        if (notNull is DotNetClrNotNullMetadataResolution.Decoded) {
            return DotNetClrKotlinNullabilityQualifier.NOT_NULL
        }
        if (maybeNull is DotNetClrMaybeNullMetadataResolution.Decoded) {
            return DotNetClrKotlinNullabilityQualifier.NULLABLE
        }
        return declarationQualifier
    }
}

data class DotNetClrKotlinNullabilityComponent(
    val component: DotNetClrNullableTypeComponent,
    val qualifier: DotNetClrKotlinNullabilityQualifier,
) {
    val type: DotNetClrResolvedTypeSignature
        get() = component.type
}

sealed interface DotNetClrKotlinNullabilityProjection {
    val application: DotNetClrNullableEvidenceApplication

    val type: DotNetClrResolvedTypeSignature
        get() = application.type

    data class Projected(
        override val application: DotNetClrNullableEvidenceApplication.Applied,
        val components: List<DotNetClrKotlinNullabilityComponent>,
    ) : DotNetClrKotlinNullabilityProjection

    data class Oblivious(
        override val application: DotNetClrNullableEvidenceApplication.Oblivious,
    ) : DotNetClrKotlinNullabilityProjection

    data class Suppressed(
        override val application: DotNetClrNullableEvidenceApplication.Suppressed,
    ) : DotNetClrKotlinNullabilityProjection

    sealed interface DiagnosticFallback : DotNetClrKotlinNullabilityProjection {
        override val application:
                DotNetClrNullableEvidenceApplication.DiagnosticFallback

        data class InvalidDeclaration(
            override val application:
                    DotNetClrNullableEvidenceApplication.DiagnosticFallback
                        .InvalidDeclaration,
        ) : DiagnosticFallback

        data class InvalidTypeApplication(
            override val application:
                    DotNetClrNullableEvidenceApplication.DiagnosticFallback
                        .InvalidTypeApplication,
        ) : DiagnosticFallback
    }
}

/**
 * Projects already selected and physically aligned Roslyn nullable evidence into Kotlin's
 * foreign-type qualifier vocabulary.
 *
 * Semantic flags 0, 1, and 2 become forced-flexible, not-null, and nullable respectively. A
 * generic-value-type leading 0 is structural preorder padding and remains only in the retained
 * physical application. Unselected, suppressed, or invalid evidence retains its exact application
 * and physical type in a distinct unchanged projection. This operation neither constructs FIR nor
 * derives definitely-not-null from generic-parameter markers, and it deliberately does not assign
 * diagnostic severity.
 */
class DotNetClrKotlinNullabilityProjector {
    fun project(
        application: DotNetClrNullableEvidenceApplication,
    ): DotNetClrKotlinNullabilityProjection =
        when (application) {
            is DotNetClrNullableEvidenceApplication.Applied ->
                DotNetClrKotlinNullabilityProjection.Projected(
                    application,
                    application.application.components.mapNotNull { component ->
                        when (component.kind) {
                            DotNetClrNullableTypeComponentKind.NULLABILITY ->
                                DotNetClrKotlinNullabilityComponent(
                                    component,
                                    component.annotation.asKotlinQualifier(),
                                )

                            DotNetClrNullableTypeComponentKind
                                .GENERIC_VALUE_TYPE_PADDING ->
                                null
                        }
                    },
                )

            is DotNetClrNullableEvidenceApplication.Oblivious ->
                DotNetClrKotlinNullabilityProjection.Oblivious(application)

            is DotNetClrNullableEvidenceApplication.Suppressed ->
                DotNetClrKotlinNullabilityProjection.Suppressed(application)

            is DotNetClrNullableEvidenceApplication.DiagnosticFallback
                .InvalidDeclaration ->
                DotNetClrKotlinNullabilityProjection.DiagnosticFallback
                    .InvalidDeclaration(application)

            is DotNetClrNullableEvidenceApplication.DiagnosticFallback
                .InvalidTypeApplication ->
                DotNetClrKotlinNullabilityProjection.DiagnosticFallback
                    .InvalidTypeApplication(application)
        }

    private fun DotNetClrNullableAnnotation.asKotlinQualifier():
            DotNetClrKotlinNullabilityQualifier =
        when (this) {
            DotNetClrNullableAnnotation.OBLIVIOUS ->
                DotNetClrKotlinNullabilityQualifier.FORCE_FLEXIBILITY

            DotNetClrNullableAnnotation.NOT_ANNOTATED ->
                DotNetClrKotlinNullabilityQualifier.NOT_NULL

            DotNetClrNullableAnnotation.ANNOTATED ->
                DotNetClrKotlinNullabilityQualifier.NULLABLE
        }
}
