/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol

enum class DotNetGenericOwnerCompleteEmissionTypeKindSnapshot {
    NATURAL_INTERFACE,
    INTERFACE_SEMANTIC_CAPABILITY,
    IMPLEMENTATION_CLASS,
    CLASS_SEMANTIC_CAPABILITY,
}

enum class DotNetGenericOwnerCompleteEmissionMethodKindSnapshot {
    NATURAL_INTERFACE_SLOT,
    INTERFACE_SEMANTIC_CAPABILITY_SLOT,
    IMPLEMENTATION_TYPED_ENTRY,
    CLASS_SEMANTIC_CAPABILITY_SLOT,
    CLASS_SEMANTIC_CAPABILITY_DISPATCHER,
    INTERFACE_SEMANTIC_CAPABILITY_DISPATCHER,
}

enum class DotNetGenericOwnerCompleteEmissionMethodImplKindSnapshot {
    CLASS_SEMANTIC_CAPABILITY_IMPLEMENTATION,
    INTERFACE_SEMANTIC_CAPABILITY_IMPLEMENTATION,
}

enum class DotNetGenericOwnerCompleteEmissionTypeParameterVarianceSnapshot {
    INVARIANT,
    COVARIANT,
    CONTRAVARIANT,
}

data class DotNetGenericOwnerCompleteEmissionTypeDefSnapshot(
    val kind: DotNetGenericOwnerCompleteEmissionTypeKindSnapshot,
    val ownerName: String,
    val physicalAliasViews: List<DotNetGenericOwnerPhysicalValueShadowTypeDefView?>,
    val genericArity: Int,
    val category: DotNetGenericOwnerPhysicalNamedTypeCategory,
    val genericParameterVariances:
        List<DotNetGenericOwnerCompleteEmissionTypeParameterVarianceSnapshot>,
    val genericParameterConstraintCounts: List<Int>,
    val baseTypeEdgeCount: Int,
    val interfaceEdgeCount: Int,
)

data class DotNetGenericOwnerCompleteEmissionMethodDefSnapshot(
    val kind: DotNetGenericOwnerCompleteEmissionMethodKindSnapshot,
    val role: DotNetGenericOwnerMemberFamilyRole?,
    val ownerKind: DotNetGenericOwnerCompleteEmissionTypeKindSnapshot,
    val genericArity: Int,
    val genericParameterVariances:
        List<DotNetGenericOwnerCompleteEmissionTypeParameterVarianceSnapshot>,
    val genericParameterConstraintCounts: List<Int>,
)

data class DotNetGenericOwnerCompleteEmissionMethodImplSnapshot(
    val kind: DotNetGenericOwnerCompleteEmissionMethodImplKindSnapshot,
    val implementingTypeKind: DotNetGenericOwnerCompleteEmissionTypeKindSnapshot,
    val bodyMethodKind: DotNetGenericOwnerCompleteEmissionMethodKindSnapshot,
    val declarationOwnerTypeKind: DotNetGenericOwnerCompleteEmissionTypeKindSnapshot,
    val declarationMethodKind: DotNetGenericOwnerCompleteEmissionMethodKindSnapshot,
)

data class DotNetGenericOwnerCompleteEmissionRowsComparisonSnapshot(
    val status: DotNetGenericOwnerPhysicalMethodDefEmissionComparisonStatus,
    val expectedCount: Int,
    val actualCount: Int?,
    val diagnostics: List<String>,
)

/** One bounded certificate; it is deliberately not a shared sealed-emission epoch. */
data class DotNetGenericOwnerCompleteEmissionFamilyComparisonSnapshot(
    val scope: DotNetIlEmissionScope,
    val ownerName: String,
    val logicalMemberName: String,
    val implementationOwnerName: String,
    val status: DotNetGenericOwnerPhysicalMethodDefEmissionComparisonStatus,
    val typeDefs: DotNetGenericOwnerCompleteEmissionRowsComparisonSnapshot,
    val methodDefs: DotNetGenericOwnerCompleteEmissionRowsComparisonSnapshot,
    val methodImpls: DotNetGenericOwnerCompleteEmissionRowsComparisonSnapshot,
    val expectedTypeDefs: List<DotNetGenericOwnerCompleteEmissionTypeDefSnapshot>,
    val expectedMethodDefs: List<DotNetGenericOwnerCompleteEmissionMethodDefSnapshot>,
    val expectedMethodImpls: List<DotNetGenericOwnerCompleteEmissionMethodImplSnapshot>,
) {
    init {
        require(status == joinDotNetGenericOwnerCompleteEmissionComparisonStatuses(
            listOf(typeDefs.status, methodDefs.status, methodImpls.status),
        )) { "a complete-emission snapshot must retain the fail-closed component join" }
    }
}

/** Public diagnostic vocabulary for an exact final TypeDef accessibility mask. */
enum class DotNetGenericOwnerSealedEmissionTypeDefVisibilitySnapshot {
    PUBLIC,
    NOT_PUBLIC,
    NESTED_PUBLIC,
    NESTED_PRIVATE,
    NESTED_ASSEMBLY,
    NESTED_FAMILY,
}

data class DotNetGenericOwnerSealedEmissionTypeDefFlagsSnapshot(
    val visibility: DotNetGenericOwnerSealedEmissionTypeDefVisibilitySnapshot,
    val isAutoLayout: Boolean,
    val isAnsi: Boolean,
    val isInterface: Boolean,
    val isAbstract: Boolean,
    val isSealed: Boolean,
    val isBeforeFieldInit: Boolean,
)

data class DotNetGenericOwnerSealedEmissionMethodDefFlagsSnapshot(
    val visibility: DotNetGenericOwnerPhysicalMethodDefEmissionVisibility,
    val isInstance: Boolean,
    val isVirtual: Boolean,
    val isNewSlot: Boolean,
    val isAbstract: Boolean,
    val isFinal: Boolean,
    val isHideBySig: Boolean,
    val isSpecialName: Boolean,
    val isRuntimeSpecialName: Boolean,
)

data class DotNetGenericOwnerSealedEmissionTypeDefSnapshot(
    val kind: DotNetGenericOwnerCompleteEmissionTypeKindSnapshot,
    val physicalPath: List<String>,
    val flags: DotNetGenericOwnerSealedEmissionTypeDefFlagsSnapshot,
    val structural: DotNetGenericOwnerCompleteEmissionTypeDefSnapshot,
)

data class DotNetGenericOwnerSealedEmissionMethodDefSnapshot(
    val kind: DotNetGenericOwnerCompleteEmissionMethodKindSnapshot,
    val physicalName: String,
    val physicalGenericParameterNames: List<String>,
    val flags: DotNetGenericOwnerSealedEmissionMethodDefFlagsSnapshot,
    val structural: DotNetGenericOwnerCompleteEmissionMethodDefSnapshot,
    val header: DotNetGenericOwnerPhysicalMethodDefEmissionHeaderSnapshot,
)

/** One fail-closed family-scoped outcome at the sealed-emission authority epoch. */
data class DotNetGenericOwnerSealedEmissionFamilySnapshot(
    val scope: DotNetIlEmissionScope,
    val ownerName: String,
    val logicalMemberName: String,
    val implementationOwnerName: String,
    val status: DotNetGenericOwnerPhysicalMethodDefEmissionComparisonStatus,
    val diagnostics: List<String>,
    val typeDefs: List<DotNetGenericOwnerSealedEmissionTypeDefSnapshot>,
    val methodDefs: List<DotNetGenericOwnerSealedEmissionMethodDefSnapshot>,
    val methodImpls: List<DotNetGenericOwnerCompleteEmissionMethodImplSnapshot>,
) {
    init {
        require(ownerName.isNotEmpty() && logicalMemberName.isNotEmpty() &&
                implementationOwnerName.isNotEmpty() && diagnostics.all(String::isNotEmpty)) {
            "a sealed-emission family outcome requires non-empty labels and diagnostics"
        }
        if (status == DotNetGenericOwnerPhysicalMethodDefEmissionComparisonStatus.MATCH) {
            require(diagnostics.isEmpty() &&
                    typeDefs.size == DotNetGenericOwnerCompleteEmissionTypeKindSnapshot.entries.size &&
                    typeDefs.map { row -> row.kind }.toSet() ==
                    DotNetGenericOwnerCompleteEmissionTypeKindSnapshot.entries.toSet() &&
                    typeDefs.all { row -> row.kind == row.structural.kind } &&
                    methodDefs.size == DotNetGenericOwnerCompleteEmissionMethodKindSnapshot.entries.size &&
                    methodDefs.map { row -> row.kind }.toSet() ==
                    DotNetGenericOwnerCompleteEmissionMethodKindSnapshot.entries.toSet() &&
                    methodDefs.all { row -> row.kind == row.structural.kind } &&
                    methodImpls.size == DotNetGenericOwnerCompleteEmissionMethodImplKindSnapshot.entries.size &&
                    methodImpls.map { row -> row.kind }.toSet() ==
                    DotNetGenericOwnerCompleteEmissionMethodImplKindSnapshot.entries.toSet()) {
                "a matching sealed-emission family requires every actual physical row exactly once"
            }
        } else {
            require(diagnostics.isNotEmpty() && typeDefs.isEmpty() && methodDefs.isEmpty() &&
                    methodImpls.isEmpty()) {
                "an unsealed family must retain its failure and publish no authoritative rows"
            }
        }
    }
}

enum class DotNetGenericOwnerSemanticEquivalenceForwardingEdgeKind {
    CLASS_DISPATCHER_TO_TYPED_ENTRY,
    INTERFACE_DISPATCHER_TO_CLASS_DISPATCHER,
}

/** One role-bound edge whose physical endpoints were observed in the final emitter. */
data class DotNetGenericOwnerSemanticEquivalenceForwardingEdgeSnapshot(
    val kind: DotNetGenericOwnerSemanticEquivalenceForwardingEdgeKind,
    val bodyMethodKind: DotNetGenericOwnerCompleteEmissionMethodKindSnapshot,
    val targetMethodKind: DotNetGenericOwnerCompleteEmissionMethodKindSnapshot,
    val methodGenericArity: Int,
    val isVirtual: Boolean,
) {
    init {
        require(methodGenericArity >= 0) {
            "a semantic-equivalence forwarding edge requires a non-negative MethodSpec arity"
        }
    }
}

/**
 * Final body evidence remains orthogonal to the 4/6/2 declaration-row seal. A missing body edge
 * is unavailable; a malformed or duplicate claim is conflict. Only [Known] may seed a later
 * producer semantic-equivalence certificate.
 */
sealed interface DotNetGenericOwnerSemanticEquivalenceForwardingEvidence {
    data class Known(
        val edges: List<DotNetGenericOwnerSemanticEquivalenceForwardingEdgeSnapshot>,
    ) : DotNetGenericOwnerSemanticEquivalenceForwardingEvidence {
        init {
            require(edges.size == DotNetGenericOwnerSemanticEquivalenceForwardingEdgeKind.entries.size &&
                    edges.map { edge -> edge.kind }.toSet() ==
                    DotNetGenericOwnerSemanticEquivalenceForwardingEdgeKind.entries.toSet() &&
                    edges.all { edge ->
                        when (edge.kind) {
                            DotNetGenericOwnerSemanticEquivalenceForwardingEdgeKind
                                .CLASS_DISPATCHER_TO_TYPED_ENTRY ->
                                edge.bodyMethodKind ==
                                    DotNetGenericOwnerCompleteEmissionMethodKindSnapshot
                                        .CLASS_SEMANTIC_CAPABILITY_DISPATCHER &&
                                        edge.targetMethodKind ==
                                    DotNetGenericOwnerCompleteEmissionMethodKindSnapshot
                                        .IMPLEMENTATION_TYPED_ENTRY
                            DotNetGenericOwnerSemanticEquivalenceForwardingEdgeKind
                                .INTERFACE_DISPATCHER_TO_CLASS_DISPATCHER ->
                                edge.bodyMethodKind ==
                                    DotNetGenericOwnerCompleteEmissionMethodKindSnapshot
                                        .INTERFACE_SEMANTIC_CAPABILITY_DISPATCHER &&
                                        edge.targetMethodKind ==
                                    DotNetGenericOwnerCompleteEmissionMethodKindSnapshot
                                        .CLASS_SEMANTIC_CAPABILITY_DISPATCHER
                        }
                    }
            ) {
                "known semantic-equivalence evidence requires both exact forwarding-role edges"
            }
        }
    }

    data class Unavailable(val reason: String) :
        DotNetGenericOwnerSemanticEquivalenceForwardingEvidence {
        init {
            require(reason.isNotEmpty()) { "unavailable forwarding evidence requires a reason" }
        }
    }

    data class Conflict(val reason: String) :
        DotNetGenericOwnerSemanticEquivalenceForwardingEvidence {
        init {
            require(reason.isNotEmpty()) { "conflicting forwarding evidence requires a reason" }
        }
    }
}

