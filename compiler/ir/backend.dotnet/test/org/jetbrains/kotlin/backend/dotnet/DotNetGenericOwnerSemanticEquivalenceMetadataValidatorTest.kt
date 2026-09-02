/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import java.io.File
import org.jetbrains.kotlin.load.dotnet.DotNetClrAssemblyMetadata
import org.jetbrains.kotlin.load.dotnet.DotNetClrAssemblyReference
import org.jetbrains.kotlin.load.dotnet.DotNetClrBlob
import org.jetbrains.kotlin.load.dotnet.DotNetClrGenericParameterDefinition
import org.jetbrains.kotlin.load.dotnet.DotNetClrGenericParameterKind
import org.jetbrains.kotlin.load.dotnet.DotNetClrInterfaceImplementation
import org.jetbrains.kotlin.load.dotnet.DotNetClrMemberReference
import org.jetbrains.kotlin.load.dotnet.DotNetClrMemberReferenceSignature
import org.jetbrains.kotlin.load.dotnet.DotNetClrMetadataHandle
import org.jetbrains.kotlin.load.dotnet.DotNetClrMethodBody
import org.jetbrains.kotlin.load.dotnet.DotNetClrMethodDefinition
import org.jetbrains.kotlin.load.dotnet.DotNetClrMethodImplementation
import org.jetbrains.kotlin.load.dotnet.DotNetClrMethodSignature
import org.jetbrains.kotlin.load.dotnet.DotNetClrMethodSpecification
import org.jetbrains.kotlin.load.dotnet.DotNetClrParameterDefinition
import org.jetbrains.kotlin.load.dotnet.DotNetClrPrimitiveType
import org.jetbrains.kotlin.load.dotnet.DotNetClrSignatureCallingConvention
import org.jetbrains.kotlin.load.dotnet.DotNetClrTypeDefinition
import org.jetbrains.kotlin.load.dotnet.DotNetClrTypeReference
import org.jetbrains.kotlin.load.dotnet.DotNetClrTypeSignature
import org.jetbrains.kotlin.load.dotnet.DotNetClrTypeSpecification
import org.jetbrains.kotlin.load.dotnet.DotNetManagedAssemblyIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DotNetGenericOwnerSemanticEquivalenceMetadataValidatorTest {
    @Test
    fun acceptsExactArityZeroAndArityOneForwardingBodies() {
        listOf(0, 1).forEach { methodArity ->
            val fixture = metadataFixture(methodArity)
            val callAssembly = fixture.assembly.copy(methodBodies = fixture.assembly.methodBodies.map { body ->
                body.copy(code = DotNetClrBlob.copyOf(
                    body.code.toUnsignedIntList().toMutableList().also { bytes ->
                        bytes[methodArity + 1] = CIL_CALL
                    }.map(Int::toByte).toByteArray(),
                ))
            })
            val family = fixture.bindFamily(callAssembly)
            val certificate = fixture.bindCertificate(family, callAssembly)

            assertEquals(fixture.familyDeclaration.indexKey(), family.familyIndexKey)
            assertEquals(fixture.familyDeclaration.indexKey(), certificate.family.familyIndexKey)
            withTemporaryAssemblyFile { assemblyFile ->
                val stamp = createDotNetGenericOwnerPeValidationStamp(
                    assemblyFile,
                    callAssembly,
                    listOf(certificate),
                )
                assertTrue(stamp.belongsTo(assemblyFile))
                val declarations = mapOf(
                    fixture.familyDeclaration.indexKey() to fixture.familyDeclaration,
                    fixture.certificateDeclaration.indexKey() to fixture.certificateDeclaration,
                )
                DotNetExternalLibrary(
                    artifact = DotNetLibraryArtifact(
                        fixture.assembly.identity.name,
                        "net10.0",
                        fixture.assembly.identity.version,
                        fixture.assembly.identity.culture,
                    ),
                    assemblyFile = assemblyFile,
                    declarations = declarations,
                    friendAssemblies = emptySet(),
                    genericOwnerPeValidationStamp = stamp,
                )
                withTemporaryAssemblyFile { otherAssemblyFile ->
                    assertFailsWith<IllegalArgumentException> {
                        DotNetExternalLibrary(
                            artifact = DotNetLibraryArtifact(
                                fixture.assembly.identity.name,
                                "net10.0",
                                fixture.assembly.identity.version,
                                fixture.assembly.identity.culture,
                            ),
                            assemblyFile = otherAssemblyFile,
                            declarations = declarations,
                            friendAssemblies = emptySet(),
                            genericOwnerPeValidationStamp = stamp,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun rejectsWrongMethodImplEndpointAndConstructedOwner() {
        val fixture = metadataFixture(methodArity = 0)
        val classImplementation = fixture.assembly.methodImplementations.single { implementation ->
            implementation.bodyMethod == fixture.method(
                DotNetProducerGenericOwnerSealedMethodDefRole.CLASS_SEMANTIC_CAPABILITY_DISPATCHER,
            )
        }
        val wrongEndpoint = fixture.assembly.copy(methodImplementations =
            fixture.assembly.methodImplementations.map { implementation ->
                if (implementation.handle == classImplementation.handle) {
                    implementation.copy(bodyMethod = fixture.method(
                        DotNetProducerGenericOwnerSealedMethodDefRole.INTERFACE_SEMANTIC_CAPABILITY_DISPATCHER,
                    ))
                } else {
                    implementation
                }
            }
        )
        assertFailsWith<IllegalArgumentException> {
            fixture.bindFamily(wrongEndpoint)
        }

        val classSlot = fixture.methodDefinition(
            DotNetProducerGenericOwnerSealedMethodDefRole.CLASS_SEMANTIC_CAPABILITY_SLOT,
        )
        val wrongOwnerSpec = DotNetClrTypeSpecification(
            handle = DotNetClrMetadataHandle(TYPE_SPEC_TABLE, 91),
            signature = DotNetClrTypeSignature.GenericInstance(
                DotNetClrTypeSignature.Named(
                    fixture.type(DotNetProducerGenericOwnerSealedTypeDefRole.NATURAL_INTERFACE),
                    isValueType = false,
                ),
                listOf(DotNetClrTypeSignature.GenericParameter(
                    DotNetClrGenericParameterKind.TYPE,
                    0,
                )),
            ),
            rawSignature = emptyList(),
        )
        val wrongDeclaration = DotNetClrMemberReference(
            handle = DotNetClrMetadataHandle(MEMBER_REF_TABLE, 91),
            parent = wrongOwnerSpec.handle,
            name = classSlot.name,
            signature = DotNetClrMemberReferenceSignature.Method(classSlot.signature),
            rawSignature = emptyList(),
        )
        val wrongOwner = fixture.assembly.copy(
            typeSpecifications = fixture.assembly.typeSpecifications + wrongOwnerSpec,
            memberReferences = fixture.assembly.memberReferences + wrongDeclaration,
            methodImplementations = fixture.assembly.methodImplementations.map { implementation ->
                if (implementation.handle == classImplementation.handle) {
                    implementation.copy(declarationMethod = wrongDeclaration.handle)
                } else {
                    implementation
                }
            },
        )
        assertFailsWith<IllegalArgumentException> {
            fixture.bindFamily(wrongOwner)
        }
    }

    @Test
    fun acceptsAnExactOpenLocalMemberRefBodyButRejectsAnotherLocalOwner() {
        val fixture = metadataFixture(methodArity = 0)
        val selectedImplementation = fixture.assembly.methodImplementations.first { implementation ->
            implementation.bodyMethod == fixture.method(
                DotNetProducerGenericOwnerSealedMethodDefRole.CLASS_SEMANTIC_CAPABILITY_DISPATCHER,
            )
        }
        val expectedBody = fixture.methodDefinition(
            DotNetProducerGenericOwnerSealedMethodDefRole.CLASS_SEMANTIC_CAPABILITY_DISPATCHER,
        )
        fun assemblyWithBodyMemberReference(
            row: Int,
            parent: DotNetClrMetadataHandle,
        ): DotNetClrAssemblyMetadata {
            val reference = DotNetClrMemberReference(
                handle = DotNetClrMetadataHandle(MEMBER_REF_TABLE, row),
                parent = parent,
                name = expectedBody.name,
                signature = DotNetClrMemberReferenceSignature.Method(expectedBody.signature),
                rawSignature = emptyList(),
            )
            return fixture.assembly.copy(
                memberReferences = fixture.assembly.memberReferences + reference,
                methodImplementations = fixture.assembly.methodImplementations.map { implementation ->
                    if (implementation.handle == selectedImplementation.handle) {
                        implementation.copy(bodyMethod = reference.handle)
                    } else {
                        implementation
                    }
                },
            )
        }

        fixture.bindFamily(assemblyWithBodyMemberReference(
            row = 92,
            parent = fixture.type(DotNetProducerGenericOwnerSealedTypeDefRole.IMPLEMENTATION_CLASS),
        ))
        assertFailsWith<IllegalArgumentException> {
            fixture.bindFamily(assemblyWithBodyMemberReference(
                row = 93,
                parent = fixture.type(DotNetProducerGenericOwnerSealedTypeDefRole.NATURAL_INTERFACE),
            ))
        }
    }

    @Test
    fun rejectsHostileForwardingOpcodeTokenBoxArgumentsAndTrailingCode() {
        val fixture = metadataFixture(methodArity = 1)
        val classDispatcher = fixture.method(
            DotNetProducerGenericOwnerSealedMethodDefRole.CLASS_SEMANTIC_CAPABILITY_DISPATCHER,
        )
        val interfaceDispatcher = fixture.method(
            DotNetProducerGenericOwnerSealedMethodDefRole.INTERFACE_SEMANTIC_CAPABILITY_DISPATCHER,
        )
        val classBody = fixture.body(classDispatcher)
        val interfaceBody = fixture.body(interfaceDispatcher)
        val classSpecification = fixture.methodSpecificationCalling(
            DotNetProducerGenericOwnerSealedMethodDefRole.IMPLEMENTATION_TYPED_ENTRY,
        )
        val validClassCode = classBody.code.toUnsignedIntList()
        val validInterfaceCode = interfaceBody.code.toUnsignedIntList()

        val wrongCallOpcode = validClassCode.toMutableList().also { bytes ->
            bytes[2] = CIL_CALLI
        }
        val wrongCallTarget = fixture.assembly.copy(methodSpecifications =
            fixture.assembly.methodSpecifications.map { specification ->
                if (specification.handle == classSpecification.handle) {
                    specification.copy(method = fixture.callTarget(
                        DotNetProducerGenericOwnerSealedMethodDefRole.CLASS_SEMANTIC_CAPABILITY_DISPATCHER,
                    ))
                } else {
                    specification
                }
            }
        )
        val wrongBoxOpcode = validClassCode.toMutableList().also { bytes ->
            bytes[7] = CIL_NOP
        }
        val wrongBoxSpec = fixture.assembly.copy(typeSpecifications =
            fixture.assembly.typeSpecifications.map { specification ->
                if (specification.handle == fixture.boxTypeSpecification) {
                    specification.copy(signature = DotNetClrTypeSignature.GenericParameter(
                        DotNetClrGenericParameterKind.METHOD,
                        0,
                    ))
                } else {
                    specification
                }
            }
        )
        val changedArguments = validInterfaceCode.toMutableList().also { bytes ->
            bytes[1] = CIL_LDARG_2
        }
        val trailingCode = validInterfaceCode + CIL_NOP

        val hostiles = listOf(
            fixture.assembly.withCode(classDispatcher, wrongCallOpcode),
            wrongCallTarget,
            fixture.assembly.withCode(classDispatcher, wrongBoxOpcode),
            wrongBoxSpec,
            fixture.assembly.withCode(interfaceDispatcher, changedArguments),
            fixture.assembly.withCode(interfaceDispatcher, trailingCode),
        )
        hostiles.forEachIndexed { index, hostile ->
            val family = fixture.bindFamily(hostile)
            assertFailsWith<IllegalArgumentException>("forwarding-body hostile $index was accepted") {
                fixture.bindCertificate(family, hostile)
            }
        }
    }

    @Test
    fun rejectsMethodSpecWithWrongArgumentsOrNonMethodSpecGenericCall() {
        val fixture = metadataFixture(methodArity = 1)
        val classDispatcher = fixture.method(
            DotNetProducerGenericOwnerSealedMethodDefRole.CLASS_SEMANTIC_CAPABILITY_DISPATCHER,
        )
        val classBody = fixture.body(classDispatcher)
        val classSpecification = fixture.methodSpecificationCalling(
            DotNetProducerGenericOwnerSealedMethodDefRole.IMPLEMENTATION_TYPED_ENTRY,
        )
        val wrongArguments = fixture.assembly.copy(methodSpecifications =
            fixture.assembly.methodSpecifications.map { specification ->
                if (specification.handle == classSpecification.handle) {
                    specification.copy(typeArguments = listOf(
                        DotNetClrTypeSignature.GenericParameter(DotNetClrGenericParameterKind.TYPE, 0),
                    ))
                } else {
                    specification
                }
            }
        )
        val directMethodDefCall = classBody.code.toUnsignedIntList().toMutableList().also { bytes ->
            bytes.replaceToken(start = 3, fixture.method(
                DotNetProducerGenericOwnerSealedMethodDefRole.IMPLEMENTATION_TYPED_ENTRY,
            ))
        }

        listOf(
            wrongArguments,
            fixture.assembly.withCode(classDispatcher, directMethodDefCall),
        ).forEachIndexed { index, hostile ->
            val family = fixture.bindFamily(hostile)
            assertFailsWith<IllegalArgumentException>("generic call-token hostile $index was accepted") {
                fixture.bindCertificate(family, hostile)
            }
        }
    }

    @Test
    fun rejectsCrossSnapshotBindingAndAllowsUnrelatedSelectedBodies() {
        val fixture = metadataFixture(methodArity = 0)
        val family = fixture.bindFamily()
        val secondSnapshot = fixture.assembly.copy()
        assertFailsWith<IllegalArgumentException> {
            fixture.bindCertificate(family, secondSnapshot)
        }

        val unrelatedMethod = DotNetClrMethodDefinition(
            handle = DotNetClrMetadataHandle(METHOD_DEF_TABLE, 99),
            declaringType = fixture.type(DotNetProducerGenericOwnerSealedTypeDefRole.IMPLEMENTATION_CLASS),
            name = "Unrelated",
            relativeVirtualAddress = 99,
            implementationAttributes = 0,
            attributes = PRIVATE_FINAL_VIRTUAL_METHOD_ATTRIBUTES,
            signature = DotNetClrMethodSignature(
                callingConvention = DotNetClrSignatureCallingConvention.DEFAULT,
                hasThis = true,
                hasExplicitThis = false,
                genericParameterCount = 0,
                returnType = DotNetClrTypeSignature.Void,
                parameterTypes = emptyList(),
                varargParameterStart = null,
            ),
            rawSignature = emptyList(),
        )
        val unrelatedBody = DotNetClrMethodBody(
            method = unrelatedMethod.handle,
            isTiny = true,
            headerSize = 1,
            maxStack = 0,
            initLocals = false,
            localVariableSignature = null,
            hasExtraSections = false,
            code = DotNetClrBlob.copyOf(byteArrayOf(CIL_RET.toByte())),
        )
        val overSelected = fixture.assembly.copy(
            methodDefinitions = fixture.assembly.methodDefinitions + unrelatedMethod,
            methodBodies = fixture.assembly.methodBodies + unrelatedBody,
        )
        val overSelectedFamily = fixture.bindFamily(overSelected)
        val overSelectedCertificate = fixture.bindCertificate(overSelectedFamily, overSelected)
        withTemporaryAssemblyFile { assemblyFile ->
            assertTrue(createDotNetGenericOwnerPeValidationStamp(
                assemblyFile,
                overSelected,
                listOf(overSelectedCertificate),
            ).belongsTo(assemblyFile))
        }
    }

    @Test
    fun stampRejectsCrossFamilyMethodDefAndMethodImplAliasing() {
        val fixture = metadataFixture(methodArity = 0)
        val originalFamily = fixture.bindFamily()
        val originalCertificate = fixture.bindCertificate(originalFamily)

        listOf(
            fixture.publication.key.copy(
                implementationOwnerKey = "demo/OtherValue|class",
                implementationMemberKey = "demo/OtherValue.value|function",
            ),
            fixture.publication.key.copy(
                logicalInterfaceMemberKey = "demo/OtherProducer.value|function",
            ),
        ).forEachIndexed { index, aliasedKey ->
            val aliasedFamilyDeclaration = fixture.publication.copy(key = aliasedKey)
                .toPhysicalDeclaration()
            val aliasedCertificateDeclaration =
                DotNetProducerGenericOwnerSemanticEquivalenceCertificate
                    .finalConcreteDirectTypedEntryChain(aliasedFamilyDeclaration.indexKey())
                    .toPhysicalDeclaration()
            val aliasedFamily = validateDotNetGenericOwnerSealedFamilyAgainstClrMetadata(
                aliasedFamilyDeclaration,
                fixture.assembly,
                CORE_LIBRARY,
            )
            val aliasedCertificate =
                validateDotNetGenericOwnerSemanticEquivalenceCertificateAgainstClrMetadata(
                    aliasedCertificateDeclaration,
                    aliasedFamily,
                    fixture.assembly,
                )
            withTemporaryAssemblyFile { assemblyFile ->
                assertFailsWith<IllegalArgumentException>(
                    "cross-family PE-row alias hostile $index was accepted",
                ) {
                    createDotNetGenericOwnerPeValidationStamp(
                        assemblyFile,
                        fixture.assembly,
                        listOf(originalCertificate, aliasedCertificate),
                    )
                }
            }
        }
    }

    @Test
    fun roleBijectionPermitsOnlyTheRowsOwnedByACommonLogicalEndpointToBeShared() {
        val firstKey = metadataFixture(methodArity = 0).publication.key

        fun validatePair(
            secondKey: DotNetProducerGenericOwnerSealedFamilyKey,
            sharedMethodRoles: Set<DotNetProducerGenericOwnerSealedMethodDefRole>,
            sharedMethodImplRoles: Set<DotNetProducerGenericOwnerSealedMethodImplRole>,
        ) {
            val firstMethods = DotNetProducerGenericOwnerSealedMethodDefRole.entries.associateWith { role ->
                DotNetClrMetadataHandle(METHOD_DEF_TABLE, role.ordinal + 1)
            }
            val secondMethods = DotNetProducerGenericOwnerSealedMethodDefRole.entries.associateWith { role ->
                if (role in sharedMethodRoles) {
                    firstMethods.getValue(role)
                } else {
                    DotNetClrMetadataHandle(METHOD_DEF_TABLE, role.ordinal + 101)
                }
            }
            val firstMethodImpls = DotNetProducerGenericOwnerSealedMethodImplRole.entries.associateWith { role ->
                DotNetClrMetadataHandle(METHOD_IMPL_TABLE, role.ordinal + 1)
            }
            val secondMethodImpls = DotNetProducerGenericOwnerSealedMethodImplRole.entries.associateWith { role ->
                if (role in sharedMethodImplRoles) {
                    firstMethodImpls.getValue(role)
                } else {
                    DotNetClrMetadataHandle(METHOD_IMPL_TABLE, role.ordinal + 101)
                }
            }
            validateDotNetGenericOwnerPeRoleBijection(
                methodDefClaims = listOf(firstKey to firstMethods, secondKey to secondMethods)
                    .flatMap { familyAndMethods ->
                        familyAndMethods.second.map { entry ->
                            DotNetGenericOwnerPeMethodDefRoleClaim(
                                familyAndMethods.first,
                                entry.key,
                                entry.value,
                            )
                        }
                    },
                methodImplClaims = listOf(firstKey to firstMethodImpls, secondKey to secondMethodImpls)
                    .flatMap { familyAndMethods ->
                        familyAndMethods.second.map { entry ->
                            DotNetGenericOwnerPeMethodImplRoleClaim(
                                familyAndMethods.first,
                                entry.key,
                                entry.value,
                            )
                        }
                    },
            )
        }

        validatePair(
            secondKey = firstKey.copy(
                implementationOwnerKey = "demo/OtherValue|class",
                implementationMemberKey = "demo/OtherValue.value|function",
            ),
            sharedMethodRoles = setOf(
                DotNetProducerGenericOwnerSealedMethodDefRole.NATURAL_INTERFACE_SLOT,
                DotNetProducerGenericOwnerSealedMethodDefRole.INTERFACE_SEMANTIC_CAPABILITY_SLOT,
            ),
            sharedMethodImplRoles = emptySet(),
        )
        validatePair(
            secondKey = firstKey.copy(
                logicalInterfaceMemberKey = "demo/OtherProducer.value|function",
            ),
            sharedMethodRoles = setOf(
                DotNetProducerGenericOwnerSealedMethodDefRole.IMPLEMENTATION_TYPED_ENTRY,
                DotNetProducerGenericOwnerSealedMethodDefRole.CLASS_SEMANTIC_CAPABILITY_SLOT,
                DotNetProducerGenericOwnerSealedMethodDefRole.CLASS_SEMANTIC_CAPABILITY_DISPATCHER,
            ),
            sharedMethodImplRoles = setOf(
                DotNetProducerGenericOwnerSealedMethodImplRole
                    .CLASS_SEMANTIC_CAPABILITY_IMPLEMENTATION,
            ),
        )
    }

    @Test
    fun roleBijectionRejectsOnePhysicalRowClaimedForDifferentRoles() {
        val firstKey = metadataFixture(methodArity = 0).publication.key
        val secondKey = firstKey.copy(
            logicalInterfaceMemberKey = "demo/OtherProducer.value|function",
            implementationOwnerKey = "demo/OtherValue|class",
            implementationMemberKey = "demo/OtherValue.value|function",
        )
        val sharedMethodDef = DotNetClrMetadataHandle(METHOD_DEF_TABLE, 1)
        assertFailsWith<IllegalArgumentException> {
            validateDotNetGenericOwnerPeRoleBijection(
                methodDefClaims = listOf(
                    DotNetGenericOwnerPeMethodDefRoleClaim(
                        firstKey,
                        DotNetProducerGenericOwnerSealedMethodDefRole.NATURAL_INTERFACE_SLOT,
                        sharedMethodDef,
                    ),
                    DotNetGenericOwnerPeMethodDefRoleClaim(
                        secondKey,
                        DotNetProducerGenericOwnerSealedMethodDefRole.IMPLEMENTATION_TYPED_ENTRY,
                        sharedMethodDef,
                    ),
                ),
                methodImplClaims = emptyList(),
            )
        }

        val sharedMethodImpl = DotNetClrMetadataHandle(METHOD_IMPL_TABLE, 1)
        assertFailsWith<IllegalArgumentException> {
            validateDotNetGenericOwnerPeRoleBijection(
                methodDefClaims = emptyList(),
                methodImplClaims = listOf(
                    DotNetGenericOwnerPeMethodImplRoleClaim(
                        firstKey,
                        DotNetProducerGenericOwnerSealedMethodImplRole
                            .CLASS_SEMANTIC_CAPABILITY_IMPLEMENTATION,
                        sharedMethodImpl,
                    ),
                    DotNetGenericOwnerPeMethodImplRoleClaim(
                        secondKey,
                        DotNetProducerGenericOwnerSealedMethodImplRole
                            .INTERFACE_SEMANTIC_CAPABILITY_IMPLEMENTATION,
                        sharedMethodImpl,
                    ),
                ),
            )
        }
    }

    @Test
    fun validatesSplitNullableParamRowsForEveryJMethodDef() {
        val fixture = metadataFixture(methodArity = 0, splitNullableResults = true)
        fixture.bindFamily()

        assertEquals(2, fixture.assembly.parameterDefinitions.size)
        fixture.assembly.parameterDefinitions.forEachIndexed { parameterIndex, selectedParameter ->
            listOf(
                fixture.assembly.copy(
                    parameterDefinitions = fixture.assembly.parameterDefinitions - selectedParameter,
                ),
                fixture.assembly.copy(
                    parameterDefinitions = fixture.assembly.parameterDefinitions.map { parameter ->
                        if (parameter.handle == selectedParameter.handle) {
                            parameter.copy(attributes = 0)
                        } else {
                            parameter
                        }
                    },
                ),
                fixture.assembly.copy(
                    parameterDefinitions = fixture.assembly.parameterDefinitions.map { parameter ->
                        if (parameter.handle == selectedParameter.handle) {
                            parameter.copy(sequence = 0)
                        } else {
                            parameter
                        }
                    },
                ),
            ).forEachIndexed { hostileIndex, hostile ->
                assertFailsWith<IllegalArgumentException>(
                    "split-nullable Param-row hostile $parameterIndex/$hostileIndex was accepted",
                ) {
                    fixture.bindFamily(hostile)
                }
            }
        }
    }

    @Test
    fun rejectsRepresentativeHostilePeFactsAtTheirOwningValidationStage() {
        val fixture = metadataFixture(methodArity = 0)
        val implementationType = fixture.type(
            DotNetProducerGenericOwnerSealedTypeDefRole.IMPLEMENTATION_CLASS,
        )
        val classDispatcher = fixture.method(
            DotNetProducerGenericOwnerSealedMethodDefRole.CLASS_SEMANTIC_CAPABILITY_DISPATCHER,
        )
        val interfaceDispatcher = fixture.method(
            DotNetProducerGenericOwnerSealedMethodDefRole.INTERFACE_SEMANTIC_CAPABILITY_DISPATCHER,
        )

        fun withType(
            handle: DotNetClrMetadataHandle,
            transform: (DotNetClrTypeDefinition) -> DotNetClrTypeDefinition,
        ): DotNetClrAssemblyMetadata = fixture.assembly.copy(
            typeDefinitions = fixture.assembly.typeDefinitions.map { type ->
                if (type.handle == handle) transform(type) else type
            },
        )

        fun withMethod(
            handle: DotNetClrMetadataHandle,
            transform: (DotNetClrMethodDefinition) -> DotNetClrMethodDefinition,
        ): DotNetClrAssemblyMetadata = fixture.assembly.copy(
            methodDefinitions = fixture.assembly.methodDefinitions.map { method ->
                if (method.handle == handle) transform(method) else method
            },
        )

        fun withBody(
            handle: DotNetClrMetadataHandle,
            transform: (DotNetClrMethodBody) -> DotNetClrMethodBody,
        ): DotNetClrAssemblyMetadata = fixture.assembly.copy(
            methodBodies = fixture.assembly.methodBodies.map { body ->
                if (body.method == handle) transform(body) else body
            },
        )

        data class Hostile(
            val description: String,
            val metadata: DotNetClrAssemblyMetadata,
            val failsWhileBindingFamily: Boolean,
        )

        val firstMethodImpl = fixture.assembly.methodImplementations.first()
        val secondMethodImpl = fixture.assembly.methodImplementations.last()
        val hostiles = listOf(
            Hostile(
                "TypeDef flags",
                withType(implementationType) { type ->
                    type.copy(attributes = type.attributes and 0x0100L.inv())
                },
                failsWhileBindingFamily = true,
            ),
            Hostile(
                "TypeDef direct edge",
                fixture.assembly.copy(
                    interfaceImplementations = fixture.assembly.interfaceImplementations.dropLast(1),
                ),
                failsWhileBindingFamily = true,
            ),
            Hostile(
                "TypeDef generic arity",
                fixture.assembly.copy(
                    genericParameterDefinitions = fixture.assembly.genericParameterDefinitions.filterNot { parameter ->
                        parameter.owner == implementationType
                    },
                ),
                failsWhileBindingFamily = true,
            ),
            Hostile(
                "MethodDef flags",
                withMethod(classDispatcher) { method ->
                    method.copy(attributes = method.attributes and 0x0020.inv())
                },
                failsWhileBindingFamily = true,
            ),
            Hostile(
                "MethodDef signature",
                withMethod(interfaceDispatcher) { method ->
                    method.copy(signature = method.signature.copy(
                        returnType = DotNetClrTypeSignature.Primitive(DotNetClrPrimitiveType.STRING),
                    ))
                },
                failsWhileBindingFamily = true,
            ),
            Hostile(
                "MethodDef RVA",
                withMethod(classDispatcher) { method -> method.copy(relativeVirtualAddress = 0) },
                failsWhileBindingFamily = true,
            ),
            Hostile(
                "extra MethodImpl",
                fixture.assembly.copy(
                    methodImplementations = fixture.assembly.methodImplementations + firstMethodImpl.copy(
                        handle = DotNetClrMetadataHandle(METHOD_IMPL_TABLE, 99),
                        declarationMethod = secondMethodImpl.declarationMethod,
                    ),
                ),
                failsWhileBindingFamily = true,
            ),
            Hostile(
                "missing K body",
                fixture.assembly.copy(
                    methodBodies = fixture.assembly.methodBodies.filterNot { body ->
                        body.method == classDispatcher
                    },
                ),
                failsWhileBindingFamily = false,
            ),
            Hostile(
                "duplicate K body",
                fixture.assembly.copy(
                    methodBodies = fixture.assembly.methodBodies + fixture.body(classDispatcher),
                ),
                failsWhileBindingFamily = false,
            ),
            Hostile(
                "nonempty local signature",
                withBody(classDispatcher) { body -> body.copy(
                    initLocals = true,
                    localVariableSignature = DotNetClrMetadataHandle(17, 1),
                ) },
                failsWhileBindingFamily = false,
            ),
            Hostile(
                "exception-handling section",
                withBody(classDispatcher) { body -> body.copy(hasExtraSections = true) },
                failsWhileBindingFamily = false,
            ),
        )

        hostiles.forEach { hostile ->
            if (hostile.failsWhileBindingFamily) {
                assertFailsWith<IllegalArgumentException>(hostile.description) {
                    fixture.bindFamily(hostile.metadata)
                }
            } else {
                val family = fixture.bindFamily(hostile.metadata)
                assertFailsWith<IllegalArgumentException>(hostile.description) {
                    fixture.bindCertificate(family, hostile.metadata)
                }
            }
        }
    }

    private data class MetadataFixture(
        val publication: DotNetProducerGenericOwnerSealedFamilyPublication,
        val familyDeclaration: DotNetPhysicalDeclaration.GenericOwnerSealedFamily,
        val certificateDeclaration: DotNetPhysicalDeclaration.GenericOwnerSemanticEquivalenceCertificate,
        val assembly: DotNetClrAssemblyMetadata,
        val typeHandles: Map<DotNetProducerGenericOwnerSealedTypeDefRole, DotNetClrMetadataHandle>,
        val methodHandles: Map<DotNetProducerGenericOwnerSealedMethodDefRole, DotNetClrMetadataHandle>,
        val callTargetHandles: Map<DotNetProducerGenericOwnerSealedMethodDefRole, DotNetClrMetadataHandle>,
        val boxTypeSpecification: DotNetClrMetadataHandle,
    ) {
        fun type(role: DotNetProducerGenericOwnerSealedTypeDefRole): DotNetClrMetadataHandle =
            typeHandles.getValue(role)

        fun method(role: DotNetProducerGenericOwnerSealedMethodDefRole): DotNetClrMetadataHandle =
            methodHandles.getValue(role)

        fun callTarget(role: DotNetProducerGenericOwnerSealedMethodDefRole): DotNetClrMetadataHandle =
            callTargetHandles.getValue(role)

        fun methodDefinition(role: DotNetProducerGenericOwnerSealedMethodDefRole): DotNetClrMethodDefinition =
            assembly.methodDefinitions.single { method -> method.handle == method(role) }

        fun body(method: DotNetClrMetadataHandle): DotNetClrMethodBody =
            assembly.methodBodies.single { body -> body.method == method }

        fun methodSpecificationCalling(
            role: DotNetProducerGenericOwnerSealedMethodDefRole,
        ): DotNetClrMethodSpecification = assembly.methodSpecifications.single { specification ->
            specification.method == callTarget(role)
        }

        fun bindFamily(
            metadata: DotNetClrAssemblyMetadata = assembly,
        ): DotNetGenericOwnerSealedFamilyMetadataBinding =
            validateDotNetGenericOwnerSealedFamilyAgainstClrMetadata(
                familyDeclaration,
                metadata,
                CORE_LIBRARY,
            )

        fun bindCertificate(
            family: DotNetGenericOwnerSealedFamilyMetadataBinding,
            metadata: DotNetClrAssemblyMetadata = assembly,
        ): DotNetGenericOwnerSemanticEquivalenceMetadataBinding =
            validateDotNetGenericOwnerSemanticEquivalenceCertificateAgainstClrMetadata(
                certificateDeclaration,
                family,
                metadata,
            )
    }

    private fun metadataFixture(
        methodArity: Int,
        splitNullableResults: Boolean = false,
    ): MetadataFixture {
        require(methodArity in 0..1)
        val publication = producerSealedFamilyPublicationFixture().withResultAndMethodArity(
            methodArity = methodArity,
            splitNullableResults = splitNullableResults,
        )
        val familyDeclaration = publication.toPhysicalDeclaration()
        val certificateDeclaration = DotNetProducerGenericOwnerSemanticEquivalenceCertificate
            .finalConcreteDirectTypedEntryChain(familyDeclaration.indexKey())
            .toPhysicalDeclaration()
        val typeHandles = publication.body.typeDefs.associate { type ->
            type.role to DotNetClrMetadataHandle(TYPE_DEF_TABLE, type.role.ordinal + 1)
        }
        val methodHandles = publication.body.methodDefs.associate { method ->
            method.role to DotNetClrMetadataHandle(METHOD_DEF_TABLE, method.role.ordinal + 1)
        }
        val typesByKey = publication.body.typeDefs.associate { type ->
            type.row.structural.identityKey to typeHandles.getValue(type.role)
        }
        val methodsByKey = publication.body.methodDefs.associate { method ->
            method.row.structural.identityKey to methodHandles.getValue(method.role)
        }
        val typeCategories = publication.body.typeDefs.associate { type ->
            type.row.structural.identityKey to type.row.structural.category
        }
        val coreAssemblyReference = DotNetClrAssemblyReference(
            handle = DotNetClrMetadataHandle(ASSEMBLY_REF_TABLE, 1),
            name = CORE_LIBRARY,
            version = "10.0.0.0",
            culture = "neutral",
            flags = 0,
            publicKeyOrToken = emptyList(),
            hashValue = emptyList(),
        )
        val systemObject = DotNetClrTypeReference(
            handle = DotNetClrMetadataHandle(TYPE_REF_TABLE, 1),
            namespaceName = "System",
            metadataName = "Object",
            resolutionScope = coreAssemblyReference.handle,
        )
        val typeDefinitions = publication.body.typeDefs.map { type ->
            val flatPath = type.row.physicalPath.single()
            val separator = flatPath.lastIndexOf('.')
            DotNetClrTypeDefinition(
                handle = typeHandles.getValue(type.role),
                namespaceName = if (separator < 0) "" else flatPath.substring(0, separator),
                metadataName = flatPath.substring(separator + 1),
                attributes = type.row.flags.toTestClrAttributes(),
                baseType = if (type.role == DotNetProducerGenericOwnerSealedTypeDefRole.IMPLEMENTATION_CLASS) {
                    systemObject.handle
                } else {
                    null
                },
                declaringType = null,
            )
        }
        val naturalConstruction = DotNetClrTypeSpecification(
            handle = DotNetClrMetadataHandle(TYPE_SPEC_TABLE, 1),
            signature = DotNetClrTypeSignature.GenericInstance(
                DotNetClrTypeSignature.Named(
                    typeHandles.getValue(DotNetProducerGenericOwnerSealedTypeDefRole.NATURAL_INTERFACE),
                    isValueType = false,
                ),
                listOf(DotNetClrTypeSignature.GenericParameter(DotNetClrGenericParameterKind.TYPE, 0)),
            ),
            rawSignature = emptyList(),
        )
        val boxType = DotNetClrTypeSpecification(
            handle = DotNetClrMetadataHandle(TYPE_SPEC_TABLE, 2),
            signature = DotNetClrTypeSignature.GenericParameter(DotNetClrGenericParameterKind.TYPE, 0),
            rawSignature = emptyList(),
        )
        val implementationConstruction = DotNetClrTypeSpecification(
            handle = DotNetClrMetadataHandle(TYPE_SPEC_TABLE, 3),
            signature = DotNetClrTypeSignature.GenericInstance(
                DotNetClrTypeSignature.Named(
                    typeHandles.getValue(DotNetProducerGenericOwnerSealedTypeDefRole.IMPLEMENTATION_CLASS),
                    isValueType = false,
                ),
                listOf(DotNetClrTypeSignature.GenericParameter(DotNetClrGenericParameterKind.TYPE, 0)),
            ),
            rawSignature = emptyList(),
        )
        val interfaceImplementations = listOf(
            DotNetClrInterfaceImplementation(
                DotNetClrMetadataHandle(INTERFACE_IMPL_TABLE, 1),
                typeHandles.getValue(DotNetProducerGenericOwnerSealedTypeDefRole.IMPLEMENTATION_CLASS),
                naturalConstruction.handle,
            ),
            DotNetClrInterfaceImplementation(
                DotNetClrMetadataHandle(INTERFACE_IMPL_TABLE, 2),
                typeHandles.getValue(DotNetProducerGenericOwnerSealedTypeDefRole.IMPLEMENTATION_CLASS),
                typeHandles.getValue(DotNetProducerGenericOwnerSealedTypeDefRole.INTERFACE_SEMANTIC_CAPABILITY),
            ),
            DotNetClrInterfaceImplementation(
                DotNetClrMetadataHandle(INTERFACE_IMPL_TABLE, 3),
                typeHandles.getValue(DotNetProducerGenericOwnerSealedTypeDefRole.IMPLEMENTATION_CLASS),
                typeHandles.getValue(DotNetProducerGenericOwnerSealedTypeDefRole.CLASS_SEMANTIC_CAPABILITY),
            ),
            DotNetClrInterfaceImplementation(
                DotNetClrMetadataHandle(INTERFACE_IMPL_TABLE, 4),
                typeHandles.getValue(DotNetProducerGenericOwnerSealedTypeDefRole.CLASS_SEMANTIC_CAPABILITY),
                typeHandles.getValue(DotNetProducerGenericOwnerSealedTypeDefRole.INTERFACE_SEMANTIC_CAPABILITY),
            ),
        )
        val methodDefinitions = publication.body.methodDefs.map { method ->
            val header = method.row.structural.header
            val methodKey = method.row.structural.identityKey
            DotNetClrMethodDefinition(
                handle = methodHandles.getValue(method.role),
                declaringType = typeHandles.getValue(method.role.ownerRoleForTest),
                name = method.row.physicalName,
                relativeVirtualAddress = if (method.row.dispatch.isAbstract) 0 else method.role.ordinal + 1L,
                implementationAttributes = 0,
                attributes = method.row.toTestClrAttributes(),
                signature = DotNetClrMethodSignature(
                    callingConvention = DotNetClrSignatureCallingConvention.DEFAULT,
                    hasThis = header.isInstance,
                    hasExplicitThis = false,
                    genericParameterCount = header.genericArity,
                    returnType = when (val result = header.result) {
                        DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.Void ->
                            DotNetClrTypeSignature.Void
                        is DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.Direct ->
                            result.carrier.toTestClrSignature(header.owner, methodKey, typesByKey, typeCategories)
                        is DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.SplitNullable ->
                            result.payload.toTestClrSignature(header.owner, methodKey, typesByKey, typeCategories)
                    },
                    parameterTypes = header.ordinaryParameterCarriers.map { carrier ->
                        carrier.toTestClrSignature(header.owner, methodKey, typesByKey, typeCategories)
                    } + if (header.result is
                            DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.SplitNullable
                    ) {
                        listOf(DotNetClrTypeSignature.ByReference(
                            DotNetClrTypeSignature.Primitive(DotNetClrPrimitiveType.BOOLEAN),
                        ))
                    } else {
                        emptyList()
                    },
                    varargParameterStart = null,
                ),
                rawSignature = emptyList(),
            )
        }
        val genericParameters = buildList {
            publication.body.typeDefs.forEach { type ->
                type.row.structural.genericParameters.forEachIndexed { index, parameter ->
                    add(DotNetClrGenericParameterDefinition(
                        handle = DotNetClrMetadataHandle(GENERIC_PARAMETER_TABLE, size + 1),
                        number = index,
                        attributes = parameter.variance.ordinal,
                        owner = typeHandles.getValue(type.role),
                        name = "T$index",
                    ))
                }
            }
            publication.body.methodDefs.forEach { method ->
                method.row.structural.genericParameters.forEachIndexed { index, parameter ->
                    add(DotNetClrGenericParameterDefinition(
                        handle = DotNetClrMetadataHandle(GENERIC_PARAMETER_TABLE, size + 1),
                        number = index,
                        attributes = parameter.variance.ordinal,
                        owner = methodHandles.getValue(method.role),
                        name = method.row.physicalGenericParameterNames[index],
                    ))
                }
            }
        }
        val splitNullableParameters = publication.body.methodDefs.mapIndexedNotNull { index, method ->
            if (method.row.structural.header.result !is
                    DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.SplitNullable
            ) return@mapIndexedNotNull null
            val methodDefinition = methodDefinitions.single { definition ->
                definition.handle == methodHandles.getValue(method.role)
            }
            DotNetClrParameterDefinition(
                handle = DotNetClrMetadataHandle(PARAM_TABLE, index + 1),
                declaringMethod = methodDefinition.handle,
                sequence = methodDefinition.signature.parameterTypes.size,
                name = "isNull",
                attributes = OUT_PARAMETER_ATTRIBUTE,
            )
        }
        val methodReferences = publication.body.methodImpls.mapIndexed { index, implementation ->
            val declaration = publication.body.methodDefs.single { method ->
                method.row.structural.identityKey == implementation.row.declarationMethodDefKey
            }
            val owner = typesByKey.getValue(
                (implementation.row.declarationOwner as
                        DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.Construction).definition,
            )
            val methodDefinition = methodDefinitions.single { method ->
                method.handle == methodsByKey.getValue(implementation.row.declarationMethodDefKey)
            }
            DotNetClrMemberReference(
                handle = DotNetClrMetadataHandle(MEMBER_REF_TABLE, index + 1),
                parent = owner,
                name = declaration.row.physicalName,
                signature = DotNetClrMemberReferenceSignature.Method(methodDefinition.signature),
                rawSignature = emptyList(),
            )
        }
        val methodImplementations = publication.body.methodImpls.mapIndexed { index, implementation ->
            DotNetClrMethodImplementation(
                handle = DotNetClrMetadataHandle(METHOD_IMPL_TABLE, index + 1),
                implementingType = typesByKey.getValue(implementation.row.implementingTypeDefKey),
                bodyMethod = methodsByKey.getValue(implementation.row.bodyMethodDefKey),
                declarationMethod = methodReferences[index].handle,
            )
        }
        val callTargetRoles = listOf(
            DotNetProducerGenericOwnerSealedMethodDefRole.IMPLEMENTATION_TYPED_ENTRY,
            DotNetProducerGenericOwnerSealedMethodDefRole.CLASS_SEMANTIC_CAPABILITY_DISPATCHER,
        )
        val callReferences = callTargetRoles.mapIndexed { index, role ->
            val target = methodDefinitions.single { method ->
                method.handle == methodHandles.getValue(role)
            }
            role to DotNetClrMemberReference(
                handle = DotNetClrMetadataHandle(MEMBER_REF_TABLE, methodReferences.size + index + 1),
                parent = implementationConstruction.handle,
                name = target.name,
                signature = DotNetClrMemberReferenceSignature.Method(target.signature),
                rawSignature = emptyList(),
            )
        }.toMap()
        var nextMethodSpecRow = 1
        fun callToken(role: DotNetProducerGenericOwnerSealedMethodDefRole): Pair<DotNetClrMetadataHandle, DotNetClrMethodSpecification?> {
            val target = callReferences.getValue(role).handle
            if (methodArity == 0) return target to null
            val specification = DotNetClrMethodSpecification(
                handle = DotNetClrMetadataHandle(METHOD_SPEC_TABLE, nextMethodSpecRow++),
                method = target,
                typeArguments = listOf(DotNetClrTypeSignature.GenericParameter(
                    DotNetClrGenericParameterKind.METHOD,
                    0,
                )),
                rawInstantiation = DotNetClrBlob.copyOf(byteArrayOf()),
            )
            return specification.handle to specification
        }
        val classCall = callToken(DotNetProducerGenericOwnerSealedMethodDefRole.IMPLEMENTATION_TYPED_ENTRY)
        val interfaceCall = callToken(
            DotNetProducerGenericOwnerSealedMethodDefRole.CLASS_SEMANTIC_CAPABILITY_DISPATCHER,
        )
        val methodSpecifications = listOfNotNull(classCall.second, interfaceCall.second)
        val classCode = buildList {
            add(CIL_LDARG_0)
            if (methodArity == 1) add(CIL_LDARG_1)
            add(CIL_CALLVIRT)
            addToken(classCall.first)
            add(CIL_BOX)
            addToken(boxType.handle)
            add(CIL_RET)
        }
        val interfaceCode = buildList {
            add(CIL_LDARG_0)
            if (methodArity == 1) add(CIL_LDARG_1)
            add(CIL_CALLVIRT)
            addToken(interfaceCall.first)
            add(CIL_RET)
        }
        fun body(role: DotNetProducerGenericOwnerSealedMethodDefRole, code: List<Int>) =
            DotNetClrMethodBody(
                method = methodHandles.getValue(role),
                isTiny = false,
                headerSize = 12,
                maxStack = methodArity + 1,
                initLocals = false,
                localVariableSignature = null,
                hasExtraSections = false,
                code = DotNetClrBlob.copyOf(code.map(Int::toByte).toByteArray()),
            )
        val assembly = DotNetClrAssemblyMetadata(
            identity = DotNetManagedAssemblyIdentity(
                name = "SemanticEquivalenceFixture",
                version = "1.0.0.0",
                culture = "neutral",
                publicKey = emptyList(),
                publicKeyToken = emptyList(),
            ),
            assemblyReferences = listOf(coreAssemblyReference),
            typeReferences = listOf(systemObject),
            typeDefinitions = typeDefinitions,
            interfaceImplementations = interfaceImplementations,
            exportedTypes = emptyList(),
            typeSpecifications = listOf(naturalConstruction, boxType, implementationConstruction),
            fieldDefinitions = emptyList(),
            methodDefinitions = methodDefinitions,
            parameterDefinitions = splitNullableParameters,
            constantDefinitions = emptyList(),
            fieldMarshalDefinitions = emptyList(),
            memberReferences = methodReferences + callReferences.values,
            customAttributes = emptyList(),
            propertyDefinitions = emptyList(),
            methodSemantics = emptyList(),
            genericParameterDefinitions = genericParameters,
            genericParameterConstraints = emptyList(),
            methodImplementations = methodImplementations,
            methodSpecifications = methodSpecifications,
            methodBodies = listOf(
                body(
                    DotNetProducerGenericOwnerSealedMethodDefRole.CLASS_SEMANTIC_CAPABILITY_DISPATCHER,
                    classCode,
                ),
                body(
                    DotNetProducerGenericOwnerSealedMethodDefRole.INTERFACE_SEMANTIC_CAPABILITY_DISPATCHER,
                    interfaceCode,
                ),
            ),
            hasCompleteMethodSpecifications = true,
        )
        return MetadataFixture(
            publication,
            familyDeclaration,
            certificateDeclaration,
            assembly,
            typeHandles,
            methodHandles,
            callReferences.mapValues { entry -> entry.value.handle },
            boxType.handle,
        )
    }

    private fun DotNetProducerGenericOwnerSealedFamilyPublication.withResultAndMethodArity(
        methodArity: Int,
        splitNullableResults: Boolean,
    ): DotNetProducerGenericOwnerSealedFamilyPublication = copy(body = body.copy(
        methodDefs = body.methodDefs.map { method ->
            val oldStructural = method.row.structural
            val result = when (val oldResult = oldStructural.header.result) {
                is DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.SplitNullable -> if (
                    splitNullableResults
                ) {
                    oldResult
                } else {
                    DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.Direct(oldResult.payload)
                }
                else -> oldResult
            }
            method.copy(
                row = method.row.copy(
                    structural = oldStructural.copy(
                        header = oldStructural.header.copy(
                            genericArity = methodArity,
                            ordinaryParameterCarriers = if (methodArity == 0) {
                                emptyList()
                            } else {
                                oldStructural.header.ordinaryParameterCarriers
                            },
                            result = result,
                        ),
                        genericParameters = if (methodArity == 0) emptyList() else oldStructural.genericParameters,
                    ),
                    physicalGenericParameterNames = if (methodArity == 0) {
                        emptyList()
                    } else {
                        method.row.physicalGenericParameterNames
                    },
                ),
                logicalParameterDomains = if (methodArity == 0) emptyList() else method.logicalParameterDomains,
            )
        },
    ))

    private fun DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.toTestClrSignature(
        currentOwner: DotNetGenericOwnerPhysicalMethodDefEmissionTypeKey,
        currentMethod: DotNetGenericOwnerPhysicalMethodDefEmissionMethodKey,
        typesByKey: Map<DotNetGenericOwnerPhysicalMethodDefEmissionTypeKey, DotNetClrMetadataHandle>,
        typeCategories: Map<DotNetGenericOwnerPhysicalMethodDefEmissionTypeKey,
                DotNetGenericOwnerPhysicalNamedTypeCategory>,
    ): DotNetClrTypeSignature = when (this) {
        is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.Leaf -> when (kind) {
            DotNetGenericOwnerPhysicalTypeKind.VOID -> DotNetClrTypeSignature.Void
            DotNetGenericOwnerPhysicalTypeKind.BOOLEAN ->
                DotNetClrTypeSignature.Primitive(DotNetClrPrimitiveType.BOOLEAN)
            DotNetGenericOwnerPhysicalTypeKind.INT32 ->
                DotNetClrTypeSignature.Primitive(DotNetClrPrimitiveType.INT32)
            DotNetGenericOwnerPhysicalTypeKind.STRING ->
                DotNetClrTypeSignature.Primitive(DotNetClrPrimitiveType.STRING)
            DotNetGenericOwnerPhysicalTypeKind.OBJECT ->
                DotNetClrTypeSignature.Primitive(DotNetClrPrimitiveType.OBJECT)
            else -> error("unsupported synthetic fixture leaf $kind")
        }
        is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.OwnerParameter -> {
            require(binder == currentOwner)
            DotNetClrTypeSignature.GenericParameter(DotNetClrGenericParameterKind.TYPE, index)
        }
        is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.MethodParameter -> {
            require(binder == currentMethod)
            DotNetClrTypeSignature.GenericParameter(DotNetClrGenericParameterKind.METHOD, index)
        }
        is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.Construction -> {
            val named = DotNetClrTypeSignature.Named(
                typesByKey.getValue(definition),
                typeCategories.getValue(definition) == DotNetGenericOwnerPhysicalNamedTypeCategory.VALUE_TYPE,
            )
            if (arguments.isEmpty()) {
                named
            } else {
                DotNetClrTypeSignature.GenericInstance(
                    named,
                    arguments.map { argument ->
                        argument.toTestClrSignature(currentOwner, currentMethod, typesByKey, typeCategories)
                    },
                )
            }
        }
        is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.SzArray ->
            DotNetClrTypeSignature.SzArray(
                element.toTestClrSignature(currentOwner, currentMethod, typesByKey, typeCategories),
            )
        is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.ByReference ->
            DotNetClrTypeSignature.ByReference(
                element.toTestClrSignature(currentOwner, currentMethod, typesByKey, typeCategories),
            )
        DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.Other ->
            error("unsupported synthetic fixture carrier")
    }

    private fun DotNetIlRawTypeDefFlags.toTestClrAttributes(): Long =
        when (visibility) {
            DotNetIlRawTypeDefVisibility.NOT_PUBLIC -> 0L
            DotNetIlRawTypeDefVisibility.PUBLIC -> 1L
            DotNetIlRawTypeDefVisibility.NESTED_PUBLIC -> 2L
            DotNetIlRawTypeDefVisibility.NESTED_PRIVATE -> 3L
            DotNetIlRawTypeDefVisibility.NESTED_FAMILY -> 4L
            DotNetIlRawTypeDefVisibility.NESTED_ASSEMBLY -> 5L
        } or (if (isInterface) 0x20L else 0L) or
                (if (isAbstract) 0x80L else 0L) or
                (if (isSealed) 0x100L else 0L) or
                (if (isBeforeFieldInit) 0x0010_0000L else 0L)

    private fun DotNetGenericOwnerSealedEmissionMethodDefRow.toTestClrAttributes(): Int =
        when (visibility) {
            DotNetIlRawMethodDefVisibility.PRIVATE -> 1
            DotNetIlRawMethodDefVisibility.FAMILY_AND_ASSEMBLY -> 2
            DotNetIlRawMethodDefVisibility.ASSEMBLY -> 3
            DotNetIlRawMethodDefVisibility.FAMILY -> 4
            DotNetIlRawMethodDefVisibility.FAMILY_OR_ASSEMBLY -> 5
            DotNetIlRawMethodDefVisibility.PUBLIC -> 6
        } or (if (!dispatch.isInstance) 0x10 else 0) or
                (if (dispatch.isFinal) 0x20 else 0) or
                (if (dispatch.isVirtual) 0x40 else 0) or
                (if (isHideBySig) 0x80 else 0) or
                (if (dispatch.isNewSlot) 0x100 else 0) or
                (if (dispatch.isAbstract) 0x400 else 0) or
                (if (isSpecialName) 0x800 else 0) or
                (if (isRuntimeSpecialName) 0x1000 else 0)

    private val DotNetProducerGenericOwnerSealedMethodDefRole.ownerRoleForTest:
            DotNetProducerGenericOwnerSealedTypeDefRole
        get() = when (this) {
            DotNetProducerGenericOwnerSealedMethodDefRole.NATURAL_INTERFACE_SLOT ->
                DotNetProducerGenericOwnerSealedTypeDefRole.NATURAL_INTERFACE
            DotNetProducerGenericOwnerSealedMethodDefRole.INTERFACE_SEMANTIC_CAPABILITY_SLOT ->
                DotNetProducerGenericOwnerSealedTypeDefRole.INTERFACE_SEMANTIC_CAPABILITY
            DotNetProducerGenericOwnerSealedMethodDefRole.IMPLEMENTATION_TYPED_ENTRY,
            DotNetProducerGenericOwnerSealedMethodDefRole.CLASS_SEMANTIC_CAPABILITY_DISPATCHER,
            DotNetProducerGenericOwnerSealedMethodDefRole.INTERFACE_SEMANTIC_CAPABILITY_DISPATCHER,
            -> DotNetProducerGenericOwnerSealedTypeDefRole.IMPLEMENTATION_CLASS
            DotNetProducerGenericOwnerSealedMethodDefRole.CLASS_SEMANTIC_CAPABILITY_SLOT ->
                DotNetProducerGenericOwnerSealedTypeDefRole.CLASS_SEMANTIC_CAPABILITY
        }

    private fun DotNetClrAssemblyMetadata.withCode(
        method: DotNetClrMetadataHandle,
        code: List<Int>,
    ): DotNetClrAssemblyMetadata = copy(methodBodies = methodBodies.map { body ->
        if (body.method == method) {
            body.copy(code = DotNetClrBlob.copyOf(code.map(Int::toByte).toByteArray()))
        } else {
            body
        }
    })

    private fun MutableList<Int>.replaceToken(start: Int, handle: DotNetClrMetadataHandle) {
        repeat(4) { index -> this[start + index] = handle.token ushr (index * 8) and 0xff }
    }

    private fun MutableList<Int>.addToken(handle: DotNetClrMetadataHandle) {
        repeat(4) { index -> add(handle.token ushr (index * 8) and 0xff) }
    }

    private inline fun <T> withTemporaryAssemblyFile(block: (File) -> T): T {
        val file = File.createTempFile("kotlin-dotnet-semantic-equivalence-", ".dll")
        return try {
            block(file)
        } finally {
            file.delete()
        }
    }

    private companion object {
        const val CORE_LIBRARY = "System.Private.CoreLib"
        const val TYPE_REF_TABLE = 1
        const val TYPE_DEF_TABLE = 2
        const val METHOD_DEF_TABLE = 6
        const val PARAM_TABLE = 8
        const val INTERFACE_IMPL_TABLE = 9
        const val MEMBER_REF_TABLE = 10
        const val METHOD_IMPL_TABLE = 25
        const val TYPE_SPEC_TABLE = 27
        const val ASSEMBLY_REF_TABLE = 35
        const val GENERIC_PARAMETER_TABLE = 42
        const val METHOD_SPEC_TABLE = 43

        const val PRIVATE_FINAL_VIRTUAL_METHOD_ATTRIBUTES = 0x00e1
        const val OUT_PARAMETER_ATTRIBUTE = 0x0002
        const val CIL_NOP = 0x00
        const val CIL_LDARG_0 = 0x02
        const val CIL_LDARG_1 = 0x03
        const val CIL_LDARG_2 = 0x04
        const val CIL_RET = 0x2a
        const val CIL_CALL = 0x28
        const val CIL_CALLI = 0x29
        const val CIL_CALLVIRT = 0x6f
        const val CIL_BOX = 0x8c
    }
}
