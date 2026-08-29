/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.load.dotnet.DotNetClrAssemblyMetadata
import org.jetbrains.kotlin.load.dotnet.DotNetClrGenericParameterConstraint
import org.jetbrains.kotlin.load.dotnet.DotNetClrGenericParameterDefinition
import org.jetbrains.kotlin.load.dotnet.DotNetClrGenericParameterKind
import org.jetbrains.kotlin.load.dotnet.DotNetClrMetadataHandle
import org.jetbrains.kotlin.load.dotnet.DotNetClrMethodDefinition
import org.jetbrains.kotlin.load.dotnet.DotNetClrMethodSignature
import org.jetbrains.kotlin.load.dotnet.DotNetClrParameterDefinition
import org.jetbrains.kotlin.load.dotnet.DotNetClrPrimitiveType
import org.jetbrains.kotlin.load.dotnet.DotNetClrSignatureCallingConvention
import org.jetbrains.kotlin.load.dotnet.DotNetClrTypeDefinition
import org.jetbrains.kotlin.load.dotnet.DotNetClrTypeSignature
import org.jetbrains.kotlin.load.dotnet.DotNetManagedAssemblyIdentity
import org.jetbrains.kotlin.types.Variance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DotNetGenericOwnerNaturalMethodDefMetadataValidatorTest {
    @Test
    fun joinsRecordedOrdinaryInputsAndDirectOwnerResultWithLogicalKlibProjection() {
        val owner = DotNetIlClassInfo(
            "sample.Source`2",
            typeParameterVariances = listOf(Variance.OUT_VARIANCE, Variance.OUT_VARIANCE),
        )
        val receiver = DotNetIlValueType.GenericInstance(
            owner,
            listOf(
                DotNetIlValueType.TypeParameter(0, false),
                DotNetIlValueType.TypeParameter(1, false),
            ),
        )
        fun recorded(
            parameter: DotNetIlValueType = DotNetIlValueType.Boolean,
            result: DotNetIlValueType = DotNetIlValueType.TypeParameter(1, false),
            split: Boolean = true,
            hasThis: Boolean = true,
        ) = DotNetIlFunctionInfo(
            owner,
            DotNetIlMethodSignature(
                returnType = DotNetIlReturnType.Value(result),
                parameterTypes = listOf(receiver, parameter),
                hasThis = hasThis,
                hasSplitNullableResult = split,
                methodGenericParameterCount = 0,
            ),
        )
        fun inspect(info: DotNetIlFunctionInfo) = inspectDotNetExternalNaturalMethodLogicalProjection(
            logicalParameterTypes = listOf(DotNetIlValueType.Boolean),
            logicalResultOwnerParameterIndex = 1,
            logicalSplitNullableResult = true,
            recordedInfo = info,
        )

        assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<Unit>>(inspect(recorded()))
        listOf(
            recorded(parameter = DotNetIlValueType.Int32),
            recorded(result = DotNetIlValueType.Object),
            recorded(result = DotNetIlValueType.TypeParameter(0, false)),
            recorded(split = false),
            recorded(hasThis = false),
        ).forEach { hostile ->
            assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(inspect(hostile))
        }
    }

    @Test
    fun bindsTheExactMethodDefAmongSameNameSameRegularArityOverloads() {
        val fixture = metadataFixture(includeDecoy = true)
        val binding = validateFixture(fixture.assembly)

        assertEquals(fixture.targetMethod, binding.methodDefinition.handle)
        assertEquals(fixture.targetSplitParameter, binding.splitNullableOutParameter?.handle)
    }

    @Test
    fun bindsTheExactDirectMethodDefAmongSameNameSameRegularArityOverloads() {
        val fixture = metadataFixture(includeDecoy = true)
        val assembly = fixture.assembly.copy(
            methodDefinitions = fixture.assembly.methodDefinitions.map { method ->
                method.copy(signature = method.signature.copy(
                    parameterTypes = method.signature.parameterTypes.dropLast(1),
                ))
            },
            parameterDefinitions = emptyList(),
        )
        val binding = validateDotNetGenericOwnerNaturalMethodDefAgainstClrMetadata(
            declaration = directNaturalMethodDefDeclaration(),
            assembly = assembly,
            coreLibraryAssemblyName = CORE_LIBRARY,
        )

        assertEquals(fixture.targetMethod, binding.methodDefinition.handle)
        assertNull(binding.splitNullableOutParameter)
    }

    @Test
    fun rejectsMalformedSplitTailAndAmbiguousFullMatches() {
        val fixture = metadataFixture()
        val declaration = producerSealedFamilyPublicationFixture()
            .let { publication ->
                publication.toNaturalMethodDefPhysicalDeclaration("demo/Source|class")
            }

        val wrongTail = fixture.assembly.copy(methodDefinitions = listOf(
            fixture.assembly.methodDefinitions.single().copy(
                signature = fixture.assembly.methodDefinitions.single().signature.copy(
                    parameterTypes = listOf(
                        DotNetClrTypeSignature.GenericParameter(
                            DotNetClrGenericParameterKind.METHOD,
                            0,
                        ),
                        DotNetClrTypeSignature.ByReference(
                            DotNetClrTypeSignature.Primitive(DotNetClrPrimitiveType.INT32),
                        ),
                    ),
                ),
            ),
        ))
        assertFailsWith<IllegalArgumentException> {
            validateDotNetGenericOwnerNaturalMethodDefTokenAgainstClrMetadata(
                declaration.logicalMemberKey,
                declaration.physicalMethod,
                wrongTail,
                CORE_LIBRARY,
            )
        }

        val missingOut = fixture.assembly.copy(parameterDefinitions =
            fixture.assembly.parameterDefinitions.map { parameter -> parameter.copy(attributes = 0) }
        )
        assertFailsWith<IllegalArgumentException> {
            validateDotNetGenericOwnerNaturalMethodDefTokenAgainstClrMetadata(
                declaration.logicalMemberKey,
                declaration.physicalMethod,
                missingOut,
                CORE_LIBRARY,
            )
        }

        val ambiguous = metadataFixture(includeDuplicateTarget = true).assembly
        val ambiguity = assertFailsWith<IllegalArgumentException> {
            validateDotNetGenericOwnerNaturalMethodDefTokenAgainstClrMetadata(
                declaration.logicalMemberKey,
                declaration.physicalMethod,
                ambiguous,
                CORE_LIBRARY,
            )
        }
        assertTrue(ambiguity.message.orEmpty().contains("ambiguous"))
    }

    @Test
    fun rejectsPeFlagsVarianceConstraintsAndOwnerArityWhichDisagreeWithTheSeal() {
        val fixture = metadataFixture()
        val assembly = fixture.assembly
        val target = assembly.methodDefinitions.single()
        val ownerParameter = assembly.genericParameterDefinitions.single { parameter ->
            parameter.owner == fixture.ownerType
        }
        val methodParameter = assembly.genericParameterDefinitions.single { parameter ->
            parameter.owner == fixture.targetMethod
        }

        val hostiles = listOf(
            assembly.copy(typeDefinitions = listOf(
                assembly.typeDefinitions.single().copy(
                    attributes = NATURAL_TYPE_ATTRIBUTES and INTERFACE_ATTRIBUTE.inv(),
                ),
            )),
            assembly.copy(typeDefinitions = listOf(
                assembly.typeDefinitions.single().copy(
                    attributes = NATURAL_TYPE_ATTRIBUTES or SEQUENTIAL_LAYOUT_ATTRIBUTE,
                ),
            )),
            assembly.copy(typeDefinitions = listOf(
                assembly.typeDefinitions.single().copy(
                    attributes = NATURAL_TYPE_ATTRIBUTES or UNICODE_STRING_FORMAT_ATTRIBUTE,
                ),
            )),
            assembly.copy(typeDefinitions = listOf(
                assembly.typeDefinitions.single().copy(
                    baseType = DotNetClrMetadataHandle(TYPE_DEF_TABLE, 2),
                ),
            )),
            assembly.copy(methodDefinitions = listOf(
                target.copy(attributes = target.attributes and NEW_SLOT_ATTRIBUTE.inv()),
            )),
            assembly.copy(methodDefinitions = listOf(
                target.copy(attributes = target.attributes and VIRTUAL_ATTRIBUTE.inv()),
            )),
            assembly.copy(genericParameterDefinitions = listOf(
                ownerParameter.copy(attributes = 0),
                methodParameter,
            )),
            assembly.copy(genericParameterConstraints = listOf(
                DotNetClrGenericParameterConstraint(
                    DotNetClrMetadataHandle(GENERIC_PARAMETER_CONSTRAINT_TABLE, 1),
                    ownerParameter.handle,
                    fixture.ownerType,
                ),
            )),
            assembly.copy(genericParameterDefinitions = listOf(
                ownerParameter,
                methodParameter.copy(attributes = REFERENCE_TYPE_CONSTRAINT_ATTRIBUTE),
            )),
            assembly.copy(genericParameterDefinitions = assembly.genericParameterDefinitions +
                    ownerParameter.copy(
                        handle = DotNetClrMetadataHandle(GENERIC_PARAMETER_TABLE, 3),
                        number = 1,
                        name = "Extra",
                    ),
            ),
        )
        hostiles.forEachIndexed { index, hostile ->
            assertFailsWith<IllegalArgumentException>("sealed metadata hostile $index was accepted") {
                validateFixture(hostile)
            }
        }
    }

    private fun directNaturalMethodDefDeclaration():
            DotNetPhysicalDeclaration.GenericOwnerNaturalMethodDef {
        val publication = producerSealedFamilyPublicationFixture()
            .toNaturalMethodDefPublication("demo/Source|class")
        val header = publication.naturalMethod.row.structural.header
        val split = header.result as DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.SplitNullable
        return publication.copy(
            naturalMethod = publication.naturalMethod.copy(
                row = publication.naturalMethod.row.copy(
                    structural = publication.naturalMethod.row.structural.copy(
                        header = header.copy(
                            result = DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.Direct(
                                split.payload,
                            ),
                        ),
                    ),
                ),
            ),
        ).toPhysicalDeclaration()
    }

    private fun validateFixture(
        assembly: DotNetClrAssemblyMetadata,
    ): DotNetGenericOwnerNaturalMethodDefMetadataBinding {
        val publication = producerSealedFamilyPublicationFixture()
        return validateDotNetGenericOwnerNaturalMethodDefAgainstClrMetadata(
            declaration = publication.toNaturalMethodDefPhysicalDeclaration("demo/Source|class"),
            assembly = assembly,
            coreLibraryAssemblyName = CORE_LIBRARY,
        )
    }

    private data class MetadataFixture(
        val assembly: DotNetClrAssemblyMetadata,
        val ownerType: DotNetClrMetadataHandle,
        val targetMethod: DotNetClrMetadataHandle,
        val targetSplitParameter: DotNetClrMetadataHandle,
    )

    private fun metadataFixture(
        includeDecoy: Boolean = false,
        includeDuplicateTarget: Boolean = false,
    ): MetadataFixture {
        val owner = DotNetClrMetadataHandle(TYPE_DEF_TABLE, 1)
        val target = DotNetClrMetadataHandle(METHOD_DEF_TABLE, 1)
        val decoy = DotNetClrMetadataHandle(METHOD_DEF_TABLE, 2)
        val duplicate = DotNetClrMetadataHandle(METHOD_DEF_TABLE, 3)

        fun method(
            handle: DotNetClrMetadataHandle,
            ordinaryParameter: DotNetClrTypeSignature,
        ) = DotNetClrMethodDefinition(
            handle = handle,
            declaringType = owner,
            name = "Read",
            relativeVirtualAddress = 0,
            implementationAttributes = 0,
            attributes = NATURAL_METHOD_ATTRIBUTES,
            signature = DotNetClrMethodSignature(
                callingConvention = DotNetClrSignatureCallingConvention.DEFAULT,
                hasThis = true,
                hasExplicitThis = false,
                genericParameterCount = 1,
                returnType = DotNetClrTypeSignature.GenericParameter(
                    DotNetClrGenericParameterKind.TYPE,
                    0,
                ),
                parameterTypes = listOf(
                    ordinaryParameter,
                    DotNetClrTypeSignature.ByReference(
                        DotNetClrTypeSignature.Primitive(DotNetClrPrimitiveType.BOOLEAN),
                    ),
                ),
                varargParameterStart = null,
            ),
            rawSignature = emptyList(),
        )

        val methods = buildList {
            add(method(
                target,
                DotNetClrTypeSignature.GenericParameter(DotNetClrGenericParameterKind.METHOD, 0),
            ))
            if (includeDecoy) {
                add(method(
                    decoy,
                    DotNetClrTypeSignature.Primitive(DotNetClrPrimitiveType.INT32),
                ))
            }
            if (includeDuplicateTarget) {
                add(method(
                    duplicate,
                    DotNetClrTypeSignature.GenericParameter(
                        DotNetClrGenericParameterKind.METHOD,
                        0,
                    ),
                ))
            }
        }
        val methodGenericParameters = methods.mapIndexed { index, method ->
            DotNetClrGenericParameterDefinition(
                handle = DotNetClrMetadataHandle(GENERIC_PARAMETER_TABLE, index + 2),
                number = 0,
                attributes = 0,
                owner = method.handle,
                name = "R",
            )
        }
        val splitParameters = methods.mapIndexed { index, method ->
            DotNetClrParameterDefinition(
                handle = DotNetClrMetadataHandle(PARAM_TABLE, index + 1),
                declaringMethod = method.handle,
                sequence = 2,
                name = "isNull",
                attributes = OUT_PARAMETER_ATTRIBUTE,
            )
        }
        val assembly = DotNetClrAssemblyMetadata(
            identity = DotNetManagedAssemblyIdentity(
                name = "Demo",
                version = "1.0.0.0",
                culture = "neutral",
                publicKey = emptyList(),
                publicKeyToken = emptyList(),
            ),
            assemblyReferences = emptyList(),
            typeReferences = emptyList(),
            typeDefinitions = listOf(DotNetClrTypeDefinition(
                handle = owner,
                namespaceName = "demo",
                metadataName = "Source`1",
                attributes = NATURAL_TYPE_ATTRIBUTES,
                baseType = null,
                declaringType = null,
            )),
            interfaceImplementations = emptyList(),
            exportedTypes = emptyList(),
            typeSpecifications = emptyList(),
            fieldDefinitions = emptyList(),
            methodDefinitions = methods,
            parameterDefinitions = splitParameters,
            constantDefinitions = emptyList(),
            fieldMarshalDefinitions = emptyList(),
            memberReferences = emptyList(),
            customAttributes = emptyList(),
            propertyDefinitions = emptyList(),
            methodSemantics = emptyList(),
            genericParameterDefinitions = listOf(DotNetClrGenericParameterDefinition(
                handle = DotNetClrMetadataHandle(GENERIC_PARAMETER_TABLE, 1),
                number = 0,
                attributes = COVARIANT_ATTRIBUTE,
                owner = owner,
                name = "T",
            )) + methodGenericParameters,
            genericParameterConstraints = emptyList(),
        )
        return MetadataFixture(
            assembly,
            owner,
            target,
            splitParameters.first().handle,
        )
    }

    private companion object {
        const val CORE_LIBRARY = "System.Private.CoreLib"
        const val TYPE_DEF_TABLE = 2
        const val METHOD_DEF_TABLE = 6
        const val PARAM_TABLE = 8
        const val GENERIC_PARAMETER_TABLE = 42
        const val GENERIC_PARAMETER_CONSTRAINT_TABLE = 44

        const val COVARIANT_ATTRIBUTE = 0x0001
        const val REFERENCE_TYPE_CONSTRAINT_ATTRIBUTE = 0x0004
        const val OUT_PARAMETER_ATTRIBUTE = 0x0002
        const val INTERFACE_ATTRIBUTE = 0x0000_0020L
        const val SEQUENTIAL_LAYOUT_ATTRIBUTE = 0x0000_0008L
        const val UNICODE_STRING_FORMAT_ATTRIBUTE = 0x0001_0000L
        const val VIRTUAL_ATTRIBUTE = 0x0040
        const val NEW_SLOT_ATTRIBUTE = 0x0100
        const val NATURAL_TYPE_ATTRIBUTES = 0x0000_00a1L
        const val NATURAL_METHOD_ATTRIBUTES = 0x05c6
    }
}
