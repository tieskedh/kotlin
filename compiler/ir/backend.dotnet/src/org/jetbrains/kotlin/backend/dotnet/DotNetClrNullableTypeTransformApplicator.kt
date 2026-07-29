/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

data class DotNetClrNullableTypeComponent(
    val type: DotNetClrResolvedTypeSignature,
    val annotation: DotNetClrNullableAnnotation,
)

enum class DotNetClrNullableTypeApplicationFailure {
    FLAG_COUNT_MISMATCH,
    INVALID_PHYSICAL_TYPE,
}

sealed interface DotNetClrNullableTypeApplication {
    data class Applied(
        val type: DotNetClrResolvedTypeSignature,
        val components: List<DotNetClrNullableTypeComponent>,
    ) : DotNetClrNullableTypeApplication

    data class Invalid(
        val failure: DotNetClrNullableTypeApplicationFailure,
        val type: DotNetClrResolvedTypeSignature,
        val expectedFlagCount: Int? = null,
        val actualFlagCount: Int? = null,
        val physicalClassification: DotNetClrPhysicalTypeClassification? = null,
    ) : DotNetClrNullableTypeApplication
}

/**
 * Aligns one decoded Roslyn nullable transform with an exact resolved CLR signature tree.
 *
 * The result deliberately remains physical evidence. In particular, NOT_ANNOTATED is not yet a
 * Kotlin definitely-non-null type, and this class neither selects an enclosing nullable context
 * nor applies NullablePublicOnly accessibility policy.
 */
class DotNetClrNullableTypeTransformApplicator(
    private val physicalTypeClassifier: DotNetClrPhysicalTypeClassifier,
) {
    fun apply(
        type: DotNetClrResolvedTypeSignature,
        transform: DotNetClrNullableTransform,
    ): DotNetClrNullableTypeApplication {
        val components = ArrayList<DotNetClrResolvedTypeSignature>()
        val invalidClassification = collectComponents(type, components)
        if (invalidClassification != null) {
            return DotNetClrNullableTypeApplication.Invalid(
                failure = DotNetClrNullableTypeApplicationFailure.INVALID_PHYSICAL_TYPE,
                type = type,
                physicalClassification = invalidClassification,
            )
        }

        val annotations = when (transform) {
            is DotNetClrNullableTransform.Uniform ->
                List(components.size) { transform.annotation }

            is DotNetClrNullableTransform.Sequence -> {
                if (transform.annotations.size != components.size) {
                    return DotNetClrNullableTypeApplication.Invalid(
                        failure = DotNetClrNullableTypeApplicationFailure.FLAG_COUNT_MISMATCH,
                        type = type,
                        expectedFlagCount = components.size,
                        actualFlagCount = transform.annotations.size,
                    )
                }
                transform.annotations
            }
        }
        return DotNetClrNullableTypeApplication.Applied(
            type = type,
            components = components.zip(annotations) { component, annotation ->
                DotNetClrNullableTypeComponent(component, annotation)
            },
        )
    }

    private fun collectComponents(
        type: DotNetClrResolvedTypeSignature,
        components: MutableList<DotNetClrResolvedTypeSignature>,
    ): DotNetClrPhysicalTypeClassification? {
        when (val consumption = consumesTransformFlag(type)) {
            FlagConsumption.Consumes -> components += type
            FlagConsumption.Skips -> Unit
            is FlagConsumption.Invalid -> return consumption.classification
        }

        return when (type) {
            DotNetClrResolvedTypeSignature.Void,
            DotNetClrResolvedTypeSignature.TypedReference,
            is DotNetClrResolvedTypeSignature.Primitive,
            is DotNetClrResolvedTypeSignature.Named,
            is DotNetClrResolvedTypeSignature.GenericParameter,
            -> null

            is DotNetClrResolvedTypeSignature.Pointer ->
                collectComponents(type.elementType, components)

            is DotNetClrResolvedTypeSignature.ByReference ->
                collectComponents(type.elementType, components)

            is DotNetClrResolvedTypeSignature.SzArray ->
                collectComponents(type.elementType, components)

            is DotNetClrResolvedTypeSignature.Array ->
                collectComponents(type.elementType, components)

            is DotNetClrResolvedTypeSignature.GenericInstance ->
                collectChildren(type.arguments, components)

            is DotNetClrResolvedTypeSignature.FunctionPointer ->
                collectChildren(
                    listOf(type.signature.returnType) + type.signature.parameterTypes,
                    components,
                )

            is DotNetClrResolvedTypeSignature.Modified ->
                collectComponents(type.unmodifiedType, components)
        }
    }

    private fun collectChildren(
        children: List<DotNetClrResolvedTypeSignature>,
        components: MutableList<DotNetClrResolvedTypeSignature>,
    ): DotNetClrPhysicalTypeClassification? {
        for (child in children) {
            val invalidClassification = collectComponents(child, components)
            if (invalidClassification != null) return invalidClassification
        }
        return null
    }

    private fun consumesTransformFlag(
        type: DotNetClrResolvedTypeSignature,
    ): FlagConsumption =
        when (type) {
            DotNetClrResolvedTypeSignature.Void,
            DotNetClrResolvedTypeSignature.TypedReference,
            -> FlagConsumption.Skips

            is DotNetClrResolvedTypeSignature.Primitive ->
                if (type.type.isSystemValueType) {
                    FlagConsumption.Skips
                } else {
                    FlagConsumption.Consumes
                }

            is DotNetClrResolvedTypeSignature.Named ->
                nominalConsumption(type, isGenericInstance = false)

            is DotNetClrResolvedTypeSignature.GenericInstance ->
                nominalConsumption(type, isGenericInstance = true)

            is DotNetClrResolvedTypeSignature.GenericParameter,
            is DotNetClrResolvedTypeSignature.Pointer,
            is DotNetClrResolvedTypeSignature.SzArray,
            is DotNetClrResolvedTypeSignature.Array,
            is DotNetClrResolvedTypeSignature.FunctionPointer,
            -> FlagConsumption.Consumes

            is DotNetClrResolvedTypeSignature.ByReference,
            is DotNetClrResolvedTypeSignature.Modified,
            -> FlagConsumption.Skips
        }

    private fun nominalConsumption(
        type: DotNetClrResolvedTypeSignature,
        isGenericInstance: Boolean,
    ): FlagConsumption =
        when (val classification = physicalTypeClassifier.classify(type)) {
            is DotNetClrPhysicalTypeClassification.Classified ->
                when (classification.kind) {
                    DotNetClrPhysicalTypeKind.REFERENCE ->
                        FlagConsumption.Consumes

                    DotNetClrPhysicalTypeKind.NON_NULLABLE_VALUE ->
                        if (isGenericInstance) {
                            FlagConsumption.Consumes
                        } else {
                            FlagConsumption.Skips
                        }

                    DotNetClrPhysicalTypeKind.NULLABLE_VALUE ->
                        FlagConsumption.Skips
                }

            is DotNetClrPhysicalTypeClassification.Invalid,
            is DotNetClrPhysicalTypeClassification.InvalidHierarchy,
            is DotNetClrPhysicalTypeClassification.Unsupported,
            -> FlagConsumption.Invalid(classification)
        }

    private sealed interface FlagConsumption {
        data object Consumes : FlagConsumption

        data object Skips : FlagConsumption

        data class Invalid(
            val classification: DotNetClrPhysicalTypeClassification,
        ) : FlagConsumption
    }
}