internal data class DotNetGenericOwnerCompleteEmissionFamilyProducts(
    val comparison: DotNetGenericOwnerCompleteEmissionFamilyComparisonSnapshot,
    val sealed: DotNetGenericOwnerSealedEmissionFamilySnapshot,
    val producerSealedFamilyBody: DotNetProducerGenericOwnerSealedFamilyBody?,
    val semanticEquivalenceForwardingEvidence:
        DotNetGenericOwnerSemanticEquivalenceForwardingEvidence,
    private val logicalMember: IrSimpleFunctionSymbol,
    private val implementationMember: IrSimpleFunctionSymbol,
) {
    /** Identity-safe correlation with the semantic-equivalence obligation selected before emission. */
    fun matchesSemanticEquivalenceObligation(
        obligation: Pair<IrSimpleFunctionSymbol, IrSimpleFunctionSymbol>,
    ): Boolean = logicalMember === obligation.first && implementationMember === obligation.second

    sealed interface ProducerPublication {
        data object Unavailable : ProducerPublication
        data class Published(
            val publication: DotNetProducerGenericOwnerSealedFamilyPublication,
        ) : ProducerPublication
        data class Conflict(val reason: String) : ProducerPublication
    }

    /** Joins final physical evidence to exact pre-lowering KLIB keys without using CLR names. */
    fun producerSealedFamilyPublication(
        preLoweringDeclarationKeys: Map<IrDeclaration, String>,
    ): ProducerPublication {
        val body = producerSealedFamilyBody ?: return ProducerPublication.Unavailable
        val implementationOwner = implementationMember.owner.parent as? IrClass
            ?: return ProducerPublication.Conflict(
                "a producer-sealed implementation member has no class owner",
            )
        val implementationOwnerKey = preLoweringDeclarationKeys[implementationOwner]
        val implementationMemberKey = preLoweringDeclarationKeys[implementationMember.owner]
        if (implementationOwnerKey == null && implementationMemberKey == null) {
            return ProducerPublication.Unavailable
        }
        if (implementationOwnerKey == null || implementationMemberKey == null) {
            return ProducerPublication.Conflict(
                "an exported producer-sealed implementation lost either its owner or member logical key",
            )
        }
        val logicalInterfaceMemberKey = preLoweringDeclarationKeys[logicalMember.owner]
            ?: return ProducerPublication.Conflict(
                "an exported producer-sealed implementation lost its logical interface-member key",
            )
        val key = DotNetProducerGenericOwnerSealedFamilyKey(
            logicalInterfaceMemberKey = logicalInterfaceMemberKey,
            implementationOwnerKey = implementationOwnerKey,
            implementationMemberKey = implementationMemberKey,
        )
        return ProducerPublication.Published(body.publish(key))
    }
}

internal data class DotNetGenericOwnerCompleteEmissionScopeObservations(
    val scope: DotNetIlEmissionScope,
    val typeDefs: List<DotNetGenericOwnerPhysicalTypeDefEmissionObservation>,
    val methodDefs: List<DotNetGenericOwnerPhysicalMethodDefHeaderObservation>,
    val methodImpls: List<DotNetGenericOwnerPhysicalMethodImplObservation>,
)

/**
 * Seals one declaration-owned natural slot without requiring the wider implementation-family J.
 * The join uses logical symbols and emitter identities; paths and names are validated facts, never
 * the selectors which create the binding.
 */
internal fun inspectDotNetProducerGenericOwnerNaturalMethodDefPublication(
    logicalOwnerKey: String,
    logicalMemberKey: String,
    owner: IrClass,
    source: IrSimpleFunction,
    family: DotNetPhysicalDeclaration.PublishedGenericInterfaceFamily,
    member: DotNetPublishedGenericInterfaceMemberContract,
    logicalParameterDomains: List<DotNetGenericOwnerPhysicalSlotDomain>,
    logicalResultDomain: DotNetGenericOwnerPhysicalSlotDomain?,
    current: DotNetGenericOwnerCompleteEmissionScopeObservations,
    otherScopes: List<DotNetGenericOwnerCompleteEmissionScopeObservations>,
): DotNetGenericOwnerPhysicalBindingResult<DotNetProducerGenericOwnerNaturalMethodDefPublication> {
    if (member.role != DotNetPublishedGenericInterfaceMemberRole.PRODUCER &&
        member.resultLayout != DotNetPublishedGenericInterfaceMemberResultLayout.SPLIT_NULLABLE
    ) {
        return DotNetGenericOwnerPhysicalBindingResult.Unavailable
    }
    if (member.logicalMemberKey != logicalMemberKey || family.contract.logicalOwnerKey != logicalOwnerKey ||
        source.parent !== owner
    ) {
        return DotNetGenericOwnerPhysicalBindingResult.Conflict(
            "a natural MethodDef publication crossed its exact pre-lowering owner/member join",
        )
    }

    val expectedOwner = DotNetGenericOwnerPhysicalTypeDefIdentity.Local(
        owner.symbol,
        DotNetGenericInterfaceView.DECLARED,
    )
    val expectedMethod = DotNetGenericOwnerPhysicalMethodDefIdentity.Local(
        source.symbol,
        DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY,
    )
    fun DotNetGenericOwnerPhysicalTypeDefEmissionObservation.claimsExpectedOwner(): Boolean =
        claimedAliases.any(expectedOwner::sameLocalTypeIdentityAs)
    fun DotNetGenericOwnerPhysicalMethodDefHeaderObservation.claimsExpectedMethod(): Boolean =
        physicalMethodIdentity?.sameLocalMethodIdentityAs(expectedMethod) == true

    if (otherScopes.any { scope ->
            scope.typeDefs.any { observation -> observation.claimsExpectedOwner() } ||
                    scope.methodDefs.any { observation -> observation.claimsExpectedMethod() }
        }
    ) {
        return DotNetGenericOwnerPhysicalBindingResult.Conflict(
            "a natural MethodDef declaration was observed in more than one physical emission scope",
        )
    }
    val typeCandidates = current.typeDefs.filter { observation -> observation.claimsExpectedOwner() }
    if (typeCandidates.size != 1) {
        return DotNetGenericOwnerPhysicalBindingResult.Conflict(
            "a natural MethodDef declaration requires exactly one final natural TypeDef observation",
        )
    }
    val typeObservation = typeCandidates.single()
    val observedOwner = when (val physicalType = typeObservation.physicalType) {
        is DotNetGenericOwnerObservedMethodDefOwner.Local -> physicalType.typeDef
        is DotNetGenericOwnerObservedMethodDefOwner.Unbindable ->
            return DotNetGenericOwnerPhysicalBindingResult.Conflict(physicalType.reason)
    }
    if (observedOwner.aliases.none(expectedOwner::sameLocalTypeIdentityAs) ||
        observedOwner.category != DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE ||
        observedOwner.genericArity != family.contract.genericArity ||
        typeObservation.physicalTypePath != family.ownerPath ||
        typeObservation.genericParameters.map { parameter -> parameter.variance } !=
                family.naturalTypeParameterVariances
    ) {
        return DotNetGenericOwnerPhysicalBindingResult.Conflict(
            "the final natural TypeDef disagrees with its producer-published H authority",
        )
    }

    val methodIdentityCandidates = current.methodDefs.filter { observation ->
        observation.claimsExpectedMethod()
    }
    if (methodIdentityCandidates.isEmpty()) {
        // This bounded declaration has no observed natural slot. Other logical members are not proof
        // that a natural MethodDef exists, so leave N unavailable instead of inferring an owner or row.
        return DotNetGenericOwnerPhysicalBindingResult.Unavailable
    }
    val methodCandidates = methodIdentityCandidates.filter { observation ->
        (observation.physicalMethodOwner as? DotNetGenericOwnerObservedMethodDefOwner.Local)
            ?.typeDef?.physicalKey == observedOwner.physicalKey
    }
    if (methodCandidates.isEmpty()) {
        return DotNetGenericOwnerPhysicalBindingResult.Conflict(
            "the bounded natural MethodDef identity was emitted only on another physical owner",
        )
    }
    if (methodCandidates.size != 1) {
        return DotNetGenericOwnerPhysicalBindingResult.Conflict(
            "a natural MethodDef declaration requires exactly one final MethodDef on its natural TypeDef",
        )
    }
    if (methodIdentityCandidates.any { candidate -> candidate !in methodCandidates }) {
        return DotNetGenericOwnerPhysicalBindingResult.Conflict(
            "the bounded natural MethodDef identity was also emitted on another physical owner",
        )
    }
    val methodObservation = methodCandidates.single()
    val allocator = EmissionIdentityAllocator()
    allocator.expectedTypeAliasGroup(
        observedOwner.aliases,
        observedOwner.genericArity,
        observedOwner.category,
    )
    val methodKey = allocator.method(expectedMethod)

    fun carrier(
        value: DotNetGenericOwnerObservedMethodCarrier,
        position: String,
    ): DotNetGenericOwnerPhysicalBindingResult<DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape> {
        return when (val conversion = value.toActualCarrierShapeForDeclarationSeal(
            allocator,
            expectedMethod,
        )) {
            is DotNetGenericOwnerPhysicalBindingResult.Bound -> conversion
            is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                DotNetGenericOwnerPhysicalBindingResult.Conflict("$position: ${conversion.reason}")
            DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                DotNetGenericOwnerPhysicalBindingResult.Conflict(
                    "$position: the final carrier has no self-contained declaration binding",
                )
        }
    }

    val typeGenericParameters = mutableListOf<DotNetGenericOwnerCompleteEmissionGenericParameterRow>()
    typeObservation.genericParameters.forEach { parameter ->
        val constraints = mutableListOf<DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape>()
        parameter.constraints.forEach { constraint ->
            when (val converted = carrier(constraint, "natural TypeDef GenericParam constraint")) {
                is DotNetGenericOwnerPhysicalBindingResult.Bound -> constraints += converted.value
                is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return converted
                DotNetGenericOwnerPhysicalBindingResult.Unavailable -> error("carrier conversion is total")
            }
        }
        typeGenericParameters += DotNetGenericOwnerCompleteEmissionGenericParameterRow(
            parameter.variance,
            constraints,
        )
    }
    val directEdges = mutableListOf<DotNetGenericOwnerCompleteEmissionTypeDefEdgeRow>()
    typeObservation.directSupertypes.forEach { edge ->
        when (val converted = carrier(edge.target, "natural TypeDef direct edge")) {
            is DotNetGenericOwnerPhysicalBindingResult.Bound ->
                directEdges += DotNetGenericOwnerCompleteEmissionTypeDefEdgeRow(edge.kind, converted.value)
            is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return converted
            DotNetGenericOwnerPhysicalBindingResult.Unavailable -> error("carrier conversion is total")
        }
    }
    val naturalType = DotNetGenericOwnerSealedEmissionTypeDefRow(
        DotNetGenericOwnerCompleteEmissionTypeDefRow(
            identityKey = when (val key = allocator.actualType(observedOwner)) {
                is EmissionIdentityAllocator.ActualType.Bound -> key.key
                is EmissionIdentityAllocator.ActualType.Conflict ->
                    return DotNetGenericOwnerPhysicalBindingResult.Conflict(key.reason)
            },
            aliases = observedOwner.aliases.map(allocator::alias),
            genericArity = observedOwner.genericArity,
            category = observedOwner.category,
            genericParameters = typeGenericParameters,
            directEdges = directEdges,
        ),
        typeObservation.physicalTypePath,
        typeObservation.flags,
    )

    val methodGenericParameters = mutableListOf<DotNetGenericOwnerCompleteEmissionGenericParameterRow>()
    methodObservation.genericParameters.forEach { parameter ->
        if (parameter.variance != DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT) {
            return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                "a natural MethodDef GenericParam has illegal declaration-site variance",
            )
        }
        val constraints = mutableListOf<DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape>()
        parameter.constraints.forEach { constraint ->
            when (val converted = carrier(constraint, "natural MethodDef GenericParam constraint")) {
                is DotNetGenericOwnerPhysicalBindingResult.Bound -> constraints += converted.value
                is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return converted
                DotNetGenericOwnerPhysicalBindingResult.Unavailable -> error("carrier conversion is total")
            }
        }
        methodGenericParameters += DotNetGenericOwnerCompleteEmissionGenericParameterRow(
            parameter.variance,
            constraints,
        )
    }
    val header = when (val evidence = buildDotNetGenericOwnerActualMethodDefEmissionHeaderShape(
        allocator,
        expectedMethod,
        methodObservation,
        observedOwner,
    )) {
        is DotNetGenericOwnerPhysicalBindingResult.Bound -> evidence.value
        is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
            return DotNetGenericOwnerPhysicalBindingResult.Conflict(evidence.reason)
        DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
            return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                "the final natural MethodDef header has no self-contained declaration binding",
            )
    }
    if (logicalParameterDomains.size != header.ordinaryParameterCarriers.size) {
        return DotNetGenericOwnerPhysicalBindingResult.Conflict(
            "the natural MethodDef's logical parameter domains disagree with its final physical header",
        )
    }
    val splitNullable = header.result is
            DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.SplitNullable
    if (splitNullable !=
        (member.resultLayout == DotNetPublishedGenericInterfaceMemberResultLayout.SPLIT_NULLABLE)
    ) {
        return DotNetGenericOwnerPhysicalBindingResult.Conflict(
            "the H member contract disagrees with the final natural MethodDef result layout",
        )
    }
    if ((header.result != DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.Void) !=
        (logicalResultDomain != null)
    ) {
        return DotNetGenericOwnerPhysicalBindingResult.Conflict(
            "the natural MethodDef's logical result domain disagrees with its final physical header",
        )
    }
    val naturalMethod = DotNetProducerGenericOwnerSealedMethodDef(
        DotNetProducerGenericOwnerSealedMethodDefRole.NATURAL_INTERFACE_SLOT,
        DotNetGenericOwnerSealedEmissionMethodDefRow(
            DotNetGenericOwnerCompleteEmissionMethodDefRow(
                methodKey,
                header,
                methodGenericParameters,
            ),
            methodObservation.physicalMethodName,
            methodObservation.genericParameters.map { parameter -> parameter.physicalName },
            methodObservation.visibility,
            methodObservation.dispatch,
            methodObservation.isHideBySig,
            methodObservation.isSpecialName,
            methodObservation.isRuntimeSpecialName,
        ),
        logicalParameterDomains,
        logicalResultDomain,
    )
    if (!hasBoundedDotNetProducerGenericOwnerNaturalMethodDefGrammar(naturalType, naturalMethod)) {
        return DotNetGenericOwnerPhysicalBindingResult.Unavailable
    }
    fun DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.isDeclarationLocal(): Boolean = when (this) {
        is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.Leaf -> true
        is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.OwnerParameter ->
            binder == naturalType.structural.identityKey
        is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.MethodParameter -> binder == methodKey
        is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.Construction ->
            definition == naturalType.structural.identityKey && arguments.all { argument ->
                argument.isDeclarationLocal()
            }
        is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.SzArray -> element.isDeclarationLocal()
        is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.ByReference -> element.isDeclarationLocal()
        DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.Other -> false
    }
    val headerCarriers = buildList {
        header.receiverCarrier?.let(::add)
        addAll(header.ordinaryParameterCarriers)
        when (val result = header.result) {
            is DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.Direct -> add(result.carrier)
            is DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.SplitNullable -> add(result.payload)
            DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.Void -> Unit
        }
    }
    // ABI 63 seals the bounded root declaration grammar. A carrier which names another local
    // TypeDef needs a portable path/category projection rather than a fabricated transitive seal;
    // leave that later family unrecorded instead of weakening or guessing this declaration fact.
    if (naturalType.structural.directEdges.isNotEmpty() ||
        naturalType.structural.genericParameters.any { parameter -> parameter.constraints.isNotEmpty() } ||
        naturalMethod.row.structural.genericParameters.any { parameter -> parameter.constraints.isNotEmpty() } ||
        headerCarriers.any { carrier -> !carrier.isDeclarationLocal() }
    ) {
        return DotNetGenericOwnerPhysicalBindingResult.Unavailable
    }
    val publication = DotNetProducerGenericOwnerNaturalMethodDefPublication(
        logicalOwnerKey,
        logicalMemberKey,
        naturalType,
        naturalMethod,
    )
    return when (val inspection = inspectDotNetProducerGenericOwnerNaturalMethodDef(publication)) {
        is DotNetGenericOwnerPhysicalBindingResult.Bound -> inspection
        is DotNetGenericOwnerPhysicalBindingResult.Conflict -> inspection
        DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
            DotNetGenericOwnerPhysicalBindingResult.Conflict(
                "a final natural MethodDef publication unexpectedly lacked declaration authority",
            )
    }
}

