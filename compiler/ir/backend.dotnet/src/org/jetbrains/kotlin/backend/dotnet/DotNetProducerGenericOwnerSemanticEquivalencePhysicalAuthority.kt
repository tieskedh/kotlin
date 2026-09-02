/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

/**
 * The exact physical declarations admitted by one PE-authenticated external `K`/`J` pair.
 *
 * The constructor is private deliberately: ordinary producer records and caller-created symbolic
 * descriptions cannot authorize a producer MethodDef.  [from] first rejoins the bound certificate
 * to its DLL stamp and then projects only rows already present in the same sealed `J` family.
 */
internal class DotNetProducerGenericOwnerSemanticEquivalencePhysicalProjection private constructor(
    val familyKey: DotNetProducerGenericOwnerSealedFamilyKey,
    val typeDefinitions: List<DotNetGenericOwnerPhysicalTypeDefReference>,
    val directSupertypeEdgeSets: List<DotNetGenericOwnerPhysicalDirectSupertypeEdgeSet>,
    val naturalMethodDefinition: DotNetGenericOwnerPhysicalMethodDefReference,
    typeDefinitionsByRole:
            Map<DotNetProducerGenericOwnerSealedTypeDefRole,
                    DotNetGenericOwnerPhysicalTypeDefIdentity.KotlinProducer>,
) {
    val typeDefinitionsByRole:
            Map<DotNetProducerGenericOwnerSealedTypeDefRole,
                    DotNetGenericOwnerPhysicalTypeDefIdentity.KotlinProducer> =
        typeDefinitionsByRole.toMap()

    val authorizedProducerMethodDefinitions:
            Set<DotNetGenericOwnerPhysicalMethodDefIdentity.KotlinProducer> = setOf(
        naturalMethodDefinition.identity as DotNetGenericOwnerPhysicalMethodDefIdentity.KotlinProducer,
    )

    companion object {
        fun from(
            bound: DotNetBoundGenericOwnerSemanticEquivalenceCertificate,
        ): DotNetGenericOwnerPhysicalBindingResult<
                DotNetProducerGenericOwnerSemanticEquivalencePhysicalProjection> {
            if (!bound.library.genericOwnerPeValidationStamp.authenticates(
                    bound.declaration,
                    bound.sealedFamily.declaration,
                )
            ) {
                // A raw serialized K/J pair is valid library data, but it is not physical authority.
                return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            }
            if (bound.authority.epoch !=
                DotNetGenericOwnerPhysicalAuthorityEpoch.SEALED_EMISSION_SIGNATURE_INDEX ||
                bound.authority.certificate != bound.certificate ||
                bound.authority.sealedFamily.publication != bound.sealedFamily.publication
            ) {
                return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                    "PE-authenticated semantic equivalence lost its exact sealed J authority",
                )
            }

            return try {
                project(bound)
            } catch (failure: IllegalArgumentException) {
                DotNetGenericOwnerPhysicalBindingResult.Conflict(
                    failure.message?.takeIf(String::isNotEmpty)
                        ?: "PE-authenticated semantic-equivalence projection is inconsistent",
                )
            }
        }

        private fun project(
            bound: DotNetBoundGenericOwnerSemanticEquivalenceCertificate,
        ): DotNetGenericOwnerPhysicalBindingResult<
                DotNetProducerGenericOwnerSemanticEquivalencePhysicalProjection> {
            val publication = bound.sealedFamily.publication
            val body = publication.body
            val typeRowsByRole = body.typeDefs.associateBy { typeDef -> typeDef.role }
            if (body.typeDefs.size != DotNetProducerGenericOwnerSealedTypeDefRole.entries.size ||
                typeRowsByRole.keys != DotNetProducerGenericOwnerSealedTypeDefRole.entries.toSet()
            ) {
                return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                    "PE-authenticated semantic equivalence does not contain the complete sealed J TypeDef family",
                )
            }

            val artifact = bound.library.artifact
            val identitiesByRole = typeRowsByRole.mapValues { entry ->
                DotNetGenericOwnerPhysicalTypeDefIdentity.KotlinProducer(
                    artifact,
                    entry.value.row.physicalPath,
                )
            }
            if (identitiesByRole.values.toSet().size != identitiesByRole.size) {
                return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                    "PE-authenticated semantic equivalence aliases distinct J TypeDef roles",
                )
            }
            val identitiesBySealedKey = typeRowsByRole.values.associate { typeDef ->
                typeDef.row.structural.identityKey to identitiesByRole.getValue(typeDef.role)
            }
            if (identitiesBySealedKey.size != identitiesByRole.size) {
                return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                    "PE-authenticated semantic equivalence contains duplicate J TypeDef keys",
                )
            }

            val naturalMethod = body.methodDefs.single { methodDef ->
                methodDef.role == DotNetProducerGenericOwnerSealedMethodDefRole.NATURAL_INTERFACE_SLOT
            }
            val naturalMethodIdentity = DotNetGenericOwnerPhysicalMethodDefIdentity.KotlinProducer(
                artifact,
                publication.naturalMethodDefPhysicalIdentity(),
            )
            val translator = SealedCarrierTranslator(
                identitiesBySealedKey,
                mapOf(naturalMethod.row.structural.identityKey to naturalMethodIdentity),
            )

            val typeDefinitions = mutableListOf<DotNetGenericOwnerPhysicalTypeDefReference>()
            val edgeSets = mutableListOf<DotNetGenericOwnerPhysicalDirectSupertypeEdgeSet>()
            for (role in DotNetProducerGenericOwnerSealedTypeDefRole.entries) {
                val sealedType = typeRowsByRole.getValue(role).row.structural
                val identity = identitiesByRole.getValue(role)
                val genericParameters = when (
                    val conversion = translator.genericParameters(sealedType.genericParameters)
                ) {
                    is DotNetGenericOwnerPhysicalBindingResult.Bound -> conversion.value
                    is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                        return DotNetGenericOwnerPhysicalBindingResult.Conflict(conversion.reason)
                    DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                        return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                }
                typeDefinitions += DotNetGenericOwnerPhysicalTypeDefReference(
                    identity = identity,
                    genericParameters = genericParameters,
                    category = sealedType.category,
                )

                val edges = mutableListOf<DotNetGenericOwnerPhysicalDirectSupertypeEdgeReference>()
                for (edge in sealedType.directEdges) {
                    val target = when (val conversion = translator.carrier(edge.target)) {
                        is DotNetGenericOwnerPhysicalBindingResult.Bound -> conversion.value
                        is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                            return DotNetGenericOwnerPhysicalBindingResult.Conflict(conversion.reason)
                        DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                            return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                    }
                    edges += DotNetGenericOwnerPhysicalDirectSupertypeEdgeReference(edge.kind, target)
                }
                edgeSets += DotNetGenericOwnerPhysicalDirectSupertypeEdgeSet(identity, edges)
            }

            val methodHeader = naturalMethod.row.structural.header
            val methodGenericParameters = when (
                val conversion = translator.genericParameters(
                    naturalMethod.row.structural.genericParameters,
                )
            ) {
                is DotNetGenericOwnerPhysicalBindingResult.Bound -> conversion.value
                is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                    return DotNetGenericOwnerPhysicalBindingResult.Conflict(conversion.reason)
                DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                    return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            }
            if (methodHeader.ordinaryParameterCarriers.size !=
                naturalMethod.logicalParameterDomains.size
            ) {
                return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                    "PE-authenticated natural MethodDef has an incomplete logical parameter-domain vector",
                )
            }
            val parameterSlots = mutableListOf<DotNetGenericOwnerPhysicalCallableValueSlotReference>()
            for (index in methodHeader.ordinaryParameterCarriers.indices) {
                val carrier = when (
                    val conversion = translator.carrier(
                        methodHeader.ordinaryParameterCarriers[index],
                    )
                ) {
                    is DotNetGenericOwnerPhysicalBindingResult.Bound -> conversion.value
                    is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                        return DotNetGenericOwnerPhysicalBindingResult.Conflict(conversion.reason)
                    DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                        return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                }
                parameterSlots += DotNetGenericOwnerPhysicalCallableValueSlotReference(
                    naturalMethod.logicalParameterDomains[index],
                    carrier,
                )
            }
            val resultLayout = when (val result = methodHeader.result) {
                DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.Void -> {
                    if (naturalMethod.logicalResultDomain != null) {
                        return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                            "PE-authenticated void natural MethodDef has a logical result domain",
                        )
                    }
                    DotNetGenericOwnerPhysicalCallableResultLayoutReference.Void
                }
                is DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.Direct -> {
                    val carrier = when (val conversion = translator.carrier(result.carrier)) {
                        is DotNetGenericOwnerPhysicalBindingResult.Bound -> conversion.value
                        is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                            return DotNetGenericOwnerPhysicalBindingResult.Conflict(conversion.reason)
                        DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                            return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                    }
                    DotNetGenericOwnerPhysicalCallableResultLayoutReference.Direct(
                        DotNetGenericOwnerPhysicalCallableValueSlotReference(
                            naturalMethod.logicalResultDomain ?: return
                                DotNetGenericOwnerPhysicalBindingResult.Conflict(
                                    "PE-authenticated natural MethodDef has no logical result domain",
                                ),
                            carrier,
                        ),
                    )
                }
                is DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.SplitNullable -> {
                    val payload = when (val conversion = translator.carrier(result.payload)) {
                        is DotNetGenericOwnerPhysicalBindingResult.Bound -> conversion.value
                        is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                            return DotNetGenericOwnerPhysicalBindingResult.Conflict(conversion.reason)
                        DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                            return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                    }
                    DotNetGenericOwnerPhysicalCallableResultLayoutReference.SplitNullable(
                        DotNetGenericOwnerPhysicalCallableValueSlotReference(
                            naturalMethod.logicalResultDomain ?: return
                                DotNetGenericOwnerPhysicalBindingResult.Conflict(
                                    "PE-authenticated split natural MethodDef has no logical result domain",
                                ),
                            payload,
                        ),
                    )
                }
            }
            val naturalMethodDefinition = DotNetGenericOwnerPhysicalMethodDefReference(
                identity = naturalMethodIdentity,
                declaringType = identitiesByRole.getValue(
                    DotNetProducerGenericOwnerSealedTypeDefRole.NATURAL_INTERFACE,
                ),
                visibility = when (methodHeader.visibility) {
                    DotNetGenericOwnerPhysicalMethodDefEmissionVisibility.PUBLIC ->
                        DotNetGenericOwnerPhysicalMemberVisibility.PUBLIC
                    DotNetGenericOwnerPhysicalMethodDefEmissionVisibility.FAMILY ->
                        DotNetGenericOwnerPhysicalMemberVisibility.FAMILY
                    DotNetGenericOwnerPhysicalMethodDefEmissionVisibility.ASSEMBLY ->
                        DotNetGenericOwnerPhysicalMemberVisibility.ASSEMBLY
                    DotNetGenericOwnerPhysicalMethodDefEmissionVisibility.FAMILY_OR_ASSEMBLY ->
                        DotNetGenericOwnerPhysicalMemberVisibility.FAMILY_OR_ASSEMBLY
                    DotNetGenericOwnerPhysicalMethodDefEmissionVisibility.PRIVATE ->
                        DotNetGenericOwnerPhysicalMemberVisibility.PRIVATE
                    DotNetGenericOwnerPhysicalMethodDefEmissionVisibility.FAMILY_AND_ASSEMBLY ->
                        return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                },
                dispatch = methodHeader.dispatch,
                signature = DotNetGenericOwnerPhysicalMethodSignatureReference(
                    isInstance = methodHeader.isInstance,
                    genericArity = methodHeader.genericArity,
                    resultLayout = resultLayout,
                    parameterSlots = parameterSlots,
                ),
                genericParameters = methodGenericParameters,
            )

            return DotNetGenericOwnerPhysicalBindingResult.Bound(
                DotNetProducerGenericOwnerSemanticEquivalencePhysicalProjection(
                    familyKey = publication.key,
                    typeDefinitions = typeDefinitions,
                    directSupertypeEdgeSets = edgeSets,
                    naturalMethodDefinition = naturalMethodDefinition,
                    typeDefinitionsByRole = identitiesByRole,
                ),
            )
        }
    }
}

