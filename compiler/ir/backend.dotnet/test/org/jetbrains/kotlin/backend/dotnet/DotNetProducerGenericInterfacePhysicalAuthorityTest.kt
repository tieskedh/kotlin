/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DotNetProducerGenericInterfacePhysicalAuthorityTest {
    private val artifact = DotNetLibraryArtifact("sample.Library", "netstandard2.0")

    @Test
    fun bindsNaturalIdentitiesWithoutTreatingPartialFamilyParentsAsCompleteEdges() {
        val root = FamilySpec(
            logicalOwnerKey = "C:sample/Root",
            ownerPath = listOf("sample.Root`1"),
            capabilityOwnerPath = listOf("sample.RootKotlinSemantic"),
        )
        val derived = FamilySpec(
            logicalOwnerKey = "C:sample/Derived",
            ownerPath = listOf("sample.Derived`1"),
            capabilityOwnerPath = listOf("sample.DerivedKotlinSemantic"),
            parentLogicalOwnerKeys = listOf(root.logicalOwnerKey),
            rootLogicalOwnerKeys = listOf(root.logicalOwnerKey),
            lineageDepth = 1,
        )
        val authority = bindAuthority(root, derived)
        val declarations = authority.declarations
        assertEquals(
            DotNetGenericOwnerPhysicalAuthorityEpoch.BOUND_DECLARATION_INDEX,
            declarations.epoch,
        )

        val rootDefinition = authority.naturalTypeDefinitionsByLogicalOwnerKey.getValue(root.logicalOwnerKey)
        val derivedDefinition = authority.naturalTypeDefinitionsByLogicalOwnerKey.getValue(derived.logicalOwnerKey)
        assertSame(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            declarations.directSupertypeEdgesOrUnavailable(rootDefinition),
        )
        assertSame(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            declarations.directSupertypeEdgesOrUnavailable(derivedDefinition),
        )

        val derivedOfString = construct(declarations, derivedDefinition)
        val closure = declarations.physicalInterfaceViewClosureOrError(derivedOfString).boundValue()
        assertEquals(
            setOf(DotNetGenericOwnerPhysicalView(derivedOfString)),
            closure.interfaceViews,
        )
        assertTrue(!closure.isComplete)
    }

    @Test
    fun bindsExactSiblingIdentityWithoutInventingItsIncompleteInterfaceImplSet() {
        val root = FamilySpec(
            logicalOwnerKey = "C:sample/Root",
            ownerPath = listOf("sample.Root`1"),
            capabilityOwnerPath = listOf("sample.RootKotlinSemantic"),
            exactOwnerPath = listOf("sample.RootKotlinExact`1"),
        )
        val derived = FamilySpec(
            logicalOwnerKey = "C:sample/Derived",
            ownerPath = listOf("sample.Derived`1"),
            capabilityOwnerPath = listOf("sample.DerivedKotlinSemantic"),
            exactOwnerPath = listOf("sample.DerivedKotlinExact`1"),
            parentLogicalOwnerKeys = listOf(root.logicalOwnerKey),
            rootLogicalOwnerKeys = listOf(root.logicalOwnerKey),
            lineageDepth = 1,
        )
        val authority = bindAuthority(root, derived)
        val declarations = authority.declarations
        val exactDerived = authority.exactTypeDefinitionsByLogicalOwnerKey.getValue(derived.logicalOwnerKey)

        assertSame(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            declarations.directSupertypeEdgesOrUnavailable(exactDerived),
        )

        val exactDerivedOfString = construct(declarations, exactDerived)
        val closure = declarations.physicalInterfaceViewClosureOrError(exactDerivedOfString).boundValue()
        assertEquals(
            setOf(DotNetGenericOwnerPhysicalView(exactDerivedOfString)),
            closure.interfaceViews,
        )
        assertTrue(!closure.isComplete)
        assertNull(
            declarations.typeDescriptionOrNull(
                DotNetGenericOwnerPhysicalTypeDefIdentity.KotlinProducer(
                    artifact,
                    derived.capabilityOwnerPath,
                ),
            ),
        )
    }

    @Test
    fun normalizesCanonicalAndDeclaredViewsToOneNaturalProducerIdentity() {
        val root = FamilySpec(
            logicalOwnerKey = "C:sample/Root",
            ownerPath = listOf("sample.Root`1"),
            capabilityOwnerPath = listOf("sample.RootKotlinSemantic"),
        )
        val authority = bindAuthority(root)

        assertEquals(1, authority.naturalTypeDefinitionsByLogicalOwnerKey.size)
        assertTrue(authority.exactTypeDefinitionsByLogicalOwnerKey.isEmpty())
        val naturalDefinition = authority.naturalTypeDefinitionsByLogicalOwnerKey.getValue(root.logicalOwnerKey)
        assertEquals(
            DotNetGenericOwnerPhysicalTypeDefIdentity.KotlinProducer(artifact, root.ownerPath),
            naturalDefinition,
        )
        assertSame(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            authority.declarations.directSupertypeEdgesOrUnavailable(
                DotNetGenericOwnerPhysicalTypeDefIdentity.KotlinProducer(artifact, root.ownerPath),
            ),
        )
    }

    private fun bindAuthority(
        vararg families: FamilySpec,
    ): DotNetProducerGenericInterfacePhysicalAuthority {
        val declarations = linkedMapOf<String, DotNetPhysicalDeclaration>()
        families.forEach { family ->
            family.declarations().forEach { entry ->
                check(declarations.put(entry.key, entry.value) == null)
            }
        }
        val library = DotNetExternalLibrary(
            artifact = artifact,
            assemblyFile = File(artifact.assemblyFileName),
            declarations = declarations,
            friendAssemblies = emptySet(),
        )
        return DotNetProducerGenericInterfacePhysicalAuthority.bind(listOf(library)).boundValue()
    }

    private fun construct(
        declarations: DotNetGenericOwnerPhysicalDeclarationIndex,
        definition: DotNetGenericOwnerPhysicalTypeDefIdentity,
    ): DotNetGenericOwnerSymbolicCarrierReference.Constructed = declarations.constructTypeOrError(
        definition,
        listOf(DotNetGenericOwnerSymbolicCarrierReference.stringCarrier()),
    ).boundValue()

    private fun <T> DotNetGenericOwnerPhysicalBindingResult<T>.boundValue(): T = when (this) {
        is DotNetGenericOwnerPhysicalBindingResult.Bound -> value
        is DotNetGenericOwnerPhysicalBindingResult.Conflict -> error(reason)
        DotNetGenericOwnerPhysicalBindingResult.Unavailable -> error("physical authority was unavailable")
    }

    private inner class FamilySpec(
        val logicalOwnerKey: String,
        val ownerPath: List<String>,
        val capabilityOwnerPath: List<String>,
        val exactOwnerPath: List<String>? = null,
        val parentLogicalOwnerKeys: List<String> = emptyList(),
        val rootLogicalOwnerKeys: List<String> = listOf(logicalOwnerKey),
        val lineageDepth: Int = 0,
    ) {
        fun declarations(): Map<String, DotNetPhysicalDeclaration> {
            val memberKey = "F:${logicalOwnerKey.removePrefix("C:")}/broad"
            val declaredMembers = if (exactOwnerPath != null) {
                listOf(
                    DotNetPublishedGenericInterfaceMemberContract(
                        memberKey,
                        DotNetPublishedGenericInterfaceMemberRole.BROAD_FIXED_BARRIER_INPUT,
                    ),
                )
            } else {
                emptyList()
            }
            return buildMap {
                put(
                    logicalOwnerKey,
                    DotNetPhysicalDeclaration.Class(
                        ownerPath = ownerPath,
                        physicalTypeParameterCount = 1,
                        genericOwnerAbi = DotNetGenericOwnerAbi(
                            capabilityAssemblyName = artifact.assemblyName,
                            capabilityOwnerPath = capabilityOwnerPath,
                        ),
                    ),
                )
                if (exactOwnerPath != null) {
                    put(
                        "G:$memberKey",
                        DotNetPhysicalDeclaration.GenericOwnerMemberFamily(
                            ownerPath = capabilityOwnerPath,
                            ownerLogicalKey = logicalOwnerKey,
                            logicalMemberKey = memberKey,
                            capabilityMethodName = "broad__KotlinCapability__fixture",
                            capabilityMethodGenericParameterCount = 0,
                            defaultCapabilityMethodName = null,
                            defaultCapabilityMethodGenericParameterCount = null,
                            semanticHookOwnerPath = null,
                            semanticHookMethodName = null,
                            semanticHookMethodGenericParameterCount = null,
                            foreignOverrideProbeMethodName = null,
                            foreignOverrideProbeMethodGenericParameterCount = null,
                        ),
                    )
                }
                put(
                    "H:$logicalOwnerKey",
                    DotNetPhysicalDeclaration.PublishedGenericInterfaceFamily(
                        ownerPath = ownerPath,
                        capabilityAssemblyName = artifact.assemblyName,
                        capabilityOwnerPath = capabilityOwnerPath,
                        exactOwnerPath = exactOwnerPath,
                        contract = DotNetPublishedGenericInterfaceFamilyContract(
                            logicalOwnerKey = logicalOwnerKey,
                            genericArity = 1,
                            kind = when {
                                parentLogicalOwnerKeys.isEmpty() ->
                                    DotNetPublishedGenericInterfaceFamilyKind.ROOT
                                parentLogicalOwnerKeys.size == 1 ->
                                    DotNetPublishedGenericInterfaceFamilyKind.DERIVED
                                else -> DotNetPublishedGenericInterfaceFamilyKind.INTERSECTION
                            },
                            rootLogicalOwnerKeys = rootLogicalOwnerKeys,
                            directParents = parentLogicalOwnerKeys.sorted().map { parentLogicalOwnerKey ->
                                DotNetPublishedGenericInterfaceParentContract(
                                    parentLogicalOwnerKey,
                                    listOf(0),
                                )
                            },
                            lineageDepth = lineageDepth,
                            declaredMembers = declaredMembers,
                            capabilityBindingKind =
                                DotNetPublishedGenericInterfaceCapabilityBindingKind.OWNED,
                            reusedParentLogicalOwnerKey = null,
                        ),
                    ),
                )
            }
        }
    }
}