/**
 * Seals one concrete class MethodDef independently from the complete semantic `J` family.
 *
 * Selection is exclusively the pre-lowering implementation/root symbols. The physical path,
 * MethodDef name, signature and flags are facts copied from the successful final-emission
 * transaction; none of them participates in choosing the candidate.
 */
internal fun inspectDotNetProducerGenericOwnerImplementationMethodDefPublication(
    logicalInterfaceMemberKey: String,
    implementationOwnerKey: String,
    implementationMemberKey: String,
    naturalOwner: IrClass,
    naturalSource: IrSimpleFunction,
    implementationOwner: IrClass,
    implementationSource: IrSimpleFunction,
    naturalDeclaration: DotNetPhysicalDeclaration.GenericOwnerNaturalMethodDef,
    current: DotNetGenericOwnerCompleteEmissionScopeObservations,
    otherScopes: List<DotNetGenericOwnerCompleteEmissionScopeObservations>,
): DotNetGenericOwnerPhysicalBindingResult<DotNetPhysicalDeclaration.GenericOwnerImplementationMethodDef> {
    if (naturalDeclaration.logicalMemberKey != logicalInterfaceMemberKey ||
        naturalSource.parent !== naturalOwner || implementationSource.parent !== implementationOwner
    ) {
        return DotNetGenericOwnerPhysicalBindingResult.Conflict(
            "an implementation MethodDef publication crossed its exact pre-lowering declaration join",
        )
    }

    val expectedImplementationOwner = DotNetGenericOwnerPhysicalTypeDefIdentity.Local(
        implementationOwner.symbol,
        view = null,
    )
    val expectedNaturalOwner = DotNetGenericOwnerPhysicalTypeDefIdentity.Local(
        naturalOwner.symbol,
        DotNetGenericInterfaceView.DECLARED,
    )
    val expectedMethod = DotNetGenericOwnerPhysicalMethodDefIdentity.Local(
        implementationSource.symbol,
        DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY,
    )
    val expectedNaturalMethod = DotNetGenericOwnerPhysicalMethodDefIdentity.Local(
        naturalSource.symbol,
        DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY,
    )
    fun DotNetGenericOwnerPhysicalTypeDefEmissionObservation.claimsImplementationOwner(): Boolean =
        claimedAliases.any(expectedImplementationOwner::sameLocalTypeIdentityAs)
    fun DotNetGenericOwnerPhysicalMethodDefHeaderObservation.claimsImplementationMethod(): Boolean =
        physicalMethodIdentity?.sameLocalMethodIdentityAs(expectedMethod) == true

    if (otherScopes.any { scope ->
            scope.typeDefs.any { observation -> observation.claimsImplementationOwner() } ||
                    scope.methodDefs.any { observation -> observation.claimsImplementationMethod() }
        }
    ) {
        return DotNetGenericOwnerPhysicalBindingResult.Conflict(
            "an implementation MethodDef declaration was observed in more than one emission scope",
        )
    }
    val typeObservation = current.typeDefs.singleOrNull { observation ->
        observation.claimsImplementationOwner()
    } ?: return DotNetGenericOwnerPhysicalBindingResult.Conflict(
        "an implementation MethodDef requires exactly one final implementation TypeDef observation",
    )
    val observedOwner = when (val physicalType = typeObservation.physicalType) {
        is DotNetGenericOwnerObservedMethodDefOwner.Local -> physicalType.typeDef
        is DotNetGenericOwnerObservedMethodDefOwner.Unbindable ->
            return DotNetGenericOwnerPhysicalBindingResult.Conflict(physicalType.reason)
    }
    if (observedOwner.aliases.none(expectedImplementationOwner::sameLocalTypeIdentityAs) ||
        observedOwner.category != DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS ||
        observedOwner.genericArity <= 0 || typeObservation.physicalTypePath.size != 1 ||
        typeObservation.flags.visibility != DotNetIlRawTypeDefVisibility.PUBLIC ||
        typeObservation.flags.isInterface || typeObservation.flags.isAbstract ||
        typeObservation.flags.isSealed ||
        typeObservation.genericParameters.size != observedOwner.genericArity ||
        typeObservation.genericParameters.any { parameter ->
            parameter.variance != DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT ||
                    parameter.constraints.isNotEmpty()
        }
    ) {
        return DotNetGenericOwnerPhysicalBindingResult.Unavailable
    }

    val methodIdentityCandidates = current.methodDefs.filter { observation ->
        observation.claimsImplementationMethod()
    }
    if (methodIdentityCandidates.isEmpty()) return DotNetGenericOwnerPhysicalBindingResult.Unavailable
    val methodCandidates = methodIdentityCandidates.filter { observation ->
        (observation.physicalMethodOwner as? DotNetGenericOwnerObservedMethodDefOwner.Local)
            ?.typeDef?.physicalKey == observedOwner.physicalKey
    }
    if (methodCandidates.size != 1 || methodIdentityCandidates.size != 1) {
        return DotNetGenericOwnerPhysicalBindingResult.Conflict(
            "an implementation MethodDef requires exactly one final MethodDef on its exact class owner",
        )
    }
    val methodObservation = methodCandidates.single()
    if (methodObservation.visibility != DotNetIlRawMethodDefVisibility.PUBLIC ||
        !methodObservation.dispatch.isInstance || !methodObservation.dispatch.isVirtual ||
        methodObservation.dispatch.isAbstract || methodObservation.dispatch.isFinal ||
        !methodObservation.isHideBySig || methodObservation.isSpecialName ||
        methodObservation.isRuntimeSpecialName || methodObservation.genericArity != 0 ||
        methodObservation.genericParameters.isNotEmpty()
    ) {
        return DotNetGenericOwnerPhysicalBindingResult.Unavailable
    }
    val naturalConstructionCandidates = typeObservation.directSupertypes.filter { edge ->
        edge.kind == DotNetGenericOwnerDirectSupertypeKind.INTERFACE &&
                (edge.target as? DotNetGenericOwnerObservedMethodCarrier.LocalConstruction)
                    ?.definition?.aliases?.any(expectedNaturalOwner::sameLocalTypeIdentityAs) == true
    }
    if (naturalConstructionCandidates.isEmpty()) {
        // The bounded M grammar records only one direct construction of N. Transitive or
        // otherwise unrepresented interface authority remains a later proof, not a conflict in
        // an otherwise valid producer library.
        return DotNetGenericOwnerPhysicalBindingResult.Unavailable
    }
    if (naturalConstructionCandidates.size != 1) {
        return DotNetGenericOwnerPhysicalBindingResult.Conflict(
            "an implementation MethodDef requires one exact direct construction of its N owner",
        )
    }
    val naturalConstruction = naturalConstructionCandidates.single().target as
            DotNetGenericOwnerObservedMethodCarrier.LocalConstruction
    if (current.methodImpls.any { observation ->
            val implementingOwner = observation.implementingType as?
                    DotNetGenericOwnerObservedMethodDefOwner.Local
            implementingOwner?.typeDef?.aliases?.any(
                expectedImplementationOwner::sameLocalTypeIdentityAs,
            ) == true && (
                    observation.bodyFunction === implementationSource.symbol ||
                            observation.bodyIdentity?.sameLocalMethodIdentityAs(expectedMethod) == true ||
                            observation.declarationOwner == naturalConstruction ||
                            observation.declarationFunction === naturalSource.symbol ||
                            observation.declarationIdentity
                                ?.sameLocalMethodIdentityAs(expectedNaturalMethod) == true
                    )
        }
    ) {
        // ABI 64 M records only ordinary implicit dispatch. Neither redirecting the class body
        // nor any member of the selected N construction may be silently omitted from this seal.
        return DotNetGenericOwnerPhysicalBindingResult.Unavailable
    }
    val naturalArity = naturalDeclaration.publication().naturalType.structural.genericArity
    if (naturalConstruction.arguments.size != naturalArity) {
        return DotNetGenericOwnerPhysicalBindingResult.Conflict(
            "an implementation MethodDef has an arity-mismatched natural-interface construction",
        )
    }
    val naturalArguments = naturalConstruction.arguments.map { argument ->
        val parameter = argument as? DotNetGenericOwnerObservedMethodCarrier.OwnerParameter
            ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
        if (parameter.binder.physicalKey != observedOwner.physicalKey ||
            parameter.index !in 0 until observedOwner.genericArity
        ) {
            return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                "an implementation MethodDef interface construction has an unbound owner parameter",
            )
        }
        DotNetGenericOwnerPhysicalTypeExpressionRecord.ownerParameter(parameter.index)
    }

    val allocator = EmissionIdentityAllocator()
    allocator.expectedTypeAliasGroup(
        observedOwner.aliases,
        observedOwner.genericArity,
        observedOwner.category,
    )
    val methodKey = allocator.method(expectedMethod)
    val ownerKey = when (val key = allocator.actualType(observedOwner)) {
        is EmissionIdentityAllocator.ActualType.Bound -> key.key
        is EmissionIdentityAllocator.ActualType.Conflict ->
            return DotNetGenericOwnerPhysicalBindingResult.Conflict(key.reason)
    }
    val header = when (val evidence = buildDotNetGenericOwnerActualMethodDefEmissionHeaderShape(
        allocator,
        expectedMethod,
        methodObservation,
        observedOwner,
    )) {
        is DotNetGenericOwnerPhysicalBindingResult.Bound -> evidence.value
        is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return evidence
        DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
            return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                "the final implementation MethodDef has no self-contained owner binding",
            )
    }
    val naturalSignature = naturalDeclaration.physicalMethod.signature
    if (header.ordinaryParameterCarriers.size != naturalSignature.parameterSlots.size ||
        (header.result is DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.SplitNullable) !=
        (naturalSignature.resultLayout is
                DotNetGenericOwnerPhysicalCallableResultLayoutRecord.SplitNullable)
    ) {
        return DotNetGenericOwnerPhysicalBindingResult.Conflict(
            "the final implementation MethodDef disagrees with its N calling convention",
        )
    }
    val implementationType = DotNetGenericOwnerSealedEmissionTypeDefRow(
        structural = DotNetGenericOwnerCompleteEmissionTypeDefRow(
            identityKey = ownerKey,
            aliases = observedOwner.aliases.map(allocator::alias),
            genericArity = observedOwner.genericArity,
            category = observedOwner.category,
            genericParameters = typeObservation.genericParameters.map { parameter ->
                DotNetGenericOwnerCompleteEmissionGenericParameterRow(
                    parameter.variance,
                    constraints = emptyList(),
                )
            },
            // M seals the class binder, not a partial copy of the complete TypeDef graph. The
            // exact N construction is recorded independently below and PE-validated on import.
            directEdges = emptyList(),
        ),
        physicalPath = typeObservation.physicalTypePath,
        flags = typeObservation.flags,
    )
    val implementationMethod = DotNetProducerGenericOwnerSealedMethodDef(
        role = DotNetProducerGenericOwnerSealedMethodDefRole.IMPLEMENTATION_TYPED_ENTRY,
        row = DotNetGenericOwnerSealedEmissionMethodDefRow(
            structural = DotNetGenericOwnerCompleteEmissionMethodDefRow(
                identityKey = methodKey,
                header = header,
                genericParameters = emptyList(),
            ),
            physicalName = methodObservation.physicalMethodName,
            physicalGenericParameterNames = emptyList(),
            visibility = methodObservation.visibility,
            dispatch = methodObservation.dispatch,
            isHideBySig = methodObservation.isHideBySig,
            isSpecialName = methodObservation.isSpecialName,
            isRuntimeSpecialName = methodObservation.isRuntimeSpecialName,
        ),
        logicalParameterDomains = naturalSignature.parameterSlots.map { slot -> slot.domain },
        logicalResultDomain = naturalSignature.resultLayout.valueSlotOrNull?.domain,
    )
    val physicalMethod = try {
        dotNetProducerSealedMethodDefPhysicalIdentity(
            implementationMemberKey,
            implementationType,
            implementationMethod,
        )
    } catch (failure: IllegalArgumentException) {
        return DotNetGenericOwnerPhysicalBindingResult.Conflict(
            failure.message ?: "the implementation MethodDef cannot be projected",
        )
    }
    val declaration = DotNetPhysicalDeclaration.GenericOwnerImplementationMethodDef(
        logicalInterfaceMemberKey = logicalInterfaceMemberKey,
        implementationOwnerKey = implementationOwnerKey,
        implementationMemberKey = implementationMemberKey,
        ownerPath = implementationType.physicalPath,
        ownerTypeParameterVariances = implementationType.structural.genericParameters.map { parameter ->
            parameter.variance
        },
        ownerVisibility = DotNetGenericOwnerPhysicalTypeVisibility.PUBLIC,
        ownerDispatch = DotNetGenericOwnerPhysicalTypeDispatch.OVERRIDABLE,
        naturalInterfaceTypeArguments = naturalArguments,
        physicalMethod = physicalMethod,
        methodVisibility = DotNetGenericOwnerPhysicalMemberVisibility.PUBLIC,
        methodDispatch = DotNetGenericOwnerPhysicalMemberDispatch.OVERRIDABLE,
        methodIntroducesSlot = methodObservation.dispatch.isNewSlot,
        methodIsHideBySig = methodObservation.isHideBySig,
        methodIsSpecialName = methodObservation.isSpecialName,
        methodIsRuntimeSpecialName = methodObservation.isRuntimeSpecialName,
    )
    return DotNetGenericOwnerPhysicalBindingResult.Bound(declaration)
}