/**
 * SEALED declaration authority used by external semantic-equivalence operation routing.
 *
 * This object contains no logical IR declaration and no value fact.  Callers must still prove an
 * exact receiver construction independently before selecting [naturalMethodDefinition].
 */
internal class DotNetProducerGenericOwnerSemanticEquivalencePhysicalAuthority private constructor(
    val declarations: DotNetGenericOwnerPhysicalDeclarationIndex,
    val familyKey: DotNetProducerGenericOwnerSealedFamilyKey,
    val naturalMethodDefinition: DotNetGenericOwnerPhysicalMethodDefIdentity.KotlinProducer,
    typeDefinitionsByRole:
            Map<DotNetProducerGenericOwnerSealedTypeDefRole,
                    DotNetGenericOwnerPhysicalTypeDefIdentity.KotlinProducer>,
) {
    private val typeDefinitionsByRole = typeDefinitionsByRole.toMap()

    val epoch: DotNetGenericOwnerPhysicalAuthorityEpoch
        get() = declarations.epoch

    fun typeDefinition(
        role: DotNetProducerGenericOwnerSealedTypeDefRole,
    ): DotNetGenericOwnerPhysicalTypeDefIdentity.KotlinProducer =
        typeDefinitionsByRole.getValue(role)

    companion object {
        fun bind(
            certificate: DotNetBoundGenericOwnerSemanticEquivalenceCertificate,
        ): DotNetGenericOwnerPhysicalBindingResult<
                DotNetProducerGenericOwnerSemanticEquivalencePhysicalAuthority> {
            val projection = when (
                val result = DotNetProducerGenericOwnerSemanticEquivalencePhysicalProjection.from(
                    certificate,
                )
            ) {
                is DotNetGenericOwnerPhysicalBindingResult.Bound -> result.value
                is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                    return DotNetGenericOwnerPhysicalBindingResult.Conflict(result.reason)
                DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                    return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            }
            val declarations = when (
                val result = DotNetGenericOwnerPhysicalDeclarationIndex
                    .bindProducerSealedSemanticEquivalence(projection)
            ) {
                is DotNetGenericOwnerPhysicalBindingResult.Bound -> result.value
                is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                    return DotNetGenericOwnerPhysicalBindingResult.Conflict(result.reason)
                DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                    return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            }
            if (declarations.epoch !=
                DotNetGenericOwnerPhysicalAuthorityEpoch.SEALED_EMISSION_SIGNATURE_INDEX
            ) {
                return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                    "external semantic-equivalence declarations did not bind at the SEALED epoch",
                )
            }
            return DotNetGenericOwnerPhysicalBindingResult.Bound(
                DotNetProducerGenericOwnerSemanticEquivalencePhysicalAuthority(
                    declarations = declarations,
                    familyKey = projection.familyKey,
                    naturalMethodDefinition = projection.authorizedProducerMethodDefinitions.single(),
                    typeDefinitionsByRole = projection.typeDefinitionsByRole,
                ),
            )
        }
    }
}

