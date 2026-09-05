/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import java.io.File
import java.util.Base64
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DotNetProducerGenericDelegatePhysicalAuthorityTest {
    private val artifact = DotNetLibraryArtifact("sample.Delegates", "netstandard2.0")

    @Test
    fun bindsExactProducerRecordedDelegateVariance() {
        val covariantKey = "C:sample/Producer"
        val contravariantKey = "C:sample/Consumer"
        val authority = bindAuthority(mapOf(
            covariantKey to delegateRecord(
                listOf("sample.Producer`1"),
                DotNetGenericOwnerPhysicalTypeParameterVariance.COVARIANT,
            ),
            contravariantKey to delegateRecord(
                listOf("sample.Consumer`1"),
                DotNetGenericOwnerPhysicalTypeParameterVariance.CONTRAVARIANT,
            ),
        ))
        val declarations = authority.declarations
        val producer = authority.typeDefinitionsByLogicalClassifierKey.getValue(covariantKey)
        val consumer = authority.typeDefinitionsByLogicalClassifierKey.getValue(contravariantKey)

        assertEquals(
            DotNetGenericOwnerPhysicalAuthorityEpoch.BOUND_DECLARATION_INDEX,
            declarations.epoch,
        )
        for (definition in listOf(producer, consumer)) {
            val description = checkNotNull(declarations.typeDescriptionOrNull(definition))
            assertEquals(DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS, description.category)
            assertTrue(description.supportsClrDelegateVariance)
            assertTrue(description.genericParameters.single().isUnconstrained)
            assertEquals(
                DotNetGenericOwnerPhysicalBindingResult.Unavailable,
                declarations.directSupertypeEdgesOrUnavailable(definition),
            )
        }
        assertBound(conversion(declarations, producer, stringType(), objectType()))
        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            conversion(declarations, producer, objectType(), stringType()),
        )
        assertBound(conversion(declarations, consumer, objectType(), stringType()))
        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            conversion(declarations, consumer, stringType(), objectType()),
        )
        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            conversion(declarations, producer, int32Type(), objectType()),
        )
    }

    @Test
    fun preservesProducerDelegateAuthorityAcrossDeclarationEpochs() {
        val logicalKey = "C:sample/Producer"
        val bound = bindAuthority(mapOf(
            logicalKey to delegateRecord(
                listOf("sample.Producer`1"),
                DotNetGenericOwnerPhysicalTypeParameterVariance.COVARIANT,
            ),
        ))
        val sealed = bound.declarations.advance(
            DotNetGenericOwnerPhysicalAuthorityEpoch.SEALED_EMISSION_SIGNATURE_INDEX,
            typeDefinitions = emptyList(),
            methodDefinitions = emptyList(),
        ).boundValue()
        val definition = bound.typeDefinitionsByLogicalClassifierKey.getValue(logicalKey)

        assertBound(conversion(sealed, definition, stringType(), objectType()))
    }

    @Test
    fun composesOrderedProducerDelegateBinders() {
        val logicalKey = "C:sample/Transformer"
        val authority = bindAuthority(mapOf(
            logicalKey to delegateRecord(
                listOf("sample.Transformer`2"),
                DotNetGenericOwnerPhysicalTypeParameterVariance.CONTRAVARIANT,
                DotNetGenericOwnerPhysicalTypeParameterVariance.COVARIANT,
            ),
        ))
        val declarations = authority.declarations
        val definition = authority.typeDefinitionsByLogicalClassifierKey.getValue(logicalKey)

        assertBound(declarations.proveClrReferenceVarianceConversionOrError(
            DotNetGenericOwnerPhysicalView(construction(
                declarations,
                definition,
                listOf(objectType(), stringType()),
            )),
            DotNetGenericOwnerPhysicalView(construction(
                declarations,
                definition,
                listOf(stringType(), objectType()),
            )),
        ))
        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            declarations.proveClrReferenceVarianceConversionOrError(
                DotNetGenericOwnerPhysicalView(construction(
                    declarations,
                    definition,
                    listOf(stringType(), objectType()),
                )),
                DotNetGenericOwnerPhysicalView(construction(
                    declarations,
                    definition,
                    listOf(objectType(), stringType()),
                )),
            ),
        )
    }

    @Test
    fun doesNotInferDelegateAuthorityFromANameOrVariantClassRecord() {
        val logicalKey = "C:sample/System.Func"
        val ownerPath = listOf("System.Func`1")
        val authority = bindAuthority(mapOf(
            logicalKey to DotNetPhysicalDeclaration.Class(
                ownerPath = ownerPath,
                physicalTypeParameterCount = 1,
                physicalTypeParameterVariances = listOf(
                    DotNetGenericOwnerPhysicalTypeParameterVariance.COVARIANT,
                ),
            ),
        ))

        assertTrue(authority.typeDefinitionsByLogicalClassifierKey.isEmpty())
        assertNull(authority.declarations.typeDescriptionOrNull(
            DotNetGenericOwnerPhysicalTypeDefIdentity.KotlinProducer(artifact, ownerPath),
        ))
    }

    @Test
    fun callerAuthoredProducerDelegateFactCannotEnterTheGeneralIndex() {
        val identity = DotNetGenericOwnerPhysicalTypeDefIdentity.KotlinProducer(
            artifact,
            listOf("sample.Producer`1"),
        )
        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            DotNetGenericOwnerPhysicalDeclarationIndex.bind(
                DotNetGenericOwnerPhysicalAuthorityEpoch.BOUND_DECLARATION_INDEX,
                typeDefinitions = listOf(DotNetGenericOwnerPhysicalTypeDefReference(
                    identity = identity,
                    genericParameters = listOf(DotNetGenericOwnerPhysicalGenericParameterReference(
                        DotNetGenericOwnerPhysicalTypeParameterVariance.COVARIANT,
                        constraints = emptyList(),
                    )),
                    category = DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS,
                    supportsClrDelegateVariance = true,
                )),
                methodDefinitions = emptyList(),
            ),
        )
    }

    @Test
    fun conflictsWhenOneProducerTypeDefHasDifferentDelegateRows() {
        val ownerPath = listOf("sample.Dual`1")
        val result = DotNetProducerGenericDelegatePhysicalAuthority.bind(listOf(library(mapOf(
            "C:sample/First" to delegateRecord(
                ownerPath,
                DotNetGenericOwnerPhysicalTypeParameterVariance.COVARIANT,
            ),
            "C:sample/Second" to delegateRecord(
                ownerPath,
                DotNetGenericOwnerPhysicalTypeParameterVariance.CONTRAVARIANT,
            ),
        ))))

        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(result)
    }

    @Test
    fun rejectsDelegateAuthorityWhichOverlapsAnotherKotlinClassAbi() {
        assertFailsWith<IllegalArgumentException> {
            DotNetPhysicalDeclaration.Class(
                ownerPath = listOf("sample.Bad`1"),
                physicalTypeParameterCount = 1,
                physicalTypeParameterVariances = listOf(
                    DotNetGenericOwnerPhysicalTypeParameterVariance.COVARIANT,
                ),
                physicalClassVarianceKind = DotNetPhysicalClassVarianceKind.SEALED_CLR_DELEGATE,
                genericOwnerAbi = DotNetGenericOwnerAbi(
                    capabilityAssemblyName = artifact.assemblyName,
                    capabilityOwnerPath = listOf("sample.BadKotlinSemantic"),
                ),
            )
        }
    }

    @Test
    fun deterministicallyRoundTripsAndRejectsUnknownDelegateKind() {
        val logicalKey = "C:sample/Producer"
        val declarations = mapOf(logicalKey to delegateRecord(
            listOf("sample.Producer`1"),
            DotNetGenericOwnerPhysicalTypeParameterVariance.COVARIANT,
        ))
        val encoded = DotNetLibraryAbiCodec.encode(declarations)

        assertEquals("68", DotNetLibraryAbiCodec.ABI_VERSION)
        assertEquals(declarations, DotNetLibraryAbiCodec.decode(encoded.toProperties()))

        val entry = encoded.entries.single()
        val decoder = Base64.getUrlDecoder()
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val fields = decoder.decode(entry.value)
            .toString(Charsets.UTF_8)
            .split('\u0000')
            .toMutableList()
        fields[16] = "INVOKE_SHAPED_CLASS"
        val malformed = encoded + (entry.key to encoder.encodeToString(
            fields.joinToString("\u0000").toByteArray(Charsets.UTF_8),
        ))
        val failure = assertFailsWith<IllegalArgumentException> {
            DotNetLibraryAbiCodec.decode(malformed.toProperties())
        }
        assertTrue(failure.message.orEmpty().contains("invalid physical class variance kind"))

        val preAbi66Fields = decoder.decode(entry.value)
            .toString(Charsets.UTF_8)
            .split('\u0000')
            .toMutableList()
            .also { fieldsBeforeKind -> fieldsBeforeKind.removeAt(16) }
        val preAbi66 = encoded + (entry.key to encoder.encodeToString(
            preAbi66Fields.joinToString("\u0000").toByteArray(Charsets.UTF_8),
        ))
        assertFailsWith<IllegalArgumentException> {
            DotNetLibraryAbiCodec.decode(preAbi66.toProperties())
        }
    }

    private fun bindAuthority(
        declarations: Map<String, DotNetPhysicalDeclaration>,
    ): DotNetProducerGenericDelegatePhysicalAuthority =
        DotNetProducerGenericDelegatePhysicalAuthority.bind(listOf(library(declarations))).boundValue()

    private fun library(
        declarations: Map<String, DotNetPhysicalDeclaration>,
    ) = DotNetExternalLibrary(
        artifact = artifact,
        assemblyFile = File(artifact.assemblyFileName),
        declarations = declarations,
        friendAssemblies = emptySet(),
    )

    private fun delegateRecord(
        ownerPath: List<String>,
        vararg variances: DotNetGenericOwnerPhysicalTypeParameterVariance,
    ) = DotNetPhysicalDeclaration.Class(
        ownerPath = ownerPath,
        physicalTypeParameterCount = variances.size,
        physicalTypeParameterVariances = variances.toList(),
        physicalClassVarianceKind = DotNetPhysicalClassVarianceKind.SEALED_CLR_DELEGATE,
    )

    private fun conversion(
        declarations: DotNetGenericOwnerPhysicalDeclarationIndex,
        definition: DotNetGenericOwnerPhysicalTypeDefIdentity,
        sourceArgument: DotNetGenericOwnerSymbolicCarrierReference,
        targetArgument: DotNetGenericOwnerSymbolicCarrierReference,
    ): DotNetGenericOwnerPhysicalBindingResult<Unit> =
        declarations.proveClrReferenceVarianceConversionOrError(
            DotNetGenericOwnerPhysicalView(construction(declarations, definition, sourceArgument)),
            DotNetGenericOwnerPhysicalView(construction(declarations, definition, targetArgument)),
        )

    private fun construction(
        declarations: DotNetGenericOwnerPhysicalDeclarationIndex,
        definition: DotNetGenericOwnerPhysicalTypeDefIdentity,
        argument: DotNetGenericOwnerSymbolicCarrierReference,
    ) = construction(declarations, definition, listOf(argument))

    private fun construction(
        declarations: DotNetGenericOwnerPhysicalDeclarationIndex,
        definition: DotNetGenericOwnerPhysicalTypeDefIdentity,
        arguments: List<DotNetGenericOwnerSymbolicCarrierReference>,
    ) = declarations.constructTypeOrError(definition, arguments).boundValue()

    private fun stringType() = DotNetGenericOwnerSymbolicCarrierReference.stringCarrier()

    private fun objectType() = DotNetGenericOwnerSymbolicCarrierReference.objectCarrier()

    private fun int32Type() = DotNetGenericOwnerSymbolicCarrierReference.int32Carrier()

    private fun assertBound(result: DotNetGenericOwnerPhysicalBindingResult<*>) {
        assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<*>>(result)
    }

    private fun <T> DotNetGenericOwnerPhysicalBindingResult<T>.boundValue(): T = when (this) {
        is DotNetGenericOwnerPhysicalBindingResult.Bound -> value
        is DotNetGenericOwnerPhysicalBindingResult.Conflict -> error(reason)
        DotNetGenericOwnerPhysicalBindingResult.Unavailable -> error("physical authority was unavailable")
    }

    private fun Map<String, String>.toProperties(): Properties = Properties().also { properties ->
        forEach(properties::setProperty)
    }
}
