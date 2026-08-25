/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

private fun DotNetGenericOwnerPhysicalTypeExpressionRecord.isCanonicalCoreObject(): Boolean =
    kind == DotNetGenericOwnerPhysicalTypeKind.NAMED &&
            scope == DotNetGenericOwnerPhysicalTypeScope.CORE_LIBRARY &&
            typePath == listOf("System", "Object") &&
            genericArity == 0 &&
            namedTypeCategory == DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS &&
            arguments.isEmpty()

/** Minimal projection of one artifact owner needed to bind its recorded TypeDef ancestry. */
internal class DotNetGenericOwnerArtifactPhysicalAuthorityOwnerInput(
    physicalOwnerPath: List<String>,
    physicalCapabilityOwnerPath: List<String>?,
    val genericArity: Int,
    directSupertypes: List<DotNetGenericOwnerPhysicalDirectSupertypeRecord>,
) {
    val physicalOwnerPath: List<String> = physicalOwnerPath.toList()
    val physicalCapabilityOwnerPath: List<String>? = physicalCapabilityOwnerPath?.toList()
    val directSupertypes: List<DotNetGenericOwnerPhysicalDirectSupertypeRecord> = directSupertypes.toList()

    init {
        require(physicalOwnerPath.isNotEmpty() && physicalOwnerPath.all(String::isNotEmpty) && genericArity > 0) {
            "a generic-owner artifact authority input requires a physical owner and positive arity"
        }
        require(physicalCapabilityOwnerPath == null ||
                physicalCapabilityOwnerPath.isNotEmpty() && physicalCapabilityOwnerPath.all(String::isNotEmpty)) {
            "a generic-owner artifact authority input has an incomplete capability path"
        }
    }
}

/**
 * Symbolic physical declaration authority projected only from one producer's detached generic-owner artifact.
 *
 * [artifactIdentity] is mandatory because the architecture artifact deliberately has no CLR assembly identity.
 * Producer and core physical paths are the only named-type evidence accepted here. An assembly-scoped type
 * would require retained foreign metadata, while a current-compilation type would require an IR symbol; neither
 * identity is reconstructed from its text path.
 */