private class SealedCarrierTranslator(
    private val typeDefinitions:
            Map<DotNetGenericOwnerPhysicalMethodDefEmissionTypeKey,
                    DotNetGenericOwnerPhysicalTypeDefIdentity.KotlinProducer>,
    private val methodDefinitions:
            Map<DotNetGenericOwnerPhysicalMethodDefEmissionMethodKey,
                    DotNetGenericOwnerPhysicalMethodDefIdentity.KotlinProducer>,
) {
    fun genericParameters(
        rows: List<DotNetGenericOwnerCompleteEmissionGenericParameterRow>,
    ): DotNetGenericOwnerPhysicalBindingResult<
            List<DotNetGenericOwnerPhysicalGenericParameterReference>> {
        val result = mutableListOf<DotNetGenericOwnerPhysicalGenericParameterReference>()
        for (row in rows) {
            val constraints = mutableListOf<DotNetGenericOwnerSymbolicCarrierReference>()
            for (constraint in row.constraints) {
                when (val conversion = carrier(constraint)) {
                    is DotNetGenericOwnerPhysicalBindingResult.Bound -> constraints += conversion.value
                    is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                        return DotNetGenericOwnerPhysicalBindingResult.Conflict(conversion.reason)
                    DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                        return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                }
            }
            result += DotNetGenericOwnerPhysicalGenericParameterReference(
                variance = row.variance,
                constraints = constraints,
            )
        }
        return DotNetGenericOwnerPhysicalBindingResult.Bound(result)
    }

    fun carrier(
        shape: DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape,
    ): DotNetGenericOwnerPhysicalBindingResult<DotNetGenericOwnerSymbolicCarrierReference> =
        when (shape) {
            is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.Leaf ->
                DotNetGenericOwnerPhysicalBindingResult.Bound(
                    DotNetGenericOwnerSymbolicCarrierReference.Leaf(shape.kind),
                )
            is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.OwnerParameter -> {
                val owner = typeDefinitions[shape.binder]
                    ?: return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                        "PE-authenticated J carrier references a TypeDef outside its family",
                    )
                DotNetGenericOwnerPhysicalBindingResult.Bound(
                    DotNetGenericOwnerSymbolicCarrierReference.Parameter
                        .unboundTypeParameterReference(owner, shape.index),
                )
            }
            is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.MethodParameter -> {
                val method = methodDefinitions[shape.binder]
                    ?: return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                        "PE-authenticated natural MethodDef carrier references another MethodDef binder",
                    )
                DotNetGenericOwnerPhysicalBindingResult.Bound(
                    DotNetGenericOwnerSymbolicCarrierReference.Parameter
                        .methodParameterReference(method, shape.index),
                )
            }
            is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.Construction -> {
                val definition = typeDefinitions[shape.definition]
                    ?: return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                        "PE-authenticated J carrier constructs a TypeDef outside its family",
                    )
                val arguments = mutableListOf<DotNetGenericOwnerSymbolicCarrierReference>()
                for (argument in shape.arguments) {
                    when (val conversion = carrier(argument)) {
                        is DotNetGenericOwnerPhysicalBindingResult.Bound -> arguments += conversion.value
                        is DotNetGenericOwnerPhysicalBindingResult.Conflict -> return conversion
                        DotNetGenericOwnerPhysicalBindingResult.Unavailable -> return conversion
                    }
                }
                DotNetGenericOwnerPhysicalBindingResult.Bound(
                    DotNetGenericOwnerSymbolicCarrierReference.Constructed.unboundTypeReference(
                        definition,
                        arguments,
                    ),
                )
            }
            is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.SzArray -> when (
                val element = carrier(shape.element)
            ) {
                is DotNetGenericOwnerPhysicalBindingResult.Bound ->
                    DotNetGenericOwnerPhysicalBindingResult.Bound(
                        DotNetGenericOwnerSymbolicCarrierReference.SzArray(element.value),
                    )
                is DotNetGenericOwnerPhysicalBindingResult.Conflict -> element
                DotNetGenericOwnerPhysicalBindingResult.Unavailable -> element
            }
            is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.ByReference,
            DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.Other,
            -> DotNetGenericOwnerPhysicalBindingResult.Unavailable
        }
}
