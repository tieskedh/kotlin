/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol

/**
 * Correlates one opaque BOUND local callable family with final emitter evidence.
 *
 * This is a partial sealed overlay, not a declaration-index epoch transition. In particular,
 * missing final evidence remains unavailable and can never be inherited from the additive BOUND
 * index. IR symbols exist only in this adapter; the structural comparator and returned snapshots
 * contain no IR handles.
 */
internal fun compareDotNetGenericOwnerPhysicalMethodDefEmissionFamily(
    authority: DotNetLocalGenericOwnerPhysicalAuthority,
    scope: DotNetIlEmissionScope,
    logicalMember: IrSimpleFunctionSymbol,
    family: DotNetLocalGenericOwnerPhysicalCallableFamily,
    observations: List<DotNetGenericOwnerPhysicalMethodDefHeaderObservation>,
    otherScopeObservations: List<DotNetGenericOwnerPhysicalMethodDefHeaderObservation>,
): DotNetGenericOwnerPhysicalMethodDefEmissionFamilyComparisonSnapshot? {
    val declarations = authority.boundDeclarations ?: return null
    val logicalOwner = logicalMember.owner.parent as? IrClass ?: return null
    val naturalIdentity = family.selectedMethod(
        DotNetLocalGenericOwnerPhysicalCallableEntryKind.NATURAL_INTERFACE,
    ) as? DotNetGenericOwnerPhysicalMethodDefIdentity.Local ?: return null
    val semanticIdentity = family.selectedMethod(
        DotNetLocalGenericOwnerPhysicalCallableEntryKind.SEMANTIC_CAPABILITY_INTERFACE_SLOT,
    ) as? DotNetGenericOwnerPhysicalMethodDefIdentity.Local ?: return null
    val natural = declarations.methodDescriptionOrNull(naturalIdentity) ?: return null
    val semantic = declarations.methodDescriptionOrNull(semanticIdentity) ?: return null
    val naturalOwner = natural.declaringType as? DotNetGenericOwnerPhysicalTypeDefIdentity.Local
        ?: return null
    val semanticOwner = semantic.declaringType as? DotNetGenericOwnerPhysicalTypeDefIdentity.Local
        ?: return null

    val logicalScope = DotNetIlEmissionScope.entries.single { candidate -> candidate.owns(logicalOwner) }
    if (logicalScope != scope) return null

    val allocator = EmissionIdentityAllocator()
    val endpointInputs = listOf(
        EndpointInput(
            DotNetGenericOwnerPhysicalMethodDefEmissionEntryKind.NATURAL_INTERFACE,
            naturalIdentity,
            natural,
        ),
        EndpointInput(
            DotNetGenericOwnerPhysicalMethodDefEmissionEntryKind.SEMANTIC_CAPABILITY_INTERFACE_SLOT,
            semanticIdentity,
            semantic,
        ),
    )
    val physicalScopes = listOf(naturalOwner, semanticOwner).map { owner ->
        DotNetIlEmissionScope.entries.single { candidate -> candidate.owns(owner.owner.owner) }
    }.toSet()
    val scopeConflict = physicalScopes != setOf(scope)

    // Register every expected identity before any actual alias is bound. Final-emission evidence
    // may match an existing BOUND identity, but it must never create or merge that authority.
    val expectedEndpoints = endpointInputs.map { input ->
        input to (buildExpectedHeader(authority, declarations, allocator, input)
            ?: error("BOUND local callable authority lost one of its own MethodDef descriptions"))
    }
    val endpoints = expectedEndpoints.map { expectedEndpoint ->
        val input = expectedEndpoint.first
        val expected = expectedEndpoint.second
        val candidates = if (scopeConflict) {
            listOf(DotNetGenericOwnerPhysicalMethodDefEmissionHeaderEvidence.Conflict(
                "the logical family and its physical MethodDef owners belong to different emission scopes",
            ))
        } else {
            correlateActualHeaders(
                authority,
                allocator,
                input,
                endpointInputs.map(EndpointInput::identity),
                observations,
                otherScopeObservations,
            )
        }
        compareDotNetGenericOwnerPhysicalMethodDefEmissionEndpoint(
            entryKind = input.entryKind,
            methodRole = input.identity.role,
            expectedShape = expected.shape,
            expectedSnapshot = expected.snapshot,
            actualCandidates = candidates,
        )
    }
    val status = when {
        endpoints.any { endpoint ->
            endpoint.status == DotNetGenericOwnerPhysicalMethodDefEmissionComparisonStatus.CONFLICT
        } -> DotNetGenericOwnerPhysicalMethodDefEmissionComparisonStatus.CONFLICT
        endpoints.any { endpoint ->
            endpoint.status == DotNetGenericOwnerPhysicalMethodDefEmissionComparisonStatus.UNAVAILABLE
        } -> DotNetGenericOwnerPhysicalMethodDefEmissionComparisonStatus.UNAVAILABLE
        else -> DotNetGenericOwnerPhysicalMethodDefEmissionComparisonStatus.MATCH
    }
    return DotNetGenericOwnerPhysicalMethodDefEmissionFamilyComparisonSnapshot(
        scope = scope,
        ownerName = logicalOwner.dotNetPhysicalValueStableName(),
        logicalMemberName = logicalMember.owner.name.asString(),
        status = status,
        endpoints = endpoints,
    )
}

private data class EndpointInput(
    val entryKind: DotNetGenericOwnerPhysicalMethodDefEmissionEntryKind,
    val identity: DotNetGenericOwnerPhysicalMethodDefIdentity.Local,
    val reference: DotNetGenericOwnerPhysicalMethodDefReference,
)

private data class BuiltExpectedHeader(
    val shape: DotNetGenericOwnerPhysicalMethodDefEmissionHeaderShape,
    val snapshot: DotNetGenericOwnerPhysicalMethodDefEmissionHeaderSnapshot,
)

