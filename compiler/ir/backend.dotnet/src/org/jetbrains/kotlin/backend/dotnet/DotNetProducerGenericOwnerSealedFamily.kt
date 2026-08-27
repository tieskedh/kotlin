/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Exact logical joins supplied by the pre-lowering declaration-key index, never by CLR names. */
internal data class DotNetProducerGenericOwnerSealedFamilyKey(
    val logicalInterfaceMemberKey: String,
    val implementationOwnerKey: String,
    val implementationMemberKey: String,
) {
    init {
        require(logicalInterfaceMemberKey.isNotEmpty() && implementationOwnerKey.isNotEmpty() &&
                implementationMemberKey.isNotEmpty() &&
                '\u0000' !in logicalInterfaceMemberKey && '\u0000' !in implementationOwnerKey &&
                '\u0000' !in implementationMemberKey) {
            "a producer-sealed family requires every exact NUL-free logical declaration key"
        }
    }
}

internal enum class DotNetProducerGenericOwnerSealedTypeDefRole {
    NATURAL_INTERFACE,
    INTERFACE_SEMANTIC_CAPABILITY,
    IMPLEMENTATION_CLASS,
    CLASS_SEMANTIC_CAPABILITY,
}

internal enum class DotNetProducerGenericOwnerSealedMethodDefRole {
    NATURAL_INTERFACE_SLOT,
    INTERFACE_SEMANTIC_CAPABILITY_SLOT,
    IMPLEMENTATION_TYPED_ENTRY,
    CLASS_SEMANTIC_CAPABILITY_SLOT,
    CLASS_SEMANTIC_CAPABILITY_DISPATCHER,
    INTERFACE_SEMANTIC_CAPABILITY_DISPATCHER,
}

internal enum class DotNetProducerGenericOwnerSealedMethodImplRole {
    CLASS_SEMANTIC_CAPABILITY_IMPLEMENTATION,
    INTERFACE_SEMANTIC_CAPABILITY_IMPLEMENTATION,
}

internal data class DotNetProducerGenericOwnerSealedTypeDef(
    val role: DotNetProducerGenericOwnerSealedTypeDefRole,
    val row: DotNetGenericOwnerSealedEmissionTypeDefRow,
)

/**
 * [logicalParameterDomains] and [logicalResultDomain] remain KLIB-side routing facts. Every other
 * field in [row] is copied from the successful final-emission transaction.
 */
internal data class DotNetProducerGenericOwnerSealedMethodDef(
    val role: DotNetProducerGenericOwnerSealedMethodDefRole,
    val row: DotNetGenericOwnerSealedEmissionMethodDefRow,
    val logicalParameterDomains: List<DotNetGenericOwnerPhysicalSlotDomain>,
    val logicalResultDomain: DotNetGenericOwnerPhysicalSlotDomain?,
)

internal data class DotNetProducerGenericOwnerSealedMethodImpl(
    val role: DotNetProducerGenericOwnerSealedMethodImplRole,
    val row: DotNetGenericOwnerCompleteEmissionMethodImplRow,
)

/**
 * Complete supported, IR-free body of one actually matched bounded 4/6/2 family seal.
 *
 * Ordered TypeDef GenericParam variance/constraint rows are physical authority. TypeDef
 * GenericParam display names are not in the admitted sealed-emission observation grammar (unlike
 * MethodDef GenericParam names), so this body deliberately makes no claim about them.
 */
internal data class DotNetProducerGenericOwnerSealedFamilyBody(
    val typeDefs: List<DotNetProducerGenericOwnerSealedTypeDef>,
    val methodDefs: List<DotNetProducerGenericOwnerSealedMethodDef>,
    val methodImpls: List<DotNetProducerGenericOwnerSealedMethodImpl>,
) {
    fun publish(
        key: DotNetProducerGenericOwnerSealedFamilyKey,
    ): DotNetProducerGenericOwnerSealedFamilyPublication =
        DotNetProducerGenericOwnerSealedFamilyPublication(key, this)
}

internal data class DotNetProducerGenericOwnerSealedFamilyPublication(
    val key: DotNetProducerGenericOwnerSealedFamilyKey,
    val body: DotNetProducerGenericOwnerSealedFamilyBody,
)

/** A decoded producer seal is admitted atomically or exposes no physical row. */
internal class DotNetProducerGenericOwnerSealedFamilyAuthority private constructor(
    val publication: DotNetProducerGenericOwnerSealedFamilyPublication,
    private val sealedIndex: DotNetGenericOwnerSealedEmissionSignatureIndex,
) {
    val epoch: DotNetGenericOwnerPhysicalAuthorityEpoch
        get() = DotNetGenericOwnerPhysicalAuthorityEpoch.SEALED_EMISSION_SIGNATURE_INDEX

    private val typeDefsByRole = publication.body.typeDefs.associateBy { row -> row.role }
    private val methodDefsByRole = publication.body.methodDefs.associateBy { row -> row.role }
    private val methodImplsByRole = publication.body.methodImpls.associateBy { row -> row.role }

    fun typeDef(
        role: DotNetProducerGenericOwnerSealedTypeDefRole,
    ): DotNetGenericOwnerPhysicalBindingResult<DotNetGenericOwnerSealedEmissionTypeDefRow> =
        sealedIndex.typeDef(typeDefsByRole.getValue(role).row.structural.identityKey)

    fun methodDef(
        role: DotNetProducerGenericOwnerSealedMethodDefRole,
    ): DotNetGenericOwnerPhysicalBindingResult<DotNetGenericOwnerSealedEmissionMethodDefRow> =
        sealedIndex.methodDef(methodDefsByRole.getValue(role).row.structural.identityKey)

    fun methodImpl(
        role: DotNetProducerGenericOwnerSealedMethodImplRole,
    ): DotNetGenericOwnerCompleteEmissionMethodImplRow = methodImplsByRole.getValue(role).row

    companion object {
        internal fun create(
            publication: DotNetProducerGenericOwnerSealedFamilyPublication,
            sealedIndex: DotNetGenericOwnerSealedEmissionSignatureIndex,
        ) = DotNetProducerGenericOwnerSealedFamilyAuthority(publication, sealedIndex)
    }
}

