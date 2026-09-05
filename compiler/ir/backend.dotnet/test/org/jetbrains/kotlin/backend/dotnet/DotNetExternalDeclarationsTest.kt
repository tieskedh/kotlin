/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.backend.dotnet.lower.requiresProducerRecordedGenericOwnerConstructorMethodDef
import org.jetbrains.kotlin.builtins.DefaultBuiltIns
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.impl.ModuleDescriptorImpl
import org.jetbrains.kotlin.ir.builders.declarations.addTypeParameter
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.declarations.buildConstructor
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.declarations.createEmptyExternalPackageFragment
import org.jetbrains.kotlin.ir.declarations.impl.IrFactoryImpl
import org.jetbrains.kotlin.ir.declarations.impl.IrFileImpl
import org.jetbrains.kotlin.ir.declarations.impl.IrModuleFragmentImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrFileSymbolImpl
import org.jetbrains.kotlin.ir.types.SimpleTypeNullability
import org.jetbrains.kotlin.ir.types.defaultType as typeParameterDefaultType
import org.jetbrains.kotlin.ir.types.impl.IrSimpleTypeImpl
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.NaiveSourceBasedFileEntryImpl
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.storage.LockBasedStorageManager
import org.jetbrains.kotlin.types.Variance
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DotNetExternalDeclarationsTest {
    @Test
    fun resolvesNaturalMethodDefByLogicalIdentityRatherThanNameAndArity() {
        val moduleDescriptor = ModuleDescriptorImpl(
            Name.special("<naturalMethodDefTestModule>"),
            LockBasedStorageManager("DotNetExternalNaturalMethodDefTest"),
            DefaultBuiltIns.Instance,
        )
        val module = IrModuleFragmentImpl(moduleDescriptor)
        val externalPackage = createEmptyExternalPackageFragment(module, FqName("sample"))
        val owner = IrFactoryImpl.buildClass {
            name = Name.identifier("Source")
            kind = ClassKind.INTERFACE
            modality = Modality.ABSTRACT
            visibility = DescriptorVisibilities.PUBLIC
        }.apply { parent = externalPackage }
        owner.addTypeParameter {
            name = Name.identifier("T")
            variance = Variance.OUT_VARIANCE
        }
        fun payload(name: String) = IrFactoryImpl.buildClass {
            this.name = Name.identifier(name)
            visibility = DescriptorVisibilities.PUBLIC
        }.apply { parent = externalPackage }
        val firstPayload = payload("FirstPayload")
        val secondPayload = payload("SecondPayload")
        fun payloadType(irClass: org.jetbrains.kotlin.ir.declarations.IrClass) = IrSimpleTypeImpl(
            irClass.symbol,
            SimpleTypeNullability.NOT_SPECIFIED,
            emptyList(),
            emptyList(),
        )
        val first = IrFactoryImpl.buildFun {
            name = Name.identifier("read")
            returnType = payloadType(firstPayload)
            modality = Modality.ABSTRACT
            visibility = DescriptorVisibilities.PUBLIC
        }.apply {
            parent = owner
            addValueParameter("candidate", payloadType(firstPayload))
        }
        val second = IrFactoryImpl.buildFun {
            name = Name.identifier("read")
            returnType = payloadType(secondPayload)
            modality = Modality.ABSTRACT
            visibility = DescriptorVisibilities.PUBLIC
        }.apply {
            parent = owner
            addValueParameter("candidate", payloadType(secondPayload))
        }
        owner.declarations += listOf(first, second)
        externalPackage.declarations += listOf(owner, firstPayload, secondPayload)

        val firstKey = checkNotNull(first.dotNetLibraryAbiKeyOrNull("F"))
        val secondKey = checkNotNull(second.dotNetLibraryAbiKeyOrNull("F"))
        assertTrue(firstKey != secondKey)
        val naturalMethodDef = producerSealedFamilyPublicationFixture()
            .toNaturalMethodDefPublication("sample/Source|class")
            .copy(logicalMemberKey = firstKey)
            .toPhysicalDeclaration()
        val resolver = DotNetExternalDeclarations(listOf(DotNetExternalLibrary(
            artifact = DotNetLibraryArtifact("sample.Library", "netstandard2.0"),
            assemblyFile = File("sample.Library.dll"),
            declarations = mapOf(naturalMethodDef.indexKey() to naturalMethodDef),
            friendAssemblies = emptySet(),
        )))

        assertSame(
            naturalMethodDef,
            checkNotNull(resolver.genericOwnerNaturalMethodDefOrNull(first)).declaration,
        )
        assertNull(resolver.genericOwnerNaturalMethodDefOrNull(second))
    }

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
    fun consumesOnlyCompatibleProducerRecordedDelegateVariance() {
        val moduleDescriptor = ModuleDescriptorImpl(
            Name.special("<delegateVarianceTestModule>"),
            LockBasedStorageManager("DotNetExternalDelegateVarianceTest"),
            DefaultBuiltIns.Instance,
        )
        val module = IrModuleFragmentImpl(moduleDescriptor)
        val externalPackage = createEmptyExternalPackageFragment(module, FqName("sample"))
        val logicalDelegate = IrFactoryImpl.buildClass {
            name = Name.identifier("ProducerDelegate")
            visibility = DescriptorVisibilities.PUBLIC
        }.apply { parent = externalPackage }
        logicalDelegate.addTypeParameter {
            name = Name.identifier("T")
            variance = Variance.OUT_VARIANCE
        }
        externalPackage.declarations += logicalDelegate
        val logicalKey = checkNotNull(logicalDelegate.dotNetLibraryAbiKeyOrNull("C"))

        fun resolver(
            physicalVariance: DotNetGenericOwnerPhysicalTypeParameterVariance,
        ): DotNetExternalDeclarations = DotNetExternalDeclarations(listOf(
            DotNetExternalLibrary(
                artifact = DotNetLibraryArtifact("sample.Library", "netstandard2.0"),
                assemblyFile = File("sample.Library.dll"),
                declarations = mapOf(
                    logicalKey to DotNetPhysicalDeclaration.Class(
                        ownerPath = listOf("sample.ProducerDelegate`1"),
                        physicalTypeParameterCount = 1,
                        physicalTypeParameterVariances = listOf(physicalVariance),
                        physicalClassVarianceKind =
                            DotNetPhysicalClassVarianceKind.SEALED_CLR_DELEGATE,
                    ),
                ),
                friendAssemblies = emptySet(),
            ),
        ))

        val covariantResolver = resolver(
            DotNetGenericOwnerPhysicalTypeParameterVariance.COVARIANT,
        )
        val covariantMapper = DotNetIlTypeMapper(
            availableClasses = emptyMap(),
            externalDeclarations = covariantResolver,
            genericOwnerRehearsal = true,
        )
        assertEquals(
            listOf(Variance.OUT_VARIANCE),
            checkNotNull(covariantResolver.classInfoOrNull(logicalDelegate, covariantMapper))
                .typeParameterVariances,
        )

        val contravariantResolver = resolver(
            DotNetGenericOwnerPhysicalTypeParameterVariance.CONTRAVARIANT,
        )
        val contravariantMapper = DotNetIlTypeMapper(
            availableClasses = emptyMap(),
            externalDeclarations = contravariantResolver,
            genericOwnerRehearsal = true,
        )
        val failure = assertFailsWith<IllegalArgumentException> {
            contravariantResolver.classInfoOrNull(logicalDelegate, contravariantMapper)
        }
        assertEquals(
            "external Kotlin/.NET delegate '$logicalKey' has physical variance " +
                    "inconsistent with its KLIB classifier",
            failure.message,
        )
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
                physicalTypeParameterVariances = listOf(
                    DotNetGenericOwnerPhysicalTypeParameterVariance.COVARIANT,
                    DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT,
                ),
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
                capabilityMethodGenericParameterCount = 0,
                defaultCapabilityMethodName = null,
                defaultCapabilityMethodGenericParameterCount = null,
                semanticHookOwnerPath = null,
                semanticHookMethodName = null,
                semanticHookMethodGenericParameterCount = null,
                foreignOverrideProbeMethodName = null,
                foreignOverrideProbeMethodGenericParameterCount = null,
                semanticObjectParameterIndices = setOf(0),
            ),
            "H:$logicalOwnerKey" to DotNetPhysicalDeclaration.PublishedGenericInterfaceFamily(
                ownerPath = listOf("sample.GenericOwner`2"),
                capabilityAssemblyName = "sample.Library",
                capabilityOwnerPath = capabilityOwnerPath,
                exactOwnerPath = listOf("sample.IGenericOwnerKotlinExact`2"),
                naturalTypeParameterVariances = listOf(
                    DotNetGenericOwnerPhysicalTypeParameterVariance.COVARIANT,
                    DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT,
                ),
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
                            resultLayout = DotNetPublishedGenericInterfaceMemberResultLayout.DIRECT,
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

        val inconsistentDeclarations = declarations + (
                "G:$logicalMemberKey" to
                        (declarations.getValue("G:$logicalMemberKey") as
                                DotNetPhysicalDeclaration.GenericOwnerMemberFamily).copy(
                            semanticObjectParameterIndices = emptySet(),
                        )
                )
        val failure = assertFailsWith<IllegalArgumentException> {
            DotNetExternalDeclarations(
                listOf(
                    DotNetExternalLibrary(
                        artifact = DotNetLibraryArtifact("sample.Library", "netstandard2.0"),
                        assemblyFile = File("sample.Library.dll"),
                        declarations = inconsistentDeclarations,
                        friendAssemblies = emptySet(),
                    )
                )
            )
        }
        assertEquals(
            "published generic-interface member '$logicalMemberKey' lacks or contradicts its producer G family",
            failure.message,
        )
    }

    @Test
    fun rejectsNonPublishedSemanticGenericOwnerFamilyWithoutHook() {
        val logicalOwnerKey = "C:sample/GenericOwner"
        val logicalMemberKey = "F:sample/GenericOwner/member"
        val capabilityOwnerPath = listOf("sample.GenericOwnerKotlinSemantic")
        val declarations = mapOf(
            logicalOwnerKey to DotNetPhysicalDeclaration.Class(
                ownerPath = listOf("sample.GenericOwner`1"),
                physicalTypeParameterCount = 1,
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
                capabilityMethodGenericParameterCount = 0,
                defaultCapabilityMethodName = null,
                defaultCapabilityMethodGenericParameterCount = null,
                semanticHookOwnerPath = null,
                semanticHookMethodName = null,
                semanticHookMethodGenericParameterCount = null,
                foreignOverrideProbeMethodName = null,
                foreignOverrideProbeMethodGenericParameterCount = null,
                semanticObjectParameterIndices = setOf(0),
            ),
        )

        val failure = assertFailsWith<IllegalArgumentException> {
            DotNetExternalDeclarations(
                listOf(
                    DotNetExternalLibrary(
                        artifact = DotNetLibraryArtifact("sample.Library", "netstandard2.0"),
                        assemblyFile = File("sample.Library.dll"),
                        declarations = declarations,
                        friendAssemblies = emptySet(),
                    )
                )
            )
        }
        assertEquals(
            "external non-published generic-owner member '$logicalMemberKey' has semantic policy " +
                    "without a semantic hook",
            failure.message,
        )

        val wrongCapability = declarations + (
                "G:$logicalMemberKey" to
                        (declarations.getValue("G:$logicalMemberKey") as
                                DotNetPhysicalDeclaration.GenericOwnerMemberFamily).copy(
                            ownerPath = listOf("sample.UnrelatedSemantic"),
                            semanticObjectParameterIndices = emptySet(),
                        )
                )
        val wrongCapabilityFailure = assertFailsWith<IllegalArgumentException> {
            DotNetExternalDeclarations(
                listOf(
                    DotNetExternalLibrary(
                        artifact = DotNetLibraryArtifact("sample.Library", "netstandard2.0"),
                        assemblyFile = File("sample.Library.dll"),
                        declarations = wrongCapability,
                        friendAssemblies = emptySet(),
                    )
                )
            )
        }
        assertEquals(
            "external generic-owner member '$logicalMemberKey' is inconsistent with its " +
                    "owning class and capability records",
            wrongCapabilityFailure.message,
        )
    }

    @Test
    fun producerRecordedPhysicalVarianceWinsOverLogicalKlibVariance() {
        val moduleDescriptor = ModuleDescriptorImpl(
            Name.special("<varianceTestModule>"),
            LockBasedStorageManager("DotNetExternalDeclarationsVarianceTest"),
            DefaultBuiltIns.Instance,
        )
        val module = IrModuleFragmentImpl(moduleDescriptor)
        val externalPackage = createEmptyExternalPackageFragment(module, FqName("sample"))
        val logicalOwner = IrFactoryImpl.buildClass {
            name = Name.identifier("GenericOwner")
            kind = ClassKind.INTERFACE
            modality = Modality.ABSTRACT
            visibility = DescriptorVisibilities.PUBLIC
        }.apply {
            parent = externalPackage
        }
        logicalOwner.addTypeParameter {
            name = Name.identifier("T")
            variance = Variance.OUT_VARIANCE
        }
        val logicalOwnerKey = checkNotNull(logicalOwner.dotNetLibraryAbiKeyOrNull("C"))
        val capabilityOwnerPath = listOf("sample.GenericOwnerKotlinSemantic")
        val declarations = mapOf(
            logicalOwnerKey to DotNetPhysicalDeclaration.Class(
                ownerPath = listOf("sample.GenericOwner`1"),
                physicalTypeParameterCount = 1,
                physicalTypeParameterVariances = listOf(
                    DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT,
                ),
                genericOwnerAbi = DotNetGenericOwnerAbi(
                    capabilityAssemblyName = "sample.Library",
                    capabilityOwnerPath = capabilityOwnerPath,
                ),
            ),
            "H:$logicalOwnerKey" to DotNetPhysicalDeclaration.PublishedGenericInterfaceFamily(
                ownerPath = listOf("sample.GenericOwner`1"),
                capabilityAssemblyName = "sample.Library",
                capabilityOwnerPath = capabilityOwnerPath,
                naturalTypeParameterVariances = listOf(
                    DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT,
                ),
                contract = DotNetPublishedGenericInterfaceFamilyContract(
                    logicalOwnerKey = logicalOwnerKey,
                    genericArity = 1,
                    kind = DotNetPublishedGenericInterfaceFamilyKind.ROOT,
                    rootLogicalOwnerKeys = listOf(logicalOwnerKey),
                    directParents = emptyList(),
                    lineageDepth = 0,
                    declaredMembers = emptyList(),
                    capabilityBindingKind = DotNetPublishedGenericInterfaceCapabilityBindingKind.OWNED,
                    reusedParentLogicalOwnerKey = null,
                ),
            ),
        )
        val resolver = DotNetExternalDeclarations(listOf(DotNetExternalLibrary(
            artifact = DotNetLibraryArtifact("sample.Library", "netstandard2.0"),
            assemblyFile = File("sample.Library.dll"),
            declarations = declarations,
            friendAssemblies = emptySet(),
        )))
        val mapper = DotNetIlTypeMapper(
            availableClasses = emptyMap(),
            externalDeclarations = resolver,
            genericOwnerRehearsal = true,
        )

        assertEquals(listOf(Variance.OUT_VARIANCE), logicalOwner.typeParameters.map { it.variance })
        assertEquals(
            listOf(Variance.INVARIANT),
            checkNotNull(resolver.classInfoOrNull(logicalOwner, mapper)).typeParameterVariances,
        )

        val staleDeclarations = declarations + (
                "H:$logicalOwnerKey" to
                        (declarations.getValue("H:$logicalOwnerKey") as
                                DotNetPhysicalDeclaration.PublishedGenericInterfaceFamily).copy(
                            naturalTypeParameterVariances = listOf(
                                DotNetGenericOwnerPhysicalTypeParameterVariance.CONTRAVARIANT,
                            ),
                        )
                )
        val failure = assertFailsWith<IllegalArgumentException> {
            DotNetExternalDeclarations(listOf(DotNetExternalLibrary(
                artifact = DotNetLibraryArtifact("sample.Library", "netstandard2.0"),
                assemblyFile = File("sample.Library.dll"),
                declarations = staleDeclarations,
                friendAssemblies = emptySet(),
            )))
        }
        assertEquals(
            "published generic-interface family '$logicalOwnerKey' is inconsistent with its class record",
            failure.message,
        )

        val physicallyContravariantDeclarations = declarations
            .plus(logicalOwnerKey to
                    (declarations.getValue(logicalOwnerKey) as DotNetPhysicalDeclaration.Class).copy(
                        physicalTypeParameterVariances = listOf(
                            DotNetGenericOwnerPhysicalTypeParameterVariance.CONTRAVARIANT,
                        ),
                    ))
            .plus("H:$logicalOwnerKey" to
                    (declarations.getValue("H:$logicalOwnerKey") as
                            DotNetPhysicalDeclaration.PublishedGenericInterfaceFamily).copy(
                        naturalTypeParameterVariances = listOf(
                            DotNetGenericOwnerPhysicalTypeParameterVariance.CONTRAVARIANT,
                        ),
                    ))
        val physicallyContravariantResolver = DotNetExternalDeclarations(listOf(
            DotNetExternalLibrary(
                artifact = DotNetLibraryArtifact("sample.Library", "netstandard2.0"),
                assemblyFile = File("sample.Library.dll"),
                declarations = physicallyContravariantDeclarations,
                friendAssemblies = emptySet(),
            ),
        ))
        val logicalFailure = assertFailsWith<IllegalArgumentException> {
            physicallyContravariantResolver
                .publishedGenericInterfaceNaturalFixedTypeInputOrNull(logicalOwner)
        }
        assertEquals(
            "external Kotlin/.NET interface '$logicalOwnerKey' records physical variance " +
                    "stronger than its KLIB contract",
            logicalFailure.message,
        )
    }

    @Test
    fun distinguishesProducerRecordedNaturalAndSemanticFunctionResults() {
        val moduleDescriptor = ModuleDescriptorImpl(
            Name.special("<functionResultTestModule>"),
            LockBasedStorageManager("DotNetExternalDeclarationsFunctionResultTest"),
            DefaultBuiltIns.Instance,
        )
        val module = IrModuleFragmentImpl(moduleDescriptor)
        val externalPackage = createEmptyExternalPackageFragment(module, FqName("sample"))
        val logicalOwner = IrFactoryImpl.buildClass {
            name = Name.identifier("GenericOwner")
            kind = ClassKind.INTERFACE
            modality = Modality.ABSTRACT
            visibility = DescriptorVisibilities.PUBLIC
        }.apply {
            parent = externalPackage
        }
        logicalOwner.addTypeParameter {
            name = Name.identifier("T")
            variance = Variance.OUT_VARIANCE
        }
        val payload = IrFactoryImpl.buildClass {
            name = Name.identifier("Payload")
            visibility = DescriptorVisibilities.PUBLIC
        }.apply {
            parent = externalPackage
        }
        val payloadType = IrSimpleTypeImpl(
            payload.symbol,
            SimpleTypeNullability.NOT_SPECIFIED,
            emptyList(),
            emptyList(),
        )
        val factory = IrFactoryImpl.buildFun {
            name = Name.identifier("genericOwnerFactory")
            returnType = logicalOwner.symbol.typeWith(payloadType)
            visibility = DescriptorVisibilities.PUBLIC
        }.apply {
            parent = externalPackage
        }
        externalPackage.declarations += listOf(logicalOwner, payload, factory)

        val logicalKey = checkNotNull(factory.dotNetLibraryAbiKeyOrNull("F"))
        val physicalFunction = DotNetPhysicalDeclaration.Function(
            ownerPath = listOf("sample.FunctionResultsKt"),
            methodName = "genericOwnerFactory",
            isInstance = false,
            methodGenericParameterCount = 0,
        )
        fun resolver(declarations: Map<String, DotNetPhysicalDeclaration>) =
            DotNetExternalDeclarations(listOf(DotNetExternalLibrary(
                artifact = DotNetLibraryArtifact("sample.Library", "netstandard2.0"),
                assemblyFile = File("sample.Library.dll"),
                declarations = declarations,
                friendAssemblies = emptySet(),
            )))

        assertTrue(resolver(mapOf(logicalKey to physicalFunction))
            .hasNaturalGenericOwnerFunctionReturn(factory))

        val semanticResult = DotNetPhysicalDeclaration.GenericOwnerFunctionCarrier(
            ownerPath = physicalFunction.ownerPath,
            logicalFunctionKey = logicalKey,
            returnCarrier = DotNetGenericOwnerFunctionCarrierKind.SEMANTIC_CAPABILITY,
            parameterCarriers = emptyMap(),
        )
        assertFalse(resolver(mapOf(
            logicalKey to physicalFunction,
            "S:$logicalKey" to semanticResult,
        )).hasNaturalGenericOwnerFunctionReturn(factory))
    }

    @Test
    fun sourceBackedBootstrapDeclarationsRetainExternalPhysicalAuthority() {
        val moduleDescriptor = ModuleDescriptorImpl(
            Name.special("<bootstrapProductTestModule>"),
            LockBasedStorageManager("DotNetExternalBootstrapProductTest"),
            DefaultBuiltIns.Instance,
        )
        val module = IrModuleFragmentImpl(moduleDescriptor)
        val file = IrFileImpl(
            NaiveSourceBasedFileEntryImpl("bootstrap.kt"),
            IrFileSymbolImpl(),
            FqName("sample"),
            module,
        ).also { module.files += it }
        val owner = IrFactoryImpl.buildClass {
            name = Name.identifier("Bootstrap")
            visibility = DescriptorVisibilities.PUBLIC
        }.apply { parent = file }
        val ownerType = IrSimpleTypeImpl(
            owner.symbol,
            SimpleTypeNullability.NOT_SPECIFIED,
            emptyList(),
            emptyList(),
        )
        val factory = IrFactoryImpl.buildFun {
            name = Name.identifier("bootstrap")
            returnType = ownerType
            visibility = DescriptorVisibilities.PUBLIC
        }.apply { parent = file }
        file.declarations += listOf(owner, factory)

        val ownerKey = checkNotNull(owner.dotNetLibraryAbiKeyOrNull("C"))
        val factoryKey = checkNotNull(factory.dotNetLibraryAbiKeyOrNull("F"))
        val resolver = DotNetExternalDeclarations(listOf(DotNetExternalLibrary(
            artifact = DotNetLibraryArtifact("sample.BootstrapProduct", "netstandard2.0"),
            assemblyFile = File("sample.BootstrapProduct.dll"),
            declarations = mapOf(
                ownerKey to DotNetPhysicalDeclaration.Class(
                    ownerPath = listOf("sample.Bootstrap"),
                ),
                factoryKey to DotNetPhysicalDeclaration.Function(
                    ownerPath = listOf("sample.BootstrapKt"),
                    methodName = "bootstrap",
                    isInstance = false,
                    methodGenericParameterCount = 0,
                ),
            ),
            friendAssemblies = emptySet(),
        )))
        val mapper = DotNetIlTypeMapper(
            availableClasses = emptyMap(),
            externalDeclarations = resolver,
        )

        val ownerInfo = checkNotNull(resolver.classInfoOrNull(owner, mapper))
        assertEquals("sample.BootstrapProduct", ownerInfo.assemblyName)
        assertEquals(listOf("sample.Bootstrap"), ownerInfo.physicalPathComponents())
        val factoryInfo = checkNotNull(resolver.functionInfoOrNull(factory, mapper))
        assertEquals("sample.BootstrapProduct", factoryInfo.owner.assemblyName)
        assertEquals("bootstrap", factoryInfo.physicalMethodName)
        assertEquals(
            DotNetIlReturnType.Value(DotNetIlValueType.UserClass(ownerInfo)),
            factoryInfo.signature.returnType,
        )
    }

    @Test
    fun constructorAuthorityUsesCurrentEmitterOwnershipRatherThanSourceBacking() {
        val moduleDescriptor = ModuleDescriptorImpl(
            Name.special("<bootstrapConstructorTestModule>"),
            LockBasedStorageManager("DotNetExternalBootstrapConstructorTest"),
            DefaultBuiltIns.Instance,
        )
        val module = IrModuleFragmentImpl(moduleDescriptor)
        val file = IrFileImpl(
            NaiveSourceBasedFileEntryImpl("bootstrapConstructor.kt"),
            IrFileSymbolImpl(),
            FqName("sample"),
            module,
        ).also { module.files += it }
        val owner = IrFactoryImpl.buildClass {
            name = Name.identifier("Box")
            visibility = DescriptorVisibilities.PUBLIC
        }.apply { parent = file }
        val parameter = owner.addTypeParameter {
            name = Name.identifier("T")
        }
        val constructor = IrFactoryImpl.buildConstructor {
            isPrimary = true
            visibility = DescriptorVisibilities.PUBLIC
            returnType = owner.symbol.typeWith(parameter.typeParameterDefaultType)
        }.apply {
            parent = owner
            addValueParameter("value", parameter.typeParameterDefaultType)
        }
        owner.declarations += constructor
        file.declarations += owner

        assertTrue(
            owner.requiresProducerRecordedGenericOwnerConstructorMethodDef(
                currentModuleClasses = emptySet(),
                hasSemanticCarrierParameter = true,
            ),
            "a source-backed bootstrap owner is external to the current user emission",
        )
        assertFalse(
            owner.requiresProducerRecordedGenericOwnerConstructorMethodDef(
                currentModuleClasses = setOf(owner),
                hasSemanticCarrierParameter = true,
            ),
            "the same declaration must not consume an external record in its own emission",
        )
        assertFalse(
            owner.requiresProducerRecordedGenericOwnerConstructorMethodDef(
                currentModuleClasses = emptySet(),
                hasSemanticCarrierParameter = false,
            ),
            "an ordinary declaration-stable constructor parameter needs no rehearsal seal",
        )

        val ownerKey = checkNotNull(owner.dotNetLibraryAbiKeyOrNull("C"))
        val constructorKey = checkNotNull(constructor.dotNetLibraryAbiKeyOrNull("F"))
        val ownerPath = listOf("sample.Box`1")
        val exactConstructor = DotNetPhysicalDeclaration.GenericOwnerConstructorMethodDef(
            logicalOwnerKey = ownerKey,
            logicalConstructorKey = constructorKey,
            ownerPath = ownerPath,
            physicalMethod = DotNetGenericOwnerPhysicalMethodIdentityRecord(
                physicalOwnerPath = ownerPath,
                physicalMethodName = ".ctor",
                signature = DotNetGenericOwnerPhysicalMethodSignatureRecord(
                    isInstance = true,
                    genericArity = 0,
                    resultLayout = DotNetGenericOwnerPhysicalCallableResultLayoutRecord.Void,
                    parameterSlots = listOf(DotNetGenericOwnerPhysicalValueSlotRecord(
                        domain = DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_INPUT,
                        type = DotNetGenericOwnerPhysicalTypeExpressionRecord.objectType(),
                    )),
                ),
            ),
            visibility = DotNetGenericOwnerPhysicalConstructorVisibility.PUBLIC,
        )
        val resolver = DotNetExternalDeclarations(listOf(DotNetExternalLibrary(
            artifact = DotNetLibraryArtifact("sample.BootstrapProduct", "netstandard2.0"),
            assemblyFile = File("sample.BootstrapProduct.dll"),
            declarations = mapOf(
                ownerKey to DotNetPhysicalDeclaration.Class(
                    ownerPath = ownerPath,
                    physicalTypeParameterCount = 1,
                ),
                constructorKey to DotNetPhysicalDeclaration.Function(
                    ownerPath = ownerPath,
                    methodName = ".ctor",
                    isInstance = true,
                    methodGenericParameterCount = 0,
                ),
                exactConstructor.indexKey() to exactConstructor,
            ),
            friendAssemblies = emptySet(),
        )))

        val externalMapper = DotNetIlTypeMapper(
            availableClasses = emptyMap(),
            localClasses = emptySet(),
            externalDeclarations = resolver,
            genericOwnerRehearsal = true,
        )
        assertEquals(
            "sample.BootstrapProduct",
            checkNotNull(externalMapper.externalGenericOwnerConstructorInfoOrNull(constructor))
                .owner.assemblyName,
        )

        val localClassInfo = DotNetIlClassInfo(
            "sample.Box`1",
            typeParameterVariances = listOf(Variance.INVARIANT),
        )
        val localMapper = DotNetIlTypeMapper(
            availableClasses = mapOf(owner to localClassInfo),
            localClasses = setOf(owner),
            externalDeclarations = resolver,
            genericOwnerRehearsal = true,
        )
        assertNull(localMapper.externalGenericOwnerConstructorInfoOrNull(constructor))
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
