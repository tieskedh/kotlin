/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.declarations.MetadataSource
import org.jetbrains.kotlin.ir.declarations.impl.IrFactoryImpl
import org.jetbrains.kotlin.load.dotnet.DotNetClrAssemblyReference
import org.jetbrains.kotlin.load.dotnet.DotNetClrAssemblyMetadata
import org.jetbrains.kotlin.load.dotnet.DotNetClrClasspathAssembly
import org.jetbrains.kotlin.load.dotnet.DotNetClrGenericParameterDefinition
import org.jetbrains.kotlin.load.dotnet.DotNetClrGenericParameterKind
import org.jetbrains.kotlin.load.dotnet.DotNetClrImportedDeclarationGraph
import org.jetbrains.kotlin.load.dotnet.DotNetClrImportedMethodSource
import org.jetbrains.kotlin.load.dotnet.DotNetClrImportedTypeSource
import org.jetbrains.kotlin.load.dotnet.DotNetClrInterfaceImplementation
import org.jetbrains.kotlin.load.dotnet.DotNetClrMetadataHandle
import org.jetbrains.kotlin.load.dotnet.DotNetClrMethodDefinition
import org.jetbrains.kotlin.load.dotnet.DotNetClrMethodSignature
import org.jetbrains.kotlin.load.dotnet.DotNetClrPrimitiveType
import org.jetbrains.kotlin.load.dotnet.DotNetClrResolvedInterfaceImplementation
import org.jetbrains.kotlin.load.dotnet.DotNetClrResolvedMethodSignature
import org.jetbrains.kotlin.load.dotnet.DotNetClrResolvedTypeDefinition
import org.jetbrains.kotlin.load.dotnet.DotNetClrResolvedTypeHierarchy
import org.jetbrains.kotlin.load.dotnet.DotNetClrResolvedTypeSignature
import org.jetbrains.kotlin.load.dotnet.DotNetClrResolvedTypeView
import org.jetbrains.kotlin.load.dotnet.DotNetClrSignatureCallingConvention
import org.jetbrains.kotlin.load.dotnet.DotNetClrTypeDefinition
import org.jetbrains.kotlin.load.dotnet.DotNetClrTypeReference
import org.jetbrains.kotlin.load.dotnet.DotNetClrTypeSignature
import org.jetbrains.kotlin.load.dotnet.DotNetClrTypeSpecification
import org.jetbrains.kotlin.load.dotnet.DotNetManagedAssemblyIdentity
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

class DotNetRetainedForeignGenericOwnerPhysicalAuthorityTest {
    @Test
    fun `retained metadata binds exact owner MethodDef and generic parameter rows`() {
        val fixture = fixture(
            ownerParameterAttributes = REFERENCE_TYPE_CONSTRAINT_ATTRIBUTE,
            methodParameterAttributes = NOT_NULLABLE_VALUE_TYPE_CONSTRAINT_ATTRIBUTE or
                    DEFAULT_CONSTRUCTOR_CONSTRAINT_ATTRIBUTE or ALLOW_BY_REF_LIKE_ATTRIBUTE,
        )
        val declarations = assertIs<
                DotNetGenericOwnerPhysicalBindingResult.Bound<
                        DotNetGenericOwnerPhysicalDeclarationIndex,
                        >,
                >(
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeign(
                fixture.source,
                fixture.method,
            )
        ).value
        val ownerIdentity =
            DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(fixture.source)
        val methodIdentity =
            DotNetGenericOwnerPhysicalMethodDefIdentity.ForeignClr.retained(
                fixture.source,
                fixture.method,
            )

        val owner = assertNotNull(declarations.typeDescriptionOrNull(ownerIdentity))
        assertEquals(DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE, owner.category)
        assertEquals(1, owner.genericArity)
        assertEquals(
            DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT,
            owner.genericParameters.single().variance,
        )
        assertEquals(true, owner.genericParameters.single().hasReferenceTypeConstraint)
        assertEquals(false, owner.genericParameters.single().hasNotNullableValueTypeConstraint)
        assertEquals(false, owner.genericParameters.single().hasDefaultConstructorConstraint)
        assertEquals(false, owner.genericParameters.single().allowsByRefLike)
        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Bound(emptySet()),
            declarations.directSupertypeEdgesOrUnavailable(ownerIdentity),
        )

        val method = assertNotNull(declarations.methodDescriptionOrNull(methodIdentity))
        assertEquals(ownerIdentity, method.declaringType)
        assertEquals(DotNetGenericOwnerPhysicalMemberVisibility.PUBLIC, method.visibility)
        assertEquals(DotNetGenericOwnerPhysicalMemberDispatch.ABSTRACT, method.dispatch)
        assertEquals(true, method.signature.isInstance)
        assertEquals(1, method.signature.genericArity)
        assertEquals(
            DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT,
            method.genericParameters.single().variance,
        )
        assertEquals(true, method.genericParameters.single().hasNotNullableValueTypeConstraint)
        assertEquals(true, method.genericParameters.single().hasDefaultConstructorConstraint)
        assertEquals(true, method.genericParameters.single().allowsByRefLike)

        val result = assertIs<DotNetGenericOwnerPhysicalCallableResultLayoutReference.Direct>(
            method.signature.resultLayout,
        ).slot
        assertEquals(DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_OUTPUT, result.domain)
        assertEquals(
            DotNetGenericOwnerSymbolicCarrierReference.Parameter.unboundTypeParameterReference(
                ownerIdentity,
                0,
            ),
            result.carrier,
        )
        assertEquals(2, method.signature.parameterSlots.size)
        assertEquals(
            DotNetGenericOwnerPhysicalSlotDomain.DECLARATION_INDEPENDENT,
            method.signature.parameterSlots[0].domain,
        )
        assertEquals(
            DotNetGenericOwnerSymbolicCarrierReference.Parameter.methodParameterReference(
                methodIdentity,
                0,
            ),
            method.signature.parameterSlots[0].carrier,
        )
        assertEquals(
            DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_INPUT,
            method.signature.parameterSlots[1].domain,
        )
        assertEquals(
            DotNetGenericOwnerSymbolicCarrierReference.SzArray(
                DotNetGenericOwnerSymbolicCarrierReference.Parameter
                    .unboundTypeParameterReference(ownerIdentity, 0),
            ),
            method.signature.parameterSlots[1].carrier,
        )

