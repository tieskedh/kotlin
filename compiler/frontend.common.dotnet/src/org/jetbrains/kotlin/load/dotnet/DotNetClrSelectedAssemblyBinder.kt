/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.load.dotnet

/**
 * Resolves references only inside one caller-selected CLR assembly graph.
 *
 * Selection policy remains outside the objective metadata layer. Once a caller supplies the
 * selected graph, FIR import, custom-attribute decoding, and platform-artifact validation must
 * use the same exact identity rules instead of maintaining private binders.
 */
class DotNetClrSelectedAssemblyBinder(
    assemblies: List<DotNetClrAssemblyMetadata>,
) : DotNetClrAssemblyReferenceBinder {
    private val assembliesByName =
        assemblies.groupBy { assembly -> assembly.identity.name.lowercase() }

    override fun bind(
        sourceAssembly: DotNetClrAssemblyMetadata,
        reference: DotNetClrAssemblyReference,
    ): DotNetClrAssemblyMetadata? {
        val candidates = assembliesByName[reference.name.lowercase()].orEmpty()
            .filter { assembly ->
                assembly.identity.version == reference.version &&
                        assembly.identity.culture == reference.culture &&
                        reference.publicKeyOrToken.matches(assembly, reference)
            }
        return candidates.singleOrNull()
    }

    fun bind(name: DotNetClrSerializedAssemblyName): DotNetClrAssemblyMetadata? {
        val candidates = assembliesByName[name.name.lowercase()].orEmpty()
            .filter { assembly ->
                name.version?.components?.joinToString(".")?.let { version ->
                    assembly.identity.version == version
                } != false &&
                        name.cultureName?.let { culture ->
                            assembly.identity.culture == culture.ifEmpty { "neutral" }
                        } != false &&
                        name.publicKeyOrToken?.let { key ->
                            if (name.hasPublicKey) {
                                assembly.identity.publicKey == key
                            } else {
                                assembly.identity.publicKeyToken == key
                            }
                        } != false
            }
        return candidates.singleOrNull()
    }

    private fun List<Int>.matches(
        assembly: DotNetClrAssemblyMetadata,
        reference: DotNetClrAssemblyReference,
    ): Boolean {
        if (isEmpty()) {
            return assembly.identity.publicKey.isEmpty() &&
                    assembly.identity.publicKeyToken.isEmpty()
        }
        val hasFullPublicKey = reference.flags and 0x0001L != 0L
        return if (hasFullPublicKey) {
            assembly.identity.publicKey == this
        } else {
            assembly.identity.publicKeyToken == this
        }
    }
}

fun resolveDotNetClrCustomAttributeCoreTypes(
    assemblies: List<DotNetClrAssemblyMetadata>,
    resolver: DotNetClrTypeResolver,
): DotNetClrCustomAttributeCoreTypes? {
    val systemAttribute = resolveDotNetClrSystemType(assemblies, resolver, "System", "Attribute")
        ?: return null
    val systemEnum = resolveDotNetClrSystemType(assemblies, resolver, "System", "Enum")
        ?: return null
    val systemType = resolveDotNetClrSystemType(assemblies, resolver, "System", "Type")
        ?: return null
    return DotNetClrCustomAttributeCoreTypes(systemAttribute, systemEnum, systemType)
}

fun resolveDotNetClrSystemType(
    assemblies: List<DotNetClrAssemblyMetadata>,
    resolver: DotNetClrTypeResolver,
    namespaceName: String,
    metadataName: String,
): DotNetClrResolvedTypeDefinition? {
    val matches = assemblies.mapNotNull { assembly ->
        when (
            val resolution = resolver.resolveTopLevelType(
                assembly,
                namespaceName,
                metadataName,
            )
        ) {
            is DotNetClrTypeResolution.Resolved -> resolution.type
            is DotNetClrTypeResolution.Unresolved -> null
        }
    }.distinct()
    return matches.singleOrNull()
}
