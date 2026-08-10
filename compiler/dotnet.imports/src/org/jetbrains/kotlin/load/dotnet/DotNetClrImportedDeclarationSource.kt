/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.load.dotnet

import org.jetbrains.kotlin.descriptors.SourceFile
import org.jetbrains.kotlin.serialization.deserialization.IncompatibleVersionErrorData
import org.jetbrains.kotlin.serialization.deserialization.descriptors.DeserializedContainerAbiStability
import org.jetbrains.kotlin.serialization.deserialization.descriptors.DeserializedContainerSource
import org.jetbrains.kotlin.serialization.deserialization.descriptors.PreReleaseInfo
import java.util.Collections
import java.util.IdentityHashMap

/**
 * In-process protocol version for exact foreign CLR declaration linkage retained through FIR2IR.
 *
 * This carrier is not serialized into a Kotlin or CLR artifact. The explicit version still makes
 * producer/consumer shape changes exhaustive instead of allowing a backend to reinterpret an
 * unfamiliar carrier from names or tokens.
 */
enum class DotNetClrImportedDeclarationCarrierVersion {
    V3,
}

/** One selected CLR import graph, shared by every declaration carrier produced from it. */
class DotNetClrImportedDeclarationGraph(
    val assemblies: List<DotNetClrClasspathAssembly.WithoutCarrier>,
    val hierarchies: List<DotNetClrResolvedTypeHierarchy>,
    val physicalCoreTypes: DotNetClrPhysicalTypeCoreTypes? = null,
) {
    private val assembliesByMetadata =
        IdentityHashMap<DotNetClrAssemblyMetadata, DotNetClrClasspathAssembly.WithoutCarrier>()
    private val hierarchiesByAssembly =
        IdentityHashMap<DotNetClrAssemblyMetadata, Map<DotNetClrMetadataHandle, DotNetClrResolvedTypeHierarchy>>()

    init {
        require(assemblies.all { assembly ->
            assembliesByMetadata.put(assembly.metadata, assembly) == null
        }) {
            "Imported CLR declaration graph retains one selected assembly more than once"
        }
        require(hierarchies.all { hierarchy ->
            hierarchy.type.type.assembly in assembliesByMetadata
        }) {
            "Imported CLR declaration graph contains a hierarchy outside its selected assemblies"
        }
        require(hierarchies.all { hierarchy ->
            hierarchy.interfaces.all { implementation ->
                hierarchy.type.type.assembly.interfaceImplementations.any { row ->
                    row === implementation.row
                } &&
                        implementation.row.implementingType == hierarchy.type.type.definition.handle &&
                        assembliesByMetadata.containsKey(implementation.interfaceType.type.assembly) &&
                        implementation.interfaceType.type.assembly.typeDefinitions.any { definition ->
                            definition === implementation.interfaceType.type.definition
                        }
            }
        }) {
            "Imported CLR declaration graph contains a detached interface implementation"
        }
        physicalCoreTypes?.let { coreTypes ->
            require(
                listOf(
                    coreTypes.systemValueType,
                    coreTypes.systemEnum,
                    coreTypes.systemNullable,
                ).all { type ->
                    assembliesByMetadata.containsKey(type.assembly) &&
                            type.assembly.typeDefinitions.any { definition -> definition === type.definition }
                }
            ) {
                "Imported CLR declaration graph contains core identities outside its selected assemblies"
            }
        }
        val mutableHierarchies =
            IdentityHashMap<DotNetClrAssemblyMetadata, MutableMap<DotNetClrMetadataHandle, DotNetClrResolvedTypeHierarchy>>()
        require(hierarchies.all { hierarchy ->
            mutableHierarchies.getOrPut(hierarchy.type.type.assembly, ::linkedMapOf)
                .put(hierarchy.type.type.definition.handle, hierarchy) == null
        }) {
            "Imported CLR declaration graph retains one TypeDef hierarchy more than once"
        }
        for (entry in mutableHierarchies.entries) {
            hierarchiesByAssembly[entry.key] = Collections.unmodifiableMap(entry.value)
        }
    }

    fun assemblyOrNull(
        metadata: DotNetClrAssemblyMetadata,
    ): DotNetClrClasspathAssembly.WithoutCarrier? = assembliesByMetadata[metadata]

    fun hierarchyOrNull(
        type: DotNetClrResolvedTypeDefinition,
    ): DotNetClrResolvedTypeHierarchy? =
        hierarchiesByAssembly[type.assembly]?.get(type.definition.handle)
}