private data class CompleteExpectedMethod(
    val kind: DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodKind,
    val emittedFunction: IrSimpleFunctionSymbol,
    val identity: DotNetGenericOwnerPhysicalMethodDefIdentity.Local,
    val reference: DotNetGenericOwnerPhysicalMethodDefReference,
    val key: DotNetGenericOwnerPhysicalMethodDefEmissionMethodKey,
)

private data class CompleteActualMethod(
    val sealedRow: DotNetGenericOwnerSealedEmissionMethodDefRow,
    val snapshot: DotNetGenericOwnerPhysicalMethodDefEmissionHeaderSnapshot,
)

internal data class DotNetGenericOwnerExpectedSemanticEquivalenceForwardingEdge(
    val kind: DotNetGenericOwnerSemanticEquivalenceForwardingEdgeKind,
    val bodyKind: DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodKind,
    val bodyFunction: IrSimpleFunctionSymbol,
    val bodyIdentity: DotNetGenericOwnerPhysicalMethodDefIdentity.Local,
    val targetKind: DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodKind,
    val targetFunction: IrSimpleFunctionSymbol,
    val targetIdentity: DotNetGenericOwnerPhysicalMethodDefIdentity.Local,
)

private data class BoundSemanticEquivalenceTargetSignature(
    val receiver: DotNetGenericOwnerObservedMethodCarrier,
    val parameters: List<DotNetGenericOwnerObservedMethodCarrier>,
    val result: DotNetGenericOwnerObservedMethodCarrier,
    val hasSplitNullableResult: Boolean,
)

/**
 * Replays the emitter's TypeDef/MethodSpec substitution against the independently observed target
 * MethodDef header.  The call edge is evidence only when its verifier-visible signature is exactly
 * the signature obtained from that header; an equal target symbol or row identity alone is not
 * enough to prove that the emitted instruction invoked the intended construction.
 */
private fun bindSemanticEquivalenceTargetSignature(
    target: DotNetGenericOwnerPhysicalMethodDefHeaderObservation,
    edge: DotNetGenericOwnerPhysicalForwardingCallEdge,
): DotNetGenericOwnerPhysicalBindingResult<BoundSemanticEquivalenceTargetSignature> {
    val targetOwner = (target.physicalMethodOwner as?
            DotNetGenericOwnerObservedMethodDefOwner.Local)?.typeDef
        ?: return DotNetGenericOwnerPhysicalBindingResult.Conflict(
            "the forwarding target has no exact local TypeDef binder",
        )
    val ownerConstruction = edge.targetOwner as?
            DotNetGenericOwnerObservedMethodCarrier.LocalConstruction
        ?: return DotNetGenericOwnerPhysicalBindingResult.Conflict(
            "the forwarding call has no exact constructed target owner",
        )
    if (ownerConstruction.definition.physicalKey != targetOwner.physicalKey ||
        ownerConstruction.arguments.size != targetOwner.genericArity
    ) {
        return DotNetGenericOwnerPhysicalBindingResult.Conflict(
            "the forwarding call targets another TypeDef construction",
        )
    }
    if (edge.methodInstantiation.size != target.genericArity) {
        return DotNetGenericOwnerPhysicalBindingResult.Conflict(
            "the forwarding call has an arity-mismatched MethodSpec vector",
        )
    }
    val targetIdentity = target.physicalMethodIdentity
        ?: return DotNetGenericOwnerPhysicalBindingResult.Conflict(
            "the forwarding target has no exact local MethodDef binder",
        )

    fun bind(
        carrier: DotNetGenericOwnerObservedMethodCarrier,
    ): DotNetGenericOwnerPhysicalBindingResult<DotNetGenericOwnerObservedMethodCarrier> {
        return when (carrier) {
            is DotNetGenericOwnerObservedMethodCarrier.Leaf ->
                DotNetGenericOwnerPhysicalBindingResult.Bound(carrier)
            is DotNetGenericOwnerObservedMethodCarrier.OwnerParameter -> {
                if (carrier.binder.physicalKey != targetOwner.physicalKey) {
                    DotNetGenericOwnerPhysicalBindingResult.Conflict(
                        "the target signature contains an owner parameter from another TypeDef binder",
                    )
                } else {
                    ownerConstruction.arguments.getOrNull(carrier.index)?.let { argument ->
                        DotNetGenericOwnerPhysicalBindingResult.Bound(argument)
                    } ?: DotNetGenericOwnerPhysicalBindingResult.Conflict(
                        "the target signature contains an out-of-range owner parameter",
                    )
                }
            }
            is DotNetGenericOwnerObservedMethodCarrier.MethodParameter -> {
                if (carrier.physicalOwner.physicalKey != targetOwner.physicalKey ||
                    carrier.physicalFunction !== target.physicalFunction ||
                    carrier.physicalMethodIdentity?.sameLocalMethodIdentityAs(
                        targetIdentity,
                    ) != true
                ) {
                    DotNetGenericOwnerPhysicalBindingResult.Conflict(
                        "the target signature contains a method parameter from another MethodDef binder",
                    )
                } else {
                    edge.methodInstantiation.getOrNull(carrier.index)?.let { argument ->
                        DotNetGenericOwnerPhysicalBindingResult.Bound(argument)
                    } ?: DotNetGenericOwnerPhysicalBindingResult.Conflict(
                        "the target signature contains an out-of-range method parameter",
                    )
                }
            }
            is DotNetGenericOwnerObservedMethodCarrier.LocalConstruction -> {
                val arguments = mutableListOf<DotNetGenericOwnerObservedMethodCarrier>()
                for (argument in carrier.arguments) {
                    when (val bound = bind(argument)) {
                        is DotNetGenericOwnerPhysicalBindingResult.Bound -> arguments += bound.value
                        is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return bound
                        DotNetGenericOwnerPhysicalBindingResult.Unavailable -> error(
                            "semantic-equivalence target binding is total",
                        )
                    }
                }
                DotNetGenericOwnerPhysicalBindingResult.Bound(carrier.copy(arguments = arguments))
            }
            is DotNetGenericOwnerObservedMethodCarrier.SzArray -> when (val element = bind(carrier.element)) {
                is DotNetGenericOwnerPhysicalBindingResult.Bound ->
                    DotNetGenericOwnerPhysicalBindingResult.Bound(carrier.copy(element = element.value))
                is DotNetGenericOwnerPhysicalBindingResult.Conflict -> element
                DotNetGenericOwnerPhysicalBindingResult.Unavailable -> error(
                    "semantic-equivalence target binding is total",
                )
            }
            is DotNetGenericOwnerObservedMethodCarrier.ByReference ->
                when (val element = bind(carrier.element)) {
                    is DotNetGenericOwnerPhysicalBindingResult.Bound ->
                        DotNetGenericOwnerPhysicalBindingResult.Bound(carrier.copy(element = element.value))
                    is DotNetGenericOwnerPhysicalBindingResult.Conflict -> element
                    DotNetGenericOwnerPhysicalBindingResult.Unavailable -> error(
                        "semantic-equivalence target binding is total",
                    )
                }
            is DotNetGenericOwnerObservedMethodCarrier.Other ->
                DotNetGenericOwnerPhysicalBindingResult.Conflict(
                    "the target signature contains a carrier outside the bounded identity vocabulary",
                )
            is DotNetGenericOwnerObservedMethodCarrier.Unbindable ->
                DotNetGenericOwnerPhysicalBindingResult.Conflict(carrier.reason)
        }
    }

    fun bound(
        carrier: DotNetGenericOwnerObservedMethodCarrier,
        position: String,
    ): DotNetGenericOwnerPhysicalBindingResult<DotNetGenericOwnerObservedMethodCarrier> =
        when (val result = bind(carrier)) {
            is DotNetGenericOwnerPhysicalBindingResult.Bound -> result
            is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                DotNetGenericOwnerPhysicalBindingResult.Conflict("$position: ${result.reason}")
            DotNetGenericOwnerPhysicalBindingResult.Unavailable -> error(
                "semantic-equivalence target binding is total",
            )
        }

    val receiver = target.signature.receiverCarrier
        ?: return DotNetGenericOwnerPhysicalBindingResult.Conflict(
            "the forwarding target is not an instance MethodDef",
        )
    val boundReceiver = when (val result = bound(receiver, "target receiver")) {
        is DotNetGenericOwnerPhysicalBindingResult.Bound -> result.value
        is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return result
        DotNetGenericOwnerPhysicalBindingResult.Unavailable -> error(
            "semantic-equivalence target binding is total",
        )
    }
    val boundParameters = mutableListOf<DotNetGenericOwnerObservedMethodCarrier>()
    target.signature.parameterCarriers.forEachIndexed { index, parameter ->
        when (val result = bound(parameter, "target parameter $index")) {
            is DotNetGenericOwnerPhysicalBindingResult.Bound -> boundParameters += result.value
            is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return result
            DotNetGenericOwnerPhysicalBindingResult.Unavailable -> error(
                "semantic-equivalence target binding is total",
            )
        }
    }
    val boundResult = when (val result = bound(target.signature.returnCarrier, "target result")) {
        is DotNetGenericOwnerPhysicalBindingResult.Bound -> result.value
        is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return result
        DotNetGenericOwnerPhysicalBindingResult.Unavailable -> error(
            "semantic-equivalence target binding is total",
        )
    }
    return DotNetGenericOwnerPhysicalBindingResult.Bound(
        BoundSemanticEquivalenceTargetSignature(
            boundReceiver,
            boundParameters,
            boundResult,
            target.signature.hasSplitNullableResult,
        ),
    )
}

