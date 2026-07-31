/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.load.dotnet

import org.jetbrains.kotlin.descriptors.SourceFile
import org.jetbrains.kotlin.serialization.deserialization.IncompatibleVersionErrorData
import org.jetbrains.kotlin.serialization.deserialization.descriptors.DeserializedContainerAbiStability
import org.jetbrains.kotlin.serialization.deserialization.descriptors.DeserializedContainerSource
import org.jetbrains.kotlin.serialization.deserialization.descriptors.PreReleaseInfo

/**
 * In-process protocol version for exact foreign CLR declaration linkage retained through FIR2IR.
 *
 * This carrier is not serialized into a Kotlin or CLR artifact. The explicit version still makes
 * producer/consumer shape changes exhaustive instead of allowing a backend to reinterpret an
 * unfamiliar carrier from names or tokens.
 */
enum class DotNetClrImportedDeclarationCarrierVersion {
    V1,
}

sealed class DotNetClrImportedDeclarationSource(
    val assembly: DotNetClrClasspathAssembly.WithoutCarrier,
    val declaringType: DotNetClrTypeDefinition,
) : DeserializedContainerSource {
    val carrierVersion: DotNetClrImportedDeclarationCarrierVersion =
        DotNetClrImportedDeclarationCarrierVersion.V1

    init {
        require(assembly.metadata.typeDefinitions.any { it === declaringType }) {
            "Imported CLR TypeDef ${declaringType.handle} does not belong to '${assembly.assemblyFile}'"
        }
    }

    override val incompatibility: IncompatibleVersionErrorData<*>?
        get() = null
    override val preReleaseInfo: PreReleaseInfo
        get() = PreReleaseInfo.DEFAULT_VISIBLE
    override val abiStability: DeserializedContainerAbiStability
        get() = DeserializedContainerAbiStability.STABLE

    override fun getContainingFile(): SourceFile = SourceFile.NO_SOURCE_FILE
}

/**
 * Exact physical linkage retained on one FIR function imported from a resource-free CLR DLL.
 *
 * FIR2IR preserves [DeserializedContainerSource] on lazy external functions. Keeping the selected
 * assembly, TypeDef, and MethodDef here prevents codegen from performing a second classpath or
 * display-name lookup after Kotlin type enhancement has produced the logical declaration view.
 */
class DotNetClrImportedMethodSource(
    assembly: DotNetClrClasspathAssembly.WithoutCarrier,
    declaringType: DotNetClrTypeDefinition,
    val method: DotNetClrMethodDefinition,
) : DotNetClrImportedDeclarationSource(assembly, declaringType) {
    init {
        require(method.declaringType == declaringType.handle) {
            "Imported CLR MethodDef ${method.handle} does not belong to TypeDef ${declaringType.handle}"
        }
        require(assembly.metadata.methodDefinitions.any { it === method }) {
            "Imported CLR MethodDef ${method.handle} does not belong to '${assembly.assemblyFile}'"
        }
    }

    override val presentableString: String =
        "${assembly.identityDisplayName()} TypeDef 0x${declaringType.handle.token.toUInt().toString(16)} " +
                "MethodDef 0x${method.handle.token.toUInt().toString(16)}"
}

/**
 * One physical Property row and its exact MethodSemantics-selected accessors.
 *
 * The same source is retained on the lazy IR property, getter, and optional setter. Codegen uses
 * accessor declaration identity to select [getter] or [setter]; their names are never inferred
 * from [property].
 */
class DotNetClrImportedPropertySource(
    assembly: DotNetClrClasspathAssembly.WithoutCarrier,
    declaringType: DotNetClrTypeDefinition,
    val property: DotNetClrPropertyDefinition,
    val getter: DotNetClrMethodDefinition,
    val setter: DotNetClrMethodDefinition?,
) : DotNetClrImportedDeclarationSource(assembly, declaringType) {
    init {
        require(property.declaringType == declaringType.handle) {
            "Imported CLR Property ${property.handle} does not belong to TypeDef ${declaringType.handle}"
        }
        require(getter.declaringType == declaringType.handle) {
            "Imported CLR property getter ${getter.handle} does not belong to TypeDef ${declaringType.handle}"
        }
        require(setter == null || setter.declaringType == declaringType.handle) {
            "Imported CLR property setter ${setter?.handle} does not belong to TypeDef ${declaringType.handle}"
        }
        require(assembly.metadata.propertyDefinitions.any { it === property }) {
            "Imported CLR Property ${property.handle} does not belong to '${assembly.assemblyFile}'"
        }
        require(assembly.metadata.methodDefinitions.any { it === getter }) {
            "Imported CLR property getter ${getter.handle} does not belong to '${assembly.assemblyFile}'"
        }
        require(setter == null || assembly.metadata.methodDefinitions.any { it === setter }) {
            "Imported CLR property setter ${setter?.handle} does not belong to '${assembly.assemblyFile}'"
        }
    }

    override val presentableString: String =
        "${assembly.identityDisplayName()} TypeDef 0x${declaringType.handle.token.toUInt().toString(16)} " +
                "Property 0x${property.handle.token.toUInt().toString(16)}"
}

private fun DotNetClrClasspathAssembly.WithoutCarrier.identityDisplayName(): String =
    "${metadata.identity.name}, Version=${metadata.identity.version}, Culture=${metadata.identity.culture}"
