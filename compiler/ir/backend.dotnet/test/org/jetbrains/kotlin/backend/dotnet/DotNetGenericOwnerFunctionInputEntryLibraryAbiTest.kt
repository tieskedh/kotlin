/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import java.util.Base64
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DotNetGenericOwnerFunctionInputEntryLibraryAbiTest {
    @Test
    fun roundTripsIndependentNaturalAndObjectResultPolicies() {
        assertEquals("72", DotNetLibraryAbiCodec.ABI_VERSION)
        for (returnCarrier in listOf(null, DotNetGenericOwnerFunctionCarrierKind.OBJECT)) {
            val entry = inputEntry().copy(returnCarrier = returnCarrier)
            val declarations = declarations(entry)
            val encoded = DotNetLibraryAbiCodec.encode(declarations)
            val decoded = DotNetLibraryAbiCodec.decode(encoded.toProperties())

            assertEquals(declarations, decoded)
            assertEquals(encoded, DotNetLibraryAbiCodec.encode(decoded))
            assertEquals(declarations.getValue(entry.logicalFunctionKey), decoded.getValue(entry.logicalFunctionKey))
            val fields = decodeText(encoded.getValue(propertyKey(entry.indexKey()))).split('\u0000')
            assertEquals(if (returnCarrier == null) "N" else "O", fields[6])
            assertEquals(entry.sourceSignature, DotNetGenericOwnerPhysicalFamilyCodec.decodePhysicalMethodSignature(fields[7]))
            assertEquals("0,2", fields[5], "object parameter indices must have deterministic order")
        }
    }

    @Test
    fun rejectsTheOldInputOnlyRecordShape() {
        for (hasReturnPolicy in listOf(false, true)) {
            val oldShape = mutateEntry { fields ->
                fields.removeAt(7)
                if (!hasReturnPolicy) fields.removeAt(6)
            }
            assertFailsWith<IllegalArgumentException> {
                DotNetLibraryAbiCodec.decode(oldShape)
            }
        }
    }

    @Test
    fun rejectsMissingMalformedOrHeaderInconsistentSourceSignatures() {
        for (encodedSignature in listOf("", "not-a-signature", "0;0;missing;0")) {
            assertFailsWith<IllegalArgumentException>("accepted source signature '$encodedSignature'") {
                DotNetLibraryAbiCodec.decode(mutateEntry { fields -> fields[7] = encodedSignature })
            }
        }
        val entry = inputEntry()
        for (signature in listOf(
            entry.sourceSignature.copy(isInstance = true),
            entry.sourceSignature.copy(genericArity = 2),
        )) {
            assertFailsWith<IllegalArgumentException> { entry.copy(sourceSignature = signature) }
            assertFailsWith<IllegalArgumentException> {
                DotNetLibraryAbiCodec.decode(mutateEntry { fields ->
                    fields[7] = DotNetGenericOwnerPhysicalFamilyCodec.encodePhysicalMethodSignature(signature)
                })
            }
        }
    }

    @Test
    fun rejectsUnsupportedOrUnknownAlternateResultCarriers() {
        assertFailsWith<IllegalArgumentException> {
            inputEntry().copy(returnCarrier = DotNetGenericOwnerFunctionCarrierKind.SEMANTIC_CAPABILITY)
        }
        for (encodedCarrier in listOf("C", "unknown", "")) {
            assertFailsWith<IllegalArgumentException>("accepted alternate result carrier '$encodedCarrier'") {
                DotNetLibraryAbiCodec.decode(mutateEntry { fields -> fields[6] = encodedCarrier })
            }
        }
    }

    @Test
    fun rejectsMalformedIdentityArityAndParameterPayloads() {
        val mutations: List<(MutableList<String>) -> Unit> = listOf(
            { fields -> fields[1] = "F:demo.other" },
            { fields -> fields[2] = "unknown" },
            { fields -> fields[3] = "" },
            { fields -> fields[4] = "-1" },
            { fields -> fields[4] = "not-an-arity" },
            { fields -> fields[5] = "" },
            { fields -> fields[5] = "-1" },
            { fields -> fields[5] = "not-an-index" },
            { fields -> fields[8] = "0" },
            { fields -> fields[8] = "2" },
            { fields -> fields[8] = "not-a-size" },
            { fields -> fields[9] = "" },
            { fields -> fields.removeAt(fields.lastIndex) },
            { fields -> fields.add("unexpected") },
        )
        mutations.forEachIndexed { index, mutation ->
            assertFailsWith<IllegalArgumentException>("accepted malformed Q payload $index") {
                DotNetLibraryAbiCodec.decode(mutateEntry(mutation))
            }
        }
    }

    @Test
    fun rejectsDuplicateObjectParameterIndices() {
        val failure = assertFailsWith<IllegalArgumentException> {
            DotNetLibraryAbiCodec.decode(mutateEntry { fields -> fields[5] = "0,2,0" })
        }
        assertTrue(failure.message.orEmpty().contains("repeats parameter index 0"))
    }

    @Test
    fun rejectsDuplicateLogicalRecordsWithDifferentBase64Padding() {
        val entry = inputEntry()
        val encoded = DotNetLibraryAbiCodec.encode(declarations(entry))
        val ordinaryKey = propertyKey(entry.indexKey())
        val paddedKey = DotNetLibraryAbiCodec.DECLARATION_PROPERTY_PREFIX +
                Base64.getUrlEncoder().encodeToString(entry.indexKey().toByteArray(Charsets.UTF_8))
        assertNotEquals(ordinaryKey, paddedKey)
        val properties = encoded.toProperties().apply {
            setProperty(paddedKey, encoded.getValue(ordinaryKey))
        }
        val failure = assertFailsWith<IllegalArgumentException> {
            DotNetLibraryAbiCodec.decode(properties)
        }
        assertTrue(failure.message.orEmpty().contains("duplicate CLR declaration identity"))
    }

    private fun inputEntry() = DotNetPhysicalDeclaration.GenericOwnerFunctionInputEntry(
        ownerPath = listOf("demo.ApiKt"),
        logicalFunctionKey = "F:demo.identity",
        methodName = "identity__KotlinClassifierInput__fixture",
        isInstance = false,
        methodGenericParameterCount = 1,
        objectParameterIndices = linkedSetOf(2, 0),
        sourceSignature = DotNetGenericOwnerPhysicalMethodSignatureRecord(
            isInstance = false,
            genericArity = 1,
            resultLayout = DotNetGenericOwnerPhysicalCallableResultLayoutRecord.Direct(
                independentSlot(DotNetGenericOwnerPhysicalTypeExpressionRecord.stringType()),
            ),
            parameterSlots = listOf(
                independentSlot(DotNetGenericOwnerPhysicalTypeExpressionRecord.stringType()),
                independentSlot(DotNetGenericOwnerPhysicalTypeExpressionRecord.int32Type()),
                independentSlot(DotNetGenericOwnerPhysicalTypeExpressionRecord.methodParameter(0)),
            ),
        ),
    )

    private fun independentSlot(type: DotNetGenericOwnerPhysicalTypeExpressionRecord) =
        DotNetGenericOwnerPhysicalValueSlotRecord(DotNetGenericOwnerPhysicalSlotDomain.DECLARATION_INDEPENDENT, type)

    private fun declarations(
        entry: DotNetPhysicalDeclaration.GenericOwnerFunctionInputEntry,
    ): Map<String, DotNetPhysicalDeclaration> = linkedMapOf(
        entry.logicalFunctionKey to DotNetPhysicalDeclaration.Function(
            ownerPath = entry.ownerPath,
            methodName = "identity",
            isInstance = entry.isInstance,
            methodGenericParameterCount = entry.methodGenericParameterCount,
        ),
        entry.indexKey() to entry,
    )

    private fun mutateEntry(mutation: (MutableList<String>) -> Unit): Properties {
        val entry = inputEntry()
        val encoded = DotNetLibraryAbiCodec.encode(declarations(entry)).toMutableMap()
        val propertyKey = propertyKey(entry.indexKey())
        val fields = decodeText(encoded.getValue(propertyKey)).split('\u0000').toMutableList()
        mutation(fields)
        encoded[propertyKey] = encodeText(fields.joinToString("\u0000"))
        return encoded.toProperties()
    }

    private fun propertyKey(logicalKey: String): String =
        DotNetLibraryAbiCodec.DECLARATION_PROPERTY_PREFIX + encodeText(logicalKey)

    private fun encodeText(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun decodeText(value: String): String =
        Base64.getUrlDecoder().decode(value).toString(Charsets.UTF_8)

    private fun Map<String, String>.toProperties(): Properties = Properties().apply { putAll(this@toProperties) }
}
