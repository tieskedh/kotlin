/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.builtins.DefaultBuiltIns
import org.jetbrains.kotlin.descriptors.impl.ModuleDescriptorImpl
import org.jetbrains.kotlin.ir.builders.declarations.addTypeParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.declarations.impl.IrFactoryImpl
import org.jetbrains.kotlin.ir.declarations.impl.IrFileImpl
import org.jetbrains.kotlin.ir.declarations.impl.IrModuleFragmentImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrFileSymbolImpl
import org.jetbrains.kotlin.ir.types.SimpleTypeNullability
import org.jetbrains.kotlin.ir.types.defaultType as typeParameterDefaultType
import org.jetbrains.kotlin.ir.types.impl.IrSimpleTypeImpl
import org.jetbrains.kotlin.ir.util.NaiveSourceBasedFileEntryImpl
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.storage.LockBasedStorageManager
import org.jetbrains.kotlin.types.Variance
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame

class DotNetExternalDeclarationsTest {
    @Test
    fun physicalOwnerArityControlsOwnerDependentInterfaceEdges() {
        val logicalOwner = IrFactoryImpl.buildClass {
            name = Name.identifier("LogicalOwner")
        }
        val parameter = logicalOwner.addTypeParameter {
            name = Name.identifier("T")
        }
        val closedClass = IrFactoryImpl.buildClass {
            name = Name.identifier("Closed")
        }
        val closedType = IrSimpleTypeImpl(
            closedClass.symbol,
            SimpleTypeNullability.NOT_SPECIFIED,
            emptyList(),
            emptyList(),
        )

        val erasedOwner = DotNetIlClassInfo("sample.ErasedOwner")
        val reifiedOwner = DotNetIlClassInfo(
            "sample.ReifiedOwner`1",
            typeParameterVariances = listOf(Variance.INVARIANT),
        )

        assertFalse(erasedOwner.canNameDirectInterfaceType(logicalOwner, parameter.typeParameterDefaultType))
        assertEquals(true, reifiedOwner.canNameDirectInterfaceType(logicalOwner, parameter.typeParameterDefaultType))
        assertEquals(true, erasedOwner.canNameDirectInterfaceType(logicalOwner, closedType))
    }

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

    @Test
    fun doesNotMangleLocalGenericOwnerFunctionFacts() {
        val moduleDescriptor = ModuleDescriptorImpl(
            Name.special("<testModule>"),
            LockBasedStorageManager("DotNetExternalDeclarationsTest"),
            DefaultBuiltIns.Instance,
        )
        val module = IrModuleFragmentImpl(moduleDescriptor)
        val file = IrFileImpl(
            NaiveSourceBasedFileEntryImpl("local.kt"),
            IrFileSymbolImpl(),
            FqName("sample"),
            module,
        ).also { module.files += it }
        val unrelatedOwner = IrFactoryImpl.buildClass {
            name = Name.identifier("UnrelatedOwner")
        }.apply { parent = file }
        val unrelatedTypeParameter = unrelatedOwner.addTypeParameter {
            name = Name.identifier("T")
        }
        val localFunction = IrFactoryImpl.buildFun {
            name = Name.identifier("localFunction")
            returnType = IrSimpleTypeImpl(
                unrelatedTypeParameter.symbol,
                SimpleTypeNullability.NOT_SPECIFIED,
                emptyList(),
                emptyList(),
            )
        }.apply { parent = file }
        file.declarations += unrelatedOwner
        file.declarations += localFunction

        val resolver = DotNetExternalDeclarations(emptyList())

        // The deliberately out-of-scope type parameter makes public ABI mangling fail. Local
        // declarations must be rejected by this external resolver before it attempts that work.
        assertNull(resolver.genericOwnerMemberFamilyOrNull(localFunction))
        assertNull(resolver.genericOwnerFunctionCarrierOrNull(localFunction))
        assertFalse(resolver.hasNaturalGenericOwnerFunctionReturn(localFunction))
        assertNull(resolver.genericOwnerFunctionInputEntryOrNull(localFunction))
    }
}
