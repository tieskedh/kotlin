/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.types.Variance
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class DotNetExternalDeclarationsTest {
    @Test
    fun resolvesProducerRecordedExactGenericInterfaceOwner() {
        val logicalOwnerKey = "C:sample/GenericOwner"
        val logicalMemberKey = "F:sample/GenericOwner/member"
        val capabilityOwnerPath = listOf("sample.IGenericOwnerKotlinSemantic")
        val declarations = mapOf(
            logicalOwnerKey to DotNetPhysicalDeclaration.Class(
                ownerPath = listOf("sample.GenericOwner`2"),
                physicalTypeParameterCount = 2,
                genericOwnerAbi = DotNetGenericOwnerAbi(
                    capabilityAssemblyName = "sample.Library",
                    capabilityOwnerPath = capabilityOwnerPath,
                ),
            ),
            "G:$logicalMemberKey" to DotNetPhysicalDeclaration.GenericOwnerMemberFamily(
                ownerPath = capabilityOwnerPath,
                ownerLogicalKey = logicalOwnerKey,
                logicalMemberKey = logicalMemberKey,
                capabilityMethodName = "member__KotlinCapability__1234",
                defaultCapabilityMethodName = null,
                semanticHookOwnerPath = null,
                semanticHookMethodName = null,
                foreignOverrideProbeMethodName = null,
            ),
            "H:$logicalOwnerKey" to DotNetPhysicalDeclaration.PublishedGenericInterfaceFamily(
                ownerPath = listOf("sample.GenericOwner`2"),
                capabilityAssemblyName = "sample.Library",
                capabilityOwnerPath = capabilityOwnerPath,
                exactOwnerPath = listOf("sample.IGenericOwnerKotlinExact`2"),
                contract = DotNetPublishedGenericInterfaceFamilyContract(
                    logicalOwnerKey = logicalOwnerKey,
                    genericArity = 2,
                    kind = DotNetPublishedGenericInterfaceFamilyKind.ROOT,
                    rootLogicalOwnerKeys = listOf(logicalOwnerKey),
                    directParents = emptyList(),
                    lineageDepth = 0,
                    declaredMembers = listOf(
                        DotNetPublishedGenericInterfaceMemberContract(
                            logicalMemberKey = logicalMemberKey,
                            role = DotNetPublishedGenericInterfaceMemberRole.BROAD_NESTED_SEMANTIC_INPUT,
                        )
                    ),
                    capabilityBindingKind =
                        DotNetPublishedGenericInterfaceCapabilityBindingKind.OWNED,
                    reusedParentLogicalOwnerKey = null,
                ),
            ),
        )
        val resolver = DotNetExternalDeclarations(
            listOf(
                DotNetExternalLibrary(
                    artifact = DotNetLibraryArtifact("sample.Library", "netstandard2.0"),
                    assemblyFile = File("sample.Library.dll"),
                    declarations = declarations,
                    friendAssemblies = emptySet(),
                )
            )
        )

        val exactOwner = checkNotNull(
            resolver.exactGenericInterfaceOwnerInfoOrNull(logicalOwnerKey)
        )

        assertEquals("[sample.Library]'sample.IGenericOwnerKotlinExact`2'", exactOwner.ilTypeRef)
        assertEquals(
            listOf(Variance.INVARIANT, Variance.INVARIANT),
            exactOwner.typeParameterVariances,
        )
        assertSame(exactOwner, resolver.exactGenericInterfaceOwnerInfoOrNull(logicalOwnerKey))
        assertNull(resolver.exactGenericInterfaceOwnerInfoOrNull("C:sample/Producer"))
    }
}