internal fun inspectDotNetProducerGenericOwnerSealedFamily(
    publication: DotNetProducerGenericOwnerSealedFamilyPublication?,
): DotNetGenericOwnerPhysicalBindingResult<DotNetProducerGenericOwnerSealedFamilyAuthority> {
    if (publication == null) return DotNetGenericOwnerPhysicalBindingResult.Unavailable
    val body = publication.body
    val conflicts = validateProducerSealedFamilyRoles(body).toMutableList()
    if (conflicts.isNotEmpty()) {
        return DotNetGenericOwnerPhysicalBindingResult.Conflict(conflicts.distinct().joinToString("; "))
    }

    val actual = DotNetGenericOwnerSealedEmissionManifestEvidence.Known(
        typeDefs = body.typeDefs.map { row -> row.row },
        methodDefs = body.methodDefs.map { row -> row.row },
        methodImpls = body.methodImpls.map { row -> row.row },
    )
    // This structural projection is derived from the same actual rows. It is not BOUND authority:
    // the sealed-index validator still builds its result exclusively from [actual], while this
    // projection enables its complete intrinsic binder/coordinate/flag validation to be reused.
    val actualStructural = DotNetGenericOwnerCompleteEmissionManifest(
        typeDefs = actual.typeDefs.map { row -> row.structural },
        methodDefs = actual.methodDefs.map { row -> row.structural },
        methodImpls = actual.methodImpls,
    )
    val inspection = inspectDotNetGenericOwnerSealedEmissionSignatureIndex(actualStructural, actual)
    return when (val binding = inspection.binding) {
        is DotNetGenericOwnerPhysicalBindingResult.Bound ->
            DotNetGenericOwnerPhysicalBindingResult.Bound(
                DotNetProducerGenericOwnerSealedFamilyAuthority.create(publication, binding.value),
            )
        is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
            DotNetGenericOwnerPhysicalBindingResult.Conflict(binding.reason)
        DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
            DotNetGenericOwnerPhysicalBindingResult.Conflict(
                inspection.diagnostics.ifEmpty {
                    listOf("a claimed producer-sealed family is incomplete")
                }.joinToString("; "),
            )
    }
}