internal fun inspectDotNetGenericOwnerSemanticEquivalenceForwardingBodies(
    expectedEdges: List<DotNetGenericOwnerExpectedSemanticEquivalenceForwardingEdge>,
    observations: List<DotNetGenericOwnerPhysicalMethodDefHeaderObservation>,
    otherScopeObservations: List<DotNetGenericOwnerPhysicalMethodDefHeaderObservation>,
): DotNetGenericOwnerSemanticEquivalenceForwardingEvidence {
    require(expectedEdges.size == DotNetGenericOwnerSemanticEquivalenceForwardingEdgeKind.entries.size &&
            expectedEdges.map { edge -> edge.kind }.toSet() ==
            DotNetGenericOwnerSemanticEquivalenceForwardingEdgeKind.entries.toSet()) {
        "semantic-equivalence inspection requires both expected forwarding edges exactly once"
    }
    val conflicts = mutableListOf<String>()
    val unavailable = mutableListOf<String>()
    val edges = mutableListOf<DotNetGenericOwnerSemanticEquivalenceForwardingEdgeSnapshot>()

    fun DotNetGenericOwnerPhysicalMethodDefHeaderObservation.touches(
        function: IrSimpleFunctionSymbol,
        identity: DotNetGenericOwnerPhysicalMethodDefIdentity.Local,
    ): Boolean = physicalFunction === function ||
            physicalMethodIdentity?.sameLocalMethodIdentityAs(identity) == true

    expectedEdges.forEach { expectedEdge ->
        if (otherScopeObservations.any { observation ->
                observation.touches(expectedEdge.bodyFunction, expectedEdge.bodyIdentity)
            }
        ) {
            conflicts += "${expectedEdge.kind}: the forwarding body was observed in another physical scope"
            return@forEach
        }
        val candidates = observations.filter { observation ->
            observation.touches(expectedEdge.bodyFunction, expectedEdge.bodyIdentity)
        }
        if (candidates.isEmpty()) {
            unavailable += "${expectedEdge.kind}: the final forwarding MethodDef was not observed"
            return@forEach
        }
        if (candidates.size != 1) {
            conflicts += "${expectedEdge.kind}: the final forwarding MethodDef was observed more than once"
            return@forEach
        }
        val body = candidates.single()
        if (body.physicalFunction !== expectedEdge.bodyFunction ||
            body.physicalMethodIdentity?.sameLocalMethodIdentityAs(expectedEdge.bodyIdentity) != true
        ) {
            conflicts += "${expectedEdge.kind}: function and physical identity select different body MethodDefs"
            return@forEach
        }
        when (val evidence = body.forwardingBodyEvidence) {
            null -> {
                unavailable += "${expectedEdge.kind}: the final MethodDef has no forwarding-body observation"
                return@forEach
            }
            is DotNetGenericOwnerPhysicalForwardingBodyEvidence.Unavailable -> {
                unavailable += "${expectedEdge.kind}: ${evidence.reason}"
                return@forEach
            }
            is DotNetGenericOwnerPhysicalForwardingBodyEvidence.Conflict -> {
                conflicts += "${expectedEdge.kind}: ${evidence.reason}"
                return@forEach
            }
            is DotNetGenericOwnerPhysicalForwardingBodyEvidence.Forwarding -> {
                val edge = evidence.edge
                if (edge.targetFunction !== expectedEdge.targetFunction ||
                    edge.targetIdentity?.sameLocalMethodIdentityAs(expectedEdge.targetIdentity) != true
                ) {
                    conflicts += "${expectedEdge.kind}: the resolved call targets another physical MethodDef"
                    return@forEach
                }
                val targetCandidates = observations.filter { observation ->
                    observation.touches(expectedEdge.targetFunction, expectedEdge.targetIdentity)
                }
                if (targetCandidates.size != 1) {
                    conflicts += "${expectedEdge.kind}: the resolved target has no unique final MethodDef header"
                    return@forEach
                }
                val target = targetCandidates.single()
                if (target.physicalFunction !== expectedEdge.targetFunction ||
                    target.physicalMethodIdentity
                        ?.sameLocalMethodIdentityAs(expectedEdge.targetIdentity) != true ||
                    edge.targetPhysicalOwner != target.physicalMethodOwner
                ) {
                    conflicts += "${expectedEdge.kind}: the resolved target disagrees with its final MethodDef header"
                    return@forEach
                }
                val targetSignature = when (
                    val binding = bindSemanticEquivalenceTargetSignature(target, edge)
                ) {
                    is DotNetGenericOwnerPhysicalBindingResult.Bound -> binding.value
                    is DotNetGenericOwnerPhysicalBindingResult.Conflict -> {
                        conflicts += "${expectedEdge.kind}: ${binding.reason}"
                        return@forEach
                    }
                    DotNetGenericOwnerPhysicalBindingResult.Unavailable -> error(
                        "semantic-equivalence target binding is total",
                    )
                }
                if (edge.targetOwner != targetSignature.receiver ||
                    edge.parameterCarriers !=
                    listOf(targetSignature.receiver) + targetSignature.parameters ||
                    edge.returnCarrier != targetSignature.result ||
                    edge.hasSplitNullableResult != targetSignature.hasSplitNullableResult
                ) {
                    conflicts += "${expectedEdge.kind}: the resolved call signature disagrees with its target MethodDef"
                    return@forEach
                }
                val bodyReceiver = body.signature.receiverCarrier
                if (bodyReceiver == null || edge.targetOwner != bodyReceiver ||
                    edge.parameterCarriers != listOf(bodyReceiver) + body.signature.parameterCarriers
                ) {
                    conflicts += "${expectedEdge.kind}: the resolved call does not preserve the exact receiver/parameter carriers"
                    return@forEach
                }
                val methodArgumentsAreExact = edge.methodInstantiation.size == body.genericArity &&
                        edge.methodInstantiation.indices.all { index ->
                            val argument = edge.methodInstantiation[index] as?
                                    DotNetGenericOwnerObservedMethodCarrier.MethodParameter
                            argument != null &&
                                    argument.physicalFunction === body.physicalFunction &&
                                    argument.physicalMethodIdentity
                                        ?.sameLocalMethodIdentityAs(expectedEdge.bodyIdentity) == true &&
                                    argument.index == index
                        }
                if (!methodArgumentsAreExact || target.genericArity != body.genericArity) {
                    conflicts += "${expectedEdge.kind}: the resolved call does not preserve the exact MethodSpec binder"
                    return@forEach
                }
                edges += DotNetGenericOwnerSemanticEquivalenceForwardingEdgeSnapshot(
                    kind = expectedEdge.kind,
                    bodyMethodKind = expectedEdge.bodyKind.toSnapshot(),
                    targetMethodKind = expectedEdge.targetKind.toSnapshot(),
                    methodGenericArity = body.genericArity,
                    isVirtual = edge.isVirtual,
                )
            }
        }
    }
    return when {
        conflicts.isNotEmpty() -> DotNetGenericOwnerSemanticEquivalenceForwardingEvidence.Conflict(
            conflicts.distinct().joinToString("; "),
        )
        unavailable.isNotEmpty() -> DotNetGenericOwnerSemanticEquivalenceForwardingEvidence.Unavailable(
            unavailable.distinct().joinToString("; "),
        )
        else -> DotNetGenericOwnerSemanticEquivalenceForwardingEvidence.Known(edges)
    }
}

private sealed interface CompleteConversion<out T> {
    data class Known<T>(val value: T) : CompleteConversion<T>
    data class Unavailable(val reason: String) : CompleteConversion<Nothing>
    data class Conflict(val reason: String) : CompleteConversion<Nothing>
}

private class CompleteRowsAccumulator<T> {
    private val rows = mutableListOf<T>()
    private val unavailable = mutableListOf<String>()
    private val conflicts = mutableListOf<String>()

    fun add(result: CompleteConversion<T>) {
        when (result) {
            is CompleteConversion.Known -> rows += result.value
            is CompleteConversion.Unavailable -> unavailable += result.reason
            is CompleteConversion.Conflict -> conflicts += result.reason
        }
    }

    fun conflict(reason: String) {
        conflicts += reason
    }

    fun evidence(): DotNetGenericOwnerCompleteEmissionRowsEvidence<T> = when {
        conflicts.isNotEmpty() -> DotNetGenericOwnerCompleteEmissionRowsEvidence.Conflict(
            conflicts.distinct().joinToString("; "),
        )
        unavailable.isNotEmpty() -> DotNetGenericOwnerCompleteEmissionRowsEvidence.Unavailable(
            unavailable.distinct().joinToString("; "),
        )
        else -> DotNetGenericOwnerCompleteEmissionRowsEvidence.Known(rows.toList())
    }
}

private fun <T, R> DotNetGenericOwnerCompleteEmissionRowsEvidence<T>.mapRows(
    transform: (T) -> R,
): DotNetGenericOwnerCompleteEmissionRowsEvidence<R> = when (this) {
    is DotNetGenericOwnerCompleteEmissionRowsEvidence.Known ->
        DotNetGenericOwnerCompleteEmissionRowsEvidence.Known(rows.map(transform))
    is DotNetGenericOwnerCompleteEmissionRowsEvidence.Unavailable ->
        DotNetGenericOwnerCompleteEmissionRowsEvidence.Unavailable(reason)
    is DotNetGenericOwnerCompleteEmissionRowsEvidence.Conflict ->
        DotNetGenericOwnerCompleteEmissionRowsEvidence.Conflict(reason)
}

private fun sealedManifestEvidence(
    typeDefs: DotNetGenericOwnerCompleteEmissionRowsEvidence<DotNetGenericOwnerSealedEmissionTypeDefRow>,
    methodDefs: DotNetGenericOwnerCompleteEmissionRowsEvidence<CompleteActualMethod>,
    methodImpls: DotNetGenericOwnerCompleteEmissionRowsEvidence<DotNetGenericOwnerCompleteEmissionMethodImplRow>,
): DotNetGenericOwnerSealedEmissionManifestEvidence {
    val evidence = listOf(typeDefs, methodDefs, methodImpls)
    val conflicts = evidence.filterIsInstance<DotNetGenericOwnerCompleteEmissionRowsEvidence.Conflict>()
    if (conflicts.isNotEmpty()) {
        return DotNetGenericOwnerSealedEmissionManifestEvidence.Conflict(
            conflicts.joinToString("; ") { conflict -> conflict.reason },
        )
    }
    val unavailable = evidence.filterIsInstance<DotNetGenericOwnerCompleteEmissionRowsEvidence.Unavailable>()
    if (unavailable.isNotEmpty()) {
        return DotNetGenericOwnerSealedEmissionManifestEvidence.Unavailable(
            unavailable.joinToString("; ") { missing -> missing.reason },
        )
    }
    return DotNetGenericOwnerSealedEmissionManifestEvidence.Known(
        typeDefs = (typeDefs as DotNetGenericOwnerCompleteEmissionRowsEvidence.Known).rows,
        methodDefs = (methodDefs as DotNetGenericOwnerCompleteEmissionRowsEvidence.Known).rows.map { method ->
            method.sealedRow
        },
        methodImpls = (methodImpls as DotNetGenericOwnerCompleteEmissionRowsEvidence.Known).rows,
    )
}

private fun DotNetGenericOwnerSealedEmissionManifestEvidence.requireImplicitNaturalImplementationNameMatch(
    expectedMethods: List<CompleteExpectedMethod>,
): DotNetGenericOwnerSealedEmissionManifestEvidence {
    if (this !is DotNetGenericOwnerSealedEmissionManifestEvidence.Known) return this
    val naturalKey = expectedMethods.single { method ->
        method.kind == DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodKind.NATURAL_INTERFACE_SLOT
    }.key
    val implementationKey = expectedMethods.single { method ->
        method.kind == DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodKind.IMPLEMENTATION_TYPED_ENTRY
    }.key
    val rowsByKey = methodDefs.associateBy { row -> row.structural.identityKey }
    val natural = rowsByKey[naturalKey] ?: return this
    val implementation = rowsByKey[implementationKey] ?: return this
    return if (natural.physicalName == implementation.physicalName) {
        this
    } else {
        DotNetGenericOwnerSealedEmissionManifestEvidence.Conflict(
            "implicit natural-interface mapping requires the final slot and implementation MethodDefs to share a name",
        )
    }
}

internal fun compareDotNetGenericOwnerCompleteEmissionFamily(
    authority: DotNetLocalGenericOwnerPhysicalAuthority,
    family: DotNetLocalGenericOwnerPhysicalCompleteEmissionFamily,
    successfulEmissions: List<DotNetGenericOwnerCompleteEmissionScopeObservations>,
): DotNetGenericOwnerCompleteEmissionFamilyComparisonSnapshot =
    inspectDotNetGenericOwnerCompleteEmissionFamily(authority, family, successfulEmissions).comparison

