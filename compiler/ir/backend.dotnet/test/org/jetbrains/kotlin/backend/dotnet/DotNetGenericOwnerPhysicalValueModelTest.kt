/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.ir.builders.declarations.addTypeParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.declarations.buildField
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.builders.declarations.buildVariable
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.impl.IrExternalPackageFragmentImpl
import org.jetbrains.kotlin.ir.declarations.impl.IrFactoryImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetValueImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrExternalPackageFragmentSymbolImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrClassSymbolImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrSimpleFunctionSymbolImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrVariableSymbolImpl
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.SimpleTypeNullability
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.impl.IrSimpleTypeImpl
import org.jetbrains.kotlin.ir.util.IrErrorModuleFragment
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DotNetGenericOwnerPhysicalValueModelTest {
    @Test
    fun `generic-owner admission inspects every physical state requirement`() {
        assertTrue(
            DotNetGenericOwnerCandidateDisposition.REQUIRES_TYPED_WRITE_VALUE_PROVENANCE
                .allowsGenericOwnerRehearsalAfterStateResolution(),
            "a stale priority-compressed state disposition must not override resolved final state",
        )
        assertTrue(
            DotNetGenericOwnerCandidateDisposition.REQUIRES_COMPLETE_FIELD_ACCESS_GRAPH
                .allowsGenericOwnerRehearsalAfterStateResolution(),
            "final per-field authority, not a stale state disposition, decides admission",
        )
        assertFalse(
            DotNetGenericOwnerCandidateDisposition.RETAINED_VALUE_CLASS_ABI
                .allowsGenericOwnerRehearsalAfterStateResolution(),
        )
        assertTrue(
            listOf(
                DotNetGenericOwnerStateCarrierRequirement.TYPED_STORAGE_PRODUCER_GRAPH_PROVEN,
                DotNetGenericOwnerStateCarrierRequirement.SEMANTIC_OBJECT_REQUIRED,
            ).areResolvedForGenericOwnerRehearsal(),
        )
        assertFalse(
            listOf(
                DotNetGenericOwnerStateCarrierRequirement.SEMANTIC_OBJECT_REQUIRED,
                DotNetGenericOwnerStateCarrierRequirement.TYPED_WRITE_VALUE_PROVENANCE_REQUIRED,
            ).areResolvedForGenericOwnerRehearsal(),
        )
        assertFalse(
            listOf(
                DotNetGenericOwnerStateCarrierRequirement.TYPED_STORAGE_PRODUCER_GRAPH_PROVEN,
                DotNetGenericOwnerStateCarrierRequirement.COMPLETE_ACCESS_GRAPH_REQUIRED,
            ).areResolvedForGenericOwnerRehearsal(),
        )
    }

    @Test
    fun `lineage can only select an independently guaranteed view`() {
        val sourceInt = view(source(int32Type()))

        assertNull(unknownProvenance().selectViewOrNull(sourceInt))
        assertFailsWith<IllegalArgumentException> {
            DotNetGenericOwnerPhysicalValueProvenance(
                guaranteedViews = knownViews(),
                selectedViewLineage = mapOf(sourceInt.family to sourceInt),
            )
        }

        assertEquals(
            sourceInt,
            knownProvenance(sourceInt).selectViewOrNull(sourceInt)
                ?.selectedViewLineage
                ?.get(sourceInt.family),
        )
    }

    @Test
    fun `a join retains only views guaranteed on every non-null path`() {
        val sourceInt = view(source(int32Type()))
        val sourceString = view(source(stringType()))

        assertEquals(
            emptySet(),
            assertIs<DotNetGenericOwnerGuaranteedViews.Known>(
                knownProvenance(sourceInt).join(knownProvenance(sourceString)).guaranteedViews,
            ).views,
        )
        assertEquals(
            DotNetGenericOwnerGuaranteedViews.Unknown,
            knownProvenance(sourceInt).join(unknownProvenance()).guaranteedViews,
        )
    }

    @Test
    fun `different selected constructions lose lineage even for the same dual-view object`() {
        val sourceInt = view(source(int32Type()))
        val sourceString = view(source(stringType()))
        val guaranteedByDual = knownViews(sourceInt, sourceString)
        val viaInt = DotNetGenericOwnerPhysicalValueProvenance(guaranteedByDual)
            .selectViewOrNull(sourceInt)!!
        val viaString = DotNetGenericOwnerPhysicalValueProvenance(guaranteedByDual)
            .selectViewOrNull(sourceString)!!

        val joined = viaInt.join(viaString)

        assertEquals(guaranteedByDual, joined.guaranteedViews)
        assertEquals(emptyMap(), joined.selectedViewLineage)
    }

    @Test
    fun `identical selected construction survives a join`() {
        val sourceInt = view(source(int32Type()))
        val selected = knownProvenance(sourceInt).selectViewOrNull(sourceInt)!!

        assertEquals(selected, selected.join(selected))
    }

    @Test
    fun `lineage disappears when only one reaching arm selected the shared view`() {
        val sourceInt = view(source(int32Type()))
        val selected = knownProvenance(sourceInt).selectViewOrNull(sourceInt)!!
        val unselected = knownProvenance(sourceInt)

        assertEquals(emptyMap(), selected.join(unselected).selectedViewLineage)
    }

    @Test
    fun `diagnostic evidence does not change provenance lattice equality`() {
        val sourceInt = view(source(int32Type()))
        val transferred = DotNetGenericOwnerGuaranteedViews.Known(
            mapOf(sourceInt to setOf(DotNetGenericOwnerPhysicalViewEvidence.IDENTITY_PRESERVING_TRANSFER)),
        )
        val fromStorage = DotNetGenericOwnerGuaranteedViews.Known(
            mapOf(sourceInt to setOf(DotNetGenericOwnerPhysicalViewEvidence.STORAGE_READ)),
        )

        assertEquals(transferred, fromStorage)
        assertEquals(
            DotNetGenericOwnerPhysicalValueProvenance(transferred),
            DotNetGenericOwnerPhysicalValueProvenance(fromStorage),
        )
    }

    @Test
    fun `CLR covariance adds a selected view without rewriting the produced carrier or ancestry`() {
        val covariant = localOwnerIdentity(IrClassSymbolImpl())
        val declarations = boundDeclarationIndex(
            listOf(variantTypeDescription(
                covariant,
                DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE,
                DotNetGenericOwnerPhysicalTypeParameterVariance.COVARIANT,
            )),
            emptyList(),
            edgeSets = listOf(edgeSet(covariant)),
        )
        val sourceConstruction = boundConstruction(declarations, covariant, listOf(stringType()))
        val targetConstruction = boundConstruction(declarations, covariant, listOf(objectType()))
        val sourceView = view(sourceConstruction)
        val targetView = view(targetConstruction)
        val sourceValue = directValue(boundCarrier(declarations, sourceConstruction))

        val closure = boundInterfaceClosure(declarations, sourceConstruction)
        assertEquals(setOf(sourceView), closure.interfaceViews)
        assertFalse(targetView in closure.interfaceViews)

        val converted = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerProducedValueFact,
                >>(
            sourceValue.selectClrReferenceVarianceViewOrError(declarations, targetView),
        ).value
        assertEquals(sourceValue.layout, converted.layout)
        assertEquals(
            setOf(sourceView, targetView),
            knownViewsOf(converted.provenance),
        )
        assertEquals(targetView, converted.provenance.selectedViewLineage[covariant])
        assertEquals(
            setOf(DotNetGenericOwnerPhysicalViewEvidence.CLR_REFERENCE_VARIANCE_CONVERSION),
            assertIs<DotNetGenericOwnerGuaranteedViews.Known>(
                converted.provenance.guaranteedViews,
            ).evidenceByView[targetView],
        )
        assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<*>>(
            DotNetGenericOwnerAuthenticatedPhysicalView.prove(
                sourceValue,
                declarations,
                targetView,
            ),
        )

        val logicallyBroad = directValue(
            boundCarrier(declarations, objectType()),
            knownProvenance(sourceView),
        )
        val broadConversion = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerProducedValueFact,
                >>(
            logicallyBroad.selectClrReferenceVarianceViewOrError(
                declarations,
                targetView,
            )
        ).value
        assertEquals(logicallyBroad.layout, broadConversion.layout)
        assertEquals(targetView, broadConversion.provenance.selectedViewLineage[covariant])

        val otherConstruction = boundConstruction(declarations, covariant, listOf(int32Type()))
        val joined = sourceValue.join(
            directValue(boundCarrier(declarations, otherConstruction)),
        ) { _, _ -> boundCarrier(declarations, objectType()) }
        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            joined.selectClrReferenceVarianceViewOrError(declarations, targetView),
        )
        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            nullValue().selectClrReferenceVarianceViewOrError(declarations, targetView),
        )
    }

    @Test
    fun `CLR variance obeys direction and never boxes value arguments`() {
        val covariant = localOwnerIdentity(IrClassSymbolImpl())
        val contravariant = localOwnerIdentity(IrClassSymbolImpl())
        val invariant = localOwnerIdentity(IrClassSymbolImpl())
        val nullable = localOwnerIdentity(IrClassSymbolImpl())
        val declarations = boundDeclarationIndex(
            listOf(
                variantTypeDescription(
                    covariant,
                    DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE,
                    DotNetGenericOwnerPhysicalTypeParameterVariance.COVARIANT,
                ),
                variantTypeDescription(
                    contravariant,
                    DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE,
                    DotNetGenericOwnerPhysicalTypeParameterVariance.CONTRAVARIANT,
                ),
                typeDescription(
                    invariant,
                    1,
                    DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE,
                ),
                typeDescription(
                    nullable,
                    1,
                    DotNetGenericOwnerPhysicalNamedTypeCategory.VALUE_TYPE,
                    supportsInlineNull = true,
                ),
            ),
            emptyList(),
        )

        fun conversion(
            owner: DotNetGenericOwnerPhysicalTypeDefIdentity,
            sourceArgument: DotNetGenericOwnerSymbolicCarrierReference,
            targetArgument: DotNetGenericOwnerSymbolicCarrierReference,
        ) = declarations.proveClrReferenceVarianceConversionOrError(
            view(boundConstruction(declarations, owner, listOf(sourceArgument))),
            view(boundConstruction(declarations, owner, listOf(targetArgument))),
        )

        assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<*>>(
            conversion(covariant, stringType(), objectType()),
        )
        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            conversion(covariant, objectType(), stringType()),
        )
        assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<*>>(
            conversion(contravariant, objectType(), stringType()),
        )
        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            conversion(contravariant, stringType(), objectType()),
        )
        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            conversion(invariant, stringType(), objectType()),
        )
        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            DotNetGenericOwnerPhysicalDeclarationIndex.bind(
                DotNetGenericOwnerPhysicalAuthorityEpoch.BOUND_DECLARATION_INDEX,
                listOf(variantTypeDescription(
                    localOwnerIdentity(IrClassSymbolImpl()),
                    DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS,
                    DotNetGenericOwnerPhysicalTypeParameterVariance.COVARIANT,
                )),
                emptyList(),
            ),
        )
        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            DotNetGenericOwnerPhysicalDeclarationIndex.bind(
                DotNetGenericOwnerPhysicalAuthorityEpoch.BOUND_DECLARATION_INDEX,
                listOf(DotNetGenericOwnerPhysicalTypeDefReference(
                    localOwnerIdentity(IrClassSymbolImpl()),
                    listOf(DotNetGenericOwnerPhysicalGenericParameterReference(
                        DotNetGenericOwnerPhysicalTypeParameterVariance.COVARIANT,
                        constraints = emptyList(),
                    )),
                    DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS,
                    supportsClrDelegateVariance = true,
                )),
                emptyList(),
            ),
        )
        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            conversion(covariant, int32Type(), objectType()),
        )
        assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<*>>(
            conversion(
                covariant,
                DotNetGenericOwnerSymbolicCarrierReference.SzArray(stringType()),
                DotNetGenericOwnerSymbolicCarrierReference.SzArray(objectType()),
            ),
        )
        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            conversion(
                covariant,
                DotNetGenericOwnerSymbolicCarrierReference.SzArray(int32Type()),
                DotNetGenericOwnerSymbolicCarrierReference.SzArray(objectType()),
            ),
        )
        val nullableInt = boundConstruction(declarations, nullable, listOf(int32Type()))
        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            conversion(covariant, nullableInt, objectType()),
        )
    }

    @Test
    fun `nested covariance composes through exact recorded interface ancestry`() {
        val child = localOwnerIdentity(IrClassSymbolImpl())
        val parent = localOwnerIdentity(IrClassSymbolImpl())
        val outer = localOwnerIdentity(IrClassSymbolImpl())
        val types = listOf(
            typeDescription(child, 1, DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE),
            variantTypeDescription(
                parent,
                DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE,
                DotNetGenericOwnerPhysicalTypeParameterVariance.COVARIANT,
            ),
            variantTypeDescription(
                outer,
                DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE,
                DotNetGenericOwnerPhysicalTypeParameterVariance.COVARIANT,
            ),
        )
        val provisional = boundDeclarationIndex(types, emptyList())
        val childParameter = boundTypeParameter(provisional, child, 0)
        val parentOfChildParameter = boundConstruction(
            provisional,
            parent,
            listOf(childParameter),
        )
        val declarations = boundDeclarationIndex(
            types,
            emptyList(),
            edgeSets = listOf(
                edgeSet(child, interfaceEdge(parentOfChildParameter)),
                edgeSet(parent),
                edgeSet(outer),
            ),
        )
        val childOfString = boundConstruction(declarations, child, listOf(stringType()))
        val parentOfString = boundConstruction(declarations, parent, listOf(stringType()))
        val parentOfObject = boundConstruction(declarations, parent, listOf(objectType()))
        val source = view(boundConstruction(declarations, outer, listOf(childOfString)))
        val target = view(boundConstruction(declarations, outer, listOf(parentOfObject)))

        assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<*>>(
            declarations.proveClrReferenceVarianceConversionOrError(source, target),
        )
        val childClosure = boundInterfaceClosure(declarations, childOfString)
        assertTrue(view(parentOfString) in childClosure.interfaceViews)
        assertFalse(view(parentOfObject) in childClosure.interfaceViews)

        val missingAncestry = boundDeclarationIndex(types, emptyList())
        val missingSource = view(boundConstruction(
            missingAncestry,
            outer,
            listOf(boundConstruction(missingAncestry, child, listOf(stringType()))),
        ))
        val missingTarget = view(boundConstruction(
            missingAncestry,
            outer,
            listOf(boundConstruction(missingAncestry, parent, listOf(objectType()))),
        ))
        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            missingAncestry.proveClrReferenceVarianceConversionOrError(
                missingSource,
                missingTarget,
            ),
        )
    }

    @Test
    fun `reference-constrained binders and unchanged value arguments compose without narrowing`() {
        val variant = localOwnerIdentity(IrClassSymbolImpl())
        val referenceBinderOwner = localOwnerIdentity(IrClassSymbolImpl())
        val unknownBinderOwner = localOwnerIdentity(IrClassSymbolImpl())
        val declarations = boundDeclarationIndex(
            listOf(
                variantTypeDescription(
                    variant,
                    DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE,
                    DotNetGenericOwnerPhysicalTypeParameterVariance.COVARIANT,
                    DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT,
                ),
                DotNetGenericOwnerPhysicalTypeDefReference(
                    referenceBinderOwner,
                    listOf(DotNetGenericOwnerPhysicalGenericParameterReference(
                        DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT,
                        constraints = emptyList(),
                        hasReferenceTypeConstraint = true,
                    )),
                    DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS,
                ),
                typeDescription(
                    unknownBinderOwner,
                    1,
                    DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS,
                ),
            ),
            emptyList(),
        )
        val referenceParameter = boundTypeParameter(declarations, referenceBinderOwner, 0)
        val unknownParameter = boundTypeParameter(declarations, unknownBinderOwner, 0)

        fun variantView(
            first: DotNetGenericOwnerSymbolicCarrierReference,
            second: DotNetGenericOwnerSymbolicCarrierReference,
        ) = view(boundConstruction(declarations, variant, listOf(first, second)))

        assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<*>>(
            declarations.proveClrReferenceVarianceConversionOrError(
                variantView(referenceParameter, int32Type()),
                variantView(objectType(), int32Type()),
            ),
        )
        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            declarations.proveClrReferenceVarianceConversionOrError(
                variantView(unknownParameter, int32Type()),
                variantView(objectType(), int32Type()),
            ),
        )
        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            declarations.proveClrReferenceVarianceConversionOrError(
                variantView(stringType(), int32Type()),
                variantView(objectType(), DotNetGenericOwnerSymbolicCarrierReference.booleanCarrier()),
            ),
        )
    }

    @Test
    fun `exact operation arguments use CLR variance without a semantic or object route`() {
        val receiverOwner = localOwnerIdentity(IrClassSymbolImpl())
        val covariant = localOwnerIdentity(IrClassSymbolImpl())
        val method = localMethodIdentity(IrSimpleFunctionSymbolImpl())
        val types = listOf(
            typeDescription(
                receiverOwner,
                0,
                DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE,
            ),
            variantTypeDescription(
                covariant,
                DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE,
                DotNetGenericOwnerPhysicalTypeParameterVariance.COVARIANT,
            ),
        )
        val provisional = boundDeclarationIndex(types, emptyList())
        val receiverConstruction = boundConstruction(
            provisional,
            receiverOwner,
            emptyList(),
        )
        val sourceOfObject = boundConstruction(
            provisional,
            covariant,
            listOf(objectType()),
        )
        val methodDescription = callableMethodDescription(
            method,
            receiverOwner,
            listOf(callableSlot(
                DotNetGenericOwnerPhysicalSlotDomain.DECLARATION_INDEPENDENT,
                sourceOfObject,
            )),
            DotNetGenericOwnerPhysicalCallableResultLayoutReference.Void,
        )
        val declarations = boundDeclarationIndex(
            types,
            listOf(methodDescription),
            edgeSets = listOf(edgeSet(receiverOwner), edgeSet(covariant)),
        )
        val receiver = directValue(boundCarrier(declarations, receiverConstruction))
        val sourceOfString = boundConstruction(
            declarations,
            covariant,
            listOf(stringType()),
        )
        val sourceOfInt = boundConstruction(
            declarations,
            covariant,
            listOf(int32Type()),
        )

        val route = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerPhysicalOperationRoute,
                >>(
            selectDotNetGenericOwnerPhysicalOperationRoute(
                declarations,
                method,
                DotNetGenericOwnerPhysicalOperationRouteRequest(view(receiverConstruction)),
                receiver,
                listOf(directValue(boundCarrier(declarations, sourceOfString))),
            )
        ).value
        assertEquals(sourceOfObject, route.instantiatedSignature.parameterSlots.single().carrier)
        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            selectDotNetGenericOwnerPhysicalOperationRoute(
                declarations,
                method,
                DotNetGenericOwnerPhysicalOperationRouteRequest(view(receiverConstruction)),
                receiver,
                listOf(directValue(boundCarrier(declarations, sourceOfInt))),
            ),
        )
    }

    @Test
    fun `declaration authority rejects conflicting TypeDef descriptions`() {
        val identity = localOwnerIdentity(IrClassSymbolImpl())

        val result = DotNetGenericOwnerPhysicalDeclarationIndex.bind(
            DotNetGenericOwnerPhysicalAuthorityEpoch.BOUND_DECLARATION_INDEX,
            listOf(
                typeDescription(identity, 1, DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS),
                typeDescription(identity, 2, DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE),
            ),
            emptyList(),
        )

        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(result)
    }

    @Test
    fun `TypeDef authority snapshots every ordered GenericParam fact`() {
        val identity = localOwnerIdentity(IrClassSymbolImpl())
        val constraints = mutableListOf<DotNetGenericOwnerSymbolicCarrierReference>(objectType())
        val parameters = mutableListOf(
            DotNetGenericOwnerPhysicalGenericParameterReference(
                variance = DotNetGenericOwnerPhysicalTypeParameterVariance.COVARIANT,
                constraints = constraints,
                hasReferenceTypeConstraint = true,
                hasDefaultConstructorConstraint = true,
                allowsByRefLike = true,
            ),
        )
        val description = DotNetGenericOwnerPhysicalTypeDefReference(
            identity,
            parameters,
            DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE,
        )
        constraints.clear()
        parameters.clear()

        val binding = DotNetGenericOwnerPhysicalDeclarationIndex.bind(
            DotNetGenericOwnerPhysicalAuthorityEpoch.BOUND_DECLARATION_INDEX,
            listOf(description),
            emptyList(),
        )
        val declarations = assertIs<
                DotNetGenericOwnerPhysicalBindingResult.Bound<DotNetGenericOwnerPhysicalDeclarationIndex>,
                >(binding).value
        val retained = checkNotNull(declarations.typeDescriptionOrNull(identity))
        val parameter = retained.genericParameters.single()

        assertEquals(1, retained.genericArity)
        assertEquals(DotNetGenericOwnerPhysicalTypeParameterVariance.COVARIANT, parameter.variance)
        assertEquals(listOf(objectType()), parameter.constraints)
        assertTrue(parameter.hasReferenceTypeConstraint)
        assertTrue(parameter.hasDefaultConstructorConstraint)
        assertTrue(parameter.allowsByRefLike)
    }

    @Test
    fun `TypeDef authority rejects conflicting GenericParam variance and constraints`() {
        val identity = localOwnerIdentity(IrClassSymbolImpl())
        fun description(
            variance: DotNetGenericOwnerPhysicalTypeParameterVariance,
            referenceType: Boolean,
        ) = DotNetGenericOwnerPhysicalTypeDefReference(
            identity,
            listOf(DotNetGenericOwnerPhysicalGenericParameterReference(
                variance,
                constraints = emptyList(),
                hasReferenceTypeConstraint = referenceType,
            )),
            DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE,
        )

        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            DotNetGenericOwnerPhysicalDeclarationIndex.bind(
                DotNetGenericOwnerPhysicalAuthorityEpoch.BOUND_DECLARATION_INDEX,
                listOf(
                    description(DotNetGenericOwnerPhysicalTypeParameterVariance.COVARIANT, false),
                    description(DotNetGenericOwnerPhysicalTypeParameterVariance.CONTRAVARIANT, false),
                ),
                emptyList(),
            ),
        )
        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            DotNetGenericOwnerPhysicalDeclarationIndex.bind(
                DotNetGenericOwnerPhysicalAuthorityEpoch.BOUND_DECLARATION_INDEX,
                listOf(
                    description(DotNetGenericOwnerPhysicalTypeParameterVariance.COVARIANT, false),
                    description(DotNetGenericOwnerPhysicalTypeParameterVariance.COVARIANT, true),
                ),
                emptyList(),
            ),
        )
    }

    @Test
    fun `TypeDef GenericParam constraints may reference only their own binder`() {
        val owner = localOwnerIdentity(IrClassSymbolImpl())
        val other = localOwnerIdentity(IrClassSymbolImpl())
        val otherParameter = DotNetGenericOwnerSymbolicCarrierReference.Parameter
            .unboundTypeParameterReference(other, 0)
        val ownSecondParameter = DotNetGenericOwnerSymbolicCarrierReference.Parameter
            .unboundTypeParameterReference(owner, 1)
        fun parameter(
            constraints: List<DotNetGenericOwnerSymbolicCarrierReference> = emptyList(),
        ) = DotNetGenericOwnerPhysicalGenericParameterReference(
            DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT,
            constraints,
        )
        val otherDescription = DotNetGenericOwnerPhysicalTypeDefReference(
            other,
            listOf(parameter()),
            DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS,
        )

        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            DotNetGenericOwnerPhysicalDeclarationIndex.bind(
                DotNetGenericOwnerPhysicalAuthorityEpoch.BOUND_DECLARATION_INDEX,
                listOf(
                    DotNetGenericOwnerPhysicalTypeDefReference(
                        owner,
                        listOf(parameter(listOf(otherParameter))),
                        DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS,
                    ),
                    otherDescription,
                ),
                emptyList(),
            ),
        )
        assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<*>>(
            DotNetGenericOwnerPhysicalDeclarationIndex.bind(
                DotNetGenericOwnerPhysicalAuthorityEpoch.BOUND_DECLARATION_INDEX,
                listOf(
                    DotNetGenericOwnerPhysicalTypeDefReference(
                        owner,
                        listOf(parameter(listOf(ownSecondParameter)), parameter()),
                        DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS,
                    ),
                ),
                emptyList(),
            ),
        )
    }

    @Test
    fun `arbitrary construction remains unavailable until GenericParam constraints are proven`() {
        val identity = localOwnerIdentity(IrClassSymbolImpl())
        val binding = DotNetGenericOwnerPhysicalDeclarationIndex.bind(
            DotNetGenericOwnerPhysicalAuthorityEpoch.BOUND_DECLARATION_INDEX,
            listOf(DotNetGenericOwnerPhysicalTypeDefReference(
                identity,
                listOf(DotNetGenericOwnerPhysicalGenericParameterReference(
                    DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT,
                    constraints = emptyList(),
                    hasReferenceTypeConstraint = true,
                )),
                DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS,
            )),
            emptyList(),
        )
        val declarations = assertIs<
                DotNetGenericOwnerPhysicalBindingResult.Bound<DotNetGenericOwnerPhysicalDeclarationIndex>,
                >(binding).value

        assertIs<DotNetGenericOwnerPhysicalBindingResult.Unavailable>(
            declarations.constructTypeOrError(identity, listOf(stringType())),
        )
    }

    @Test
    fun `caller-authored constrained TypeDef cannot mint conversion authority`() {
        val identity = localOwnerIdentity(IrClassSymbolImpl())
        val declarations = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerPhysicalDeclarationIndex,
                >>(
            DotNetGenericOwnerPhysicalDeclarationIndex.bind(
                DotNetGenericOwnerPhysicalAuthorityEpoch.BOUND_DECLARATION_INDEX,
                listOf(DotNetGenericOwnerPhysicalTypeDefReference(
                    identity,
                    listOf(DotNetGenericOwnerPhysicalGenericParameterReference(
                        DotNetGenericOwnerPhysicalTypeParameterVariance.COVARIANT,
                        constraints = emptyList(),
                        hasReferenceTypeConstraint = true,
                    )),
                    DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE,
                )),
                emptyList(),
                listOf(edgeSet(identity)),
            )
        ).value
        val source = DotNetGenericOwnerPhysicalView(
            DotNetGenericOwnerSymbolicCarrierReference.Constructed.unboundTypeReference(
                identity,
                listOf(stringType()),
            )
        )
        val target = DotNetGenericOwnerPhysicalView(
            DotNetGenericOwnerSymbolicCarrierReference.Constructed.unboundTypeReference(
                identity,
                listOf(objectType()),
            )
        )

        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            declarations.proveClrReferenceVarianceConversionOrError(source, target),
        )
        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            declarations.constructTypeOrError(identity, target.construction.arguments),
        )
    }

    @Test
    fun `declaration authority rejects conflicting MethodDef descriptions`() {
        val owner = localOwnerIdentity(IrClassSymbolImpl())
        val identity = localMethodIdentity(IrSimpleFunctionSymbolImpl())

        val result = DotNetGenericOwnerPhysicalDeclarationIndex.bind(
            DotNetGenericOwnerPhysicalAuthorityEpoch.SEALED_EMISSION_SIGNATURE_INDEX,
            listOf(typeDescription(owner, 0, DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS)),
            listOf(
                methodDescription(identity, owner, 1),
                methodDescription(identity, owner, 0),
            ),
        )

        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(result)
    }

    @Test
    fun `FieldDef authority binds exact owner parameter and object carriers`() {
        val ownerDeclaration = testOwner("StateOwner")
        val owner = DotNetGenericOwnerPhysicalTypeDefIdentity.Local(ownerDeclaration.symbol, view = null)
        val typedField = testField(ownerDeclaration, "typed")
        val objectField = testField(ownerDeclaration, "semantic")
        val types = listOf(typeDescription(owner, 1, DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS))
        val provisional = boundDeclarationIndex(types, emptyList())
        val ownerParameter = boundTypeParameter(provisional, owner, 0)
        val typedReference = fieldDescription(typedField.symbol, owner, ownerParameter)
        val objectReference = fieldDescription(objectField.symbol, owner, objectType())

        val declarations = boundDeclarationIndex(
            types = types,
            methods = emptyList(),
            fields = listOf(typedReference, objectReference),
        )

        assertEquals(
            typedReference,
            declarations.fieldDescriptionOrNull(
                DotNetGenericOwnerPhysicalFieldDefIdentity.Local(typedField.symbol),
            ),
        )
        assertEquals(
            objectReference,
            declarations.fieldDescriptionOrNull(
                DotNetGenericOwnerPhysicalFieldDefIdentity.Local(objectField.symbol),
            ),
        )
    }

    @Test
    fun `FieldDef authority rejects conflicting carriers for one field identity`() {
        val ownerDeclaration = testOwner("StateOwner")
        val owner = DotNetGenericOwnerPhysicalTypeDefIdentity.Local(ownerDeclaration.symbol, view = null)
        val field = testField(ownerDeclaration, "state")
        val types = listOf(typeDescription(owner, 1, DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS))
        val provisional = boundDeclarationIndex(types, emptyList())
        val ownerParameter = boundTypeParameter(provisional, owner, 0)

        val result = DotNetGenericOwnerPhysicalDeclarationIndex.bind(
            epoch = DotNetGenericOwnerPhysicalAuthorityEpoch.BOUND_DECLARATION_INDEX,
            typeDefinitions = types,
            methodDefinitions = emptyList(),
            fieldDefinitions = listOf(
                fieldDescription(field.symbol, owner, ownerParameter),
                fieldDescription(field.symbol, owner, objectType()),
            ),
        )

        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(result)
    }

    @Test
    fun `FieldDef authority requires a declared exact physical owner`() {
        val ownerDeclaration = testOwner("StateOwner")
        val otherOwnerDeclaration = testOwner("OtherOwner")
        val owner = DotNetGenericOwnerPhysicalTypeDefIdentity.Local(ownerDeclaration.symbol, view = null)
        val otherOwner = DotNetGenericOwnerPhysicalTypeDefIdentity.Local(otherOwnerDeclaration.symbol, view = null)
        val field = testField(ownerDeclaration, "state")

        assertIs<DotNetGenericOwnerPhysicalBindingResult.Unavailable>(
            DotNetGenericOwnerPhysicalDeclarationIndex.bind(
                epoch = DotNetGenericOwnerPhysicalAuthorityEpoch.BOUND_DECLARATION_INDEX,
                typeDefinitions = emptyList(),
                methodDefinitions = emptyList(),
                fieldDefinitions = listOf(fieldDescription(field.symbol, owner, objectType())),
            ),
        )
        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            DotNetGenericOwnerPhysicalDeclarationIndex.bind(
                epoch = DotNetGenericOwnerPhysicalAuthorityEpoch.BOUND_DECLARATION_INDEX,
                typeDefinitions = listOf(
                    typeDescription(owner, 0, DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS),
                    typeDescription(otherOwner, 0, DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS),
                ),
                methodDefinitions = emptyList(),
                fieldDefinitions = listOf(fieldDescription(field.symbol, otherOwner, objectType())),
            ),
        )
        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            DotNetGenericOwnerPhysicalDeclarationIndex.bind(
                epoch = DotNetGenericOwnerPhysicalAuthorityEpoch.BOUND_DECLARATION_INDEX,
                typeDefinitions = listOf(
                    typeDescription(
                        DotNetGenericOwnerPhysicalTypeDefIdentity.Local(
                            ownerDeclaration.symbol,
                            DotNetGenericInterfaceView.DECLARED,
                        ),
                        0,
                        DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS,
                    ),
                ),
                methodDefinitions = emptyList(),
                fieldDefinitions = listOf(
                    fieldDescription(
                        field.symbol,
                        DotNetGenericOwnerPhysicalTypeDefIdentity.Local(
                            ownerDeclaration.symbol,
                            DotNetGenericInterfaceView.DECLARED,
                        ),
                        objectType(),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `FieldDef authority rejects carriers from another generic binder`() {
        val ownerDeclaration = testOwner("StateOwner")
        val otherOwnerDeclaration = testOwner("OtherOwner")
        val owner = DotNetGenericOwnerPhysicalTypeDefIdentity.Local(ownerDeclaration.symbol, view = null)
        val otherOwner = DotNetGenericOwnerPhysicalTypeDefIdentity.Local(otherOwnerDeclaration.symbol, view = null)
        val field = testField(ownerDeclaration, "state")
        val types = listOf(
            typeDescription(owner, 1, DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS),
            typeDescription(otherOwner, 1, DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS),
        )
        val provisional = boundDeclarationIndex(types, emptyList())

        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            DotNetGenericOwnerPhysicalDeclarationIndex.bind(
                epoch = DotNetGenericOwnerPhysicalAuthorityEpoch.BOUND_DECLARATION_INDEX,
                typeDefinitions = types,
                methodDefinitions = emptyList(),
                fieldDefinitions = listOf(
                    fieldDescription(field.symbol, owner, boundTypeParameter(provisional, otherOwner, 0)),
                ),
            ),
        )
        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            DotNetGenericOwnerPhysicalDeclarationIndex.bind(
                epoch = DotNetGenericOwnerPhysicalAuthorityEpoch.BOUND_DECLARATION_INDEX,
                typeDefinitions = types,
                methodDefinitions = emptyList(),
                fieldDefinitions = listOf(
                    fieldDescription(
                        field.symbol,
                        owner,
                        DotNetGenericOwnerSymbolicCarrierReference.Parameter.methodParameterReference(
                            localMethodIdentity(IrSimpleFunctionSymbolImpl()),
                            0,
                        ),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `final state seal accepts selected state beside ordinary complete fields`() {
        val fixture = stateEmissionFixture()

        val snapshot = fixture.authority.sealFinalStateFields(
            listOf(fixture.scopeObservation()),
        ).single()

        assertEquals(DotNetIlEmissionScope.USER, snapshot.scope)
        assertEquals("StateOwner", snapshot.ownerName)
        assertEquals("state", snapshot.logicalFieldName)
        assertEquals("state", snapshot.physicalFieldName)
        assertEquals(
            DotNetGenericOwnerStateCarrierRequirement.TYPED_STORAGE_PRODUCER_GRAPH_PROVEN,
            snapshot.requirement,
        )
        assertEquals(
            DotNetGenericOwnerPhysicalStateEmissionCarrierKind.OWNER_TYPE_PARAMETER,
            snapshot.carrierKind,
        )
        assertEquals(0, snapshot.ownerParameterIndex)
    }

    @Test
    fun `final state seal rejects duplicate unbindable and cross-scope claimants`() {
        val fixture = stateEmissionFixture()
        val duplicateOwner = fixture.observedOwner.copy(
            physicalKey = DotNetGenericOwnerObservedPhysicalTypeDefKey(1),
        )
        val duplicateClaim = fixture.ownerObservation.copy(
            physicalType = DotNetGenericOwnerObservedMethodDefOwner.Local(duplicateOwner),
            physicalKey = duplicateOwner.physicalKey,
            fieldDefinitions = emptyList(),
        )
        val unbindableClaim = fixture.ownerObservation.copy(
            physicalType = DotNetGenericOwnerObservedMethodDefOwner.Unbindable(
                "hostile unbindable owner",
                isConflict = true,
            ),
            physicalKey = null,
            fieldDefinitions = emptyList(),
        )

        listOf(
            Triple("same-scope duplicate owner", listOf(
                fixture.scopeObservation(typeDefs = listOf(fixture.ownerObservation, duplicateClaim)),
            ), "no unique final TypeDef observation"),
            Triple("same-scope unbindable alias claimant", listOf(
                fixture.scopeObservation(typeDefs = listOf(fixture.ownerObservation, unbindableClaim)),
            ), "no unique final TypeDef observation"),
            Triple("cross-scope alias claimant", listOf(
                fixture.scopeObservation(),
                fixture.scopeObservation(
                    scope = DotNetIlEmissionScope.STDLIB,
                    typeDefs = listOf(unbindableClaim),
                ),
            ), "escaped its emission scope"),
        ).forEach { [description, emissions, expectedMessage] ->
            val failure = assertFailsWith<IllegalStateException>(
                "final state seal accepted $description",
            ) {
                fixture.authority.sealFinalStateFields(emissions)
            }
            assertTrue(
                expectedMessage in failure.message.orEmpty(),
                "final state seal rejected $description for the wrong reason: ${failure.message}",
            )
        }
    }

    @Test
    fun `final state seal requires the complete BOUND instance field set`() {
        val fixture = stateEmissionFixture()
        val extraField = testField(fixture.owner, "extra")
        val extraObservation = fixture.ordinaryFieldObservation.copy(
            physicalField = extraField.symbol,
            physicalFieldIdentity = DotNetGenericOwnerPhysicalFieldDefIdentity.Local(extraField.symbol),
            physicalName = "extra",
        )

        listOf(
            Triple(
                "missing ordinary field",
                listOf(fixture.selectedFieldObservation),
                "changed its complete BOUND instance-field set",
            ),
            Triple("extra post-BOUND field", listOf(
                fixture.selectedFieldObservation,
                fixture.ordinaryFieldObservation,
                extraObservation,
            ), "changed its complete BOUND instance-field set"),
        ).forEach { [description, fields, expectedMessage] ->
            val ownerObservation = fixture.ownerObservation.copy(fieldDefinitions = fields)
            val failure = assertFailsWith<IllegalStateException>(
                "final state seal accepted $description",
            ) {
                fixture.authority.sealFinalStateFields(
                    listOf(fixture.scopeObservation(typeDefs = listOf(ownerObservation))),
                )
            }
            assertTrue(
                expectedMessage in failure.message.orEmpty(),
                "final state seal rejected $description for the wrong reason: ${failure.message}",
            )
        }
    }

    @Test
    fun `final state seal rejects contradictory owner and selected FieldDef facts`() {
        val fixture = stateEmissionFixture()
        val otherOwnerDeclaration = testOwner("OtherStateOwner")
        val otherOwner = DotNetGenericOwnerObservedLocalTypeDef(
            physicalKey = DotNetGenericOwnerObservedPhysicalTypeDefKey(2),
            identity = DotNetGenericOwnerPhysicalTypeDefIdentity.Local(
                otherOwnerDeclaration.symbol,
                view = null,
            ),
            genericArity = 1,
            category = DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS,
        )
        val wrongArityOwner = fixture.observedOwner.copy(genericArity = 2)
        val wrongCategoryOwner = fixture.observedOwner.copy(
            category = DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE,
        )
        val twoParameters = List(2) {
            DotNetGenericOwnerPhysicalTypeDefGenericParameterObservation(
                DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT,
                constraints = emptyList(),
            )
        }
        val interfaceFlags = fixture.ownerObservation.flags.copy(
            isInterface = true,
            isAbstract = true,
            isSealed = false,
            isBeforeFieldInit = false,
        )

        listOf(
            Triple("owner arity", fixture.ownerObservation.copy(
                physicalType = DotNetGenericOwnerObservedMethodDefOwner.Local(wrongArityOwner),
                genericParameters = twoParameters,
            ), "contradicts its BOUND TypeDef shape"),
            Triple("owner category", fixture.ownerObservation.copy(
                physicalType = DotNetGenericOwnerObservedMethodDefOwner.Local(wrongCategoryOwner),
                flags = interfaceFlags,
            ), "contradicts its BOUND TypeDef shape"),
            Triple("selected visibility", fixture.ownerObservation.withSelectedField(
                fixture.selectedFieldObservation.copy(
                    visibility = DotNetIlRawMethodDefVisibility.PUBLIC,
                ),
            ), "flags contradict BOUND state authority"),
            Triple("selected init-only flag", fixture.ownerObservation.withSelectedField(
                fixture.selectedFieldObservation.copy(isInitOnly = true),
            ), "flags contradict BOUND state authority"),
            Triple("selected carrier", fixture.ownerObservation.withSelectedField(
                fixture.selectedFieldObservation.copy(
                    carrier = DotNetGenericOwnerObservedMethodCarrier.Leaf(
                        DotNetGenericOwnerPhysicalTypeKind.OBJECT,
                    ),
                ),
            ), "lost its owner-parameter carrier"),
            Triple("selected owner-parameter binder", fixture.ownerObservation.withSelectedField(
                fixture.selectedFieldObservation.copy(
                    carrier = DotNetGenericOwnerObservedMethodCarrier.OwnerParameter(otherOwner, 0),
                ),
            ), "changed its exact binder"),
        ).forEach { [description, ownerObservation, expectedMessage] ->
            val failure = assertFailsWith<IllegalStateException>(
                "final state seal accepted wrong $description",
            ) {
                fixture.authority.sealFinalStateFields(
                    listOf(fixture.scopeObservation(typeDefs = listOf(ownerObservation))),
                )
            }
            assertTrue(
                expectedMessage in failure.message.orEmpty(),
                "final state seal rejected wrong $description for the wrong reason: ${failure.message}",
            )
        }
    }

    @Test
    fun `MethodDef authority validates its full physical description and scoped binders`() {
        val owner = localOwnerIdentity(IrClassSymbolImpl())
        val otherOwner = localOwnerIdentity(IrClassSymbolImpl())
        val method = localMethodIdentity(IrSimpleFunctionSymbolImpl())
        val otherMethod = localMethodIdentity(IrSimpleFunctionSymbolImpl())
        val types = listOf(
            typeDescription(owner, 1, DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS),
            typeDescription(otherOwner, 1, DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS),
        )
        val provisional = boundDeclarationIndex(types, emptyList())
        val ownerParameter = boundTypeParameter(provisional, owner, 0)
        val otherOwnerParameter = boundTypeParameter(provisional, otherOwner, 0)
        val methodParameter = DotNetGenericOwnerSymbolicCarrierReference.Parameter
            .methodParameterReference(method, 0)
        val otherMethodParameter = DotNetGenericOwnerSymbolicCarrierReference.Parameter
            .methodParameterReference(otherMethod, 0)

        fun reference(
            declaringType: DotNetGenericOwnerPhysicalTypeDefIdentity = owner,
            visibility: DotNetGenericOwnerPhysicalMemberVisibility =
                DotNetGenericOwnerPhysicalMemberVisibility.PUBLIC,
            dispatch: DotNetGenericOwnerPhysicalMemberDispatch =
                DotNetGenericOwnerPhysicalMemberDispatch.OVERRIDABLE,
            isInstance: Boolean = true,
            parameterCarrier: DotNetGenericOwnerSymbolicCarrierReference = methodParameter,
            parameterDomain: DotNetGenericOwnerPhysicalSlotDomain =
                DotNetGenericOwnerPhysicalSlotDomain.DECLARATION_INDEPENDENT,
            resultLayout: DotNetGenericOwnerPhysicalCallableResultLayoutReference =
                DotNetGenericOwnerPhysicalCallableResultLayoutReference.Direct(
                    callableSlot(
                        DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_OUTPUT,
                        ownerParameter,
                    ),
                ),
        ) = DotNetGenericOwnerPhysicalMethodDefReference(
            identity = method,
            declaringType = declaringType,
            visibility = visibility,
            dispatch = dispatch,
            signature = DotNetGenericOwnerPhysicalMethodSignatureReference(
                isInstance = isInstance,
                genericArity = 1,
                resultLayout = resultLayout,
                parameterSlots = listOf(callableSlot(parameterDomain, parameterCarrier)),
            ),
            genericParameters = listOf(
                DotNetGenericOwnerPhysicalGenericParameterReference(
                    DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT,
                    constraints = emptyList(),
                ),
            ),
        )

        val valid = reference()
        assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<*>>(
            DotNetGenericOwnerPhysicalDeclarationIndex.bind(
                DotNetGenericOwnerPhysicalAuthorityEpoch.BOUND_DECLARATION_INDEX,
                types,
                listOf(valid),
            ),
        )
        listOf(
            reference(declaringType = otherOwner),
            reference(visibility = DotNetGenericOwnerPhysicalMemberVisibility.PRIVATE),
            reference(dispatch = DotNetGenericOwnerPhysicalMemberDispatch.FINAL),
            reference(isInstance = false),
            reference(parameterCarrier = ownerParameter),
            reference(
                resultLayout = DotNetGenericOwnerPhysicalCallableResultLayoutReference.SplitNullable(
                    callableSlot(
                        DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_OUTPUT,
                        ownerParameter,
                    ),
                ),
            ),
        ).forEach { conflicting ->
            assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
                DotNetGenericOwnerPhysicalDeclarationIndex.bind(
                    DotNetGenericOwnerPhysicalAuthorityEpoch.BOUND_DECLARATION_INDEX,
                    types,
                    listOf(valid, conflicting),
                ),
            )
        }
        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            DotNetGenericOwnerPhysicalDeclarationIndex.bind(
                DotNetGenericOwnerPhysicalAuthorityEpoch.BOUND_DECLARATION_INDEX,
                emptyList(),
                listOf(valid),
            ),
        )
        listOf(
            reference(parameterCarrier = otherOwnerParameter),
            reference(parameterCarrier = otherMethodParameter),
            reference(
                parameterCarrier = ownerParameter,
                parameterDomain = DotNetGenericOwnerPhysicalSlotDomain.DECLARATION_INDEPENDENT,
            ),
        ).forEach { malformed ->
            assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
                DotNetGenericOwnerPhysicalDeclarationIndex.bind(
                    DotNetGenericOwnerPhysicalAuthorityEpoch.BOUND_DECLARATION_INDEX,
                    types,
                    listOf(malformed),
                ),
            )
        }
    }

    @Test
    fun `split nullable rejects every non canonical hidden null flag`() {
        val payload = callableSlot(
            DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_OUTPUT,
            int32Type(),
        )
        assertFailsWith<IllegalArgumentException> {
            DotNetGenericOwnerPhysicalCallableResultLayoutReference.SplitNullable(
                payload,
                DotNetGenericOwnerPhysicalHiddenParameterReference(
                    DotNetGenericOwnerSymbolicCarrierReference.booleanCarrier(),
                    DotNetGenericOwnerPhysicalHiddenParameterPassing.REF,
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            DotNetGenericOwnerPhysicalCallableResultLayoutReference.SplitNullable(
                payload,
                DotNetGenericOwnerPhysicalHiddenParameterReference(
                    int32Type(),
                    DotNetGenericOwnerPhysicalHiddenParameterPassing.OUT,
                ),
            )
        }
    }

    @Test
    fun `matching duplicate descriptions denote one physical identity`() {
        val identity = localOwnerIdentity(IrClassSymbolImpl())
        val description = typeDescription(
            identity,
            1,
            DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE,
        )
        val index = boundDeclarationIndex(listOf(description, description), emptyList())

        val first = boundConstruction(index, identity, listOf(int32Type()))
        val second = boundConstruction(index, identity, listOf(int32Type()))

        assertEquals(first, second)
        assertEquals(identity, first.definition)
    }

    @Test
    fun `physical identity and construction snapshot caller-owned lists`() {
        val mutablePath = mutableListOf("Rehearsal.Snapshot`1")
        val identity = DotNetGenericOwnerPhysicalTypeDefIdentity.KotlinProducer(
            producerArtifact,
            mutablePath,
        )
        val index = boundDeclarationIndex(
            listOf(typeDescription(identity, 1, DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS)),
            emptyList(),
        )
        val mutableArguments = mutableListOf<DotNetGenericOwnerSymbolicCarrierReference>(int32Type())
        val construction = boundConstruction(index, identity, mutableArguments)

        mutablePath[0] = "Rehearsal.Mutated`1"
        mutableArguments[0] = stringType()

        assertEquals(listOf("Rehearsal.Snapshot`1"), identity.ownerPath)
        assertEquals(listOf(int32Type()), construction.arguments)
    }

    @Test
    fun `MethodDef and GenericParam authority snapshot caller-owned lists`() {
        val owner = localOwnerIdentity(IrClassSymbolImpl())
        val method = localMethodIdentity(IrSimpleFunctionSymbolImpl())
        val mutableConstraints = mutableListOf<DotNetGenericOwnerSymbolicCarrierReference>(
            stringType(),
        )
        val genericParameter = DotNetGenericOwnerPhysicalGenericParameterReference(
            DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT,
            mutableConstraints,
        )
        val mutableGenericParameters = mutableListOf(genericParameter)
        val reference = DotNetGenericOwnerPhysicalMethodDefReference(
            identity = method,
            declaringType = owner,
            visibility = DotNetGenericOwnerPhysicalMemberVisibility.PRIVATE,
            dispatch = DotNetGenericOwnerPhysicalMemberDispatch.FINAL,
            signature = DotNetGenericOwnerPhysicalMethodSignatureReference(
                isInstance = true,
                genericArity = 1,
                resultLayout = DotNetGenericOwnerPhysicalCallableResultLayoutReference.Void,
                parameterSlots = emptyList(),
            ),
            genericParameters = mutableGenericParameters,
        )
        val declarations = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerPhysicalDeclarationIndex,
                >>(
            DotNetGenericOwnerPhysicalDeclarationIndex.bind(
                DotNetGenericOwnerPhysicalAuthorityEpoch.BOUND_DECLARATION_INDEX,
                listOf(typeDescription(owner, 0, DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS)),
                listOf(reference),
            ),
        ).value

        mutableConstraints += int32Type()
        mutableGenericParameters.clear()

        val retained = checkNotNull(declarations.methodDescriptionOrNull(method))
        assertEquals(1, retained.genericParameters.size)
        assertEquals(listOf(stringType()), retained.genericParameters.single().constraints)
    }

    @Test
    fun `matching MethodDef descriptions ignore GenericParam constraint row order`() {
        val owner = localOwnerIdentity(IrClassSymbolImpl())
        val firstConstraint = localOwnerIdentity(IrClassSymbolImpl())
        val secondConstraint = localOwnerIdentity(IrClassSymbolImpl())
        val method = localMethodIdentity(IrSimpleFunctionSymbolImpl())
        val types = listOf(
            typeDescription(owner, 0, DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS),
            typeDescription(firstConstraint, 0, DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE),
            typeDescription(secondConstraint, 0, DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE),
        )
        val provisional = boundDeclarationIndex(types, emptyList())
        val constraints = listOf(
            boundConstruction(provisional, firstConstraint, emptyList()),
            boundConstruction(provisional, secondConstraint, emptyList()),
        )
        fun reference(
            orderedConstraints: List<DotNetGenericOwnerSymbolicCarrierReference>,
        ) = DotNetGenericOwnerPhysicalMethodDefReference(
            identity = method,
            declaringType = owner,
            visibility = DotNetGenericOwnerPhysicalMemberVisibility.PRIVATE,
            dispatch = DotNetGenericOwnerPhysicalMemberDispatch.FINAL,
            signature = DotNetGenericOwnerPhysicalMethodSignatureReference(
                isInstance = true,
                genericArity = 1,
                resultLayout = DotNetGenericOwnerPhysicalCallableResultLayoutReference.Void,
                parameterSlots = emptyList(),
            ),
            genericParameters = listOf(
                DotNetGenericOwnerPhysicalGenericParameterReference(
                    DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT,
                    orderedConstraints,
                ),
            ),
        )

        assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<*>>(
            DotNetGenericOwnerPhysicalDeclarationIndex.bind(
                DotNetGenericOwnerPhysicalAuthorityEpoch.BOUND_DECLARATION_INDEX,
                types,
                listOf(reference(constraints), reference(constraints.reversed())),
            ),
        )
    }

    @Test
    fun `authority epochs only advance and cannot reinterpret prior declarations`() {
        val owner = localOwnerIdentity(IrClassSymbolImpl())
        val earlyDescription = typeDescription(
            owner,
            1,
            DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE,
        )
        val early = boundDeclarationIndex(
            listOf(earlyDescription),
            emptyList(),
            DotNetGenericOwnerPhysicalAuthorityEpoch.EARLY_REPRESENTATION_PLAN,
        )

        val advanced = assertIs<
                DotNetGenericOwnerPhysicalBindingResult.Bound<
                        DotNetGenericOwnerPhysicalDeclarationIndex,
                        >,
                >(
            early.advance(
                DotNetGenericOwnerPhysicalAuthorityEpoch.BOUND_DECLARATION_INDEX,
                listOf(earlyDescription),
                emptyList(),
            ),
        ).value

        assertEquals(
            DotNetGenericOwnerPhysicalAuthorityEpoch.BOUND_DECLARATION_INDEX,
            advanced.epoch,
        )
        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            advanced.advance(
                DotNetGenericOwnerPhysicalAuthorityEpoch.EARLY_REPRESENTATION_PLAN,
                emptyList(),
                emptyList(),
            ),
        )
        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            advanced.advance(
                DotNetGenericOwnerPhysicalAuthorityEpoch.SEALED_EMISSION_SIGNATURE_INDEX,
                listOf(
                    typeDescription(owner, 2, DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS),
                ),
                emptyList(),
            ),
        )
    }

    @Test
    fun `producer MethodDef description cannot contradict its retained generic arity`() {
        val signature = DotNetGenericOwnerPhysicalMethodSignatureRecord(
            isInstance = true,
            genericArity = 1,
            resultLayout = DotNetGenericOwnerPhysicalCallableResultLayoutRecord.Void,
            parameterSlots = emptyList(),
        )
        val identity = DotNetGenericOwnerPhysicalMethodDefIdentity.KotlinProducer(
            producerArtifact,
            DotNetGenericOwnerPhysicalMethodIdentityRecord(
                listOf("Rehearsal.Source`1"),
                "Probe",
                signature,
            ),
        )
        val owner = DotNetGenericOwnerPhysicalTypeDefIdentity.KotlinProducer(
            producerArtifact,
            listOf("Rehearsal.Source`1"),
        )

        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            DotNetGenericOwnerPhysicalDeclarationIndex.bind(
                DotNetGenericOwnerPhysicalAuthorityEpoch.BOUND_DECLARATION_INDEX,
                listOf(typeDescription(owner, 1, DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS)),
                listOf(methodDescription(identity, owner, 0)),
            ),
        )
        val wrongOwner = DotNetGenericOwnerPhysicalTypeDefIdentity.KotlinProducer(
            producerArtifact,
            listOf("Rehearsal.Other`1"),
        )
        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            DotNetGenericOwnerPhysicalDeclarationIndex.bind(
                DotNetGenericOwnerPhysicalAuthorityEpoch.BOUND_DECLARATION_INDEX,
                listOf(
                    typeDescription(owner, 1, DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS),
                    typeDescription(wrongOwner, 1, DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS),
                ),
                listOf(methodDescription(identity, wrongOwner, 1)),
            ),
        )
    }

    @Test
    fun `retained MethodDef remains unavailable until its complete metadata adapter is bound`() {
        val signature = DotNetGenericOwnerPhysicalMethodSignatureRecord(
            isInstance = true,
            genericArity = 1,
            resultLayout = DotNetGenericOwnerPhysicalCallableResultLayoutRecord.Void,
            parameterSlots = emptyList(),
        )
        val identity = DotNetGenericOwnerPhysicalMethodDefIdentity.KotlinProducer(
            producerArtifact,
            DotNetGenericOwnerPhysicalMethodIdentityRecord(
                listOf("Rehearsal.Source`1"),
                "Probe",
                signature,
            ),
        )
        val owner = DotNetGenericOwnerPhysicalTypeDefIdentity.KotlinProducer(
            producerArtifact,
            listOf("Rehearsal.Source`1"),
        )

        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            DotNetGenericOwnerPhysicalDeclarationIndex.bind(
                DotNetGenericOwnerPhysicalAuthorityEpoch.BOUND_DECLARATION_INDEX,
                listOf(typeDescription(owner, 1, DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS)),
                listOf(methodDescription(identity, owner, 1)),
            ),
        )
    }

    @Test
    fun `physical null encoding is derived from authority`() {
        assertEquals(
            DotNetGenericOwnerPhysicalNullEncoding.NON_NULL_ONLY,
            int32Carrier().nullEncoding,
        )
        assertEquals(
            DotNetGenericOwnerPhysicalNullEncoding.NULL_REFERENCE,
            referenceCarrier(source(int32Type())).nullEncoding,
        )
        assertEquals(
            DotNetGenericOwnerPhysicalNullEncoding.INLINE_NULLABLE_VALUE,
            nullableIntCarrier().nullEncoding,
        )
        assertFailsWith<IllegalArgumentException> {
            typeDescription(
                sourceOwner,
                1,
                DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE,
                supportsInlineNull = true,
            )
        }
    }

    @Test
    fun `generic parameter identity is scoped to its physical owner`() {
        val firstOwner = localOwnerIdentity(IrClassSymbolImpl())
        val secondOwner = localOwnerIdentity(IrClassSymbolImpl())
        val index = boundDeclarationIndex(
            listOf(
                typeDescription(firstOwner, 1, DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS),
                typeDescription(secondOwner, 1, DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS),
            ),
            emptyList(),
        )
        val firstParameter = boundTypeParameter(index, firstOwner, 0)
        val secondParameter = boundTypeParameter(index, secondOwner, 0)

        assertNotEquals(firstParameter, secondParameter)
        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            index.typeParameterOrError(firstOwner, 1),
        )
    }

    @Test
    fun `method parameter identity is scoped to its physical MethodDef`() {
        val owner = localOwnerIdentity(IrClassSymbolImpl())
        val firstMethod = localMethodIdentity(IrSimpleFunctionSymbolImpl())
        val secondMethod = localMethodIdentity(IrSimpleFunctionSymbolImpl())
        val index = boundDeclarationIndex(
            listOf(typeDescription(owner, 0, DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS)),
            listOf(
                methodDescription(firstMethod, owner, 1),
                methodDescription(secondMethod, owner, 1),
            ),
        )
        val firstParameter = boundMethodParameter(index, firstMethod, 0)
        val secondParameter = boundMethodParameter(index, secondMethod, 0)

        assertNotEquals(firstParameter, secondParameter)
        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            index.methodParameterOrError(firstMethod, 1),
        )
    }

    @Test
    fun `unrecorded declarations are unavailable and never inferred`() {
        val index = boundDeclarationIndex(emptyList(), emptyList())
        val owner = localOwnerIdentity(IrClassSymbolImpl())
        val method = localMethodIdentity(IrSimpleFunctionSymbolImpl())

        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            index.constructTypeOrError(owner, listOf(int32Type())),
        )
        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            index.typeParameterOrError(owner, 0),
        )
        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            index.methodParameterOrError(method, 0),
        )
    }

    @Test
    fun `an unavailable edge set differs from a recorded empty edge set`() {
        val root = localOwnerIdentity(IrClassSymbolImpl())
        val early = boundDeclarationIndex(
            listOf(typeDescription(root, 0, DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE)),
            emptyList(),
        )

        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            early.directSupertypeEdgesOrUnavailable(root),
        )

        val sealed = boundEdgeIndex(early, listOf(edgeSet(root)))
        val recorded = assertIs<
                DotNetGenericOwnerPhysicalBindingResult.Bound<
                        Set<DotNetGenericOwnerPhysicalDirectSupertypeEdgeReference>,
                        >,
                >(sealed.directSupertypeEdgesOrUnavailable(root))
        val closure = boundInterfaceClosure(sealed, boundConstruction(sealed, root, emptyList()))

        assertEquals(emptySet(), recorded.value)
        assertEquals(setOf(view(boundConstruction(sealed, root, emptyList()))), closure.interfaceViews)
        assertTrue(closure.isComplete)
    }

    @Test
    fun `direct-supertype authority validates base and interface target categories`() {
        val source = localOwnerIdentity(IrClassSymbolImpl())
        val base = localOwnerIdentity(IrClassSymbolImpl())
        val firstBase = localOwnerIdentity(IrClassSymbolImpl())
        val contract = localOwnerIdentity(IrClassSymbolImpl())
        val index = boundDeclarationIndex(
            listOf(
                typeDescription(source, 0, DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS),
                typeDescription(base, 0, DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS),
                typeDescription(firstBase, 0, DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS),
                typeDescription(contract, 0, DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE),
            ),
            emptyList(),
        )
        val baseType = boundConstruction(index, base, emptyList())
        val firstBaseType = boundConstruction(index, firstBase, emptyList())
        val contractType = boundConstruction(index, contract, emptyList())

        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            index.advance(
                DotNetGenericOwnerPhysicalAuthorityEpoch.SEALED_EMISSION_SIGNATURE_INDEX,
                emptyList(),
                emptyList(),
                listOf(edgeSet(source)),
            ),
        )
        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            index.advance(
                DotNetGenericOwnerPhysicalAuthorityEpoch.SEALED_EMISSION_SIGNATURE_INDEX,
                emptyList(),
                emptyList(),
                listOf(edgeSet(source, interfaceEdge(baseType))),
            ),
        )
        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            index.advance(
                DotNetGenericOwnerPhysicalAuthorityEpoch.SEALED_EMISSION_SIGNATURE_INDEX,
                emptyList(),
                emptyList(),
                listOf(edgeSet(source, baseEdge(contractType))),
            ),
        )
        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            index.advance(
                DotNetGenericOwnerPhysicalAuthorityEpoch.SEALED_EMISSION_SIGNATURE_INDEX,
                emptyList(),
                emptyList(),
                listOf(edgeSet(source, baseEdge(baseType), baseEdge(firstBaseType))),
            ),
        )
        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            index.advance(
                DotNetGenericOwnerPhysicalAuthorityEpoch.SEALED_EMISSION_SIGNATURE_INDEX,
                emptyList(),
                emptyList(),
                listOf(edgeSet(contract, baseEdge(baseType))),
            ),
        )

        assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<*>>(
            index.advance(
                DotNetGenericOwnerPhysicalAuthorityEpoch.SEALED_EMISSION_SIGNATURE_INDEX,
                emptyList(),
                emptyList(),
                listOf(edgeSet(source, baseEdge(baseType), interfaceEdge(contractType))),
            ),
        )
    }

    @Test
    fun `interface closure follows recorded base-class edges`() {
        val derived = localOwnerIdentity(IrClassSymbolImpl())
        val base = localOwnerIdentity(IrClassSymbolImpl())
        val contract = localOwnerIdentity(IrClassSymbolImpl())
        val index = boundDeclarationIndex(
            listOf(
                typeDescription(derived, 0, DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS),
                typeDescription(base, 0, DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS),
                typeDescription(contract, 0, DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE),
            ),
            emptyList(),
        )
        val baseType = boundConstruction(index, base, emptyList())
        val contractType = boundConstruction(index, contract, emptyList())
        val sealed = boundEdgeIndex(
            index,
            listOf(
                edgeSet(derived, baseEdge(baseType)),
                edgeSet(
                    base,
                    baseEdge(DotNetGenericOwnerSymbolicCarrierReference.objectCarrier()),
                    interfaceEdge(contractType),
                ),
                edgeSet(contract),
            ),
        )

        val closure = boundInterfaceClosure(sealed, boundConstruction(sealed, derived, emptyList()))

        assertEquals(setOf(view(contractType)), closure.interfaceViews)
        assertTrue(closure.isComplete)
    }

    @Test
    fun `recorded interface selection disappears when only the physical edge changes`() {
        val owner = localOwnerIdentity(IrClassSymbolImpl())
        val natural = DotNetGenericOwnerPhysicalTypeDefIdentity.Local(
            IrClassSymbolImpl(),
            DotNetGenericInterfaceView.DECLARED,
        )
        val declarations = boundDeclarationIndex(
            listOf(
                typeDescription(owner, 1, DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS),
                typeDescription(natural, 1, DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE),
            ),
            emptyList(),
        )
        val ownerParameter = boundTypeParameter(declarations, owner, 0)
        val ownerConstruction = boundConstruction(declarations, owner, listOf(ownerParameter))
        val desiredConstruction = boundConstruction(declarations, natural, listOf(ownerParameter))
        val desiredView = view(desiredConstruction)

        fun produced(index: DotNetGenericOwnerPhysicalDeclarationIndex): DotNetGenericOwnerProducedValueFact {
            val reboundOwner = boundConstruction(index, owner, listOf(boundTypeParameter(index, owner, 0)))
            val carrier = assertIs<
                    DotNetGenericOwnerPhysicalBindingResult.Bound<DotNetGenericOwnerPhysicalCarrier>,
                    >(index.carrierOrError(reboundOwner)).value
            return directValue(carrier)
        }

        val withExactEdge = boundEdgeIndex(
            declarations,
            listOf(
                edgeSet(
                    owner,
                    baseEdge(objectType()),
                    interfaceEdge(desiredConstruction),
                ),
                edgeSet(natural),
            ),
        )
        val selected = assertIs<DotNetGenericOwnerProducedValueFact>(
            produced(withExactEdge).selectRecordedPhysicalInterfaceViewOrNull(
                withExactEdge,
                desiredView,
            ),
        )
        assertEquals(desiredView, selected.provenance.selectedViewLineage[natural])
        assertTrue(desiredView in knownViewsOf(selected.provenance))
        assertTrue(
            DotNetGenericOwnerPhysicalViewEvidence.RECORDED_INTERFACE_EDGE in
                    assertIs<DotNetGenericOwnerGuaranteedViews.Known>(
                        selected.provenance.guaranteedViews,
                    ).evidenceByView.getValue(desiredView),
        )

        val withoutInterfaceEdge = boundEdgeIndex(
            declarations,
            listOf(edgeSet(owner, baseEdge(objectType())), edgeSet(natural)),
        )
        assertNull(
            produced(withoutInterfaceEdge).selectRecordedPhysicalInterfaceViewOrNull(
                withoutInterfaceEdge,
                desiredView,
            ),
        )
        assertNull(
            produced(declarations).selectRecordedPhysicalInterfaceViewOrNull(
                declarations,
                desiredView,
            ),
        )

        val wrongConstruction = boundConstruction(declarations, natural, listOf(objectType()))
        val withOnlyWrongEdge = boundEdgeIndex(
            declarations,
            listOf(
                edgeSet(owner, baseEdge(objectType()), interfaceEdge(wrongConstruction)),
                edgeSet(natural),
            ),
        )
        assertNull(
            produced(withOnlyWrongEdge).selectRecordedPhysicalInterfaceViewOrNull(
                withOnlyWrongEdge,
                desiredView,
            ),
        )
        assertNotEquals(ownerConstruction, desiredConstruction)
    }

    @Test
    fun `suspend producers cannot enter the ordinary local MethodDef family`() {
        val logicalOwner = IrFactoryImpl.buildClass {
            name = Name.identifier("SuspendingProducer")
        }
        val parameter = logicalOwner.addTypeParameter {
            name = Name.identifier("T")
        }
        val logicalMember = IrFactoryImpl.buildFun {
            name = Name.identifier("produce")
            returnType = parameter.defaultType
            isSuspend = true
        }.also { member ->
            member.parent = logicalOwner
            logicalOwner.declarations += member
        }
        val capabilityOwner = IrFactoryImpl.buildClass {
            name = Name.identifier("SuspendingProducerSemantic")
        }
        val semanticMember = IrFactoryImpl.buildFun {
            name = Name.identifier("produceSemantic")
            returnType = parameter.defaultType
        }.also { member ->
            member.parent = capabilityOwner
            capabilityOwner.declarations += member
        }

        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            DotNetLocalGenericOwnerPhysicalCallableFamily.bindCallableOrError(
                DotNetLocalGenericOwnerPhysicalCallableFamilyInput(
                    logicalMember.symbol,
                    semanticMember.symbol,
                ),
                declarationIndex,
                emptyMap(),
            ),
        )
    }

    @Test
    fun `callable family rejects incoherent MethodDef generic arities`() {
        val logicalOwner = IrFactoryImpl.buildClass {
            name = Name.identifier("Producer")
        }
        val resultParameter = logicalOwner.addTypeParameter {
            name = Name.identifier("T")
        }
        val logicalMember = IrFactoryImpl.buildFun {
            name = Name.identifier("produce")
            returnType = resultParameter.defaultType
        }.also { member ->
            member.parent = logicalOwner
            logicalOwner.declarations += member
        }
        val semanticOwner = IrFactoryImpl.buildClass {
            name = Name.identifier("ProducerSemantic")
        }
        val semanticMember = IrFactoryImpl.buildFun {
            name = Name.identifier("produceSemantic")
            returnType = resultParameter.defaultType
        }.also { member ->
            member.parent = semanticOwner
            semanticOwner.declarations += member
        }
        val naturalOwnerIdentity = DotNetGenericOwnerPhysicalTypeDefIdentity.Local(
            logicalOwner.symbol,
            DotNetGenericInterfaceView.DECLARED,
        )
        val semanticOwnerIdentity = DotNetGenericOwnerPhysicalTypeDefIdentity.Local(
            semanticOwner.symbol,
            view = null,
        )
        val naturalInput = DotNetLocalGenericOwnerPhysicalTypeInput(
            naturalOwnerIdentity,
            "Producer",
            genericParameters = dotNetInvariantUnconstrainedPhysicalGenericParameters(1),
            role = DotNetLocalGenericOwnerPhysicalTypeRole.NATURAL_INTERFACE,
        )
        val semanticInput = DotNetLocalGenericOwnerPhysicalTypeInput(
            semanticOwnerIdentity,
            "ProducerSemantic",
            genericParameters = emptyList(),
            role = DotNetLocalGenericOwnerPhysicalTypeRole.SEMANTIC_CAPABILITY,
        )
        val typeOnlyIndex = boundDeclarationIndex(
            listOf(naturalInput.asReference(), semanticInput.asReference()),
            emptyList(),
        )
        val naturalIdentity = DotNetGenericOwnerPhysicalMethodDefIdentity.Local(
            logicalMember.symbol,
            DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY,
        )
        val semanticIdentity = DotNetGenericOwnerPhysicalMethodDefIdentity.Local(
            semanticMember.symbol,
            role = null,
        )
        val naturalMethodParameter =
            DotNetGenericOwnerSymbolicCarrierReference.Parameter.methodParameterReference(
                naturalIdentity,
                0,
            )
        fun producerMethod(
            identity: DotNetGenericOwnerPhysicalMethodDefIdentity.Local,
            owner: DotNetGenericOwnerPhysicalTypeDefIdentity.Local,
            genericArity: Int,
            result: DotNetGenericOwnerSymbolicCarrierReference,
        ) = DotNetGenericOwnerPhysicalMethodDefReference(
            identity,
            owner,
            DotNetGenericOwnerPhysicalMemberVisibility.PUBLIC,
            DotNetGenericOwnerPhysicalMemberDispatch.ABSTRACT,
            DotNetGenericOwnerPhysicalMethodSignatureReference(
                isInstance = true,
                genericArity = genericArity,
                resultLayout = DotNetGenericOwnerPhysicalCallableResultLayoutReference.Direct(
                    callableSlot(DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_OUTPUT, result),
                ),
                parameterSlots = if (genericArity == 1) {
                    listOf(callableSlot(
                        DotNetGenericOwnerPhysicalSlotDomain.DECLARATION_INDEPENDENT,
                        naturalMethodParameter,
                    ))
                } else {
                    emptyList()
                },
            ),
            genericParameters = List(genericArity) {
                DotNetGenericOwnerPhysicalGenericParameterReference(
                    DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT,
                    constraints = emptyList(),
                )
            },
        )
        val declarations = boundDeclarationIndex(
            listOf(naturalInput.asReference(), semanticInput.asReference()),
            listOf(
                producerMethod(
                    naturalIdentity,
                    naturalOwnerIdentity,
                    genericArity = 1,
                    result = boundTypeParameter(typeOnlyIndex, naturalOwnerIdentity, 0),
                ),
                producerMethod(
                    semanticIdentity,
                    semanticOwnerIdentity,
                    genericArity = 0,
                    result = objectType(),
                ),
            ),
        )

        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            DotNetLocalGenericOwnerPhysicalCallableFamily.bindCallableOrError(
                DotNetLocalGenericOwnerPhysicalCallableFamilyInput(
                    logicalMember.symbol,
                    semanticMember.symbol,
                ),
                declarations,
                listOf(naturalInput, semanticInput).associateBy { input -> input.identity },
            ),
        )
    }

    @Test
    fun `selected physical MethodDef is proven without endpoint fallback`() {
        val fixture = operationFixture(OperationFixtureSchema())

        val natural = boundOperationRoute(
            fixture,
            fixture.naturalMethod,
            fixture.naturalView,
        )
        assertEquals(fixture.naturalMethod, natural.method.identity)
        assertEquals(fixture.naturalView.family, natural.method.declaringType)
        assertTrue(natural.method.signature.isInstance)
        assertTrue(natural.method.signature.parameterSlots.isEmpty())
        assertEquals(
            fixture.ownerParameter,
            assertIs<DotNetGenericOwnerPhysicalCallableResultLayoutReference.Direct>(
                natural.instantiatedSignature.resultLayout,
            ).slot.carrier,
        )

        val semantic = boundOperationRoute(
            fixture,
            fixture.semanticMethod,
            fixture.semanticView,
        )
        assertEquals(fixture.semanticMethod, semantic.method.identity)
        assertEquals(fixture.semanticView.family, semantic.method.declaringType)
        assertTrue(semantic.method.signature.isInstance)
        assertTrue(semantic.method.signature.parameterSlots.isEmpty())
        assertEquals(
            objectType(),
            assertIs<DotNetGenericOwnerPhysicalCallableResultLayoutReference.Direct>(
                semantic.instantiatedSignature.resultLayout,
            ).slot.carrier,
        )
        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            selectDotNetGenericOwnerPhysicalOperationRoute(
                fixture.declarations,
                fixture.naturalMethod,
                DotNetGenericOwnerPhysicalOperationRouteRequest(
                    fixture.semanticView,
                ),
                fixture.receiver,
                emptyList(),
            ),
        )
        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            selectDotNetGenericOwnerPhysicalOperationRoute(
                fixture.declarations,
                fixture.semanticMethod,
                DotNetGenericOwnerPhysicalOperationRouteRequest(
                    fixture.naturalView,
                ),
                fixture.receiver,
                emptyList(),
            ),
        )
        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            selectDotNetGenericOwnerPhysicalOperationRoute(
                fixture.declarations,
                fixture.naturalMethod,
                DotNetGenericOwnerPhysicalOperationRouteRequest(
                    fixture.naturalView,
                ),
                fixture.receiver,
                listOf(fixture.receiver),
            ),
        )
        listOf(
            directValue(boundCarrier(fixture.declarations, objectType())),
            DotNetGenericOwnerProducedValueFact(
                DotNetGenericOwnerProducedValueLayout.Unknown,
                unknownProvenance(),
                DotNetGenericOwnerPhysicalNullState.NON_NULL,
            ),
        ).forEach { unprovenReceiver ->
            assertEquals(
                DotNetGenericOwnerPhysicalBindingResult.Unavailable,
                selectDotNetGenericOwnerPhysicalOperationRoute(
                    fixture.declarations,
                    fixture.naturalMethod,
                    DotNetGenericOwnerPhysicalOperationRouteRequest(
                        fixture.naturalView,
                    ),
                    unprovenReceiver,
                    emptyList(),
                ),
            )
        }
    }

    @Test
    fun `void physical MethodDef route produces no value`() {
        val fixture = operationFixture(
            OperationFixtureSchema(),
            naturalReturnsVoid = true,
        )

        val route = boundOperationRoute(
            fixture,
            fixture.naturalMethod,
            fixture.naturalView,
        )

        assertEquals(
            DotNetGenericOwnerPhysicalCallableResultLayoutReference.Void,
            route.instantiatedSignature.resultLayout,
        )
        assertNull(route.producedResult)
    }

    @Test
    fun `operation routing never falls back across a missing selected edge or MethodDef`() {
        val schema = OperationFixtureSchema()

        fun route(
            fixture: OperationFixture,
            selectedMethod: DotNetGenericOwnerPhysicalMethodDefIdentity,
            view: DotNetGenericOwnerPhysicalView,
        ) = selectDotNetGenericOwnerPhysicalOperationRoute(
            fixture.declarations,
            selectedMethod,
            DotNetGenericOwnerPhysicalOperationRouteRequest(view),
            fixture.receiver,
            emptyList(),
        )

        operationFixture(schema, includeNaturalEdge = false).let { fixture ->
            assertEquals(
                DotNetGenericOwnerPhysicalBindingResult.Unavailable,
                route(fixture, fixture.naturalMethod, fixture.naturalView),
            )
            assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<*>>(
                route(fixture, fixture.semanticMethod, fixture.semanticView),
            )
        }
        operationFixture(schema, naturalEdgeUsesObject = true).let { fixture ->
            assertEquals(
                DotNetGenericOwnerPhysicalBindingResult.Unavailable,
                route(fixture, fixture.naturalMethod, fixture.naturalView),
            )
        }
        operationFixture(schema, includeSemanticEdge = false).let { fixture ->
            assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<*>>(
                route(fixture, fixture.naturalMethod, fixture.naturalView),
            )
            assertEquals(
                DotNetGenericOwnerPhysicalBindingResult.Unavailable,
                route(fixture, fixture.semanticMethod, fixture.semanticView),
            )
        }
        operationFixture(schema, includeNaturalMethod = false).let { fixture ->
            assertEquals(
                DotNetGenericOwnerPhysicalBindingResult.Unavailable,
                route(fixture, fixture.naturalMethod, fixture.naturalView),
            )
            assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<*>>(
                route(fixture, fixture.semanticMethod, fixture.semanticView),
            )
        }
        operationFixture(schema, includeSemanticMethod = false).let { fixture ->
            assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<*>>(
                route(fixture, fixture.naturalMethod, fixture.naturalView),
            )
            assertEquals(
                DotNetGenericOwnerPhysicalBindingResult.Unavailable,
                route(fixture, fixture.semanticMethod, fixture.semanticView),
            )
        }
    }

    @Test
    fun `selected MethodSpec arity is authoritative and substitutes only its own binder`() {
        val schema = OperationFixtureSchema()
        val callerMethod = localMethodIdentity(IrSimpleFunctionSymbolImpl())
        val constrainedMethod = localMethodIdentity(IrSimpleFunctionSymbolImpl())
        val splitArrayMethod = localMethodIdentity(IrSimpleFunctionSymbolImpl())
        val types = listOf(
            typeDescription(schema.owner, 1, DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS),
            typeDescription(schema.natural, 1, DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE),
            typeDescription(schema.semantic, 0, DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE),
        )
        val provisional = boundDeclarationIndex(types, emptyList())
        val ownerParameter = boundTypeParameter(provisional, schema.owner, 0)
        val ownerConstruction = boundConstruction(provisional, schema.owner, listOf(ownerParameter))
        val naturalConstruction = boundConstruction(provisional, schema.natural, listOf(ownerParameter))
        val semanticConstruction = boundConstruction(provisional, schema.semantic, emptyList())

        fun genericMethod(
            identity: DotNetGenericOwnerPhysicalMethodDefIdentity,
            owner: DotNetGenericOwnerPhysicalTypeDefIdentity,
            arity: Int,
            constraints: List<DotNetGenericOwnerSymbolicCarrierReference> = emptyList(),
        ): DotNetGenericOwnerPhysicalMethodDefReference {
            val parameters = List(arity) { index ->
                DotNetGenericOwnerSymbolicCarrierReference.Parameter.methodParameterReference(
                    identity,
                    index,
                )
            }
            return DotNetGenericOwnerPhysicalMethodDefReference(
                identity = identity,
                declaringType = owner,
                visibility = DotNetGenericOwnerPhysicalMemberVisibility.PUBLIC,
                dispatch = DotNetGenericOwnerPhysicalMemberDispatch.ABSTRACT,
                signature = DotNetGenericOwnerPhysicalMethodSignatureReference(
                    isInstance = true,
                    genericArity = arity,
                    resultLayout = DotNetGenericOwnerPhysicalCallableResultLayoutReference.Direct(
                        callableSlot(
                            DotNetGenericOwnerPhysicalSlotDomain.DECLARATION_INDEPENDENT,
                            parameters.last(),
                        ),
                    ),
                    parameterSlots = listOf(callableSlot(
                        DotNetGenericOwnerPhysicalSlotDomain.DECLARATION_INDEPENDENT,
                        parameters.first(),
                    )),
                ),
                genericParameters = List(arity) {
                    DotNetGenericOwnerPhysicalGenericParameterReference(
                        DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT,
                        constraints = constraints,
                    )
                },
            )
        }

        val splitArrayParameter =
            DotNetGenericOwnerSymbolicCarrierReference.Parameter.methodParameterReference(
                splitArrayMethod,
                0,
            )
        val splitArrayCarrier = DotNetGenericOwnerSymbolicCarrierReference.SzArray(
            splitArrayParameter,
        )
        val splitArrayMethodReference = DotNetGenericOwnerPhysicalMethodDefReference(
            identity = splitArrayMethod,
            declaringType = schema.semantic,
            visibility = DotNetGenericOwnerPhysicalMemberVisibility.PUBLIC,
            dispatch = DotNetGenericOwnerPhysicalMemberDispatch.ABSTRACT,
            signature = DotNetGenericOwnerPhysicalMethodSignatureReference(
                isInstance = true,
                genericArity = 1,
                resultLayout = DotNetGenericOwnerPhysicalCallableResultLayoutReference.SplitNullable(
                    callableSlot(
                        DotNetGenericOwnerPhysicalSlotDomain.DECLARATION_INDEPENDENT,
                        splitArrayCarrier,
                    ),
                ),
                parameterSlots = listOf(callableSlot(
                    DotNetGenericOwnerPhysicalSlotDomain.DECLARATION_INDEPENDENT,
                    splitArrayCarrier,
                )),
            ),
            genericParameters = listOf(DotNetGenericOwnerPhysicalGenericParameterReference(
                DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT,
                constraints = emptyList(),
            )),
        )

        val declarations = boundDeclarationIndex(
            types,
            listOf(
                genericMethod(schema.naturalMethod, schema.natural, arity = 2),
                genericMethod(schema.semanticMethod, schema.semantic, arity = 1),
                genericMethod(
                    constrainedMethod,
                    schema.semantic,
                    arity = 1,
                    constraints = listOf(objectType()),
                ),
                splitArrayMethodReference,
                methodDescription(callerMethod, schema.owner, arity = 1),
            ),
            edgeSets = listOf(
                edgeSet(
                    schema.owner,
                    baseEdge(objectType()),
                    interfaceEdge(naturalConstruction),
                    interfaceEdge(semanticConstruction),
                ),
                edgeSet(schema.natural),
                edgeSet(schema.semantic),
            ),
        )
        val receiver = directValue(boundCarrier(declarations, ownerConstruction))
        val intArgument = directValue(boundCarrier(declarations, int32Type()))

        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            selectDotNetGenericOwnerPhysicalOperationRoute(
                declarations,
                schema.naturalMethod,
                DotNetGenericOwnerPhysicalOperationRouteRequest(
                    view(naturalConstruction),
                    methodArguments = listOf(int32Type()),
                ),
                receiver,
                listOf(intArgument),
            ),
        )

        fun semanticRoute(
            methodArgument: DotNetGenericOwnerSymbolicCarrierReference,
        ): DotNetGenericOwnerPhysicalOperationRoute {
            val argument = directValue(boundCarrier(declarations, methodArgument))
            return assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                    DotNetGenericOwnerPhysicalOperationRoute,
                    >>(
                selectDotNetGenericOwnerPhysicalOperationRoute(
                    declarations,
                    schema.semanticMethod,
                    DotNetGenericOwnerPhysicalOperationRouteRequest(
                        view(semanticConstruction),
                        methodArguments = listOf(methodArgument),
                    ),
                    receiver,
                    listOf(argument),
                ),
            ).value
        }

        val closed = semanticRoute(int32Type())
        assertEquals(listOf(int32Type()), closed.methodArguments)
        assertEquals(
            int32Type(),
            assertIs<DotNetGenericOwnerPhysicalCallableResultLayoutReference.Direct>(
                closed.instantiatedSignature.resultLayout,
            ).slot.carrier,
        )
        assertEquals(int32Type(), closed.instantiatedSignature.parameterSlots.single().carrier)

        val openCallerParameter = boundMethodParameter(declarations, callerMethod, 0)
        val open = semanticRoute(openCallerParameter)
        assertEquals(listOf(openCallerParameter), open.methodArguments)
        assertEquals(
            openCallerParameter,
            assertIs<DotNetGenericOwnerPhysicalCallableResultLayoutReference.Direct>(
                open.instantiatedSignature.resultLayout,
            ).slot.carrier,
        )
        assertEquals(openCallerParameter, open.instantiatedSignature.parameterSlots.single().carrier)

        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            selectDotNetGenericOwnerPhysicalOperationRoute(
                declarations,
                constrainedMethod,
                DotNetGenericOwnerPhysicalOperationRouteRequest(
                    view(semanticConstruction),
                    methodArguments = listOf(int32Type()),
                ),
                receiver,
                listOf(intArgument),
            ),
        )

        val intArray = DotNetGenericOwnerSymbolicCarrierReference.SzArray(int32Type())
        val splitArrayRoute = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerPhysicalOperationRoute,
                >>(
            selectDotNetGenericOwnerPhysicalOperationRoute(
                declarations,
                splitArrayMethod,
                DotNetGenericOwnerPhysicalOperationRouteRequest(
                    view(semanticConstruction),
                    methodArguments = listOf(int32Type()),
                ),
                receiver,
                listOf(directValue(boundCarrier(declarations, intArray))),
            ),
        ).value
        assertEquals(intArray, splitArrayRoute.instantiatedSignature.parameterSlots.single().carrier)
        assertEquals(
            intArray,
            assertIs<DotNetGenericOwnerPhysicalCallableResultLayoutReference.SplitNullable>(
                splitArrayRoute.instantiatedSignature.resultLayout,
            ).payloadSlot.carrier,
        )
    }

    @Test
    fun `strict owner input composes with split nullable output using exact value carriers`() {
        val owner = localOwnerIdentity(IrClassSymbolImpl())
        val natural = DotNetGenericOwnerPhysicalTypeDefIdentity.Local(
            IrClassSymbolImpl(),
            DotNetGenericInterfaceView.DECLARED,
        )
        val method = DotNetGenericOwnerPhysicalMethodDefIdentity.Local(
            IrSimpleFunctionSymbolImpl(),
            DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY,
        )
        val types = listOf(
            typeDescription(owner, 2, DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS),
            typeDescription(natural, 2, DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE),
        )
        val provisional = boundDeclarationIndex(types, emptyList())
        val ownerK = boundTypeParameter(provisional, owner, 0)
        val ownerV = boundTypeParameter(provisional, owner, 1)
        val naturalK = boundTypeParameter(provisional, natural, 0)
        val naturalV = boundTypeParameter(provisional, natural, 1)
        val methodReference = callableMethodDescription(
            identity = method,
            declaringType = natural,
            parameterSlots = listOf(callableSlot(
                DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_INPUT,
                naturalK,
            )),
            resultLayout = DotNetGenericOwnerPhysicalCallableResultLayoutReference.SplitNullable(
                callableSlot(DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_OUTPUT, naturalV),
            ),
        )
        val naturalTemplate = boundConstruction(provisional, natural, listOf(ownerK, ownerV))
        val declarations = boundDeclarationIndex(
            types,
            listOf(methodReference),
            edgeSets = listOf(
                edgeSet(owner, baseEdge(objectType()), interfaceEdge(naturalTemplate)),
                edgeSet(natural),
            ),
        )
        fun select(
            ownerArguments: List<DotNetGenericOwnerSymbolicCarrierReference>,
            argument: DotNetGenericOwnerProducedValueFact,
        ): DotNetGenericOwnerPhysicalOperationRoute {
            val ownerConstruction = boundConstruction(declarations, owner, ownerArguments)
            val naturalConstruction = boundConstruction(declarations, natural, ownerArguments)
            val receiver = directValue(boundCarrier(declarations, ownerConstruction))
            return assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                    DotNetGenericOwnerPhysicalOperationRoute,
                    >>(
                selectDotNetGenericOwnerPhysicalOperationRoute(
                    declarations,
                    method,
                    DotNetGenericOwnerPhysicalOperationRouteRequest(
                        view(naturalConstruction),
                    ),
                    receiver,
                    listOf(argument),
                ),
            ).value
        }

        val symbolic = select(
            listOf(ownerK, ownerV),
            directValue(boundCarrier(declarations, ownerK)),
        )
        assertEquals(ownerK, symbolic.instantiatedSignature.parameterSlots.single().carrier)
        assertEquals(
            ownerV,
            assertIs<DotNetGenericOwnerPhysicalCallableResultLayoutReference.SplitNullable>(
                symbolic.instantiatedSignature.resultLayout,
            ).payloadSlot.carrier,
        )

        val concrete = select(
            listOf(int32Type(), int32Type()),
            directValue(boundCarrier(declarations, int32Type())),
        )
        val concreteResult = assertIs<DotNetGenericOwnerPhysicalCallableResultLayoutReference.SplitNullable>(
            concrete.instantiatedSignature.resultLayout,
        )
        assertEquals(int32Type(), concrete.instantiatedSignature.parameterSlots.single().carrier)
        assertEquals(int32Type(), concreteResult.payloadSlot.carrier)
        assertEquals(
            DotNetGenericOwnerPhysicalHiddenParameterReference(
                DotNetGenericOwnerSymbolicCarrierReference.booleanCarrier(),
                DotNetGenericOwnerPhysicalHiddenParameterPassing.OUT,
            ),
            concreteResult.nullFlag,
        )
        val concreteProducedResult = assertNotNull(concrete.producedResult)
        assertEquals(
            DotNetGenericOwnerProducedValueLayout.SplitNullable(
                boundCarrier(declarations, int32Type()),
            ),
            concreteProducedResult.layout,
        )
        assertEquals(
            DotNetGenericOwnerPhysicalNullState.MAYBE_NULL,
            concreteProducedResult.nullState,
        )
        val concreteOwner = boundConstruction(
            declarations,
            owner,
            listOf(int32Type(), int32Type()),
        )
        val concreteNatural = boundConstruction(
            declarations,
            natural,
            listOf(int32Type(), int32Type()),
        )
        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            selectDotNetGenericOwnerPhysicalOperationRoute(
                declarations,
                method,
                DotNetGenericOwnerPhysicalOperationRouteRequest(
                    view(concreteNatural),
                ),
                directValue(boundCarrier(declarations, concreteOwner)),
                listOf(directValue(boundCarrier(declarations, objectType()))),
            ),
        )
    }

    @Test
    fun `owner and method inputs compose with a split nullable owner result`() {
        val owner = localOwnerIdentity(IrClassSymbolImpl())
        val natural = DotNetGenericOwnerPhysicalTypeDefIdentity.Local(
            IrClassSymbolImpl(),
            DotNetGenericInterfaceView.DECLARED,
        )
        val method = DotNetGenericOwnerPhysicalMethodDefIdentity.Local(
            IrSimpleFunctionSymbolImpl(),
            DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY,
        )
        val types = listOf(
            typeDescription(owner, 1, DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS),
            typeDescription(natural, 2, DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE),
        )
        val provisional = boundDeclarationIndex(types, emptyList())
        val ownerT = boundTypeParameter(provisional, owner, 0)
        val naturalK = boundTypeParameter(provisional, natural, 0)
        val naturalV = boundTypeParameter(provisional, natural, 1)
        val methodR = DotNetGenericOwnerSymbolicCarrierReference.Parameter.methodParameterReference(
            method,
            0,
        )
        val methodReference = DotNetGenericOwnerPhysicalMethodDefReference(
            identity = method,
            declaringType = natural,
            visibility = DotNetGenericOwnerPhysicalMemberVisibility.PUBLIC,
            dispatch = DotNetGenericOwnerPhysicalMemberDispatch.ABSTRACT,
            signature = DotNetGenericOwnerPhysicalMethodSignatureReference(
                isInstance = true,
                genericArity = 1,
                resultLayout =
                    DotNetGenericOwnerPhysicalCallableResultLayoutReference.SplitNullable(
                        callableSlot(
                            DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_OUTPUT,
                            naturalV,
                        ),
                    ),
                parameterSlots = listOf(
                    callableSlot(
                        DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_INPUT,
                        naturalK,
                    ),
                    callableSlot(
                        DotNetGenericOwnerPhysicalSlotDomain.DECLARATION_INDEPENDENT,
                        methodR,
                    ),
                ),
            ),
            genericParameters = dotNetInvariantUnconstrainedPhysicalGenericParameters(1),
        )
        val naturalTemplate = boundConstruction(
            provisional,
            natural,
            listOf(ownerT, ownerT),
        )
        val declarations = boundDeclarationIndex(
            types,
            listOf(methodReference),
            edgeSets = listOf(
                edgeSet(owner, baseEdge(objectType()), interfaceEdge(naturalTemplate)),
                edgeSet(natural),
            ),
        )
        val ownerConstruction = boundConstruction(declarations, owner, listOf(ownerT))
        val naturalConstruction = boundConstruction(
            declarations,
            natural,
            listOf(ownerT, ownerT),
        )
        val receiver = directValue(boundCarrier(declarations, ownerConstruction))
        val ownerValue = directValue(boundCarrier(declarations, ownerT))
        val route = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerPhysicalOperationRoute,
                >>(
            selectDotNetGenericOwnerPhysicalOperationRoute(
                declarations,
                method,
                DotNetGenericOwnerPhysicalOperationRouteRequest(
                    view(naturalConstruction),
                    methodArguments = listOf(ownerT),
                ),
                receiver,
                listOf(ownerValue, ownerValue),
            ),
        ).value

        assertEquals(1, route.instantiatedSignature.genericArity)
        assertEquals(listOf(ownerT), route.methodArguments)
        assertEquals(
            listOf(
                DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_INPUT,
                DotNetGenericOwnerPhysicalSlotDomain.DECLARATION_INDEPENDENT,
            ),
            route.instantiatedSignature.parameterSlots.map { slot -> slot.domain },
        )
        assertEquals(
            listOf(ownerT, ownerT),
            route.instantiatedSignature.parameterSlots.map { slot -> slot.carrier },
        )
        assertEquals(methodR, route.method.signature.parameterSlots[1].carrier)
        val split = assertIs<
                DotNetGenericOwnerPhysicalCallableResultLayoutReference.SplitNullable,
                >(route.instantiatedSignature.resultLayout)
        assertEquals(ownerT, split.payloadSlot.carrier)
        assertEquals(
            DotNetGenericOwnerProducedValueLayout.SplitNullable(
                boundCarrier(declarations, ownerT),
            ),
            assertNotNull(route.producedResult).layout,
        )
        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            selectDotNetGenericOwnerPhysicalOperationRoute(
                declarations,
                method,
                DotNetGenericOwnerPhysicalOperationRouteRequest(
                    view(naturalConstruction),
                    methodArguments = listOf(ownerT),
                ),
                receiver,
                listOf(ownerValue, directValue(boundCarrier(declarations, objectType()))),
            ),
        )
    }

    @Test
    fun `direct-supertype targets may reference only their source TypeDef parameters`() {
        val source = localOwnerIdentity(IrClassSymbolImpl())
        val other = localOwnerIdentity(IrClassSymbolImpl())
        val contract = localOwnerIdentity(IrClassSymbolImpl())
        val method = localMethodIdentity(IrSimpleFunctionSymbolImpl())
        val index = boundDeclarationIndex(
            listOf(
                typeDescription(source, 1, DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS),
                typeDescription(other, 1, DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS),
                typeDescription(contract, 1, DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE),
            ),
            listOf(methodDescription(method, source, 1)),
        )

        fun contractOf(argument: DotNetGenericOwnerSymbolicCarrierReference) =
            boundConstruction(index, contract, listOf(argument))

        assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<*>>(
            index.advance(
                DotNetGenericOwnerPhysicalAuthorityEpoch.SEALED_EMISSION_SIGNATURE_INDEX,
                emptyList(),
                emptyList(),
                listOf(edgeSet(
                    source,
                    baseEdge(DotNetGenericOwnerSymbolicCarrierReference.objectCarrier()),
                    interfaceEdge(contractOf(boundTypeParameter(index, source, 0))),
                )),
            ),
        )
        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            index.advance(
                DotNetGenericOwnerPhysicalAuthorityEpoch.SEALED_EMISSION_SIGNATURE_INDEX,
                emptyList(),
                emptyList(),
                listOf(edgeSet(
                    source,
                    baseEdge(DotNetGenericOwnerSymbolicCarrierReference.objectCarrier()),
                    interfaceEdge(contractOf(boundTypeParameter(index, other, 0))),
                )),
            ),
        )
        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            index.advance(
                DotNetGenericOwnerPhysicalAuthorityEpoch.SEALED_EMISSION_SIGNATURE_INDEX,
                emptyList(),
                emptyList(),
                listOf(edgeSet(
                    source,
                    baseEdge(DotNetGenericOwnerSymbolicCarrierReference.objectCarrier()),
                    interfaceEdge(contractOf(boundMethodParameter(index, method, 0))),
                )),
            ),
        )
    }

    @Test
    fun `interface closure recursively substitutes nested source parameters`() {
        val source = localOwnerIdentity(IrClassSymbolImpl())
        val middle = localOwnerIdentity(IrClassSymbolImpl())
        val root = localOwnerIdentity(IrClassSymbolImpl())
        val index = boundDeclarationIndex(
            listOf(
                typeDescription(source, 1, DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS),
                typeDescription(middle, 1, DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE),
                typeDescription(root, 1, DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE),
            ),
            emptyList(),
        )
        val sourceParameter = boundTypeParameter(index, source, 0)
        val middleParameter = boundTypeParameter(index, middle, 0)
        val middleOfSource = boundConstruction(index, middle, listOf(sourceParameter))
        val rootOfMiddleArray = boundConstruction(
            index,
            root,
            listOf(DotNetGenericOwnerSymbolicCarrierReference.SzArray(middleParameter)),
        )
        val sealed = boundEdgeIndex(
            index,
            listOf(
                edgeSet(
                    source,
                    baseEdge(DotNetGenericOwnerSymbolicCarrierReference.objectCarrier()),
                    interfaceEdge(middleOfSource),
                ),
                edgeSet(middle, interfaceEdge(rootOfMiddleArray)),
                edgeSet(root),
            ),
        )
        val sourceString = boundConstruction(sealed, source, listOf(stringType()))
        val middleString = boundConstruction(sealed, middle, listOf(stringType()))
        val rootStringArray = boundConstruction(
            sealed,
            root,
            listOf(DotNetGenericOwnerSymbolicCarrierReference.SzArray(stringType())),
        )

        val closure = boundInterfaceClosure(sealed, sourceString)

        assertEquals(setOf(view(middleString), view(rootStringArray)), closure.interfaceViews)
        assertTrue(closure.isComplete)
    }

    @Test
    fun `positive interface views survive an incomplete recorded edge closure`() {
        val source = localOwnerIdentity(IrClassSymbolImpl())
        val contract = localOwnerIdentity(IrClassSymbolImpl())
        val index = boundDeclarationIndex(
            listOf(
                typeDescription(source, 0, DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS),
                typeDescription(contract, 0, DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE),
            ),
            emptyList(),
        )
        val contractType = boundConstruction(index, contract, emptyList())
        val sealed = boundEdgeIndex(
            index,
            listOf(edgeSet(
                source,
                baseEdge(DotNetGenericOwnerSymbolicCarrierReference.objectCarrier()),
                interfaceEdge(contractType),
            )),
        )

        val closure = boundInterfaceClosure(sealed, boundConstruction(sealed, source, emptyList()))

        assertEquals(setOf(view(contractType)), closure.interfaceViews)
        assertFalse(closure.isComplete)
        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            sealed.directSupertypeEdgesOrUnavailable(contract),
        )
    }

    @Test
    fun `different constructions of one interface are ordinary positive views not conflicts`() {
        val source = localOwnerIdentity(IrClassSymbolImpl())
        val contract = localOwnerIdentity(IrClassSymbolImpl())
        val index = boundDeclarationIndex(
            listOf(
                typeDescription(source, 0, DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS),
                typeDescription(contract, 1, DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE),
            ),
            emptyList(),
        )
        val contractInt = boundConstruction(index, contract, listOf(int32Type()))
        val contractString = boundConstruction(index, contract, listOf(stringType()))
        val sealed = boundEdgeIndex(
            index,
            listOf(
                edgeSet(
                    source,
                    baseEdge(DotNetGenericOwnerSymbolicCarrierReference.objectCarrier()),
                    interfaceEdge(contractInt),
                    interfaceEdge(contractString),
                ),
                edgeSet(contract),
            ),
        )

        val closure = boundInterfaceClosure(sealed, boundConstruction(sealed, source, emptyList()))

        assertEquals(setOf(view(contractInt), view(contractString)), closure.interfaceViews)
        assertTrue(closure.isComplete)
    }

    @Test
    fun `different complete edge sets for one source are declaration conflicts`() {
        val source = localOwnerIdentity(IrClassSymbolImpl())
        val first = localOwnerIdentity(IrClassSymbolImpl())
        val second = localOwnerIdentity(IrClassSymbolImpl())
        val index = boundDeclarationIndex(
            listOf(
                typeDescription(source, 0, DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS),
                typeDescription(first, 0, DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE),
                typeDescription(second, 0, DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE),
            ),
            emptyList(),
        )

        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            index.advance(
                DotNetGenericOwnerPhysicalAuthorityEpoch.SEALED_EMISSION_SIGNATURE_INDEX,
                emptyList(),
                emptyList(),
                listOf(
                    edgeSet(
                        source,
                        baseEdge(DotNetGenericOwnerSymbolicCarrierReference.objectCarrier()),
                        interfaceEdge(boundConstruction(index, first, emptyList())),
                    ),
                    edgeSet(
                        source,
                        baseEdge(DotNetGenericOwnerSymbolicCarrierReference.objectCarrier()),
                        interfaceEdge(boundConstruction(index, second, emptyList())),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `a complete edge set rejects duplicate metadata rows`() {
        val source = localOwnerIdentity(IrClassSymbolImpl())
        val contract = localOwnerIdentity(IrClassSymbolImpl())
        val index = boundDeclarationIndex(
            listOf(
                typeDescription(source, 0, DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS),
                typeDescription(contract, 0, DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE),
            ),
            emptyList(),
        )
        val edge = interfaceEdge(boundConstruction(index, contract, emptyList()))

        assertFailsWith<IllegalArgumentException> {
            DotNetGenericOwnerPhysicalDirectSupertypeEdgeSet(source, listOf(edge, edge))
        }
    }

    @Test
    fun `declaration binding rejects an indirect physical inheritance cycle`() {
        val first = localOwnerIdentity(IrClassSymbolImpl())
        val second = localOwnerIdentity(IrClassSymbolImpl())
        val index = boundDeclarationIndex(
            listOf(
                typeDescription(first, 0, DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE),
                typeDescription(second, 0, DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE),
            ),
            emptyList(),
        )
        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            index.advance(
                DotNetGenericOwnerPhysicalAuthorityEpoch.SEALED_EMISSION_SIGNATURE_INDEX,
                emptyList(),
                emptyList(),
                listOf(
                    edgeSet(first, interfaceEdge(boundConstruction(index, second, emptyList()))),
                    edgeSet(second, interfaceEdge(boundConstruction(index, first, emptyList()))),
                ),
            ),
        )
    }

    @Test
    fun `core System Object cannot acquire a second constructed carrier identity`() {
        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            DotNetGenericOwnerPhysicalDeclarationIndex.bind(
                DotNetGenericOwnerPhysicalAuthorityEpoch.EARLY_REPRESENTATION_PLAN,
                listOf(
                    typeDescription(
                        DotNetGenericOwnerPhysicalTypeDefIdentity.CoreLibrary(
                            listOf("System", "Object"),
                        ),
                        0,
                        DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS,
                    ),
                ),
                emptyList(),
            ),
        )
    }

    @Test
    fun `construction binding validates arity and rejects void arguments`() {
        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            declarationIndex.constructTypeOrError(sourceOwner, emptyList()),
        )
        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            declarationIndex.constructTypeOrError(
                sourceOwner,
                listOf(DotNetGenericOwnerSymbolicCarrierReference.voidCarrier()),
            ),
        )
    }

    @Test
    fun `unreachable flow is join bottom`() {
        val reachable = DotNetGenericOwnerPhysicalFlowFact.Reachable(
            directValue(referenceCarrier(source(int32Type()))),
        )

        assertEquals(
            reachable,
            DotNetGenericOwnerPhysicalFlowFact.Unreachable.join(
                reachable,
                ::truthfulCommonCarrier,
            ),
        )
        assertEquals(
            reachable,
            reachable.join(
                DotNetGenericOwnerPhysicalFlowFact.Unreachable,
                ::truthfulCommonCarrier,
            ),
        )
    }

    @Test
    fun `ordinary reference-carrier disagreement loses precision without authority conflict`() {
        val leftCarrier = referenceCarrier(source(int32Type()))
        val rightCarrier = referenceCarrier(source(stringType()))
        val left = directValue(leftCarrier)
        val right = directValue(rightCarrier)

        val joined = left.join(right, ::truthfulCommonCarrier)

        assertEquals(DotNetGenericOwnerProducedValueLayout.Direct(objectCarrier()), joined.layout)
        assertEquals(
            emptySet(),
            assertIs<DotNetGenericOwnerGuaranteedViews.Known>(joined.provenance.guaranteedViews).views,
        )
    }

    @Test
    fun `a real shared admitted view may be the direct common carrier`() {
        val left = boundConstruction(declarationIndex, leftOwner, emptyList())
        val right = boundConstruction(declarationIndex, rightOwner, emptyList())
        val shared = source(objectType())
        val sharedView = view(shared)
        val leftFact = directValue(referenceCarrier(left), knownProvenance(sharedView))
        val rightFact = directValue(referenceCarrier(right), knownProvenance(sharedView))
        val selectShared: (
            DotNetGenericOwnerPhysicalCarrier,
            DotNetGenericOwnerPhysicalCarrier,
        ) -> DotNetGenericOwnerPhysicalCarrier? = { first, second ->
            if (setOf(first.type, second.type) == setOf(left, right)) referenceCarrier(shared)
            else truthfulCommonCarrier(first, second)
        }

        val joined = leftFact.join(rightFact, selectShared)

        assertEquals(DotNetGenericOwnerProducedValueLayout.Direct(referenceCarrier(shared)), joined.layout)
        assertEquals(setOf(sharedView), knownViewsOf(joined.provenance))
        assertFailsWith<IllegalArgumentException> {
            directValue(referenceCarrier(left)).join(
                directValue(referenceCarrier(right)),
                selectShared,
            )
        }
    }

    @Test
    fun `recorded interface join selects the unique shared physical construction`() {
        val types = listOf(
            typeDescription(sourceOwner, 1, DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE),
            typeDescription(leftOwner, 0, DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS),
            typeDescription(rightOwner, 0, DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS),
        )
        val provisional = boundDeclarationIndex(types, emptyList())
        val sharedConstruction = boundConstruction(provisional, sourceOwner, listOf(objectType()))
        val declarations = boundDeclarationIndex(
            types,
            emptyList(),
            edgeSets = listOf(
                edgeSet(leftOwner, baseEdge(objectType()), interfaceEdge(sharedConstruction)),
                edgeSet(rightOwner, baseEdge(objectType()), interfaceEdge(sharedConstruction)),
                edgeSet(sourceOwner),
            ),
        )
        val left = directValue(
            boundCarrier(declarations, boundConstruction(declarations, leftOwner, emptyList())),
        )
        val right = directValue(
            boundCarrier(declarations, boundConstruction(declarations, rightOwner, emptyList())),
        )

        val joined = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerProducedValueFact,
                >>(
            left.joinAtRecordedPhysicalInterfaceFamilyOrError(right, declarations, sourceOwner),
        ).value
        val sharedView = view(sharedConstruction)

        assertEquals(
            DotNetGenericOwnerProducedValueLayout.Direct(boundCarrier(declarations, sharedConstruction)),
            joined.layout,
        )
        assertTrue(sharedView in knownViewsOf(joined.provenance))
        assertEquals(sharedView, joined.provenance.selectedViewLineage[sourceOwner])
    }

    @Test
    fun `recorded interface join does not weaken an identical direct carrier`() {
        val types = listOf(
            typeDescription(sourceOwner, 1, DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE),
            typeDescription(leftOwner, 0, DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS),
        )
        val declarations = boundDeclarationIndex(
            types,
            emptyList(),
            edgeSets = listOf(edgeSet(leftOwner, baseEdge(objectType())), edgeSet(sourceOwner)),
        )
        val leftConstruction = boundConstruction(declarations, leftOwner, emptyList())
        val value = directValue(boundCarrier(declarations, leftConstruction))

        val joined = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerProducedValueFact,
                >>(
            value.joinAtRecordedPhysicalInterfaceFamilyOrError(value, declarations, sourceOwner),
        ).value

        assertEquals(value, joined)
    }

    @Test
    fun `recorded interface join cannot invent a missing physical edge`() {
        val types = listOf(
            typeDescription(sourceOwner, 1, DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE),
            typeDescription(leftOwner, 0, DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS),
            typeDescription(rightOwner, 0, DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS),
        )
        val provisional = boundDeclarationIndex(types, emptyList())
        val leftView = boundConstruction(provisional, sourceOwner, listOf(objectType()))
        val declarations = boundDeclarationIndex(
            types,
            emptyList(),
            edgeSets = listOf(
                edgeSet(leftOwner, baseEdge(objectType()), interfaceEdge(leftView)),
                edgeSet(rightOwner, baseEdge(objectType())),
                edgeSet(sourceOwner),
            ),
        )
        val left = directValue(
            boundCarrier(declarations, boundConstruction(declarations, leftOwner, emptyList())),
        )
        val right = directValue(
            boundCarrier(declarations, boundConstruction(declarations, rightOwner, emptyList())),
        )

        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            left.joinAtRecordedPhysicalInterfaceFamilyOrError(right, declarations, sourceOwner),
        )
    }

    @Test
    fun `recorded interface join treats ambiguous constructions as lost precision`() {
        val types = listOf(
            typeDescription(sourceOwner, 1, DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE),
            typeDescription(leftOwner, 0, DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS),
            typeDescription(rightOwner, 0, DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS),
        )
        val provisional = boundDeclarationIndex(types, emptyList())
        val intConstruction = boundConstruction(provisional, sourceOwner, listOf(int32Type()))
        val stringConstruction = boundConstruction(provisional, sourceOwner, listOf(stringType()))
        val declarations = boundDeclarationIndex(
            types,
            emptyList(),
            edgeSets = listOf(
                edgeSet(
                    leftOwner,
                    baseEdge(objectType()),
                    interfaceEdge(intConstruction),
                    interfaceEdge(stringConstruction),
                ),
                edgeSet(
                    rightOwner,
                    baseEdge(objectType()),
                    interfaceEdge(intConstruction),
                    interfaceEdge(stringConstruction),
                ),
                edgeSet(sourceOwner),
            ),
        )
        val leftCarrier = boundCarrier(
            declarations,
            boundConstruction(declarations, leftOwner, emptyList()),
        )
        val rightCarrier = boundCarrier(
            declarations,
            boundConstruction(declarations, rightOwner, emptyList()),
        )
        val left = directValue(leftCarrier)
        val right = directValue(rightCarrier)

        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            left.joinAtRecordedPhysicalInterfaceFamilyOrError(right, declarations, sourceOwner),
        )

        val selectedInt = knownProvenance(view(intConstruction), view(stringConstruction))
            .selectViewOrNull(view(intConstruction))!!
        val selectedJoin = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerProducedValueFact,
                >>(
            directValue(leftCarrier, selectedInt).joinAtRecordedPhysicalInterfaceFamilyOrError(
                directValue(rightCarrier, selectedInt),
                declarations,
                sourceOwner,
            ),
        ).value

        assertEquals(
            DotNetGenericOwnerProducedValueLayout.Direct(boundCarrier(declarations, intConstruction)),
            selectedJoin.layout,
        )
        assertEquals(
            view(intConstruction),
            selectedJoin.provenance.selectedViewLineage[sourceOwner],
        )
    }

    @Test
    fun `a direct constructed carrier cannot start with unknown self-view provenance`() {
        val sourceInt = source(int32Type())

        assertFailsWith<IllegalArgumentException> {
            DotNetGenericOwnerProducedValueFact(
                DotNetGenericOwnerProducedValueLayout.Direct(referenceCarrier(sourceInt)),
                unknownProvenance(),
                DotNetGenericOwnerPhysicalNullState.NON_NULL,
            )
        }
    }

    @Test
    fun `scalar disagreement cannot fabricate an object join through implicit boxing`() {
        val joined = directValue(int32Carrier()).join(
            directValue(stringCarrier()),
            ::truthfulCommonCarrier,
        )

        assertIs<DotNetGenericOwnerProducedValueLayout.Unknown>(joined.layout)
    }

    @Test
    fun `produced and storage carriers remain independent`() {
        val sourceInt = source(int32Type())
        val sourceIntView = view(sourceInt)
        val produced = directValue(
            carrier = referenceCarrier(sourceInt),
            provenance = knownProvenance(sourceIntView).selectViewOrNull(sourceIntView)!!,
        )

        val stored = produced.placeInStorageOrNull(
            DotNetGenericOwnerStorageCarrier.Fixed(objectCarrier()),
            ::canStoreIdentityPreserving,
        )!!
        val read = stored.read().value

        assertEquals(DotNetGenericOwnerProducedValueLayout.Direct(objectCarrier()), read.layout)
        assertEquals(produced.provenance, read.provenance)
    }

    @Test
    fun `boxing is an explicit materialization and not identity-preserving placement`() {
        assertNull(
            directValue(int32Carrier()).placeInStorageOrNull(
                DotNetGenericOwnerStorageCarrier.Fixed(objectCarrier()),
                ::canStoreIdentityPreserving,
            ),
        )
        assertNull(
            directValue(nullableIntCarrier()).placeInStorageOrNull(
                DotNetGenericOwnerStorageCarrier.Fixed(objectCarrier()),
                ::canStoreIdentityPreserving,
            ),
        )
    }

    @Test
    fun `every admitted storage fact can produce a valid read fact`() {
        assertFailsWith<IllegalArgumentException> {
            DotNetGenericOwnerPhysicalStorageFact(
                DotNetGenericOwnerPhysicalStorageLayout.Direct(
                    DotNetGenericOwnerStorageCarrier.Fixed(int32Carrier()),
                ),
                unknownProvenance(),
                DotNetGenericOwnerPhysicalNullState.MAYBE_NULL,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            DotNetGenericOwnerPhysicalStorageFact(
                DotNetGenericOwnerPhysicalStorageLayout.Direct(
                    DotNetGenericOwnerStorageCarrier.Fixed(int32Carrier()),
                ),
                DotNetGenericOwnerPhysicalValueProvenance.noNonNullViews(),
                DotNetGenericOwnerPhysicalNullState.NULL,
            )
        }
    }

    @Test
    fun `placement validates but cannot narrow a broad produced carrier`() {
        val broad = directValue(objectCarrier(), unknownProvenance())

        assertNull(
            broad.placeInStorageOrNull(
                DotNetGenericOwnerStorageCarrier.Fixed(referenceCarrier(source(int32Type()))),
                ::canStoreIdentityPreserving,
            ),
        )
    }

    @Test
    fun `local placement authority admits only equal local owner-bound carriers`() {
        val owner = testOwner("PlacementOwner")
        val function = IrFactoryImpl.buildFun {
            name = Name.identifier("place")
            returnType = owner.typeParameters.single().defaultType
        }.also { declaration ->
            declaration.parent = owner
            owner.declarations += declaration
        }
        val ownerIdentity = localOwnerIdentity(owner.symbol as IrClassSymbolImpl)
        val interfaceIdentity = DotNetGenericOwnerPhysicalTypeDefIdentity.Local(
            IrClassSymbolImpl(),
            DotNetGenericInterfaceView.DECLARED,
        )
        val localDeclarations = boundDeclarationIndex(
            listOf(
                typeDescription(
                    ownerIdentity,
                    1,
                    DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS,
                ),
                typeDescription(
                    interfaceIdentity,
                    1,
                    DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE,
                ),
            ),
            emptyList(),
        )
        val construction = boundConstruction(
            localDeclarations,
            interfaceIdentity,
            listOf(boundTypeParameter(localDeclarations, ownerIdentity, 0)),
        )
        val localCarrier = boundCarrier(localDeclarations, construction)
        val localProduced = directValue(localCarrier, knownProvenance(view(construction)))
        val localStorage = DotNetGenericOwnerPhysicalStorageFact(
            DotNetGenericOwnerPhysicalStorageLayout.Direct(
                DotNetGenericOwnerStorageCarrier.Fixed(localCarrier),
            ),
            localProduced.provenance,
            DotNetGenericOwnerPhysicalNullState.NON_NULL,
        )
        val ownerParameterCarrier = boundCarrier(
            localDeclarations,
            boundTypeParameter(localDeclarations, ownerIdentity, 0),
        )
        val parameterProduced = DotNetGenericOwnerProducedValueFact(
            DotNetGenericOwnerProducedValueLayout.Direct(ownerParameterCarrier),
            unknownProvenance(),
            DotNetGenericOwnerPhysicalNullState.MAYBE_NULL,
        )
        val parameterStorage = DotNetGenericOwnerPhysicalStorageFact(
            DotNetGenericOwnerPhysicalStorageLayout.Direct(
                DotNetGenericOwnerStorageCarrier.Fixed(ownerParameterCarrier),
            ),
            parameterProduced.provenance,
            DotNetGenericOwnerPhysicalNullState.MAYBE_NULL,
        )

        fun record(
            variable: IrVariableSymbolImpl,
            produced: DotNetGenericOwnerProducedValueFact,
            storage: DotNetGenericOwnerPhysicalStorageFact,
        ) = DotNetGenericOwnerPhysicalValueShadowRecord(
            function.symbol,
            variable,
            DotNetGenericOwnerPhysicalValueShadowSnapshot(
                ownerName = "PlacementOwner",
                sourceFunctionName = "place",
                physicalFunctionName = "place",
                functionRole = DotNetGenericOwnerPhysicalValueShadowFunctionRole.OTHER,
                phase = DotNetGenericOwnerPhysicalValueShadowPhase.POST_FINAL_ROUTING,
                variableName = "value",
                status = DotNetGenericOwnerPhysicalValueShadowStatus.ANALYZED,
                initializerProducedLayout = DotNetGenericOwnerPhysicalValueLayoutKind.DIRECT,
                initializerProducedCarrier = DotNetGenericOwnerPhysicalValueShadowCarrierSnapshot(
                    DotNetGenericOwnerPhysicalValueShadowCarrierKind.OBJECT,
                ),
                storageLayout = DotNetGenericOwnerPhysicalValueLayoutKind.DIRECT,
                storageCarrier = DotNetGenericOwnerPhysicalValueShadowCarrierSnapshot(
                    DotNetGenericOwnerPhysicalValueShadowCarrierKind.OBJECT,
                ),
                guaranteeState = DotNetGenericOwnerPhysicalValueShadowGuaranteeState.KNOWN,
                guaranteedViews = emptyList(),
                selectedViewLineage = emptyList(),
                initializerNullState = DotNetGenericOwnerPhysicalValueShadowNullState.NON_NULL,
                contentsNullState = DotNetGenericOwnerPhysicalValueShadowNullState.NON_NULL,
                unsupportedReason = null,
            ),
            produced,
            storage,
        )

        val localVariable = buildVariable(
            function,
            0,
            0,
            IrDeclarationOrigin.DEFINED,
            Name.identifier("local"),
            owner.typeParameters.single().defaultType,
        ).symbol as IrVariableSymbolImpl
        val foreignVariable = buildVariable(
            function,
            0,
            0,
            IrDeclarationOrigin.DEFINED,
            Name.identifier("foreign"),
            owner.typeParameters.single().defaultType,
        ).symbol as IrVariableSymbolImpl
        val parameterSource = buildVariable(
            function,
            0,
            0,
            IrDeclarationOrigin.DEFINED,
            Name.identifier("parameterSource"),
            owner.typeParameters.single().defaultType,
        ).symbol as IrVariableSymbolImpl
        val parameterVariable = buildVariable(
            function,
            0,
            0,
            IrDeclarationOrigin.DEFINED,
            Name.identifier("parameter"),
            owner.typeParameters.single().defaultType,
        ).also { variable ->
            variable.initializer = IrGetValueImpl(0, 0, parameterSource)
        }.symbol as IrVariableSymbolImpl
        val foreignCarrier = referenceCarrier(source(int32Type()))
        val foreignProduced = directValue(foreignCarrier)
        val foreignStorage = DotNetGenericOwnerPhysicalStorageFact(
            DotNetGenericOwnerPhysicalStorageLayout.Direct(
                DotNetGenericOwnerStorageCarrier.Fixed(foreignCarrier),
            ),
            foreignProduced.provenance,
            DotNetGenericOwnerPhysicalNullState.NON_NULL,
        )
        val authority = assertIs<
                DotNetGenericOwnerPhysicalBindingResult.Bound<
                        DotNetGenericOwnerPhysicalValueLocalPlacementAuthority,
                        >,
                >(
            DotNetGenericOwnerPhysicalValueLocalPlacementAuthority.bind(
                listOf(
                    record(localVariable, localProduced, localStorage),
                    record(parameterVariable, parameterProduced, parameterStorage),
                    record(foreignVariable, foreignProduced, foreignStorage),
                ),
            ),
        ).value

        assertNotNull(authority.retainedProducedCarrierOrNull(function.symbol, localVariable))
        assertNotNull(authority.retainedProducedCarrierOrNull(function.symbol, parameterVariable))
        assertNull(authority.retainedProducedCarrierOrNull(function.symbol, foreignVariable))
        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            DotNetGenericOwnerPhysicalValueLocalPlacementAuthority.bind(
                listOf(
                    record(localVariable, localProduced, localStorage),
                    record(localVariable, localProduced, localStorage),
                ),
            ),
        )
    }

    @Test
    fun `alternative writes lose construction-specific provenance`() {
        val sourceInt = source(int32Type())
        val sourceString = source(stringType())
        val initial = directValue(referenceCarrier(sourceInt)).placeInStorageOrNull(
            DotNetGenericOwnerStorageCarrier.Fixed(objectCarrier()),
            ::canStoreIdentityPreserving,
        )!!

        val joinedWrites = initial.joinAlternativeWrite(
            directValue(referenceCarrier(sourceString)),
            ::canStoreIdentityPreserving,
        )!!

        assertEquals(
            emptySet(),
            assertIs<DotNetGenericOwnerGuaranteedViews.Known>(
                joinedWrites.contentsProvenance.guaranteedViews,
            ).views,
        )
        assertEquals(emptyMap(), joinedWrites.contentsProvenance.selectedViewLineage)
    }

    @Test
    fun `sequential overwrite kills prior construction provenance`() {
        val sourceInt = source(int32Type())
        val sourceString = source(stringType())
        val initial = directValue(referenceCarrier(sourceInt)).placeInStorageOrNull(
            DotNetGenericOwnerStorageCarrier.Fixed(objectCarrier()),
            ::canStoreIdentityPreserving,
        )!!

        val overwritten = initial.overwriteOrNull(
            directValue(referenceCarrier(sourceString)),
            ::canStoreIdentityPreserving,
        )!!

        assertEquals(setOf(view(sourceString)), knownViewsOf(overwritten.contentsProvenance))
    }

    @Test
    fun `a constructed storage carrier guarantees its own view on read`() {
        val sourceInt = source(int32Type())
        val stored = DotNetGenericOwnerPhysicalStorageFact(
            DotNetGenericOwnerPhysicalStorageLayout.Direct(
                DotNetGenericOwnerStorageCarrier.Fixed(referenceCarrier(sourceInt)),
            ),
            unknownProvenance(),
            DotNetGenericOwnerPhysicalNullState.NON_NULL,
        )

        val read = stored.read().value

        assertEquals(setOf(view(sourceInt)), knownViewsOf(read.provenance))
        assertEquals(
            setOf(DotNetGenericOwnerPhysicalViewEvidence.STORAGE_READ),
            assertIs<DotNetGenericOwnerGuaranteedViews.Known>(read.provenance.guaranteedViews)
                .evidenceByView[view(sourceInt)],
        )
    }

    @Test
    fun `null arm preserves a view guaranteed for every non-null reference value`() {
        val sourceInt = source(int32Type())
        val exact = directValue(referenceCarrier(sourceInt))

        val joined = exact.join(nullValue(), ::truthfulCommonCarrier)

        assertEquals(DotNetGenericOwnerProducedValueLayout.Direct(referenceCarrier(sourceInt)), joined.layout)
        assertEquals(exact.provenance, joined.provenance)
        assertEquals(DotNetGenericOwnerPhysicalNullState.MAYBE_NULL, joined.nullState)
    }

    @Test
    fun `null is placed directly into a compatible exact reference slot`() {
        val sourceInt = source(int32Type())

        val stored = nullValue().placeInStorageOrNull(
            DotNetGenericOwnerStorageCarrier.Fixed(referenceCarrier(sourceInt)),
            ::canStoreIdentityPreserving,
        )!!

        assertEquals(DotNetGenericOwnerProducedValueLayout.Null, stored.read().value.layout)
        assertEquals(DotNetGenericOwnerPhysicalNullState.NULL, stored.contentsNullState)
    }

    @Test
    fun `direct scalar plus null requires explicit nullable materialization`() {
        val joined = directValue(int32Carrier()).join(nullValue(), ::truthfulCommonCarrier)
        val nullableJoined = directValue(nullableIntCarrier()).join(
            nullValue(),
            ::truthfulCommonCarrier,
        )

        assertIs<DotNetGenericOwnerProducedValueLayout.Unknown>(joined.layout)
        assertIs<DotNetGenericOwnerProducedValueLayout.Unknown>(nullableJoined.layout)
        assertEquals(DotNetGenericOwnerPhysicalNullState.MAYBE_NULL, joined.nullState)
        assertNull(
            nullValue().placeInStorageOrNull(
                DotNetGenericOwnerStorageCarrier.Fixed(nullableIntCarrier()),
                ::canStoreIdentityPreserving,
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            DotNetGenericOwnerProducedValueFact(
                DotNetGenericOwnerProducedValueLayout.Direct(int32Carrier()),
                unknownProvenance(),
                DotNetGenericOwnerPhysicalNullState.MAYBE_NULL,
            )
        }
    }

    @Test
    fun `an explicitly materialized inline null retains its physical carrier`() {
        val materializedNull = DotNetGenericOwnerProducedValueFact(
            DotNetGenericOwnerProducedValueLayout.Direct(nullableIntCarrier()),
            DotNetGenericOwnerPhysicalValueProvenance.noNonNullViews(),
            DotNetGenericOwnerPhysicalNullState.NULL,
        )

        val stored = materializedNull.placeInStorageOrNull(
            DotNetGenericOwnerStorageCarrier.Fixed(nullableIntCarrier()),
            ::canStoreIdentityPreserving,
        )!!
        val read = stored.read().value

        assertEquals(DotNetGenericOwnerProducedValueLayout.Direct(nullableIntCarrier()), read.layout)
        assertEquals(DotNetGenericOwnerPhysicalNullState.NULL, read.nullState)
        assertEquals(
            DotNetGenericOwnerPhysicalValueProvenance.noNonNullViews(),
            read.provenance,
        )
    }

    @Test
    fun `substitution-dependent carrier preserves typed maybe-null flow but rejects carrierless null`() {
        val parameterCarrier = boundCarrier(
            boundTypeParameter(declarationIndex, lookupOwner, 1),
        )
        val maybeNull = DotNetGenericOwnerProducedValueFact(
            DotNetGenericOwnerProducedValueLayout.Direct(parameterCarrier),
            unknownProvenance(),
            DotNetGenericOwnerPhysicalNullState.MAYBE_NULL,
        )

        val stored = maybeNull.placeInStorageOrNull(
            DotNetGenericOwnerStorageCarrier.Fixed(parameterCarrier),
            ::canStoreIdentityPreserving,
        )

        assertEquals(maybeNull, stored?.read()?.value)
        assertNull(
            nullValue().placeInStorageOrNull(
                DotNetGenericOwnerStorageCarrier.Fixed(parameterCarrier),
                ::canStoreIdentityPreserving,
            ),
        )
    }

    @Test
    fun `split nullable remains a result layout and null requires explicit materialization`() {
        val payload = boundCarrier(boundTypeParameter(declarationIndex, lookupOwner, 1))
        val split = DotNetGenericOwnerProducedValueFact(
            layout = DotNetGenericOwnerProducedValueLayout.SplitNullable(payload),
            provenance = unknownProvenance(),
            nullState = DotNetGenericOwnerPhysicalNullState.MAYBE_NULL,
        )

        assertNull(
            split.placeInStorageOrNull(
                DotNetGenericOwnerStorageCarrier.Fixed(objectCarrier()),
                ::canStoreIdentityPreserving,
            ),
        )
        assertIs<DotNetGenericOwnerProducedValueLayout.Unknown>(
            split.join(nullValue(), ::truthfulCommonCarrier).layout,
        )
    }

    @Test
    fun `split nullable placement preserves its payload and null flag layout`() {
        val payload = boundCarrier(boundTypeParameter(declarationIndex, lookupOwner, 1))
        val split = DotNetGenericOwnerProducedValueFact(
            layout = DotNetGenericOwnerProducedValueLayout.SplitNullable(payload),
            provenance = unknownProvenance(),
            nullState = DotNetGenericOwnerPhysicalNullState.MAYBE_NULL,
        )
        val storageLayout = DotNetGenericOwnerPhysicalStorageLayout.SplitNullable(
            DotNetGenericOwnerStorageCarrier.Fixed(payload),
        )

        val stored = split.placeInStorageOrNull(
            storageLayout,
            ::canStoreIdentityPreserving,
        )!!

        assertEquals(split, stored.read().value)
        assertNull(
            directValue(payload).placeInStorageOrNull(
                storageLayout,
                ::canStoreIdentityPreserving,
            ),
        )
        assertNull(
            nullValue().placeInStorageOrNull(
                storageLayout,
                ::canStoreIdentityPreserving,
            ),
        )
        assertNull(
            split.copy(
                layout = DotNetGenericOwnerProducedValueLayout.SplitNullable(objectCarrier()),
            ).placeInStorageOrNull(
                storageLayout,
                ::canStoreIdentityPreserving,
            ),
        )
        assertNull(stored.joinAlternativeWrite(split, ::canStoreIdentityPreserving))
        assertNull(stored.overwriteOrNull(split, ::canStoreIdentityPreserving))

        val construction = source(int32Type())
        val constructedPayload = boundCarrier(declarationIndex, construction)
        val constructedSplit = split.copy(
            layout = DotNetGenericOwnerProducedValueLayout.SplitNullable(constructedPayload),
            provenance = unknownProvenance(),
        )
        val constructedRead = constructedSplit.placeInStorageOrNull(
            DotNetGenericOwnerPhysicalStorageLayout.SplitNullable(
                DotNetGenericOwnerStorageCarrier.Fixed(constructedPayload),
            ),
            ::canStoreIdentityPreserving,
        )!!.read().value
        val constructedViews = assertIs<DotNetGenericOwnerGuaranteedViews.Known>(
            constructedRead.provenance.guaranteedViews,
        )
        assertEquals(
            setOf(DotNetGenericOwnerPhysicalViewEvidence.STORAGE_READ),
            constructedViews.evidenceByView[view(construction)],
        )
    }

    @Test
    fun `split local payload must match the enclosing owner parameter`() {
        val key = boundCarrier(boundTypeParameter(declarationIndex, lookupOwner, 0))
        val value = boundCarrier(boundTypeParameter(declarationIndex, lookupOwner, 1))
        val produced = DotNetGenericOwnerProducedValueFact(
            layout = DotNetGenericOwnerProducedValueLayout.SplitNullable(value),
            provenance = unknownProvenance(),
            nullState = DotNetGenericOwnerPhysicalNullState.MAYBE_NULL,
        )
        val carriers = listOf(key, value)

        assertNull(
            splitNullableOwnerParameterStorageLayoutOrNull(
                produced,
                localOwnerParameterIndex = 1,
                enclosingOwnerParameterIndex = 0,
                ownerParameterCarriers = carriers,
            ),
            "a logical B? local must not retain !B+bool for an enclosing !A+bool MethodDef",
        )
        assertEquals(
            DotNetGenericOwnerPhysicalStorageLayout.SplitNullable(
                DotNetGenericOwnerStorageCarrier.Fixed(value),
            ),
            splitNullableOwnerParameterStorageLayoutOrNull(
                produced,
                localOwnerParameterIndex = 1,
                enclosingOwnerParameterIndex = 1,
                ownerParameterCarriers = carriers,
            ),
        )
    }

    @Test
    fun `split local use summary admits only unprotected direct returns`() {
        fun summary(
            reads: Int,
            ownReturns: Int,
            otherReturns: Int = 0,
            protectedReturns: Int = 0,
        ) = DotNetGenericOwnerPhysicalSplitLocalUseSummary(
            readCount = reads,
            directFunctionReturnCount = ownReturns,
            directOtherReturnCount = otherReturns,
            protectedRegionReturnCount = protectedReturns,
            returnValueKinds = setOf("IrGetValueImpl"),
        )

        listOf(
            summary(reads = 1, ownReturns = 1),
            summary(reads = 2, ownReturns = 2),
            summary(reads = 7, ownReturns = 7),
        ).forEach { admitted ->
            assertTrue(admitted.hasOnlyUnprotectedDirectFunctionReturnUses, admitted.toString())
        }
        listOf(
            summary(reads = 0, ownReturns = 0),
            summary(reads = 1, ownReturns = 0),
            summary(reads = 2, ownReturns = 1),
            summary(reads = 1, ownReturns = 2),
            summary(reads = 1, ownReturns = 0, otherReturns = 1),
            summary(reads = 1, ownReturns = 1, protectedReturns = 1),
        ).forEach { hostile ->
            assertFalse(hostile.hasOnlyUnprotectedDirectFunctionReturnUses, hostile.toString())
        }
    }

    @Test
    fun `owner-dependent input and split result parameters remain orthogonally scoped`() {
        val key = boundTypeParameter(declarationIndex, lookupOwner, 0)
        val value = boundTypeParameter(declarationIndex, lookupOwner, 1)
        val result = DotNetGenericOwnerProducedValueLayout.SplitNullable(
            boundCarrier(value),
        )

        assertNotEquals(key, value)
        assertEquals(value, result.payloadCarrier.type)
    }

    @Test
    fun `joins obey the dataflow algebra for representative facts`() {
        val intFact = directValue(referenceCarrier(source(int32Type())))
        val stringFact = directValue(referenceCarrier(source(stringType())))
        val broadFact = directValue(objectCarrier(), unknownProvenance())

        assertEquals(intFact, intFact.join(intFact, ::truthfulCommonCarrier))
        assertEquals(
            intFact.join(stringFact, ::truthfulCommonCarrier),
            stringFact.join(intFact, ::truthfulCommonCarrier),
        )
        assertEquals(
            intFact.join(stringFact, ::truthfulCommonCarrier)
                .join(broadFact, ::truthfulCommonCarrier),
            intFact.join(
                stringFact.join(broadFact, ::truthfulCommonCarrier),
                ::truthfulCommonCarrier,
            ),
        )
    }

    @Test
    fun `different physical result layouts do not fabricate a common representation`() {
        val direct = directValue(int32Carrier())
        val split = direct.copy(
            layout = DotNetGenericOwnerProducedValueLayout.SplitNullable(int32Carrier()),
            nullState = DotNetGenericOwnerPhysicalNullState.MAYBE_NULL,
        )

        assertIs<DotNetGenericOwnerProducedValueLayout.Unknown>(
            direct.join(split, ::truthfulCommonCarrier).layout,
        )
    }

    @Test
    fun `void cannot become a produced or stored value carrier`() {
        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            declarationIndex.carrierOrError(
                DotNetGenericOwnerSymbolicCarrierReference.voidCarrier(),
            ),
        )
    }

    private fun directValue(
        carrier: DotNetGenericOwnerPhysicalCarrier,
        provenance: DotNetGenericOwnerPhysicalValueProvenance = unknownProvenance(),
    ): DotNetGenericOwnerProducedValueFact {
        val construction = carrier.type as? DotNetGenericOwnerSymbolicCarrierReference.Constructed
        val producedProvenance = construction?.let {
            provenance.guarantee(
                DotNetGenericOwnerPhysicalView(it),
                DotNetGenericOwnerPhysicalViewEvidence.IDENTITY_PRESERVING_TRANSFER,
            )
        } ?: provenance
        return DotNetGenericOwnerProducedValueFact(
            layout = DotNetGenericOwnerProducedValueLayout.Direct(carrier),
            provenance = producedProvenance,
            nullState = DotNetGenericOwnerPhysicalNullState.NON_NULL,
        )
    }

    private fun nullValue() = DotNetGenericOwnerProducedValueFact(
        layout = DotNetGenericOwnerProducedValueLayout.Null,
        provenance = DotNetGenericOwnerPhysicalValueProvenance.noNonNullViews(),
        nullState = DotNetGenericOwnerPhysicalNullState.NULL,
    )

    private fun knownViewsOf(
        provenance: DotNetGenericOwnerPhysicalValueProvenance,
    ): Set<DotNetGenericOwnerPhysicalView> =
        assertIs<DotNetGenericOwnerGuaranteedViews.Known>(provenance.guaranteedViews).views

    private fun knownProvenance(
        vararg views: DotNetGenericOwnerPhysicalView,
    ) = DotNetGenericOwnerPhysicalValueProvenance(knownViews(*views))

    private fun knownViews(
        vararg views: DotNetGenericOwnerPhysicalView,
    ) = DotNetGenericOwnerGuaranteedViews.Known(
        views.associateWith {
            setOf(DotNetGenericOwnerPhysicalViewEvidence.IDENTITY_PRESERVING_TRANSFER)
        },
    )

    private fun unknownProvenance() = DotNetGenericOwnerPhysicalValueProvenance(
        DotNetGenericOwnerGuaranteedViews.Unknown,
    )

    private fun source(
        argument: DotNetGenericOwnerSymbolicCarrierReference,
    ) = boundConstruction(declarationIndex, sourceOwner, listOf(argument))

    private fun nullableIntType() = boundConstruction(
        declarationIndex,
        nullableOwner,
        listOf(int32Type()),
    )

    private fun view(
        carrier: DotNetGenericOwnerSymbolicCarrierReference.Constructed,
    ) = DotNetGenericOwnerPhysicalView(carrier)

    private fun localOwnerIdentity(
        symbol: IrClassSymbolImpl,
    ) = DotNetGenericOwnerPhysicalTypeDefIdentity.Local(symbol, view = null)

    private fun localMethodIdentity(
        symbol: IrSimpleFunctionSymbolImpl,
    ) = DotNetGenericOwnerPhysicalMethodDefIdentity.Local(symbol, role = null)

    private data class StateEmissionFixture(
        val authority: DotNetLocalGenericOwnerPhysicalAuthority,
        val owner: IrClass,
        val observedOwner: DotNetGenericOwnerObservedLocalTypeDef,
        val selectedFieldObservation: DotNetGenericOwnerPhysicalFieldDefObservation,
        val ordinaryFieldObservation: DotNetGenericOwnerPhysicalFieldDefObservation,
        val ownerObservation: DotNetGenericOwnerPhysicalTypeDefEmissionObservation,
    ) {
        fun scopeObservation(
            scope: DotNetIlEmissionScope = DotNetIlEmissionScope.USER,
            typeDefs: List<DotNetGenericOwnerPhysicalTypeDefEmissionObservation> =
                listOf(ownerObservation),
        ) = DotNetGenericOwnerCompleteEmissionScopeObservations(
            scope = scope,
            typeDefs = typeDefs,
            methodDefs = emptyList(),
            methodImpls = emptyList(),
        )
    }

    private fun stateEmissionFixture(): StateEmissionFixture {
        val owner = testOwner("StateOwner")
        val packageFragment = IrExternalPackageFragmentImpl(
            IrExternalPackageFragmentSymbolImpl(),
            FqName("sample"),
            IrErrorModuleFragment,
        )
        owner.parent = packageFragment
        packageFragment.declarations += owner
        val ordinaryValue = IrFactoryImpl.buildClass {
            name = Name.identifier("OrdinaryValue")
        }.also { declaration ->
            declaration.parent = packageFragment
            packageFragment.declarations += declaration
        }
        val selectedField = testField(owner, "state")
        val ordinaryField = testField(
            owner,
            "ordinary",
            IrSimpleTypeImpl(
                ordinaryValue.symbol,
                SimpleTypeNullability.NOT_SPECIFIED,
                arguments = emptyList(),
                annotations = emptyList(),
            ),
        )
        val ownerIdentity = DotNetGenericOwnerPhysicalTypeDefIdentity.Local(owner.symbol, view = null)
        val ownerInput = DotNetLocalGenericOwnerPhysicalTypeInput(
            identity = ownerIdentity,
            logicalOwnerName = "StateOwner",
            genericParameters = dotNetInvariantUnconstrainedPhysicalGenericParameters(1),
            role = DotNetLocalGenericOwnerPhysicalTypeRole.GENERIC_CLASS,
        )
        val earlyAuthority = assertIs<
                DotNetGenericOwnerPhysicalBindingResult.Bound<DotNetLocalGenericOwnerPhysicalAuthority>,
                >(DotNetLocalGenericOwnerPhysicalAuthority.bindEarly(listOf(ownerInput))).value
        val authority = assertIs<
                DotNetGenericOwnerPhysicalBindingResult.Bound<DotNetLocalGenericOwnerPhysicalAuthority>,
                >(earlyAuthority.advanceBound(emptyList()) { provisional ->
            val ownerParameter = boundTypeParameter(provisional, ownerIdentity, 0)
            val fieldDefinition = fieldDescription(
                selectedField.symbol,
                ownerIdentity,
                ownerParameter,
            )
            DotNetGenericOwnerPhysicalBindingResult.Bound(
                DotNetLocalGenericOwnerPhysicalBoundInput(
                    methodDefinitions = emptyList(),
                    callableFamilies = emptyList(),
                    directSupertypeEdgeSets = emptyList(),
                    stateFamilies = listOf(
                        DotNetLocalGenericOwnerPhysicalStateFamilyInput(
                            owner = ownerIdentity,
                            boundInstanceFields = listOf(selectedField.symbol, ordinaryField.symbol),
                            states = listOf(
                                DotNetLocalGenericOwnerPhysicalStateInput(
                                    field = selectedField.symbol,
                                    logicalFieldName = "state",
                                    requirement = DotNetGenericOwnerStateCarrierRequirement
                                        .TYPED_STORAGE_PRODUCER_GRAPH_PROVEN,
                                    memorySemantics = DotNetGenericOwnerStateMemorySemantics.PLAIN,
                                    hasImplicitFieldInitializer = false,
                                    fieldDefinition = fieldDefinition,
                                ),
                            ),
                        ),
                    ),
                ),
            )
        }).value
        val observedOwner = DotNetGenericOwnerObservedLocalTypeDef(
            physicalKey = DotNetGenericOwnerObservedPhysicalTypeDefKey(0),
            identity = ownerIdentity,
            genericArity = 1,
            category = DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS,
        )
        val selectedFieldObservation = DotNetGenericOwnerPhysicalFieldDefObservation(
            physicalField = selectedField.symbol,
            physicalFieldIdentity = DotNetGenericOwnerPhysicalFieldDefIdentity.Local(selectedField.symbol),
            physicalName = "state",
            visibility = DotNetIlRawMethodDefVisibility.PRIVATE,
            isStatic = false,
            isInitOnly = false,
            carrier = DotNetGenericOwnerObservedMethodCarrier.OwnerParameter(observedOwner, 0),
        )
        val ordinaryFieldObservation = DotNetGenericOwnerPhysicalFieldDefObservation(
            physicalField = ordinaryField.symbol,
            physicalFieldIdentity = DotNetGenericOwnerPhysicalFieldDefIdentity.Local(ordinaryField.symbol),
            physicalName = "ordinary",
            visibility = DotNetIlRawMethodDefVisibility.PRIVATE,
            isStatic = false,
            isInitOnly = false,
            carrier = DotNetGenericOwnerObservedMethodCarrier.Leaf(
                DotNetGenericOwnerPhysicalTypeKind.OBJECT,
            ),
        )
        val ownerObservation = DotNetGenericOwnerPhysicalTypeDefEmissionObservation(
            physicalType = DotNetGenericOwnerObservedMethodDefOwner.Local(observedOwner),
            physicalKey = observedOwner.physicalKey,
            claimedAliases = observedOwner.aliases,
            physicalTypePath = listOf("sample.StateOwner`1"),
            flags = DotNetIlRawTypeDefFlags(
                visibility = DotNetIlRawTypeDefVisibility.PUBLIC,
                layout = DotNetIlRawTypeDefLayout.AUTO,
                stringFormat = DotNetIlRawTypeDefStringFormat.ANSI,
                isInterface = false,
                isAbstract = false,
                isSealed = false,
                isBeforeFieldInit = false,
            ),
            genericParameters = listOf(
                DotNetGenericOwnerPhysicalTypeDefGenericParameterObservation(
                    DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT,
                    constraints = emptyList(),
                ),
            ),
            directSupertypes = emptyList(),
            fieldDefinitions = listOf(selectedFieldObservation, ordinaryFieldObservation),
        )
        return StateEmissionFixture(
            authority = authority,
            owner = owner,
            observedOwner = observedOwner,
            selectedFieldObservation = selectedFieldObservation,
            ordinaryFieldObservation = ordinaryFieldObservation,
            ownerObservation = ownerObservation,
        )
    }

    private fun DotNetGenericOwnerPhysicalTypeDefEmissionObservation.withSelectedField(
        selected: DotNetGenericOwnerPhysicalFieldDefObservation,
    ): DotNetGenericOwnerPhysicalTypeDefEmissionObservation = copy(
        fieldDefinitions = fieldDefinitions.map { field ->
            if (field.physicalField === selected.physicalField) selected else field
        },
    )

    private fun testOwner(name: String): IrClass = IrFactoryImpl.buildClass {
        this.name = Name.identifier(name)
    }.also { owner ->
        owner.addTypeParameter { this.name = Name.identifier("T") }
    }

    private fun testField(
        owner: IrClass,
        name: String,
        type: IrType = owner.typeParameters.single().defaultType,
    ) = IrFactoryImpl.buildField {
        this.name = Name.identifier(name)
        this.type = type
        origin = IrDeclarationOrigin.DEFINED
        visibility = DescriptorVisibilities.PRIVATE
        isFinal = false
    }.also { field ->
        field.parent = owner
        owner.declarations += field
    }

    private fun typeDescription(
        identity: DotNetGenericOwnerPhysicalTypeDefIdentity,
        arity: Int,
        category: DotNetGenericOwnerPhysicalNamedTypeCategory,
        supportsInlineNull: Boolean = false,
    ) = DotNetGenericOwnerPhysicalTypeDefReference(
        identity,
        dotNetInvariantUnconstrainedPhysicalGenericParameters(arity),
        category,
        supportsInlineNull,
    )

    private fun variantTypeDescription(
        identity: DotNetGenericOwnerPhysicalTypeDefIdentity,
        category: DotNetGenericOwnerPhysicalNamedTypeCategory,
        vararg variances: DotNetGenericOwnerPhysicalTypeParameterVariance,
    ) = DotNetGenericOwnerPhysicalTypeDefReference(
        identity,
        variances.map { variance ->
            DotNetGenericOwnerPhysicalGenericParameterReference(
                variance,
                constraints = emptyList(),
            )
        },
        category,
    )

    private fun methodDescription(
        identity: DotNetGenericOwnerPhysicalMethodDefIdentity,
        declaringType: DotNetGenericOwnerPhysicalTypeDefIdentity,
        arity: Int,
    ) = DotNetGenericOwnerPhysicalMethodDefReference(
        identity = identity,
        declaringType = declaringType,
        visibility = DotNetGenericOwnerPhysicalMemberVisibility.PRIVATE,
        dispatch = DotNetGenericOwnerPhysicalMemberDispatch.FINAL,
        signature = DotNetGenericOwnerPhysicalMethodSignatureReference(
            isInstance = true,
            genericArity = arity,
            resultLayout = DotNetGenericOwnerPhysicalCallableResultLayoutReference.Void,
            parameterSlots = emptyList(),
        ),
        genericParameters = List(arity) {
            DotNetGenericOwnerPhysicalGenericParameterReference(
                DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT,
                constraints = emptyList(),
            )
        },
    )

    private fun fieldDescription(
        field: org.jetbrains.kotlin.ir.symbols.IrFieldSymbol,
        declaringType: DotNetGenericOwnerPhysicalTypeDefIdentity,
        carrier: DotNetGenericOwnerSymbolicCarrierReference,
    ) = DotNetGenericOwnerPhysicalFieldDefReference(
        identity = DotNetGenericOwnerPhysicalFieldDefIdentity.Local(field),
        declaringType = declaringType,
        visibility = DotNetGenericOwnerPhysicalMemberVisibility.PRIVATE,
        isStatic = false,
        isInitOnly = false,
        carrier = carrier,
    )

    private fun callableSlot(
        domain: DotNetGenericOwnerPhysicalSlotDomain,
        carrier: DotNetGenericOwnerSymbolicCarrierReference,
    ) = DotNetGenericOwnerPhysicalCallableValueSlotReference(domain, carrier)

    private fun callableMethodDescription(
        identity: DotNetGenericOwnerPhysicalMethodDefIdentity,
        declaringType: DotNetGenericOwnerPhysicalTypeDefIdentity,
        parameterSlots: List<DotNetGenericOwnerPhysicalCallableValueSlotReference>,
        resultLayout: DotNetGenericOwnerPhysicalCallableResultLayoutReference,
    ) = DotNetGenericOwnerPhysicalMethodDefReference(
        identity = identity,
        declaringType = declaringType,
        visibility = DotNetGenericOwnerPhysicalMemberVisibility.PUBLIC,
        dispatch = DotNetGenericOwnerPhysicalMemberDispatch.ABSTRACT,
        signature = DotNetGenericOwnerPhysicalMethodSignatureReference(
            isInstance = true,
            genericArity = 0,
            resultLayout = resultLayout,
            parameterSlots = parameterSlots,
        ),
        genericParameters = emptyList(),
    )

    private class OperationFixtureSchema {
        val owner = DotNetGenericOwnerPhysicalTypeDefIdentity.Local(IrClassSymbolImpl(), view = null)
        val natural = DotNetGenericOwnerPhysicalTypeDefIdentity.Local(
            IrClassSymbolImpl(),
            DotNetGenericInterfaceView.DECLARED,
        )
        val semantic = DotNetGenericOwnerPhysicalTypeDefIdentity.Local(IrClassSymbolImpl(), view = null)
        val naturalMethod = DotNetGenericOwnerPhysicalMethodDefIdentity.Local(
            IrSimpleFunctionSymbolImpl(),
            DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY,
        )
        val semanticMethod = DotNetGenericOwnerPhysicalMethodDefIdentity.Local(
            IrSimpleFunctionSymbolImpl(),
            role = null,
        )
    }

    private data class OperationFixture(
        val declarations: DotNetGenericOwnerPhysicalDeclarationIndex,
        val naturalMethod: DotNetGenericOwnerPhysicalMethodDefIdentity,
        val semanticMethod: DotNetGenericOwnerPhysicalMethodDefIdentity,
        val ownerParameter: DotNetGenericOwnerSymbolicCarrierReference.Parameter,
        val naturalView: DotNetGenericOwnerPhysicalView,
        val semanticView: DotNetGenericOwnerPhysicalView,
        val receiver: DotNetGenericOwnerProducedValueFact,
    )

    private fun operationFixture(
        schema: OperationFixtureSchema,
        includeNaturalEdge: Boolean = true,
        naturalEdgeUsesObject: Boolean = false,
        includeSemanticEdge: Boolean = true,
        includeNaturalMethod: Boolean = true,
        includeSemanticMethod: Boolean = true,
        naturalReturnsVoid: Boolean = false,
    ): OperationFixture {
        val types = listOf(
            typeDescription(schema.owner, 1, DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS),
            typeDescription(schema.natural, 1, DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE),
            typeDescription(schema.semantic, 0, DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE),
        )
        val provisional = boundDeclarationIndex(types, emptyList())
        val ownerParameter = boundTypeParameter(provisional, schema.owner, 0)
        val naturalParameter = boundTypeParameter(provisional, schema.natural, 0)
        val ownerConstruction = boundConstruction(provisional, schema.owner, listOf(ownerParameter))
        val naturalConstruction = boundConstruction(provisional, schema.natural, listOf(ownerParameter))
        val semanticConstruction = boundConstruction(provisional, schema.semantic, emptyList())
        val naturalMethod = callableMethodDescription(
            schema.naturalMethod,
            schema.natural,
            emptyList(),
            if (naturalReturnsVoid) {
                DotNetGenericOwnerPhysicalCallableResultLayoutReference.Void
            } else {
                DotNetGenericOwnerPhysicalCallableResultLayoutReference.Direct(callableSlot(
                    DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_OUTPUT,
                    naturalParameter,
                ))
            },
        )
        val semanticMethod = callableMethodDescription(
            schema.semanticMethod,
            schema.semantic,
            emptyList(),
            DotNetGenericOwnerPhysicalCallableResultLayoutReference.Direct(callableSlot(
                DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_OUTPUT,
                objectType(),
            )),
        )
        val methods = buildList {
            if (includeNaturalMethod) add(naturalMethod)
            if (includeSemanticMethod) add(semanticMethod)
        }
        val ownerEdges = buildList {
            add(baseEdge(objectType()))
            if (includeNaturalEdge) {
                add(interfaceEdge(if (naturalEdgeUsesObject) {
                    boundConstruction(provisional, schema.natural, listOf(objectType()))
                } else {
                    naturalConstruction
                }))
            }
            if (includeSemanticEdge) add(interfaceEdge(semanticConstruction))
        }
        val declarations = boundDeclarationIndex(
            types,
            methods,
            edgeSets = listOf(
                edgeSet(schema.owner, *ownerEdges.toTypedArray()),
                edgeSet(schema.natural),
                edgeSet(schema.semantic),
            ),
        )
        return OperationFixture(
            declarations = declarations,
            naturalMethod = schema.naturalMethod,
            semanticMethod = schema.semanticMethod,
            ownerParameter = ownerParameter,
            naturalView = view(naturalConstruction),
            semanticView = view(semanticConstruction),
            receiver = directValue(boundCarrier(declarations, ownerConstruction)),
        )
    }

    private fun boundOperationRoute(
        fixture: OperationFixture,
        selectedMethod: DotNetGenericOwnerPhysicalMethodDefIdentity,
        requiredView: DotNetGenericOwnerPhysicalView,
    ): DotNetGenericOwnerPhysicalOperationRoute = assertIs<
            DotNetGenericOwnerPhysicalBindingResult.Bound<DotNetGenericOwnerPhysicalOperationRoute>,
            >(
        selectDotNetGenericOwnerPhysicalOperationRoute(
            fixture.declarations,
            selectedMethod,
            DotNetGenericOwnerPhysicalOperationRouteRequest(requiredView),
            fixture.receiver,
            emptyList(),
        ),
    ).value

    private fun interfaceEdge(
        target: DotNetGenericOwnerSymbolicCarrierReference,
    ) = DotNetGenericOwnerPhysicalDirectSupertypeEdgeReference(
        DotNetGenericOwnerDirectSupertypeKind.INTERFACE,
        target,
    )

    private fun baseEdge(
        target: DotNetGenericOwnerSymbolicCarrierReference,
    ) = DotNetGenericOwnerPhysicalDirectSupertypeEdgeReference(
        DotNetGenericOwnerDirectSupertypeKind.BASE_CLASS,
        target,
    )

    private fun edgeSet(
        source: DotNetGenericOwnerPhysicalTypeDefIdentity,
        vararg edges: DotNetGenericOwnerPhysicalDirectSupertypeEdgeReference,
    ) = DotNetGenericOwnerPhysicalDirectSupertypeEdgeSet(source, edges.asIterable())

    private fun boundDeclarationIndex(
        types: Iterable<DotNetGenericOwnerPhysicalTypeDefReference>,
        methods: Iterable<DotNetGenericOwnerPhysicalMethodDefReference>,
        epoch: DotNetGenericOwnerPhysicalAuthorityEpoch =
            DotNetGenericOwnerPhysicalAuthorityEpoch.BOUND_DECLARATION_INDEX,
        edgeSets: Iterable<DotNetGenericOwnerPhysicalDirectSupertypeEdgeSet> = emptyList(),
        fields: Iterable<DotNetGenericOwnerPhysicalFieldDefReference> = emptyList(),
    ): DotNetGenericOwnerPhysicalDeclarationIndex = assertIs<
            DotNetGenericOwnerPhysicalBindingResult.Bound<DotNetGenericOwnerPhysicalDeclarationIndex>,
            >(
        DotNetGenericOwnerPhysicalDeclarationIndex.bind(
            epoch,
            types,
            methods,
            edgeSets,
            fields,
        ),
    ).value

    private fun boundEdgeIndex(
        index: DotNetGenericOwnerPhysicalDeclarationIndex,
        edgeSets: Iterable<DotNetGenericOwnerPhysicalDirectSupertypeEdgeSet>,
    ): DotNetGenericOwnerPhysicalDeclarationIndex = assertIs<
            DotNetGenericOwnerPhysicalBindingResult.Bound<DotNetGenericOwnerPhysicalDeclarationIndex>,
            >(
        index.advance(
            DotNetGenericOwnerPhysicalAuthorityEpoch.SEALED_EMISSION_SIGNATURE_INDEX,
            emptyList(),
            emptyList(),
            edgeSets,
        ),
    ).value

    private fun boundInterfaceClosure(
        index: DotNetGenericOwnerPhysicalDeclarationIndex,
        construction: DotNetGenericOwnerSymbolicCarrierReference.Constructed,
    ): DotNetGenericOwnerPhysicalInterfaceViewClosure = assertIs<
            DotNetGenericOwnerPhysicalBindingResult.Bound<DotNetGenericOwnerPhysicalInterfaceViewClosure>,
            >(index.physicalInterfaceViewClosureOrError(construction)).value

    private fun boundConstruction(
        index: DotNetGenericOwnerPhysicalDeclarationIndex,
        owner: DotNetGenericOwnerPhysicalTypeDefIdentity,
        arguments: List<DotNetGenericOwnerSymbolicCarrierReference>,
    ): DotNetGenericOwnerSymbolicCarrierReference.Constructed = assertIs<
            DotNetGenericOwnerPhysicalBindingResult.Bound<
                    DotNetGenericOwnerSymbolicCarrierReference.Constructed,
                    >,
            >(index.constructTypeOrError(owner, arguments)).value

    private fun boundTypeParameter(
        index: DotNetGenericOwnerPhysicalDeclarationIndex,
        owner: DotNetGenericOwnerPhysicalTypeDefIdentity,
        parameterIndex: Int,
    ): DotNetGenericOwnerSymbolicCarrierReference.Parameter = assertIs<
            DotNetGenericOwnerPhysicalBindingResult.Bound<
                    DotNetGenericOwnerSymbolicCarrierReference.Parameter,
                    >,
            >(index.typeParameterOrError(owner, parameterIndex)).value

    private fun boundMethodParameter(
        index: DotNetGenericOwnerPhysicalDeclarationIndex,
        method: DotNetGenericOwnerPhysicalMethodDefIdentity,
        parameterIndex: Int,
    ): DotNetGenericOwnerSymbolicCarrierReference.Parameter = assertIs<
            DotNetGenericOwnerPhysicalBindingResult.Bound<
                    DotNetGenericOwnerSymbolicCarrierReference.Parameter,
                    >,
            >(index.methodParameterOrError(method, parameterIndex)).value

    private fun int32Type() = DotNetGenericOwnerSymbolicCarrierReference.int32Carrier()

    private fun stringType() = DotNetGenericOwnerSymbolicCarrierReference.stringCarrier()

    private fun objectType() = DotNetGenericOwnerSymbolicCarrierReference.objectCarrier()

    private fun referenceCarrier(
        type: DotNetGenericOwnerSymbolicCarrierReference,
    ) = boundCarrier(type).also {
        assertEquals(DotNetGenericOwnerPhysicalNullEncoding.NULL_REFERENCE, it.nullEncoding)
    }

    private fun int32Carrier() = boundCarrier(int32Type())

    private fun stringCarrier() = referenceCarrier(stringType())

    private fun objectCarrier() = referenceCarrier(
        DotNetGenericOwnerSymbolicCarrierReference.objectCarrier(),
    )

    private fun nullableIntCarrier() = boundCarrier(nullableIntType())

    private fun boundCarrier(
        type: DotNetGenericOwnerSymbolicCarrierReference,
    ): DotNetGenericOwnerPhysicalCarrier = boundCarrier(declarationIndex, type)

    private fun boundCarrier(
        index: DotNetGenericOwnerPhysicalDeclarationIndex,
        type: DotNetGenericOwnerSymbolicCarrierReference,
    ): DotNetGenericOwnerPhysicalCarrier = assertIs<
            DotNetGenericOwnerPhysicalBindingResult.Bound<DotNetGenericOwnerPhysicalCarrier>,
            >(index.carrierOrError(type)).value

    private fun truthfulCommonCarrier(
        left: DotNetGenericOwnerPhysicalCarrier,
        right: DotNetGenericOwnerPhysicalCarrier,
    ): DotNetGenericOwnerPhysicalCarrier? = when {
        left == right -> left
        isIdentityReferenceCarrier(left) && isIdentityReferenceCarrier(right) -> objectCarrier()
        else -> null
    }

    private fun canStoreIdentityPreserving(
        produced: DotNetGenericOwnerPhysicalCarrier,
        storage: DotNetGenericOwnerPhysicalCarrier,
    ): Boolean = produced == storage ||
            storage == objectCarrier() && isIdentityReferenceCarrier(produced)

    private fun isIdentityReferenceCarrier(carrier: DotNetGenericOwnerPhysicalCarrier): Boolean =
        when (val type = carrier.type) {
            is DotNetGenericOwnerSymbolicCarrierReference.Constructed ->
                declarationIndex.typeDescriptionOrNull(type.definition)?.category in setOf(
                    DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS,
                    DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE,
                )
            is DotNetGenericOwnerSymbolicCarrierReference.SzArray -> true
            is DotNetGenericOwnerSymbolicCarrierReference.Leaf ->
                type.kind == DotNetGenericOwnerPhysicalTypeKind.STRING ||
                        type.kind == DotNetGenericOwnerPhysicalTypeKind.OBJECT
            is DotNetGenericOwnerSymbolicCarrierReference.Parameter -> false
        }

    private val producerArtifact = DotNetLibraryArtifact(
        assemblyName = "PhysicalValueModelProducer",
        targetFramework = "net10.0",
    )

    private val sourceOwner = DotNetGenericOwnerPhysicalTypeDefIdentity.KotlinProducer(
        producerArtifact,
        listOf("Rehearsal.Source`1"),
    )
    private val lookupOwner = DotNetGenericOwnerPhysicalTypeDefIdentity.KotlinProducer(
        producerArtifact,
        listOf("Rehearsal.Lookup`2"),
    )
    private val nullableOwner = DotNetGenericOwnerPhysicalTypeDefIdentity.CoreLibrary(
        listOf("System.Nullable`1"),
    )
    private val leftOwner = DotNetGenericOwnerPhysicalTypeDefIdentity.KotlinProducer(
        producerArtifact,
        listOf("Rehearsal.Left"),
    )
    private val rightOwner = DotNetGenericOwnerPhysicalTypeDefIdentity.KotlinProducer(
        producerArtifact,
        listOf("Rehearsal.Right"),
    )

    private val declarationIndex = boundDeclarationIndex(
        listOf(
            typeDescription(sourceOwner, 1, DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE),
            typeDescription(lookupOwner, 2, DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE),
            typeDescription(leftOwner, 0, DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS),
            typeDescription(rightOwner, 0, DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS),
            typeDescription(
                nullableOwner,
                1,
                DotNetGenericOwnerPhysicalNamedTypeCategory.VALUE_TYPE,
                supportsInlineNull = true,
            ),
        ),
        emptyList(),
    )
}
