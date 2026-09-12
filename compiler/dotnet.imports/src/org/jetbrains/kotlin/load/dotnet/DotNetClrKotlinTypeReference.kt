/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.load.dotnet

import org.jetbrains.kotlin.name.ClassId

/**
 * A producer-validated Kotlin classifier referenced by a foreign CLR signature.
 *
 * This is transport, not foreign declaration authority: the classifier still comes from KLIB.
 * Its producer joins the logical signature, physical class record and containing PE before
 * creating this reference. Consumers retain the actual row, never reconstruct it from ClassId.
 */
class DotNetClrKotlinTypeReference(
    val assembly: DotNetClrClasspathAssembly.WithCarrier,
    val metadata: DotNetClrAssemblyMetadata,
    val definition: DotNetClrTypeDefinition,
    val logicalClassId: ClassId,
    val logicalClassifierKey: String,
    val logicalTypeParameterCount: Int,
    val physicalTypeParameterCount: Int,
) {
    init {
        require(metadata.typeDefinitions.any { it === definition }) {
            "Kotlin CLR reference carries a detached TypeDef"
        }
        val resourceIdentity = assembly.carrierResource.assemblyIdentity
        require(metadata.identity == resourceIdentity && !metadata.identity.hasPublicKey) {
            "Kotlin CLR reference disagrees with its containing metadata resource"
        }
        require(logicalClassifierKey.isNotEmpty() && logicalTypeParameterCount >= 0 &&
                (physicalTypeParameterCount == 0 || physicalTypeParameterCount == logicalTypeParameterCount)) {
            "Kotlin CLR reference has an unsupported logical/physical arity relation"
        }
        val parameters = metadata.genericParameterDefinitions.filter { it.owner == definition.handle }
        require(parameters.map { it.number }.sorted() == (0 until physicalTypeParameterCount).toList()) {
            "Kotlin CLR reference disagrees with its actual GenericParams"
        }
    }

    fun refersTo(type: DotNetClrResolvedTypeDefinition): Boolean =
        type.assembly === metadata && type.definition === definition
}
