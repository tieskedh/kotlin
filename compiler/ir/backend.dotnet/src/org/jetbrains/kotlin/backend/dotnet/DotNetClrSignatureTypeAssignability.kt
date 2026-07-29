package org.jetbrains.kotlin.backend.dotnet

/**
 * Resolves supported CLR signature assignability above exact nominal hierarchy traversal.
 *
 * Kotlin source subtyping is not inferred here. This is the physical foreign-signature relation
 * used by importer validation. Generic-interface variance and array-to-array conversions are
 * supported; dependent parameters, delegate variance, and vector-to-generic-interface
 * conversions remain explicit boundaries. The signature entry point never inserts boxing. The
 * nominal-view entry point is separate so generic-constraint validation can compare selected type
 * definitions without pretending that an unboxed value is assignment-compatible with a reference
 * location.
 */
class DotNetClrSignatureTypeAssignabilityResolver(
    private val typeResolver: DotNetClrTypeResolver,
    private val physicalTypeClassifier: DotNetClrPhysicalTypeClassifier,
    private val primitiveTypes: DotNetClrPrimitiveTypeCatalog,
    private val systemArray: DotNetClrResolvedTypeDefinition,
    resolutionLimit: Int = DEFAULT_RESOLUTION_LIMIT,
) {
    private val exactResolver =
        DotNetClrTypeAssignabilityResolver(typeResolver, resolutionLimit)
    private val resolutionLimit = resolutionLimit.also { limit ->
        require(limit in 1..MAX_RESOLUTION_LIMIT) {
            "CLR signature assignability resolution limit must be in 1..$MAX_RESOLUTION_LIMIT"
        }
    }

    fun isAssignable(
        actual: DotNetClrResolvedTypeView,
        expected: DotNetClrResolvedTypeView,
    ): DotNetClrTypeAssignability =
        isAssignable(
            actual,
            expected,
            linkedSetOf(),
            ResolutionCounter(),
        )

    fun isAssignable(
        actual: DotNetClrResolvedTypeSignature,
        expected: DotNetClrResolvedTypeSignature,
    ): DotNetClrTypeAssignability =
        isAssignable(
            actual,
            expected,
            linkedSetOf(),
            ResolutionCounter(),
        )

    private fun isAssignable(
        actual: DotNetClrResolvedTypeView,
        expected: DotNetClrResolvedTypeView,
        active: MutableSet<AssignabilityPair>,
        counter: ResolutionCounter,
    ): DotNetClrTypeAssignability {
        counter.count++
        if (counter.count > resolutionLimit) {
            return DotNetClrTypeAssignability.ResolutionLimitExceeded(
                resolutionLimit,
                actual,
            )
        }
        val pair = AssignabilityPair.Nominal(actual, expected)
        if (!active.add(pair)) {
            return DotNetClrTypeAssignability.InheritanceCycle(actual)
        }
        return try {
            when (val exact = exactResolver.isAssignable(actual, expected)) {
                is DotNetClrTypeAssignability.VariantConversionRequired ->
                    evaluateInterfaceVariance(exact, active, counter)

                else -> exact
            }
        } finally {
            active.remove(pair)
        }
    }

    private fun isAssignable(
        actual: DotNetClrResolvedTypeSignature,
        expected: DotNetClrResolvedTypeSignature,
        active: MutableSet<AssignabilityPair>,
        counter: ResolutionCounter,
    ): DotNetClrTypeAssignability {
        if (actual == expected) return DotNetClrTypeAssignability.Assignable
        if (!actual.isArraySignature() &&
            !expected.isArraySignature() &&
            actual !is DotNetClrResolvedTypeSignature.GenericParameter &&
            expected !is DotNetClrResolvedTypeSignature.GenericParameter &&
            actual !is DotNetClrResolvedTypeSignature.Modified &&
            expected !is DotNetClrResolvedTypeSignature.Modified
        ) {
            val actualView = actual.toNominalView()
            val expectedView = expected.toNominalView()
            if (actualView != null && expectedView != null) {
                val actualClassification = physicalTypeClassifier.classify(actual)
                val actualKind =
                    actualClassification as?
                            DotNetClrPhysicalTypeClassification.Classified
                        ?: return classificationFailure(
                            actual,
                            expected,
                            actual,
                            actualClassification,
                        )
                val expectedClassification =
                    physicalTypeClassifier.classify(expected)
                val expectedKind =
                    expectedClassification as?
                            DotNetClrPhysicalTypeClassification.Classified
                        ?: return classificationFailure(
                            actual,
                            expected,
                            expected,
                            expectedClassification,
                        )
                if (actualKind.kind != expectedKind.kind) {
                    return DotNetClrTypeAssignability.NotAssignable
                }
                if (actualKind.kind != DotNetClrPhysicalTypeKind.REFERENCE) {
                    return if (actualView == expectedView) {
                        DotNetClrTypeAssignability.Assignable
                    } else {
                        DotNetClrTypeAssignability.NotAssignable
                    }
                }
                return isAssignable(actualView, expectedView, active, counter)
            }
        }
        counter.count++
        if (counter.count > resolutionLimit) {
            return DotNetClrTypeAssignability.SignatureResolutionLimitExceeded(
                resolutionLimit,
                actual,
            )
        }
        val pair = AssignabilityPair.Signature(actual, expected)
        if (!active.add(pair)) {
            return DotNetClrTypeAssignability.SignatureCycle(actual)
        }
        return try {
            when {
                actual is DotNetClrResolvedTypeSignature.SzArray &&
                        expected is DotNetClrResolvedTypeSignature.SzArray ->
                    evaluateArrayElements(
                        actual.elementType,
                        expected.elementType,
                        active,
                        counter,
                    )

                actual is DotNetClrResolvedTypeSignature.Array &&
                        expected is DotNetClrResolvedTypeSignature.Array ->
                    if (actual.shape.rank == expected.shape.rank) {
                        evaluateArrayElements(
                            actual.elementType,
                            expected.elementType,
                            active,
                            counter,
                        )
                    } else {
                        DotNetClrTypeAssignability.NotAssignable
                    }

                actual.isArraySignature() || expected.isArraySignature() ->
                    if (actual.isArraySignature() && expected.toNominalView() != null) {
                        evaluateArrayToNominal(
                            actual,
                            expected,
                            active,
                            counter,
                        )
                    } else if (
                        expected.isArraySignature() &&
                        actual.toNominalView() != null
                    ) {
                        DotNetClrTypeAssignability.UnsupportedSignatureConversion(
                            DotNetClrSignatureConversionUnsupported.NOMINAL_TO_ARRAY,
                            actual,
                            expected,
                        )
                    } else {
                        DotNetClrTypeAssignability.NotAssignable
                    }

                actual is DotNetClrResolvedTypeSignature.GenericParameter ||
                        expected is DotNetClrResolvedTypeSignature.GenericParameter ->
                    DotNetClrTypeAssignability.UnsupportedSignatureConversion(
                        DotNetClrSignatureConversionUnsupported.OPEN_GENERIC_PARAMETER,
                        actual,
                        expected,
                    )

                else ->
                    DotNetClrTypeAssignability.UnsupportedSignatureConversion(
                        DotNetClrSignatureConversionUnsupported.NON_NOMINAL_SIGNATURE,
                        actual,
                        expected,
                    )
            }
        } finally {
            active.remove(pair)
        }
    }

    private fun evaluateArrayToNominal(
        actual: DotNetClrResolvedTypeSignature,
        expected: DotNetClrResolvedTypeSignature,
        active: MutableSet<AssignabilityPair>,
        counter: ResolutionCounter,
    ): DotNetClrTypeAssignability {
        val expectedView = checkNotNull(expected.toNominalView())
        val expectedClassification = physicalTypeClassifier.classify(expected)
        val expectedKind =
            expectedClassification as? DotNetClrPhysicalTypeClassification.Classified
                ?: return classificationFailure(
                    actual,
                    expected,
                    expected,
                    expectedClassification,
                )
        if (expectedKind.kind != DotNetClrPhysicalTypeKind.REFERENCE) {
            return DotNetClrTypeAssignability.NotAssignable
        }
        when (
            val baseResult =
                isAssignable(
                    DotNetClrResolvedTypeView(systemArray, emptyList()),
                    expectedView,
                    active,
                    counter,
                )
        ) {
            DotNetClrTypeAssignability.Assignable -> return baseResult
            DotNetClrTypeAssignability.NotAssignable -> Unit
            else -> return baseResult
        }
        if (actual is DotNetClrResolvedTypeSignature.Array ||
            !expectedView.type.definition.isInterface ||
            expectedView.arguments.size != 1
        ) {
            return DotNetClrTypeAssignability.NotAssignable
        }
        return DotNetClrTypeAssignability.UnsupportedSignatureConversion(
            DotNetClrSignatureConversionUnsupported.VECTOR_TO_GENERIC_INTERFACE,
            actual,
            expected,
        )
    }

    private fun evaluateInterfaceVariance(
        conversion: DotNetClrTypeAssignability.VariantConversionRequired,
        active: MutableSet<AssignabilityPair>,
        counter: ResolutionCounter,
    ): DotNetClrTypeAssignability {
        var firstInvalid: DotNetClrTypeAssignability? = null
        var firstUnsupported: DotNetClrTypeAssignability? = null
        for (actual in conversion.actualCandidates) {
            when (
                val result =
                    evaluateInterfaceVarianceCandidate(
                        actual,
                        conversion,
                        active,
                        counter,
                    )
            ) {
                DotNetClrTypeAssignability.Assignable -> return result
                is DotNetClrTypeAssignability.InvalidVariance,
                is DotNetClrTypeAssignability.InvalidTypeClassification,
                is DotNetClrTypeAssignability.InvalidEnumStorage,
                is DotNetClrTypeAssignability.InvalidHierarchy,
                is DotNetClrTypeAssignability.InheritanceCycle,
                is DotNetClrTypeAssignability.SignatureCycle,
                is DotNetClrTypeAssignability.ResolutionLimitExceeded,
                is DotNetClrTypeAssignability.SignatureResolutionLimitExceeded,
                -> if (firstInvalid == null) firstInvalid = result

                is DotNetClrTypeAssignability.VariantConversionRequired,
                is DotNetClrTypeAssignability.UnsupportedSignatureConversion,
                ->
                    if (firstUnsupported == null) firstUnsupported = result

                DotNetClrTypeAssignability.NotAssignable -> Unit
            }
        }
        return firstInvalid
            ?: firstUnsupported
            ?: DotNetClrTypeAssignability.NotAssignable
    }

    private fun evaluateInterfaceVarianceCandidate(
        actual: DotNetClrResolvedTypeView,
        original: DotNetClrTypeAssignability.VariantConversionRequired,
        active: MutableSet<AssignabilityPair>,
        counter: ResolutionCounter,
    ): DotNetClrTypeAssignability {
        val expected = original.expected
        if (!actual.type.definition.isInterface) return original

        val parameters = actual.type.assembly.genericParameterDefinitions
            .filter { parameter ->
                parameter.owner == actual.type.definition.handle
            }
            .sortedBy(DotNetClrGenericParameterDefinition::number)
        if (parameters.map(DotNetClrGenericParameterDefinition::number) !=
            parameters.indices.toList() ||
            parameters.size != actual.arguments.size ||
            parameters.size != expected.arguments.size
        ) {
            return DotNetClrTypeAssignability.InvalidVariance(actual, expected)
        }

        for (parameter in parameters) {
            val actualArgument = actual.arguments[parameter.number]
            val expectedArgument = expected.arguments[parameter.number]
            if (actualArgument == expectedArgument) continue
            if (parameter.variance == DotNetClrGenericParameterVariance.INVARIANT) {
                return DotNetClrTypeAssignability.NotAssignable
            }
            val actualKind = classifyReferenceArgument(actualArgument)
            if (actualKind !is ReferenceArgumentClassification.Reference) {
                return actualKind.asAssignabilityFailure(original)
            }
            val expectedKind = classifyReferenceArgument(expectedArgument)
            if (expectedKind !is ReferenceArgumentClassification.Reference) {
                return expectedKind.asAssignabilityFailure(original)
            }

            val source = when (parameter.variance) {
                DotNetClrGenericParameterVariance.COVARIANT ->
                    actualKind.type

                DotNetClrGenericParameterVariance.CONTRAVARIANT ->
                    expectedKind.type

                DotNetClrGenericParameterVariance.INVARIANT ->
                    error("Invariant arguments were handled before reference classification")
            }
            val destination = when (parameter.variance) {
                DotNetClrGenericParameterVariance.COVARIANT ->
                    expectedKind.type

                DotNetClrGenericParameterVariance.CONTRAVARIANT ->
                    actualKind.type

                DotNetClrGenericParameterVariance.INVARIANT ->
                    error("Invariant arguments were handled before reference classification")
            }
            when (
                val argumentResult =
                    isAssignable(source, destination, active, counter)
            ) {
                DotNetClrTypeAssignability.Assignable -> Unit
                else -> return argumentResult
            }
        }
        return DotNetClrTypeAssignability.Assignable
    }

    private fun classifyReferenceArgument(
        type: DotNetClrResolvedTypeSignature,
    ): ReferenceArgumentClassification {
        return when (
            val classification = physicalTypeClassifier.classify(type)
        ) {
            is DotNetClrPhysicalTypeClassification.Classified ->
                if (classification.kind == DotNetClrPhysicalTypeKind.REFERENCE) {
                    ReferenceArgumentClassification.Reference(type)
                } else {
                    ReferenceArgumentClassification.NotReference
                }

            is DotNetClrPhysicalTypeClassification.Unsupported ->
                ReferenceArgumentClassification.Unsupported

            is DotNetClrPhysicalTypeClassification.Invalid,
            is DotNetClrPhysicalTypeClassification.InvalidHierarchy,
            -> ReferenceArgumentClassification.Invalid(
                type,
                classification,
            )
        }
    }

    private fun evaluateArrayElements(
        actual: DotNetClrResolvedTypeSignature,
        expected: DotNetClrResolvedTypeSignature,
        active: MutableSet<AssignabilityPair>,
        counter: ResolutionCounter,
    ): DotNetClrTypeAssignability {
        if (actual == expected) return DotNetClrTypeAssignability.Assignable
        if (actual is DotNetClrResolvedTypeSignature.Modified ||
            expected is DotNetClrResolvedTypeSignature.Modified
        ) {
            return DotNetClrTypeAssignability.UnsupportedSignatureConversion(
                DotNetClrSignatureConversionUnsupported.NON_NOMINAL_SIGNATURE,
                actual,
                expected,
            )
        }
        val actualClassification = physicalTypeClassifier.classify(actual)
        val actualKind =
            actualClassification as? DotNetClrPhysicalTypeClassification.Classified
                ?: return classificationFailure(
                    actual,
                    expected,
                    actual,
                    actualClassification,
                )
        val expectedClassification = physicalTypeClassifier.classify(expected)
        val expectedKind =
            expectedClassification as? DotNetClrPhysicalTypeClassification.Classified
                ?: return classificationFailure(
                    actual,
                    expected,
                    expected,
                    expectedClassification,
                )
        if (actualKind.kind == DotNetClrPhysicalTypeKind.REFERENCE &&
            expectedKind.kind == DotNetClrPhysicalTypeKind.REFERENCE
        ) {
            return isAssignable(actual, expected, active, counter)
        }
        if (actualKind.kind == DotNetClrPhysicalTypeKind.REFERENCE ||
            expectedKind.kind == DotNetClrPhysicalTypeKind.REFERENCE
        ) {
            return DotNetClrTypeAssignability.NotAssignable
        }
        val actualReduced = actual.reducedArrayElementType()
        if (actualReduced is ReducedArrayElementType.Invalid) {
            return DotNetClrTypeAssignability.InvalidEnumStorage(
                actualReduced.type,
                actualReduced.resolution,
            )
        }
        val expectedReduced = expected.reducedArrayElementType()
        if (expectedReduced is ReducedArrayElementType.Invalid) {
            return DotNetClrTypeAssignability.InvalidEnumStorage(
                expectedReduced.type,
                expectedReduced.resolution,
            )
        }
        return if (
            actualReduced is ReducedArrayElementType.Reduced &&
            expectedReduced is ReducedArrayElementType.Reduced &&
            actualReduced.kind == expectedReduced.kind
        ) {
            DotNetClrTypeAssignability.Assignable
        } else {
            DotNetClrTypeAssignability.NotAssignable
        }
    }

    private fun classificationFailure(
        actual: DotNetClrResolvedTypeSignature,
        expected: DotNetClrResolvedTypeSignature,
        type: DotNetClrResolvedTypeSignature,
        classification: DotNetClrPhysicalTypeClassification,
    ): DotNetClrTypeAssignability =
        when (classification) {
            is DotNetClrPhysicalTypeClassification.Unsupported ->
                DotNetClrTypeAssignability.UnsupportedSignatureConversion(
                    if (
                        classification.reason ==
                        DotNetClrPhysicalTypeClassificationUnsupported.GENERIC_PARAMETER
                    ) {
                        DotNetClrSignatureConversionUnsupported.OPEN_GENERIC_PARAMETER
                    } else {
                        DotNetClrSignatureConversionUnsupported.NON_NOMINAL_SIGNATURE
                    },
                    actual,
                    expected,
                )

            is DotNetClrPhysicalTypeClassification.Invalid,
            is DotNetClrPhysicalTypeClassification.InvalidHierarchy,
            -> DotNetClrTypeAssignability.InvalidTypeClassification(
                type,
                classification,
            )

            is DotNetClrPhysicalTypeClassification.Classified ->
                error("A classified physical type is not a classification failure")
        }

    private fun DotNetClrResolvedTypeSignature.reducedArrayElementType():
            ReducedArrayElementType =
        when (this) {
            is DotNetClrResolvedTypeSignature.Primitive ->
                type.reducedArrayElementKind()
                    ?.let(ReducedArrayElementType::Reduced)
                    ?: ReducedArrayElementType.None

            is DotNetClrResolvedTypeSignature.Named ->
                when (
                    val resolution =
                        typeResolver.resolveEnumStorage(
                            type,
                            physicalTypeClassifier.systemEnum,
                        )
                ) {
                    is DotNetClrEnumStorageResolution.Resolved ->
                        checkNotNull(
                            resolution.storageType.reducedArrayElementKind()
                        ).let(ReducedArrayElementType::Reduced)

                    DotNetClrEnumStorageResolution.NotEnum ->
                        ReducedArrayElementType.None

                    is DotNetClrEnumStorageResolution.Invalid,
                    is DotNetClrEnumStorageResolution.UnresolvedBaseType,
                    -> ReducedArrayElementType.Invalid(this, resolution)
                }

            else -> ReducedArrayElementType.None
        }

    private fun DotNetClrPrimitiveType.reducedArrayElementKind():
            ReducedArrayElementKind? =
        when (this) {
            DotNetClrPrimitiveType.INT8,
            DotNetClrPrimitiveType.UINT8,
            -> ReducedArrayElementKind.INT8

            DotNetClrPrimitiveType.INT16,
            DotNetClrPrimitiveType.UINT16,
            -> ReducedArrayElementKind.INT16

            DotNetClrPrimitiveType.INT32,
            DotNetClrPrimitiveType.UINT32,
            -> ReducedArrayElementKind.INT32

            DotNetClrPrimitiveType.INT64,
            DotNetClrPrimitiveType.UINT64,
            -> ReducedArrayElementKind.INT64

            DotNetClrPrimitiveType.NATIVE_INT,
            DotNetClrPrimitiveType.NATIVE_UINT,
            -> ReducedArrayElementKind.NATIVE_INT

            else -> null
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
                ).takeIf {
                    arguments.size == genericType.type.genericArity()
                }

            is DotNetClrResolvedTypeSignature.Modified ->
                unmodifiedType.toNominalView()

            else -> null
        }

    private fun DotNetClrResolvedTypeSignature.isArraySignature(): Boolean =
        this is DotNetClrResolvedTypeSignature.SzArray ||
                this is DotNetClrResolvedTypeSignature.Array

    private fun ReferenceArgumentClassification.asAssignabilityFailure(
        original: DotNetClrTypeAssignability.VariantConversionRequired,
    ): DotNetClrTypeAssignability =
        when (this) {
            is ReferenceArgumentClassification.Reference ->
                error("A reference argument is not a failure")

            ReferenceArgumentClassification.NotReference ->
                DotNetClrTypeAssignability.NotAssignable

            ReferenceArgumentClassification.Unsupported -> original
            is ReferenceArgumentClassification.Invalid ->
                DotNetClrTypeAssignability.InvalidTypeClassification(
                    type,
                    classification,
                )
        }

    private sealed interface AssignabilityPair {
        data class Nominal(
            val actual: DotNetClrResolvedTypeView,
            val expected: DotNetClrResolvedTypeView,
        ) : AssignabilityPair

        data class Signature(
            val actual: DotNetClrResolvedTypeSignature,
            val expected: DotNetClrResolvedTypeSignature,
        ) : AssignabilityPair
    }

    private class ResolutionCounter(
        var count: Int = 0,
    )

    private sealed interface ReferenceArgumentClassification {
        data class Reference(
            val type: DotNetClrResolvedTypeSignature,
        ) : ReferenceArgumentClassification

        data object NotReference : ReferenceArgumentClassification

        data object Unsupported : ReferenceArgumentClassification

        data class Invalid(
            val type: DotNetClrResolvedTypeSignature,
            val classification: DotNetClrPhysicalTypeClassification,
        ) : ReferenceArgumentClassification
    }

    private sealed interface ReducedArrayElementType {
        data class Reduced(
            val kind: ReducedArrayElementKind,
        ) : ReducedArrayElementType

        data object None : ReducedArrayElementType

        data class Invalid(
            val type: DotNetClrResolvedTypeSignature.Named,
            val resolution: DotNetClrEnumStorageResolution,
        ) : ReducedArrayElementType
    }

    private enum class ReducedArrayElementKind {
        INT8,
        INT16,
        INT32,
        INT64,
        NATIVE_INT,
    }

    private companion object {
        const val DEFAULT_RESOLUTION_LIMIT = 256
        const val MAX_RESOLUTION_LIMIT = 4096
    }
}