private class LocalTypeIdentityKey(
    private val identity: DotNetGenericOwnerPhysicalTypeDefIdentity.Local,
) {
    override fun equals(other: Any?): Boolean = other is LocalTypeIdentityKey &&
            identity.owner === other.identity.owner && identity.view == other.identity.view

    override fun hashCode(): Int = 31 * System.identityHashCode(identity.owner) +
            (identity.view?.hashCode() ?: 0)
}

private class LocalMethodIdentityKey(
    private val identity: DotNetGenericOwnerPhysicalMethodDefIdentity.Local,
) {
    override fun equals(other: Any?): Boolean = other is LocalMethodIdentityKey &&
            identity.function === other.identity.function && identity.role == other.identity.role

    override fun hashCode(): Int = 31 * System.identityHashCode(identity.function) +
            (identity.role?.hashCode() ?: 0)
}

/** Invocation-local keys strip IR from the pure comparator without turning names into identity. */
internal class EmissionIdentityAllocator {
    private data class ExpectedType(
        val key: DotNetGenericOwnerPhysicalMethodDefEmissionTypeKey,
        val genericArity: Int,
        val category: DotNetGenericOwnerPhysicalNamedTypeCategory,
    )

    sealed interface ActualType {
        data class Bound(val key: DotNetGenericOwnerPhysicalMethodDefEmissionTypeKey) : ActualType
        data class Conflict(val reason: String) : ActualType
    }

    private val expectedTypes = linkedMapOf<LocalTypeIdentityKey, ExpectedType>()
    private val actualOnlyTypes = linkedMapOf<
            DotNetGenericOwnerObservedPhysicalTypeDefKey,
            DotNetGenericOwnerPhysicalMethodDefEmissionTypeKey,
            >()
    private val methods = linkedMapOf<LocalMethodIdentityKey, DotNetGenericOwnerPhysicalMethodDefEmissionMethodKey>()
    private var nextTypeKey = 0

    fun expectedType(
        identity: DotNetGenericOwnerPhysicalTypeDefIdentity.Local,
        description: DotNetGenericOwnerPhysicalTypeDefReference,
    ): DotNetGenericOwnerPhysicalMethodDefEmissionTypeKey {
        val identityKey = LocalTypeIdentityKey(identity)
        val existing = expectedTypes[identityKey]
        if (existing != null) {
            check(existing.genericArity == description.genericArity &&
                    existing.category == description.category) {
                "BOUND authority assigned contradictory facts to one expected TypeDef identity"
            }
            return existing.key
        }
        val key = DotNetGenericOwnerPhysicalMethodDefEmissionTypeKey(nextTypeKey++)
        expectedTypes[identityKey] = ExpectedType(key, description.genericArity, description.category)
        return key
    }

    fun actualType(typeDef: DotNetGenericOwnerObservedLocalTypeDef): ActualType {
        val matches = typeDef.aliases.mapNotNull { alias ->
            expectedTypes[LocalTypeIdentityKey(alias)]
        }.distinctBy(ExpectedType::key)
        if (matches.size > 1) {
            return ActualType.Conflict(
                "one emitted physical TypeDef aliases multiple distinct BOUND TypeDef identities",
            )
        }
        val expected = matches.singleOrNull()
        if (expected != null) {
            if (expected.genericArity != typeDef.genericArity || expected.category != typeDef.category) {
                return ActualType.Conflict(
                    "an emitted TypeDef alias contradicts the BOUND arity or category",
                )
            }
            return ActualType.Bound(expected.key)
        }
        return ActualType.Bound(actualOnlyTypes.getOrPut(typeDef.physicalKey) {
            DotNetGenericOwnerPhysicalMethodDefEmissionTypeKey(nextTypeKey++)
        })
    }

    fun method(identity: DotNetGenericOwnerPhysicalMethodDefIdentity.Local):
            DotNetGenericOwnerPhysicalMethodDefEmissionMethodKey =
        methods.getOrPut(LocalMethodIdentityKey(identity)) {
            DotNetGenericOwnerPhysicalMethodDefEmissionMethodKey(methods.size)
        }
}

