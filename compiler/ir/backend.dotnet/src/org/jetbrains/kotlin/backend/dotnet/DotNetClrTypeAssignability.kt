package org.jetbrains.kotlin.backend.dotnet

sealed interface DotNetClrTypeAssignability {
    data object Assignable : DotNetClrTypeAssignability

    data object NotAssignable : DotNetClrTypeAssignability

    data class VariantConversionRequired(
        val actualCandidates: List<DotNetClrResolvedTypeView>,
        val expected: DotNetClrResolvedTypeView,
    ) : DotNetClrTypeAssignability

    data class InvalidVariance(
        val actual: DotNetClrResolvedTypeView,
        val expected: DotNetClrResolvedTypeView,
        val failure: DotNetClrVarianceFailure,
    ) : DotNetClrTypeAssignability

    data class InvalidTypeClassification(
        val type: DotNetClrResolvedTypeSignature,
        val classification: DotNetClrPhysicalTypeClassification,
    ) : DotNetClrTypeAssignability

    data class UnsupportedSignatureConversion(
        val reason: DotNetClrSignatureConversionUnsupported,
        val actual: DotNetClrResolvedTypeSignature,
        val expected: DotNetClrResolvedTypeSignature,
    ) : DotNetClrTypeAssignability

    data class InvalidEnumStorage(
        val type: DotNetClrResolvedTypeSignature.Named,
        val resolution: DotNetClrEnumStorageResolution,
    ) : DotNetClrTypeAssignability

    data class InvalidHierarchy(
        val type: DotNetClrResolvedTypeView,
        val resolution: DotNetClrTypeHierarchyViewResolution.Invalid,
    ) : DotNetClrTypeAssignability

    data class InheritanceCycle(
        val type: DotNetClrResolvedTypeView,
    ) : DotNetClrTypeAssignability

    data class SignatureCycle(
        val type: DotNetClrResolvedTypeSignature,
    ) : DotNetClrTypeAssignability

    data class ResolutionLimitExceeded(
        val limit: Int,
        val type: DotNetClrResolvedTypeView,
    ) : DotNetClrTypeAssignability

    data class SignatureResolutionLimitExceeded(
        val limit: Int,
        val type: DotNetClrResolvedTypeSignature,
    ) : DotNetClrTypeAssignability
}

enum class DotNetClrVarianceFailure {
    OWNER_IS_NOT_INTERFACE_OR_DELEGATE,
    DELEGATE_IS_NOT_SEALED,
    GENERIC_PARAMETER_LAYOUT,
}

enum class DotNetClrSignatureConversionUnsupported {
    NOMINAL_TO_ARRAY,
    OPEN_GENERIC_PARAMETER,
    NON_NOMINAL_SIGNATURE,
}

/**
 * Checks exact nominal assignability through the imported CLR base/interface graph.
 *
 * This operation does not apply CLR generic variance, array conversions, boxing, or
 * generic-parameter constraints. It retains a reachable same-definition variant view as
 * [DotNetClrTypeAssignability.VariantConversionRequired] instead of manufacturing either a false
 * positive or a false violation.
 */