private fun validateProducerSealedFamilyRoles(
    body: DotNetProducerGenericOwnerSealedFamilyBody,
): List<String> = buildList {
    fun <T> exactRoles(actual: List<T>, expected: Set<T>, kind: String) {
        if (actual.size != expected.size || actual.toSet() != expected) {
            add("a producer-sealed family requires every $kind role exactly once")
        }
    }
    exactRoles(
        body.typeDefs.map { row -> row.role },
        DotNetProducerGenericOwnerSealedTypeDefRole.entries.toSet(),
        "TypeDef",
    )
    exactRoles(
        body.methodDefs.map { row -> row.role },
        DotNetProducerGenericOwnerSealedMethodDefRole.entries.toSet(),
        "MethodDef",
    )
    exactRoles(
        body.methodImpls.map { row -> row.role },
        DotNetProducerGenericOwnerSealedMethodImplRole.entries.toSet(),
        "MethodImpl",
    )
    if (isNotEmpty()) return@buildList

    val types = body.typeDefs.associateBy { row -> row.role }
    val methods = body.methodDefs.associateBy { row -> row.role }
    val methodImpls = body.methodImpls.associateBy { row -> row.role }
    val expectedTypeShapes = mapOf(
        DotNetProducerGenericOwnerSealedTypeDefRole.NATURAL_INTERFACE to Triple(
            DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE,
            1,
            2,
        ),
        DotNetProducerGenericOwnerSealedTypeDefRole.INTERFACE_SEMANTIC_CAPABILITY to Triple(
            DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE,
            0,
            1,
        ),
        DotNetProducerGenericOwnerSealedTypeDefRole.IMPLEMENTATION_CLASS to Triple(
            DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS,
            1,
            1,
        ),
        DotNetProducerGenericOwnerSealedTypeDefRole.CLASS_SEMANTIC_CAPABILITY to Triple(
            DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE,
            0,
            1,
        ),
    )
    expectedTypeShapes.forEach { entry ->
        val row = types.getValue(entry.key).row.structural
        val expected = entry.value
        if (row.category != expected.first || row.genericArity != expected.second ||
            row.aliases.size != expected.third
        ) {
            add("a producer-sealed ${entry.key} row contradicts the bounded TypeDef role shape")
        }
    }
    val naturalType = types.getValue(
        DotNetProducerGenericOwnerSealedTypeDefRole.NATURAL_INTERFACE,
    ).row.structural
    val interfaceCapabilityType = types.getValue(
        DotNetProducerGenericOwnerSealedTypeDefRole.INTERFACE_SEMANTIC_CAPABILITY,
    ).row.structural
    val implementationType = types.getValue(
        DotNetProducerGenericOwnerSealedTypeDefRole.IMPLEMENTATION_CLASS,
    ).row.structural
    val classCapabilityType = types.getValue(
        DotNetProducerGenericOwnerSealedTypeDefRole.CLASS_SEMANTIC_CAPABILITY,
    ).row.structural
    body.typeDefs.forEach { type ->
        val flags = type.row.flags
        val expectsInterface = type.role != DotNetProducerGenericOwnerSealedTypeDefRole.IMPLEMENTATION_CLASS
        if (flags.layout != DotNetIlRawTypeDefLayout.AUTO ||
            flags.stringFormat != DotNetIlRawTypeDefStringFormat.ANSI ||
            flags.isInterface != expectsInterface ||
            flags.isAbstract != expectsInterface ||
            flags.isSealed != !expectsInterface ||
            expectsInterface && flags.isBeforeFieldInit
        ) {
            add("a producer-sealed ${type.role} row contradicts the bounded TypeDef flags")
        }
    }
    if (naturalType.genericParameters.singleOrNull()?.let { parameter ->
            parameter.variance == DotNetGenericOwnerPhysicalTypeParameterVariance.COVARIANT &&
                    parameter.constraints.isEmpty()
        } != true || implementationType.genericParameters.singleOrNull()?.let { parameter ->
            parameter.variance == DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT &&
                    parameter.constraints.isEmpty()
        } != true || interfaceCapabilityType.genericParameters.isNotEmpty() ||
        classCapabilityType.genericParameters.isNotEmpty()
    ) {
        add("a producer-sealed family contradicts the bounded TypeDef GenericParam rows")
    }
    fun construction(
        type: DotNetGenericOwnerCompleteEmissionTypeDefRow,
        arguments: List<DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape> = emptyList(),
    ) = DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.Construction(type.identityKey, arguments)
    val implementationParameter = DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.OwnerParameter(
        implementationType.identityKey,
        0,
    )
    val expectedClassCapabilityEdges = setOf(DotNetGenericOwnerCompleteEmissionTypeDefEdgeRow(
        DotNetGenericOwnerDirectSupertypeKind.INTERFACE,
        construction(interfaceCapabilityType),
    ))
    val expectedImplementationEdges = setOf(
        DotNetGenericOwnerCompleteEmissionTypeDefEdgeRow(
            DotNetGenericOwnerDirectSupertypeKind.BASE_CLASS,
            DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.Leaf(
                DotNetGenericOwnerPhysicalTypeKind.OBJECT,
            ),
        ),
        DotNetGenericOwnerCompleteEmissionTypeDefEdgeRow(
            DotNetGenericOwnerDirectSupertypeKind.INTERFACE,
            construction(naturalType, listOf(implementationParameter)),
        ),
        DotNetGenericOwnerCompleteEmissionTypeDefEdgeRow(
            DotNetGenericOwnerDirectSupertypeKind.INTERFACE,
            construction(classCapabilityType),
        ),
        DotNetGenericOwnerCompleteEmissionTypeDefEdgeRow(
            DotNetGenericOwnerDirectSupertypeKind.INTERFACE,
            construction(interfaceCapabilityType),
        ),
    )
    if (naturalType.directEdges.isNotEmpty() || interfaceCapabilityType.directEdges.isNotEmpty() ||
        classCapabilityType.directEdges.size != expectedClassCapabilityEdges.size ||
        classCapabilityType.directEdges.toSet() != expectedClassCapabilityEdges ||
        implementationType.directEdges.size != expectedImplementationEdges.size ||
        implementationType.directEdges.toSet() != expectedImplementationEdges
    ) {
        add("a producer-sealed family has an incompatible complete direct TypeDef edge set")
    }
    if (body.typeDefs.any { type ->
            type.row.structural.identityKey.value < 0 ||
                    type.row.structural.aliases.any { alias -> alias.value < 0 }
        } || body.methodDefs.any { method -> method.row.structural.identityKey.value < 0 }
    ) {
        add("a producer-sealed family contains a negative family-local physical key")
    }
    val expectedOwners = mapOf(
        DotNetProducerGenericOwnerSealedMethodDefRole.NATURAL_INTERFACE_SLOT to
                DotNetProducerGenericOwnerSealedTypeDefRole.NATURAL_INTERFACE,
        DotNetProducerGenericOwnerSealedMethodDefRole.INTERFACE_SEMANTIC_CAPABILITY_SLOT to
                DotNetProducerGenericOwnerSealedTypeDefRole.INTERFACE_SEMANTIC_CAPABILITY,
        DotNetProducerGenericOwnerSealedMethodDefRole.IMPLEMENTATION_TYPED_ENTRY to
                DotNetProducerGenericOwnerSealedTypeDefRole.IMPLEMENTATION_CLASS,
        DotNetProducerGenericOwnerSealedMethodDefRole.CLASS_SEMANTIC_CAPABILITY_SLOT to
                DotNetProducerGenericOwnerSealedTypeDefRole.CLASS_SEMANTIC_CAPABILITY,
        DotNetProducerGenericOwnerSealedMethodDefRole.CLASS_SEMANTIC_CAPABILITY_DISPATCHER to
                DotNetProducerGenericOwnerSealedTypeDefRole.IMPLEMENTATION_CLASS,
        DotNetProducerGenericOwnerSealedMethodDefRole.INTERFACE_SEMANTIC_CAPABILITY_DISPATCHER to
                DotNetProducerGenericOwnerSealedTypeDefRole.IMPLEMENTATION_CLASS,
    )
    expectedOwners.forEach { entry ->
        val method = methods.getValue(entry.key)
        val header = method.row.structural.header
        val expectedType = types.getValue(entry.value).row.structural
        val expectedReceiver = construction(
            expectedType,
            List(expectedType.genericArity) { index ->
                DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.OwnerParameter(
                    expectedType.identityKey,
                    index,
                )
            },
        )
        val isDispatcher = entry.key in setOf(
            DotNetProducerGenericOwnerSealedMethodDefRole.CLASS_SEMANTIC_CAPABILITY_DISPATCHER,
            DotNetProducerGenericOwnerSealedMethodDefRole.INTERFACE_SEMANTIC_CAPABILITY_DISPATCHER,
        )
        val isImplementation = entry.key ==
                DotNetProducerGenericOwnerSealedMethodDefRole.IMPLEMENTATION_TYPED_ENTRY
        val expectedVisibility = if (isDispatcher) {
            DotNetGenericOwnerPhysicalMethodDefEmissionVisibility.PRIVATE
        } else {
            DotNetGenericOwnerPhysicalMethodDefEmissionVisibility.PUBLIC
        }
        val expectedRawVisibility = if (isDispatcher) {
            DotNetIlRawMethodDefVisibility.PRIVATE
        } else {
            DotNetIlRawMethodDefVisibility.PUBLIC
        }
        val expectedDispatch = when {
            isDispatcher -> DotNetGenericOwnerPhysicalMemberDispatch.FINAL
            isImplementation -> DotNetGenericOwnerPhysicalMemberDispatch.OVERRIDABLE
            else -> DotNetGenericOwnerPhysicalMemberDispatch.ABSTRACT
        }
        val expectedRawDispatch = when {
            isDispatcher -> DotNetIlRawMethodDefDispatch(
                isInstance = true,
                isVirtual = true,
                isNewSlot = true,
                isAbstract = false,
                isFinal = true,
            )
            isImplementation -> DotNetIlRawMethodDefDispatch(
                isInstance = true,
                isVirtual = true,
                isNewSlot = true,
                isAbstract = false,
                isFinal = false,
            )
            else -> DotNetIlRawMethodDefDispatch(
                isInstance = true,
                isVirtual = true,
                isNewSlot = true,
                isAbstract = true,
                isFinal = false,
            )
        }
        if (header.owner != expectedType.identityKey ||
            header.ownerGenericArity != expectedType.genericArity ||
            header.ownerCategory != expectedType.category ||
            header.visibility != expectedVisibility ||
            header.dispatch != expectedDispatch ||
            !header.isInstance ||
            header.receiverCarrier != expectedReceiver ||
            method.row.visibility != expectedRawVisibility ||
            method.row.dispatch != expectedRawDispatch ||
            !method.row.isHideBySig || method.row.isSpecialName || method.row.isRuntimeSpecialName
        ) {
            add("a producer-sealed ${entry.key} MethodDef contradicts the bounded role flags and receiver")
        }
    }
    val methodArities = body.methodDefs.map { method -> method.row.structural.header.genericArity }.toSet()
    val onlyMethodArity = methodArities.singleOrNull()
    if (onlyMethodArity == null || onlyMethodArity !in 0..1) {
        add("a producer-sealed family contradicts the bounded MethodDef generic arity")
    } else {
        val methodArity = onlyMethodArity
        body.methodDefs.forEach { method ->
            val structural = method.row.structural
            val expectedParameter = if (methodArity == 1) {
                DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.MethodParameter(
                    structural.identityKey,
                    0,
                )
            } else {
                null
            }
            if (structural.genericParameters.size != methodArity ||
                structural.genericParameters.any { parameter ->
                    parameter.variance != DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT ||
                            parameter.constraints.isNotEmpty()
                } || structural.header.ordinaryParameterCarriers != listOfNotNull(expectedParameter) ||
                method.logicalParameterDomains != List(methodArity) {
                    DotNetGenericOwnerPhysicalSlotDomain.DECLARATION_INDEPENDENT
                }
            ) {
                add("a producer-sealed ${method.role} row contradicts the bounded MethodDef parameter grammar")
            }
        }
    }

    fun resultPayload(
        method: DotNetProducerGenericOwnerSealedMethodDef,
    ): DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape? = when (
        val result = method.row.structural.header.result
    ) {
        is DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.Direct -> result.carrier
        is DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.SplitNullable -> result.payload
        DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.Void -> null
    }
    val expectedResultPayloads = mapOf(
        DotNetProducerGenericOwnerSealedMethodDefRole.NATURAL_INTERFACE_SLOT to
                DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.OwnerParameter(
                    naturalType.identityKey,
                    0,
                ),
        DotNetProducerGenericOwnerSealedMethodDefRole.IMPLEMENTATION_TYPED_ENTRY to
                implementationParameter,
    )
    val objectCarrier = DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.Leaf(
        DotNetGenericOwnerPhysicalTypeKind.OBJECT,
    )
    body.methodDefs.forEach { method ->
        val expectedPayload = expectedResultPayloads[method.role] ?: objectCarrier
        if (resultPayload(method) != expectedPayload ||
            method.logicalResultDomain != DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_OUTPUT
        ) {
            add("a producer-sealed ${method.role} row contradicts the bounded result grammar")
        }
    }
    val naturalResult = methods.getValue(
        DotNetProducerGenericOwnerSealedMethodDefRole.NATURAL_INTERFACE_SLOT,
    ).row.structural.header.result
    val implementationResult = methods.getValue(
        DotNetProducerGenericOwnerSealedMethodDefRole.IMPLEMENTATION_TYPED_ENTRY,
    ).row.structural.header.result
    val typedLayoutsMatch = naturalResult is DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.Direct &&
            implementationResult is DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.Direct ||
            naturalResult is DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.SplitNullable &&
            implementationResult is DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.SplitNullable
    if (!typedLayoutsMatch) {
        add("a producer-sealed family requires matching natural and typed-entry result layouts")
    }
    val semanticRoles = setOf(
        DotNetProducerGenericOwnerSealedMethodDefRole.INTERFACE_SEMANTIC_CAPABILITY_SLOT,
        DotNetProducerGenericOwnerSealedMethodDefRole.CLASS_SEMANTIC_CAPABILITY_SLOT,
        DotNetProducerGenericOwnerSealedMethodDefRole.CLASS_SEMANTIC_CAPABILITY_DISPATCHER,
        DotNetProducerGenericOwnerSealedMethodDefRole.INTERFACE_SEMANTIC_CAPABILITY_DISPATCHER,
    )
    if (semanticRoles.any { role ->
            methods.getValue(role).row.structural.header.result !=
                    DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.Direct(objectCarrier)
        }
    ) {
        add("a producer-sealed family requires every semantic MethodDef to return object directly")
    }

    val natural = methods.getValue(DotNetProducerGenericOwnerSealedMethodDefRole.NATURAL_INTERFACE_SLOT)
    val implementation = methods.getValue(DotNetProducerGenericOwnerSealedMethodDefRole.IMPLEMENTATION_TYPED_ENTRY)
    if (natural.row.physicalName != implementation.row.physicalName) {
        add("a producer-sealed implicit natural implementation must retain the natural slot name")
    }

    body.methodDefs.forEach { method ->
        val header = method.row.structural.header
        if (method.logicalParameterDomains.size != header.ordinaryParameterCarriers.size) {
            add("a producer-sealed ${method.role} MethodDef has an incoherent logical parameter-domain vector")
        }
        if (method.logicalParameterDomains.any { domain -> domain !in setOf(
                DotNetGenericOwnerPhysicalSlotDomain.DECLARATION_INDEPENDENT,
                DotNetGenericOwnerPhysicalSlotDomain.OWNER_EXACT_RECEIVER,
                DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_INPUT,
                DotNetGenericOwnerPhysicalSlotDomain.BROAD_CANDIDATE_INPUT,
            )
        }) {
            add("a producer-sealed ${method.role} MethodDef has a non-input logical slot domain")
        }
        val expectsResultDomain = header.result != DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.Void
        if (expectsResultDomain != (method.logicalResultDomain != null) ||
            method.logicalResultDomain != null && method.logicalResultDomain !in setOf(
                DotNetGenericOwnerPhysicalSlotDomain.DECLARATION_INDEPENDENT,
                DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_OUTPUT,
            )
        ) {
            add("a producer-sealed ${method.role} MethodDef has an incoherent logical result domain")
        }
    }

    fun expectedMethodImpl(
        role: DotNetProducerGenericOwnerSealedMethodImplRole,
        bodyRole: DotNetProducerGenericOwnerSealedMethodDefRole,
        declarationOwnerRole: DotNetProducerGenericOwnerSealedTypeDefRole,
        declarationRole: DotNetProducerGenericOwnerSealedMethodDefRole,
    ) {
        val row = methodImpls.getValue(role).row
        val implementingType = types.getValue(
            DotNetProducerGenericOwnerSealedTypeDefRole.IMPLEMENTATION_CLASS,
        ).row.structural.identityKey
        val bodyMethod = methods.getValue(bodyRole).row.structural.identityKey
        val declarationMethod = methods.getValue(declarationRole).row.structural.identityKey
        val declarationOwner = row.declarationOwner as?
                DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.Construction
        val expectedDeclarationOwner = types.getValue(declarationOwnerRole).row.structural.identityKey
        val endpointsMatch = row.implementingTypeDefKey == implementingType &&
                row.bodyMethodDefKey == bodyMethod &&
                row.declarationMethodDefKey == declarationMethod &&
                declarationOwner != null &&
                declarationOwner.definition == expectedDeclarationOwner &&
                declarationOwner.arguments.isEmpty()
        if (!endpointsMatch) {
            add("a producer-sealed $role row has the wrong bounded MethodImpl endpoints")
            return
        }
        val exactDeclarationOwner = checkNotNull(declarationOwner)

        val bodyHeader = methods.getValue(bodyRole).row.structural.header
        val declarationHeader = methods.getValue(declarationRole).row.structural.header
        fun substituteDeclarationCarrier(
            carrier: DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape,
        ): DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape? = when (carrier) {
            is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.Leaf -> carrier
            is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.OwnerParameter ->
                if (carrier.binder == declarationHeader.owner) {
                    exactDeclarationOwner.arguments.getOrNull(carrier.index)
                } else {
                    null
                }
            is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.MethodParameter ->
                if (carrier.binder == declarationMethod) {
                    DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.MethodParameter(
                        bodyMethod,
                        carrier.index,
                    )
                } else {
                    null
                }
            is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.Construction -> {
                val arguments = carrier.arguments.map { argument ->
                    substituteDeclarationCarrier(argument) ?: return null
                }
                carrier.copy(arguments = arguments)
            }
            is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.SzArray ->
                substituteDeclarationCarrier(carrier.element)?.let { element -> carrier.copy(element = element) }
            is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.ByReference ->
                substituteDeclarationCarrier(carrier.element)?.let { element -> carrier.copy(element = element) }
            DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.Other -> null
        }
        fun substituteDeclarationResult(
            result: DotNetGenericOwnerPhysicalMethodDefEmissionResultShape,
        ): DotNetGenericOwnerPhysicalMethodDefEmissionResultShape? = when (result) {
            DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.Void -> result
            is DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.Direct ->
                substituteDeclarationCarrier(result.carrier)?.let { carrier -> result.copy(carrier = carrier) }
            is DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.SplitNullable ->
                substituteDeclarationCarrier(result.payload)?.let { payload -> result.copy(payload = payload) }
        }
        val substitutedDeclarationParameters = declarationHeader.ordinaryParameterCarriers.map { carrier ->
            substituteDeclarationCarrier(carrier)
        }
        if (substitutedDeclarationParameters.any { carrier -> carrier == null }) {
            add("a producer-sealed $role MethodImpl has an unbindable declaration signature")
            return
        }
        val declarationParameters = substitutedDeclarationParameters.filterNotNull()
        val declarationResult = substituteDeclarationResult(declarationHeader.result)
        if (bodyHeader.isInstance != declarationHeader.isInstance ||
            bodyHeader.genericArity != declarationHeader.genericArity ||
            bodyHeader.ordinaryParameterCarriers != declarationParameters ||
            declarationResult == null || bodyHeader.result != declarationResult
        ) {
            add("a producer-sealed $role MethodImpl has incompatible body and declaration signatures")
        }
    }
    expectedMethodImpl(
        DotNetProducerGenericOwnerSealedMethodImplRole.CLASS_SEMANTIC_CAPABILITY_IMPLEMENTATION,
        DotNetProducerGenericOwnerSealedMethodDefRole.CLASS_SEMANTIC_CAPABILITY_DISPATCHER,
        DotNetProducerGenericOwnerSealedTypeDefRole.CLASS_SEMANTIC_CAPABILITY,
        DotNetProducerGenericOwnerSealedMethodDefRole.CLASS_SEMANTIC_CAPABILITY_SLOT,
    )
    expectedMethodImpl(
        DotNetProducerGenericOwnerSealedMethodImplRole.INTERFACE_SEMANTIC_CAPABILITY_IMPLEMENTATION,
        DotNetProducerGenericOwnerSealedMethodDefRole.INTERFACE_SEMANTIC_CAPABILITY_DISPATCHER,
        DotNetProducerGenericOwnerSealedTypeDefRole.INTERFACE_SEMANTIC_CAPABILITY,
        DotNetProducerGenericOwnerSealedMethodDefRole.INTERFACE_SEMANTIC_CAPABILITY_SLOT,
    )
}

