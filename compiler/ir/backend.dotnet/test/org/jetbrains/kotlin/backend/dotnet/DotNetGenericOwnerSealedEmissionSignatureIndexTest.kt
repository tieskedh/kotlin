/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DotNetGenericOwnerSealedEmissionSignatureIndexTest {
    @Test
    fun doesNotCopyAMissingBoundSentinelIntoTheActualOnlySeal() {
        val sentinel = typeDef(
            typeKey(2),
            aliasKey(2),
            arity = 0,
            category = DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE,
        )
        val expectedWithUnemittedSentinel = expected.copy(
            typeDefs = expected.typeDefs + sentinel,
        )

        val result = bindDotNetGenericOwnerSealedEmissionSignatureIndex(
            expectedWithUnemittedSentinel,
            actual,
        )

        assertIs<DotNetGenericOwnerPhysicalBindingResult.Unavailable>(result)
    }

    @Test
    fun reportsAMissingFinalRowAsUnavailable() {
        val result = bindDotNetGenericOwnerSealedEmissionSignatureIndex(
            expected,
            actual.copy(methodDefs = actual.methodDefs.dropLast(1)),
        )

        assertIs<DotNetGenericOwnerPhysicalBindingResult.Unavailable>(result)
    }

    @Test
    fun rejectsDuplicatePhysicalTypePaths() {
        val firstPath = actual.typeDefs.first().physicalPath
        val hostile = actual.copy(
            typeDefs = listOf(
                actual.typeDefs.first(),
                actual.typeDefs.last().copy(physicalPath = firstPath),
            ),
        )

        val result = bindDotNetGenericOwnerSealedEmissionSignatureIndex(expected, hostile)

        val conflict = assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(result)
        assertTrue(conflict.reason.contains("TypeDef path"))
    }

    @Test
    fun rejectsPhysicalTypePathAndNestingFlagDisagreement() {
        val hostile = actual.copy(
            typeDefs = actual.typeDefs.mapIndexed { index, row ->
                if (index == 0) row.copy(physicalPath = listOf("demo.Outer", "Owner`1")) else row
            },
        )

        val result = bindDotNetGenericOwnerSealedEmissionSignatureIndex(expected, hostile)

        val conflict = assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(result)
        assertTrue(conflict.reason.contains("nesting flags"))
    }

    @Test
    fun rejectsDuplicateClrMethodCoordinatesEvenWithDifferentKeysAndReturnTypes() {
        val original = actual.methodDefs.first()
        val duplicateStructural = original.structural.copy(
            identityKey = methodKey(2),
            header = original.structural.header.copy(result = direct(objectCarrier)),
        )
        val duplicate = original.copy(structural = duplicateStructural)
        val hostileExpected = expected.copy(
            methodDefs = expected.methodDefs + duplicateStructural,
        )
        val hostileActual = actual.copy(methodDefs = actual.methodDefs + duplicate)

        val result = bindDotNetGenericOwnerSealedEmissionSignatureIndex(
            hostileExpected,
            hostileActual,
        )

        val conflict = assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(result)
        assertTrue(conflict.reason.contains("CLR MethodDef coordinate"))
    }

    @Test
    fun rejectsRuntimeSpecialNameWithoutSpecialName() {
        val hostile = actual.copy(
            methodDefs = actual.methodDefs.mapIndexed { index, row ->
                if (index == 0) row.copy(isRuntimeSpecialName = true) else row
            },
        )

        val result = bindDotNetGenericOwnerSealedEmissionSignatureIndex(expected, hostile)

        val conflict = assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(result)
        assertTrue(conflict.reason.contains("rtspecialname without specialname"))
    }

    @Test
    fun rejectsFinalFlagsThatContradictStructuralAuthority() {
        val hostileTypeFlags = actual.copy(
            typeDefs = actual.typeDefs.mapIndexed { index, row ->
                if (index == 0) {
                    row.copy(flags = classFlags.copy(
                        isInterface = true,
                        isAbstract = true,
                        isSealed = false,
                        isBeforeFieldInit = false,
                    ))
                } else {
                    row
                }
            },
        )
        val typeConflict = assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            bindDotNetGenericOwnerSealedEmissionSignatureIndex(expected, hostileTypeFlags),
        )
        assertTrue(typeConflict.reason.contains("interface flag"))

        val hostileMethodFlags = actual.copy(
            methodDefs = actual.methodDefs.mapIndexed { index, row ->
                if (index == 0) row.copy(visibility = DotNetIlRawMethodDefVisibility.PUBLIC) else row
            },
        )
        val methodConflict = assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            bindDotNetGenericOwnerSealedEmissionSignatureIndex(expected, hostileMethodFlags),
        )
        assertTrue(methodConflict.reason.contains("exact flags"))
    }

    @Test
    fun includesTheSplitNullableBoolByReferenceParameterInTheClrCoordinate() {
        val original = actual.methodDefs.first()
        val splitStructural = original.structural.copy(
            identityKey = methodKey(2),
            header = original.structural.header.copy(
                result = DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.SplitNullable(objectCarrier),
            ),
        )
        val split = original.copy(structural = splitStructural)
        val result = bindDotNetGenericOwnerSealedEmissionSignatureIndex(
            expected.copy(methodDefs = expected.methodDefs + splitStructural),
            actual.copy(methodDefs = actual.methodDefs + split),
        )

        assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<*>>(result)
    }

    @Test
    fun retainsExactFinalNamesAndFlagsWithoutReplacingThemFromBound() {
        val result = bindDotNetGenericOwnerSealedEmissionSignatureIndex(expected, actual)
        val bound = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<*>>(result)
        val index = assertIs<DotNetGenericOwnerSealedEmissionSignatureIndex>(bound.value)

        assertEquals(DotNetGenericOwnerPhysicalAuthorityEpoch.SEALED_EMISSION_SIGNATURE_INDEX, index.epoch)
        assertEquals(2, index.typeDefCount)
        assertEquals(2, index.methodDefCount)
        assertEquals(1, index.methodImplCount)

        val sealedClassResult = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<*>>(
            index.typeDef(typeKey(0)),
        )
        val sealedClass = assertIs<DotNetGenericOwnerSealedEmissionTypeDefRow>(sealedClassResult.value)
        assertEquals(listOf("demo.Owner`1"), sealedClass.physicalPath)
        assertEquals(classFlags, sealedClass.flags)

        val sealedMethodResult = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<*>>(
            index.methodDef(methodKey(0)),
        )
        val sealedMethod = assertIs<DotNetGenericOwnerSealedEmissionMethodDefRow>(sealedMethodResult.value)
        assertEquals("Produce", sealedMethod.physicalName)
        assertEquals(DotNetIlRawMethodDefVisibility.PRIVATE, sealedMethod.visibility)
        assertEquals(finalInstanceDispatch, sealedMethod.dispatch)
        assertTrue(sealedMethod.isHideBySig)
        assertEquals(false, sealedMethod.isSpecialName)
        assertEquals(false, sealedMethod.isRuntimeSpecialName)
        assertEquals(
            listOf(methodImpl),
            index.methodImpls(typeKey(0), methodKey(0)),
        )
        assertIs<DotNetGenericOwnerPhysicalBindingResult.Unavailable>(index.typeDef(typeKey(99)))
        assertIs<DotNetGenericOwnerPhysicalBindingResult.Unavailable>(index.methodDef(methodKey(99)))
    }

    private fun typeDef(
        key: DotNetGenericOwnerPhysicalMethodDefEmissionTypeKey,
        alias: DotNetGenericOwnerCompleteEmissionTypeDefAliasKey,
        arity: Int,
        category: DotNetGenericOwnerPhysicalNamedTypeCategory,
        edges: List<DotNetGenericOwnerCompleteEmissionTypeDefEdgeRow> = emptyList(),
    ) = DotNetGenericOwnerCompleteEmissionTypeDefRow(
        identityKey = key,
        aliases = listOf(alias),
        genericArity = arity,
        category = category,
        genericParameters = List(arity) {
            DotNetGenericOwnerCompleteEmissionTypeParameterRow(
                DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT,
                constraints = emptyList(),
            )
        },
        directEdges = edges,
    )

    private fun methodDef(
        key: DotNetGenericOwnerPhysicalMethodDefEmissionMethodKey,
        owner: DotNetGenericOwnerPhysicalMethodDefEmissionTypeKey,
        ownerArity: Int,
        ownerCategory: DotNetGenericOwnerPhysicalNamedTypeCategory,
        visibility: DotNetGenericOwnerPhysicalMethodDefEmissionVisibility,
        dispatch: DotNetGenericOwnerPhysicalMemberDispatch,
        result: DotNetGenericOwnerPhysicalMethodDefEmissionResultShape,
    ) = DotNetGenericOwnerCompleteEmissionMethodDefRow(
        identityKey = key,
        header = DotNetGenericOwnerPhysicalMethodDefEmissionHeaderShape(
            owner = owner,
            ownerGenericArity = ownerArity,
            ownerCategory = ownerCategory,
            visibility = visibility,
            dispatch = dispatch,
            isInstance = true,
            genericArity = 0,
            receiverCarrier = construction(
                owner,
                *(0 until ownerArity).map { index -> ownerParameter(owner, index) }.toTypedArray(),
            ),
            ordinaryParameterCarriers = emptyList(),
            result = result,
        ),
    )

    private fun typeKey(value: Int) = DotNetGenericOwnerPhysicalMethodDefEmissionTypeKey(value)
    private fun methodKey(value: Int) = DotNetGenericOwnerPhysicalMethodDefEmissionMethodKey(value)
    private fun aliasKey(value: Int) = DotNetGenericOwnerCompleteEmissionTypeDefAliasKey(value)

    private fun ownerParameter(
        binder: DotNetGenericOwnerPhysicalMethodDefEmissionTypeKey,
        index: Int,
    ) = DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.OwnerParameter(binder, index)

    private fun construction(
        definition: DotNetGenericOwnerPhysicalMethodDefEmissionTypeKey,
        vararg arguments: DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape,
    ) = DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.Construction(
        definition,
        arguments.toList(),
    )

    private fun direct(carrier: DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape) =
        DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.Direct(carrier)

    private val objectCarrier = DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.Leaf(
        DotNetGenericOwnerPhysicalTypeKind.OBJECT,
    )
    private val classType = typeDef(
        typeKey(0),
        aliasKey(0),
        arity = 1,
        category = DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS,
        edges = listOf(
            DotNetGenericOwnerCompleteEmissionTypeDefEdgeRow(
                DotNetGenericOwnerDirectSupertypeKind.BASE_CLASS,
                objectCarrier,
            ),
            DotNetGenericOwnerCompleteEmissionTypeDefEdgeRow(
                DotNetGenericOwnerDirectSupertypeKind.INTERFACE,
                construction(typeKey(1)),
            ),
        ),
    )
    private val interfaceType = typeDef(
        typeKey(1),
        aliasKey(1),
        arity = 0,
        category = DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE,
    )
    private val classMethod = methodDef(
        methodKey(0),
        typeKey(0),
        ownerArity = 1,
        ownerCategory = DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS,
        visibility = DotNetGenericOwnerPhysicalMethodDefEmissionVisibility.PRIVATE,
        dispatch = DotNetGenericOwnerPhysicalMemberDispatch.FINAL,
        result = direct(ownerParameter(typeKey(0), 0)),
    )
    private val interfaceMethod = methodDef(
        methodKey(1),
        typeKey(1),
        ownerArity = 0,
        ownerCategory = DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE,
        visibility = DotNetGenericOwnerPhysicalMethodDefEmissionVisibility.PUBLIC,
        dispatch = DotNetGenericOwnerPhysicalMemberDispatch.ABSTRACT,
        result = direct(objectCarrier),
    )
    private val methodImpl = DotNetGenericOwnerCompleteEmissionMethodImplRow(
        implementingTypeDefKey = typeKey(0),
        bodyMethodDefKey = methodKey(0),
        declarationOwner = construction(typeKey(1)),
        declarationMethodDefKey = methodKey(1),
    )
    private val expected = DotNetGenericOwnerCompleteEmissionManifest(
        typeDefs = listOf(classType, interfaceType),
        methodDefs = listOf(classMethod, interfaceMethod),
        methodImpls = listOf(methodImpl),
    )

    private val classFlags = DotNetIlRawTypeDefFlags(
        visibility = DotNetIlRawTypeDefVisibility.NOT_PUBLIC,
        layout = DotNetIlRawTypeDefLayout.AUTO,
        stringFormat = DotNetIlRawTypeDefStringFormat.ANSI,
        isInterface = false,
        isAbstract = false,
        isSealed = true,
        isBeforeFieldInit = true,
    )
    private val interfaceFlags = DotNetIlRawTypeDefFlags(
        visibility = DotNetIlRawTypeDefVisibility.PUBLIC,
        layout = DotNetIlRawTypeDefLayout.AUTO,
        stringFormat = DotNetIlRawTypeDefStringFormat.ANSI,
        isInterface = true,
        isAbstract = true,
        isSealed = false,
        isBeforeFieldInit = false,
    )
    private val finalInstanceDispatch = DotNetIlRawMethodDefDispatch(
        isInstance = true,
        isVirtual = false,
        isNewSlot = false,
        isAbstract = false,
        isFinal = false,
    )
    private val abstractInstanceDispatch = DotNetIlRawMethodDefDispatch(
        isInstance = true,
        isVirtual = true,
        isNewSlot = true,
        isAbstract = true,
        isFinal = false,
    )
    private val actual = DotNetGenericOwnerSealedEmissionManifestEvidence.Known(
        typeDefs = listOf(
            DotNetGenericOwnerSealedEmissionTypeDefRow(
                classType,
                physicalPath = listOf("demo.Owner`1"),
                flags = classFlags,
            ),
            DotNetGenericOwnerSealedEmissionTypeDefRow(
                interfaceType,
                physicalPath = listOf("demo.Capability"),
                flags = interfaceFlags,
            ),
        ),
        methodDefs = listOf(
            DotNetGenericOwnerSealedEmissionMethodDefRow(
                classMethod,
                physicalName = "Produce",
                visibility = DotNetIlRawMethodDefVisibility.PRIVATE,
                dispatch = finalInstanceDispatch,
                isHideBySig = true,
                isSpecialName = false,
                isRuntimeSpecialName = false,
            ),
            DotNetGenericOwnerSealedEmissionMethodDefRow(
                interfaceMethod,
                physicalName = "ProduceSemantic",
                visibility = DotNetIlRawMethodDefVisibility.PUBLIC,
                dispatch = abstractInstanceDispatch,
                isHideBySig = true,
                isSpecialName = false,
                isRuntimeSpecialName = false,
            ),
        ),
        methodImpls = listOf(methodImpl),
    )

}