private fun buildExpectedHeader(
    authority: DotNetLocalGenericOwnerPhysicalAuthority,
    declarations: DotNetGenericOwnerPhysicalDeclarationIndex,
    allocator: EmissionIdentityAllocator,
    input: EndpointInput,
): BuiltExpectedHeader? {
    val owner = input.reference.declaringType as? DotNetGenericOwnerPhysicalTypeDefIdentity.Local
        ?: return null
    val ownerDescription = declarations.typeDescriptionOrNull(owner) ?: return null
    val ownerKey = allocator.expectedType(owner, ownerDescription)
    val ownerSnapshot = owner.toSnapshot(authority, ownerDescription)
    val receiverShape = if (input.reference.signature.isInstance) {
        DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.Construction(
            ownerKey,
            (0 until ownerDescription.genericArity).map { index ->
                DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.OwnerParameter(ownerKey, index)
            },
        )
    } else {
        null
    }
    val receiverSnapshot = if (input.reference.signature.isInstance) {
        DotNetGenericOwnerPhysicalMethodDefEmissionCarrierSnapshot(
            kind = DotNetGenericOwnerPhysicalMethodDefEmissionCarrierKind.LOCAL_CONSTRUCTION,
            typeDef = ownerSnapshot,
            arguments = (0 until ownerDescription.genericArity).map { index ->
                DotNetGenericOwnerPhysicalMethodDefEmissionCarrierSnapshot(
                    kind = DotNetGenericOwnerPhysicalMethodDefEmissionCarrierKind.OWNER_PARAMETER,
                    typeDef = ownerSnapshot,
                    parameterIndex = index,
                )
            },
        )
    } else {
        null
    }
    val parameterPairs = input.reference.signature.parameterSlots.map { slot ->
        expectedCarrier(authority, declarations, allocator, input.identity, slot.carrier)
            ?: return null
    }
    val result = expectedResult(
        authority,
        declarations,
        allocator,
        input.identity,
        input.reference.signature.resultLayout,
    ) ?: return null
    val visibility = input.reference.visibility.toEmissionVisibility()
    return BuiltExpectedHeader(
        DotNetGenericOwnerPhysicalMethodDefEmissionHeaderShape(
            owner = ownerKey,
            ownerGenericArity = ownerDescription.genericArity,
            ownerCategory = ownerDescription.category,
            visibility = visibility,
            dispatch = input.reference.dispatch,
            isInstance = input.reference.signature.isInstance,
            genericArity = input.reference.signature.genericArity,
            receiverCarrier = receiverShape,
            ordinaryParameterCarriers = parameterPairs.map { pair -> pair.first },
            result = result.first,
        ),
        DotNetGenericOwnerPhysicalMethodDefEmissionHeaderSnapshot(
            methodIdentity = DotNetGenericOwnerPhysicalMethodDefEmissionIdentitySnapshot(
                input.identity.role,
            ),
            owner = ownerSnapshot,
            physicalMethodNameForDiagnostics = null,
            visibility = visibility,
            dispatch = DotNetGenericOwnerPhysicalMethodDefEmissionDispatchSnapshot(
                input.reference.dispatch,
            ),
            isInstance = input.reference.signature.isInstance,
            genericArity = input.reference.signature.genericArity,
            receiverCarrier = receiverSnapshot,
            ordinaryParameterCarriers = parameterPairs.map { pair -> pair.second },
            result = result.second,
        ),
    )
}

private fun expectedResult(
    authority: DotNetLocalGenericOwnerPhysicalAuthority,
    declarations: DotNetGenericOwnerPhysicalDeclarationIndex,
    allocator: EmissionIdentityAllocator,
    method: DotNetGenericOwnerPhysicalMethodDefIdentity.Local,
    result: DotNetGenericOwnerPhysicalCallableResultLayoutReference,
): Pair<
        DotNetGenericOwnerPhysicalMethodDefEmissionResultShape,
        DotNetGenericOwnerPhysicalMethodDefEmissionResultSnapshot,
        >? = when (result) {
    DotNetGenericOwnerPhysicalCallableResultLayoutReference.Void ->
        DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.Void to
                DotNetGenericOwnerPhysicalMethodDefEmissionResultSnapshot(
                    DotNetGenericOwnerPhysicalMethodDefEmissionResultLayout.VOID,
                    carrier = null,
                    hasCanonicalOutBooleanNullFlag = false,
                )
    is DotNetGenericOwnerPhysicalCallableResultLayoutReference.Direct -> {
        val carrier = expectedCarrier(authority, declarations, allocator, method, result.slot.carrier)
            ?: return null
        DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.Direct(carrier.first) to
                DotNetGenericOwnerPhysicalMethodDefEmissionResultSnapshot(
                    DotNetGenericOwnerPhysicalMethodDefEmissionResultLayout.DIRECT,
                    carrier.second,
                    hasCanonicalOutBooleanNullFlag = false,
                )
    }
    is DotNetGenericOwnerPhysicalCallableResultLayoutReference.SplitNullable -> {
        val carrier = expectedCarrier(
            authority,
            declarations,
            allocator,
            method,
            result.payloadSlot.carrier,
        ) ?: return null
        DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.SplitNullable(carrier.first) to
                DotNetGenericOwnerPhysicalMethodDefEmissionResultSnapshot(
                    DotNetGenericOwnerPhysicalMethodDefEmissionResultLayout.SPLIT_NULLABLE,
                    carrier.second,
                    hasCanonicalOutBooleanNullFlag = true,
                )
    }
}