internal sealed interface DotNetProducerGenericOwnerSealedFamilyDecodeResult {
    data class Success(
        val publication: DotNetProducerGenericOwnerSealedFamilyPublication,
    ) : DotNetProducerGenericOwnerSealedFamilyDecodeResult

    data class Malformed(val reason: String) : DotNetProducerGenericOwnerSealedFamilyDecodeResult {
        init {
            require(reason.isNotEmpty()) { "a malformed producer-sealed family requires a reason" }
        }
    }
}

/** Versioned deterministic payload codec embedded by the rehearsal's producer-library ABI. */
internal object DotNetProducerGenericOwnerSealedFamilyCodec {
    private const val MAGIC = 0x4B_44_53_46 // KDSF
    private const val VERSION = 1
    private const val MAX_COLLECTION_SIZE = 1_024
    private const val MAX_STRING_BYTES = 1_048_576
    private const val MAX_CARRIER_DEPTH = 64

    /**
     * The comparison keys are invocation-local coordinates, not portable identities. Project the
     * already validated 4/6/2 role graph onto one fixed wire namespace before serializing it.
     */
    private fun DotNetProducerGenericOwnerSealedFamilyPublication.canonicalizedForWire():
            DotNetProducerGenericOwnerSealedFamilyPublication {
        val orderedTypes = body.typeDefs.sortedBy { row -> row.role.ordinal }
        val orderedMethods = body.methodDefs.sortedBy { row -> row.role.ordinal }
        val typeKeys = orderedTypes.associate { row ->
            row.row.structural.identityKey to
                    DotNetGenericOwnerPhysicalMethodDefEmissionTypeKey(row.role.ordinal)
        }
        val methodKeys = orderedMethods.associate { row ->
            row.row.structural.identityKey to
                    DotNetGenericOwnerPhysicalMethodDefEmissionMethodKey(row.role.ordinal)
        }

        fun remapCarrier(
            carrier: DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape,
        ): DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape = when (carrier) {
            is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.Leaf -> carrier
            is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.OwnerParameter -> carrier.copy(
                binder = typeKeys.getValue(carrier.binder),
            )
            is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.MethodParameter -> carrier.copy(
                binder = methodKeys.getValue(carrier.binder),
            )
            is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.Construction -> carrier.copy(
                definition = typeKeys.getValue(carrier.definition),
                arguments = carrier.arguments.map(::remapCarrier),
            )
            is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.SzArray -> carrier.copy(
                element = remapCarrier(carrier.element),
            )
            is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.ByReference -> carrier.copy(
                element = remapCarrier(carrier.element),
            )
            DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.Other -> carrier
        }
        fun remapParameter(
            parameter: DotNetGenericOwnerCompleteEmissionGenericParameterRow,
        ) = parameter.copy(
            constraints = parameter.constraints.map(::remapCarrier)
                .sortedBy { constraint -> constraint.canonicalSortKey() },
        )
        fun remapResult(
            result: DotNetGenericOwnerPhysicalMethodDefEmissionResultShape,
        ): DotNetGenericOwnerPhysicalMethodDefEmissionResultShape = when (result) {
            DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.Void -> result
            is DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.Direct ->
                result.copy(carrier = remapCarrier(result.carrier))
            is DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.SplitNullable ->
                result.copy(payload = remapCarrier(result.payload))
        }

        var nextAlias = 0
        val canonicalTypes = orderedTypes.map { type ->
            val structural = type.row.structural
            val aliases = List(structural.aliases.size) {
                DotNetGenericOwnerCompleteEmissionTypeDefAliasKey(nextAlias++)
            }
            val edges = structural.directEdges.map { edge ->
                edge.copy(target = remapCarrier(edge.target))
            }.sortedWith(compareBy(
                { edge: DotNetGenericOwnerCompleteEmissionTypeDefEdgeRow -> edge.kind.ordinal },
                { edge -> edge.target.canonicalSortKey() },
            ))
            type.copy(row = type.row.copy(
                structural = structural.copy(
                    identityKey = typeKeys.getValue(structural.identityKey),
                    aliases = aliases,
                    genericParameters = structural.genericParameters.map(::remapParameter),
                    directEdges = edges,
                ),
            ))
        }
        val canonicalMethods = orderedMethods.map { method ->
            val structural = method.row.structural
            val header = structural.header
            method.copy(row = method.row.copy(
                structural = structural.copy(
                    identityKey = methodKeys.getValue(structural.identityKey),
                    header = header.copy(
                        owner = typeKeys.getValue(header.owner),
                        receiverCarrier = header.receiverCarrier?.let(::remapCarrier),
                        ordinaryParameterCarriers = header.ordinaryParameterCarriers.map(::remapCarrier),
                        result = remapResult(header.result),
                    ),
                    genericParameters = structural.genericParameters.map(::remapParameter),
                ),
            ))
        }
        val canonicalMethodImpls = body.methodImpls.sortedBy { row -> row.role.ordinal }.map { methodImpl ->
            val row = methodImpl.row
            methodImpl.copy(row = row.copy(
                implementingTypeDefKey = typeKeys.getValue(row.implementingTypeDefKey),
                bodyMethodDefKey = methodKeys.getValue(row.bodyMethodDefKey),
                declarationOwner = remapCarrier(row.declarationOwner),
                declarationMethodDefKey = methodKeys.getValue(row.declarationMethodDefKey),
            ))
        }
        return copy(body = DotNetProducerGenericOwnerSealedFamilyBody(
            canonicalTypes,
            canonicalMethods,
            canonicalMethodImpls,
        ))
    }

