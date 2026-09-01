/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

/**
 * Exact producer declarations which may authenticate CLR variance on a class-category TypeDef.
 *
 * The declaration index is built from decoded physical-library records. A Kotlin logical class,
 * an `Invoke`-shaped member, or an unmarked variant class is never delegate evidence. Current
 * producer GenericParam records publish no CLR constraints, so this bounded authority is
 * deliberately limited to the exact recorded unconstrained variance vector.
 */
internal class DotNetProducerGenericDelegatePhysicalDeclarations private constructor(
    val typeDefinitions: List<DotNetGenericOwnerPhysicalTypeDefReference>,
    val delegateTypeDefinitions: List<DotNetGenericOwnerPhysicalTypeDefIdentity.KotlinProducer>,
    typeDefinitionsByLogicalClassifierKey:
            Map<String, DotNetGenericOwnerPhysicalTypeDefIdentity.KotlinProducer>,
) {
    val typeDefinitionsByLogicalClassifierKey:
            Map<String, DotNetGenericOwnerPhysicalTypeDefIdentity.KotlinProducer> =
        typeDefinitionsByLogicalClassifierKey.toMap()

    companion object {
        fun build(
            libraries: List<DotNetExternalLibrary>,
        ): DotNetGenericOwnerPhysicalBindingResult<DotNetProducerGenericDelegatePhysicalDeclarations> {
            val producerIndex = try {
                DotNetExternalDeclarationIndex(libraries)
            } catch (failure: IllegalArgumentException) {
                return DotNetGenericOwnerPhysicalBindingResult.Conflict(
                    failure.message?.takeIf(String::isNotEmpty)
                        ?: "invalid producer-recorded CLR delegate authority",
                )
            }
            val definitions = mutableListOf<DotNetGenericOwnerPhysicalTypeDefReference>()
            val identities = mutableListOf<DotNetGenericOwnerPhysicalTypeDefIdentity.KotlinProducer>()
            val definitionsByLogicalKey = linkedMapOf<
                    String,
                    DotNetGenericOwnerPhysicalTypeDefIdentity.KotlinProducer,
                    >()

            producerIndex.declarations.toSortedMap().forEach { entry ->
                val declaration = entry.value.declaration as? DotNetPhysicalDeclaration.Class
                    ?: return@forEach
                if (declaration.physicalClassVarianceKind !=
                    DotNetPhysicalClassVarianceKind.SEALED_CLR_DELEGATE
                ) {
                    return@forEach
                }
                val identity = DotNetGenericOwnerPhysicalTypeDefIdentity.KotlinProducer(
                    entry.value.library.artifact,
                    declaration.ownerPath,
                )
                val definition = DotNetGenericOwnerPhysicalTypeDefReference(
                    identity = identity,
                    genericParameters = declaration.physicalTypeParameterVariances.map { variance ->
                        DotNetGenericOwnerPhysicalGenericParameterReference(
                            variance = variance,
                            constraints = emptyList(),
                        )
                    },
                    category = DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS,
                    supportsClrDelegateVariance = true,
                )
                definitions += definition
                identities += identity
                definitionsByLogicalKey[entry.key] = identity
            }
            return DotNetGenericOwnerPhysicalBindingResult.Bound(
                DotNetProducerGenericDelegatePhysicalDeclarations(
                    definitions,
                    identities,
                    definitionsByLogicalKey,
                ),
            )
        }
    }
}

/** Bound physical authority for all sealed CLR delegates recorded by one library set. */
internal class DotNetProducerGenericDelegatePhysicalAuthority private constructor(
    val declarations: DotNetGenericOwnerPhysicalDeclarationIndex,
    typeDefinitionsByLogicalClassifierKey:
            Map<String, DotNetGenericOwnerPhysicalTypeDefIdentity.KotlinProducer>,
) {
    val typeDefinitionsByLogicalClassifierKey:
            Map<String, DotNetGenericOwnerPhysicalTypeDefIdentity.KotlinProducer> =
        typeDefinitionsByLogicalClassifierKey.toMap()

    companion object {
        fun bind(
            libraries: List<DotNetExternalLibrary>,
        ): DotNetGenericOwnerPhysicalBindingResult<DotNetProducerGenericDelegatePhysicalAuthority> {
            val producerDeclarations = when (
                val binding = DotNetProducerGenericDelegatePhysicalDeclarations.build(libraries)
            ) {
                is DotNetGenericOwnerPhysicalBindingResult.Bound -> binding.value
                is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                    return DotNetGenericOwnerPhysicalBindingResult.Conflict(binding.reason)
                DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                    return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            }
            val declarations = when (
                val binding = DotNetGenericOwnerPhysicalDeclarationIndex
                    .bindProducerRecordedDelegates(producerDeclarations)
            ) {
                is DotNetGenericOwnerPhysicalBindingResult.Bound -> binding.value
                is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                    return DotNetGenericOwnerPhysicalBindingResult.Conflict(binding.reason)
                DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                    return DotNetGenericOwnerPhysicalBindingResult.Unavailable
            }
            return DotNetGenericOwnerPhysicalBindingResult.Bound(
                DotNetProducerGenericDelegatePhysicalAuthority(
                    declarations,
                    producerDeclarations.typeDefinitionsByLogicalClassifierKey,
                ),
            )
        }
    }
}