private fun expectedCarrier(
    authority: DotNetLocalGenericOwnerPhysicalAuthority,
    declarations: DotNetGenericOwnerPhysicalDeclarationIndex,
    allocator: EmissionIdentityAllocator,
    currentMethod: DotNetGenericOwnerPhysicalMethodDefIdentity.Local,
    carrier: DotNetGenericOwnerSymbolicCarrierReference,
): Pair<
        DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape,
        DotNetGenericOwnerPhysicalMethodDefEmissionCarrierSnapshot,
        >? = when (carrier) {
    is DotNetGenericOwnerSymbolicCarrierReference.Leaf ->
        DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.Leaf(carrier.kind) to
                carrier.kind.toEmissionCarrierSnapshot()
    is DotNetGenericOwnerSymbolicCarrierReference.Parameter -> when (val binder = carrier.binder) {
        is DotNetGenericOwnerPhysicalGenericBinderReference.Type -> {
            val identity = binder.definition as? DotNetGenericOwnerPhysicalTypeDefIdentity.Local
                ?: return null
            val description = declarations.typeDescriptionOrNull(identity) ?: return null
            val key = allocator.expectedType(identity, description)
            DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.OwnerParameter(key, carrier.index) to
                    DotNetGenericOwnerPhysicalMethodDefEmissionCarrierSnapshot(
                        DotNetGenericOwnerPhysicalMethodDefEmissionCarrierKind.OWNER_PARAMETER,
                        typeDef = identity.toSnapshot(authority, description),
                        parameterIndex = carrier.index,
                    )
        }
        is DotNetGenericOwnerPhysicalGenericBinderReference.Method -> {
            val identity = binder.definition as? DotNetGenericOwnerPhysicalMethodDefIdentity.Local
                ?: return null
            val description = declarations.methodDescriptionOrNull(identity) ?: return null
            val owner = description.declaringType as? DotNetGenericOwnerPhysicalTypeDefIdentity.Local
                ?: return null
            val ownerDescription = declarations.typeDescriptionOrNull(owner) ?: return null
            DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.MethodParameter(
                allocator.method(identity),
                carrier.index,
            ) to DotNetGenericOwnerPhysicalMethodDefEmissionCarrierSnapshot(
                DotNetGenericOwnerPhysicalMethodDefEmissionCarrierKind.METHOD_PARAMETER,
                typeDef = owner.toSnapshot(authority, ownerDescription),
                parameterIndex = carrier.index,
                methodNameForDiagnostics = identity.function.owner.name.asString(),
            )
        }
    }
    is DotNetGenericOwnerSymbolicCarrierReference.Constructed -> {
        val identity = carrier.definition as? DotNetGenericOwnerPhysicalTypeDefIdentity.Local
            ?: return null
        val description = declarations.typeDescriptionOrNull(identity) ?: return null
        val arguments = carrier.arguments.map { argument ->
            expectedCarrier(authority, declarations, allocator, currentMethod, argument)
                ?: return null
        }
        DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.Construction(
            allocator.expectedType(identity, description),
            arguments.map { pair -> pair.first },
        ) to DotNetGenericOwnerPhysicalMethodDefEmissionCarrierSnapshot(
            DotNetGenericOwnerPhysicalMethodDefEmissionCarrierKind.LOCAL_CONSTRUCTION,
            typeDef = identity.toSnapshot(authority, description),
            arguments = arguments.map { pair -> pair.second },
        )
    }
    is DotNetGenericOwnerSymbolicCarrierReference.SzArray -> {
        val element = expectedCarrier(authority, declarations, allocator, currentMethod, carrier.element)
            ?: return null
        DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.SzArray(element.first) to
                DotNetGenericOwnerPhysicalMethodDefEmissionCarrierSnapshot(
                    DotNetGenericOwnerPhysicalMethodDefEmissionCarrierKind.SZ_ARRAY,
                    arguments = listOf(element.second),
                )
    }
}

private fun correlateActualHeaders(
    authority: DotNetLocalGenericOwnerPhysicalAuthority,
    allocator: EmissionIdentityAllocator,
    input: EndpointInput,
    familyIdentities: List<DotNetGenericOwnerPhysicalMethodDefIdentity.Local>,
    observations: List<DotNetGenericOwnerPhysicalMethodDefHeaderObservation>,
    otherScopeObservations: List<DotNetGenericOwnerPhysicalMethodDefHeaderObservation>,
): List<DotNetGenericOwnerPhysicalMethodDefEmissionHeaderEvidence> {
    val expectedOwner = input.reference.declaringType as DotNetGenericOwnerPhysicalTypeDefIdentity.Local
    val expectedOwnerArity = authority.inputOrNull(expectedOwner)?.genericArity
    val candidates = mutableListOf<DotNetGenericOwnerPhysicalMethodDefEmissionHeaderEvidence>()
    observations.forEach { observation ->
        when (classifyDotNetGenericOwnerPhysicalMethodDefEmissionIdentity(
            input.identity,
            familyIdentities,
            observation.physicalFunction,
            observation.physicalMethodIdentity,
        )) {
            DotNetGenericOwnerPhysicalMethodDefEmissionIdentityRelation.IRRELEVANT,
            DotNetGenericOwnerPhysicalMethodDefEmissionIdentityRelation.OTHER_FAMILY_ENDPOINT,
            -> return@forEach
            DotNetGenericOwnerPhysicalMethodDefEmissionIdentityRelation.UNAVAILABLE -> {
                candidates += DotNetGenericOwnerPhysicalMethodDefEmissionHeaderEvidence.Unavailable(
                    "the selected emitted MethodDef has no bound generic-owner physical identity",
                )
                return@forEach
            }
            DotNetGenericOwnerPhysicalMethodDefEmissionIdentityRelation.UNEXPECTED -> {
                candidates += DotNetGenericOwnerPhysicalMethodDefEmissionHeaderEvidence.Conflict(
                    "the selected logical MethodDef function was emitted with an unexpected physical role",
                )
                return@forEach
            }
            DotNetGenericOwnerPhysicalMethodDefEmissionIdentityRelation.MATCHING_ENDPOINT -> Unit
        }
        when (val owner = observation.physicalMethodOwner) {
            is DotNetGenericOwnerObservedMethodDefOwner.Local -> {
                when (classifyDotNetGenericOwnerPhysicalMethodDefEmissionOwner(
                    input.entryKind,
                    expectedOwner,
                    expectedOwnerArity,
                    owner.typeDef,
                )) {
                    DotNetGenericOwnerPhysicalMethodDefEmissionOwnerRelation.MATCHING_ENDPOINT ->
                        candidates += actualHeaderEvidence(
                            authority,
                            allocator,
                            input,
                            observation,
                            owner.typeDef,
                        )
                    DotNetGenericOwnerPhysicalMethodDefEmissionOwnerRelation.LEGITIMATE_EXACT_SIBLING -> Unit
                    DotNetGenericOwnerPhysicalMethodDefEmissionOwnerRelation.UNEXPECTED ->
                        candidates += DotNetGenericOwnerPhysicalMethodDefEmissionHeaderEvidence.Conflict(
                            "the selected IR function was emitted on an unexpected physical TypeDef owner",
                        )
                }
            }
            is DotNetGenericOwnerObservedMethodDefOwner.Unbindable -> candidates +=
                if (owner.isConflict) {
                    DotNetGenericOwnerPhysicalMethodDefEmissionHeaderEvidence.Conflict(owner.reason)
                } else {
                    DotNetGenericOwnerPhysicalMethodDefEmissionHeaderEvidence.Unavailable(owner.reason)
                }
        }
    }
    if (otherScopeObservations.any { observation ->
            isDotNetGenericOwnerPhysicalMethodDefEmissionIdentityEvidenceForEndpoint(
                input.identity,
                familyIdentities,
                observation.physicalFunction,
                observation.physicalMethodIdentity,
            )
        }
    ) {
        candidates += DotNetGenericOwnerPhysicalMethodDefEmissionHeaderEvidence.Conflict(
            "the same physical MethodDef endpoint was observed in more than one emission scope",
        )
    }
    return candidates
}