        // Complete GenericParam authority is not itself a proof that String satisfies `class`.
        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            declarations.constructTypeOrError(
                ownerIdentity,
                listOf(DotNetGenericOwnerSymbolicCarrierReference.stringCarrier()),
            ),
        )
    }

    @Test
    fun `valid covariant metadata retains an output-only owner parameter`() {
        val fixture = fixture(
            ownerParameterAttributes = COVARIANT_ATTRIBUTE,
            ownerDependentInput = false,
        )
        val declarations = assertIs<
                DotNetGenericOwnerPhysicalBindingResult.Bound<
                        DotNetGenericOwnerPhysicalDeclarationIndex,
                        >,
                >(
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeign(
                fixture.source,
                fixture.method,
            )
        ).value
        val ownerIdentity =
            DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(fixture.source)
        val methodIdentity =
            DotNetGenericOwnerPhysicalMethodDefIdentity.ForeignClr.retained(
                fixture.source,
                fixture.method,
            )

        assertEquals(
            DotNetGenericOwnerPhysicalTypeParameterVariance.COVARIANT,
            assertNotNull(declarations.typeDescriptionOrNull(ownerIdentity))
                .genericParameters.single().variance,
        )
        assertEquals(
            DotNetGenericOwnerPhysicalSlotDomain.DECLARATION_INDEPENDENT,
            assertNotNull(declarations.methodDescriptionOrNull(methodIdentity))
                .signature.parameterSlots[1].domain,
        )
    }

    @Test
    fun `retained foreign MethodDef routes through exact receiver and argument provenance`() {
        val fixture = fixture()
        val declarations = boundDeclarations(fixture)
        val ownerIdentity = retainedOwnerIdentity(fixture)
        val ownerConstruction = boundConstruction(
            declarations,
            ownerIdentity,
            listOf(DotNetGenericOwnerSymbolicCarrierReference.int32Carrier()),
        )

        val route = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerPhysicalOperationRoute,
                >>(
            selectRetainedRoute(
                fixture,
                directValue(declarations, ownerConstruction),
                exactTransferArguments(declarations),
            )
        ).value

        assertEquals(retainedMethodIdentity(fixture), route.method.identity)
        assertEquals(ownerConstruction, route.requiredReceiverView.construction)
        assertEquals(
            listOf(DotNetGenericOwnerSymbolicCarrierReference.int32Carrier()),
            route.methodArguments,
        )
        assertEquals(
            listOf(
                DotNetGenericOwnerSymbolicCarrierReference.int32Carrier(),
                DotNetGenericOwnerSymbolicCarrierReference.SzArray(
                    DotNetGenericOwnerSymbolicCarrierReference.int32Carrier(),
                ),
            ),
            route.instantiatedSignature.parameterSlots.map { slot -> slot.carrier },
        )
        assertEquals(
            DotNetGenericOwnerSymbolicCarrierReference.int32Carrier(),
            assertIs<DotNetGenericOwnerPhysicalCallableResultLayoutReference.Direct>(
                route.instantiatedSignature.resultLayout,
            ).slot.carrier,
        )
        val producedResult = assertNotNull(route.producedResult)
        assertEquals(
            DotNetGenericOwnerProducedValueLayout.Direct(
                boundCarrier(
                    declarations,
                    DotNetGenericOwnerSymbolicCarrierReference.int32Carrier(),
                )
            ),
            producedResult.layout,
        )
        assertEquals(DotNetGenericOwnerPhysicalNullState.NON_NULL, producedResult.nullState)
    }

    @Test
    fun `logical widening preserves a selected retained foreign construction`() {
        val fixture = fixture()
        val declarations = boundDeclarations(fixture)
        val ownerConstruction = boundConstruction(
            declarations,
            retainedOwnerIdentity(fixture),
            listOf(DotNetGenericOwnerSymbolicCarrierReference.int32Carrier()),
        )
        val selectedView = DotNetGenericOwnerPhysicalView(ownerConstruction)
        val widenedReceiver = objectValueWithRetainedViews(
            declarations,
            listOf(selectedView),
            selectedView,
        )

        val route = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerPhysicalOperationRoute,
                >>(
            selectRetainedRoute(
                fixture,
                widenedReceiver,
                exactTransferArguments(declarations),
            )
        ).value

        assertEquals(ownerConstruction, route.requiredReceiverView.construction)
        assertEquals(
            DotNetGenericOwnerSymbolicCarrierReference.int32Carrier(),
            assertIs<DotNetGenericOwnerPhysicalCallableResultLayoutReference.Direct>(
                route.instantiatedSignature.resultLayout,
            ).slot.carrier,
        )

    }

    @Test
    fun `multiple retained constructions require selected view lineage`() {
        val fixture = fixture()
        val declarations = boundDeclarations(fixture)
        val ownerIdentity = retainedOwnerIdentity(fixture)
        val intView = DotNetGenericOwnerPhysicalView(boundConstruction(
            declarations,
            ownerIdentity,
            listOf(DotNetGenericOwnerSymbolicCarrierReference.int32Carrier()),
        ))
        val stringView = DotNetGenericOwnerPhysicalView(boundConstruction(
            declarations,
            ownerIdentity,
            listOf(DotNetGenericOwnerSymbolicCarrierReference.stringCarrier()),
        ))
        val ambiguousReceiver = objectValueWithRetainedViews(
            declarations,
            listOf(intView, stringView),
        )

        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            selectRetainedRoute(
                fixture,
                ambiguousReceiver,
                exactTransferArguments(declarations),
            ),
        )

        val selectedReceiver = objectValueWithRetainedViews(
            declarations,
            listOf(intView, stringView),
            intView,
        )
        val selectedRoute = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerPhysicalOperationRoute,
                >>(
            selectRetainedRoute(
                fixture,
                selectedReceiver,
                exactTransferArguments(declarations),
            )
        ).value
        assertEquals(intView, selectedRoute.requiredReceiverView)
    }

    @Test
    fun `broad foreign receiver and incompatible owner input remain unavailable`() {
        val fixture = fixture()
        val declarations = boundDeclarations(fixture)
        val ownerConstruction = boundConstruction(
            declarations,
            retainedOwnerIdentity(fixture),
            listOf(DotNetGenericOwnerSymbolicCarrierReference.int32Carrier()),
        )
        val broadReceiver = DotNetGenericOwnerProducedValueFact(
            DotNetGenericOwnerProducedValueLayout.Direct(
                boundCarrier(
                    declarations,
                    DotNetGenericOwnerSymbolicCarrierReference.objectCarrier(),
                )
            ),
            DotNetGenericOwnerPhysicalValueProvenance(DotNetGenericOwnerGuaranteedViews.Unknown),
            DotNetGenericOwnerPhysicalNullState.NON_NULL,
        )
        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            selectRetainedRoute(
                fixture,
                broadReceiver,
                exactTransferArguments(declarations),
            ),
        )

        val incompatibleArguments = listOf(
            directValue(
                declarations,
                DotNetGenericOwnerSymbolicCarrierReference.int32Carrier(),
            ),
            directValue(
                declarations,
                DotNetGenericOwnerSymbolicCarrierReference.SzArray(
                    DotNetGenericOwnerSymbolicCarrierReference.stringCarrier(),
                ),
            ),
        )
        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            selectRetainedRoute(
                fixture,
                directValue(declarations, ownerConstruction),
                incompatibleArguments,
            ),
        )
    }

    @Test
    fun `retained recursive result produces its exact foreign view`() {
        val fixture = fixture(recursiveResult = true)
        val declarations = boundDeclarations(fixture)
        val ownerConstruction = boundConstruction(
            declarations,
            retainedOwnerIdentity(fixture),
            listOf(DotNetGenericOwnerSymbolicCarrierReference.int32Carrier()),
        )

        val route = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerPhysicalOperationRoute,
                >>(
            selectRetainedRoute(
                fixture,
                directValue(declarations, ownerConstruction),
                exactTransferArguments(declarations),
            )
        ).value
        val result = assertNotNull(route.producedResult)
        assertEquals(
            DotNetGenericOwnerProducedValueLayout.Direct(
                boundCarrier(declarations, ownerConstruction),
            ),
            result.layout,
        )
        assertEquals(DotNetGenericOwnerPhysicalNullState.MAYBE_NULL, result.nullState)
        val evidence = assertIs<DotNetGenericOwnerGuaranteedViews.Known>(
            result.provenance.guaranteedViews,
        ).evidenceByView
        assertEquals(
            setOf(DotNetGenericOwnerPhysicalViewEvidence.FROZEN_PARAMETER_OR_RESULT),
            evidence[DotNetGenericOwnerPhysicalView(ownerConstruction)],
        )
    }

    @Test
    fun `memberless retained TypeDef proves an inherited foreign MethodDef route`() {
        val fixture = fixture(includeInheritedReceiver = true)
        val receiverSource = assertNotNull(fixture.inheritedReceiverSource)
        assertEquals(
            emptyList(),
            receiverSource.assembly.metadata.methodDefinitions.filter { candidate ->
                candidate.declaringType == receiverSource.declaringType.handle
            },
        )
        val declarations = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerPhysicalDeclarationIndex,
                >>(
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeignInheritedReceiver(
                fixture.source,
                fixture.method,
                receiverSource,
            )
        ).value
        val receiverIdentity =
            DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(receiverSource)
        val receiverConstruction = boundConstruction(
            declarations,
            receiverIdentity,
            emptyList(),
        )
        val parentConstruction = boundConstruction(
            declarations,
            retainedOwnerIdentity(fixture),
            listOf(DotNetGenericOwnerSymbolicCarrierReference.int32Carrier()),
        )

        val closure = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerPhysicalInterfaceViewClosure,
                >>(
            declarations.physicalInterfaceViewClosureOrError(receiverConstruction)
        ).value
        assertEquals(true, closure.isComplete)
        assertEquals(
            setOf(
                DotNetGenericOwnerPhysicalView(receiverConstruction),
                DotNetGenericOwnerPhysicalView(parentConstruction),
            ),
            closure.interfaceViews,
        )

        val receiver = directValue(declarations, receiverConstruction)
        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            selectRetainedRoute(
                fixture,
                receiver,
                exactTransferArguments(declarations),
            ),
        )
        val route = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerPhysicalOperationRoute,
                >>(
            selectRetainedRoute(
                fixture,
                receiver,
                exactTransferArguments(declarations),
                receiverSource,
            )
        ).value
        assertEquals(parentConstruction, route.requiredReceiverView.construction)
        assertEquals(retainedMethodIdentity(fixture), route.method.identity)
        assertEquals(
            DotNetGenericOwnerSymbolicCarrierReference.int32Carrier(),
            assertIs<DotNetGenericOwnerPhysicalCallableResultLayoutReference.Direct>(
                route.instantiatedSignature.resultLayout,
            ).slot.carrier,
        )

    }

    @Test
    fun `cross assembly memberless TypeDef proves inherited foreign MethodDef route`() {
        val fixture = crossAssemblyFixture()
        val receiverSource = assertNotNull(fixture.inheritedReceiverSource)
        assertEquals(false, fixture.source.assembly === receiverSource.assembly)
        assertEquals(
            fixture.source.declaringType.handle,
            receiverSource.declaringType.handle,
        )

        val declarations = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerPhysicalDeclarationIndex,
                >>(
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeignInheritedReceiver(
                fixture.source,
                fixture.method,
                receiverSource,
            )
        ).value
        val receiverIdentity =
            DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(receiverSource)
        val receiverConstruction = boundConstruction(
            declarations,
            receiverIdentity,
            emptyList(),
        )
        val parentConstruction = boundConstruction(
            declarations,
            retainedOwnerIdentity(fixture),
            listOf(DotNetGenericOwnerSymbolicCarrierReference.int32Carrier()),
        )

        val route = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerPhysicalOperationRoute,
                >>(
            selectRetainedRoute(
                fixture,
                directValue(declarations, receiverConstruction),
                exactTransferArguments(declarations),
                receiverSource,
            )
        ).value
        assertEquals(parentConstruction, route.requiredReceiverView.construction)
        assertEquals(retainedMethodIdentity(fixture), route.method.identity)
    }

    @Test
    fun `cross assembly inherited route rejects an unbound AssemblyRef`() {
        val fixture = crossAssemblyFixture(parentReferenceVersion = "2.0.0.0")

        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeignInheritedReceiver(
                fixture.source,
                fixture.method,
                assertNotNull(fixture.inheritedReceiverSource),
            )
        )
    }

    @Test
    fun `TypeDef carrier rejects a mismatched retained hierarchy`() {
        val fixture = fixture(includeInheritedReceiver = true)
        val receiverSource = assertNotNull(fixture.inheritedReceiverSource)

        assertFailsWith<IllegalArgumentException> {
            DotNetClrImportedTypeSource(
                receiverSource.assembly,
                fixture.source.declaringType,
                receiverSource.declaringHierarchy,
                receiverSource.graph,
            )
        }
    }

    @Test
    fun `memberless IR class resolves its retained TypeDef and inherited construction`() {
        val fixture = fixture(includeInheritedReceiver = true)
        val receiverSource = assertNotNull(fixture.inheritedReceiverSource)
        val importedClass = IrFactoryImpl.buildClass {
            name = Name.identifier("IntSource")
            kind = ClassKind.INTERFACE
            modality = Modality.ABSTRACT
            visibility = DescriptorVisibilities.PUBLIC
        }.apply {
            metadata = object : MetadataSource.Class {
                override val name: Name = this@apply.name
                override val platformDeclarationSource: Any = receiverSource

                override fun recordLocalClassType(type: FqName) = Unit

                override fun asFirSymbol(): Any? = null
            }
        }
        val referencedAssemblies = mutableListOf<DotNetClrClasspathAssembly.WithoutCarrier>()
        val declarations = DotNetClrImportedDeclarations(
            assemblyReferenceSink = referencedAssemblies::add,
            coreLibraryReference = "System.Private.CoreLib",
        )

        val classInfo = assertNotNull(declarations.classInfoOrNull(importedClass))

        assertEquals(emptyList(), importedClass.declarations)
        assertEquals("Foreign.IntSource", classInfo.ilClassName)
        assertEquals(0, classInfo.typeParameterCount)
        val inherited = assertIs<DotNetIlValueType.GenericInstance>(classInfo.interfaces.single())
        assertEquals("Foreign.Source`1", inherited.classInfo.ilClassName)
        assertEquals(listOf(DotNetIlValueType.Int32), inherited.arguments)
        assertEquals(listOf(receiverSource.assembly), referencedAssemblies.distinct())
    }

    @Test
    fun `cross assembly memberless IR class retains both exact assembly references`() {
        val fixture = crossAssemblyFixture()
        val receiverSource = assertNotNull(fixture.inheritedReceiverSource)
        val importedClass = IrFactoryImpl.buildClass {
            name = Name.identifier("IntSource")
            kind = ClassKind.INTERFACE
            modality = Modality.ABSTRACT
            visibility = DescriptorVisibilities.PUBLIC
        }.apply {
            metadata = object : MetadataSource.Class {
                override val name: Name = this@apply.name
                override val platformDeclarationSource: Any = receiverSource

                override fun recordLocalClassType(type: FqName) = Unit

                override fun asFirSymbol(): Any? = null
            }
        }
        val referencedAssemblies = mutableListOf<DotNetClrClasspathAssembly.WithoutCarrier>()
        val declarations = DotNetClrImportedDeclarations(
            assemblyReferenceSink = referencedAssemblies::add,
            coreLibraryReference = "System.Private.CoreLib",
        )

        val classInfo = assertNotNull(declarations.classInfoOrNull(importedClass))

        assertEquals("Foreign.Child", classInfo.assemblyName)
        val inherited = assertIs<DotNetIlValueType.GenericInstance>(classInfo.interfaces.single())
        assertEquals("Foreign.Authority", inherited.classInfo.assemblyName)
        assertEquals(listOf(DotNetIlValueType.Int32), inherited.arguments)
        assertEquals(
            listOf(receiverSource.assembly, fixture.source.assembly),
            referencedAssemblies.distinct(),
        )
    }

    @Test
    fun `retained InterfaceImpl disagreement with raw TypeSpec is a conflict`() {
        val fixture = fixture(
            includeInheritedReceiver = true,
            retainedInheritedArgument = DotNetClrResolvedTypeSignature.Primitive(
                DotNetClrPrimitiveType.STRING,
            ),
        )

        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeignInheritedReceiver(
                fixture.source,
                fixture.method,
                assertNotNull(fixture.inheritedReceiverSource),
            )
        )
    }

    @Test
    fun `open retained InterfaceImpl forwards its receiver binder without boxing`() {
        val fixture = fixture(
            includeInheritedReceiver = true,
            openInheritedReceiver = true,
        )
        val receiverSource = assertNotNull(fixture.inheritedReceiverSource)
        val declarations = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerPhysicalDeclarationIndex,
                >>(
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeignInheritedReceiver(
                fixture.source,
                fixture.method,
                receiverSource,
            )
        ).value
        val receiverIdentity =
            DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(receiverSource)
        val parentIdentity = retainedOwnerIdentity(fixture)
        val receiverParameter = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerSymbolicCarrierReference.Parameter,
                >>(
            declarations.typeParameterOrError(receiverIdentity, 0)
        ).value
        val edgeSet = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                Set<DotNetGenericOwnerPhysicalDirectSupertypeEdgeReference>,
                >>(
            declarations.directSupertypeEdgesOrUnavailable(receiverIdentity)
        ).value
        assertEquals(
            DotNetGenericOwnerSymbolicCarrierReference.Constructed.unboundTypeReference(
                parentIdentity,
                listOf(receiverParameter),
            ),
            edgeSet.single().target,
        )

        listOf(
            DotNetGenericOwnerSymbolicCarrierReference.int32Carrier(),
            DotNetGenericOwnerSymbolicCarrierReference.stringCarrier(),
        ).forEach { argument ->
            val receiverConstruction = boundConstruction(
                declarations,
                receiverIdentity,
                listOf(argument),
            )
            val parentConstruction = boundConstruction(
                declarations,
                parentIdentity,
                listOf(argument),
            )
            val closure = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                    DotNetGenericOwnerPhysicalInterfaceViewClosure,
                    >>(
                declarations.physicalInterfaceViewClosureOrError(receiverConstruction)
            ).value
            assertEquals(true, closure.isComplete)
            assertEquals(
                setOf(
                    DotNetGenericOwnerPhysicalView(receiverConstruction),
                    DotNetGenericOwnerPhysicalView(parentConstruction),
                ),
                closure.interfaceViews,
            )

            val route = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                    DotNetGenericOwnerPhysicalOperationRoute,
                    >>(
                selectRetainedRoute(
                    fixture,
                    directValue(declarations, receiverConstruction),
                    exactTransferArguments(declarations, argument),
                    receiverSource,
                )
            ).value
            assertEquals(parentConstruction, route.requiredReceiverView.construction)
            assertEquals(
                argument,
                assertIs<DotNetGenericOwnerPhysicalCallableResultLayoutReference.Direct>(
                    route.instantiatedSignature.resultLayout,
                ).slot.carrier,
            )
        }
    }

    @Test
    fun `open retained InterfaceImpl substitutes its binder inside an SZ array`() {
        val rawArray = DotNetClrTypeSignature.SzArray(
            DotNetClrTypeSignature.GenericParameter(DotNetClrGenericParameterKind.TYPE, 0),
        )
        val retainedArray = DotNetClrResolvedTypeSignature.SzArray(
            DotNetClrResolvedTypeSignature.GenericParameter(
                DotNetClrGenericParameterKind.TYPE,
                0,
            ),
        )
        val fixture = fixture(
            includeInheritedReceiver = true,
            openInheritedReceiver = true,
            rawInheritedArgument = rawArray,
            retainedInheritedArgument = retainedArray,
        )
        val receiverSource = assertNotNull(fixture.inheritedReceiverSource)
        val declarations = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerPhysicalDeclarationIndex,
                >>(
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeignInheritedReceiver(
                fixture.source,
                fixture.method,
                receiverSource,
            )
        ).value
        val int32 = DotNetGenericOwnerSymbolicCarrierReference.int32Carrier()
        val int32Array = DotNetGenericOwnerSymbolicCarrierReference.SzArray(int32)
        val receiverConstruction = boundConstruction(
            declarations,
            DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(receiverSource),
            listOf(int32),
        )
        val parentConstruction = boundConstruction(
            declarations,
            retainedOwnerIdentity(fixture),
            listOf(int32Array),
        )

        val route = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerPhysicalOperationRoute,
                >>(
            selectRetainedRoute(
                fixture,
                directValue(declarations, receiverConstruction),
                exactTransferArguments(declarations, int32Array),
                receiverSource,
            )
        ).value
        assertEquals(parentConstruction, route.requiredReceiverView.construction)
        assertEquals(
            int32Array,
            assertIs<DotNetGenericOwnerPhysicalCallableResultLayoutReference.Direct>(
                route.instantiatedSignature.resultLayout,
            ).slot.carrier,
        )
    }

    @Test
    fun `closed receiver view cannot authorize an open retained TypeDef`() {
        val fixture = fixture(
            includeInheritedReceiver = true,
            openInheritedReceiver = true,
            closedInheritedReceiverView = true,
        )

        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeignInheritedReceiver(
                fixture.source,
                fixture.method,
                assertNotNull(fixture.inheritedReceiverSource),
            ),
        )
    }

    @Test
    fun `open receiver retains CLR variance while forwarding its binder`() {
        val fixture = fixture(
            ownerParameterAttributes = COVARIANT_ATTRIBUTE,
            ownerDependentInput = false,
            includeInheritedReceiver = true,
            openInheritedReceiver = true,
            inheritedReceiverParameterAttributes = COVARIANT_ATTRIBUTE,
        )
        val receiverSource = assertNotNull(fixture.inheritedReceiverSource)
        val declarations = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerPhysicalDeclarationIndex,
                >>(
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeignInheritedReceiver(
                fixture.source,
                fixture.method,
                receiverSource,
            )
        ).value
        val receiverIdentity =
            DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(receiverSource)
        assertEquals(
            DotNetGenericOwnerPhysicalTypeParameterVariance.COVARIANT,
            assertNotNull(declarations.typeDescriptionOrNull(receiverIdentity))
                .genericParameters.single().variance,
        )

        val stringCarrier = DotNetGenericOwnerSymbolicCarrierReference.stringCarrier()
        val receiverConstruction = boundConstruction(
            declarations,
            receiverIdentity,
            listOf(stringCarrier),
        )
        val route = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerPhysicalOperationRoute,
                >>(
            selectRetainedRoute(
                fixture,
                directValue(declarations, receiverConstruction),
                exactTransferArguments(declarations, stringCarrier),
                receiverSource,
            )
        ).value
        assertEquals(
            stringCarrier,
            assertIs<DotNetGenericOwnerPhysicalCallableResultLayoutReference.Direct>(
                route.instantiatedSignature.resultLayout,
            ).slot.carrier,
        )
    }

    @Test
    fun `open receiver retains contravariant CLR binder authority`() {
        val int32Raw = DotNetClrTypeSignature.Primitive(DotNetClrPrimitiveType.INT32)
        val int32Retained = DotNetClrResolvedTypeSignature.Primitive(
            DotNetClrPrimitiveType.INT32,
        )
        val fixture = fixture(
            ownerParameterAttributes = CONTRAVARIANT_ATTRIBUTE,
            rawReturnType = int32Raw,
            retainedReturnType = int32Retained,
            includeInheritedReceiver = true,
            openInheritedReceiver = true,
            inheritedReceiverParameterAttributes = CONTRAVARIANT_ATTRIBUTE,
        )
        val receiverSource = assertNotNull(fixture.inheritedReceiverSource)
        val declarations = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerPhysicalDeclarationIndex,
                >>(
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeignInheritedReceiver(
                fixture.source,
                fixture.method,
                receiverSource,
            )
        ).value
        val receiverIdentity =
            DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(receiverSource)
        assertEquals(
            DotNetGenericOwnerPhysicalTypeParameterVariance.CONTRAVARIANT,
            assertNotNull(declarations.typeDescriptionOrNull(receiverIdentity))
                .genericParameters.single().variance,
        )
    }

    @Test
    fun `open retained InterfaceImpl cannot escape its receiver binder`() {
        val escapedParameter = DotNetClrTypeSignature.GenericParameter(
            DotNetClrGenericParameterKind.TYPE,
            1,
        )
        val fixture = fixture(
            includeInheritedReceiver = true,
            openInheritedReceiver = true,
            rawInheritedArgument = escapedParameter,
            retainedInheritedArgument = DotNetClrResolvedTypeSignature.GenericParameter(
                DotNetClrGenericParameterKind.TYPE,
                1,
            ),
        )

        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeignInheritedReceiver(
                fixture.source,
                fixture.method,
                assertNotNull(fixture.inheritedReceiverSource),
            )
        )
    }

    @Test
    fun `constrained open receiver remains outside the first forwarding grammar`() {
        val fixture = fixture(
            includeInheritedReceiver = true,
            openInheritedReceiver = true,
            inheritedReceiverParameterAttributes = REFERENCE_TYPE_CONSTRAINT_ATTRIBUTE,
        )

        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeignInheritedReceiver(
                fixture.source,
                fixture.method,
                assertNotNull(fixture.inheritedReceiverSource),
            ),
        )
    }

    @Test
    fun `missing retained InterfaceImpl cannot prove the inherited MethodDef route`() {
        val fixture = fixture(
            includeInheritedReceiver = true,
            inheritedInterfaceCount = 0,
        )

        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeignInheritedReceiver(
                fixture.source,
                fixture.method,
                assertNotNull(fixture.inheritedReceiverSource),
            ),
        )
    }

    @Test
    fun `multiple exact InterfaceImpl rows preserve every view and select owner by identity`() {
        for (relatedFirst in listOf(true, false)) {
            val fixture = fixture(
                includeInheritedReceiver = true,
                includeInheritedRelatedEdge = true,
                inheritedRelatedEdgeFirst = relatedFirst,
            )
            val receiverSource = assertNotNull(fixture.inheritedReceiverSource)
            val relatedType = assertNotNull(fixture.relatedType)
            assertEquals(
                relatedFirst,
                receiverSource.declaringHierarchy.interfaces.first().interfaceType.type
                    .hasSameIdentityAs(relatedType),
            )
            val declarations = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                    DotNetGenericOwnerPhysicalDeclarationIndex,
                    >>(
                DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeignInheritedReceiver(
                    fixture.source,
                    fixture.method,
                    receiverSource,
                )
            ).value
            val receiverIdentity =
                DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(receiverSource)
            val parentIdentity = retainedOwnerIdentity(fixture)
            val relatedIdentity =
                DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(
                    receiverSource,
                    relatedType,
                )
            val receiverConstruction = boundConstruction(
                declarations,
                receiverIdentity,
                emptyList(),
            )
            val parentConstruction = boundConstruction(
                declarations,
                parentIdentity,
                listOf(DotNetGenericOwnerSymbolicCarrierReference.int32Carrier()),
            )
            val relatedConstruction = boundConstruction(
                declarations,
                relatedIdentity,
                emptyList(),
            )

            val directEdges = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                    Set<DotNetGenericOwnerPhysicalDirectSupertypeEdgeReference>,
                    >>(
                declarations.directSupertypeEdgesOrUnavailable(receiverIdentity)
            ).value
            assertEquals(
                setOf<DotNetGenericOwnerSymbolicCarrierReference>(
                    parentConstruction,
                    relatedConstruction,
                ),
                directEdges.mapTo(linkedSetOf()) { edge -> edge.target },
            )
            val closure = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                    DotNetGenericOwnerPhysicalInterfaceViewClosure,
                    >>(
                declarations.physicalInterfaceViewClosureOrError(receiverConstruction)
            ).value
            assertEquals(true, closure.isComplete)
            assertEquals(
                setOf(
                    DotNetGenericOwnerPhysicalView(receiverConstruction),
                    DotNetGenericOwnerPhysicalView(parentConstruction),
                    DotNetGenericOwnerPhysicalView(relatedConstruction),
                ),
                closure.interfaceViews,
            )

            val route = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                    DotNetGenericOwnerPhysicalOperationRoute,
                    >>(
                selectRetainedRoute(
                    fixture,
                    directValue(declarations, receiverConstruction),
                    exactTransferArguments(declarations),
                    receiverSource,
                )
            ).value
            assertEquals(parentConstruction, route.requiredReceiverView.construction)
            assertEquals(retainedMethodIdentity(fixture), route.method.identity)
        }
    }

    @Test
    fun `distinct constructions of one owner require independently proven lineage`() {
        for (booleanEdgeFirst in listOf(true, false)) {
            val booleanCarrier = DotNetGenericOwnerSymbolicCarrierReference.booleanCarrier()
            val fixture = fixture(
                includeInheritedReceiver = true,
                secondRawInheritedArgument = DotNetClrTypeSignature.Primitive(
                    DotNetClrPrimitiveType.BOOLEAN,
                ),
                secondRetainedInheritedArgument = DotNetClrResolvedTypeSignature.Primitive(
                    DotNetClrPrimitiveType.BOOLEAN,
                ),
                secondInheritedEdgeFirst = booleanEdgeFirst,
            )
            val receiverSource = assertNotNull(fixture.inheritedReceiverSource)
            val declarations = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                    DotNetGenericOwnerPhysicalDeclarationIndex,
                    >>(
                DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeignInheritedReceiver(
                    fixture.source,
                    fixture.method,
                    receiverSource,
                )
            ).value
            val receiverConstruction = boundConstruction(
                declarations,
                DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(receiverSource),
                emptyList(),
            )
            val ownerIdentity = retainedOwnerIdentity(fixture)
            val intConstruction = boundConstruction(
                declarations,
                ownerIdentity,
                listOf(DotNetGenericOwnerSymbolicCarrierReference.int32Carrier()),
            )
            val booleanConstruction = boundConstruction(
                declarations,
                ownerIdentity,
                listOf(booleanCarrier),
            )
            val intView = DotNetGenericOwnerPhysicalView(intConstruction)
            val booleanView = DotNetGenericOwnerPhysicalView(booleanConstruction)
            val receiver = directValue(declarations, receiverConstruction)

            val closure = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                    DotNetGenericOwnerPhysicalInterfaceViewClosure,
                    >>(
                declarations.physicalInterfaceViewClosureOrError(receiverConstruction)
            ).value
            assertEquals(true, closure.isComplete)
            assertEquals(
                setOf(
                    DotNetGenericOwnerPhysicalView(receiverConstruction),
                    intView,
                    booleanView,
                ),
                closure.interfaceViews,
            )
            assertEquals(
                DotNetGenericOwnerPhysicalBindingResult.Unavailable,
                selectRetainedRoute(
                    fixture,
                    receiver,
                    exactTransferArguments(declarations),
                    receiverSource,
                ),
            )

            listOf(
                intView to DotNetGenericOwnerSymbolicCarrierReference.int32Carrier(),
                booleanView to booleanCarrier,
            ).forEach { entry ->
                val selectedView = entry.first
                val ownerArgument = entry.second
                val selectedReceiver = assertNotNull(
                    receiver.selectRecordedPhysicalInterfaceViewOrNull(
                        declarations,
                        selectedView,
                    )
                )
                val route = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                        DotNetGenericOwnerPhysicalOperationRoute,
                        >>(
                    selectRetainedRoute(
                        fixture,
                        selectedReceiver,
                        exactTransferArguments(declarations, ownerArgument),
                        receiverSource,
                    )
                ).value
                assertEquals(selectedView, route.requiredReceiverView)
                assertEquals(
                    ownerArgument,
                    assertIs<DotNetGenericOwnerPhysicalCallableResultLayoutReference.Direct>(
                        route.instantiatedSignature.resultLayout,
                    ).slot.carrier,
                )
            }

            val fabricatedObjectView = DotNetGenericOwnerPhysicalView(
                boundConstruction(
                    declarations,
                    ownerIdentity,
                    listOf(DotNetGenericOwnerSymbolicCarrierReference.objectCarrier()),
                )
            )
            assertEquals(
                null,
                receiver.selectRecordedPhysicalInterfaceViewOrNull(
                    declarations,
                    fabricatedObjectView,
                )
            )
        }
    }

    @Test
    fun `duplicate InterfaceImpl rows reaching the selected owner are a conflict`() {
        val fixture = fixture(
            includeInheritedReceiver = true,
            inheritedInterfaceCount = 2,
        )

        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeignInheritedReceiver(
                fixture.source,
                fixture.method,
                assertNotNull(fixture.inheritedReceiverSource),
            ),
        )
    }

    @Test
    fun `retained InterfaceImpl outside the closed carrier grammar is unavailable`() {
        val fixture = fixture(
            includeInheritedReceiver = true,
            rawInheritedArgument = DotNetClrTypeSignature.Primitive(
                DotNetClrPrimitiveType.INT64,
            ),
            retainedInheritedArgument = DotNetClrResolvedTypeSignature.Primitive(
                DotNetClrPrimitiveType.INT64,
            ),
        )

        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeignInheritedReceiver(
                fixture.source,
                fixture.method,
                assertNotNull(fixture.inheritedReceiverSource),
            ),
        )
    }

    @Test
    fun `retained receiver from another selected graph cannot authorize an inherited route`() {
        val methodFixture = fixture()
        val receiverFixture = fixture(includeInheritedReceiver = true)

        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeignInheritedReceiver(
                methodFixture.source,
                methodFixture.method,
                assertNotNull(receiverFixture.inheritedReceiverSource),
            ),
        )
    }

    @Test
    fun `caller-authored foreign descriptions cannot bypass retained metadata adapter`() {
        val fixture = fixture()
        val retained = assertIs<
                DotNetGenericOwnerPhysicalBindingResult.Bound<
                        DotNetGenericOwnerPhysicalDeclarationIndex,
                        >,
                >(
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeign(
                fixture.source,
                fixture.method,
            )
        ).value
        val ownerIdentity =
            DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(fixture.source)
        val methodIdentity =
            DotNetGenericOwnerPhysicalMethodDefIdentity.ForeignClr.retained(
                fixture.source,
                fixture.method,
            )
        val owner = assertNotNull(retained.typeDescriptionOrNull(ownerIdentity))
        val method = assertNotNull(retained.methodDescriptionOrNull(methodIdentity))

        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            DotNetGenericOwnerPhysicalDeclarationIndex.bind(
                DotNetGenericOwnerPhysicalAuthorityEpoch.BOUND_DECLARATION_INDEX,
                listOf(owner),
                listOf(method),
                listOf(DotNetGenericOwnerPhysicalDirectSupertypeEdgeSet(ownerIdentity, emptyList())),
            ),
        )
    }

    @Test
    fun `resolved carrier disagreement with raw MethodDef is a declaration conflict`() {
        val fixture = fixture(
            retainedReturnType = DotNetClrResolvedTypeSignature.Primitive(
                DotNetClrPrimitiveType.STRING,
            ),
        )

        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeign(
                fixture.source,
                fixture.method,
            )
        )
    }

    @Test
    fun `valid foreign carrier outside current leaf grammar remains unavailable`() {
        val fixture = fixture(
            rawReturnType = DotNetClrTypeSignature.Primitive(DotNetClrPrimitiveType.INT64),
            retainedReturnType = DotNetClrResolvedTypeSignature.Primitive(
                DotNetClrPrimitiveType.INT64,
            ),
        )

        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeign(
                fixture.source,
                fixture.method,
            ),
        )
    }

    @Test
    fun `recursive owner construction retains its exact foreign TypeDef binder`() {
        val fixture = fixture(recursiveResult = true)
        val declarations = assertIs<
                DotNetGenericOwnerPhysicalBindingResult.Bound<
                        DotNetGenericOwnerPhysicalDeclarationIndex,
                        >,
                >(
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeign(
                fixture.source,
                fixture.method,
            )
        ).value
        val ownerIdentity =
            DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(fixture.source)
        val methodIdentity =
            DotNetGenericOwnerPhysicalMethodDefIdentity.ForeignClr.retained(
                fixture.source,
                fixture.method,
            )
        val result = assertIs<DotNetGenericOwnerPhysicalCallableResultLayoutReference.Direct>(
            assertNotNull(declarations.methodDescriptionOrNull(methodIdentity)).signature.resultLayout,
        ).slot

        assertEquals(DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_OUTPUT, result.domain)
        assertEquals(
            DotNetGenericOwnerSymbolicCarrierReference.Constructed.unboundTypeReference(
                ownerIdentity,
                listOf(
                    DotNetGenericOwnerSymbolicCarrierReference.Parameter
                        .unboundTypeParameterReference(ownerIdentity, 0)
                ),
            ),
            result.carrier,
        )
    }

    @Test
    fun `CLR variance on a MethodDef generic parameter is contradictory metadata`() {
        val fixture = fixture(methodParameterAttributes = COVARIANT_ATTRIBUTE)

        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeign(
                fixture.source,
                fixture.method,
            )
        )
    }

    @Test
    fun `reserved combined CLR variance is a declaration conflict`() {
        val fixture = fixture(
            ownerParameterAttributes = COVARIANT_ATTRIBUTE or CONTRAVARIANT_ATTRIBUTE,
        )

        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeign(
                fixture.source,
                fixture.method,
            )
        )
    }

    @Test
    fun `unknown GenericParam flags remain outside the admitted grammar`() {
        val fixture = fixture(ownerParameterAttributes = UNKNOWN_GENERIC_PARAMETER_ATTRIBUTE)

        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeign(
                fixture.source,
                fixture.method,
            ),
        )
    }

    @Test
    fun `retained hierarchy may not omit a raw InterfaceImpl row`() {
        val fixture = fixture(rawInterfaceEdge = true)

        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeign(
                fixture.source,
                fixture.method,
            )
        )
    }

    @Test
    fun `complete retained hierarchy outside the root grammar is unavailable`() {
        val fixture = fixture(
            rawInterfaceEdge = true,
            retainedInterfaceEdge = true,
        )

        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeign(
                fixture.source,
                fixture.method,
            ),
        )
    }

    @Test
    fun `closed owner view cannot become open TypeDef authority`() {
        val fixture = fixture(closedOwnerView = true)

        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeign(
                fixture.source,
                fixture.method,
            ),
        )
    }

    @Test
    fun `a MethodDef outside the retained source is a conflict without constructing its identity`() {
        val fixture = fixture()
        val detachedMethod = fixture.method.copy(
            handle = DotNetClrMetadataHandle(METHOD_DEF_TABLE, 2),
            name = "Detached",
        )

        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeign(
                fixture.source,
                detachedMethod,
            )
        )
    }

    @Test
    fun `foreign TypeDef identities retain exact selected rows beyond the declaring source`() {
        val fixture = fixture(includeRelatedType = true)
        val declaring =
            DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(fixture.source)
        val related =
            DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(
                fixture.source,
                assertNotNull(fixture.relatedType),
            )

        assertNotEquals(declaring, related)
        assertEquals(
            related,
            DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(
                fixture.source,
                assertNotNull(fixture.relatedType),
            ),
        )
    }

    @Test
    fun `advancing an index cannot authenticate another selected foreign row`() {
        val fixture = fixture(includeRelatedType = true)
        val retained = assertIs<
                DotNetGenericOwnerPhysicalBindingResult.Bound<
                        DotNetGenericOwnerPhysicalDeclarationIndex,
                        >,
                >(
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeign(
                fixture.source,
                fixture.method,
            )
        ).value
        val relatedIdentity =
            DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(
                fixture.source,
                assertNotNull(fixture.relatedType),
            )

        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            retained.advance(
                DotNetGenericOwnerPhysicalAuthorityEpoch.SEALED_EMISSION_SIGNATURE_INDEX,
                listOf(
                    DotNetGenericOwnerPhysicalTypeDefReference(
                        relatedIdentity,
                        emptyList(),
                        DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE,
                    )
                ),
                emptyList(),
                listOf(
                    DotNetGenericOwnerPhysicalDirectSupertypeEdgeSet(
                        relatedIdentity,
                        emptyList(),
                    )
                ),
            ),
        )
    }

    private fun boundDeclarations(
        fixture: Fixture,
    ): DotNetGenericOwnerPhysicalDeclarationIndex = assertIs<
            DotNetGenericOwnerPhysicalBindingResult.Bound<
                    DotNetGenericOwnerPhysicalDeclarationIndex,
                    >,
            >(
        DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeign(
            fixture.source,
            fixture.method,
        )
    ).value

    private fun retainedOwnerIdentity(
        fixture: Fixture,
    ): DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr =
        DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(fixture.source)

    private fun retainedMethodIdentity(
        fixture: Fixture,
    ): DotNetGenericOwnerPhysicalMethodDefIdentity.ForeignClr =
        DotNetGenericOwnerPhysicalMethodDefIdentity.ForeignClr.retained(
            fixture.source,
            fixture.method,
        )

    private fun boundConstruction(
        declarations: DotNetGenericOwnerPhysicalDeclarationIndex,
        definition: DotNetGenericOwnerPhysicalTypeDefIdentity,
        arguments: List<DotNetGenericOwnerSymbolicCarrierReference>,
    ): DotNetGenericOwnerSymbolicCarrierReference.Constructed = assertIs<
            DotNetGenericOwnerPhysicalBindingResult.Bound<
                    DotNetGenericOwnerSymbolicCarrierReference.Constructed,
                    >,
            >(
        declarations.constructTypeOrError(definition, arguments)
    ).value

    private fun boundCarrier(
        declarations: DotNetGenericOwnerPhysicalDeclarationIndex,
        type: DotNetGenericOwnerSymbolicCarrierReference,
    ): DotNetGenericOwnerPhysicalCarrier = assertIs<
            DotNetGenericOwnerPhysicalBindingResult.Bound<DotNetGenericOwnerPhysicalCarrier>,
            >(
        declarations.carrierOrError(type)
    ).value

    private fun directValue(
        declarations: DotNetGenericOwnerPhysicalDeclarationIndex,
        type: DotNetGenericOwnerSymbolicCarrierReference,
    ): DotNetGenericOwnerProducedValueFact {
        val construction = type as? DotNetGenericOwnerSymbolicCarrierReference.Constructed
        val provenance = construction?.let { exactConstruction ->
            DotNetGenericOwnerPhysicalValueProvenance(
                DotNetGenericOwnerGuaranteedViews.Known(
                    mapOf(
                        DotNetGenericOwnerPhysicalView(exactConstruction) to setOf(
                            DotNetGenericOwnerPhysicalViewEvidence.RETAINED_FOREIGN_METADATA,
                        )
                    ),
                ),
            )
        } ?: DotNetGenericOwnerPhysicalValueProvenance.noNonNullViews()
        return DotNetGenericOwnerProducedValueFact(
            DotNetGenericOwnerProducedValueLayout.Direct(boundCarrier(declarations, type)),
            provenance,
            DotNetGenericOwnerPhysicalNullState.NON_NULL,
        )
    }

    private fun objectValueWithRetainedViews(
        declarations: DotNetGenericOwnerPhysicalDeclarationIndex,
        views: List<DotNetGenericOwnerPhysicalView>,
        selectedView: DotNetGenericOwnerPhysicalView? = null,
    ): DotNetGenericOwnerProducedValueFact {
        var provenance = DotNetGenericOwnerPhysicalValueProvenance(
            DotNetGenericOwnerGuaranteedViews.Known(
                views.associateWith {
                    setOf(DotNetGenericOwnerPhysicalViewEvidence.RETAINED_FOREIGN_METADATA)
                },
            ),
        )
        if (selectedView != null) {
            provenance = assertNotNull(provenance.selectViewOrNull(selectedView))
        }
        return DotNetGenericOwnerProducedValueFact(
            DotNetGenericOwnerProducedValueLayout.Direct(
                boundCarrier(
                    declarations,
                    DotNetGenericOwnerSymbolicCarrierReference.objectCarrier(),
                )
            ),
            provenance,
            DotNetGenericOwnerPhysicalNullState.NON_NULL,
        )
    }

    private fun exactTransferArguments(
        declarations: DotNetGenericOwnerPhysicalDeclarationIndex,
        ownerArgument: DotNetGenericOwnerSymbolicCarrierReference =
            DotNetGenericOwnerSymbolicCarrierReference.int32Carrier(),
    ): List<DotNetGenericOwnerProducedValueFact> = listOf(
        directValue(
            declarations,
            DotNetGenericOwnerSymbolicCarrierReference.int32Carrier(),
        ),
        directValue(
            declarations,
            DotNetGenericOwnerSymbolicCarrierReference.SzArray(
                ownerArgument,
            ),
        ),
    )

    private fun selectRetainedRoute(
        fixture: Fixture,
        receiver: DotNetGenericOwnerProducedValueFact,
        arguments: List<DotNetGenericOwnerProducedValueFact>,
        inheritedReceiverSource: DotNetClrImportedTypeSource? = null,
    ): DotNetGenericOwnerPhysicalBindingResult<DotNetGenericOwnerPhysicalOperationRoute> =
        selectDotNetRetainedForeignGenericOwnerPhysicalOperationRoute(
            source = fixture.source,
            method = fixture.method,
            receiver = receiver,
            arguments = arguments,
            methodArguments = listOf(DotNetGenericOwnerSymbolicCarrierReference.int32Carrier()),
            inheritedReceiverSource = inheritedReceiverSource,
        )

    private fun fixture(
        ownerParameterAttributes: Int = 0,
        methodParameterAttributes: Int = 0,
        rawReturnType: DotNetClrTypeSignature = DotNetClrTypeSignature.GenericParameter(
            DotNetClrGenericParameterKind.TYPE,
            0,
        ),
        retainedReturnType: DotNetClrResolvedTypeSignature =
            DotNetClrResolvedTypeSignature.GenericParameter(
                DotNetClrGenericParameterKind.TYPE,
                0,
            ),
        includeRelatedType: Boolean = false,
        rawInterfaceEdge: Boolean = false,
        retainedInterfaceEdge: Boolean = false,
        closedOwnerView: Boolean = false,
        recursiveResult: Boolean = false,
        ownerDependentInput: Boolean = true,
        includeInheritedReceiver: Boolean = false,
        openInheritedReceiver: Boolean = false,
        closedInheritedReceiverView: Boolean = false,
        inheritedReceiverParameterAttributes: Int = 0,
        rawInheritedArgument: DotNetClrTypeSignature? = null,
        retainedInheritedArgument: DotNetClrResolvedTypeSignature? = null,
        inheritedInterfaceCount: Int = 1,
        secondRawInheritedArgument: DotNetClrTypeSignature? = null,
        secondRetainedInheritedArgument: DotNetClrResolvedTypeSignature? = null,
        secondInheritedEdgeFirst: Boolean = false,
        includeInheritedRelatedEdge: Boolean = false,
        inheritedRelatedEdgeFirst: Boolean = true,
    ): Fixture {
        require(!retainedInterfaceEdge || rawInterfaceEdge)
        require(!closedInheritedReceiverView || openInheritedReceiver)
        require(inheritedInterfaceCount >= 0)
        require(!includeInheritedRelatedEdge || includeInheritedReceiver)
        require((secondRawInheritedArgument == null) == (secondRetainedInheritedArgument == null))
        require(secondRawInheritedArgument == null || includeInheritedReceiver)
        val ownerHandle = DotNetClrMetadataHandle(TYPE_DEF_TABLE, 1)
        val relatedHandle = DotNetClrMetadataHandle(TYPE_DEF_TABLE, 2)
        val inheritedReceiverHandle = DotNetClrMetadataHandle(TYPE_DEF_TABLE, 3)
        val methodHandle = DotNetClrMetadataHandle(METHOD_DEF_TABLE, 1)
        val inheritedTypeSpecHandle = DotNetClrMetadataHandle(TYPE_SPEC_TABLE, 1)
        val secondInheritedTypeSpecHandle = DotNetClrMetadataHandle(TYPE_SPEC_TABLE, 2)
        val owner = DotNetClrTypeDefinition(
            handle = ownerHandle,
            namespaceName = "Foreign",
            metadataName = "Source`1",
            attributes = PUBLIC_ABSTRACT_INTERFACE_ATTRIBUTES,
            baseType = null,
            declaringType = null,
        )
        val related = DotNetClrTypeDefinition(
            handle = relatedHandle,
            namespaceName = "Foreign",
            metadataName = "Related",
            attributes = PUBLIC_ABSTRACT_INTERFACE_ATTRIBUTES,
            baseType = null,
            declaringType = null,
        )
        val inheritedReceiver = DotNetClrTypeDefinition(
            handle = inheritedReceiverHandle,
            namespaceName = "Foreign",
            metadataName = if (openInheritedReceiver) "ForwardingSource`1" else "IntSource",
            attributes = PUBLIC_ABSTRACT_INTERFACE_ATTRIBUTES,
            baseType = null,
            declaringType = null,
        )
        val effectiveRawReturnType = if (recursiveResult) {
            DotNetClrTypeSignature.GenericInstance(
                DotNetClrTypeSignature.Named(ownerHandle, isValueType = false),
                listOf(
                    DotNetClrTypeSignature.GenericParameter(
                        DotNetClrGenericParameterKind.TYPE,
                        0,
                    )
                ),
            )
        } else {
            rawReturnType
        }
        val rawMethodSignature = DotNetClrMethodSignature(
            callingConvention = DotNetClrSignatureCallingConvention.DEFAULT,
            hasThis = true,
            hasExplicitThis = false,
            genericParameterCount = 1,
            returnType = effectiveRawReturnType,
            parameterTypes = listOf(
                DotNetClrTypeSignature.GenericParameter(
                    DotNetClrGenericParameterKind.METHOD,
                    0,
                ),
                DotNetClrTypeSignature.SzArray(
                    if (ownerDependentInput) {
                        DotNetClrTypeSignature.GenericParameter(
                            DotNetClrGenericParameterKind.TYPE,
                            0,
                        )
                    } else {
                        DotNetClrTypeSignature.Primitive(DotNetClrPrimitiveType.STRING)
                    }
                ),
            ),
            varargParameterStart = null,
        )
        val method = DotNetClrMethodDefinition(
            handle = methodHandle,
            declaringType = ownerHandle,
            name = "Transfer",
            relativeVirtualAddress = 0L,
            implementationAttributes = 0,
            attributes = PUBLIC_ABSTRACT_VIRTUAL_METHOD_ATTRIBUTES,
            signature = rawMethodSignature,
            rawSignature = emptyList(),
        )
        val ownerParameter = DotNetClrGenericParameterDefinition(
            handle = DotNetClrMetadataHandle(GENERIC_PARAMETER_TABLE, 1),
            number = 0,
            attributes = ownerParameterAttributes,
            owner = ownerHandle,
            name = "T",
        )
        val methodParameter = DotNetClrGenericParameterDefinition(
            handle = DotNetClrMetadataHandle(GENERIC_PARAMETER_TABLE, 2),
            number = 0,
            attributes = methodParameterAttributes,
            owner = methodHandle,
            name = "R",
        )
        val inheritedReceiverParameter = DotNetClrGenericParameterDefinition(
            handle = DotNetClrMetadataHandle(GENERIC_PARAMETER_TABLE, 3),
            number = 0,
            attributes = inheritedReceiverParameterAttributes,
            owner = inheritedReceiverHandle,
            name = "T",
        )
        val effectiveRawInheritedArgument = rawInheritedArgument ?: if (openInheritedReceiver) {
            DotNetClrTypeSignature.GenericParameter(DotNetClrGenericParameterKind.TYPE, 0)
        } else {
            DotNetClrTypeSignature.Primitive(DotNetClrPrimitiveType.INT32)
        }
        val effectiveRetainedInheritedArgument = retainedInheritedArgument ?: if (
            openInheritedReceiver && !closedInheritedReceiverView
        ) {
            DotNetClrResolvedTypeSignature.GenericParameter(DotNetClrGenericParameterKind.TYPE, 0)
        } else {
            DotNetClrResolvedTypeSignature.Primitive(DotNetClrPrimitiveType.INT32)
        }
        val hasRelatedType = includeRelatedType || rawInterfaceEdge || includeInheritedRelatedEdge
        val interfaceImplementation = DotNetClrInterfaceImplementation(
            handle = DotNetClrMetadataHandle(INTERFACE_IMPLEMENTATION_TABLE, 1),
            implementingType = ownerHandle,
            interfaceType = relatedHandle,
        )
        val inheritedTypeSpecification = DotNetClrTypeSpecification(
            handle = inheritedTypeSpecHandle,
            signature = DotNetClrTypeSignature.GenericInstance(
                DotNetClrTypeSignature.Named(ownerHandle, isValueType = false),
                listOf(effectiveRawInheritedArgument),
            ),
            rawSignature = emptyList(),
        )
        val secondInheritedTypeSpecification = secondRawInheritedArgument?.let { argument ->
            DotNetClrTypeSpecification(
                handle = secondInheritedTypeSpecHandle,
                signature = DotNetClrTypeSignature.GenericInstance(
                    DotNetClrTypeSignature.Named(ownerHandle, isValueType = false),
                    listOf(argument),
                ),
                rawSignature = emptyList(),
            )
        }
        val inheritedInterfaceTargets = buildList {
            if (includeInheritedRelatedEdge && inheritedRelatedEdgeFirst) add(relatedHandle)
            if (secondInheritedTypeSpecification != null && secondInheritedEdgeFirst) {
                add(secondInheritedTypeSpecHandle)
            }
            repeat(inheritedInterfaceCount) { add(inheritedTypeSpecHandle) }
            if (secondInheritedTypeSpecification != null && !secondInheritedEdgeFirst) {
                add(secondInheritedTypeSpecHandle)
            }
            if (includeInheritedRelatedEdge && !inheritedRelatedEdgeFirst) add(relatedHandle)
        }
        val inheritedInterfaceImplementations = inheritedInterfaceTargets.mapIndexed { index, target ->
            DotNetClrInterfaceImplementation(
                handle = DotNetClrMetadataHandle(INTERFACE_IMPLEMENTATION_TABLE, index + 2),
                implementingType = inheritedReceiverHandle,
                interfaceType = target,
            )
        }
        val metadata = DotNetClrAssemblyMetadata(
            identity = DotNetManagedAssemblyIdentity(
                name = "Foreign.Authority",
                version = "1.0.0.0",
                culture = "neutral",
                publicKey = emptyList(),
                publicKeyToken = emptyList(),
            ),
            assemblyReferences = emptyList(),
            typeReferences = emptyList(),
            typeDefinitions = buildList {
                add(owner)
                if (hasRelatedType) add(related)
                if (includeInheritedReceiver) add(inheritedReceiver)
            },
            interfaceImplementations = buildList {
                if (rawInterfaceEdge) add(interfaceImplementation)
                if (includeInheritedReceiver) addAll(inheritedInterfaceImplementations)
            },
            exportedTypes = emptyList(),
            typeSpecifications = if (includeInheritedReceiver) {
                buildList {
                    add(inheritedTypeSpecification)
                    secondInheritedTypeSpecification?.let(::add)
                }
            } else {
                emptyList()
            },
            fieldDefinitions = emptyList(),
            methodDefinitions = listOf(method),
            parameterDefinitions = emptyList(),
            constantDefinitions = emptyList(),
            fieldMarshalDefinitions = emptyList(),
            memberReferences = emptyList(),
            customAttributes = emptyList(),
            propertyDefinitions = emptyList(),
            methodSemantics = emptyList(),
            genericParameterDefinitions = buildList {
                add(ownerParameter)
                add(methodParameter)
                if (openInheritedReceiver) add(inheritedReceiverParameter)
            },
            genericParameterConstraints = emptyList(),
        )
        val assembly = DotNetClrClasspathAssembly.WithoutCarrier(
            File("Foreign.Authority.dll"),
            metadata,
        )
        val resolvedOwner = DotNetClrResolvedTypeDefinition(metadata, owner)
        val ownerView = DotNetClrResolvedTypeView(
            resolvedOwner,
            if (closedOwnerView) {
                listOf(DotNetClrResolvedTypeSignature.Primitive(DotNetClrPrimitiveType.INT32))
            } else listOf(
                DotNetClrResolvedTypeSignature.GenericParameter(
                    DotNetClrGenericParameterKind.TYPE,
                    0,
                )
            ),
        )
        val hierarchy = DotNetClrResolvedTypeHierarchy(
            type = ownerView,
            baseType = null,
            interfaces = if (retainedInterfaceEdge) {
                listOf(
                    DotNetClrResolvedInterfaceImplementation(
                        interfaceImplementation,
                        DotNetClrResolvedTypeView(
                            DotNetClrResolvedTypeDefinition(metadata, related),
                            emptyList(),
                        ),
                    )
                )
            } else {
                emptyList()
            },
        )
        val inheritedReceiverHierarchy = DotNetClrResolvedTypeHierarchy(
            type = DotNetClrResolvedTypeView(
                DotNetClrResolvedTypeDefinition(metadata, inheritedReceiver),
                if (openInheritedReceiver && !closedInheritedReceiverView) {
                    listOf(
                        DotNetClrResolvedTypeSignature.GenericParameter(
                            DotNetClrGenericParameterKind.TYPE,
                            0,
                        )
                    )
                } else if (openInheritedReceiver) {
                    listOf(
                        DotNetClrResolvedTypeSignature.Primitive(
                            DotNetClrPrimitiveType.INT32,
                        )
                    )
                } else {
                    emptyList()
                },
            ),
            baseType = null,
            interfaces = inheritedInterfaceImplementations.mapIndexed { index, implementation ->
                val target = inheritedInterfaceTargets[index]
                DotNetClrResolvedInterfaceImplementation(
                    implementation,
                    if (target == relatedHandle) {
                        DotNetClrResolvedTypeView(
                            DotNetClrResolvedTypeDefinition(metadata, related),
                            emptyList(),
                        )
                    } else {
                        DotNetClrResolvedTypeView(
                            resolvedOwner,
                            listOf(
                                if (target == secondInheritedTypeSpecHandle) {
                                    checkNotNull(secondRetainedInheritedArgument)
                                } else {
                                    effectiveRetainedInheritedArgument
                                }
                            ),
                        )
                    },
                )
            },
        )
        val relatedHierarchy = DotNetClrResolvedTypeHierarchy(
            type = DotNetClrResolvedTypeView(
                DotNetClrResolvedTypeDefinition(metadata, related),
                emptyList(),
            ),
            baseType = null,
            interfaces = emptyList(),
        )
        val graph = DotNetClrImportedDeclarationGraph(
            assemblies = listOf(assembly),
            hierarchies = buildList {
                add(hierarchy)
                if (includeInheritedReceiver) add(inheritedReceiverHierarchy)
                if (includeInheritedRelatedEdge) add(relatedHierarchy)
            },
        )
        val effectiveRetainedReturnType = if (recursiveResult) {
            DotNetClrResolvedTypeSignature.GenericInstance(
                DotNetClrResolvedTypeSignature.Named(resolvedOwner, isValueType = false),
                listOf(
                    DotNetClrResolvedTypeSignature.GenericParameter(
                        DotNetClrGenericParameterKind.TYPE,
                        0,
                    )
                ),
            )
        } else {
            retainedReturnType
        }
        val resolvedMethodSignature = DotNetClrResolvedMethodSignature(
            callingConvention = DotNetClrSignatureCallingConvention.DEFAULT,
            hasThis = true,
            hasExplicitThis = false,
            genericParameterCount = 1,
            returnType = effectiveRetainedReturnType,
            parameterTypes = listOf(
                DotNetClrResolvedTypeSignature.GenericParameter(
                    DotNetClrGenericParameterKind.METHOD,
                    0,
                ),
                DotNetClrResolvedTypeSignature.SzArray(
                    if (ownerDependentInput) {
                        DotNetClrResolvedTypeSignature.GenericParameter(
                            DotNetClrGenericParameterKind.TYPE,
                            0,
                        )
                    } else {
                        DotNetClrResolvedTypeSignature.Primitive(DotNetClrPrimitiveType.STRING)
                    }
                ),
            ),
            varargParameterStart = null,
        )
        val source = DotNetClrImportedMethodSource(
            assembly,
            owner,
            hierarchy,
            graph,
            method,
            resolvedMethodSignature,
        )
        val inheritedReceiverSource = if (includeInheritedReceiver) {
            DotNetClrImportedTypeSource(
                assembly,
                inheritedReceiver,
                inheritedReceiverHierarchy,
                graph,
            )
        } else {
            null
        }
        return Fixture(
            source,
            method,
            related.takeIf { includeRelatedType || includeInheritedRelatedEdge }
                ?.let { DotNetClrResolvedTypeDefinition(metadata, it) },
            inheritedReceiverSource,
        )
    }

    private fun crossAssemblyFixture(
        parentReferenceVersion: String? = null,
    ): Fixture {
        val parentFixture = fixture()
        val parentSource = parentFixture.source
        val parentAssembly = parentSource.assembly
        val parentMetadata = parentAssembly.metadata
        val assemblyReferenceHandle = DotNetClrMetadataHandle(ASSEMBLY_REFERENCE_TABLE, 1)
        val parentTypeReferenceHandle = DotNetClrMetadataHandle(TYPE_REFERENCE_TABLE, 1)
        val childTypeHandle = DotNetClrMetadataHandle(TYPE_DEF_TABLE, 1)
        val childTypeSpecHandle = DotNetClrMetadataHandle(TYPE_SPEC_TABLE, 1)
        val childType = DotNetClrTypeDefinition(
            handle = childTypeHandle,
            namespaceName = "Foreign",
            metadataName = "IntSource",
            attributes = PUBLIC_ABSTRACT_INTERFACE_ATTRIBUTES,
            baseType = null,
            declaringType = null,
        )
        val assemblyReference = DotNetClrAssemblyReference(
            handle = assemblyReferenceHandle,
            name = parentMetadata.identity.name,
            version = parentReferenceVersion ?: parentMetadata.identity.version,
            culture = parentMetadata.identity.culture,
            flags = 0,
            publicKeyOrToken = emptyList(),
            hashValue = emptyList(),
        )
        val parentTypeReference = DotNetClrTypeReference(
            handle = parentTypeReferenceHandle,
            namespaceName = parentSource.declaringType.namespaceName,
            metadataName = parentSource.declaringType.metadataName,
            resolutionScope = assemblyReferenceHandle,
        )
        val childTypeSpecification = DotNetClrTypeSpecification(
            handle = childTypeSpecHandle,
            signature = DotNetClrTypeSignature.GenericInstance(
                DotNetClrTypeSignature.Named(
                    parentTypeReferenceHandle,
                    isValueType = false,
                ),
                listOf(
                    DotNetClrTypeSignature.Primitive(DotNetClrPrimitiveType.INT32),
                ),
            ),
            rawSignature = emptyList(),
        )
        val childInterfaceImplementation = DotNetClrInterfaceImplementation(
            handle = DotNetClrMetadataHandle(INTERFACE_IMPLEMENTATION_TABLE, 1),
            implementingType = childTypeHandle,
            interfaceType = childTypeSpecHandle,
        )
        val childMetadata = DotNetClrAssemblyMetadata(
            identity = DotNetManagedAssemblyIdentity(
                name = "Foreign.Child",
                version = "1.0.0.0",
                culture = "neutral",
                publicKey = emptyList(),
                publicKeyToken = emptyList(),
            ),
            assemblyReferences = listOf(assemblyReference),
            typeReferences = listOf(parentTypeReference),
            typeDefinitions = listOf(childType),
            interfaceImplementations = listOf(childInterfaceImplementation),
            exportedTypes = emptyList(),
            typeSpecifications = listOf(childTypeSpecification),
            fieldDefinitions = emptyList(),
            methodDefinitions = emptyList(),
            parameterDefinitions = emptyList(),
            constantDefinitions = emptyList(),
            fieldMarshalDefinitions = emptyList(),
            memberReferences = emptyList(),
            customAttributes = emptyList(),
            propertyDefinitions = emptyList(),
            methodSemantics = emptyList(),
            genericParameterDefinitions = emptyList(),
            genericParameterConstraints = emptyList(),
        )
        val childAssembly = DotNetClrClasspathAssembly.WithoutCarrier(
            File("Foreign.Child.dll"),
            childMetadata,
        )
        val childHierarchy = DotNetClrResolvedTypeHierarchy(
            type = DotNetClrResolvedTypeView(
                DotNetClrResolvedTypeDefinition(childMetadata, childType),
                emptyList(),
            ),
            baseType = null,
            interfaces = listOf(
                DotNetClrResolvedInterfaceImplementation(
                    childInterfaceImplementation,
                    DotNetClrResolvedTypeView(
                        parentSource.declaringHierarchy.type.type,
                        listOf(
                            DotNetClrResolvedTypeSignature.Primitive(
                                DotNetClrPrimitiveType.INT32,
                            )
                        ),
                    ),
                )
            ),
        )
        val graph = DotNetClrImportedDeclarationGraph(
            assemblies = listOf(parentAssembly, childAssembly),
            hierarchies = listOf(parentSource.declaringHierarchy, childHierarchy),
        )
        val source = DotNetClrImportedMethodSource(
            parentAssembly,
            parentSource.declaringType,
            parentSource.declaringHierarchy,
            graph,
            parentFixture.method,
            parentSource.resolvedSignature,
        )
        val receiverSource = DotNetClrImportedTypeSource(
            childAssembly,
            childType,
            childHierarchy,
            graph,
        )
        return Fixture(
            source,
            parentFixture.method,
            relatedType = null,
            inheritedReceiverSource = receiverSource,
        )
    }

    private data class Fixture(
        val source: DotNetClrImportedMethodSource,
        val method: DotNetClrMethodDefinition,
        val relatedType: DotNetClrResolvedTypeDefinition?,
        val inheritedReceiverSource: DotNetClrImportedTypeSource?,
    )

    private companion object {
        const val TYPE_REFERENCE_TABLE = 1
        const val TYPE_DEF_TABLE = 2
        const val METHOD_DEF_TABLE = 6
        const val INTERFACE_IMPLEMENTATION_TABLE = 9
        const val TYPE_SPEC_TABLE = 27
        const val ASSEMBLY_REFERENCE_TABLE = 35
        const val GENERIC_PARAMETER_TABLE = 42

        const val COVARIANT_ATTRIBUTE = 0x0001
        const val CONTRAVARIANT_ATTRIBUTE = 0x0002
        const val REFERENCE_TYPE_CONSTRAINT_ATTRIBUTE = 0x0004
        const val NOT_NULLABLE_VALUE_TYPE_CONSTRAINT_ATTRIBUTE = 0x0008
        const val DEFAULT_CONSTRUCTOR_CONSTRAINT_ATTRIBUTE = 0x0010
        const val ALLOW_BY_REF_LIKE_ATTRIBUTE = 0x0020
        const val UNKNOWN_GENERIC_PARAMETER_ATTRIBUTE = 0x0040

        const val PUBLIC_ABSTRACT_INTERFACE_ATTRIBUTES = 0x0000_00a1L
        const val PUBLIC_ABSTRACT_VIRTUAL_METHOD_ATTRIBUTES = 0x0000_05c6
    }
}