internal fun inspectDotNetGenericOwnerCompleteEmissionFamily(
    authority: DotNetLocalGenericOwnerPhysicalAuthority,
    family: DotNetLocalGenericOwnerPhysicalCompleteEmissionFamily,
    successfulEmissions: List<DotNetGenericOwnerCompleteEmissionScopeObservations>,
): DotNetGenericOwnerCompleteEmissionFamilyProducts {
    val declarations = checkNotNull(authority.boundDeclarations) {
        "complete final-emission comparison requires BOUND declaration authority"
    }
    val allocator = EmissionIdentityAllocator()
    val typeDescriptions = family.types.mapValues { entry ->
        checkNotNull(declarations.typeDescriptionOrNull(entry.value)) {
            "a BOUND complete family lost one of its TypeDef descriptions"
        }
    }
    // Expected authority is registered before any actual aliases are inspected.
    family.types.forEach { entry ->
        val kind = entry.key
        val aliases = family.typeAliases.getValue(kind)
        allocator.expectedTypeAliasGroup(aliases, typeDescriptions.getValue(kind))
        aliases.forEach(allocator::alias)
    }
    val expectedMethods = family.methods.map { entry ->
        val kind = entry.key
        val selected = entry.value
        val identity = selected.second.identity as? DotNetGenericOwnerPhysicalMethodDefIdentity.Local
            ?: error("a local complete family contains a non-local MethodDef identity")
        CompleteExpectedMethod(
            kind,
            selected.first,
            identity,
            selected.second,
            allocator.method(identity),
        )
    }
    val currentMethod = expectedMethods.first().identity
    val expectedTypeRows = family.types.map { entry ->
        val kind = entry.key
        val identity = entry.value
        val description = typeDescriptions.getValue(kind)
        val parameters = family.typeParameters.getValue(kind).map { parameter ->
            DotNetGenericOwnerCompleteEmissionGenericParameterRow(
                parameter.variance,
                parameter.constraints.map { constraint ->
                    checkNotNull(buildDotNetGenericOwnerExpectedEmissionCarrierShape(
                        authority,
                        declarations,
                        allocator,
                        currentMethod,
                        constraint,
                    )) { "a BOUND complete family has an unbindable GenericParam constraint" }
                },
            )
        }
        val edges = when (val binding = declarations.directSupertypeEdgesOrUnavailable(identity)) {
            is DotNetGenericOwnerPhysicalBindingResult.Bound -> binding.value
            is DotNetGenericOwnerPhysicalBindingResult.Conflict -> error(binding.reason)
            DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                error("a BOUND complete family lost its complete TypeDef edge set")
        }
        DotNetGenericOwnerCompleteEmissionTypeDefRow(
            identityKey = allocator.expectedType(identity, description),
            aliases = family.typeAliases.getValue(kind).map(allocator::alias),
            genericArity = description.genericArity,
            category = description.category,
            genericParameters = parameters,
            directEdges = edges.map { edge ->
                DotNetGenericOwnerCompleteEmissionTypeDefEdgeRow(
                    edge.kind,
                    checkNotNull(buildDotNetGenericOwnerExpectedEmissionCarrierShape(
                        authority,
                        declarations,
                        allocator,
                        currentMethod,
                        edge.target,
                    )) { "a BOUND complete family has an unbindable TypeDef edge" },
                )
            },
        )
    }
    val expectedMethodRows = expectedMethods.map { method ->
        val genericParameters = method.reference.genericParameters.map { parameter ->
            DotNetGenericOwnerCompleteEmissionGenericParameterRow(
                parameter.variance,
                parameter.constraints.map { constraint ->
                    checkNotNull(buildDotNetGenericOwnerExpectedEmissionCarrierShape(
                        authority,
                        declarations,
                        allocator,
                        method.identity,
                        constraint,
                    )) { "a BOUND complete family has an unbindable MethodDef GenericParam constraint" }
                },
            )
        }
        DotNetGenericOwnerCompleteEmissionMethodDefRow(
            method.key,
            checkNotNull(buildDotNetGenericOwnerExpectedMethodDefEmissionHeaderShape(
                authority,
                declarations,
                allocator,
                method.identity,
                method.reference,
            )) { "a BOUND complete family has an unbindable MethodDef header" },
            genericParameters,
        )
    }
    val methodsByIdentity = expectedMethods.associateBy { method -> method.identity }
    val expectedMethodImplRowsByKind = family.methodImpls.mapValues { entry ->
        val methodImpl = entry.value
        val body = methodsByIdentity.entries.single { entry ->
            entry.key.sameLocalMethodIdentityAs(methodImpl.body)
        }.value
        val declaration = methodsByIdentity.entries.single { entry ->
            entry.key.sameLocalMethodIdentityAs(methodImpl.declaration)
        }.value
        DotNetGenericOwnerCompleteEmissionMethodImplRow(
            implementingTypeDefKey = allocator.expectedType(
                methodImpl.implementingType,
                checkNotNull(declarations.typeDescriptionOrNull(methodImpl.implementingType)),
            ),
            bodyMethodDefKey = body.key,
            declarationOwner = checkNotNull(buildDotNetGenericOwnerExpectedEmissionCarrierShape(
                authority,
                declarations,
                allocator,
                body.identity,
                methodImpl.declarationOwner,
            )),
            declarationMethodDefKey = declaration.key,
        )
    }
    val expectedMethodImplRows = expectedMethodImplRowsByKind.values.toList()
    val expected = DotNetGenericOwnerCompleteEmissionManifest(
        expectedTypeRows,
        expectedMethodRows,
        expectedMethodImplRows,
    )

    val logicalOwner = family.logicalMember.owner.parent as IrClass
    val expectedScope = DotNetIlEmissionScope.entries.single { candidate -> candidate.owns(logicalOwner) }
    val physicalOwners = buildList {
        add(family.implementationMember.owner.parent as IrClass)
        family.types.values.mapTo(this) { identity -> identity.owner.owner }
    }
    val scopeConflict = physicalOwners.any { owner -> !expectedScope.owns(owner) }
    val matchingScopes = successfulEmissions.filter { emission -> emission.scope == expectedScope }
    val current = matchingScopes.singleOrNull()
    val other = successfulEmissions.filter { emission -> emission.scope != expectedScope }
    val sharedConflict = when {
        scopeConflict -> "one BOUND complete family spans multiple physical emission scopes"
        matchingScopes.size > 1 -> "one physical emission scope published more than one final observation set"
        else -> null
    }
    val actualTypeDefs: DotNetGenericOwnerCompleteEmissionRowsEvidence<
            DotNetGenericOwnerSealedEmissionTypeDefRow,
            >
    val actualMethodDefs: DotNetGenericOwnerCompleteEmissionRowsEvidence<CompleteActualMethod>
    val actualMethodImpls: DotNetGenericOwnerCompleteEmissionRowsEvidence<
            DotNetGenericOwnerCompleteEmissionMethodImplRow,
            >
    if (sharedConflict != null) {
        actualTypeDefs = DotNetGenericOwnerCompleteEmissionRowsEvidence.Conflict(sharedConflict)
        actualMethodDefs = DotNetGenericOwnerCompleteEmissionRowsEvidence.Conflict(sharedConflict)
        actualMethodImpls = DotNetGenericOwnerCompleteEmissionRowsEvidence.Conflict(sharedConflict)
    } else {
        val currentTypeDefs = current?.typeDefs.orEmpty()
        val currentMethodDefs = current?.methodDefs.orEmpty()
        val currentMethodImpls = current?.methodImpls.orEmpty()
        actualTypeDefs = actualCompleteTypeDefs(
            authority,
            allocator,
            family,
            currentMethod,
            currentTypeDefs,
            other.flatMap { emission -> emission.typeDefs },
        )
        actualMethodDefs = actualCompleteMethodDefs(
            authority,
            allocator,
            expectedMethods,
            currentMethodDefs,
            other.flatMap { emission -> emission.methodDefs },
        )
        actualMethodImpls = actualCompleteMethodImpls(
            authority,
            allocator,
            family,
            expectedMethods,
            currentMethodImpls,
            other.flatMap { emission -> emission.methodImpls },
        )
    }
    val actual = DotNetGenericOwnerCompleteEmissionManifestEvidence(
        actualTypeDefs.mapRows { row -> row.structural },
        actualMethodDefs.mapRows { method -> method.sealedRow.structural },
        actualMethodImpls,
    )
    val observedSemanticEquivalenceForwardingEvidence = if (sharedConflict != null) {
        DotNetGenericOwnerSemanticEquivalenceForwardingEvidence.Conflict(sharedConflict)
    } else {
        fun expectedForwardingMethod(
            kind: DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodKind,
        ) = expectedMethods.single { method -> method.kind == kind }
        val classDispatcher = expectedForwardingMethod(
            DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodKind
                .CLASS_SEMANTIC_CAPABILITY_DISPATCHER,
        )
        val interfaceDispatcher = expectedForwardingMethod(
            DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodKind
                .INTERFACE_SEMANTIC_CAPABILITY_DISPATCHER,
        )
        val typedEntry = expectedForwardingMethod(
            DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodKind.IMPLEMENTATION_TYPED_ENTRY,
        )
        inspectDotNetGenericOwnerSemanticEquivalenceForwardingBodies(
            listOf(
                DotNetGenericOwnerExpectedSemanticEquivalenceForwardingEdge(
                    DotNetGenericOwnerSemanticEquivalenceForwardingEdgeKind
                        .CLASS_DISPATCHER_TO_TYPED_ENTRY,
                    classDispatcher.kind,
                    classDispatcher.emittedFunction,
                    classDispatcher.identity,
                    typedEntry.kind,
                    typedEntry.emittedFunction,
                    typedEntry.identity,
                ),
                DotNetGenericOwnerExpectedSemanticEquivalenceForwardingEdge(
                    DotNetGenericOwnerSemanticEquivalenceForwardingEdgeKind
                        .INTERFACE_DISPATCHER_TO_CLASS_DISPATCHER,
                    interfaceDispatcher.kind,
                    interfaceDispatcher.emittedFunction,
                    interfaceDispatcher.identity,
                    classDispatcher.kind,
                    classDispatcher.emittedFunction,
                    classDispatcher.identity,
                ),
            ),
            current?.methodDefs.orEmpty(),
            other.flatMap { emission -> emission.methodDefs },
        )
    }
    val comparison = compareDotNetGenericOwnerCompleteEmissionManifest(expected, actual)
    val expectedTypeSnapshots = family.types.map { entry ->
        val kind = entry.key
        val identity = entry.value
        val input = checkNotNull(authority.inputOrNull(identity))
        val parameters = family.typeParameters.getValue(kind)
        val edges = when (val binding = declarations.directSupertypeEdgesOrUnavailable(identity)) {
            is DotNetGenericOwnerPhysicalBindingResult.Bound -> binding.value
            else -> error("a BOUND complete family lost its edge snapshot")
        }
        DotNetGenericOwnerCompleteEmissionTypeDefSnapshot(
            kind.toSnapshot(),
            input.logicalOwnerName,
            family.typeAliases.getValue(kind).map { alias -> alias.view?.toSnapshot() },
            input.genericArity,
            input.category,
            parameters.map { parameter -> parameter.variance.toSnapshot() },
            parameters.map { parameter -> parameter.constraints.size },
            edges.count { edge -> edge.kind == DotNetGenericOwnerDirectSupertypeKind.BASE_CLASS },
            edges.count { edge -> edge.kind == DotNetGenericOwnerDirectSupertypeKind.INTERFACE },
        )
    }
    val methodKindsByIdentity = expectedMethods.associate { method -> method.identity to method.kind }
    val expectedMethodSnapshots = expectedMethods.map { method ->
        val ownerIdentity = method.reference.declaringType as DotNetGenericOwnerPhysicalTypeDefIdentity.Local
        val ownerKind = family.types.entries.single { entry ->
            entry.value.sameLocalTypeIdentityAs(ownerIdentity)
        }.key
        DotNetGenericOwnerCompleteEmissionMethodDefSnapshot(
            method.kind.toSnapshot(),
            method.identity.role,
            ownerKind.toSnapshot(),
            method.reference.signature.genericArity,
            method.reference.genericParameters.map { parameter -> parameter.variance.toSnapshot() },
            method.reference.genericParameters.map { parameter -> parameter.constraints.size },
        )
    }
    val expectedMethodImplSnapshots = family.methodImpls.map { entry ->
        val kind = entry.key
        val methodImpl = entry.value
        fun methodKind(identity: DotNetGenericOwnerPhysicalMethodDefIdentity.Local) =
            methodKindsByIdentity.entries.single { entry ->
                entry.key.sameLocalMethodIdentityAs(identity)
            }.value
        fun typeKind(identity: DotNetGenericOwnerPhysicalTypeDefIdentity.Local) =
            family.types.entries.single { entry ->
                entry.value.sameLocalTypeIdentityAs(identity)
            }.key
        DotNetGenericOwnerCompleteEmissionMethodImplSnapshot(
            kind.toSnapshot(),
            typeKind(methodImpl.implementingType).toSnapshot(),
            methodKind(methodImpl.body).toSnapshot(),
            typeKind(methodImpl.declarationOwner.definition as DotNetGenericOwnerPhysicalTypeDefIdentity.Local)
                .toSnapshot(),
            methodKind(methodImpl.declaration).toSnapshot(),
        )
    }
    val ownerName = checkNotNull(authority.inputOrNull(
        family.types.getValue(DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind.NATURAL_INTERFACE),
    )).logicalOwnerName
    val implementationOwnerName = checkNotNull(authority.inputOrNull(
        family.types.getValue(DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind.IMPLEMENTATION_CLASS),
    )).logicalOwnerName
    val logicalMemberName = family.logicalMember.owner.name.asString()
    val comparisonSnapshot = DotNetGenericOwnerCompleteEmissionFamilyComparisonSnapshot(
        scope = expectedScope,
        ownerName = ownerName,
        logicalMemberName = logicalMemberName,
        implementationOwnerName = implementationOwnerName,
        status = comparison.status,
        typeDefs = comparison.typeDefs.toSnapshot(expected.typeDefs.size, actual.typeDefs),
        methodDefs = comparison.methodDefs.toSnapshot(expected.methodDefs.size, actual.methodDefs),
        methodImpls = comparison.methodImpls.toSnapshot(expected.methodImpls.size, actual.methodImpls),
        expectedTypeDefs = expectedTypeSnapshots,
        expectedMethodDefs = expectedMethodSnapshots,
        expectedMethodImpls = expectedMethodImplSnapshots,
    )
    val actualSealedEvidence = sealedManifestEvidence(
        actualTypeDefs,
        actualMethodDefs,
        actualMethodImpls,
    ).requireImplicitNaturalImplementationNameMatch(expectedMethods)
    val sealedInspection = inspectDotNetGenericOwnerSealedEmissionSignatureIndex(
        expected,
        actualSealedEvidence,
    )
    var producerSealedFamilyBody: DotNetProducerGenericOwnerSealedFamilyBody? = null
    val sealedSnapshot = when (val sealedBinding = sealedInspection.binding) {
        is DotNetGenericOwnerPhysicalBindingResult.Bound -> {
            val index = sealedBinding.value
            val typeKindsByKey = family.types.map { entry ->
                allocator.expectedType(entry.value, typeDescriptions.getValue(entry.key)) to entry.key
            }.toMap()
            val typeOwnerNamesByKey = family.types.map { entry ->
                allocator.expectedType(entry.value, typeDescriptions.getValue(entry.key)) to
                        checkNotNull(authority.inputOrNull(entry.value)).logicalOwnerName
            }.toMap()
            val aliasViewsByKey = family.typeAliases.values.flatten().associate { alias ->
                allocator.alias(alias) to alias.view?.toSnapshot()
            }
            val methodsByKey = expectedMethods.associateBy(CompleteExpectedMethod::key)
            val methodKindsByKey = expectedMethods.associate { method -> method.key to method.kind }
            val actualMethodsByKey = (actualMethodDefs as
                    DotNetGenericOwnerCompleteEmissionRowsEvidence.Known).rows.associateBy { method ->
                method.sealedRow.structural.identityKey
            }
            val sealedTypeDefs = typeKindsByKey.map { entry ->
                val key = entry.key
                val kind = entry.value.toSnapshot()
                val row = when (val binding = index.typeDef(key)) {
                    is DotNetGenericOwnerPhysicalBindingResult.Bound -> binding.value
                    else -> error("a bound sealed family lost a selected TypeDef")
                }
                val structural = row.structural
                DotNetGenericOwnerSealedEmissionTypeDefSnapshot(
                    kind = kind,
                    physicalPath = row.physicalPath,
                    flags = row.flags.toSnapshot(),
                    structural = DotNetGenericOwnerCompleteEmissionTypeDefSnapshot(
                        kind = kind,
                        ownerName = typeOwnerNamesByKey.getValue(key),
                        physicalAliasViews = structural.aliases.map { alias ->
                            aliasViewsByKey.getValue(alias)
                        },
                        genericArity = structural.genericArity,
                        category = structural.category,
                        genericParameterVariances = structural.genericParameters.map { parameter ->
                            parameter.variance.toSnapshot()
                        },
                        genericParameterConstraintCounts = structural.genericParameters.map { parameter ->
                            parameter.constraints.size
                        },
                        baseTypeEdgeCount = structural.directEdges.count { edge ->
                            edge.kind == DotNetGenericOwnerDirectSupertypeKind.BASE_CLASS
                        },
                        interfaceEdgeCount = structural.directEdges.count { edge ->
                            edge.kind == DotNetGenericOwnerDirectSupertypeKind.INTERFACE
                        },
                    ),
                )
            }
            val sealedMethodDefs = methodKindsByKey.map { entry ->
                val key = entry.key
                val kind = entry.value.toSnapshot()
                val row = when (val binding = index.methodDef(key)) {
                    is DotNetGenericOwnerPhysicalBindingResult.Bound -> binding.value
                    else -> error("a bound sealed family lost a selected MethodDef")
                }
                val method = methodsByKey.getValue(key)
                DotNetGenericOwnerSealedEmissionMethodDefSnapshot(
                    kind = kind,
                    physicalName = row.physicalName,
                    physicalGenericParameterNames = row.physicalGenericParameterNames,
                    flags = row.toFlagsSnapshot(),
                    structural = DotNetGenericOwnerCompleteEmissionMethodDefSnapshot(
                        kind = kind,
                        role = method.identity.role,
                        ownerKind = typeKindsByKey.getValue(row.structural.header.owner).toSnapshot(),
                        genericArity = row.structural.header.genericArity,
                        genericParameterVariances = row.structural.genericParameters.map { parameter ->
                            parameter.variance.toSnapshot()
                        },
                        genericParameterConstraintCounts = row.structural.genericParameters.map { parameter ->
                            parameter.constraints.size
                        },
                    ),
                    header = actualMethodsByKey.getValue(key).snapshot,
                )
            }
            val sealedMethodImpls = expectedMethodImplRowsByKind.map { entry ->
                val expectedRow = entry.value
                val row = index.methodImpls(
                    expectedRow.implementingTypeDefKey,
                    expectedRow.bodyMethodDefKey,
                ).single { actualRow -> actualRow == expectedRow }
                val declarationOwnerType = when (val carrier = row.declarationOwner) {
                    is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.Construction -> carrier.definition
                    else -> error("a bound complete-family MethodImpl requires a constructed declaration owner")
                }
                DotNetGenericOwnerCompleteEmissionMethodImplSnapshot(
                    kind = entry.key.toSnapshot(),
                    implementingTypeKind = typeKindsByKey.getValue(row.implementingTypeDefKey).toSnapshot(),
                    bodyMethodKind = methodKindsByKey.getValue(row.bodyMethodDefKey).toSnapshot(),
                    declarationOwnerTypeKind = typeKindsByKey.getValue(declarationOwnerType).toSnapshot(),
                    declarationMethodKind = methodKindsByKey.getValue(row.declarationMethodDefKey).toSnapshot(),
                )
            }
            producerSealedFamilyBody = DotNetProducerGenericOwnerSealedFamilyBody(
                typeDefs = DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind.entries.map { kind ->
                    val key = allocator.expectedType(
                        family.types.getValue(kind),
                        typeDescriptions.getValue(kind),
                    )
                    val row = when (val binding = index.typeDef(key)) {
                        is DotNetGenericOwnerPhysicalBindingResult.Bound -> binding.value
                        else -> error("a bound sealed family lost a producer TypeDef row")
                    }
                    DotNetProducerGenericOwnerSealedTypeDef(
                        DotNetProducerGenericOwnerSealedTypeDefRole.valueOf(kind.name),
                        row,
                    )
                },
                methodDefs = DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodKind.entries.map { kind ->
                    val method = expectedMethods.single { candidate -> candidate.kind == kind }
                    val row = when (val binding = index.methodDef(method.key)) {
                        is DotNetGenericOwnerPhysicalBindingResult.Bound -> binding.value
                        else -> error("a bound sealed family lost a producer MethodDef row")
                    }
                    val resultDomain = when (val result = method.reference.signature.resultLayout) {
                        is DotNetGenericOwnerPhysicalCallableResultLayoutReference.Direct -> result.slot.domain
                        is DotNetGenericOwnerPhysicalCallableResultLayoutReference.SplitNullable ->
                            result.payloadSlot.domain
                        DotNetGenericOwnerPhysicalCallableResultLayoutReference.Void -> null
                    }
                    DotNetProducerGenericOwnerSealedMethodDef(
                        DotNetProducerGenericOwnerSealedMethodDefRole.valueOf(kind.name),
                        row,
                        method.reference.signature.parameterSlots.map { slot -> slot.domain },
                        resultDomain,
                    )
                },
                methodImpls = DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodImplKind.entries.map { kind ->
                    val expectedRow = expectedMethodImplRowsByKind.getValue(kind)
                    val row = index.methodImpls(
                        expectedRow.implementingTypeDefKey,
                        expectedRow.bodyMethodDefKey,
                    ).single { candidate -> candidate == expectedRow }
                    DotNetProducerGenericOwnerSealedMethodImpl(
                        DotNetProducerGenericOwnerSealedMethodImplRole.valueOf(kind.name),
                        row,
                    )
                },
            )
            DotNetGenericOwnerSealedEmissionFamilySnapshot(
                scope = expectedScope,
                ownerName = ownerName,
                logicalMemberName = logicalMemberName,
                implementationOwnerName = implementationOwnerName,
                status = DotNetGenericOwnerPhysicalMethodDefEmissionComparisonStatus.MATCH,
                diagnostics = emptyList(),
                typeDefs = sealedTypeDefs,
                methodDefs = sealedMethodDefs,
                methodImpls = sealedMethodImpls,
            )
        }
        is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
            DotNetGenericOwnerSealedEmissionFamilySnapshot(
                expectedScope,
                ownerName,
                logicalMemberName,
                implementationOwnerName,
                DotNetGenericOwnerPhysicalMethodDefEmissionComparisonStatus.CONFLICT,
                sealedInspection.diagnostics.ifEmpty { listOf(sealedBinding.reason) },
                emptyList(),
                emptyList(),
                emptyList(),
            )
        DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
            DotNetGenericOwnerSealedEmissionFamilySnapshot(
                expectedScope,
                ownerName,
                logicalMemberName,
                implementationOwnerName,
                DotNetGenericOwnerPhysicalMethodDefEmissionComparisonStatus.UNAVAILABLE,
                sealedInspection.diagnostics,
                emptyList(),
                emptyList(),
                emptyList(),
            )
    }
    val semanticEquivalenceForwardingEvidence = when {
        observedSemanticEquivalenceForwardingEvidence is
                DotNetGenericOwnerSemanticEquivalenceForwardingEvidence.Conflict ->
            observedSemanticEquivalenceForwardingEvidence
        sealedSnapshot.status == DotNetGenericOwnerPhysicalMethodDefEmissionComparisonStatus.CONFLICT ->
            DotNetGenericOwnerSemanticEquivalenceForwardingEvidence.Conflict(
                sealedSnapshot.diagnostics.joinToString("; ").ifEmpty {
                    "the forwarding endpoints did not reach the sealed-emission authority epoch"
                },
            )
        observedSemanticEquivalenceForwardingEvidence is
                DotNetGenericOwnerSemanticEquivalenceForwardingEvidence.Unavailable ->
            observedSemanticEquivalenceForwardingEvidence
        sealedSnapshot.status != DotNetGenericOwnerPhysicalMethodDefEmissionComparisonStatus.MATCH ->
            DotNetGenericOwnerSemanticEquivalenceForwardingEvidence.Unavailable(
                sealedSnapshot.diagnostics.joinToString("; ").ifEmpty {
                    "the forwarding endpoints did not reach the sealed-emission authority epoch"
                },
            )
        else -> observedSemanticEquivalenceForwardingEvidence
    }
    return DotNetGenericOwnerCompleteEmissionFamilyProducts(
        comparisonSnapshot,
        sealedSnapshot,
        producerSealedFamilyBody,
        semanticEquivalenceForwardingEvidence,
        family.logicalMember,
        family.implementationMember,
    )
}