internal enum class DotNetGenericOwnerPhysicalMethodDefEmissionIdentityRelation {
    MATCHING_ENDPOINT,
    OTHER_FAMILY_ENDPOINT,
    UNAVAILABLE,
    UNEXPECTED,
    IRRELEVANT,
}

/** Role-aware identity correlation; diagnostic names and IR origins never participate. */
internal fun classifyDotNetGenericOwnerPhysicalMethodDefEmissionIdentity(
    expected: DotNetGenericOwnerPhysicalMethodDefIdentity.Local,
    familyIdentities: List<DotNetGenericOwnerPhysicalMethodDefIdentity.Local>,
    physicalFunction: IrSimpleFunctionSymbol,
    actual: DotNetGenericOwnerPhysicalMethodDefIdentity.Local?,
): DotNetGenericOwnerPhysicalMethodDefEmissionIdentityRelation = when {
    actual == null && physicalFunction === expected.function ->
        DotNetGenericOwnerPhysicalMethodDefEmissionIdentityRelation.UNAVAILABLE
    actual == null -> DotNetGenericOwnerPhysicalMethodDefEmissionIdentityRelation.IRRELEVANT
    actual.sameLocalMethodIdentityAs(expected) ->
        DotNetGenericOwnerPhysicalMethodDefEmissionIdentityRelation.MATCHING_ENDPOINT
    // The per-emission identity is authoritative. A future lowering may deliberately emit two
    // selected family endpoints from the same IR function; the raw function symbol cannot turn
    // that other physical endpoint into a conflict.
    familyIdentities.any(actual::sameLocalMethodIdentityAs) ->
        DotNetGenericOwnerPhysicalMethodDefEmissionIdentityRelation.OTHER_FAMILY_ENDPOINT
    physicalFunction === expected.function ->
        DotNetGenericOwnerPhysicalMethodDefEmissionIdentityRelation.UNEXPECTED
    else -> DotNetGenericOwnerPhysicalMethodDefEmissionIdentityRelation.IRRELEVANT
}

/** Any selected identity evidence in another emitter transaction is scope contamination. */
internal fun isDotNetGenericOwnerPhysicalMethodDefEmissionIdentityEvidenceForEndpoint(
    expected: DotNetGenericOwnerPhysicalMethodDefIdentity.Local,
    familyIdentities: List<DotNetGenericOwnerPhysicalMethodDefIdentity.Local>,
    physicalFunction: IrSimpleFunctionSymbol,
    actual: DotNetGenericOwnerPhysicalMethodDefIdentity.Local?,
): Boolean = classifyDotNetGenericOwnerPhysicalMethodDefEmissionIdentity(
    expected,
    familyIdentities,
    physicalFunction,
    actual,
) !in setOf(
    DotNetGenericOwnerPhysicalMethodDefEmissionIdentityRelation.IRRELEVANT,
    DotNetGenericOwnerPhysicalMethodDefEmissionIdentityRelation.OTHER_FAMILY_ENDPOINT,
)

internal enum class DotNetGenericOwnerPhysicalMethodDefEmissionOwnerRelation {
    MATCHING_ENDPOINT,
    LEGITIMATE_EXACT_SIBLING,
    UNEXPECTED,
}

/**
 * The one deliberate same-symbol/different-owner exception in the bounded family: the natural
 * source member is also rendered on its invariant exact sibling. No other owner is silently
 * discarded, so a rogue or duplicated MethodDef cannot leave one correct endpoint as MATCH.
 */
internal fun classifyDotNetGenericOwnerPhysicalMethodDefEmissionOwner(
    entryKind: DotNetGenericOwnerPhysicalMethodDefEmissionEntryKind,
    expectedOwner: DotNetGenericOwnerPhysicalTypeDefIdentity.Local,
    expectedOwnerArity: Int?,
    actualOwner: DotNetGenericOwnerObservedLocalTypeDef,
): DotNetGenericOwnerPhysicalMethodDefEmissionOwnerRelation = when {
    actualOwner.aliases.any(expectedOwner::sameLocalTypeIdentityAs) ->
        DotNetGenericOwnerPhysicalMethodDefEmissionOwnerRelation.MATCHING_ENDPOINT
    entryKind == DotNetGenericOwnerPhysicalMethodDefEmissionEntryKind.NATURAL_INTERFACE &&
            actualOwner.aliases.size == 1 &&
            actualOwner.identity.owner === expectedOwner.owner &&
            actualOwner.identity.view == DotNetGenericInterfaceView.EXACT &&
            actualOwner.category == DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE &&
            actualOwner.genericArity == expectedOwnerArity ->
        DotNetGenericOwnerPhysicalMethodDefEmissionOwnerRelation.LEGITIMATE_EXACT_SIBLING
    else -> DotNetGenericOwnerPhysicalMethodDefEmissionOwnerRelation.UNEXPECTED
}