    fun encode(publication: DotNetProducerGenericOwnerSealedFamilyPublication): ByteArray {
        val validation = inspectDotNetProducerGenericOwnerSealedFamily(publication)
        require(validation is DotNetGenericOwnerPhysicalBindingResult.Bound) {
            "cannot encode an invalid producer-sealed family: " +
                    (validation as? DotNetGenericOwnerPhysicalBindingResult.Conflict)?.reason.orEmpty()
        }
        val canonicalPublication = publication.canonicalizedForWire()
        val canonicalValidation = inspectDotNetProducerGenericOwnerSealedFamily(canonicalPublication)
        check(canonicalValidation is DotNetGenericOwnerPhysicalBindingResult.Bound) {
            "canonical producer-sealed family projection lost its validated physical authority"
        }
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { sink ->
            sink.writeInt(MAGIC)
            sink.writeInt(VERSION)
            sink.writeString(canonicalPublication.key.logicalInterfaceMemberKey)
            sink.writeString(canonicalPublication.key.implementationOwnerKey)
            sink.writeString(canonicalPublication.key.implementationMemberKey)
            sink.writeList(canonicalPublication.body.typeDefs) { row ->
                writeEnum(row.role)
                writeTypeDef(row.row)
            }
            sink.writeList(canonicalPublication.body.methodDefs) { row ->
                writeEnum(row.role)
                writeMethodDef(row.row)
                writeList(row.logicalParameterDomains) { domain -> writeEnum(domain) }
                writeNullableEnum(row.logicalResultDomain)
            }
            sink.writeList(canonicalPublication.body.methodImpls) { row ->
                writeEnum(row.role)
                writeMethodImpl(row.row)
            }
        }
        return output.toByteArray()
    }

