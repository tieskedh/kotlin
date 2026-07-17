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
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.isFakeOverride
import org.jetbrains.kotlin.ir.util.isOriginallyLocalDeclaration
import org.jetbrains.kotlin.ir.util.render
import org.jetbrains.kotlin.types.Variance
import java.io.File
import java.security.MessageDigest
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

    /**
     * One logical Kotlin class and its physical CLR owner.
     *
     * [ownerPath] is always the canonical owner. A Kotlin-owned generic interface additionally
     * records its CLR-facing generic view and optional complete invariant capability explicitly;
     * consumers must never reconstruct either sibling from the canonical name or its arity.
     */
    data class Class(
        override val ownerPath: List<String>,
        val declaredOwnerPath: List<String>? = null,
        val exactOwnerPath: List<String>? = null,
    ) : DotNetPhysicalDeclaration {
        init {
            require(exactOwnerPath == null || declaredOwnerPath != null) {
                "an exact generic-interface owner requires a declared generic owner"
            }
        }
    }

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
    const val ABI_VERSION = "2"
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
                is DotNetPhysicalDeclaration.Class -> declaration.encodeFields()
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
                "GI" -> decodeGenericInterfaceClass(fields, logicalKey)
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

    private fun DotNetPhysicalDeclaration.Class.encodeFields(): List<String> {
        val declaredPath = declaredOwnerPath ?: return listOf("C") + ownerPath
        val exactPath = exactOwnerPath.orEmpty()
        return listOf(
            "GI",
            ownerPath.size.toString(),
            declaredPath.size.toString(),
            exactPath.size.toString(),
        ) + ownerPath + declaredPath + exactPath
    }

    private fun decodeGenericInterfaceClass(
        fields: List<String>,
        logicalKey: String,
    ): DotNetPhysicalDeclaration.Class {
        require(fields.size >= 4) {
            "generic-interface declaration '$logicalKey' has an incomplete CLR identity"
        }
        fun pathSize(fieldIndex: Int, view: String, allowAbsent: Boolean = false): Int {
            val size = fields[fieldIndex].toIntOrNull()
            require(size != null && (size > 0 || allowAbsent && size == 0)) {
                "generic-interface declaration '$logicalKey' has invalid $view owner-path size '${fields[fieldIndex]}'"
            }
            return size
        }

        val canonicalSize = pathSize(1, "canonical")
        val declaredSize = pathSize(2, "declared")
        val exactSize = pathSize(3, "exact", allowAbsent = true)
        val expectedSize = 4 + canonicalSize + declaredSize + exactSize
        require(fields.size == expectedSize) {
            "generic-interface declaration '$logicalKey' has an inconsistent CLR owner-path payload"
        }
        var offset = 4
        fun takePath(size: Int): List<String> = fields.subList(offset, offset + size).also { offset += size }
        val canonicalPath = takePath(canonicalSize).requireOwnerPath(logicalKey, "canonical")
        val declaredPath = takePath(declaredSize).requireOwnerPath(logicalKey, "declared")
        val exactPath = if (exactSize == 0) null else takePath(exactSize).requireOwnerPath(logicalKey, "exact")
        return DotNetPhysicalDeclaration.Class(canonicalPath, declaredPath, exactPath)
    }

    private fun List<String>.requireOwnerPath(logicalKey: String, view: String? = null): List<String> =
        onEach { component ->
            require(component.isNotEmpty()) {
                "declaration '$logicalKey' has an empty ${view?.let { "$it " }.orEmpty()}CLR owner component"
            }
        }.also { path ->
            require(path.isNotEmpty()) {
                "declaration '$logicalKey' has no ${view?.let { "$it " }.orEmpty()}CLR owner"
            }
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
 * Stable identity suffix for one logical generic-interface slot.
 *
 * Public declarations use the same Kotlin [org.jetbrains.kotlin.ir.util.IdSignature] that keys
 * the companion KLIB index. Non-exported declarations use a structural source identity; they do
 * not cross module boundaries, but still need deterministic collision-free names inside their
 * assembly. The digest is deliberately independent of declaration order and source offsets.
 */
internal fun IrSimpleFunction.dotNetGenericInterfaceCanonicalSlotId(): String {
    val signatureComputer = PublicIdSignatureComputer(DotNetIrMangler)
    val logicalIdentity = computeDotNetLibraryAbiKeyOrNull("F", signatureComputer) ?: buildString {
        append((parent as? IrClass)?.fqNameWhenAvailable?.asString().orEmpty())
        append('|')
        append(dotNetIlMethodName())
        append('|')
        append(typeParameters.size)
        append('|')
        parameters.forEach { parameter ->
            append(parameter.kind)
            append(':')
            append(parameter.type.render())
            append(';')
        }
        append("->")
        append(returnType.render())
    }
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(logicalIdentity.toByteArray(Charsets.UTF_8))
        .take(16)
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
    return digest
}

/** Reserved physical name of a canonical erased slot; typed capabilities keep the source name. */
internal fun IrSimpleFunction.dotNetGenericInterfaceCanonicalMethodName(): String =
    "${dotNetIlMethodName()}__KotlinErased__${dotNetGenericInterfaceCanonicalSlotId()}"

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
    private val canonicalClassInfoByLogicalKey = hashMapOf<String, DotNetIlClassInfo>()
    private val declaredClassInfoByLogicalKey = hashMapOf<String, DotNetIlClassInfo>()
    private val exactClassInfoByLogicalKey = hashMapOf<String, DotNetIlClassInfo>()
    private val classLinksInProgress = hashSetOf<String>()
    private val facadeInfoByPhysicalIdentity = hashMapOf<Pair<String, List<String>>, DotNetIlClassInfo>()
    private val signatureComputer = PublicIdSignatureComputer(DotNetIrMangler)

    fun classInfoOrNull(irClass: IrClass, typeMapper: DotNetIlTypeMapper): DotNetIlClassInfo? {
        val logicalKey = irClass.computeDotNetLibraryAbiKeyOrNull("C", signatureComputer) ?: return null
        canonicalClassInfoByLogicalKey[logicalKey]?.let { return it }
        val bound = declarations[logicalKey] ?: return null
        val declaration = bound.declaration as? DotNetPhysicalDeclaration.Class ?: return null
        val canonicalVariances = if (declaration.declaredOwnerPath == null) {
            irClass.typeParameters.map { it.variance }
        } else {
            emptyList()
        }
        val classInfo = buildClassInfo(
            bound.library.artifact.assemblyName,
            declaration.ownerPath,
            canonicalVariances,
        )
        canonicalClassInfoByLogicalKey[logicalKey] = classInfo

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

    /** The explicitly indexed generic CLR view carrying the declaration's Kotlin variance vector. */
    fun declaredClassInfoOrNull(irClass: IrClass): DotNetIlClassInfo? {
        val logicalKey = irClass.computeDotNetLibraryAbiKeyOrNull("C", signatureComputer) ?: return null
        declaredClassInfoByLogicalKey[logicalKey]?.let { return it }
        val bound = declarations[logicalKey] ?: return null
        val declaration = bound.declaration as? DotNetPhysicalDeclaration.Class ?: return null
        val declaredOwnerPath = declaration.declaredOwnerPath ?: return null
        return buildClassInfo(
            bound.library.artifact.assemblyName,
            declaredOwnerPath,
            irClass.typeParameters.map { it.variance },
        ).also { declaredClassInfoByLogicalKey[logicalKey] = it }
    }

    /** The explicitly indexed complete typed capability; every CLR parameter is invariant. */
    fun exactClassInfoOrNull(irClass: IrClass): DotNetIlClassInfo? {
        val logicalKey = irClass.computeDotNetLibraryAbiKeyOrNull("C", signatureComputer) ?: return null
        exactClassInfoByLogicalKey[logicalKey]?.let { return it }
        val bound = declarations[logicalKey] ?: return null
        val declaration = bound.declaration as? DotNetPhysicalDeclaration.Class ?: return null
        val exactOwnerPath = declaration.exactOwnerPath ?: return null
        return buildClassInfo(
            bound.library.artifact.assemblyName,
            exactOwnerPath,
            List(irClass.typeParameters.size) { Variance.INVARIANT },
        ).also { exactClassInfoByLogicalKey[logicalKey] = it }
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
        finalTypeParameterVariances: List<Variance>,
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
    genericInterfaces: Map<IrClass, DotNetGenericInterfaceInfo> = emptyMap(),
): Map<String, DotNetPhysicalDeclaration> = buildMap {
    val signatureComputer = PublicIdSignatureComputer(DotNetIrMangler)
    for (entry in availableClasses) {
        val irClass = entry.key
        val classInfo = entry.value
        if (irClass.fileOrNull !in files || irClass.isOriginallyLocalDeclaration) continue
        val logicalKey = irClass.computeDotNetLibraryAbiKeyOrNull("C", signatureComputer) ?: continue
        val genericInterface = genericInterfaces[irClass]
        if (genericInterface == null) {
            put(logicalKey, DotNetPhysicalDeclaration.Class(classInfo.physicalPathComponents()))
        } else {
            val canonicalOwnerPath = genericInterface.canonicalClassInfo.physicalPathComponents()
            require(canonicalOwnerPath == classInfo.physicalPathComponents()) {
                "generic interface '${irClass.render()}' has a canonical CLR owner inconsistent with its class index"
            }
            put(
                logicalKey,
                DotNetPhysicalDeclaration.Class(
                    ownerPath = canonicalOwnerPath,
                    declaredOwnerPath = genericInterface.declaredClassInfo.physicalPathComponents(),
                    exactOwnerPath = genericInterface.exactClassInfo?.physicalPathComponents(),
                )
            )
        }
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
                methodName = functionInfo.physicalMethodName ?: function.dotNetIlMethodName(),
                isInstance = functionInfo.isInstance,
            )
        )
    }
}
