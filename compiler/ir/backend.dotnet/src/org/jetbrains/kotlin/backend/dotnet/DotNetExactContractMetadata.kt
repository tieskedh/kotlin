/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.irAttribute
import org.jetbrains.kotlin.load.dotnet.DotNetExactContractEffect
import org.jetbrains.kotlin.load.dotnet.DotNetExactContractProjection
import org.jetbrains.kotlin.load.dotnet.DotNetExactContractProjectionVersion

/** The FIR-selected additive CLR view; Kotlin contract semantics remain in FIR and KLIB. */
internal var IrSimpleFunction.dotNetExactContractProjection: DotNetExactContractProjection?
        by irAttribute(copyByDefault = true)

internal fun IrSimpleFunction.installDotNetExactContractProjection(
    projection: DotNetExactContractProjection,
) {
    when (projection.carrierVersion) {
        DotNetExactContractProjectionVersion.V1 -> Unit
    }
    val previous = dotNetExactContractProjection
    check(previous == null || previous == projection) {
        "Conflicting exact CLR contract projections on '${name.asString()}'"
    }
    dotNetExactContractProjection = projection
}

internal data class DotNetRenderedCodeAnalysisMetadata(
    val methodAttributes: List<String>,
    val parameterAttributes: Map<Int, List<String>>,
) {
    val isNotEmpty: Boolean
        get() = methodAttributes.isNotEmpty() || parameterAttributes.isNotEmpty()
}

/** Renders only identities physically supplied by the selected target-framework profile. */
internal fun IrSimpleFunction.renderDotNetCodeAnalysisMetadata(
    coreLibrary: DotNetCoreLibraryProfile,
    retainedParameterCount: Int = parameters.size,
): DotNetRenderedCodeAnalysisMetadata {
    val attributeAssemblyReference = coreLibrary.codeAnalysisAttributeAssemblyReference
        ?: return DotNetRenderedCodeAnalysisMetadata(emptyList(), emptyMap())
    val projection = dotNetExactContractProjection
        ?: return DotNetRenderedCodeAnalysisMetadata(emptyList(), emptyMap())
    require(retainedParameterCount in 0..parameters.size)

    val methodAttributes = mutableListOf<String>()
    val parameterAttributes = linkedMapOf<Int, MutableList<String>>()
    val valueParameters = parameters.filter { parameter -> parameter.kind == IrParameterKind.Regular }

    fun attributeTargetIndex(valueParameterIndex: Int): Int? {
        val parameter = valueParameters.getOrNull(valueParameterIndex) ?: return null
        val physicalIndex = parameters.indexOf(parameter)
        if (physicalIndex !in 0 until retainedParameterCount) return null
        return physicalIndex + 1
    }

    fun addParameterAttribute(parameterIndex: Int, attribute: String) {
        parameterAttributes.getOrPut(parameterIndex, ::mutableListOf).add(attribute)
    }

    for (effect in projection.effects) {
        when (effect) {
            DotNetExactContractEffect.DoesNotReturn -> methodAttributes +=
                emptyCodeAnalysisAttribute(attributeAssemblyReference, "DoesNotReturnAttribute")
            is DotNetExactContractEffect.ParameterNotNull -> {
                val index = attributeTargetIndex(effect.valueParameterIndex) ?: continue
                addParameterAttribute(
                    index,
                    emptyCodeAnalysisAttribute(attributeAssemblyReference, "NotNullAttribute"),
                )
            }
            is DotNetExactContractEffect.ParameterNotNullWhen -> {
                val index = attributeTargetIndex(effect.valueParameterIndex) ?: continue
                addParameterAttribute(
                    index,
                    booleanCodeAnalysisAttribute(
                        attributeAssemblyReference,
                        "NotNullWhenAttribute",
                        effect.returnValue,
                    ),
                )
            }
            is DotNetExactContractEffect.ReturnNotNullIfParameterNotNull -> {
                val index = attributeTargetIndex(effect.valueParameterIndex) ?: continue
                val parameterName = parameters[index - 1].name.asString()
                addParameterAttribute(
                    0,
                    stringCodeAnalysisAttribute(
                        attributeAssemblyReference,
                        "NotNullIfNotNullAttribute",
                        parameterName,
                    ),
                )
            }
            is DotNetExactContractEffect.DoesNotReturnIf -> {
                val index = attributeTargetIndex(effect.valueParameterIndex) ?: continue
                addParameterAttribute(
                    index,
                    booleanCodeAnalysisAttribute(
                        attributeAssemblyReference,
                        "DoesNotReturnIfAttribute",
                        effect.parameterValue,
                    ),
                )
            }
        }
    }
    return DotNetRenderedCodeAnalysisMetadata(
        methodAttributes = methodAttributes,
        parameterAttributes = parameterAttributes.mapValues { entry -> entry.value.toList() },
    )
}

private val DotNetCoreLibraryProfile.codeAnalysisAttributeAssemblyReference: String?
    get() = when (this) {
        DotNetCoreLibraryProfile.NET10_0 -> "[System.Runtime]"
        DotNetCoreLibraryProfile.NET48,
        DotNetCoreLibraryProfile.NETSTANDARD_2_0,
            -> null
    }

private fun emptyCodeAnalysisAttribute(assemblyReference: String, attributeName: String): String =
    ".custom instance void $assemblyReference" +
            "System.Diagnostics.CodeAnalysis.$attributeName::.ctor() = (01 00 00 00)"

private fun booleanCodeAnalysisAttribute(
    assemblyReference: String,
    attributeName: String,
    value: Boolean,
): String {
    val byte = if (value) "01" else "00"
    return ".custom instance void $assemblyReference" +
            "System.Diagnostics.CodeAnalysis.$attributeName::.ctor(bool) = (01 00 $byte 00 00)"
}

private fun stringCodeAnalysisAttribute(
    assemblyReference: String,
    attributeName: String,
    value: String,
): String {
    val blob = (listOf(0x01, 0x00) + serializedContractAttributeString(value) + listOf(0x00, 0x00))
        .joinToString(" ") { byte -> byte.toString(16).padStart(2, '0') }
    return ".custom instance void $assemblyReference" +
            "System.Diagnostics.CodeAnalysis.$attributeName::.ctor(string) = ($blob)"
}

private fun serializedContractAttributeString(value: String): List<Int> {
    val bytes = value.encodeToByteArray().map { byte -> byte.toInt() and 0xff }
    val size = bytes.size
    val length = when {
        size <= 0x7f -> listOf(size)
        size <= 0x3fff -> listOf(0x80 or (size shr 8), size and 0xff)
        size <= 0x1fffffff -> listOf(
            0xc0 or (size shr 24),
            (size shr 16) and 0xff,
            (size shr 8) and 0xff,
            size and 0xff,
        )
        else -> error("contract-attribute parameter name is too large")
    }
    return length + bytes
}