private fun actualHeaderEvidence(
    authority: DotNetLocalGenericOwnerPhysicalAuthority,
    allocator: EmissionIdentityAllocator,
    input: EndpointInput,
    observation: DotNetGenericOwnerPhysicalMethodDefHeaderObservation,
    owner: DotNetGenericOwnerObservedLocalTypeDef,
): DotNetGenericOwnerPhysicalMethodDefEmissionHeaderEvidence {
    val physicalMethodIdentity = observation.physicalMethodIdentity
        ?: return DotNetGenericOwnerPhysicalMethodDefEmissionHeaderEvidence.Unavailable(
            "the emitted MethodDef has no bound generic-owner physical identity",
        )
    if (!physicalMethodIdentity.sameLocalMethodIdentityAs(input.identity)) {
        return DotNetGenericOwnerPhysicalMethodDefEmissionHeaderEvidence.Conflict(
            "the emitted MethodDef role does not match the selected BOUND identity",
        )
    }
    val receiver = observation.signature.receiverCarrier?.toActualCarrier(
        authority,
        allocator,
        input.identity,
    )
    val parameters = observation.signature.parameterCarriers.map { carrier ->
        carrier.toActualCarrier(authority, allocator, input.identity)
    }
    val result = observation.signature.returnCarrier.toActualCarrier(
        authority,
        allocator,
        input.identity,
    )
    val allCarriers = listOfNotNull(receiver) + parameters + result
    allCarriers.firstNotNullOfOrNull { conversion -> conversion.conflictReason }?.let { reason ->
        return DotNetGenericOwnerPhysicalMethodDefEmissionHeaderEvidence.Conflict(reason)
    }
    allCarriers.firstNotNullOfOrNull { conversion -> conversion.unavailableReason }?.let { reason ->
        return DotNetGenericOwnerPhysicalMethodDefEmissionHeaderEvidence.Unavailable(reason)
    }
    val knownReceiver = receiver?.known ?: if (observation.dispatch.isInstance) {
        return DotNetGenericOwnerPhysicalMethodDefEmissionHeaderEvidence.Conflict(
            "the emitted instance MethodDef has no independently observed receiver carrier",
        )
    } else {
        null
    }
    if (!observation.dispatch.isInstance && receiver != null) {
        return DotNetGenericOwnerPhysicalMethodDefEmissionHeaderEvidence.Conflict(
            "the emitted static MethodDef unexpectedly has a receiver carrier",
        )
    }
    val knownParameters = parameters.map { conversion -> checkNotNull(conversion.known) }
    val knownResultCarrier = checkNotNull(result.known)
    val actualResult: Pair<
            DotNetGenericOwnerPhysicalMethodDefEmissionResultShape,
            DotNetGenericOwnerPhysicalMethodDefEmissionResultSnapshot,
            >
    val ordinaryParameters: List<ActualCarrier>
    if (observation.signature.hasSplitNullableResult) {
        if (knownResultCarrier.shape == DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.Leaf(
                DotNetGenericOwnerPhysicalTypeKind.VOID,
            )) {
            return DotNetGenericOwnerPhysicalMethodDefEmissionHeaderEvidence.Conflict(
                "an emitted split-nullable MethodDef cannot return void payload",
            )
        }
        val expectedNullFlag = DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.ByReference(
            DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.Leaf(
                DotNetGenericOwnerPhysicalTypeKind.BOOLEAN,
            ),
        )
        if (knownParameters.lastOrNull()?.shape != expectedNullFlag) {
            return DotNetGenericOwnerPhysicalMethodDefEmissionHeaderEvidence.Conflict(
                "an emitted split-nullable MethodDef requires one trailing out bool carrier",
            )
        }
        ordinaryParameters = knownParameters.dropLast(1)
        actualResult = DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.SplitNullable(
            knownResultCarrier.shape,
        ) to DotNetGenericOwnerPhysicalMethodDefEmissionResultSnapshot(
            DotNetGenericOwnerPhysicalMethodDefEmissionResultLayout.SPLIT_NULLABLE,
            knownResultCarrier.snapshot,
            hasCanonicalOutBooleanNullFlag = true,
        )
    } else {
        ordinaryParameters = knownParameters
        actualResult = if (knownResultCarrier.shape ==
            DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.Leaf(
                DotNetGenericOwnerPhysicalTypeKind.VOID,
            )
        ) {
            DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.Void to
                    DotNetGenericOwnerPhysicalMethodDefEmissionResultSnapshot(
                        DotNetGenericOwnerPhysicalMethodDefEmissionResultLayout.VOID,
                        carrier = null,
                        hasCanonicalOutBooleanNullFlag = false,
                    )
        } else {
            DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.Direct(knownResultCarrier.shape) to
                    DotNetGenericOwnerPhysicalMethodDefEmissionResultSnapshot(
                        DotNetGenericOwnerPhysicalMethodDefEmissionResultLayout.DIRECT,
                        knownResultCarrier.snapshot,
                        hasCanonicalOutBooleanNullFlag = false,
                    )
        }
    }
    val ownerSnapshot = owner.toSnapshot(authority)
    val ownerKey = when (val binding = allocator.actualType(owner)) {
        is EmissionIdentityAllocator.ActualType.Bound -> binding.key
        is EmissionIdentityAllocator.ActualType.Conflict ->
            return DotNetGenericOwnerPhysicalMethodDefEmissionHeaderEvidence.Conflict(binding.reason)
    }
    val visibility = observation.visibility.toEmissionVisibility()
    val dispatchCategory = observation.dispatch.toPhysicalDispatch()
    return DotNetGenericOwnerPhysicalMethodDefEmissionHeaderEvidence.Known(
        DotNetGenericOwnerPhysicalMethodDefEmissionHeaderShape(
            owner = ownerKey,
            ownerGenericArity = owner.genericArity,
            ownerCategory = owner.category,
            visibility = visibility,
            dispatch = dispatchCategory,
            isInstance = observation.dispatch.isInstance,
            genericArity = observation.genericArity,
            receiverCarrier = knownReceiver?.shape,
            ordinaryParameterCarriers = ordinaryParameters.map { carrier -> carrier.shape },
            result = actualResult.first,
        ),
        DotNetGenericOwnerPhysicalMethodDefEmissionHeaderSnapshot(
            methodIdentity = DotNetGenericOwnerPhysicalMethodDefEmissionIdentitySnapshot(
                physicalMethodIdentity.role,
            ),
            owner = ownerSnapshot,
            physicalMethodNameForDiagnostics = observation.physicalMethodNameForDiagnostics,
            visibility = visibility,
            dispatch = DotNetGenericOwnerPhysicalMethodDefEmissionDispatchSnapshot(
                category = dispatchCategory,
                isVirtual = observation.dispatch.isVirtual,
                isNewSlot = observation.dispatch.isNewSlot,
                isAbstract = observation.dispatch.isAbstract,
                isFinal = observation.dispatch.isFinal,
            ),
            isInstance = observation.dispatch.isInstance,
            genericArity = observation.genericArity,
            receiverCarrier = knownReceiver?.snapshot,
            ordinaryParameterCarriers = ordinaryParameters.map { carrier -> carrier.snapshot },
            result = actualResult.second,
        ),
    )
}