class DotNetClrTypeAssignabilityResolver(
    typeResolver: DotNetClrTypeResolver,
    private val resolutionLimit: Int = DEFAULT_RESOLUTION_LIMIT,
) {
    private val hierarchyResolver = DotNetClrTypeHierarchyViewResolver(typeResolver)

    init {
        require(resolutionLimit in 1..MAX_RESOLUTION_LIMIT) {
            "CLR assignability resolution limit must be in 1..$MAX_RESOLUTION_LIMIT"
        }
    }

    fun isAssignable(
        actual: DotNetClrResolvedTypeView,
        expected: DotNetClrResolvedTypeView,
    ): DotNetClrTypeAssignability {
        if (actual == expected) return DotNetClrTypeAssignability.Assignable

        val visited = mutableSetOf(actual)
        val queue = ArrayDeque<DotNetClrResolvedTypeView>()
        queue.addLast(actual)
        val adjacency =
            linkedMapOf<DotNetClrResolvedTypeView, List<DotNetClrResolvedTypeView>>()
        var resolvedCount = 0
        var firstInvalidHierarchy: DotNetClrTypeAssignability.InvalidHierarchy? = null
        val variantCandidates = linkedSetOf<DotNetClrResolvedTypeView>()
        if (actual.isPotentialVariantMatch(expected)) {
            variantCandidates += actual
        }

        while (queue.isNotEmpty()) {
            val type = queue.removeFirst()
            resolvedCount++
            if (resolvedCount > resolutionLimit) {
                return DotNetClrTypeAssignability.ResolutionLimitExceeded(
                    resolutionLimit,
                    type,
                )
            }
            val hierarchy = when (
                val resolution = hierarchyResolver.resolve(type)
            ) {
                is DotNetClrTypeHierarchyViewResolution.Resolved ->
                    resolution.hierarchy

                is DotNetClrTypeHierarchyViewResolution.Invalid -> {
                    if (firstInvalidHierarchy == null) {
                        firstInvalidHierarchy =
                            DotNetClrTypeAssignability.InvalidHierarchy(
                                type,
                                resolution,
                            )
                    }
                    null
                }
            }
            if (hierarchy == null) {
                continue
            }

            val supertypes = buildList {
                hierarchy.baseType?.let(::add)
                hierarchy.interfaces.mapTo(this) { implementation ->
                    implementation.interfaceType
                }
            }
            adjacency[type] = supertypes
            for (supertype in supertypes) {
                if (supertype == expected) return DotNetClrTypeAssignability.Assignable
                if (supertype.isPotentialVariantMatch(expected)) {
                    variantCandidates += supertype
                }
                if (!visited.add(supertype)) continue
                queue.addLast(supertype)
            }
        }
        firstInvalidHierarchy?.let { return it }
        findCycle(adjacency)?.let { cycle ->
            return DotNetClrTypeAssignability.InheritanceCycle(cycle)
        }
        if (variantCandidates.isNotEmpty()) {
            return DotNetClrTypeAssignability.VariantConversionRequired(
                variantCandidates.toList(),
                expected,
            )
        }
        return DotNetClrTypeAssignability.NotAssignable
    }

    private fun DotNetClrResolvedTypeView.isPotentialVariantMatch(
        expected: DotNetClrResolvedTypeView,
    ): Boolean {
        if (!type.hasSameIdentityAs(expected.type) ||
            arguments == expected.arguments ||
            arguments.size != expected.arguments.size
        ) {
            return false
        }
        val parameters = type.assembly.genericParameterDefinitions
            .filter { parameter -> parameter.owner == type.definition.handle }
            .sortedBy(DotNetClrGenericParameterDefinition::number)
        return parameters.size == arguments.size &&
                parameters.any { parameter ->
                    parameter.variance !=
                            DotNetClrGenericParameterVariance.INVARIANT
                }
    }

    private fun findCycle(
        adjacency: Map<DotNetClrResolvedTypeView, List<DotNetClrResolvedTypeView>>,
    ): DotNetClrResolvedTypeView? {
        val states = mutableMapOf<DotNetClrResolvedTypeView, VisitState>()
        for (root in adjacency.keys) {
            if (states[root] != null) continue
            val stack = ArrayDeque<CycleFrame>()
            states[root] = VisitState.ACTIVE
            stack.addLast(CycleFrame(root))
            while (stack.isNotEmpty()) {
                val frame = stack.last()
                val supertypes = adjacency[frame.type].orEmpty()
                if (frame.nextSupertypeIndex == supertypes.size) {
                    states[frame.type] = VisitState.COMPLETED
                    stack.removeLast()
                    continue
                }
                val supertype = supertypes[frame.nextSupertypeIndex++]
                when (states[supertype]) {
                    VisitState.ACTIVE -> return supertype
                    VisitState.COMPLETED -> Unit
                    null -> {
                        states[supertype] = VisitState.ACTIVE
                        stack.addLast(CycleFrame(supertype))
                    }
                }
            }
        }
        return null
    }

    private data class CycleFrame(
        val type: DotNetClrResolvedTypeView,
        var nextSupertypeIndex: Int = 0,
    )

    private enum class VisitState {
        ACTIVE,
        COMPLETED,
    }

    private companion object {
        const val DEFAULT_RESOLUTION_LIMIT = 256
        const val MAX_RESOLUTION_LIMIT = 4096
    }
}
