package org.jetbrains.kotlin.backend.dotnet

enum class DotNetClrByRefLikeStatus {
    NOT_BY_REF_LIKE,
    BY_REF_LIKE,
    MARKER_UNAVAILABLE,
}

enum class DotNetClrByRefLikeClassificationFailure {
    INVALID_MARKER_TARGET,
    DUPLICATE_MARKER,
    NON_EMPTY_MARKER_VALUE,
}

sealed interface DotNetClrByRefLikeClassification {
    data class Classified(
        val physicalKind: DotNetClrPhysicalTypeKind,
        val status: DotNetClrByRefLikeStatus,
    ) : DotNetClrByRefLikeClassification

    data class PhysicalTypeFailure(
        val classification: DotNetClrPhysicalTypeClassification,
    ) : DotNetClrByRefLikeClassification

    data class InvalidAttributeConstructor(
        val attribute: DotNetClrCustomAttribute,
        val resolution: DotNetClrCustomAttributeConstructorResolution.Invalid,
    ) : DotNetClrByRefLikeClassification

    data class InvalidMarkerValue(
        val attribute: DotNetClrCustomAttribute,
        val decoding: DotNetClrCustomAttributeValueDecoding,
    ) : DotNetClrByRefLikeClassification

    data class Invalid(
        val failure: DotNetClrByRefLikeClassificationFailure,
        val attributes: List<DotNetClrCustomAttribute>,
        val physicalKind: DotNetClrPhysicalTypeKind,
    ) : DotNetClrByRefLikeClassification
}

/**
 * Adds CLR by-ref-like marker semantics to physical type-kind classification.
 *
 * Marker identity comes from the selected profile and ordinary decoded custom-attribute
 * semantics. A same-named attribute, an undecodable constructor, or raw-blob equality is never
 * accepted as evidence.
 */
class DotNetClrByRefLikeClassifier(
    private val physicalTypeClassifier: DotNetClrPhysicalTypeClassifier,
    private val attributeDecoder: DotNetClrCustomAttributeDecoder,
    private val isByRefLikeAttribute: DotNetClrResolvedTypeDefinition?,
) {
    internal val systemValueType: DotNetClrResolvedTypeDefinition
        get() = physicalTypeClassifier.systemValueType

    internal val systemEnum: DotNetClrResolvedTypeDefinition
        get() = physicalTypeClassifier.systemEnum

    fun classify(
        type: DotNetClrResolvedTypeSignature,
    ): DotNetClrByRefLikeClassification {
        val physicalClassification = physicalTypeClassifier.classify(type)
        val physicalKind =
            (physicalClassification as? DotNetClrPhysicalTypeClassification.Classified)?.kind
                ?: return DotNetClrByRefLikeClassification.PhysicalTypeFailure(
                    physicalClassification
                )
        val definition = type.nominalDefinitionOrNull()
        if (definition == null) {
            return classified(
                physicalKind,
                DotNetClrByRefLikeStatus.NOT_BY_REF_LIKE,
            )
        }
        val markerType = isByRefLikeAttribute
            ?: return classified(
                physicalKind,
                if (physicalKind == DotNetClrPhysicalTypeKind.NON_NULLABLE_VALUE) {
                    DotNetClrByRefLikeStatus.MARKER_UNAVAILABLE
                } else {
                    DotNetClrByRefLikeStatus.NOT_BY_REF_LIKE
                },
            )

        val markerAttributes = ArrayList<ResolvedMarkerAttribute>()
        for (
            attribute in definition.assembly.customAttributes.filter { candidate ->
                candidate.parent == definition.definition.handle
            }
        ) {
            val constructor = when (
                val resolution =
                    attributeDecoder.resolveConstructor(
                        definition.assembly,
                        attribute,
                    )
            ) {
                is DotNetClrCustomAttributeConstructorResolution.Resolved ->
                    resolution.constructor

                is DotNetClrCustomAttributeConstructorResolution.Invalid ->
                    return DotNetClrByRefLikeClassification.InvalidAttributeConstructor(
                        attribute,
                        resolution,
                    )
            }
            if (!constructor.attributeType.type.hasSameIdentityAs(markerType)) continue
            val value = when (
                val decoding =
                    attributeDecoder.decodeValue(
                        definition.assembly,
                        attribute,
                        constructor,
                    )
            ) {
                is DotNetClrCustomAttributeValueDecoding.Decoded ->
                    decoding.attribute

                is DotNetClrCustomAttributeValueDecoding.Invalid,
                is DotNetClrCustomAttributeValueDecoding.Unsupported,
                -> return DotNetClrByRefLikeClassification.InvalidMarkerValue(
                    attribute,
                    decoding,
                )
            }
            markerAttributes += ResolvedMarkerAttribute(attribute, value)
        }
        if (markerAttributes.size > 1) {
            return DotNetClrByRefLikeClassification.Invalid(
                DotNetClrByRefLikeClassificationFailure.DUPLICATE_MARKER,
                markerAttributes.map(ResolvedMarkerAttribute::attribute),
                physicalKind,
            )
        }
        val marker = markerAttributes.singleOrNull()
            ?: return classified(
                physicalKind,
                DotNetClrByRefLikeStatus.NOT_BY_REF_LIKE,
            )
        if (physicalKind != DotNetClrPhysicalTypeKind.NON_NULLABLE_VALUE) {
            return DotNetClrByRefLikeClassification.Invalid(
                DotNetClrByRefLikeClassificationFailure.INVALID_MARKER_TARGET,
                listOf(marker.attribute),
                physicalKind,
            )
        }
        if (marker.value.fixedArguments.isNotEmpty() ||
            marker.value.namedArguments.isNotEmpty()
        ) {
            return DotNetClrByRefLikeClassification.Invalid(
                DotNetClrByRefLikeClassificationFailure.NON_EMPTY_MARKER_VALUE,
                listOf(marker.attribute),
                physicalKind,
            )
        }
        return classified(
            physicalKind,
            DotNetClrByRefLikeStatus.BY_REF_LIKE,
        )
    }

    private fun classified(
        physicalKind: DotNetClrPhysicalTypeKind,
        status: DotNetClrByRefLikeStatus,
    ): DotNetClrByRefLikeClassification.Classified =
        DotNetClrByRefLikeClassification.Classified(physicalKind, status)
}

private data class ResolvedMarkerAttribute(
    val attribute: DotNetClrCustomAttribute,
    val value: DotNetClrDecodedCustomAttribute,
)

private fun DotNetClrResolvedTypeSignature.nominalDefinitionOrNull():
        DotNetClrResolvedTypeDefinition? =
    when (this) {
        is DotNetClrResolvedTypeSignature.Named -> type
        is DotNetClrResolvedTypeSignature.GenericInstance -> genericType.type
        is DotNetClrResolvedTypeSignature.Modified ->
            unmodifiedType.nominalDefinitionOrNull()

        else -> null
    }