    fun decode(bytes: ByteArray): DotNetProducerGenericOwnerSealedFamilyDecodeResult {
        if (bytes.size > MAX_STRING_BYTES * 16) {
            return DotNetProducerGenericOwnerSealedFamilyDecodeResult.Malformed(
                "the producer-sealed family record exceeds its bounded size",
            )
        }
        return try {
            val source = DataInputStream(ByteArrayInputStream(bytes))
            require(source.readInt() == MAGIC) { "wrong producer-sealed family magic" }
            require(source.readInt() == VERSION) { "unsupported producer-sealed family version" }
            val key = DotNetProducerGenericOwnerSealedFamilyKey(
                source.readString(),
                source.readString(),
                source.readString(),
            )
            val typeDefs = source.readList { input ->
                DotNetProducerGenericOwnerSealedTypeDef(
                    input.readEnum<DotNetProducerGenericOwnerSealedTypeDefRole>(),
                    input.readTypeDef(),
                )
            }
            val methodDefs = source.readList { input ->
                DotNetProducerGenericOwnerSealedMethodDef(
                    input.readEnum<DotNetProducerGenericOwnerSealedMethodDefRole>(),
                    input.readMethodDef(),
                    input.readList { nested -> nested.readEnum<DotNetGenericOwnerPhysicalSlotDomain>() },
                    input.readNullableEnum<DotNetGenericOwnerPhysicalSlotDomain>(),
                )
            }
            val methodImpls = source.readList { input ->
                DotNetProducerGenericOwnerSealedMethodImpl(
                    input.readEnum<DotNetProducerGenericOwnerSealedMethodImplRole>(),
                    input.readMethodImpl(),
                )
            }
            require(source.available() == 0) { "trailing producer-sealed family bytes" }
            val publication = DotNetProducerGenericOwnerSealedFamilyPublication(
                key,
                DotNetProducerGenericOwnerSealedFamilyBody(typeDefs, methodDefs, methodImpls),
            )
            when (val inspection = inspectDotNetProducerGenericOwnerSealedFamily(publication)) {
                is DotNetGenericOwnerPhysicalBindingResult.Bound ->
                    DotNetProducerGenericOwnerSealedFamilyDecodeResult.Success(publication)
                is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                    DotNetProducerGenericOwnerSealedFamilyDecodeResult.Malformed(inspection.reason)
                DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                    DotNetProducerGenericOwnerSealedFamilyDecodeResult.Malformed(
                        "a decoded producer-sealed family unexpectedly lacked authority",
                    )
            }
        } catch (failure: Exception) {
            val reason = when (failure) {
                is EOFException -> "truncated producer-sealed family record"
                else -> failure.message ?: failure::class.java.simpleName
            }
            DotNetProducerGenericOwnerSealedFamilyDecodeResult.Malformed(reason)
        }
    }