private fun actualCompleteTypeDefs(
    authority: DotNetLocalGenericOwnerPhysicalAuthority,
    allocator: EmissionIdentityAllocator,
    family: DotNetLocalGenericOwnerPhysicalCompleteEmissionFamily,
    currentMethod: DotNetGenericOwnerPhysicalMethodDefIdentity.Local,
    observations: List<DotNetGenericOwnerPhysicalTypeDefEmissionObservation>,
    otherScopeObservations: List<DotNetGenericOwnerPhysicalTypeDefEmissionObservation>,
): DotNetGenericOwnerCompleteEmissionRowsEvidence<DotNetGenericOwnerSealedEmissionTypeDefRow> {
    val ownerSymbols = family.typeAliases.values.flatten().map { identity -> identity.owner }.toSet()
    fun touches(observation: DotNetGenericOwnerPhysicalTypeDefEmissionObservation): Boolean =
        observation.claimedAliases.any { alias -> alias.owner in ownerSymbols }
    if (otherScopeObservations.any(::touches)) {
        return DotNetGenericOwnerCompleteEmissionRowsEvidence.Conflict(
            "a complete-family TypeDef was observed in another physical emission scope",
        )
    }
    val accumulator = CompleteRowsAccumulator<DotNetGenericOwnerSealedEmissionTypeDefRow>()
    observations.filter(::touches).forEach { observation ->
        val owner = when (val physicalType = observation.physicalType) {
            is DotNetGenericOwnerObservedMethodDefOwner.Local -> physicalType.typeDef
            is DotNetGenericOwnerObservedMethodDefOwner.Unbindable -> {
                accumulator.add(if (physicalType.isConflict) {
                    CompleteConversion.Conflict(physicalType.reason)
                } else {
                    CompleteConversion.Unavailable(physicalType.reason)
                })
                return@forEach
            }
        }
        val typeKey = when (val binding = allocator.actualType(owner)) {
            is EmissionIdentityAllocator.ActualType.Bound -> binding.key
            is EmissionIdentityAllocator.ActualType.Conflict -> {
                accumulator.add(CompleteConversion.Conflict(binding.reason))
                return@forEach
            }
        }
        val genericParameters = mutableListOf<DotNetGenericOwnerCompleteEmissionGenericParameterRow>()
        for (parameter in observation.genericParameters) {
            val constraints = mutableListOf<DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape>()
            for (constraint in parameter.constraints) {
                when (val conversion = constraint.toCompleteCarrierShape(authority, allocator, currentMethod)) {
                    is CompleteConversion.Known -> constraints += conversion.value
                    is CompleteConversion.Unavailable -> {
                        accumulator.add(conversion)
                        return@forEach
                    }
                    is CompleteConversion.Conflict -> {
                        accumulator.add(conversion)
                        return@forEach
                    }
                }
            }
            if (constraints.size != constraints.toSet().size) {
                accumulator.add(CompleteConversion.Conflict(
                    "an emitted TypeDef GenericParam contains duplicate normalized constraints",
                ))
                return@forEach
            }
            genericParameters += DotNetGenericOwnerCompleteEmissionGenericParameterRow(
                parameter.variance,
                constraints,
            )
        }
        val edges = mutableListOf<DotNetGenericOwnerCompleteEmissionTypeDefEdgeRow>()
        for (edge in observation.directSupertypes) {
            when (val target = edge.target.toCompleteCarrierShape(authority, allocator, currentMethod)) {
                is CompleteConversion.Known -> edges +=
                    DotNetGenericOwnerCompleteEmissionTypeDefEdgeRow(edge.kind, target.value)
                is CompleteConversion.Unavailable -> {
                    accumulator.add(target)
                    return@forEach
                }
                is CompleteConversion.Conflict -> {
                    accumulator.add(target)
                    return@forEach
                }
            }
        }
        accumulator.add(CompleteConversion.Known(
            DotNetGenericOwnerSealedEmissionTypeDefRow(
                structural = DotNetGenericOwnerCompleteEmissionTypeDefRow(
                    typeKey,
                    owner.aliases.map(allocator::alias),
                    owner.genericArity,
                    owner.category,
                    genericParameters,
                    edges,
                ),
                physicalPath = observation.physicalTypePath,
                flags = observation.flags,
            ),
        ))
    }
    return accumulator.evidence()
}

private fun actualCompleteMethodDefs(
    authority: DotNetLocalGenericOwnerPhysicalAuthority,
    allocator: EmissionIdentityAllocator,
    expected: List<CompleteExpectedMethod>,
    observations: List<DotNetGenericOwnerPhysicalMethodDefHeaderObservation>,
    otherScopeObservations: List<DotNetGenericOwnerPhysicalMethodDefHeaderObservation>,
): DotNetGenericOwnerCompleteEmissionRowsEvidence<CompleteActualMethod> {
    fun touches(observation: DotNetGenericOwnerPhysicalMethodDefHeaderObservation): Boolean =
        expected.any { method ->
            method.emittedFunction === observation.physicalFunction ||
                    observation.physicalMethodIdentity?.sameLocalMethodIdentityAs(method.identity) == true
        }
    if (otherScopeObservations.any(::touches)) {
        return DotNetGenericOwnerCompleteEmissionRowsEvidence.Conflict(
            "a complete-family MethodDef was observed in another physical emission scope",
        )
    }
    val accumulator = CompleteRowsAccumulator<CompleteActualMethod>()
    observations.filter(::touches).forEach { observation ->
        val identity = observation.physicalMethodIdentity
        if (identity == null) {
            accumulator.add(CompleteConversion.Unavailable(
                "a selected emitted MethodDef has no bound physical identity",
            ))
            return@forEach
        }
        val byFunction = expected.filter { method -> method.emittedFunction === observation.physicalFunction }
        val byIdentity = expected.filter { method -> identity.sameLocalMethodIdentityAs(method.identity) }
        val method = byFunction.singleOrNull()?.takeIf { candidate -> candidate in byIdentity }
        if (method == null || byFunction.size != 1 || byIdentity.size != 1) {
            accumulator.add(CompleteConversion.Conflict(
                "an emitted MethodDef function and physical identity select different complete-family rows",
            ))
            return@forEach
        }
        val owner = when (val physicalOwner = observation.physicalMethodOwner) {
            is DotNetGenericOwnerObservedMethodDefOwner.Local -> physicalOwner.typeDef
            is DotNetGenericOwnerObservedMethodDefOwner.Unbindable -> {
                accumulator.add(if (physicalOwner.isConflict) {
                    CompleteConversion.Conflict(physicalOwner.reason)
                } else {
                    CompleteConversion.Unavailable(physicalOwner.reason)
                })
                return@forEach
            }
        }
        val genericParameters = when (val conversion = observation.toCompleteMethodGenericParameters(
            authority,
            allocator,
            method.identity,
        )) {
            is CompleteConversion.Known -> conversion.value
            is CompleteConversion.Unavailable -> {
                accumulator.add(conversion)
                return@forEach
            }
            is CompleteConversion.Conflict -> {
                accumulator.add(conversion)
                return@forEach
            }
        }
        when (val actual = buildDotNetGenericOwnerActualMethodDefEmissionHeaderEvidence(
            authority,
            allocator,
            method.identity,
            observation,
            owner,
        )) {
            is DotNetGenericOwnerPhysicalMethodDefEmissionHeaderEvidence.Known ->
                accumulator.add(CompleteConversion.Known(
                    CompleteActualMethod(
                        sealedRow = DotNetGenericOwnerSealedEmissionMethodDefRow(
                            structural = DotNetGenericOwnerCompleteEmissionMethodDefRow(
                                method.key,
                                actual.shape,
                                genericParameters.rows,
                            ),
                            physicalName = observation.physicalMethodName,
                            physicalGenericParameterNames = genericParameters.physicalNames,
                            visibility = observation.visibility,
                            dispatch = observation.dispatch,
                            isHideBySig = observation.isHideBySig,
                            isSpecialName = observation.isSpecialName,
                            isRuntimeSpecialName = observation.isRuntimeSpecialName,
                        ),
                        snapshot = actual.snapshot,
                    ),
                ))
            is DotNetGenericOwnerPhysicalMethodDefEmissionHeaderEvidence.Unavailable ->
                accumulator.add(CompleteConversion.Unavailable(actual.reason))
            is DotNetGenericOwnerPhysicalMethodDefEmissionHeaderEvidence.Conflict ->
                accumulator.add(CompleteConversion.Conflict(actual.reason))
        }
    }
    return accumulator.evidence()
}

