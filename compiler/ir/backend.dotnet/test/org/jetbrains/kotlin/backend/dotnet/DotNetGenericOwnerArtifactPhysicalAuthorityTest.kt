/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DotNetGenericOwnerArtifactPhysicalAuthorityTest {
    private val artifactIdentity = DotNetLibraryArtifact("sample.OwnerLibrary", "netstandard2.0")

    @Test
    fun bindsRecordedOwnerInterfaceCapabilityAndCoreEdges() {
        val ownerPath = listOf("sample.Owner`1")
        val interfacePath = listOf("sample.Source`1")
        val capabilityPath = listOf("sample.OwnerKotlinSemantic")
        val objectPath = listOf("System", "Object")
        val interfaceType = DotNetGenericOwnerPhysicalInterfaceTypeRecord(
            logicalInterfaceKey = "C:sample/Source",
            physicalTypePath = interfacePath,
            physicalVisibility = DotNetGenericOwnerPhysicalTypeVisibility.PUBLIC,
            genericArity = 1,
            physicalGenericParameters = listOf(
                DotNetGenericOwnerPhysicalGenericParameterRecord(0, emptySet(), emptyList()),
            ),
        )
        val owner = DotNetGenericOwnerArtifactPhysicalAuthorityOwnerInput(
            physicalOwnerPath = ownerPath,
            physicalCapabilityOwnerPath = capabilityPath,
            genericArity = 1,
            directSupertypes = listOf(
                directSupertype(
                    kind = DotNetGenericOwnerDirectSupertypeKind.BASE_CLASS,
                    logicalClassifierKey = null,
                    physicalType = DotNetGenericOwnerPhysicalTypeExpressionRecord.coreType(
                        objectPath,
                        DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS,
                    ),
                ),
                directSupertype(
                    kind = DotNetGenericOwnerDirectSupertypeKind.INTERFACE,
                    logicalClassifierKey = interfaceType.logicalInterfaceKey,
                    physicalType = DotNetGenericOwnerPhysicalTypeExpressionRecord.producerType(
                        interfacePath,
                        DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE,
                        listOf(DotNetGenericOwnerPhysicalTypeExpressionRecord.ownerParameter(0)),
                    ),
                ),
                directSupertype(
                    kind = DotNetGenericOwnerDirectSupertypeKind.INTERFACE,
                    logicalClassifierKey = null,
                    isSemanticCapability = true,
                    physicalType = DotNetGenericOwnerPhysicalTypeExpressionRecord.producerType(
                        capabilityPath,
                        DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE,
                    ),
                ),
            ),
        )

        val authority = DotNetGenericOwnerArtifactPhysicalAuthority.bindRecords(
            artifactIdentity,
            listOf(owner),
            listOf(interfaceType),
        ).boundValue()
        val declarations = authority.declarations
        assertEquals(
            DotNetGenericOwnerPhysicalAuthorityEpoch.BOUND_DECLARATION_INDEX,
            declarations.epoch,
        )
        val ownerDefinition = checkNotNull(authority.producerTypeDefinitionOrNull(ownerPath))
        val interfaceDefinition = checkNotNull(authority.producerTypeDefinitionOrNull(interfacePath))
        val capabilityDefinition = checkNotNull(authority.producerTypeDefinitionOrNull(capabilityPath))
        val edges = declarations.directSupertypeEdgesOrUnavailable(ownerDefinition).boundValue()
        assertEquals(3, edges.size)
        assertEquals(
            setOf(
                DotNetGenericOwnerDirectSupertypeKind.BASE_CLASS,
                DotNetGenericOwnerDirectSupertypeKind.INTERFACE,
            ),
            edges.mapTo(linkedSetOf()) { edge -> edge.kind },
        )

        val interfaceTarget = edges.map { edge -> edge.target }
            .filterIsInstance<DotNetGenericOwnerSymbolicCarrierReference.Constructed>()
            .single { target -> target.definition == interfaceDefinition }
        assertEquals(
            declarations.typeParameterOrError(ownerDefinition, 0).boundValue(),
            interfaceTarget.arguments.single(),
        )
        assertEquals(
            DotNetGenericOwnerSymbolicCarrierReference.objectCarrier(),
            edges.single { edge -> edge.kind == DotNetGenericOwnerDirectSupertypeKind.BASE_CLASS }.target,
        )

        val ownerOfInt = declarations.constructTypeOrError(
            ownerDefinition,
            listOf(DotNetGenericOwnerSymbolicCarrierReference.int32Carrier()),
        ).boundValue()
        val sourceOfInt = declarations.constructTypeOrError(
            interfaceDefinition,
            listOf(DotNetGenericOwnerSymbolicCarrierReference.int32Carrier()),
        ).boundValue()
        val capability = declarations.constructTypeOrError(capabilityDefinition, emptyList()).boundValue()
        val closure = declarations.physicalInterfaceViewClosureOrError(ownerOfInt).boundValue()
        assertTrue(DotNetGenericOwnerPhysicalView(sourceOfInt) in closure.interfaceViews)
        assertTrue(DotNetGenericOwnerPhysicalView(capability) in closure.interfaceViews)
        assertFalse(closure.isComplete)
    }

    @Test
    fun doesNotInventInlineNullAuthorityForNamedValueTypeArguments() {
        val interfacePath = listOf("sample.Source`1")
        val interfaceType = DotNetGenericOwnerPhysicalInterfaceTypeRecord(
            logicalInterfaceKey = "C:sample/Source",
            physicalTypePath = interfacePath,
            physicalVisibility = DotNetGenericOwnerPhysicalTypeVisibility.PUBLIC,
            genericArity = 1,
            physicalGenericParameters = listOf(
                DotNetGenericOwnerPhysicalGenericParameterRecord(0, emptySet(), emptyList()),
            ),
        )
        val nullableValueType = DotNetGenericOwnerPhysicalTypeExpressionRecord.coreType(
            typePath = listOf("System", "Nullable`1"),
            category = DotNetGenericOwnerPhysicalNamedTypeCategory.VALUE_TYPE,
            arguments = listOf(DotNetGenericOwnerPhysicalTypeExpressionRecord.int32Type()),
        )
        val owner = DotNetGenericOwnerArtifactPhysicalAuthorityOwnerInput(
            physicalOwnerPath = listOf("sample.Owner`1"),
            physicalCapabilityOwnerPath = null,
            genericArity = 1,
            directSupertypes = listOf(
                directSupertype(
                    kind = DotNetGenericOwnerDirectSupertypeKind.INTERFACE,
                    logicalClassifierKey = interfaceType.logicalInterfaceKey,
                    physicalType = DotNetGenericOwnerPhysicalTypeExpressionRecord.producerType(
                        interfacePath,
                        DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE,
                        listOf(nullableValueType),
                    ),
                ),
            ),
        )

        assertIs<DotNetGenericOwnerPhysicalBindingResult.Unavailable>(
            DotNetGenericOwnerArtifactPhysicalAuthority.bindRecords(
                artifactIdentity,
                listOf(owner),
                listOf(interfaceType),
            ),
        )
    }

    @Test
    fun doesNotInventForeignIdentityFromAssemblyAndTextPath() {
        val assemblyInterface = DotNetGenericOwnerPhysicalTypeExpressionRecord(
            kind = DotNetGenericOwnerPhysicalTypeKind.NAMED,
            scope = DotNetGenericOwnerPhysicalTypeScope.ASSEMBLY,
            assemblyName = "Foreign.Library",
            typePath = listOf("foreign.Source`1"),
            genericArity = 1,
            namedTypeCategory = DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE,
            arguments = listOf(DotNetGenericOwnerPhysicalTypeExpressionRecord.ownerParameter(0)),
        )
        val owner = DotNetGenericOwnerArtifactPhysicalAuthorityOwnerInput(
            physicalOwnerPath = listOf("sample.Owner`1"),
            physicalCapabilityOwnerPath = null,
            genericArity = 1,
            directSupertypes = listOf(
                directSupertype(
                    kind = DotNetGenericOwnerDirectSupertypeKind.INTERFACE,
                    logicalClassifierKey = "C:foreign/Source",
                    physicalType = assemblyInterface,
                ),
            ),
        )

        assertIs<DotNetGenericOwnerPhysicalBindingResult.Unavailable>(
            DotNetGenericOwnerArtifactPhysicalAuthority.bindRecords(
                artifactIdentity,
                listOf(owner),
                emptyList(),
            ),
        )
    }

    private fun directSupertype(
        kind: DotNetGenericOwnerDirectSupertypeKind,
        logicalClassifierKey: String?,
        physicalType: DotNetGenericOwnerPhysicalTypeExpressionRecord,
        isSemanticCapability: Boolean = false,
    ) = DotNetGenericOwnerPhysicalDirectSupertypeRecord(
        kind = kind,
        logicalClassifierKey = logicalClassifierKey,
        isSemanticCapability = isSemanticCapability,
        physicalType = physicalType,
        nullableReferenceFlags = List(physicalType.nullableReferenceTransformCountForTest()) {
            DotNetNullableReferenceFlag.OBLIVIOUS
        },
    )

    private fun DotNetGenericOwnerPhysicalTypeExpressionRecord.nullableReferenceTransformCountForTest(): Int =
        when (kind) {
            DotNetGenericOwnerPhysicalTypeKind.VOID,
            DotNetGenericOwnerPhysicalTypeKind.BOOLEAN,
            DotNetGenericOwnerPhysicalTypeKind.INT32,
            -> 0
            DotNetGenericOwnerPhysicalTypeKind.STRING,
            DotNetGenericOwnerPhysicalTypeKind.OBJECT,
            DotNetGenericOwnerPhysicalTypeKind.OWNER_TYPE_PARAMETER,
            DotNetGenericOwnerPhysicalTypeKind.METHOD_TYPE_PARAMETER,
            -> 1
            DotNetGenericOwnerPhysicalTypeKind.NAMED,
            DotNetGenericOwnerPhysicalTypeKind.SZ_ARRAY,
            -> 1 + arguments.sumOf { argument -> argument.nullableReferenceTransformCountForTest() }
        }

    private fun <T> DotNetGenericOwnerPhysicalBindingResult<T>.boundValue(): T = when (this) {
        is DotNetGenericOwnerPhysicalBindingResult.Bound -> value
        is DotNetGenericOwnerPhysicalBindingResult.Conflict -> error(reason)
        DotNetGenericOwnerPhysicalBindingResult.Unavailable -> error("physical authority was unavailable")
    }
}
