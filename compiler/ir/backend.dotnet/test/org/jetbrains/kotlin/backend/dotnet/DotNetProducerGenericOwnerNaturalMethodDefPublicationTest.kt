/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.builders.declarations.addTypeParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.impl.IrFactoryImpl
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.types.Variance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DotNetProducerGenericOwnerNaturalMethodDefPublicationTest {
    @Test
    fun publishesOneFinalNaturalTypeAndMethodObservation() {
        val fixture = PublicationFixture()

        val publication = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetProducerGenericOwnerNaturalMethodDefPublication,
                >>(fixture.inspect()).value

        assertEquals(PublicationFixture.LOGICAL_OWNER_KEY, publication.logicalOwnerKey)
        assertEquals(PublicationFixture.LOGICAL_MEMBER_KEY, publication.logicalMemberKey)
        assertEquals(PublicationFixture.NATURAL_OWNER_PATH, publication.naturalType.physicalPath)
        assertEquals("read", publication.naturalMethod.row.physicalName)
        assertEquals(
            DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_OUTPUT,
            publication.naturalMethod.logicalResultDomain,
        )
    }

    @Test
    fun leavesNaturalPublicationUnavailableWhenNoNaturalMethodWasObserved() {
        val fixture = PublicationFixture()

        assertIs<DotNetGenericOwnerPhysicalBindingResult.Unavailable>(
            fixture.inspect(currentMethods = emptyList()),
        )
    }

    @Test
    fun rejectsTheSameMethodIdentityOnTheNaturalAndAnOffOwnerTypeDef() {
        val fixture = PublicationFixture()
        val conflict = assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            fixture.inspect(
                currentTypes = listOf(fixture.naturalTypeObservation, fixture.exactTypeObservation),
                currentMethods = listOf(
                    fixture.naturalMethodObservation,
                    fixture.methodObservation(fixture.exactType),
                ),
            ),
        )

        assertTrue(conflict.reason.contains("another physical owner"))
    }

    @Test
    fun rejectsTheExpectedMethodIdentityObservedOnlyOnAnOffOwnerTypeDef() {
        val fixture = PublicationFixture()
        val conflict = assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            fixture.inspect(
                currentTypes = listOf(fixture.naturalTypeObservation, fixture.exactTypeObservation),
                currentMethods = listOf(fixture.methodObservation(fixture.exactType)),
            ),
        )

        assertTrue(conflict.reason.contains("another physical owner"))
    }

    @Test
    fun ignoresAnUnrelatedMethodOnAnExactSiblingTypeDef() {
        val fixture = PublicationFixture()

        assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetProducerGenericOwnerNaturalMethodDefPublication,
                >>(fixture.inspect(
            currentTypes = listOf(fixture.naturalTypeObservation, fixture.exactTypeObservation),
            currentMethods = listOf(
                fixture.naturalMethodObservation,
                fixture.methodObservation(
                    physicalOwner = fixture.exactType,
                    physicalFunction = fixture.unrelatedSource,
                    physicalName = "unrelated",
                ),
            ),
        ))
    }

    @Test
    fun rejectsStandaloneRecordsOutsideTheBoundedInterfaceProducerGrammar() {
        val fixture = PublicationFixture()
        val publication = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetProducerGenericOwnerNaturalMethodDefPublication,
                >>(fixture.inspect()).value

        val hostileCopies = listOf<() -> Unit>(
            {
                publication.copy(naturalType = publication.naturalType.copy(
                    flags = publication.naturalType.flags.copy(isInterface = false),
                ))
            },
            {
                publication.copy(naturalMethod = publication.naturalMethod.copy(
                    row = publication.naturalMethod.row.copy(
                        visibility = DotNetIlRawMethodDefVisibility.PRIVATE,
                    ),
                ))
            },
            {
                val header = publication.naturalMethod.row.structural.header
                publication.copy(naturalMethod = publication.naturalMethod.copy(
                    row = publication.naturalMethod.row.copy(
                        structural = publication.naturalMethod.row.structural.copy(
                            header = header.copy(
                                result = DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.Direct(
                                    DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.Leaf(
                                        DotNetGenericOwnerPhysicalTypeKind.OBJECT,
                                    ),
                                ),
                            ),
                        ),
                    ),
                ))
            },
            {
                val header = publication.naturalMethod.row.structural.header
                publication.copy(naturalMethod = publication.naturalMethod.copy(
                    row = publication.naturalMethod.row.copy(
                        structural = publication.naturalMethod.row.structural.copy(
                            header = header.copy(
                                receiverCarrier = DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.Leaf(
                                    DotNetGenericOwnerPhysicalTypeKind.OBJECT,
                                ),
                            ),
                        ),
                    ),
                ))
            },
        )
        hostileCopies.forEach { hostile ->
            assertFailsWith<IllegalArgumentException> { hostile() }
        }
    }

    @Test
    fun rejectsDuplicateFinalNaturalMethodObservations() {
        val fixture = PublicationFixture()
        val conflict = assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            fixture.inspect(currentMethods = listOf(
                fixture.naturalMethodObservation,
                fixture.naturalMethodObservation,
            )),
        )

        assertTrue(conflict.reason.contains("exactly one final MethodDef"))
    }

    @Test
    fun rejectsTheSameMethodIdentityObservedInAnotherEmissionScope() {
        val fixture = PublicationFixture()
        val conflict = assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            fixture.inspect(otherScopes = listOf(fixture.observations(
                scope = DotNetIlEmissionScope.STDLIB,
                typeDefs = emptyList(),
                methodDefs = listOf(fixture.naturalMethodObservation),
            ))),
        )

        assertTrue(conflict.reason.contains("more than one physical emission scope"))
    }

    private class PublicationFixture {
        val owner = IrFactoryImpl.buildClass {
            name = Name.identifier("Source")
            kind = ClassKind.INTERFACE
            modality = Modality.ABSTRACT
            visibility = DescriptorVisibilities.PUBLIC
        }
        private val resultParameter = owner.addTypeParameter {
            name = Name.identifier("T")
            variance = Variance.OUT_VARIANCE
        }
        val source: IrSimpleFunction = IrFactoryImpl.buildFun {
            name = Name.identifier("read")
            returnType = resultParameter.defaultType
            modality = Modality.ABSTRACT
            visibility = DescriptorVisibilities.PUBLIC
        }.also { member ->
            member.parent = owner
            owner.declarations += member
        }
        val unrelatedSource: IrSimpleFunction = IrFactoryImpl.buildFun {
            name = Name.identifier("unrelated")
            returnType = resultParameter.defaultType
            modality = Modality.ABSTRACT
            visibility = DescriptorVisibilities.PUBLIC
        }.also { unrelated ->
            unrelated.parent = owner
            owner.declarations += unrelated
        }
        private val member = DotNetPublishedGenericInterfaceMemberContract(
            LOGICAL_MEMBER_KEY,
            DotNetPublishedGenericInterfaceMemberRole.PRODUCER,
        )
        private val family = DotNetPhysicalDeclaration.PublishedGenericInterfaceFamily(
            ownerPath = NATURAL_OWNER_PATH,
            capabilityAssemblyName = "Fixture",
            capabilityOwnerPath = listOf("sample.SourceKotlinSemantic"),
            naturalTypeParameterVariances = listOf(
                DotNetGenericOwnerPhysicalTypeParameterVariance.COVARIANT,
            ),
            contract = DotNetPublishedGenericInterfaceFamilyContract(
                logicalOwnerKey = LOGICAL_OWNER_KEY,
                genericArity = 1,
                kind = DotNetPublishedGenericInterfaceFamilyKind.ROOT,
                rootLogicalOwnerKeys = listOf(LOGICAL_OWNER_KEY),
                directParents = emptyList(),
                lineageDepth = 0,
                declaredMembers = listOf(member),
                capabilityBindingKind = DotNetPublishedGenericInterfaceCapabilityBindingKind.OWNED,
                reusedParentLogicalOwnerKey = null,
            ),
        )
        val naturalType = observedType(
            key = 0,
            view = DotNetGenericInterfaceView.DECLARED,
        )
        val exactType = observedType(
            key = 1,
            view = DotNetGenericInterfaceView.EXACT,
        )
        val naturalTypeObservation = typeObservation(
            naturalType,
            NATURAL_OWNER_PATH,
            DotNetGenericOwnerPhysicalTypeParameterVariance.COVARIANT,
        )
        val exactTypeObservation = typeObservation(
            exactType,
            listOf("sample.Source__KotlinExact`1"),
            DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT,
        )
        val naturalMethodObservation = methodObservation(naturalType)

        fun inspect(
            currentTypes: List<DotNetGenericOwnerPhysicalTypeDefEmissionObservation> =
                listOf(naturalTypeObservation),
            currentMethods: List<DotNetGenericOwnerPhysicalMethodDefHeaderObservation> =
                listOf(naturalMethodObservation),
            otherScopes: List<DotNetGenericOwnerCompleteEmissionScopeObservations> = emptyList(),
        ) = inspectDotNetProducerGenericOwnerNaturalMethodDefPublication(
            logicalOwnerKey = LOGICAL_OWNER_KEY,
            logicalMemberKey = LOGICAL_MEMBER_KEY,
            owner = owner,
            source = source,
            family = family,
            member = member,
            logicalParameterDomains = emptyList(),
            logicalResultDomain = DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_OUTPUT,
            current = observations(
                scope = DotNetIlEmissionScope.USER,
                typeDefs = currentTypes,
                methodDefs = currentMethods,
            ),
            otherScopes = otherScopes,
        )

        fun observations(
            scope: DotNetIlEmissionScope,
            typeDefs: List<DotNetGenericOwnerPhysicalTypeDefEmissionObservation>,
            methodDefs: List<DotNetGenericOwnerPhysicalMethodDefHeaderObservation>,
        ) = DotNetGenericOwnerCompleteEmissionScopeObservations(
            scope,
            typeDefs,
            methodDefs,
            methodImpls = emptyList(),
        )

        fun methodObservation(
            physicalOwner: DotNetGenericOwnerObservedLocalTypeDef,
            physicalFunction: IrSimpleFunction = source,
            physicalName: String = "read",
        ): DotNetGenericOwnerPhysicalMethodDefHeaderObservation {
            val ownerParameter = DotNetGenericOwnerObservedMethodCarrier.OwnerParameter(
                physicalOwner,
                0,
            )
            return DotNetGenericOwnerPhysicalMethodDefHeaderObservation(
                physicalFunction = physicalFunction.symbol,
                physicalMethodIdentity = DotNetGenericOwnerPhysicalMethodDefIdentity.Local(
                    physicalFunction.symbol,
                    DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY,
                ),
                physicalMethodOwner = DotNetGenericOwnerObservedMethodDefOwner.Local(physicalOwner),
                physicalMethodName = physicalName,
                visibility = DotNetIlRawMethodDefVisibility.PUBLIC,
                dispatch = DotNetIlRawMethodDefDispatch(
                    isInstance = true,
                    isVirtual = true,
                    isNewSlot = true,
                    isAbstract = true,
                    isFinal = false,
                ),
                isHideBySig = true,
                isSpecialName = false,
                isRuntimeSpecialName = false,
                genericArity = 0,
                genericParameters = emptyList(),
                signature = DotNetGenericOwnerObservedMethodSignature(
                    receiverCarrier = DotNetGenericOwnerObservedMethodCarrier.LocalConstruction(
                        physicalOwner,
                        listOf(ownerParameter),
                    ),
                    returnCarrier = ownerParameter,
                    parameterCarriers = emptyList(),
                    hasSplitNullableResult = false,
                ),
            )
        }

        private fun observedType(
            key: Int,
            view: DotNetGenericInterfaceView,
        ): DotNetGenericOwnerObservedLocalTypeDef {
            val identity = DotNetGenericOwnerPhysicalTypeDefIdentity.Local(owner.symbol, view)
            return DotNetGenericOwnerObservedLocalTypeDef(
                physicalKey = DotNetGenericOwnerObservedPhysicalTypeDefKey(key),
                identity = identity,
                genericArity = 1,
                category = DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE,
            )
        }

        private fun typeObservation(
            physicalType: DotNetGenericOwnerObservedLocalTypeDef,
            physicalPath: List<String>,
            variance: DotNetGenericOwnerPhysicalTypeParameterVariance,
        ) = DotNetGenericOwnerPhysicalTypeDefEmissionObservation(
            physicalType = DotNetGenericOwnerObservedMethodDefOwner.Local(physicalType),
            physicalKey = physicalType.physicalKey,
            claimedAliases = physicalType.aliases,
            physicalTypePath = physicalPath,
            flags = DotNetIlRawTypeDefFlags(
                visibility = DotNetIlRawTypeDefVisibility.PUBLIC,
                layout = DotNetIlRawTypeDefLayout.AUTO,
                stringFormat = DotNetIlRawTypeDefStringFormat.ANSI,
                isInterface = true,
                isAbstract = true,
                isSealed = false,
                isBeforeFieldInit = false,
            ),
            genericParameters = listOf(
                DotNetGenericOwnerPhysicalTypeDefGenericParameterObservation(
                    variance,
                    constraints = emptyList(),
                ),
            ),
            directSupertypes = emptyList(),
        )

        companion object {
            const val LOGICAL_OWNER_KEY = "sample/Source|class"
            const val LOGICAL_MEMBER_KEY = "sample/Source.read|function"
            val NATURAL_OWNER_PATH = listOf("sample.Source`1")
        }
    }
}
