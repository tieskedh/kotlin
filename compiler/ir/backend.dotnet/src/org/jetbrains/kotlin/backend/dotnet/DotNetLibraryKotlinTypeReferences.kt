/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.ir.util.IdSignature
import org.jetbrains.kotlin.ir.util.IdSignatureRenderer
import org.jetbrains.kotlin.library.KotlinLibrary
import org.jetbrains.kotlin.library.components.metadata
import org.jetbrains.kotlin.library.metadata.parseModuleHeader
import org.jetbrains.kotlin.library.metadata.parsePackageFragment
import org.jetbrains.kotlin.load.dotnet.DotNetClrClasspathAssembly
import org.jetbrains.kotlin.load.dotnet.DotNetClrGenericParameterVariance
import org.jetbrains.kotlin.load.dotnet.DotNetClrKotlinTypeReference
import org.jetbrains.kotlin.load.dotnet.DotNetClrMetadataReader
import org.jetbrains.kotlin.load.dotnet.DotNetClrTypeVisibility
import org.jetbrains.kotlin.metadata.ProtoBuf
import org.jetbrains.kotlin.metadata.deserialization.Flags
import org.jetbrains.kotlin.metadata.deserialization.NameResolverImpl
import org.jetbrains.kotlin.metadata.deserialization.TypeTable
import org.jetbrains.kotlin.metadata.deserialization.upperBounds
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.StandardClassIds
import org.jetbrains.kotlin.serialization.deserialization.getClassId

class DotNetKotlinLibraryReferenceIndex(
    val classifierIds: Set<ClassId>,
    val physicalReferences: List<DotNetClrKotlinTypeReference>,
)

/** Authenticates the bounded Kotlin-owned type-reference slice against KLIB, its C record and PE. */
fun bindDotNetKotlinTypeReferences(
    library: KotlinLibrary,
    external: DotNetExternalLibrary,
    assembly: DotNetClrClasspathAssembly.WithCarrier,
    bindPhysicalReferences: Boolean = true,
): DotNetKotlinLibraryReferenceIndex {
    require(library.path.toFile().canonicalFile == assembly.assemblyFile.canonicalFile &&
            external.assemblyFile.canonicalFile == assembly.assemblyFile.canonicalFile) {
        "Kotlin CLR reference binding mixes selected library identities"
    }
    val metadata = if (bindPhysicalReferences) DotNetClrMetadataReader.read(assembly.assemblyFile) else null
    val definitionsByName = metadata?.typeDefinitions?.filter { it.declaringType == null }?.groupBy {
        if (it.namespaceName.isEmpty()) it.metadataName else "${it.namespaceName}.${it.metadataName}"
    }.orEmpty()
    val parametersByOwner = metadata?.genericParameterDefinitions?.groupBy { it.owner }.orEmpty()
    val ownersWithInterfaceEdges = metadata?.interfaceImplementations?.mapTo(hashSetOf()) { it.implementingType }.orEmpty()
    val component = library.metadata
    val references = mutableListOf<DotNetClrKotlinTypeReference>()
    val classifierIds = linkedSetOf<ClassId>()
    for (packageName in parseModuleHeader(component.moduleHeaderData).packageFragmentNameList) {
        for (part in component.getPackageFragmentNames(packageName)) {
            val fragment = parsePackageFragment(component.getPackageFragment(packageName, part))
            val names = NameResolverImpl(fragment.strings, fragment.qualifiedNames)
            for (alias in fragment.`package`.typeAliasList) {
                classifierIds += ClassId(FqName(packageName), Name.identifier(names.getString(alias.name)))
            }
            for (logicalClass in fragment.class_List) {
                val classId = names.getClassId(logicalClass.fqName)
                if (names.isLocalClassName(logicalClass.fqName)) continue
                classifierIds += classId
                for (alias in logicalClass.typeAliasList) {
                    classifierIds += classId.createNestedClassId(Name.identifier(names.getString(alias.name)))
                }
                if (metadata == null || classId.isNestedClass ||
                    Flags.CLASS_KIND.get(logicalClass.flags) != ProtoBuf.Class.Kind.INTERFACE ||
                    Flags.VISIBILITY.get(logicalClass.flags) != ProtoBuf.Visibility.PUBLIC ||
                    Flags.IS_EXPECT_CLASS.get(logicalClass.flags)
                ) continue
                // Foreign CLR arguments cannot silently bypass Kotlin-only bounds. The first
                // projection admits only the shared default nullable-Any bound; other bounds
                // need a separately proved Kotlin/physical argument contract.
                val typeTable = TypeTable(logicalClass.typeTable)
                if (logicalClass.typeParameterList.any { parameter ->
                        parameter.upperBounds(typeTable).any { bound ->
                            !bound.hasClassName() || names.getClassId(bound.className) != StandardClassIds.Any ||
                                    !bound.nullable || bound.argumentCount != 0
                        }
                    }) continue
                // Shared public signatures give an ordinary non-expect, non-local class no
                // member hash or flags. Do not parse the physical spelling into a Kotlin name.
                val signature = IdSignature.CommonSignature(
                    classId.packageFqName.asString(), classId.relativeClassName.asString(),
                    id = null, mask = 0, description = null,
                )
                val key = "C:${IdSignatureRenderer.LEGACY.render(signature)}"
                val record = external.declarations[key] as? DotNetPhysicalDeclaration.Class ?: continue
                val physicalName = record.ownerPath.singleOrNull() ?: continue
                val definition = requireNotNull(definitionsByName[physicalName]?.singleOrNull()) {
                    "Kotlin classifier '$key' has no unique producer-recorded TypeDef"
                }
                require(definition.isInterface && definition.visibility == DotNetClrTypeVisibility.PUBLIC) {
                    "Kotlin interface '$key' disagrees with its producer TypeDef"
                }
                if (definition.handle in ownersWithInterfaceEdges) continue
                val parameters = parametersByOwner[definition.handle].orEmpty().sortedBy { it.number }
                require(parameters.map { it.number } == (0 until record.physicalTypeParameterCount).toList()) {
                    "Kotlin classifier '$key' disagrees with its producer GenericParams"
                }
                val variances = parameters.map { parameter ->
                    when (parameter.variance) {
                        DotNetClrGenericParameterVariance.INVARIANT -> DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT
                        DotNetClrGenericParameterVariance.COVARIANT -> DotNetGenericOwnerPhysicalTypeParameterVariance.COVARIANT
                        DotNetClrGenericParameterVariance.CONTRAVARIANT -> DotNetGenericOwnerPhysicalTypeParameterVariance.CONTRAVARIANT
                    }
                }
                require(variances == record.physicalTypeParameterVariances) {
                    "Kotlin classifier '$key' disagrees with its producer variance"
                }
                references += DotNetClrKotlinTypeReference(
                    assembly, metadata, definition, classId, key,
                    logicalClass.typeParameterCount, record.physicalTypeParameterCount,
                )
            }
        }
    }
    require(references.map { it.definition.handle }.distinct().size == references.size &&
            references.map { it.logicalClassId }.distinct().size == references.size) {
        "Kotlin CLR reference binding has duplicate physical or logical classifiers"
    }
    return DotNetKotlinLibraryReferenceIndex(classifierIds, references)
}