private data class ActualCarrier(
    val shape: DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape,
    val snapshot: DotNetGenericOwnerPhysicalMethodDefEmissionCarrierSnapshot,
)

private data class ActualCarrierConversion(
    val known: ActualCarrier? = null,
    val unavailableReason: String? = null,
    val conflictReason: String? = null,
) {
    init {
        require(listOf(known, unavailableReason, conflictReason).count { it != null } == 1) {
            "an actual carrier conversion requires exactly one outcome"
        }
    }
}

private fun DotNetGenericOwnerObservedMethodCarrier.toActualCarrier(
    authority: DotNetLocalGenericOwnerPhysicalAuthority,
    allocator: EmissionIdentityAllocator,
    currentMethod: DotNetGenericOwnerPhysicalMethodDefIdentity.Local,
): ActualCarrierConversion = when (this) {
    is DotNetGenericOwnerObservedMethodCarrier.Leaf -> ActualCarrierConversion(
        known = ActualCarrier(
            DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.Leaf(kind),
            kind.toEmissionCarrierSnapshot(),
        ),
    )
    is DotNetGenericOwnerObservedMethodCarrier.OwnerParameter ->
        when (val binding = allocator.actualType(binder)) {
            is EmissionIdentityAllocator.ActualType.Conflict ->
                ActualCarrierConversion(conflictReason = binding.reason)
            is EmissionIdentityAllocator.ActualType.Bound -> ActualCarrierConversion(
                known = ActualCarrier(
                    DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.OwnerParameter(
                        binding.key,
                        index,
                    ),
                    DotNetGenericOwnerPhysicalMethodDefEmissionCarrierSnapshot(
                        DotNetGenericOwnerPhysicalMethodDefEmissionCarrierKind.OWNER_PARAMETER,
                        typeDef = binder.toSnapshot(authority),
                        parameterIndex = index,
                    ),
                ),
            )
        }
    is DotNetGenericOwnerObservedMethodCarrier.MethodParameter -> {
        val currentOwner = (currentMethod.function.owner.parent as? IrClass)?.symbol
        if (physicalMethodIdentity == null) {
            ActualCarrierConversion(unavailableReason =
                "an emitted method parameter has no bound physical MethodDef identity")
        } else if (!physicalMethodIdentity.sameLocalMethodIdentityAs(currentMethod) ||
            currentOwner == null || physicalOwner.aliases.none { alias -> alias.owner === currentOwner }
        ) {
            ActualCarrierConversion(conflictReason =
                "an emitted method parameter is bound to a different physical MethodDef")
        } else {
            ActualCarrierConversion(
                known = ActualCarrier(
                    DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.MethodParameter(
                        allocator.method(currentMethod),
                        index,
                    ),
                    DotNetGenericOwnerPhysicalMethodDefEmissionCarrierSnapshot(
                        DotNetGenericOwnerPhysicalMethodDefEmissionCarrierKind.METHOD_PARAMETER,
                        typeDef = physicalOwner.toSnapshot(authority),
                        parameterIndex = index,
                        methodNameForDiagnostics = physicalFunction.owner.name.asString(),
                    ),
                ),
            )
        }
    }
    is DotNetGenericOwnerObservedMethodCarrier.LocalConstruction -> {
        val arguments = arguments.map { argument ->
            argument.toActualCarrier(authority, allocator, currentMethod)
        }
        arguments.firstNotNullOfOrNull { conversion -> conversion.conflictReason }?.let { reason ->
            ActualCarrierConversion(conflictReason = reason)
        } ?: arguments.firstNotNullOfOrNull { conversion -> conversion.unavailableReason }?.let { reason ->
            ActualCarrierConversion(unavailableReason = reason)
        } ?: arguments.map { conversion -> checkNotNull(conversion.known) }.let { knownArguments ->
            when (val binding = allocator.actualType(definition)) {
                is EmissionIdentityAllocator.ActualType.Conflict ->
                    ActualCarrierConversion(conflictReason = binding.reason)
                is EmissionIdentityAllocator.ActualType.Bound -> ActualCarrierConversion(
                    known = ActualCarrier(
                        DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.Construction(
                            binding.key,
                            knownArguments.map { argument -> argument.shape },
                        ),
                        DotNetGenericOwnerPhysicalMethodDefEmissionCarrierSnapshot(
                            DotNetGenericOwnerPhysicalMethodDefEmissionCarrierKind.LOCAL_CONSTRUCTION,
                            typeDef = definition.toSnapshot(authority),
                            arguments = knownArguments.map { argument -> argument.snapshot },
                        ),
                    ),
                )
            }
        }
    }
    is DotNetGenericOwnerObservedMethodCarrier.SzArray ->
        element.toActualCarrier(authority, allocator, currentMethod).wrapActualCarrier(
            DotNetGenericOwnerPhysicalMethodDefEmissionCarrierKind.SZ_ARRAY,
            DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape::SzArray,
        )
    is DotNetGenericOwnerObservedMethodCarrier.ByReference ->
        element.toActualCarrier(authority, allocator, currentMethod).wrapActualCarrier(
            DotNetGenericOwnerPhysicalMethodDefEmissionCarrierKind.BY_REFERENCE,
            DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape::ByReference,
        )
    is DotNetGenericOwnerObservedMethodCarrier.Other -> ActualCarrierConversion(
        known = ActualCarrier(
            DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.Other,
            DotNetGenericOwnerPhysicalMethodDefEmissionCarrierSnapshot(
                DotNetGenericOwnerPhysicalMethodDefEmissionCarrierKind.OTHER,
                physicalDescription = physicalDescription,
            ),
        ),
    )
    is DotNetGenericOwnerObservedMethodCarrier.Unbindable -> if (isConflict) {
        ActualCarrierConversion(conflictReason = reason)
    } else {
        ActualCarrierConversion(unavailableReason = reason)
    }
}

