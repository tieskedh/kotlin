/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.load.dotnet

enum class DotNetClrNullableTypeComponentKind {
    NULLABILITY,
    GENERIC_VALUE_TYPE_PADDING,
}

data class DotNetClrNullableTypeComponent(
    val type: DotNetClrResolvedTypeSignature,
    val annotation: DotNetClrNullableAnnotation,
    val kind: DotNetClrNullableTypeComponentKind,
)

enum class DotNetClrNullableTypeApplicationFailure {
    FLAG_COUNT_MISMATCH,
    GENERIC_VALUE_TYPE_PADDING_NOT_OBLIVIOUS,
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
        val invalidComponentIndex: Int? = null,
        val invalidAnnotation: DotNetClrNullableAnnotation? = null,
    ) : DotNetClrNullableTypeApplication
}

/**
 * Aligns one decoded Roslyn nullable transform with an exact resolved CLR signature tree.
 *
 * The result deliberately remains physical evidence. In particular, NOT_ANNOTATED is not yet a
 * Kotlin definitely-non-null type, and this class neither selects an enclosing nullable context
 * nor applies NullablePublicOnly accessibility policy. Roslyn's mandatory leading oblivious flag
 * for a generic non-nullable value type is retained as structural padding and rejected if a
 * producer assigns it another value.
 */
class DotNetClrNullableTypeTransformApplicator(
    private val physicalTypeClassifier: DotNetClrPhysicalTypeClassifier,
) {
    fun apply(
        type: DotNetClrResolvedTypeSignature,
        transform: DotNetClrNullableTransform,
    ): DotNetClrNullableTypeApplication {
        val components = ArrayList<PhysicalComponent>()
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
        val appliedComponents = ArrayList<DotNetClrNullableTypeComponent>(
            components.size
        )
        for (index in components.indices) {
            val component = components[index]
            val annotation = annotations[index]
            if (
                component.kind ==
                    DotNetClrNullableTypeComponentKind.GENERIC_VALUE_TYPE_PADDING &&
                annotation != DotNetClrNullableAnnotation.OBLIVIOUS
            ) {
                return DotNetClrNullableTypeApplication.Invalid(
                    failure = DotNetClrNullableTypeApplicationFailure
                        .GENERIC_VALUE_TYPE_PADDING_NOT_OBLIVIOUS,
                    type = type,
                    invalidComponentIndex = index,
                    invalidAnnotation = annotation,
                )
            }
            appliedComponents += DotNetClrNullableTypeComponent(
                component.type,
                annotation,
                component.kind,
            )
        }
        return DotNetClrNullableTypeApplication.Applied(type, appliedComponents)
    }

    private fun collectComponents(
        type: DotNetClrResolvedTypeSignature,
        components: MutableList<PhysicalComponent>,
    ): DotNetClrPhysicalTypeClassification? {
        when (val consumption = consumesTransformFlag(type)) {
            is FlagConsumption.Consumes ->
                components += PhysicalComponent(type, consumption.kind)

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
        components: MutableList<PhysicalComponent>,
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
                    FlagConsumption.Consumes(
                        DotNetClrNullableTypeComponentKind.NULLABILITY
                    )
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
            -> FlagConsumption.Consumes(
                DotNetClrNullableTypeComponentKind.NULLABILITY
            )

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
                        FlagConsumption.Consumes(
                            DotNetClrNullableTypeComponentKind.NULLABILITY
                        )

                    DotNetClrPhysicalTypeKind.NON_NULLABLE_VALUE ->
                        if (isGenericInstance) {
                            FlagConsumption.Consumes(
                                DotNetClrNullableTypeComponentKind
                                    .GENERIC_VALUE_TYPE_PADDING
                            )
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
        data class Consumes(
            val kind: DotNetClrNullableTypeComponentKind,
        ) : FlagConsumption

        data object Skips : FlagConsumption

        data class Invalid(
            val classification: DotNetClrPhysicalTypeClassification,
        ) : FlagConsumption
    }

    private data class PhysicalComponent(
        val type: DotNetClrResolvedTypeSignature,
        val kind: DotNetClrNullableTypeComponentKind,
    )
}
