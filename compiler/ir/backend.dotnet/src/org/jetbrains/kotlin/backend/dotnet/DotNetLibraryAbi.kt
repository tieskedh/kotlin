/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.backend.common.serialization.signature.PublicIdSignatureComputer
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.util.IdSignatureRenderer
import org.jetbrains.kotlin.ir.util.fileOrNull
import org.jetbrains.kotlin.ir.util.isFakeOverride
import org.jetbrains.kotlin.ir.util.isOriginallyLocalDeclaration
import org.jetbrains.kotlin.ir.util.render
import java.io.File
import java.util.Base64
import java.util.Properties

/**
 * Physical CLR information paired with Kotlin's existing public [org.jetbrains.kotlin.ir.util.IdSignature].
 *
 * The index is deliberately not an export selector or a second Kotlin signature language. The
 * KLIB remains authoritative for logical declarations; this data only binds those declarations
 * to the CLR type/member identities emitted into the companion assembly.
 */
sealed interface DotNetPhysicalDeclaration {
    val ownerPath: List<String>

    data class Class(
        override val ownerPath: List<String>,
    ) : DotNetPhysicalDeclaration

    data class Function(
        override val ownerPath: List<String>,
        val methodName: String,
        val isInstance: Boolean,
    ) : DotNetPhysicalDeclaration
}

/** One metadata KLIB bound to its sibling CLR implementation and declaration index. */
data class DotNetExternalLibrary(
    val artifact: DotNetLibraryArtifact,
    val metadataFile: File,
    val implementationFile: File,
    val declarations: Map<String, DotNetPhysicalDeclaration>,
)

/** Manifest codec for the provisional declaration-index schema. */
object DotNetLibraryAbiCodec {
    const val ABI_VERSION = "1"
    const val ABI_VERSION_PROPERTY = "dotnet_abi_version"
    const val DECLARATION_PROPERTY_PREFIX = "dotnet_decl_"

    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(declarations: Map<String, DotNetPhysicalDeclaration>): Map<String, String> =
        declarations.toSortedMap().mapKeys { entry ->
            DECLARATION_PROPERTY_PREFIX + encodeText(entry.key)
        }.mapValues { entry ->
            val declaration = entry.value
            val fields = when (declaration) {
                is DotNetPhysicalDeclaration.Class -> listOf("C") + declaration.ownerPath
                is DotNetPhysicalDeclaration.Function ->
                    listOf("F", if (declaration.isInstance) "1" else "0", declaration.methodName) +
                            declaration.ownerPath
            }
            encodeText(fields.joinToString("\u0000"))
        }

    fun decode(properties: Properties): Map<String, DotNetPhysicalDeclaration> = buildMap {
        for (propertyName in properties.stringPropertyNames().sorted()) {
            if (!propertyName.startsWith(DECLARATION_PROPERTY_PREFIX)) continue
            val logicalKey = decodeText(propertyName.removePrefix(DECLARATION_PROPERTY_PREFIX))
            val fields = decodeText(properties.getProperty(propertyName)).split('\u0000')
            val declaration = when (fields.firstOrNull()) {
                "C" -> DotNetPhysicalDeclaration.Class(fields.drop(1).requireOwnerPath(logicalKey))
                "F" -> {
                    require(fields.size >= 4) { "function declaration '$logicalKey' has an incomplete CLR identity" }
                    val isInstance = when (fields[1]) {
                        "0" -> false
                        "1" -> true
                        else -> throw IllegalArgumentException(
                            "function declaration '$logicalKey' has invalid dispatch flag '${fields[1]}'"
                        )
                    }
                    require(fields[2].isNotEmpty()) { "function declaration '$logicalKey' has an empty CLR method name" }
                    DotNetPhysicalDeclaration.Function(
                        ownerPath = fields.drop(3).requireOwnerPath(logicalKey),
                        methodName = fields[2],
                        isInstance = isInstance,
                    )
                }
                else -> throw IllegalArgumentException("declaration '$logicalKey' has an unknown CLR identity kind")
            }
            require(put(logicalKey, declaration) == null) { "duplicate CLR declaration identity '$logicalKey'" }
        }
    }

    private fun List<String>.requireOwnerPath(logicalKey: String): List<String> =
        onEach { component ->
            require(component.isNotEmpty()) { "declaration '$logicalKey' has an empty CLR owner component" }
        }.also { path ->
            require(path.isNotEmpty()) { "declaration '$logicalKey' has no CLR owner" }
        }

    private fun encodeText(value: String): String = encoder.encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun decodeText(value: String): String = decoder.decode(value).toString(Charsets.UTF_8)
}

/** Computes the same public Kotlin identity for producer and metadata-deserialized consumer IR. */
private fun IrDeclaration.computeDotNetLibraryAbiKeyOrNull(
    kind: String,
    signatureComputer: PublicIdSignatureComputer,
): String? {
    val signature = (this as org.jetbrains.kotlin.ir.declarations.IrSymbolOwner).symbol.signature ?: run {
        if (!with(DotNetIrMangler) { this@computeDotNetLibraryAbiKeyOrNull.isExported(compatibleMode = false) }) return null
        signatureComputer.inFile(fileOrNull?.symbol) {
            signatureComputer.computePublicIdSignature(this, compatibleMode = false)
        }
    }
    return "$kind:${signature.render(IdSignatureRenderer.LEGACY)}"
}

/**
 * Resolves metadata-deserialized declarations through the bound physical identities of their
 * companion assemblies. Only declarations present in the index become physical CLR references.
 */
