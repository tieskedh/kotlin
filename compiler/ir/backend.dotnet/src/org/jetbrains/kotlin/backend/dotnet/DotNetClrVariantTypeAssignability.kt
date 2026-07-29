package org.jetbrains.kotlin.backend.dotnet

/**
 * Adds selected CLR generic-interface variance to exact nominal assignability.
 *
 * Array conversions, dependent generic parameters, and delegate variance remain explicit
 * [DotNetClrTypeAssignability.VariantConversionRequired] boundaries.
 */
class DotNetClrVariantTypeAssignabilityResolver(
    typeResolver: DotNetClrTypeResolver,
    private val physicalTypeClassifier: DotNetClrPhysicalTypeClassifier,
    private val primitiveTypes: DotNetClrPrimitiveTypeCatalog,
    resolutionLimit: Int = DEFAULT_RESOLUTION_LIMIT,
) {
    private val exactResolver =
        DotNetClrTypeAssignabilityResolver(typeResolver, resolutionLimit)
    private val resolutionLimit = resolutionLimit.also { limit ->
        require(limit in 1..MAX_RESOLUTION_LIMIT) {
            "CLR variant assignability resolution limit must be in 1..$MAX_RESOLUTION_LIMIT"
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
        val pair = AssignabilityPair(actual, expected)
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
                is DotNetClrTypeAssignability.InvalidHierarchy,
                is DotNetClrTypeAssignability.InheritanceCycle,
                is DotNetClrTypeAssignability.ResolutionLimitExceeded,
                -> if (firstInvalid == null) firstInvalid = result

                is DotNetClrTypeAssignability.VariantConversionRequired ->
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
        val classification = physicalTypeClassifier.classify(type)
        val classified =
            classification as? DotNetClrPhysicalTypeClassification.Classified
                ?: return ReferenceArgumentClassification.Invalid(
                    type,
                    classification,
                )
        if (classified.kind != DotNetClrPhysicalTypeKind.REFERENCE) {
            return ReferenceArgumentClassification.NotReference
        }
        return type.toNominalView()?.let(ReferenceArgumentClassification::Reference)
            ?: ReferenceArgumentClassification.Unsupported
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

    private data class AssignabilityPair(
        val actual: DotNetClrResolvedTypeView,
        val expected: DotNetClrResolvedTypeView,
    )

    private class ResolutionCounter(
        var count: Int = 0,
    )

    private sealed interface ReferenceArgumentClassification {
        data class Reference(
            val type: DotNetClrResolvedTypeView,
        ) : ReferenceArgumentClassification

        data object NotReference : ReferenceArgumentClassification

        data object Unsupported : ReferenceArgumentClassification

        data class Invalid(
            val type: DotNetClrResolvedTypeSignature,
            val classification: DotNetClrPhysicalTypeClassification,
        ) : ReferenceArgumentClassification
    }

    private companion object {
        const val DEFAULT_RESOLUTION_LIMIT = 256
        const val MAX_RESOLUTION_LIMIT = 4096
    }
}