private data class CompleteActualMethodGenericParameters(
    val rows: List<DotNetGenericOwnerCompleteEmissionGenericParameterRow>,
    val physicalNames: List<String>,
)

private fun DotNetGenericOwnerPhysicalMethodDefHeaderObservation.toCompleteMethodGenericParameters(
    authority: DotNetLocalGenericOwnerPhysicalAuthority,
    allocator: EmissionIdentityAllocator,
    currentMethod: DotNetGenericOwnerPhysicalMethodDefIdentity.Local,
): CompleteConversion<CompleteActualMethodGenericParameters> {
    if (genericParameters.size != genericArity) {
        return CompleteConversion.Conflict(
            "an emitted MethodDef has an incoherent GenericParam row count",
        )
    }
    val rows = mutableListOf<DotNetGenericOwnerCompleteEmissionGenericParameterRow>()
    for (parameter in genericParameters) {
        if (parameter.variance != DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT) {
            return CompleteConversion.Conflict(
                "an emitted MethodDef GenericParam has illegal declaration-site variance",
            )
        }
        val constraints = mutableListOf<DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape>()
        for (constraint in parameter.constraints) {
            val conversion = constraint.toActualCarrier(authority, allocator, currentMethod)
            conversion.conflictReason?.let { reason -> return CompleteConversion.Conflict(reason) }
            conversion.unavailableReason?.let { reason -> return CompleteConversion.Unavailable(reason) }
            constraints += checkNotNull(conversion.known).shape
        }
        if (constraints.size != constraints.toSet().size) {
            return CompleteConversion.Conflict(
                "an emitted MethodDef GenericParam contains duplicate normalized constraints",
            )
        }
        rows += DotNetGenericOwnerCompleteEmissionGenericParameterRow(
            parameter.variance,
            constraints,
        )
    }
    return CompleteConversion.Known(
        CompleteActualMethodGenericParameters(
            rows = rows,
            physicalNames = genericParameters.map { parameter -> parameter.physicalName },
        ),
    )
}

private fun actualCompleteMethodImpls(
    authority: DotNetLocalGenericOwnerPhysicalAuthority,
    allocator: EmissionIdentityAllocator,
    family: DotNetLocalGenericOwnerPhysicalCompleteEmissionFamily,
    expected: List<CompleteExpectedMethod>,
    observations: List<DotNetGenericOwnerPhysicalMethodImplObservation>,
    otherScopeObservations: List<DotNetGenericOwnerPhysicalMethodImplObservation>,
): DotNetGenericOwnerCompleteEmissionRowsEvidence<DotNetGenericOwnerCompleteEmissionMethodImplRow> {
    val expectedImplementation = family.types.getValue(
        DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind.IMPLEMENTATION_CLASS,
    )
    fun expectedEndpoint(
        identity: DotNetGenericOwnerPhysicalMethodDefIdentity.Local,
    ): CompleteExpectedMethod = expected.single { method ->
        method.identity.sameLocalMethodIdentityAs(identity)
    }
    val expectedBodies = family.methodImpls.values.map { methodImpl ->
        expectedEndpoint(methodImpl.body)
    }
    val expectedDeclarations = family.methodImpls.values.map { methodImpl ->
        expectedEndpoint(methodImpl.declaration)
    }
    fun endpointTouches(
        endpoints: List<CompleteExpectedMethod>,
        function: IrSimpleFunctionSymbol,
        identity: DotNetGenericOwnerPhysicalMethodDefIdentity.Local?,
    ) = endpoints.any { method ->
            method.emittedFunction === function || identity?.sameLocalMethodIdentityAs(method.identity) == true
        }
    fun implementingTypeTouches(owner: DotNetGenericOwnerObservedMethodDefOwner): Boolean =
        owner is DotNetGenericOwnerObservedMethodDefOwner.Local && owner.typeDef.aliases.any { alias ->
            alias.sameLocalTypeIdentityAs(expectedImplementation)
        }
    fun touches(observation: DotNetGenericOwnerPhysicalMethodImplObservation): Boolean {
        val bodyTouches = endpointTouches(
            expectedBodies,
            observation.bodyFunction,
            observation.bodyIdentity,
        )
        val declarationTouches = endpointTouches(
            expectedDeclarations,
            observation.declarationFunction,
            observation.declarationIdentity,
        )
        // A declaration slot can be shared by every implementation of the same interface.
        // It assigns a row to this implementation family only together with this TypeDef;
        // the selected dispatcher body remains sufficient on its own so a wrong owner cannot
        // make that row disappear from fail-closed comparison.
        return bodyTouches || implementingTypeTouches(observation.implementingType) && declarationTouches
    }
    if (otherScopeObservations.any(::touches)) {
        return DotNetGenericOwnerCompleteEmissionRowsEvidence.Conflict(
            "a complete-family MethodImpl was observed in another physical emission scope",
        )
    }
    fun endpoint(
        function: IrSimpleFunctionSymbol,
        identity: DotNetGenericOwnerPhysicalMethodDefIdentity.Local?,
    ): CompleteConversion<CompleteExpectedMethod> {
        val byFunction = expected.filter { method -> method.emittedFunction === function }
        if (identity == null) {
            return if (byFunction.isEmpty()) {
                CompleteConversion.Conflict("a MethodImpl endpoint outside the family lacks physical identity")
            } else {
                CompleteConversion.Unavailable("a selected MethodImpl endpoint lacks physical identity")
            }
        }
        val byIdentity = expected.filter { method -> identity.sameLocalMethodIdentityAs(method.identity) }
        val selected = byFunction.singleOrNull()?.takeIf { candidate -> candidate in byIdentity }
        return if (selected != null && byFunction.size == 1 && byIdentity.size == 1) {
            CompleteConversion.Known(selected)
        } else {
            CompleteConversion.Conflict(
                "a MethodImpl endpoint function and physical identity select different complete-family rows",
            )
        }
    }
    val accumulator = CompleteRowsAccumulator<DotNetGenericOwnerCompleteEmissionMethodImplRow>()
    observations.filter(::touches).forEach { observation ->
        val body = endpoint(observation.bodyFunction, observation.bodyIdentity)
        val declaration = endpoint(observation.declarationFunction, observation.declarationIdentity)
        if (body !is CompleteConversion.Known || declaration !is CompleteConversion.Known) {
            @Suppress("UNCHECKED_CAST")
            accumulator.add((when {
                body is CompleteConversion.Conflict -> body
                declaration is CompleteConversion.Conflict -> declaration
                body is CompleteConversion.Unavailable -> body
                declaration is CompleteConversion.Unavailable -> declaration
                else -> CompleteConversion.Conflict("a MethodImpl has an unclassified family endpoint")
            }) as CompleteConversion<DotNetGenericOwnerCompleteEmissionMethodImplRow>)
            return@forEach
        }
        val implementingType = when (val owner = observation.implementingType) {
            is DotNetGenericOwnerObservedMethodDefOwner.Local -> owner.typeDef
            is DotNetGenericOwnerObservedMethodDefOwner.Unbindable -> {
                accumulator.add(if (owner.isConflict) {
                    CompleteConversion.Conflict(owner.reason)
                } else {
                    CompleteConversion.Unavailable(owner.reason)
                })
                return@forEach
            }
        }
        val implementingKey = when (val binding = allocator.actualType(implementingType)) {
            is EmissionIdentityAllocator.ActualType.Bound -> binding.key
            is EmissionIdentityAllocator.ActualType.Conflict -> {
                accumulator.add(CompleteConversion.Conflict(binding.reason))
                return@forEach
            }
        }
        val declarationOwner = when (val conversion = observation.declarationOwner.toCompleteCarrierShape(
            authority,
            allocator,
            body.value.identity,
        )) {
            is CompleteConversion.Known -> conversion.value
            is CompleteConversion.Unavailable -> {
                accumulator.add(conversion)
                return@forEach
            }
            is CompleteConversion.Conflict -> {
                accumulator.add(conversion)
                return@forEach
            }
        }
        accumulator.add(CompleteConversion.Known(
            DotNetGenericOwnerCompleteEmissionMethodImplRow(
                implementingKey,
                body.value.key,
                declarationOwner,
                declaration.value.key,
            ),
        ))
    }
    return accumulator.evidence()
}

private fun DotNetGenericOwnerObservedMethodCarrier.toCompleteCarrierShape(
    authority: DotNetLocalGenericOwnerPhysicalAuthority,
    allocator: EmissionIdentityAllocator,
    currentMethod: DotNetGenericOwnerPhysicalMethodDefIdentity.Local,
): CompleteConversion<DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape> {
    if (containsMethodParameter()) {
        return CompleteConversion.Conflict(
            "a TypeDef edge, GenericParam constraint, or MethodImpl owner cannot contain a method parameter",
        )
    }
    val conversion = toActualCarrier(authority, allocator, currentMethod)
    return when {
        conversion.conflictReason != null -> CompleteConversion.Conflict(conversion.conflictReason)
        conversion.unavailableReason != null -> CompleteConversion.Unavailable(conversion.unavailableReason)
        else -> CompleteConversion.Known(checkNotNull(conversion.known).shape)
    }
}

private fun DotNetGenericOwnerObservedMethodCarrier.containsMethodParameter(): Boolean = when (this) {
    is DotNetGenericOwnerObservedMethodCarrier.MethodParameter -> true
    is DotNetGenericOwnerObservedMethodCarrier.LocalConstruction -> arguments.any { it.containsMethodParameter() }
    is DotNetGenericOwnerObservedMethodCarrier.SzArray -> element.containsMethodParameter()
    is DotNetGenericOwnerObservedMethodCarrier.ByReference -> element.containsMethodParameter()
    else -> false
}

private fun <T> DotNetGenericOwnerCompleteEmissionRowsComparison.toSnapshot(
    expectedCount: Int,
    evidence: DotNetGenericOwnerCompleteEmissionRowsEvidence<T>,
) = DotNetGenericOwnerCompleteEmissionRowsComparisonSnapshot(
    status,
    expectedCount,
    (evidence as? DotNetGenericOwnerCompleteEmissionRowsEvidence.Known)?.rows?.size,
    diagnostics,
)

private fun DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind.toSnapshot() =
    DotNetGenericOwnerCompleteEmissionTypeKindSnapshot.valueOf(name)

private fun DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodKind.toSnapshot() =
    DotNetGenericOwnerCompleteEmissionMethodKindSnapshot.valueOf(name)

private fun DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodImplKind.toSnapshot() =
    DotNetGenericOwnerCompleteEmissionMethodImplKindSnapshot.valueOf(name)

private fun DotNetGenericOwnerPhysicalTypeParameterVariance.toSnapshot() =
    DotNetGenericOwnerCompleteEmissionTypeParameterVarianceSnapshot.valueOf(name)

private fun DotNetGenericInterfaceView.toSnapshot() =
    DotNetGenericOwnerPhysicalValueShadowTypeDefView.valueOf(name)

private fun DotNetIlRawTypeDefFlags.toSnapshot() =
    DotNetGenericOwnerSealedEmissionTypeDefFlagsSnapshot(
        visibility = DotNetGenericOwnerSealedEmissionTypeDefVisibilitySnapshot.valueOf(visibility.name),
        isAutoLayout = layout == DotNetIlRawTypeDefLayout.AUTO,
        isAnsi = stringFormat == DotNetIlRawTypeDefStringFormat.ANSI,
        isInterface = isInterface,
        isAbstract = isAbstract,
        isSealed = isSealed,
        isBeforeFieldInit = isBeforeFieldInit,
    )

private fun DotNetGenericOwnerSealedEmissionMethodDefRow.toFlagsSnapshot() =
    DotNetGenericOwnerSealedEmissionMethodDefFlagsSnapshot(
        visibility = when (visibility) {
            DotNetIlRawMethodDefVisibility.PUBLIC ->
                DotNetGenericOwnerPhysicalMethodDefEmissionVisibility.PUBLIC
            DotNetIlRawMethodDefVisibility.FAMILY ->
                DotNetGenericOwnerPhysicalMethodDefEmissionVisibility.FAMILY
            DotNetIlRawMethodDefVisibility.ASSEMBLY ->
                DotNetGenericOwnerPhysicalMethodDefEmissionVisibility.ASSEMBLY
            DotNetIlRawMethodDefVisibility.FAMILY_OR_ASSEMBLY ->
                DotNetGenericOwnerPhysicalMethodDefEmissionVisibility.FAMILY_OR_ASSEMBLY
            DotNetIlRawMethodDefVisibility.FAMILY_AND_ASSEMBLY ->
                DotNetGenericOwnerPhysicalMethodDefEmissionVisibility.FAMILY_AND_ASSEMBLY
            DotNetIlRawMethodDefVisibility.PRIVATE ->
                DotNetGenericOwnerPhysicalMethodDefEmissionVisibility.PRIVATE
        },
        isInstance = dispatch.isInstance,
        isVirtual = dispatch.isVirtual,
        isNewSlot = dispatch.isNewSlot,
        isAbstract = dispatch.isAbstract,
        isFinal = dispatch.isFinal,
        isHideBySig = isHideBySig,
        isSpecialName = isSpecialName,
        isRuntimeSpecialName = isRuntimeSpecialName,
    )