internal class DotNetGenericOwnerArtifactPhysicalAuthority private constructor(
    val declarations: DotNetGenericOwnerPhysicalDeclarationIndex,
    producerTypeDefinitionsByPhysicalPath:
            Map<List<String>, DotNetGenericOwnerPhysicalTypeDefIdentity.KotlinProducer>,
) {
    private val producerTypeDefinitionsByPhysicalPath = producerTypeDefinitionsByPhysicalPath
        .mapKeys { entry -> entry.key.toList() }
        .toMap()

    fun producerTypeDefinitionOrNull(
        physicalPath: List<String>,
    ): DotNetGenericOwnerPhysicalTypeDefIdentity.KotlinProducer? =
        producerTypeDefinitionsByPhysicalPath[physicalPath]

    companion object {
        fun bind(
            artifactIdentity: DotNetLibraryArtifact,
            artifact: DotNetGenericOwnerPhysicalFamilyArtifact,
        ): DotNetGenericOwnerPhysicalBindingResult<DotNetGenericOwnerArtifactPhysicalAuthority> = bindRecords(
            artifactIdentity = artifactIdentity,
            owners = artifact.owners.map { owner ->
                DotNetGenericOwnerArtifactPhysicalAuthorityOwnerInput(
                    physicalOwnerPath = owner.physicalOwnerPath,
                    physicalCapabilityOwnerPath = owner.physicalCapabilityOwnerPath,
                    genericArity = owner.genericArity,
                    directSupertypes = owner.directSupertypes,
                )
            },
            interfaceTypes = artifact.interfaceTypes,
        )

        internal fun bindRecords(
            artifactIdentity: DotNetLibraryArtifact,
            owners: List<DotNetGenericOwnerArtifactPhysicalAuthorityOwnerInput>,
            interfaceTypes: List<DotNetGenericOwnerPhysicalInterfaceTypeRecord>,
        ): DotNetGenericOwnerPhysicalBindingResult<DotNetGenericOwnerArtifactPhysicalAuthority> {
            val producerDefinitionsByPath = linkedMapOf<
                    List<String>,
                    DotNetGenericOwnerPhysicalTypeDefIdentity.KotlinProducer,
                    >()
            val typeDefinitions = mutableListOf<DotNetGenericOwnerPhysicalTypeDefReference>()

            fun registerProducerType(
                physicalPath: List<String>,
                genericArity: Int,
                category: DotNetGenericOwnerPhysicalNamedTypeCategory,
            ): DotNetGenericOwnerPhysicalTypeDefIdentity.KotlinProducer {
                val stablePath = physicalPath.toList()
                val identity = producerDefinitionsByPath.getOrPut(stablePath) {
                    DotNetGenericOwnerPhysicalTypeDefIdentity.KotlinProducer(
                        artifactIdentity,
                        stablePath,
                    )
                }
                typeDefinitions += DotNetGenericOwnerPhysicalTypeDefReference(
                    identity = identity,
                    genericArity = genericArity,
                    category = category,
                )
                return identity
            }

            val ownerDefinitions = owners.map { owner ->
                val definition = registerProducerType(
                    owner.physicalOwnerPath,
                    owner.genericArity,
                    DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS,
                )
                owner.physicalCapabilityOwnerPath?.let { capabilityPath ->
                    registerProducerType(
                        capabilityPath,
                        genericArity = 0,
                        category = DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE,
                    )
                }
                owner to definition
            }
            interfaceTypes.forEach { interfaceType ->
                registerProducerType(
                    interfaceType.physicalTypePath,
                    interfaceType.genericArity,
                    DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE,
                )
            }

            val coreDefinitionsByPath = linkedMapOf<
                    List<String>,
                    DotNetGenericOwnerPhysicalTypeDefIdentity.CoreLibrary,
                    >()
            fun collectNamedDefinitions(
                type: DotNetGenericOwnerPhysicalTypeExpressionRecord,
            ): DotNetGenericOwnerPhysicalBindingResult<Unit> {
                if (type.isCanonicalCoreObject()) {
                    return DotNetGenericOwnerPhysicalBindingResult.Bound(Unit)
                }
                when (type.kind) {
                    DotNetGenericOwnerPhysicalTypeKind.METHOD_TYPE_PARAMETER ->
                        return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                    DotNetGenericOwnerPhysicalTypeKind.NAMED -> {
                        // The artifact does not record whether a named value type is a nullable
                        // inline carrier. Binding it as either null-capable or non-null would add
                        // physical truth absent from the producer schema.
                        if (type.namedTypeCategory == DotNetGenericOwnerPhysicalNamedTypeCategory.VALUE_TYPE) {
                            return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                        }
                        when (type.scope) {
                            DotNetGenericOwnerPhysicalTypeScope.PRODUCER -> {
                                val identity = producerDefinitionsByPath[type.typePath]
                                    ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                                typeDefinitions += DotNetGenericOwnerPhysicalTypeDefReference(
                                    identity = identity,
                                    genericArity = type.genericArity,
                                    category = checkNotNull(type.namedTypeCategory),
                                )
                            }
                            DotNetGenericOwnerPhysicalTypeScope.CORE_LIBRARY -> {
                                val stablePath = type.typePath.toList()
                                val identity = coreDefinitionsByPath.getOrPut(stablePath) {
                                    DotNetGenericOwnerPhysicalTypeDefIdentity.CoreLibrary(stablePath)
                                }
                                typeDefinitions += DotNetGenericOwnerPhysicalTypeDefReference(
                                    identity = identity,
                                    genericArity = type.genericArity,
                                    category = checkNotNull(type.namedTypeCategory),
                                )
                            }
                            DotNetGenericOwnerPhysicalTypeScope.CURRENT_COMPILATION,
                            DotNetGenericOwnerPhysicalTypeScope.ASSEMBLY,
                            null,
                            -> return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                        }
                    }
                    DotNetGenericOwnerPhysicalTypeKind.VOID,
                    DotNetGenericOwnerPhysicalTypeKind.BOOLEAN,
                    DotNetGenericOwnerPhysicalTypeKind.INT32,
                    DotNetGenericOwnerPhysicalTypeKind.STRING,
                    DotNetGenericOwnerPhysicalTypeKind.OBJECT,
                    DotNetGenericOwnerPhysicalTypeKind.OWNER_TYPE_PARAMETER,
                    DotNetGenericOwnerPhysicalTypeKind.SZ_ARRAY,
                    -> Unit
                }
                for (argument in type.arguments) {
                    when (val collection = collectNamedDefinitions(argument)) {
                        is DotNetGenericOwnerPhysicalBindingResult.Bound -> Unit
                        is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                            return DotNetGenericOwnerPhysicalBindingResult.Conflict(collection.reason)
                        DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                            return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                    }
                }
                return DotNetGenericOwnerPhysicalBindingResult.Bound(Unit)
            }
            for (supertype in owners.flatMap { owner -> owner.directSupertypes }) {
                when (val collection = collectNamedDefinitions(supertype.physicalType)) {
                    is DotNetGenericOwnerPhysicalBindingResult.Bound -> Unit
                    is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                        return DotNetGenericOwnerPhysicalBindingResult.Conflict(collection.reason)
                    DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                        return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                }
            }

            val earlyDeclarations = when (val binding = DotNetGenericOwnerPhysicalDeclarationIndex.bind(
                epoch = DotNetGenericOwnerPhysicalAuthorityEpoch.EARLY_REPRESENTATION_PLAN,
                typeDefinitions = typeDefinitions,
                methodDefinitions = emptyList(),
            )) {
                is DotNetGenericOwnerPhysicalBindingResult.Bound -> binding.value
                is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                    return DotNetGenericOwnerPhysicalBindingResult.Conflict(binding.reason)
                DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                    return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            }

            fun translateType(
                type: DotNetGenericOwnerPhysicalTypeExpressionRecord,
                sourceDefinition: DotNetGenericOwnerPhysicalTypeDefIdentity.KotlinProducer,
            ): DotNetGenericOwnerPhysicalBindingResult<DotNetGenericOwnerSymbolicCarrierReference> {
                if (type.isCanonicalCoreObject()) {
                    return DotNetGenericOwnerPhysicalBindingResult.Bound(
                        DotNetGenericOwnerSymbolicCarrierReference.objectCarrier(),
                    )
                }
                return when (type.kind) {
                DotNetGenericOwnerPhysicalTypeKind.VOID -> DotNetGenericOwnerPhysicalBindingResult.Bound(
                    DotNetGenericOwnerSymbolicCarrierReference.voidCarrier(),
                )
                DotNetGenericOwnerPhysicalTypeKind.BOOLEAN -> DotNetGenericOwnerPhysicalBindingResult.Bound(
                    DotNetGenericOwnerSymbolicCarrierReference.booleanCarrier(),
                )
                DotNetGenericOwnerPhysicalTypeKind.INT32 -> DotNetGenericOwnerPhysicalBindingResult.Bound(
                    DotNetGenericOwnerSymbolicCarrierReference.int32Carrier(),
                )
                DotNetGenericOwnerPhysicalTypeKind.STRING -> DotNetGenericOwnerPhysicalBindingResult.Bound(
                    DotNetGenericOwnerSymbolicCarrierReference.stringCarrier(),
                )
                DotNetGenericOwnerPhysicalTypeKind.OBJECT -> DotNetGenericOwnerPhysicalBindingResult.Bound(
                    DotNetGenericOwnerSymbolicCarrierReference.objectCarrier(),
                )
                DotNetGenericOwnerPhysicalTypeKind.OWNER_TYPE_PARAMETER ->
                    earlyDeclarations.typeParameterOrError(sourceDefinition, checkNotNull(type.parameterIndex))
                DotNetGenericOwnerPhysicalTypeKind.METHOD_TYPE_PARAMETER ->
                    DotNetGenericOwnerPhysicalBindingResult.Unavailable
                DotNetGenericOwnerPhysicalTypeKind.NAMED -> {
                    if (type.namedTypeCategory == DotNetGenericOwnerPhysicalNamedTypeCategory.VALUE_TYPE) {
                        return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                    }
                    val definition = when (type.scope) {
                        DotNetGenericOwnerPhysicalTypeScope.PRODUCER ->
                            producerDefinitionsByPath[type.typePath]
                        DotNetGenericOwnerPhysicalTypeScope.CORE_LIBRARY ->
                            coreDefinitionsByPath[type.typePath]
                        DotNetGenericOwnerPhysicalTypeScope.CURRENT_COMPILATION,
                        DotNetGenericOwnerPhysicalTypeScope.ASSEMBLY,
                        null,
                        -> null
                    } ?: return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                    val arguments = mutableListOf<DotNetGenericOwnerSymbolicCarrierReference>()
                    for (argument in type.arguments) {
                        when (val translation = translateType(argument, sourceDefinition)) {
                            is DotNetGenericOwnerPhysicalBindingResult.Bound -> arguments += translation.value
                            is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                                return DotNetGenericOwnerPhysicalBindingResult.Conflict(translation.reason)
                            DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                                return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                        }
                    }
                    earlyDeclarations.constructTypeOrError(definition, arguments)
                }
                DotNetGenericOwnerPhysicalTypeKind.SZ_ARRAY -> when (val element = translateType(
                    type.arguments.single(),
                    sourceDefinition,
                )) {
                    is DotNetGenericOwnerPhysicalBindingResult.Bound ->
                        DotNetGenericOwnerPhysicalBindingResult.Bound(
                            DotNetGenericOwnerSymbolicCarrierReference.SzArray(element.value),
                        )
                    is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                        DotNetGenericOwnerPhysicalBindingResult.Conflict(element.reason)
                    DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                        DotNetGenericOwnerPhysicalBindingResult.Unavailable
                }
            }
            }

            val edgeSets = mutableListOf<DotNetGenericOwnerPhysicalDirectSupertypeEdgeSet>()
            for (ownerDefinition in ownerDefinitions) {
                val owner = ownerDefinition.first
                val sourceDefinition = ownerDefinition.second
                val edges = mutableListOf<DotNetGenericOwnerPhysicalDirectSupertypeEdgeReference>()
                for (supertype in owner.directSupertypes) {
                    when (val target = translateType(supertype.physicalType, sourceDefinition)) {
                        is DotNetGenericOwnerPhysicalBindingResult.Bound -> edges +=
                            DotNetGenericOwnerPhysicalDirectSupertypeEdgeReference(
                                kind = supertype.kind,
                                target = target.value,
                            )
                        is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                            return DotNetGenericOwnerPhysicalBindingResult.Conflict(target.reason)
                        DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                            return DotNetGenericOwnerPhysicalBindingResult.Unavailable
                    }
                }
                edgeSets += DotNetGenericOwnerPhysicalDirectSupertypeEdgeSet(sourceDefinition, edges)
            }

            val boundDeclarations = when (val binding = earlyDeclarations.advance(
                nextEpoch = DotNetGenericOwnerPhysicalAuthorityEpoch.BOUND_DECLARATION_INDEX,
                typeDefinitions = emptyList(),
                methodDefinitions = emptyList(),
                directSupertypeEdgeSets = edgeSets,
            )) {
                is DotNetGenericOwnerPhysicalBindingResult.Bound -> binding.value
                is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                    return DotNetGenericOwnerPhysicalBindingResult.Conflict(binding.reason)
                DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                    return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            }
            return DotNetGenericOwnerPhysicalBindingResult.Bound(
                DotNetGenericOwnerArtifactPhysicalAuthority(
                    boundDeclarations,
                    producerDefinitionsByPath,
                ),
            )
        }
    }
}
