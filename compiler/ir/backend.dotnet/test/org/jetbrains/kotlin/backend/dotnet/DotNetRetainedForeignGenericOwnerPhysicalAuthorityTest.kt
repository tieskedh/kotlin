/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.load.dotnet.DotNetClrAssemblyMetadata
import org.jetbrains.kotlin.load.dotnet.DotNetClrClasspathAssembly
import org.jetbrains.kotlin.load.dotnet.DotNetClrGenericParameterDefinition
import org.jetbrains.kotlin.load.dotnet.DotNetClrGenericParameterKind
import org.jetbrains.kotlin.load.dotnet.DotNetClrImportedDeclarationGraph
import org.jetbrains.kotlin.load.dotnet.DotNetClrImportedDeclarationSource
import org.jetbrains.kotlin.load.dotnet.DotNetClrImportedMethodSource
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
import org.jetbrains.kotlin.load.dotnet.DotNetClrTypeSignature
import org.jetbrains.kotlin.load.dotnet.DotNetClrTypeSpecification
import org.jetbrains.kotlin.load.dotnet.DotNetManagedAssemblyIdentity
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun `retained InterfaceImpl proves an inherited foreign MethodDef route`() {
        val fixture = fixture(includeInheritedReceiver = true)
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
    fun `multiple retained InterfaceImpl rows remain outside the first edge grammar`() {
        val fixture = fixture(
            includeInheritedReceiver = true,
            inheritedInterfaceCount = 2,
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
    ): List<DotNetGenericOwnerProducedValueFact> = listOf(
        directValue(
            declarations,
            DotNetGenericOwnerSymbolicCarrierReference.int32Carrier(),
        ),
        directValue(
            declarations,
            DotNetGenericOwnerSymbolicCarrierReference.SzArray(
                DotNetGenericOwnerSymbolicCarrierReference.int32Carrier(),
            ),
        ),
    )

    private fun selectRetainedRoute(
        fixture: Fixture,
        receiver: DotNetGenericOwnerProducedValueFact,
        arguments: List<DotNetGenericOwnerProducedValueFact>,
        inheritedReceiverSource: DotNetClrImportedDeclarationSource? = null,
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
        rawInheritedArgument: DotNetClrTypeSignature =
            DotNetClrTypeSignature.Primitive(DotNetClrPrimitiveType.INT32),
        retainedInheritedArgument: DotNetClrResolvedTypeSignature =
            DotNetClrResolvedTypeSignature.Primitive(DotNetClrPrimitiveType.INT32),
        inheritedInterfaceCount: Int = 1,
    ): Fixture {
        require(!retainedInterfaceEdge || rawInterfaceEdge)
        require(inheritedInterfaceCount >= 0)
        val ownerHandle = DotNetClrMetadataHandle(TYPE_DEF_TABLE, 1)
        val relatedHandle = DotNetClrMetadataHandle(TYPE_DEF_TABLE, 2)
        val inheritedReceiverHandle = DotNetClrMetadataHandle(TYPE_DEF_TABLE, 3)
        val methodHandle = DotNetClrMetadataHandle(METHOD_DEF_TABLE, 1)
        val inheritedCarrierMethodHandle = DotNetClrMetadataHandle(METHOD_DEF_TABLE, 2)
        val inheritedTypeSpecHandle = DotNetClrMetadataHandle(TYPE_SPEC_TABLE, 1)
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
            metadataName = "IntSource",
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
        val inheritedCarrierMethodSignature = DotNetClrMethodSignature(
            callingConvention = DotNetClrSignatureCallingConvention.DEFAULT,
            hasThis = true,
            hasExplicitThis = false,
            genericParameterCount = 0,
            returnType = DotNetClrTypeSignature.Primitive(DotNetClrPrimitiveType.INT32),
            parameterTypes = emptyList(),
            varargParameterStart = null,
        )
        val inheritedCarrierMethod = DotNetClrMethodDefinition(
            handle = inheritedCarrierMethodHandle,
            declaringType = inheritedReceiverHandle,
            name = "Marker",
            relativeVirtualAddress = 0L,
            implementationAttributes = 0,
            attributes = PUBLIC_ABSTRACT_VIRTUAL_METHOD_ATTRIBUTES,
            signature = inheritedCarrierMethodSignature,
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
        val hasRelatedType = includeRelatedType || rawInterfaceEdge
        val interfaceImplementation = DotNetClrInterfaceImplementation(
            handle = DotNetClrMetadataHandle(INTERFACE_IMPLEMENTATION_TABLE, 1),
            implementingType = ownerHandle,
            interfaceType = relatedHandle,
        )
        val inheritedTypeSpecification = DotNetClrTypeSpecification(
            handle = inheritedTypeSpecHandle,
            signature = DotNetClrTypeSignature.GenericInstance(
                DotNetClrTypeSignature.Named(ownerHandle, isValueType = false),
                listOf(rawInheritedArgument),
            ),
            rawSignature = emptyList(),
        )
        val inheritedInterfaceImplementations = List(inheritedInterfaceCount) { index ->
            DotNetClrInterfaceImplementation(
                handle = DotNetClrMetadataHandle(
                    INTERFACE_IMPLEMENTATION_TABLE,
                    index + 2,
                ),
                implementingType = inheritedReceiverHandle,
                interfaceType = inheritedTypeSpecHandle,
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
                listOf(inheritedTypeSpecification)
            } else {
                emptyList()
            },
            fieldDefinitions = emptyList(),
            methodDefinitions = buildList {
                add(method)
                if (includeInheritedReceiver) add(inheritedCarrierMethod)
            },
            parameterDefinitions = emptyList(),
            constantDefinitions = emptyList(),
            fieldMarshalDefinitions = emptyList(),
            memberReferences = emptyList(),
            customAttributes = emptyList(),
            propertyDefinitions = emptyList(),
            methodSemantics = emptyList(),
            genericParameterDefinitions = listOf(ownerParameter, methodParameter),
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
                emptyList(),
            ),
            baseType = null,
            interfaces = inheritedInterfaceImplementations.map { implementation ->
                DotNetClrResolvedInterfaceImplementation(
                    implementation,
                    DotNetClrResolvedTypeView(
                        resolvedOwner,
                        listOf(retainedInheritedArgument),
                    ),
                )
            },
        )
        val graph = DotNetClrImportedDeclarationGraph(
            assemblies = listOf(assembly),
            hierarchies = buildList {
                add(hierarchy)
                if (includeInheritedReceiver) add(inheritedReceiverHierarchy)
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
            DotNetClrImportedMethodSource(
                assembly,
                inheritedReceiver,
                inheritedReceiverHierarchy,
                graph,
                inheritedCarrierMethod,
                DotNetClrResolvedMethodSignature(
                    callingConvention = DotNetClrSignatureCallingConvention.DEFAULT,
                    hasThis = true,
                    hasExplicitThis = false,
                    genericParameterCount = 0,
                    returnType = DotNetClrResolvedTypeSignature.Primitive(
                        DotNetClrPrimitiveType.INT32,
                    ),
                    parameterTypes = emptyList(),
                    varargParameterStart = null,
                ),
            )
        } else {
            null
        }
        return Fixture(
            source,
            method,
            related.takeIf { includeRelatedType }
                ?.let { DotNetClrResolvedTypeDefinition(metadata, it) },
            inheritedReceiverSource,
        )
    }

    private data class Fixture(
        val source: DotNetClrImportedMethodSource,
        val method: DotNetClrMethodDefinition,
        val relatedType: DotNetClrResolvedTypeDefinition?,
        val inheritedReceiverSource: DotNetClrImportedDeclarationSource?,
    )

    private companion object {
        const val TYPE_DEF_TABLE = 2
        const val METHOD_DEF_TABLE = 6
        const val INTERFACE_IMPLEMENTATION_TABLE = 9
        const val TYPE_SPEC_TABLE = 27
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