sealed class DotNetClrImportedDeclarationSource(
    val assembly: DotNetClrClasspathAssembly.WithoutCarrier,
    val declaringType: DotNetClrTypeDefinition,
    val declaringHierarchy: DotNetClrResolvedTypeHierarchy,
    val graph: DotNetClrImportedDeclarationGraph,
) : DeserializedContainerSource {
    val carrierVersion: DotNetClrImportedDeclarationCarrierVersion =
        DotNetClrImportedDeclarationCarrierVersion.V3

    init {
        require(assembly.metadata.typeDefinitions.any { it === declaringType }) {
            "Imported CLR TypeDef ${declaringType.handle} does not belong to '${assembly.assemblyFile}'"
        }
        require(
            declaringHierarchy.type.type.assembly === assembly.metadata &&
                    declaringHierarchy.type.type.definition === declaringType
        ) {
            "Imported CLR hierarchy does not describe TypeDef ${declaringType.handle} from '${assembly.assemblyFile}'"
        }
        require(graph.assemblyOrNull(assembly.metadata) === assembly) {
            "Imported CLR carrier does not retain its declaring assembly '${assembly.assemblyFile}'"
        }
        require(graph.hierarchyOrNull(declaringHierarchy.type.type) === declaringHierarchy) {
            "Imported CLR carrier does not retain its declaring TypeDef hierarchy"
        }
    }

    override val incompatibility: IncompatibleVersionErrorData<*>?
        get() = null
    override val preReleaseInfo: PreReleaseInfo
        get() = PreReleaseInfo.DEFAULT_VISIBLE
    override val abiStability: DeserializedContainerAbiStability
        get() = DeserializedContainerAbiStability.STABLE

    override fun getContainingFile(): SourceFile = SourceFile.NO_SOURCE_FILE
}

/**
 * Exact physical linkage retained on one FIR function imported from a resource-free CLR DLL.
 *
 * FIR2IR preserves [DeserializedContainerSource] on lazy external functions. Keeping the selected
 * assembly, TypeDef, and MethodDef here prevents codegen from performing a second classpath or
 * display-name lookup after Kotlin type enhancement has produced the logical declaration view.
 */
class DotNetClrImportedMethodSource(
    assembly: DotNetClrClasspathAssembly.WithoutCarrier,
    declaringType: DotNetClrTypeDefinition,
    declaringHierarchy: DotNetClrResolvedTypeHierarchy,
    graph: DotNetClrImportedDeclarationGraph,
    val method: DotNetClrMethodDefinition,
    val resolvedSignature: DotNetClrResolvedMethodSignature,
) : DotNetClrImportedDeclarationSource(
    assembly,
    declaringType,
    declaringHierarchy,
    graph,
) {
    init {
        require(method.declaringType == declaringType.handle) {
            "Imported CLR MethodDef ${method.handle} does not belong to TypeDef ${declaringType.handle}"
        }
        require(assembly.metadata.methodDefinitions.any { it === method }) {
            "Imported CLR MethodDef ${method.handle} does not belong to '${assembly.assemblyFile}'"
        }
        require(resolvedSignature.genericParameterCount == method.signature.genericParameterCount) {
            "Imported CLR MethodDef ${method.handle} has inconsistent resolved generic arity"
        }
    }

    override val presentableString: String =
        "${assembly.identityDisplayName()} TypeDef 0x${declaringType.handle.token.toUInt().toString(16)} " +
                "MethodDef 0x${method.handle.token.toUInt().toString(16)}"
}

/**
 * One physical Property row and its exact MethodSemantics-selected accessors.
 *
 * The same source is retained on the lazy IR property, getter, and optional setter. Codegen uses
 * accessor declaration identity to select [getter] or [setter]; their names are never inferred
 * from [property].
 */
class DotNetClrImportedPropertySource(
    assembly: DotNetClrClasspathAssembly.WithoutCarrier,
    declaringType: DotNetClrTypeDefinition,
    declaringHierarchy: DotNetClrResolvedTypeHierarchy,
    graph: DotNetClrImportedDeclarationGraph,
    val property: DotNetClrPropertyDefinition,
    val getter: DotNetClrMethodDefinition,
    val setter: DotNetClrMethodDefinition?,
    val getterSignature: DotNetClrResolvedMethodSignature,
    val setterSignature: DotNetClrResolvedMethodSignature?,
) : DotNetClrImportedDeclarationSource(
    assembly,
    declaringType,
    declaringHierarchy,
    graph,
) {
    init {
        require(property.declaringType == declaringType.handle) {
            "Imported CLR Property ${property.handle} does not belong to TypeDef ${declaringType.handle}"
        }
        require(getter.declaringType == declaringType.handle) {
            "Imported CLR property getter ${getter.handle} does not belong to TypeDef ${declaringType.handle}"
        }
        require(setter == null || setter.declaringType == declaringType.handle) {
            "Imported CLR property setter ${setter?.handle} does not belong to TypeDef ${declaringType.handle}"
        }
        require(assembly.metadata.propertyDefinitions.any { it === property }) {
            "Imported CLR Property ${property.handle} does not belong to '${assembly.assemblyFile}'"
        }
        require(assembly.metadata.methodDefinitions.any { it === getter }) {
            "Imported CLR property getter ${getter.handle} does not belong to '${assembly.assemblyFile}'"
        }
        require(setter == null || assembly.metadata.methodDefinitions.any { it === setter }) {
            "Imported CLR property setter ${setter?.handle} does not belong to '${assembly.assemblyFile}'"
        }
        require((setter == null) == (setterSignature == null)) {
            "Imported CLR property '${property.name}' has inconsistent resolved setter evidence"
        }
    }

    override val presentableString: String =
        "${assembly.identityDisplayName()} TypeDef 0x${declaringType.handle.token.toUInt().toString(16)} " +
                "Property 0x${property.handle.token.toUInt().toString(16)}"
}

private fun DotNetClrClasspathAssembly.WithoutCarrier.identityDisplayName(): String =
    "${metadata.identity.name}, Version=${metadata.identity.version}, Culture=${metadata.identity.culture}"
