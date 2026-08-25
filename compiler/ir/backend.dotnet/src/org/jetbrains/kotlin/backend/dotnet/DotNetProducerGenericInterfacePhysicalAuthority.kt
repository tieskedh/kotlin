/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

/**
 * Producer-recorded physical authority for published Kotlin generic-interface families.
 *
 * The natural interface has one physical identity: the producer's class record already
 * normalizes the canonical and declared views onto
 * [DotNetPhysicalDeclaration.PublishedGenericInterfaceFamily.ownerPath].
 * An exact sibling is a separate identity only when its path was published explicitly. The
 * published family contract deliberately omits canonical-only CLR interfaces, so it cannot
 * publish a complete InterfaceImpl set. No logical IR supertype, partial parent contract, or
 * generated-name convention participates in ancestry binding.
 */
internal class DotNetProducerGenericInterfacePhysicalAuthority private constructor(
    val declarations: DotNetGenericOwnerPhysicalDeclarationIndex,
    naturalTypeDefinitionsByLogicalOwnerKey:
            Map<String, DotNetGenericOwnerPhysicalTypeDefIdentity.KotlinProducer>,
    exactTypeDefinitionsByLogicalOwnerKey:
            Map<String, DotNetGenericOwnerPhysicalTypeDefIdentity.KotlinProducer>,
) {
    val naturalTypeDefinitionsByLogicalOwnerKey:
            Map<String, DotNetGenericOwnerPhysicalTypeDefIdentity.KotlinProducer> =
        naturalTypeDefinitionsByLogicalOwnerKey.toMap()
    val exactTypeDefinitionsByLogicalOwnerKey:
            Map<String, DotNetGenericOwnerPhysicalTypeDefIdentity.KotlinProducer> =
        exactTypeDefinitionsByLogicalOwnerKey.toMap()

    companion object {
        fun bind(
            libraries: List<DotNetExternalLibrary>,
        ): DotNetGenericOwnerPhysicalBindingResult<DotNetProducerGenericInterfacePhysicalAuthority> {
            val producerIndex = try {
                DotNetExternalDeclarationIndex(libraries)
            } catch (failure: IllegalArgumentException) {
                return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                    failure.message?.takeIf(String::isNotEmpty)
                        ?: "invalid producer-recorded generic-interface family",
                )
            }
            val families = producerIndex.publishedGenericInterfaceFamiliesByLogicalKey
                .toSortedMap()
            val naturalDefinitions = families.mapValues { entry ->
                val bound = entry.value
                DotNetGenericOwnerPhysicalTypeDefIdentity.KotlinProducer(
                    bound.library.artifact,
                    bound.family.ownerPath,
                )
            }
            val exactDefinitions = families.mapNotNull { entry ->
                val bound = entry.value
                bound.family.exactOwnerPath?.let { exactOwnerPath ->
                    entry.key to DotNetGenericOwnerPhysicalTypeDefIdentity.KotlinProducer(
                        bound.library.artifact,
                        exactOwnerPath,
                    )
                }
            }.toMap()
            val typeDefinitions = buildList {
                families.forEach { entry ->
                    val genericArity = entry.value.family.contract.genericArity
                    add(
                        DotNetGenericOwnerPhysicalTypeDefReference(
                            identity = naturalDefinitions.getValue(entry.key),
                            genericArity = genericArity,
                            category = DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE,
                        ),
                    )
                    exactDefinitions[entry.key]?.let { exactDefinition ->
                        add(
                            DotNetGenericOwnerPhysicalTypeDefReference(
                                identity = exactDefinition,
                                genericArity = genericArity,
                                category = DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE,
                            ),
                        )
                    }
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

            val boundDeclarations = when (val binding = earlyDeclarations.advance(
                nextEpoch = DotNetGenericOwnerPhysicalAuthorityEpoch.BOUND_DECLARATION_INDEX,
                typeDefinitions = emptyList(),
                methodDefinitions = emptyList(),
                // directParents is a reified-family relation, not a complete InterfaceImpl list.
                // Retain the physical TypeDef identities but leave ancestry unavailable until a
                // complete producer or retained-metadata edge record can be joined.
                directSupertypeEdgeSets = emptyList(),
            )) {
                is DotNetGenericOwnerPhysicalBindingResult.Bound -> binding.value
                is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                    return DotNetGenericOwnerPhysicalBindingResult.Conflict(binding.reason)
                DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                    return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            }
            return DotNetGenericOwnerPhysicalBindingResult.Bound(
                DotNetProducerGenericInterfacePhysicalAuthority(
                    boundDeclarations,
                    naturalDefinitions,
                    exactDefinitions,
                ),
            )
        }
    }
}
