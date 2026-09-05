/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import java.io.File
import java.util.Properties
import org.jetbrains.kotlin.types.Variance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DotNetGenericOwnerConstructorMethodDefLibraryAbiTest {
    @Test
    fun publisherSealsOnlyPlannerCandidatesWhichFinalEmissionActuallyErased() {
        val owner = DotNetIlClassInfo(
            "demo.Box`1",
            typeParameterVariances = listOf(Variance.INVARIANT),
        )
        val closedProducer = DotNetIlValueType.GenericInstance(
            DotNetIlClassInfo(
                "demo.Producer`1",
                typeParameterVariances = listOf(Variance.OUT_VARIANCE),
            ),
            listOf(DotNetIlValueType.String),
        )
        val finalParameterTypes = listOf(
            DotNetIlValueType.Object,
            closedProducer,
            DotNetIlValueType.Object,
        )
        val observations = listOf(typeDefObservation(
            owner,
            DotNetIlRawTypeDefVisibility.PUBLIC,
        ))

        assertEquals(
            setOf(0),
            genericOwnerConstructorSemanticObjectParameterIndicesForPublicationOrNull(
                candidateIndices = setOf(0, 1),
                finalParameterTypes = finalParameterTypes,
                ownerPath = owner.physicalPathComponents(),
                finalTypeDefObservations = observations,
            ),
        )
        assertNull(
            genericOwnerConstructorSemanticObjectParameterIndicesForPublicationOrNull(
                candidateIndices = setOf(1),
                finalParameterTypes = finalParameterTypes,
                ownerPath = owner.physicalPathComponents(),
                finalTypeDefObservations = observations,
            ),
            "a retained closed CLR construction must not publish an object-domain L seal",
        )
        assertNull(
            genericOwnerConstructorSemanticObjectParameterIndicesForPublicationOrNull(
                candidateIndices = emptySet(),
                finalParameterTypes = finalParameterTypes,
                ownerPath = owner.physicalPathComponents(),
                finalTypeDefObservations = observations,
            ),
            "an unrelated ordinary object parameter is not a semantic-constructor hazard",
        )
        assertFailsWith<IllegalArgumentException> {
            genericOwnerConstructorSemanticObjectParameterIndicesForPublicationOrNull(
                candidateIndices = setOf(finalParameterTypes.size),
                finalParameterTypes = finalParameterTypes,
                ownerPath = owner.physicalPathComponents(),
                finalTypeDefObservations = observations,
            )
        }
    }

    @Test
    fun publisherRequiresTheWholeFinalTypeDefChainToBeExternallyVisible() {
        val outer = DotNetIlClassInfo("demo.Outer")
        val nestedOwner = DotNetIlClassInfo(
            "Box`1",
            outer,
            listOf(Variance.INVARIANT),
        )
        val publicOuter = typeDefObservation(outer, DotNetIlRawTypeDefVisibility.PUBLIC)
        val hiddenOuter = typeDefObservation(outer, DotNetIlRawTypeDefVisibility.NOT_PUBLIC)
        val publicNested = typeDefObservation(
            nestedOwner,
            DotNetIlRawTypeDefVisibility.NESTED_PUBLIC,
        )
        val hiddenNested = typeDefObservation(
            nestedOwner,
            DotNetIlRawTypeDefVisibility.NESTED_PRIVATE,
        )
        fun publish(
            observations: List<DotNetIlRawTypeDefEmissionObservation>,
        ): Set<Int>? = genericOwnerConstructorSemanticObjectParameterIndicesForPublicationOrNull(
            candidateIndices = setOf(0),
            finalParameterTypes = listOf(DotNetIlValueType.Object),
            ownerPath = nestedOwner.physicalPathComponents(),
            finalTypeDefObservations = observations,
        )

        assertEquals(setOf(0), publish(listOf(publicOuter, publicNested)))
        assertNull(publish(listOf(hiddenOuter, publicNested)))
        assertNull(publish(listOf(publicOuter, hiddenNested)))
        assertNull(publish(listOf(publicNested)), "a missing enclosing TypeDef must fail closed")
        assertNull(
            publish(listOf(publicOuter, publicOuter, publicNested)),
            "ambiguous final TypeDef observations must fail closed",
        )
    }

    @Test
    fun exactObjectConstructorRoundTripsAndBindsByLogicalIdentity() {
        val declarations = constructorDeclarations(listOf(objectConstructor("F:demo/Box.<init>|object")))
        val decoded = DotNetLibraryAbiCodec.decode(
            DotNetLibraryAbiCodec.encode(declarations).toProperties(),
        )

        assertEquals(declarations, decoded)
        val library = DotNetExternalLibrary(
            DotNetLibraryArtifact("Demo", "net10.0"),
            File("Demo.dll"),
            decoded,
            emptySet(),
        )
        val index = DotNetExternalDeclarationIndex(listOf(library))
        val constructor = declarations.values
            .filterIsInstance<DotNetPhysicalDeclaration.GenericOwnerConstructorMethodDef>()
            .single()
        assertTrue(
            index.genericOwnerConstructorMethodDefsByLogicalKey
                .getValue(constructor.logicalConstructorKey)
                .library === library,
        )
    }

    @Test
    fun distinctFullSignaturesRemainDistinctOverloads() {
        val objectDeclaration = objectConstructor("F:demo/Box.<init>|object")
        val stringConstructor = objectConstructor("F:demo/Box.<init>|string").copy(
            physicalMethod = objectDeclaration.physicalMethod.copy(
                signature = objectDeclaration.physicalMethod.signature.copy(
                    parameterSlots = listOf(valueSlot(
                        DotNetGenericOwnerPhysicalTypeExpressionRecord.stringType(),
                    )),
                ),
            ),
        )
        val declarations = constructorDeclarations(listOf(objectDeclaration, stringConstructor))

        val decoded = DotNetLibraryAbiCodec.decode(
            DotNetLibraryAbiCodec.encode(declarations).toProperties(),
        )
        assertEquals(2, decoded.values
            .filterIsInstance<DotNetPhysicalDeclaration.GenericOwnerConstructorMethodDef>()
            .size)
    }

    @Test
    fun duplicatePhysicalSignatureAndPartialCarrierBothFailClosed() {
        val first = objectConstructor("F:demo/Box.<init>|first")
        val second = objectConstructor("F:demo/Box.<init>|second")
        val duplicate = constructorDeclarations(listOf(first, second))
        assertFailsWith<IllegalArgumentException> {
            DotNetLibraryAbiCodec.encode(duplicate)
        }

        val withPartialCarrier = constructorDeclarations(listOf(first)) + mapOf(
            "S:${first.logicalConstructorKey}" to DotNetPhysicalDeclaration.GenericOwnerFunctionCarrier(
                ownerPath = first.ownerPath,
                logicalFunctionKey = first.logicalConstructorKey,
                returnCarrier = null,
                parameterCarriers = mapOf(0 to DotNetGenericOwnerFunctionCarrierKind.OBJECT),
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            DotNetLibraryAbiCodec.encode(withPartialCarrier)
        }
    }

    private fun objectConstructor(
        logicalConstructorKey: String,
    ): DotNetPhysicalDeclaration.GenericOwnerConstructorMethodDef {
        val ownerPath = listOf("demo.Box`1")
        return DotNetPhysicalDeclaration.GenericOwnerConstructorMethodDef(
            logicalOwnerKey = OWNER_KEY,
            logicalConstructorKey = logicalConstructorKey,
            ownerPath = ownerPath,
            physicalMethod = DotNetGenericOwnerPhysicalMethodIdentityRecord(
                physicalOwnerPath = ownerPath,
                physicalMethodName = ".ctor",
                signature = DotNetGenericOwnerPhysicalMethodSignatureRecord(
                    isInstance = true,
                    genericArity = 0,
                    resultLayout = DotNetGenericOwnerPhysicalCallableResultLayoutRecord.Void,
                    parameterSlots = listOf(valueSlot(
                        DotNetGenericOwnerPhysicalTypeExpressionRecord.objectType(),
                    )),
                ),
            ),
            visibility = DotNetGenericOwnerPhysicalConstructorVisibility.PUBLIC,
        )
    }

    private fun valueSlot(
        type: DotNetGenericOwnerPhysicalTypeExpressionRecord,
    ): DotNetGenericOwnerPhysicalValueSlotRecord = DotNetGenericOwnerPhysicalValueSlotRecord(
        DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_INPUT,
        type,
    )

    private fun typeDefObservation(
        classInfo: DotNetIlClassInfo,
        visibility: DotNetIlRawTypeDefVisibility,
    ): DotNetIlRawTypeDefEmissionObservation = DotNetIlRawTypeDefEmissionObservation(
        physicalType = classInfo,
        physicalTypePath = classInfo.physicalPathComponents(),
        flags = DotNetIlRawTypeDefFlags(
            visibility = visibility,
            layout = DotNetIlRawTypeDefLayout.AUTO,
            stringFormat = DotNetIlRawTypeDefStringFormat.ANSI,
            isInterface = false,
            isAbstract = false,
            isSealed = true,
            isBeforeFieldInit = true,
        ),
        category = DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS,
        genericParameters = List(classInfo.typeParameterCount) {
            DotNetIlRawTypeDefGenericParameterObservation(
                variance = DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT,
                constraints = emptyList(),
            )
        },
        directSupertypes = listOf(DotNetIlRawTypeDefEdgeObservation(
            kind = DotNetGenericOwnerDirectSupertypeKind.BASE_CLASS,
            target = DotNetIlRawTypeDefEdgeTarget.CoreObject,
        )),
    )

    private fun constructorDeclarations(
        constructors: List<DotNetPhysicalDeclaration.GenericOwnerConstructorMethodDef>,
    ): Map<String, DotNetPhysicalDeclaration> = buildMap {
        put(
            OWNER_KEY,
            DotNetPhysicalDeclaration.Class(
                ownerPath = listOf("demo.Box`1"),
                physicalTypeParameterCount = 1,
                physicalTypeParameterVariances = listOf(
                    DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT,
                ),
            ),
        )
        constructors.forEach { constructor ->
            put(
                constructor.logicalConstructorKey,
                DotNetPhysicalDeclaration.Function(
                    ownerPath = constructor.ownerPath,
                    methodName = ".ctor",
                    isInstance = true,
                    methodGenericParameterCount = 0,
                ),
            )
            put(constructor.indexKey(), constructor)
        }
    }

    private fun Map<String, String>.toProperties(): Properties = Properties().also { properties ->
        forEach(properties::setProperty)
    }

    private companion object {
        const val OWNER_KEY = "C:demo/Box"
    }
}