    private fun DataOutputStream.writeTypeDef(row: DotNetGenericOwnerSealedEmissionTypeDefRow) {
        writeInt(row.structural.identityKey.value)
        writeList(row.structural.aliases.sortedBy { alias -> alias.value }) { alias ->
            writeInt(alias.value)
        }
        writeInt(row.structural.genericArity)
        writeEnum(row.structural.category)
        writeList(row.structural.genericParameters) { parameter -> writeGenericParameter(parameter) }
        writeList(row.structural.directEdges.sortedWith(compareBy(
            { edge: DotNetGenericOwnerCompleteEmissionTypeDefEdgeRow -> edge.kind.ordinal },
            { edge -> edge.target.canonicalSortKey() },
        ))) { edge ->
            writeEnum(edge.kind)
            writeCarrier(edge.target)
        }
        writeList(row.physicalPath) { component -> writeString(component) }
        writeEnum(row.flags.visibility)
        writeEnum(row.flags.layout)
        writeEnum(row.flags.stringFormat)
        writeBoolean(row.flags.isInterface)
        writeBoolean(row.flags.isAbstract)
        writeBoolean(row.flags.isSealed)
        writeBoolean(row.flags.isBeforeFieldInit)
    }

    private fun DataInputStream.readTypeDef(): DotNetGenericOwnerSealedEmissionTypeDefRow {
        val key = DotNetGenericOwnerPhysicalMethodDefEmissionTypeKey(readNonNegativeInt("TypeDef key"))
        val aliases = readList { input ->
            DotNetGenericOwnerCompleteEmissionTypeDefAliasKey(input.readNonNegativeInt("alias key"))
        }
        val arity = readNonNegativeInt("TypeDef generic arity")
        val category = readEnum<DotNetGenericOwnerPhysicalNamedTypeCategory>()
        val parameters = readList { input -> input.readGenericParameter() }
        val edges = readList { input ->
            DotNetGenericOwnerCompleteEmissionTypeDefEdgeRow(
                input.readEnum<DotNetGenericOwnerDirectSupertypeKind>(),
                input.readCarrier(),
            )
        }
        val path = readList { input -> input.readString() }
        val flags = DotNetIlRawTypeDefFlags(
            readEnum(),
            readEnum(),
            readEnum(),
            readBoolean(),
            readBoolean(),
            readBoolean(),
            readBoolean(),
        )
        return DotNetGenericOwnerSealedEmissionTypeDefRow(
            DotNetGenericOwnerCompleteEmissionTypeDefRow(
                key,
                aliases,
                arity,
                category,
                parameters,
                edges,
            ),
            path,
            flags,
        )
    }

    private fun DataOutputStream.writeMethodDef(row: DotNetGenericOwnerSealedEmissionMethodDefRow) {
        val structural = row.structural
        writeInt(structural.identityKey.value)
        writeHeader(structural.header)
        writeList(structural.genericParameters) { parameter -> writeGenericParameter(parameter) }
        writeString(row.physicalName)
        writeList(row.physicalGenericParameterNames) { name -> writeString(name) }
        writeEnum(row.visibility)
        writeDispatch(row.dispatch)
        writeBoolean(row.isHideBySig)
        writeBoolean(row.isSpecialName)
        writeBoolean(row.isRuntimeSpecialName)
    }

    private fun DataInputStream.readMethodDef(): DotNetGenericOwnerSealedEmissionMethodDefRow {
        val key = DotNetGenericOwnerPhysicalMethodDefEmissionMethodKey(readNonNegativeInt("MethodDef key"))
        val structural = DotNetGenericOwnerCompleteEmissionMethodDefRow(
            key,
            readHeader(),
            readList { input -> input.readGenericParameter() },
        )
        return DotNetGenericOwnerSealedEmissionMethodDefRow(
            structural,
            readString(),
            readList { input -> input.readString() },
            readEnum(),
            readDispatch(),
            readBoolean(),
            readBoolean(),
            readBoolean(),
        )
    }

    private fun DataOutputStream.writeHeader(header: DotNetGenericOwnerPhysicalMethodDefEmissionHeaderShape) {
        writeInt(header.owner.value)
        writeInt(header.ownerGenericArity)
        writeEnum(header.ownerCategory)
        writeEnum(header.visibility)
        writeEnum(header.dispatch)
        writeBoolean(header.isInstance)
        writeInt(header.genericArity)
        writeBoolean(header.receiverCarrier != null)
        header.receiverCarrier?.let { receiver -> writeCarrier(receiver) }
        writeList(header.ordinaryParameterCarriers) { parameter -> writeCarrier(parameter) }
        when (val result = header.result) {
            DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.Void -> writeByte(0)
            is DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.Direct -> {
                writeByte(1)
                writeCarrier(result.carrier)
            }
            is DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.SplitNullable -> {
                writeByte(2)
                writeCarrier(result.payload)
            }
        }
    }

    private fun DataInputStream.readHeader(): DotNetGenericOwnerPhysicalMethodDefEmissionHeaderShape {
        val owner = DotNetGenericOwnerPhysicalMethodDefEmissionTypeKey(readNonNegativeInt("MethodDef owner key"))
        val ownerArity = readNonNegativeInt("MethodDef owner arity")
        val ownerCategory = readEnum<DotNetGenericOwnerPhysicalNamedTypeCategory>()
        val visibility = readEnum<DotNetGenericOwnerPhysicalMethodDefEmissionVisibility>()
        val dispatch = readEnum<DotNetGenericOwnerPhysicalMemberDispatch>()
        val isInstance = readBoolean()
        val arity = readNonNegativeInt("MethodDef generic arity")
        val receiver = if (readBoolean()) readCarrier() else null
        val parameters = readList { input -> input.readCarrier() }
        val result = when (val tag = readUnsignedByte()) {
            0 -> DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.Void
            1 -> DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.Direct(readCarrier())
            2 -> DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.SplitNullable(readCarrier())
            else -> error("unknown producer-sealed result-layout tag $tag")
        }
        return DotNetGenericOwnerPhysicalMethodDefEmissionHeaderShape(
            owner,
            ownerArity,
            ownerCategory,
            visibility,
            dispatch,
            isInstance,
            arity,
            receiver,
            parameters,
            result,
        )
    }

