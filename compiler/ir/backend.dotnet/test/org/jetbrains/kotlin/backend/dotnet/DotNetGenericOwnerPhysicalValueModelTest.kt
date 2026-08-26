/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.ir.builders.declarations.addTypeParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.declarations.impl.IrFactoryImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrClassSymbolImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrSimpleFunctionSymbolImpl
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.name.Name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DotNetGenericOwnerPhysicalValueModelTest {
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
            returnSlot = DotNetGenericOwnerPhysicalValueSlotRecord(
                DotNetGenericOwnerPhysicalSlotDomain.DECLARATION_INDEPENDENT,
                DotNetGenericOwnerPhysicalTypeExpressionRecord.voidType(),
            ),
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
            returnSlot = DotNetGenericOwnerPhysicalValueSlotRecord(
                DotNetGenericOwnerPhysicalSlotDomain.DECLARATION_INDEPENDENT,
                DotNetGenericOwnerPhysicalTypeExpressionRecord.voidType(),
            ),
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
            DotNetLocalGenericOwnerPhysicalCallableFamily.bindDirectProducerOrError(
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
            genericArity = 1,
            role = DotNetLocalGenericOwnerPhysicalTypeRole.NATURAL_INTERFACE,
        )
        val semanticInput = DotNetLocalGenericOwnerPhysicalTypeInput(
            semanticOwnerIdentity,
            "ProducerSemantic",
            genericArity = 0,
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
            DotNetLocalGenericOwnerPhysicalCallableFamily.bindDirectProducerOrError(
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
                DotNetGenericOwnerStorageCarrier.Fixed(int32Carrier()),
                unknownProvenance(),
                DotNetGenericOwnerPhysicalNullState.MAYBE_NULL,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            DotNetGenericOwnerPhysicalStorageFact(
                DotNetGenericOwnerStorageCarrier.Fixed(int32Carrier()),
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
            DotNetGenericOwnerStorageCarrier.Fixed(referenceCarrier(sourceInt)),
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

    private fun typeDescription(
        identity: DotNetGenericOwnerPhysicalTypeDefIdentity,
        arity: Int,
        category: DotNetGenericOwnerPhysicalNamedTypeCategory,
        supportsInlineNull: Boolean = false,
    ) = DotNetGenericOwnerPhysicalTypeDefReference(
        identity,
        arity,
        category,
        supportsInlineNull,
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
            DotNetGenericOwnerPhysicalCallableResultLayoutReference.Direct(callableSlot(
                DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_OUTPUT,
                naturalParameter,
            )),
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
    ): DotNetGenericOwnerPhysicalDeclarationIndex = assertIs<
            DotNetGenericOwnerPhysicalBindingResult.Bound<DotNetGenericOwnerPhysicalDeclarationIndex>,
            >(
        DotNetGenericOwnerPhysicalDeclarationIndex.bind(
            epoch,
            types,
            methods,
            edgeSets,
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
