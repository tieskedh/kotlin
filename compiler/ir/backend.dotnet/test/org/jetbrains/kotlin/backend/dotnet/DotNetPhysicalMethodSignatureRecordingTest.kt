/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.types.Variance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class DotNetPhysicalMethodSignatureRecordingTest {
    @Test
    fun recordsEveryScalarCarrierWithoutLogicalTypeReconstruction() {
        val types = listOf(
            DotNetIlValueType.Boolean,
            DotNetIlValueType.Int32,
            DotNetIlValueType.String,
            DotNetIlValueType.Object,
            DotNetIlValueType.Int8,
            DotNetIlValueType.Int16,
            DotNetIlValueType.Int64,
            DotNetIlValueType.Float32,
            DotNetIlValueType.Float64,
            DotNetIlValueType.Char,
        )
        val record = signature(types, DotNetIlValueType.Int64).record()
        assertEquals(
            listOf(
                DotNetGenericOwnerPhysicalTypeExpressionRecord.booleanType(),
                DotNetGenericOwnerPhysicalTypeExpressionRecord.int32Type(),
                DotNetGenericOwnerPhysicalTypeExpressionRecord.stringType(),
                DotNetGenericOwnerPhysicalTypeExpressionRecord.objectType(),
            ) + listOf("SByte", "Int16", "Int64", "Single", "Double", "Char").map(::coreValueType),
            record.parameterSlots.map { it.type },
        )
        assertEquals(coreValueType("Int64"), record.resultLayout.valueSlotOrNull?.type)
    }

    @Test
    fun recordsNullableScalarsAndTheDistinctArrayCarriersAcrossProfiles() {
        for (profile in DotNetCoreLibraryProfile.entries) {
            val record = signature(
                listOf(
                    DotNetIlValueType.NullableValue(DotNetIlValueType.Int64, profile.reference),
                    DotNetIlValueType.GenericArray(DotNetIlValueType.Int64),
                    DotNetIlValueType.PrimitiveArray(DotNetIlValueType.Int64),
                    DotNetIlValueType.ErasedGenericArray(profile.reference),
                ),
            ).record(profile)
            assertEquals(
                listOf(
                    DotNetGenericOwnerPhysicalTypeExpressionRecord.coreType(
                        listOf("System", "Nullable"),
                        DotNetGenericOwnerPhysicalNamedTypeCategory.VALUE_TYPE,
                        listOf(coreValueType("Int64")),
                    ),
                    DotNetGenericOwnerPhysicalTypeExpressionRecord.szArray(coreValueType("Int64")),
                    externalType(DotNetRuntimeLibrary.ASSEMBLY_NAME, listOf("Kotlin", "LongArray")),
                    DotNetGenericOwnerPhysicalTypeExpressionRecord.coreType(
                        listOf("System", "Array"),
                        DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS,
                    ),
                ),
                record.parameterSlots.map { it.type },
            )
        }
    }

    @Test
    fun recordsObservedProducerCategoriesAndNestedPhysicalNames() {
        val source = DotNetIlClassInfo("sample.Source`1", typeParameterVariances = listOf(Variance.OUT_VARIANCE))
        val outer = DotNetIlClassInfo("sample.Outer")
        val nested = DotNetIlClassInfo("ValueBox", outer)
        val record = signature(
            listOf(
                DotNetIlValueType.GenericInstance(source, listOf(DotNetIlValueType.String)),
                DotNetIlValueType.UserClass(nested),
            ),
        ).record { type ->
            when (type) {
                source -> DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE
                nested -> DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS
                else -> error("unexpected producer type")
            }
        }
        assertEquals(
            listOf(
                DotNetGenericOwnerPhysicalTypeExpressionRecord.producerType(
                    listOf("sample.Source`1"),
                    DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE,
                    listOf(DotNetGenericOwnerPhysicalTypeExpressionRecord.stringType()),
                ),
                DotNetGenericOwnerPhysicalTypeExpressionRecord.producerType(
                    listOf("sample.Outer", "ValueBox"),
                    DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS,
                ),
            ),
            record.parameterSlots.map { it.type },
        )
    }

    @Test
    fun recordsExactExternalScopesAndNestedContainingAssembly() {
        val externalSource = DotNetIlClassInfo(
            "sample.Source`1",
            typeParameterVariances = listOf(Variance.OUT_VARIANCE),
            assemblyName = "Dependency",
        )
        val externalOuter = DotNetIlClassInfo("sample.Outer`1", assemblyName = "Dependency")
        val externalNested = DotNetIlClassInfo(
            "Inner`1",
            externalOuter,
            typeParameterVariances = listOf(Variance.INVARIANT, Variance.INVARIANT),
        )
        val arguments = listOf(DotNetIlValueType.String, DotNetIlValueType.Int64)
        val record = signature(
            listOf(
                DotNetIlValueType.GenericInstance(externalSource, arguments.take(1)),
                DotNetIlValueType.GenericInstance(externalNested, arguments),
                DotNetIlValueType.UserClass(DotNetIlClassInfo("System.Exception", assemblyName = "Other")),
            ),
        ).record()
        assertEquals(
            listOf(
                externalType("Dependency", listOf("sample", "Source"), listOf(DotNetGenericOwnerPhysicalTypeExpressionRecord.stringType())),
                externalType(
                    "Dependency",
                    listOf("sample", "Outer`1/Inner`1"),
                    listOf(DotNetGenericOwnerPhysicalTypeExpressionRecord.stringType(), coreValueType("Int64")),
                ),
                externalType("Other", listOf("System", "Exception")),
            ),
            record.parameterSlots.map { it.type },
        )
    }

    @Test
    fun dropsOnlyTheImplicitReceiverAndPreservesGenericBindersAndSplitLayout() {
        val ownerParameter = DotNetIlValueType.TypeParameter(1, isMethodParameter = false)
        val methodParameter = DotNetIlValueType.TypeParameter(0, isMethodParameter = true)
        val record = DotNetIlMethodSignature(
            returnType = DotNetIlReturnType.Value(ownerParameter),
            parameterTypes = listOf(DotNetIlValueType.Object, DotNetIlValueType.GenericArray(ownerParameter), methodParameter),
            hasThis = true,
            hasSplitNullableResult = true,
            methodGenericParameterCount = 1,
        ).record()
        assertEquals(true, record.isInstance)
        assertEquals(1, record.genericArity)
        assertEquals(2, record.parameterSlots.size)
        assertEquals(DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_INPUT, record.parameterSlots[0].domain)
        assertEquals(
            DotNetGenericOwnerPhysicalTypeExpressionRecord.szArray(DotNetGenericOwnerPhysicalTypeExpressionRecord.ownerParameter(1)),
            record.parameterSlots[0].type,
        )
        assertEquals(DotNetGenericOwnerPhysicalSlotDomain.DECLARATION_INDEPENDENT, record.parameterSlots[1].domain)
        assertEquals(DotNetGenericOwnerPhysicalTypeExpressionRecord.methodParameter(0), record.parameterSlots[1].type)
        val result = assertIs<DotNetGenericOwnerPhysicalCallableResultLayoutRecord.SplitNullable>(record.resultLayout)
        assertEquals(DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_OUTPUT, result.payloadSlot.domain)
        assertEquals(DotNetGenericOwnerPhysicalTypeExpressionRecord.ownerParameter(1), result.payloadSlot.type)
    }

    @Test
    fun recordsLegacyMappedPhysicalNamesWithoutChangingTheirScope() {
        for (profile in DotNetCoreLibraryProfile.entries) {
            val runtimeName = "Kotlin.Quoted'Exception\\Name"
            val record = signature(
                listOf(
                    DotNetIlValueType.MappedClass("${profile.reference}System.Exception"),
                    DotNetIlValueType.MappedClass("[Dependency]${runtimeName.toIlIdentifier()}"),
                ),
            ).record(profile)
            assertEquals(
                DotNetGenericOwnerPhysicalTypeExpressionRecord.coreType(
                    listOf("System", "Exception"),
                    DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS,
                ),
                record.parameterSlots[0].type,
            )
            assertEquals(externalType("Dependency", listOf("Kotlin", "Quoted'Exception\\Name")), record.parameterSlots[1].type)
        }
    }

    @Test
    fun rejectsMalformedEmitterSignaturesInsteadOfInferringMissingFacts() {
        assertFailsWith<IllegalArgumentException> { signature(emptyList()).copy(hasThis = true).record() }
        assertFailsWith<IllegalArgumentException> { signature(emptyList()).copy(hasSplitNullableResult = true).record() }
        assertFailsWith<IllegalArgumentException> {
            signature(listOf(DotNetIlValueType.TypeParameter(0, isMethodParameter = true))).record()
        }
        assertFailsWith<IllegalArgumentException> {
            signature(listOf(DotNetIlValueType.UserClass(
                DotNetIlClassInfo("sample.Source`1", typeParameterVariances = listOf(Variance.OUT_VARIANCE)),
            ))).record()
        }
        assertFailsWith<IllegalStateException> {
            signature(listOf(DotNetIlValueType.UserClass(DotNetIlClassInfo("sample.Unobserved")))).record()
        }
        assertFailsWith<IllegalStateException> {
            signature(listOf(DotNetIlValueType.ByReference(DotNetIlValueType.Int32))).record()
        }
    }

    private fun signature(parameters: List<DotNetIlValueType>, result: DotNetIlValueType? = null) = DotNetIlMethodSignature(
        returnType = result?.let(DotNetIlReturnType::Value) ?: DotNetIlReturnType.Void,
        parameterTypes = parameters,
        methodGenericParameterCount = 0,
    )

    private fun DotNetIlMethodSignature.record(
        profile: DotNetCoreLibraryProfile = DotNetCoreLibraryProfile.NET48,
        category: (DotNetIlClassInfo) -> DotNetGenericOwnerPhysicalNamedTypeCategory = { error("unobserved producer type") },
    ): DotNetGenericOwnerPhysicalMethodSignatureRecord = toGenericOwnerPhysicalMethodSignatureRecord(profile, category).also { record ->
        assertEquals(
            record,
            DotNetGenericOwnerPhysicalFamilyCodec.decodePhysicalMethodSignature(
                DotNetGenericOwnerPhysicalFamilyCodec.encodePhysicalMethodSignature(record),
            ),
        )
    }

    private fun coreValueType(name: String) = DotNetGenericOwnerPhysicalTypeExpressionRecord.coreType(
        listOf("System", name),
        DotNetGenericOwnerPhysicalNamedTypeCategory.VALUE_TYPE,
    )

    private fun externalType(
        assembly: String,
        path: List<String>,
        arguments: List<DotNetGenericOwnerPhysicalTypeExpressionRecord> = emptyList(),
    ) = DotNetGenericOwnerPhysicalTypeExpressionRecord(
        kind = DotNetGenericOwnerPhysicalTypeKind.NAMED,
        scope = DotNetGenericOwnerPhysicalTypeScope.ASSEMBLY,
        assemblyName = assembly,
        typePath = path,
        genericArity = arguments.size,
        namedTypeCategory = DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS,
        arguments = arguments,
    )
}