    private fun DataOutputStream.writeGenericParameter(
        parameter: DotNetGenericOwnerCompleteEmissionGenericParameterRow,
    ) {
        writeEnum(parameter.variance)
        writeList(parameter.constraints.sortedBy { constraint -> constraint.canonicalSortKey() }) { constraint ->
            writeCarrier(constraint)
        }
    }

    /** Stable ordering key only for metadata sets; ordered construction arguments remain ordered. */
    private fun DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.canonicalSortKey(): String =
        when (this) {
            is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.Leaf ->
                "0:${kind.ordinal}"
            is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.OwnerParameter ->
                "1:${binder.value}:$index"
            is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.MethodParameter ->
                "2:${binder.value}:$index"
            is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.Construction ->
                "3:${definition.value}:[${arguments.joinToString(",") { argument ->
                    argument.canonicalSortKey()
                }}]"
            is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.SzArray ->
                "4:${element.canonicalSortKey()}"
            is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.ByReference ->
                "5:${element.canonicalSortKey()}"
            DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.Other ->
                error("an unsupported carrier cannot enter a producer-sealed family record")
        }

    private fun DataInputStream.readGenericParameter() =
        DotNetGenericOwnerCompleteEmissionGenericParameterRow(
            readEnum(),
            readList { input -> input.readCarrier() },
        )

    private fun DataOutputStream.writeMethodImpl(row: DotNetGenericOwnerCompleteEmissionMethodImplRow) {
        writeInt(row.implementingTypeDefKey.value)
        writeInt(row.bodyMethodDefKey.value)
        writeCarrier(row.declarationOwner)
        writeInt(row.declarationMethodDefKey.value)
    }

    private fun DataInputStream.readMethodImpl() = DotNetGenericOwnerCompleteEmissionMethodImplRow(
        DotNetGenericOwnerPhysicalMethodDefEmissionTypeKey(readNonNegativeInt("MethodImpl type key")),
        DotNetGenericOwnerPhysicalMethodDefEmissionMethodKey(readNonNegativeInt("MethodImpl body key")),
        readCarrier(),
        DotNetGenericOwnerPhysicalMethodDefEmissionMethodKey(readNonNegativeInt("MethodImpl declaration key")),
    )

    private fun DataOutputStream.writeCarrier(
        carrier: DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape,
    ) {
        when (carrier) {
            is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.Leaf -> {
                writeByte(0)
                writeEnum(carrier.kind)
            }
            is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.OwnerParameter -> {
                writeByte(1)
                writeInt(carrier.binder.value)
                writeInt(carrier.index)
            }
            is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.MethodParameter -> {
                writeByte(2)
                writeInt(carrier.binder.value)
                writeInt(carrier.index)
            }
            is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.Construction -> {
                writeByte(3)
                writeInt(carrier.definition.value)
                writeList(carrier.arguments) { argument -> writeCarrier(argument) }
            }
            is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.SzArray -> {
                writeByte(4)
                writeCarrier(carrier.element)
            }
            is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.ByReference -> {
                writeByte(5)
                writeCarrier(carrier.element)
            }
            DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.Other ->
                error("an unsupported carrier cannot enter a producer-sealed family record")
        }
    }

    private fun DataInputStream.readCarrier(depth: Int = 0):
            DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape {
        require(depth <= MAX_CARRIER_DEPTH) { "producer-sealed carrier nesting is too deep" }
        return when (val tag = readUnsignedByte()) {
            0 -> DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.Leaf(readEnum())
            1 -> DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.OwnerParameter(
                DotNetGenericOwnerPhysicalMethodDefEmissionTypeKey(readNonNegativeInt("owner binder key")),
                readNonNegativeInt("owner parameter index"),
            )
            2 -> DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.MethodParameter(
                DotNetGenericOwnerPhysicalMethodDefEmissionMethodKey(readNonNegativeInt("method binder key")),
                readNonNegativeInt("method parameter index"),
            )
            3 -> DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.Construction(
                DotNetGenericOwnerPhysicalMethodDefEmissionTypeKey(readNonNegativeInt("construction key")),
                readList { input -> input.readCarrier(depth + 1) },
            )
            4 -> DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.SzArray(readCarrier(depth + 1))
            5 -> DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.ByReference(readCarrier(depth + 1))
            else -> error("unknown producer-sealed carrier tag $tag")
        }
    }

    private fun DataOutputStream.writeDispatch(dispatch: DotNetIlRawMethodDefDispatch) {
        writeBoolean(dispatch.isInstance)
        writeBoolean(dispatch.isVirtual)
        writeBoolean(dispatch.isNewSlot)
        writeBoolean(dispatch.isAbstract)
        writeBoolean(dispatch.isFinal)
    }

    private fun DataInputStream.readDispatch() = DotNetIlRawMethodDefDispatch(
        readBoolean(),
        readBoolean(),
        readBoolean(),
        readBoolean(),
        readBoolean(),
    )

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_STRING_BYTES) { "producer-sealed string is too large" }
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readString(): String {
        val size = readBoundedCount("string length", MAX_STRING_BYTES)
        val bytes = ByteArray(size)
        readFully(bytes)
        return Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }

    private inline fun <T> DataOutputStream.writeList(values: List<T>, write: DataOutputStream.(T) -> Unit) {
        require(values.size <= MAX_COLLECTION_SIZE) { "producer-sealed collection is too large" }
        writeInt(values.size)
        values.forEach { value -> write(value) }
    }

    private inline fun <T> DataInputStream.readList(read: (DataInputStream) -> T): List<T> {
        val size = readBoundedCount("collection size", MAX_COLLECTION_SIZE)
        return List(size) { read(this) }
    }

    private fun DataInputStream.readNonNegativeInt(label: String): Int =
        readInt().also { value -> require(value >= 0) { "$label must be non-negative" } }

    private fun DataInputStream.readBoundedCount(label: String, maximum: Int): Int =
        readInt().also { value -> require(value in 0..maximum) { "$label is outside its bounded range" } }

    private fun DataOutputStream.writeEnum(value: Enum<*>) = writeInt(value.ordinal)

    private inline fun <reified T : Enum<T>> DataInputStream.readEnum(): T {
        val values = enumValues<T>()
        val ordinal = readInt()
        require(ordinal in values.indices) { "unknown ${T::class.java.simpleName} ordinal $ordinal" }
        return values[ordinal]
    }

    private fun <T : Enum<T>> DataOutputStream.writeNullableEnum(value: T?) {
        writeBoolean(value != null)
        if (value != null) writeEnum(value)
    }

    private inline fun <reified T : Enum<T>> DataInputStream.readNullableEnum(): T? =
        if (readBoolean()) readEnum() else null
}