private fun ActualCarrierConversion.wrapActualCarrier(
    kind: DotNetGenericOwnerPhysicalMethodDefEmissionCarrierKind,
    wrapShape: (
        DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape,
    ) -> DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape,
): ActualCarrierConversion = when {
    conflictReason != null -> this
    unavailableReason != null -> this
    else -> checkNotNull(known).let { element ->
        ActualCarrierConversion(
            known = ActualCarrier(
                wrapShape(element.shape),
                DotNetGenericOwnerPhysicalMethodDefEmissionCarrierSnapshot(
                    kind,
                    arguments = listOf(element.snapshot),
                ),
            ),
        )
    }
}

private fun DotNetGenericOwnerObservedLocalTypeDef.toSnapshot(
    authority: DotNetLocalGenericOwnerPhysicalAuthority,
): DotNetGenericOwnerPhysicalMethodDefEmissionTypeDefSnapshot =
    DotNetGenericOwnerPhysicalMethodDefEmissionTypeDefSnapshot(
        ownerName = authority.inputOrNull(identity)?.logicalOwnerName
            ?: identity.owner.owner.dotNetPhysicalValueStableName(),
        typeDefView = identity.view?.toEmissionView(),
        physicalAliasViews = aliases.map { alias -> alias.view?.toEmissionView() },
        genericArity = genericArity,
        category = category,
    )

private fun DotNetGenericOwnerPhysicalTypeDefIdentity.Local.toSnapshot(
    authority: DotNetLocalGenericOwnerPhysicalAuthority,
    description: DotNetGenericOwnerPhysicalTypeDefReference,
): DotNetGenericOwnerPhysicalMethodDefEmissionTypeDefSnapshot =
    DotNetGenericOwnerPhysicalMethodDefEmissionTypeDefSnapshot(
        ownerName = authority.inputOrNull(this)?.logicalOwnerName
            ?: owner.owner.dotNetPhysicalValueStableName(),
        typeDefView = view?.toEmissionView(),
        genericArity = description.genericArity,
        category = description.category,
    )

private fun DotNetGenericOwnerPhysicalTypeKind.toEmissionCarrierSnapshot():
        DotNetGenericOwnerPhysicalMethodDefEmissionCarrierSnapshot {
    val snapshotKind = when (this) {
        DotNetGenericOwnerPhysicalTypeKind.VOID ->
            DotNetGenericOwnerPhysicalMethodDefEmissionCarrierKind.VOID
        DotNetGenericOwnerPhysicalTypeKind.BOOLEAN ->
            DotNetGenericOwnerPhysicalMethodDefEmissionCarrierKind.BOOLEAN
        DotNetGenericOwnerPhysicalTypeKind.INT32 ->
            DotNetGenericOwnerPhysicalMethodDefEmissionCarrierKind.INT32
        DotNetGenericOwnerPhysicalTypeKind.STRING ->
            DotNetGenericOwnerPhysicalMethodDefEmissionCarrierKind.STRING
        DotNetGenericOwnerPhysicalTypeKind.OBJECT ->
            DotNetGenericOwnerPhysicalMethodDefEmissionCarrierKind.OBJECT
        else -> error("a structural physical type kind cannot be rendered as a leaf snapshot")
    }
    return DotNetGenericOwnerPhysicalMethodDefEmissionCarrierSnapshot(snapshotKind)
}

private fun DotNetGenericInterfaceView.toEmissionView():
        DotNetGenericOwnerPhysicalValueShadowTypeDefView =
    DotNetGenericOwnerPhysicalValueShadowTypeDefView.valueOf(name)

private fun DotNetGenericOwnerPhysicalMemberVisibility.toEmissionVisibility():
        DotNetGenericOwnerPhysicalMethodDefEmissionVisibility =
    DotNetGenericOwnerPhysicalMethodDefEmissionVisibility.valueOf(name)

private fun DotNetIlRawMethodDefVisibility.toEmissionVisibility():
        DotNetGenericOwnerPhysicalMethodDefEmissionVisibility = when (this) {
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
}

private fun DotNetIlRawMethodDefDispatch.toPhysicalDispatch():
        DotNetGenericOwnerPhysicalMemberDispatch = when {
    isAbstract -> DotNetGenericOwnerPhysicalMemberDispatch.ABSTRACT
    isVirtual && !isFinal -> DotNetGenericOwnerPhysicalMemberDispatch.OVERRIDABLE
    else -> DotNetGenericOwnerPhysicalMemberDispatch.FINAL
}
