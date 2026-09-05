/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.config.DotNetTarget
import org.jetbrains.kotlin.load.dotnet.DotNetClrAssemblyMetadata
import org.jetbrains.kotlin.load.dotnet.DotNetClrAssemblyReference
import org.jetbrains.kotlin.load.dotnet.DotNetClrArrayShape
import org.jetbrains.kotlin.load.dotnet.DotNetClrCustomModifier
import org.jetbrains.kotlin.load.dotnet.DotNetClrGenericParameterConstraint
import org.jetbrains.kotlin.load.dotnet.DotNetClrGenericParameterDefinition
import org.jetbrains.kotlin.load.dotnet.DotNetClrGenericParameterKind
import org.jetbrains.kotlin.load.dotnet.DotNetClrInterfaceImplementation
import org.jetbrains.kotlin.load.dotnet.DotNetClrMemberReference
import org.jetbrains.kotlin.load.dotnet.DotNetClrMemberReferenceSignature
import org.jetbrains.kotlin.load.dotnet.DotNetClrMetadataHandle
import org.jetbrains.kotlin.load.dotnet.DotNetClrMethodImplementation
import org.jetbrains.kotlin.load.dotnet.DotNetClrMethodDefinition
import org.jetbrains.kotlin.load.dotnet.DotNetClrMethodSignature
import org.jetbrains.kotlin.load.dotnet.DotNetClrParameterDefinition
import org.jetbrains.kotlin.load.dotnet.DotNetClrPrimitiveType
import org.jetbrains.kotlin.load.dotnet.DotNetClrSignatureCallingConvention
import org.jetbrains.kotlin.load.dotnet.DotNetClrTypeDefinition
import org.jetbrains.kotlin.load.dotnet.DotNetClrTypeReference
import org.jetbrains.kotlin.load.dotnet.DotNetClrTypeSignature
import org.jetbrains.kotlin.load.dotnet.DotNetClrTypeSpecification
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
    fun bindsExactConstructorOverloadAndAuthenticatesConstructorFlags() {
        val fixture = constructorMetadataFixture(includeStringDecoy = true)
        val binding = validateDotNetGenericOwnerConstructorMethodDefAgainstClrMetadata(
            declaration = constructorDeclaration(),
            ownerDeclaration = constructorOwnerDeclaration(),
            assembly = fixture.assembly,
            producerTarget = TARGET,
        )

        assertEquals(fixture.objectConstructor, binding.methodDefinition.handle)

        val selected = fixture.assembly.methodDefinitions.single { method ->
            method.handle == fixture.objectConstructor
        }
        listOf(
            selected.copy(attributes = selected.attributes and RUNTIME_SPECIAL_NAME_ATTRIBUTE.inv()),
            selected.copy(attributes = selected.attributes and SPECIAL_NAME_ATTRIBUTE.inv()),
            selected.copy(attributes = selected.attributes and HIDE_BY_SIG_ATTRIBUTE.inv()),
            selected.copy(attributes = selected.attributes or VIRTUAL_ATTRIBUTE),
            selected.copy(attributes = selected.attributes or NEW_SLOT_ATTRIBUTE),
            selected.copy(attributes = selected.attributes or STATIC_ATTRIBUTE),
        ).forEachIndexed { index, hostile ->
            assertFailsWith<IllegalArgumentException>("constructor flag hostile $index was accepted") {
                validateDotNetGenericOwnerConstructorMethodDefAgainstClrMetadata(
                    declaration = constructorDeclaration(),
                    ownerDeclaration = constructorOwnerDeclaration(),
                    assembly = fixture.assembly.copy(
                        methodDefinitions = fixture.assembly.methodDefinitions.map { method ->
                            if (method.handle == hostile.handle) hostile else method
                        },
                    ),
                    producerTarget = TARGET,
                )
            }
        }

        val ownerParameter = fixture.assembly.genericParameterDefinitions.single()
        val ownerHostiles = listOf(
            fixture.assembly.copy(typeDefinitions = fixture.assembly.typeDefinitions.map { type ->
                type.copy(attributes = type.attributes or INTERFACE_ATTRIBUTE)
            }),
            fixture.assembly.copy(genericParameterDefinitions = emptyList()),
            fixture.assembly.copy(genericParameterDefinitions = listOf(
                ownerParameter.copy(attributes = COVARIANT_ATTRIBUTE),
            )),
            fixture.assembly.copy(genericParameterDefinitions = listOf(
                ownerParameter.copy(attributes = REFERENCE_TYPE_CONSTRAINT_ATTRIBUTE),
            )),
        )
        ownerHostiles.forEachIndexed { index, hostile ->
            assertFailsWith<IllegalArgumentException>("constructor owner hostile $index was accepted") {
                validateDotNetGenericOwnerConstructorMethodDefAgainstClrMetadata(
                    declaration = constructorDeclaration(),
                    ownerDeclaration = constructorOwnerDeclaration(),
                    assembly = hostile,
                    producerTarget = TARGET,
                )
            }
        }
    }

    @Test
    fun authenticatesTheCompleteNestedConstructorOwnerVisibilityChain() {
        val ownerPath = listOf("demo.Outer", "Box`1")
        val fixture = constructorMetadataFixture().withNestedOwner(
            outerAttributes = IMPLEMENTATION_TYPE_ATTRIBUTES,
            leafAttributes = NESTED_PUBLIC_TYPE_ATTRIBUTES,
        )
        validateDotNetGenericOwnerConstructorMethodDefAgainstClrMetadata(
            declaration = constructorDeclaration(ownerPath),
            ownerDeclaration = constructorOwnerDeclaration(ownerPath),
            assembly = fixture,
            producerTarget = TARGET,
        )

        val outer = fixture.typeDefinitions.single { type -> type.declaringType == null }
        val hiddenOuter = fixture.copy(typeDefinitions = fixture.typeDefinitions.map { type ->
            if (type.handle == outer.handle) type.copy(attributes = NOT_PUBLIC_TYPE_ATTRIBUTES) else type
        })
        val failure = assertFailsWith<IllegalArgumentException> {
            validateDotNetGenericOwnerConstructorMethodDefAgainstClrMetadata(
                declaration = constructorDeclaration(ownerPath),
                ownerDeclaration = constructorOwnerDeclaration(ownerPath),
                assembly = hiddenOuter,
                producerTarget = TARGET,
            )
        }
        assertTrue(failure.message.orEmpty().contains("non-public TypeDef"))
    }

    @Test
    fun rejectsAmbiguousExactConstructorSignature() {
        val fixture = constructorMetadataFixture(includeDuplicateObject = true)

        val failure = assertFailsWith<IllegalArgumentException> {
            validateDotNetGenericOwnerConstructorMethodDefAgainstClrMetadata(
                declaration = constructorDeclaration(),
                ownerDeclaration = constructorOwnerDeclaration(),
                assembly = fixture.assembly,
                producerTarget = TARGET,
            )
        }
        assertTrue(failure.message.orEmpty().contains("ambiguous"))
    }

    @Test
    fun rejectsVariantOrdinaryClassEvenWhenRecordedCAndPeAgree() {
        val fixture = constructorMetadataFixture()
        val ownerParameter = fixture.assembly.genericParameterDefinitions.single()
        listOf(
            DotNetGenericOwnerPhysicalTypeParameterVariance.COVARIANT to COVARIANT_ATTRIBUTE,
            DotNetGenericOwnerPhysicalTypeParameterVariance.CONTRAVARIANT to CONTRAVARIANT_ATTRIBUTE,
        ).forEach { variancePair ->
            val recordedVariance = variancePair.first
            val peVariance = variancePair.second
            assertFailsWith<IllegalArgumentException>(
                "ordinary class owner with $recordedVariance GenericParam was accepted",
            ) {
                validateDotNetGenericOwnerConstructorMethodDefAgainstClrMetadata(
                    declaration = constructorDeclaration(),
                    ownerDeclaration = constructorOwnerDeclaration().copy(
                        physicalTypeParameterVariances = listOf(recordedVariance),
                    ),
                    assembly = fixture.assembly.copy(
                        genericParameterDefinitions = listOf(
                            ownerParameter.copy(attributes = peVariance),
                        ),
                    ),
                    producerTarget = TARGET,
                )
            }
        }
    }

    @Test
    fun rejectsConstructorsOnCoreValueTypeAndEnumOwners() {
        listOf("ValueType", "Enum").forEach { baseTypeName ->
            val fixture = constructorMetadataFixture()
            assertFailsWith<IllegalArgumentException>(
                "generic owner deriving directly from System.$baseTypeName was accepted",
            ) {
                validateDotNetGenericOwnerConstructorMethodDefAgainstClrMetadata(
                    declaration = constructorDeclaration(),
                    ownerDeclaration = constructorOwnerDeclaration(),
                    assembly = fixture.withCoreOwnerBaseType(baseTypeName),
                    producerTarget = TARGET,
                )
            }
        }
    }

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
            producerTarget = TARGET,
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
                TARGET,
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
                TARGET,
            )
        }

        val ambiguous = metadataFixture(includeDuplicateTarget = true).assembly
        val ambiguity = assertFailsWith<IllegalArgumentException> {
            validateDotNetGenericOwnerNaturalMethodDefTokenAgainstClrMetadata(
                declaration.logicalMemberKey,
                declaration.physicalMethod,
                ambiguous,
                TARGET,
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

    @Test
    fun rejectsMethodImplDeclaredByAnotherMethodDefOnTheSelectedNaturalType() {
        val fixture = implementationMetadataFixture()
        val failure = assertFailsWith<IllegalArgumentException> {
            validateDotNetGenericOwnerImplementationMethodDefAgainstClrMetadata(
                declaration = fixture.implementationDeclaration,
                naturalDeclaration = fixture.naturalDeclaration,
                assembly = fixture.assembly.copy(methodImplementations = listOf(
                    DotNetClrMethodImplementation(
                        handle = DotNetClrMetadataHandle(METHOD_IMPL_TABLE, 1),
                        implementingType = fixture.implementationType,
                        bodyMethod = fixture.otherImplementationMethod,
                        declarationMethod = fixture.otherNaturalMethod,
                    ),
                )),
                producerTarget = TARGET,
            )
        }

        assertTrue(failure.message.orEmpty().contains("explicitly redirects"))
    }

    @Test
    fun rejectsNaturalMemberRefDeclarationsThroughSelectedNaturalParents() {
        val fixture = implementationMetadataFixture()
        val naturalType = fixture.assembly.typeDefinitions.single { definition ->
            definition.handle == fixture.assembly.methodDefinitions.single { method ->
                method.handle == fixture.otherNaturalMethod
            }.declaringType
        }
        val naturalMethod = fixture.assembly.methodDefinitions.single { method ->
            method.declaringType == naturalType.handle &&
                    method.name == fixture.naturalDeclaration.physicalMethod.physicalMethodName
        }
        val localNaturalReference = DotNetClrTypeReference(
            handle = DotNetClrMetadataHandle(TYPE_REF_TABLE, 1),
            namespaceName = naturalType.namespaceName,
            metadataName = naturalType.metadataName,
            resolutionScope = DotNetClrMetadataHandle(MODULE_TABLE, 1),
        )
        val aliasedNaturalSpecification = DotNetClrTypeSpecification(
            handle = DotNetClrMetadataHandle(TYPE_SPEC_TABLE, 2),
            signature = DotNetClrTypeSignature.GenericInstance(
                genericType = DotNetClrTypeSignature.Named(
                    localNaturalReference.handle,
                    isValueType = false,
                ),
                arguments = listOf(DotNetClrTypeSignature.GenericParameter(
                    DotNetClrGenericParameterKind.TYPE,
                    0,
                )),
            ),
            rawSignature = emptyList(),
        )
        listOf(
            localNaturalReference.handle to emptyList<DotNetClrTypeSpecification>(),
            fixture.assembly.typeSpecifications.single().handle to
                    emptyList<DotNetClrTypeSpecification>(),
            aliasedNaturalSpecification.handle to listOf(aliasedNaturalSpecification),
        ).forEachIndexed { index, hostile ->
            val declarationReference = DotNetClrMemberReference(
                handle = DotNetClrMetadataHandle(MEMBER_REF_TABLE, 1),
                parent = hostile.first,
                name = naturalMethod.name,
                signature = DotNetClrMemberReferenceSignature.Method(naturalMethod.signature),
                rawSignature = emptyList(),
            )
            val failure = assertFailsWith<IllegalArgumentException>(
                "natural MethodImpl declaration parent hostile $index was accepted",
            ) {
                validateDotNetGenericOwnerImplementationMethodDefAgainstClrMetadata(
                    declaration = fixture.implementationDeclaration,
                    naturalDeclaration = fixture.naturalDeclaration,
                    assembly = fixture.assembly.copy(
                        typeReferences = listOf(localNaturalReference),
                        typeSpecifications = fixture.assembly.typeSpecifications + hostile.second,
                        memberReferences = listOf(declarationReference),
                        methodImplementations = listOf(DotNetClrMethodImplementation(
                            handle = DotNetClrMetadataHandle(METHOD_IMPL_TABLE, 1),
                            implementingType = fixture.implementationType,
                            bodyMethod = fixture.otherImplementationMethod,
                            declarationMethod = declarationReference.handle,
                        )),
                    ),
                    producerTarget = TARGET,
                )
            }
            assertTrue(failure.message.orEmpty().contains("explicitly redirects"))
        }
    }

    @Test
    fun countsAnExtraNaturalInterfaceImplThroughAnExactLocalTypeRefAlias() {
        val fixture = implementationMetadataFixture()
        val naturalType = fixture.assembly.typeDefinitions.single { definition ->
            definition.handle == fixture.assembly.methodDefinitions.single { method ->
                method.handle == fixture.otherNaturalMethod
            }.declaringType
        }
        val localNaturalReference = DotNetClrTypeReference(
            handle = DotNetClrMetadataHandle(TYPE_REF_TABLE, 1),
            namespaceName = naturalType.namespaceName,
            metadataName = naturalType.metadataName,
            resolutionScope = DotNetClrMetadataHandle(MODULE_TABLE, 1),
        )
        val aliasSpecification = DotNetClrTypeSpecification(
            handle = DotNetClrMetadataHandle(TYPE_SPEC_TABLE, 2),
            signature = DotNetClrTypeSignature.GenericInstance(
                genericType = DotNetClrTypeSignature.Named(
                    localNaturalReference.handle,
                    isValueType = false,
                ),
                arguments = listOf(DotNetClrTypeSignature.GenericParameter(
                    DotNetClrGenericParameterKind.TYPE,
                    0,
                )),
            ),
            rawSignature = emptyList(),
        )
        val failure = assertFailsWith<IllegalArgumentException> {
            validateDotNetGenericOwnerImplementationMethodDefAgainstClrMetadata(
                declaration = fixture.implementationDeclaration,
                naturalDeclaration = fixture.naturalDeclaration,
                assembly = fixture.assembly.copy(
                    typeReferences = listOf(localNaturalReference),
                    typeSpecifications = fixture.assembly.typeSpecifications + aliasSpecification,
                    interfaceImplementations = fixture.assembly.interfaceImplementations +
                            DotNetClrInterfaceImplementation(
                                handle = DotNetClrMetadataHandle(INTERFACE_IMPL_TABLE, 2),
                                implementingType = fixture.implementationType,
                                interfaceType = aliasSpecification.handle,
                            ),
                ),
                producerTarget = TARGET,
            )
        }

        assertTrue(failure.message.orEmpty().contains("exactly one construction"))
    }

    @Test
    fun rejectsOverdeepExactLocalTypeRefScopeChainsBeforeMethodImplMatching() {
        val fixture = implementationMetadataFixture()
        val implementationMethod = fixture.assembly.methodDefinitions.single { method ->
            method.handle == fixture.implementationMethod
        }
        val references = (1..65).map { row ->
            DotNetClrTypeReference(
                handle = DotNetClrMetadataHandle(TYPE_REF_TABLE, row),
                namespaceName = "demo",
                metadataName = "Nested$row",
                resolutionScope = if (row == 65) {
                    DotNetClrMetadataHandle(MODULE_TABLE, 1)
                } else {
                    DotNetClrMetadataHandle(TYPE_REF_TABLE, row + 1)
                },
            )
        }
        val bodyReference = DotNetClrMemberReference(
            handle = DotNetClrMetadataHandle(MEMBER_REF_TABLE, 1),
            parent = references.first().handle,
            name = implementationMethod.name,
            signature = DotNetClrMemberReferenceSignature.Method(implementationMethod.signature),
            rawSignature = emptyList(),
        )
        val failure = assertFailsWith<IllegalArgumentException> {
            validateDotNetGenericOwnerImplementationMethodDefAgainstClrMetadata(
                declaration = fixture.implementationDeclaration,
                naturalDeclaration = fixture.naturalDeclaration,
                assembly = fixture.assembly.copy(
                    typeReferences = references,
                    memberReferences = listOf(bodyReference),
                    methodImplementations = listOf(DotNetClrMethodImplementation(
                        handle = DotNetClrMetadataHandle(METHOD_IMPL_TABLE, 1),
                        implementingType = fixture.implementationType,
                        bodyMethod = bodyReference.handle,
                        declarationMethod = fixture.otherImplementationMethod,
                    )),
                ),
                producerTarget = TARGET,
            )
        }

        assertTrue(failure.message.orEmpty().contains("scope nesting is too deep"))
    }

    @Test
    fun rejectsMemberRefBodiesWhichExactlyNameTheImplementationMethodDef() {
        val fixture = implementationMetadataFixture()
        val implementationMethod = fixture.assembly.methodDefinitions.single { method ->
            method.handle == fixture.implementationMethod
        }
        val implementationType = fixture.assembly.typeDefinitions.single { definition ->
            definition.handle == fixture.implementationType
        }
        val localImplementationReference = DotNetClrTypeReference(
            handle = DotNetClrMetadataHandle(TYPE_REF_TABLE, 1),
            namespaceName = implementationType.namespaceName,
            metadataName = implementationType.metadataName,
            resolutionScope = DotNetClrMetadataHandle(MODULE_TABLE, 1),
        )
        val exactSelfSpecification = DotNetClrTypeSpecification(
            handle = DotNetClrMetadataHandle(TYPE_SPEC_TABLE, 2),
            signature = DotNetClrTypeSignature.GenericInstance(
                genericType = DotNetClrTypeSignature.Named(
                    fixture.implementationType,
                    isValueType = false,
                ),
                arguments = listOf(DotNetClrTypeSignature.GenericParameter(
                    DotNetClrGenericParameterKind.TYPE,
                    0,
                )),
            ),
            rawSignature = emptyList(),
        )
        val aliasedSelfSpecification = exactSelfSpecification.copy(
            handle = DotNetClrMetadataHandle(TYPE_SPEC_TABLE, 3),
            signature = (exactSelfSpecification.signature as DotNetClrTypeSignature.GenericInstance).copy(
                genericType = DotNetClrTypeSignature.Named(
                    localImplementationReference.handle,
                    isValueType = false,
                ),
            ),
        )
        listOf(
            Triple(
                fixture.implementationType,
                emptyList<DotNetClrTypeReference>(),
                emptyList<DotNetClrTypeSpecification>(),
            ),
            Triple(
                exactSelfSpecification.handle,
                emptyList<DotNetClrTypeReference>(),
                listOf(exactSelfSpecification),
            ),
            Triple(
                localImplementationReference.handle,
                listOf(localImplementationReference),
                emptyList<DotNetClrTypeSpecification>(),
            ),
            Triple(
                aliasedSelfSpecification.handle,
                listOf(localImplementationReference),
                listOf(aliasedSelfSpecification),
            ),
        ).forEachIndexed { index, hostile ->
            val parent = hostile.first
            val additionalTypeReferences = hostile.second
            val additionalTypeSpecifications = hostile.third
            val memberReference = DotNetClrMemberReference(
                handle = DotNetClrMetadataHandle(MEMBER_REF_TABLE, 1),
                parent = parent,
                name = implementationMethod.name,
                signature = DotNetClrMemberReferenceSignature.Method(implementationMethod.signature),
                rawSignature = emptyList(),
            )
            val failure = assertFailsWith<IllegalArgumentException>(
                "MethodImpl body MemberRef hostile $index was accepted",
            ) {
                validateDotNetGenericOwnerImplementationMethodDefAgainstClrMetadata(
                    declaration = fixture.implementationDeclaration,
                    naturalDeclaration = fixture.naturalDeclaration,
                    assembly = fixture.assembly.copy(
                        typeReferences = fixture.assembly.typeReferences + additionalTypeReferences,
                        typeSpecifications = fixture.assembly.typeSpecifications + additionalTypeSpecifications,
                        memberReferences = listOf(memberReference),
                        methodImplementations = listOf(DotNetClrMethodImplementation(
                            handle = DotNetClrMetadataHandle(METHOD_IMPL_TABLE, 1),
                            implementingType = fixture.implementationType,
                            bodyMethod = memberReference.handle,
                            declarationMethod = fixture.otherImplementationMethod,
                        )),
                    ),
                    producerTarget = TARGET,
                )
            }
            assertTrue(failure.message.orEmpty().contains("explicitly redirects"))
        }
    }

    @Test
    fun doesNotResolveForeignSameNameTypeRefsAsImplementationOrNaturalOwners() {
        val fixture = implementationMetadataFixture()
        val naturalType = fixture.assembly.typeDefinitions.single { definition ->
            definition.handle == fixture.assembly.methodDefinitions.single { method ->
                method.handle == fixture.otherNaturalMethod
            }.declaringType
        }
        val implementationType = fixture.assembly.typeDefinitions.single { definition ->
            definition.handle == fixture.implementationType
        }
        val implementationMethod = fixture.assembly.methodDefinitions.single { method ->
            method.handle == fixture.implementationMethod
        }
        val naturalMethod = fixture.assembly.methodDefinitions.single { method ->
            method.declaringType == naturalType.handle && method.name == implementationMethod.name
        }
        val foreignScope = DotNetClrMetadataHandle(ASSEMBLY_REF_TABLE, 1)
        val foreignImplementationReference = DotNetClrTypeReference(
            handle = DotNetClrMetadataHandle(TYPE_REF_TABLE, 1),
            namespaceName = implementationType.namespaceName,
            metadataName = implementationType.metadataName,
            resolutionScope = foreignScope,
        )
        val foreignNaturalReference = DotNetClrTypeReference(
            handle = DotNetClrMetadataHandle(TYPE_REF_TABLE, 2),
            namespaceName = naturalType.namespaceName,
            metadataName = naturalType.metadataName,
            resolutionScope = foreignScope,
        )
        val foreignNaturalSpecification = DotNetClrTypeSpecification(
            handle = DotNetClrMetadataHandle(TYPE_SPEC_TABLE, 2),
            signature = DotNetClrTypeSignature.GenericInstance(
                genericType = DotNetClrTypeSignature.Named(
                    foreignNaturalReference.handle,
                    isValueType = false,
                ),
                arguments = listOf(DotNetClrTypeSignature.GenericParameter(
                    DotNetClrGenericParameterKind.TYPE,
                    0,
                )),
            ),
            rawSignature = emptyList(),
        )
        val foreignBody = DotNetClrMemberReference(
            handle = DotNetClrMetadataHandle(MEMBER_REF_TABLE, 1),
            parent = foreignImplementationReference.handle,
            name = implementationMethod.name,
            signature = DotNetClrMemberReferenceSignature.Method(implementationMethod.signature),
            rawSignature = emptyList(),
        )
        val foreignDeclaration = DotNetClrMemberReference(
            handle = DotNetClrMetadataHandle(MEMBER_REF_TABLE, 2),
            parent = foreignNaturalSpecification.handle,
            name = naturalMethod.name,
            signature = DotNetClrMemberReferenceSignature.Method(naturalMethod.signature),
            rawSignature = emptyList(),
        )
        val binding = validateDotNetGenericOwnerImplementationMethodDefAgainstClrMetadata(
            declaration = fixture.implementationDeclaration,
            naturalDeclaration = fixture.naturalDeclaration,
            assembly = fixture.assembly.copy(
                assemblyReferences = listOf(DotNetClrAssemblyReference(
                    handle = foreignScope,
                    name = "ForeignDemo",
                    version = "1.0.0.0",
                    culture = "neutral",
                    flags = 0,
                    publicKeyOrToken = emptyList(),
                    hashValue = emptyList(),
                )),
                typeReferences = listOf(foreignImplementationReference, foreignNaturalReference),
                typeSpecifications = fixture.assembly.typeSpecifications + foreignNaturalSpecification,
                memberReferences = listOf(foreignBody, foreignDeclaration),
                methodImplementations = listOf(
                    DotNetClrMethodImplementation(
                        handle = DotNetClrMetadataHandle(METHOD_IMPL_TABLE, 1),
                        implementingType = fixture.implementationType,
                        bodyMethod = foreignBody.handle,
                        declarationMethod = fixture.otherImplementationMethod,
                    ),
                    DotNetClrMethodImplementation(
                        handle = DotNetClrMetadataHandle(METHOD_IMPL_TABLE, 2),
                        implementingType = fixture.implementationType,
                        bodyMethod = fixture.otherImplementationMethod,
                        declarationMethod = foreignDeclaration.handle,
                    ),
                ),
            ),
            producerTarget = TARGET,
        )

        assertEquals(fixture.implementationMethod, binding.methodDefinition.handle)
    }

    @Test
    fun comparesCompleteMethodSignaturesModuloNestedExactLocalTypeRefAliases() {
        val fixture = implementationMetadataFixture()
        val outerType = DotNetClrTypeDefinition(
            handle = DotNetClrMetadataHandle(TYPE_DEF_TABLE, 3),
            namespaceName = "demo",
            metadataName = "Container`1",
            attributes = IMPLEMENTATION_TYPE_ATTRIBUTES,
            baseType = null,
            declaringType = null,
        )
        val nestedType = DotNetClrTypeDefinition(
            handle = DotNetClrMetadataHandle(TYPE_DEF_TABLE, 4),
            namespaceName = "",
            metadataName = "Nested",
            attributes = IMPLEMENTATION_TYPE_ATTRIBUTES,
            baseType = null,
            declaringType = outerType.handle,
        )
        val outerReference = DotNetClrTypeReference(
            handle = DotNetClrMetadataHandle(TYPE_REF_TABLE, 1),
            namespaceName = outerType.namespaceName,
            metadataName = outerType.metadataName,
            resolutionScope = DotNetClrMetadataHandle(MODULE_TABLE, 1),
        )
        val nestedReference = DotNetClrTypeReference(
            handle = DotNetClrMetadataHandle(TYPE_REF_TABLE, 2),
            namespaceName = "",
            metadataName = nestedType.metadataName,
            resolutionScope = outerReference.handle,
        )
        val assembly = fixture.assembly.copy(
            typeDefinitions = fixture.assembly.typeDefinitions + outerType + nestedType,
            typeReferences = listOf(outerReference, nestedReference),
        )
        fun named(handle: DotNetClrMetadataHandle) = DotNetClrTypeSignature.Named(
            type = handle,
            isValueType = false,
        )
        fun signature(
            outer: DotNetClrMetadataHandle,
            nested: DotNetClrMetadataHandle,
        ): DotNetClrMethodSignature {
            val nestedNamed = named(nested)
            return DotNetClrMethodSignature(
                callingConvention = DotNetClrSignatureCallingConvention.VARARG,
                hasThis = true,
                hasExplicitThis = true,
                genericParameterCount = 1,
                returnType = DotNetClrTypeSignature.Modified(
                    modifiers = listOf(DotNetClrCustomModifier(
                        isRequired = true,
                        modifierType = nested,
                    )),
                    unmodifiedType = DotNetClrTypeSignature.FunctionPointer(
                        DotNetClrMethodSignature(
                            callingConvention = DotNetClrSignatureCallingConvention.C,
                            hasThis = false,
                            hasExplicitThis = false,
                            genericParameterCount = 1,
                            returnType = DotNetClrTypeSignature.SzArray(
                                DotNetClrTypeSignature.Pointer(nestedNamed),
                            ),
                            parameterTypes = listOf(DotNetClrTypeSignature.GenericParameter(
                                DotNetClrGenericParameterKind.METHOD,
                                0,
                            )),
                            varargParameterStart = null,
                        ),
                    ),
                ),
                parameterTypes = listOf(
                    DotNetClrTypeSignature.ByReference(DotNetClrTypeSignature.Array(
                        elementType = nestedNamed,
                        shape = DotNetClrArrayShape(rank = 2, sizes = listOf(3), lowerBounds = listOf(1)),
                    )),
                    DotNetClrTypeSignature.GenericInstance(
                        genericType = named(outer),
                        arguments = listOf(nestedNamed),
                    ),
                    DotNetClrTypeSignature.TypedReference,
                    DotNetClrTypeSignature.Primitive(DotNetClrPrimitiveType.INT32),
                ),
                varargParameterStart = 2,
            )
        }
        val direct = signature(outerType.handle, nestedType.handle)
        val aliased = signature(outerReference.handle, nestedReference.handle)

        assertTrue(assembly.methodSignaturesMatchModuloExactLocalTypeReferences(direct, aliased))
        assertTrue(!assembly.methodSignaturesMatchModuloExactLocalTypeReferences(
            direct,
            aliased.copy(varargParameterStart = 3),
        ))
    }

    @Test
    fun doesNotGuessAnImplementationBodyFromOwnerAndNameWithoutTheFullSignature() {
        val fixture = implementationMetadataFixture()
        val implementationMethod = fixture.assembly.methodDefinitions.single { method ->
            method.handle == fixture.implementationMethod
        }
        val nearMiss = DotNetClrMemberReference(
            handle = DotNetClrMetadataHandle(MEMBER_REF_TABLE, 1),
            parent = fixture.implementationType,
            name = implementationMethod.name,
            signature = DotNetClrMemberReferenceSignature.Method(implementationMethod.signature.copy(
                returnType = DotNetClrTypeSignature.Primitive(DotNetClrPrimitiveType.OBJECT),
            )),
            rawSignature = emptyList(),
        )
        val binding = validateDotNetGenericOwnerImplementationMethodDefAgainstClrMetadata(
            declaration = fixture.implementationDeclaration,
            naturalDeclaration = fixture.naturalDeclaration,
            assembly = fixture.assembly.copy(
                memberReferences = listOf(nearMiss),
                methodImplementations = listOf(DotNetClrMethodImplementation(
                    handle = DotNetClrMetadataHandle(METHOD_IMPL_TABLE, 1),
                    implementingType = fixture.implementationType,
                    bodyMethod = nearMiss.handle,
                    declarationMethod = fixture.otherImplementationMethod,
                )),
            ),
            producerTarget = TARGET,
        )

        assertEquals(fixture.implementationMethod, binding.methodDefinition.handle)
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
            producerTarget = TARGET,
        )
    }

    private data class MetadataFixture(
        val assembly: DotNetClrAssemblyMetadata,
        val ownerType: DotNetClrMetadataHandle,
        val targetMethod: DotNetClrMetadataHandle,
        val targetSplitParameter: DotNetClrMetadataHandle,
    )

    private data class ImplementationMetadataFixture(
        val assembly: DotNetClrAssemblyMetadata,
        val naturalDeclaration: DotNetPhysicalDeclaration.GenericOwnerNaturalMethodDef,
        val implementationDeclaration: DotNetPhysicalDeclaration.GenericOwnerImplementationMethodDef,
        val implementationType: DotNetClrMetadataHandle,
        val implementationMethod: DotNetClrMetadataHandle,
        val otherNaturalMethod: DotNetClrMetadataHandle,
        val otherImplementationMethod: DotNetClrMetadataHandle,
    )

    private fun implementationMetadataFixture(): ImplementationMetadataFixture {
        val publication = producerSealedFamilyPublicationFixture().let { original ->
            original.copy(body = original.body.copy(methodDefs = original.body.methodDefs.map { method ->
                method.copy(
                    row = method.row.copy(
                        structural = method.row.structural.copy(
                            header = method.row.structural.header.copy(
                                genericArity = 0,
                                ordinaryParameterCarriers = emptyList(),
                            ),
                            genericParameters = emptyList(),
                        ),
                        physicalGenericParameterNames = emptyList(),
                    ),
                    logicalParameterDomains = emptyList(),
                )
            }))
        }
        val naturalDeclaration = publication.toNaturalMethodDefPhysicalDeclaration("demo/Source|class")
        val implementationOwnerPath = listOf("demo.ExternalStore`1")
        val implementationDeclaration = DotNetPhysicalDeclaration.GenericOwnerImplementationMethodDef(
            logicalInterfaceMemberKey = naturalDeclaration.logicalMemberKey,
            implementationOwnerKey = "demo/ExternalStore|class",
            implementationMemberKey = "demo/ExternalStore.read|function",
            ownerPath = implementationOwnerPath,
            ownerTypeParameterVariances = listOf(
                DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT,
            ),
            ownerVisibility = DotNetGenericOwnerPhysicalTypeVisibility.PUBLIC,
            ownerDispatch = DotNetGenericOwnerPhysicalTypeDispatch.OVERRIDABLE,
            naturalInterfaceTypeArguments = listOf(
                DotNetGenericOwnerPhysicalTypeExpressionRecord.ownerParameter(0),
            ),
            physicalMethod = naturalDeclaration.physicalMethod.copy(
                physicalOwnerPath = implementationOwnerPath,
            ),
            methodVisibility = DotNetGenericOwnerPhysicalMemberVisibility.PUBLIC,
            methodDispatch = DotNetGenericOwnerPhysicalMemberDispatch.OVERRIDABLE,
            methodIntroducesSlot = true,
            methodIsHideBySig = true,
            methodIsSpecialName = false,
            methodIsRuntimeSpecialName = false,
        )

        val naturalType = DotNetClrMetadataHandle(TYPE_DEF_TABLE, 1)
        val implementationType = DotNetClrMetadataHandle(TYPE_DEF_TABLE, 2)
        val naturalMethod = DotNetClrMetadataHandle(METHOD_DEF_TABLE, 1)
        val otherNaturalMethod = DotNetClrMetadataHandle(METHOD_DEF_TABLE, 2)
        val implementationMethod = DotNetClrMetadataHandle(METHOD_DEF_TABLE, 3)
        val otherImplementationMethod = DotNetClrMetadataHandle(METHOD_DEF_TABLE, 4)
        val splitSignature = DotNetClrMethodSignature(
            callingConvention = DotNetClrSignatureCallingConvention.DEFAULT,
            hasThis = true,
            hasExplicitThis = false,
            genericParameterCount = 0,
            returnType = DotNetClrTypeSignature.GenericParameter(
                DotNetClrGenericParameterKind.TYPE,
                0,
            ),
            parameterTypes = listOf(DotNetClrTypeSignature.ByReference(
                DotNetClrTypeSignature.Primitive(DotNetClrPrimitiveType.BOOLEAN),
            )),
            varargParameterStart = null,
        )
        fun method(
            handle: DotNetClrMetadataHandle,
            declaringType: DotNetClrMetadataHandle,
            name: String,
            attributes: Int,
        ) = DotNetClrMethodDefinition(
            handle = handle,
            declaringType = declaringType,
            name = name,
            relativeVirtualAddress = 0,
            implementationAttributes = 0,
            attributes = attributes,
            signature = splitSignature,
            rawSignature = emptyList(),
        )
        val interfaceSpecification = DotNetClrMetadataHandle(TYPE_SPEC_TABLE, 1)
        val methods = listOf(
            method(naturalMethod, naturalType, "Read", NATURAL_METHOD_ATTRIBUTES),
            method(otherNaturalMethod, naturalType, "Other", NATURAL_METHOD_ATTRIBUTES),
            method(implementationMethod, implementationType, "Read", IMPLEMENTATION_METHOD_ATTRIBUTES),
            method(otherImplementationMethod, implementationType, "Other", IMPLEMENTATION_METHOD_ATTRIBUTES),
        )
        val splitParameters = listOf(naturalMethod, implementationMethod).mapIndexed { index, owner ->
            DotNetClrParameterDefinition(
                handle = DotNetClrMetadataHandle(PARAM_TABLE, index + 1),
                declaringMethod = owner,
                sequence = 1,
                name = "isNull",
                attributes = OUT_PARAMETER_ATTRIBUTE,
            )
        }
        val baseAssembly = metadataFixture().assembly
        val assembly = baseAssembly.copy(
            typeDefinitions = listOf(
                baseAssembly.typeDefinitions.single().copy(handle = naturalType),
                DotNetClrTypeDefinition(
                    handle = implementationType,
                    namespaceName = "demo",
                    metadataName = "ExternalStore`1",
                    attributes = IMPLEMENTATION_TYPE_ATTRIBUTES,
                    baseType = null,
                    declaringType = null,
                ),
            ),
            interfaceImplementations = listOf(DotNetClrInterfaceImplementation(
                handle = DotNetClrMetadataHandle(INTERFACE_IMPL_TABLE, 1),
                implementingType = implementationType,
                interfaceType = interfaceSpecification,
            )),
            typeSpecifications = listOf(DotNetClrTypeSpecification(
                handle = interfaceSpecification,
                signature = DotNetClrTypeSignature.GenericInstance(
                    genericType = DotNetClrTypeSignature.Named(naturalType, isValueType = false),
                    arguments = listOf(DotNetClrTypeSignature.GenericParameter(
                        DotNetClrGenericParameterKind.TYPE,
                        0,
                    )),
                ),
                rawSignature = emptyList(),
            )),
            methodDefinitions = methods,
            parameterDefinitions = splitParameters,
            genericParameterDefinitions = listOf(
                DotNetClrGenericParameterDefinition(
                    handle = DotNetClrMetadataHandle(GENERIC_PARAMETER_TABLE, 1),
                    number = 0,
                    attributes = COVARIANT_ATTRIBUTE,
                    owner = naturalType,
                    name = "T",
                ),
                DotNetClrGenericParameterDefinition(
                    handle = DotNetClrMetadataHandle(GENERIC_PARAMETER_TABLE, 2),
                    number = 0,
                    attributes = 0,
                    owner = implementationType,
                    name = "T",
                ),
            ),
        )
        return ImplementationMetadataFixture(
            assembly = assembly,
            naturalDeclaration = naturalDeclaration,
            implementationDeclaration = implementationDeclaration,
            implementationType = implementationType,
            implementationMethod = implementationMethod,
            otherNaturalMethod = otherNaturalMethod,
            otherImplementationMethod = otherImplementationMethod,
        )
    }

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

    private data class ConstructorMetadataFixture(
        val assembly: DotNetClrAssemblyMetadata,
        val objectConstructor: DotNetClrMetadataHandle,
    )

    private fun constructorDeclaration(
        ownerPath: List<String> = listOf("demo.Box`1"),
    ):
            DotNetPhysicalDeclaration.GenericOwnerConstructorMethodDef {
        return DotNetPhysicalDeclaration.GenericOwnerConstructorMethodDef(
            logicalOwnerKey = "C:demo/Box",
            logicalConstructorKey = "F:demo/Box.<init>|object",
            ownerPath = ownerPath,
            physicalMethod = DotNetGenericOwnerPhysicalMethodIdentityRecord(
                physicalOwnerPath = ownerPath,
                physicalMethodName = ".ctor",
                signature = DotNetGenericOwnerPhysicalMethodSignatureRecord(
                    isInstance = true,
                    genericArity = 0,
                    resultLayout = DotNetGenericOwnerPhysicalCallableResultLayoutRecord.Void,
                    parameterSlots = listOf(DotNetGenericOwnerPhysicalValueSlotRecord(
                        DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_INPUT,
                        DotNetGenericOwnerPhysicalTypeExpressionRecord.objectType(),
                    )),
                ),
            ),
            visibility = DotNetGenericOwnerPhysicalConstructorVisibility.PUBLIC,
        )
    }

    private fun constructorOwnerDeclaration(
        ownerPath: List<String> = listOf("demo.Box`1"),
    ) = DotNetPhysicalDeclaration.Class(
        ownerPath = ownerPath,
        physicalTypeParameterCount = 1,
        physicalTypeParameterVariances = listOf(
            DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT,
        ),
    )

    private fun constructorMetadataFixture(
        includeStringDecoy: Boolean = false,
        includeDuplicateObject: Boolean = false,
    ): ConstructorMetadataFixture {
        val owner = DotNetClrMetadataHandle(TYPE_DEF_TABLE, 1)
        val objectConstructor = DotNetClrMetadataHandle(METHOD_DEF_TABLE, 1)
        val stringConstructor = DotNetClrMetadataHandle(METHOD_DEF_TABLE, 2)
        val duplicateObject = DotNetClrMetadataHandle(METHOD_DEF_TABLE, 3)
        fun constructor(
            handle: DotNetClrMetadataHandle,
            parameterType: DotNetClrPrimitiveType,
        ) = DotNetClrMethodDefinition(
            handle = handle,
            declaringType = owner,
            name = ".ctor",
            relativeVirtualAddress = 0,
            implementationAttributes = 0,
            attributes = CONSTRUCTOR_METHOD_ATTRIBUTES,
            signature = DotNetClrMethodSignature(
                callingConvention = DotNetClrSignatureCallingConvention.DEFAULT,
                hasThis = true,
                hasExplicitThis = false,
                genericParameterCount = 0,
                returnType = DotNetClrTypeSignature.Void,
                parameterTypes = listOf(DotNetClrTypeSignature.Primitive(parameterType)),
                varargParameterStart = null,
            ),
            rawSignature = emptyList(),
        )
        val methods = buildList {
            add(constructor(objectConstructor, DotNetClrPrimitiveType.OBJECT))
            if (includeStringDecoy) {
                add(constructor(stringConstructor, DotNetClrPrimitiveType.STRING))
            }
            if (includeDuplicateObject) {
                add(constructor(duplicateObject, DotNetClrPrimitiveType.OBJECT))
            }
        }
        return ConstructorMetadataFixture(
            assembly = DotNetClrAssemblyMetadata(
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
                    metadataName = "Box`1",
                    attributes = IMPLEMENTATION_TYPE_ATTRIBUTES,
                    baseType = null,
                    declaringType = null,
                )),
                interfaceImplementations = emptyList(),
                exportedTypes = emptyList(),
                typeSpecifications = emptyList(),
                fieldDefinitions = emptyList(),
                methodDefinitions = methods,
                parameterDefinitions = emptyList(),
                constantDefinitions = emptyList(),
                fieldMarshalDefinitions = emptyList(),
                memberReferences = emptyList(),
                customAttributes = emptyList(),
                propertyDefinitions = emptyList(),
                methodSemantics = emptyList(),
                genericParameterDefinitions = listOf(DotNetClrGenericParameterDefinition(
                    handle = DotNetClrMetadataHandle(GENERIC_PARAMETER_TABLE, 1),
                    number = 0,
                    attributes = 0,
                    owner = owner,
                    name = "T",
                )),
                genericParameterConstraints = emptyList(),
            ),
            objectConstructor = objectConstructor,
        )
    }

    private fun ConstructorMetadataFixture.withCoreOwnerBaseType(
        metadataName: String,
    ): DotNetClrAssemblyMetadata {
        val coreLibrary = DotNetClrMetadataHandle(ASSEMBLY_REF_TABLE, 1)
        val baseType = DotNetClrMetadataHandle(TYPE_REF_TABLE, 1)
        return assembly.copy(
            assemblyReferences = listOf(DotNetClrAssemblyReference(
                handle = coreLibrary,
                name = "System.Runtime",
                version = "10.0.0.0",
                culture = "neutral",
                flags = 0,
                publicKeyOrToken = emptyList(),
                hashValue = emptyList(),
            )),
            typeReferences = listOf(DotNetClrTypeReference(
                handle = baseType,
                namespaceName = "System",
                metadataName = metadataName,
                resolutionScope = coreLibrary,
            )),
            typeDefinitions = assembly.typeDefinitions.map { type ->
                type.copy(baseType = baseType)
            },
        )
    }

    private fun ConstructorMetadataFixture.withNestedOwner(
        outerAttributes: Long,
        leafAttributes: Long,
    ): DotNetClrAssemblyMetadata {
        val leaf = assembly.typeDefinitions.single()
        val outer = DotNetClrTypeDefinition(
            handle = DotNetClrMetadataHandle(TYPE_DEF_TABLE, 2),
            namespaceName = "demo",
            metadataName = "Outer",
            attributes = outerAttributes,
            baseType = null,
            declaringType = null,
        )
        return assembly.copy(typeDefinitions = listOf(
            outer,
            leaf.copy(
                namespaceName = "",
                metadataName = "Box`1",
                attributes = leafAttributes,
                declaringType = outer.handle,
            ),
        ))
    }

    private companion object {
        val TARGET = DotNetTarget.NET10_0
        const val MODULE_TABLE = 0
        const val TYPE_REF_TABLE = 1
        const val TYPE_DEF_TABLE = 2
        const val METHOD_DEF_TABLE = 6
        const val INTERFACE_IMPL_TABLE = 9
        const val MEMBER_REF_TABLE = 10
        const val METHOD_IMPL_TABLE = 25
        const val TYPE_SPEC_TABLE = 27
        const val ASSEMBLY_REF_TABLE = 35
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
        const val STATIC_ATTRIBUTE = 0x0010
        const val NEW_SLOT_ATTRIBUTE = 0x0100
        const val HIDE_BY_SIG_ATTRIBUTE = 0x0080
        const val SPECIAL_NAME_ATTRIBUTE = 0x0800
        const val RUNTIME_SPECIAL_NAME_ATTRIBUTE = 0x1000
        const val NATURAL_TYPE_ATTRIBUTES = 0x0000_00a1L
        const val IMPLEMENTATION_TYPE_ATTRIBUTES = 0x0000_0001L
        const val NOT_PUBLIC_TYPE_ATTRIBUTES = 0x0000_0000L
        const val NESTED_PUBLIC_TYPE_ATTRIBUTES = 0x0000_0002L
        const val NATURAL_METHOD_ATTRIBUTES = 0x05c6
        const val IMPLEMENTATION_METHOD_ATTRIBUTES = 0x01c6
        const val CONSTRUCTOR_METHOD_ATTRIBUTES = 0x1886
        const val CONTRAVARIANT_ATTRIBUTE = 0x0002
    }
}