internal class DotNetExternalDeclarations(
    val libraries: List<DotNetExternalLibrary>,
) {
    private data class BoundDeclaration(
        val library: DotNetExternalLibrary,
        val declaration: DotNetPhysicalDeclaration,
    )

    private val declarations: Map<String, BoundDeclaration> = buildMap {
        for (library in libraries) {
            for (entry in library.declarations) {
                val logicalKey = entry.key
                val declaration = entry.value
                require(put(logicalKey, BoundDeclaration(library, declaration)) == null) {
                    "duplicate external Kotlin/.NET declaration identity '$logicalKey'"
                }
            }
        }
    }
    private val classInfoByLogicalKey = hashMapOf<String, DotNetIlClassInfo>()
    private val classLinksInProgress = hashSetOf<String>()
    private val facadeInfoByPhysicalIdentity = hashMapOf<Pair<String, List<String>>, DotNetIlClassInfo>()
    private val signatureComputer = PublicIdSignatureComputer(DotNetIrMangler)

    fun classInfoOrNull(irClass: IrClass, typeMapper: DotNetIlTypeMapper): DotNetIlClassInfo? {
        val logicalKey = irClass.computeDotNetLibraryAbiKeyOrNull("C", signatureComputer) ?: return null
        classInfoByLogicalKey[logicalKey]?.let { return it }
        val bound = declarations[logicalKey] ?: return null
        val declaration = bound.declaration as? DotNetPhysicalDeclaration.Class ?: return null
        val classInfo = buildClassInfo(
            bound.library.artifact.assemblyName,
            declaration.ownerPath,
            irClass.typeParameters.map { it.variance },
        )
        classInfoByLogicalKey[logicalKey] = classInfo

        if (classLinksInProgress.add(logicalKey)) {
            try {
                classInfo.baseType = irClass.dotNetBaseSuperTypeOrNull()?.let(typeMapper::toDotNetIlValueType)
                classInfo.interfaces = irClass.dotNetDirectInterfaceTypes().mapNotNull(typeMapper::toDotNetIlValueType)
            } finally {
                classLinksInProgress.remove(logicalKey)
            }
        }
        return classInfo
    }

    fun functionInfoOrNull(
        function: IrSimpleFunction,
        typeMapper: DotNetIlTypeMapper,
    ): DotNetIlFunctionInfo? {
        val logicalKey = function.computeDotNetLibraryAbiKeyOrNull("F", signatureComputer) ?: return null
        val bound = declarations[logicalKey] ?: return null
        val declaration = bound.declaration as? DotNetPhysicalDeclaration.Function ?: return null
        val owner = (function.parent as? IrClass)?.let { classInfoOrNull(it, typeMapper) }
            ?: facadeInfoByPhysicalIdentity.getOrPut(
                bound.library.artifact.assemblyName to declaration.ownerPath
            ) {
                buildClassInfo(bound.library.artifact.assemblyName, declaration.ownerPath, emptyList())
            }
        require(owner.physicalPathComponents() == declaration.ownerPath) {
            "external function '$logicalKey' is bound to a CLR owner inconsistent with its containing class"
        }
        val signature = function.dotNetSignature(typeMapper)
        require(signature.hasThis == declaration.isInstance) {
            "external function '$logicalKey' has a CLR dispatch shape inconsistent with its metadata"
        }
        return DotNetIlFunctionInfo(owner, signature, declaration.methodName)
    }

    private fun buildClassInfo(
        assemblyName: String,
        ownerPath: List<String>,
        finalTypeParameterVariances: List<org.jetbrains.kotlin.types.Variance>,
    ): DotNetIlClassInfo {
        require(ownerPath.isNotEmpty()) { "external CLR owner path must not be empty" }
        var current: DotNetIlClassInfo? = null
        for (entry in ownerPath.withIndex()) {
            val index = entry.index
            val component = entry.value
            current = DotNetIlClassInfo(
                component,
                current,
                if (index == ownerPath.lastIndex) finalTypeParameterVariances else emptyList(),
                if (index == 0) assemblyName else null,
            )
        }
        return checkNotNull(current)
    }
}

/** Builds the physical index only from declarations that survived the emitter's fixpoint. */
internal fun collectDotNetLibraryDeclarations(
    files: Set<IrFile>,
    availableClasses: Map<IrClass, DotNetIlClassInfo>,
    availableFunctions: Map<IrSimpleFunction, DotNetIlFunctionInfo>,
): Map<String, DotNetPhysicalDeclaration> = buildMap {
    val signatureComputer = PublicIdSignatureComputer(DotNetIrMangler)
    for (entry in availableClasses) {
        val irClass = entry.key
        val classInfo = entry.value
        if (irClass.fileOrNull !in files || irClass.isOriginallyLocalDeclaration) continue
        val logicalKey = irClass.computeDotNetLibraryAbiKeyOrNull("C", signatureComputer) ?: continue
        put(logicalKey, DotNetPhysicalDeclaration.Class(classInfo.physicalPathComponents()))
    }
    for (entry in availableFunctions) {
        val function = entry.key
        val functionInfo = entry.value
        if (function.fileOrNull !in files || function.isOriginallyLocalDeclaration || function.isFakeOverride) continue
        val logicalKey = function.computeDotNetLibraryAbiKeyOrNull("F", signatureComputer) ?: continue
        put(
            logicalKey,
            DotNetPhysicalDeclaration.Function(
                ownerPath = functionInfo.owner.physicalPathComponents(),
                methodName = function.dotNetIlMethodName(),
                isInstance = functionInfo.isInstance,
            )
        )
    }
}
