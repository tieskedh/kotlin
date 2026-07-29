/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

/**
 * Kotlin's policy vocabulary for one enhanced foreign-type component.
 *
 * This mirrors the mature JVM foreign-type enhancement states without coupling the CLR importer
 * to the Java classfile model.
 */
enum class DotNetClrKotlinNullabilityQualifier {
    FORCE_FLEXIBILITY,
    NULLABLE,
    NOT_NULL,
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
