/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
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

internal data class DotNetGenericOwnerCompleteEmissionFamilyProducts(
    val comparison: DotNetGenericOwnerCompleteEmissionFamilyComparisonSnapshot,
    val sealed: DotNetGenericOwnerSealedEmissionFamilySnapshot,
    val producerSealedFamilyBody: DotNetProducerGenericOwnerSealedFamilyBody?,
    private val logicalMember: IrSimpleFunctionSymbol,
    private val implementationMember: IrSimpleFunctionSymbol,
) {
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
    return DotNetGenericOwnerCompleteEmissionFamilyProducts(
        comparisonSnapshot,
        sealedSnapshot,
        producerSealedFamilyBody,
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
