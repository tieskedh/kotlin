/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.backend.dotnet.lower.isDotNetGenericInterfaceDefaultPhysicalMethod
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.util.fileOrNull
import org.jetbrains.kotlin.ir.util.isFakeOverride
import org.jetbrains.kotlin.types.Variance
import java.security.MessageDigest
import java.util.Base64

/**
 * DLL-owned contract used by the supported C# source-authoring path for Kotlin interfaces.
 *
 * This is deliberately separate from [DotNetPhysicalDeclaration]. The latter supplements a KLIB
 * and therefore keeps the KLIB authoritative for logical declarations. This manifest must be
 * sufficient together with ordinary CLR metadata in the same DLL: a Roslyn tool must not need the
 * sibling KLIB to discover Kotlin's physical interface views or implementation obligations.
 */
data class DotNetCSharpImplementationManifest(
    val schemaVersion: Int,
    val assemblyName: String,
    val targetProfile: String,
    val interfaces: List<DotNetCSharpInterfaceContract>,
)

data class DotNetCSharpInterfaceContract(
    val logicalKey: String,
    val canonicalOwnerPath: List<String>,
    val declaredOwnerPath: List<String>,
    val exactOwnerPath: List<String>?,
    val typeParameters: List<DotNetCSharpTypeParameter>,
    val sourceAuthoringSupported: Boolean,
    val unsupportedReasons: List<String>,
    val members: List<DotNetCSharpMemberContract>,
    val intersections: List<DotNetCSharpIntersectionContract>,
)

data class DotNetCSharpTypeParameter(
    val name: String,
    val variance: DotNetCSharpTypeParameterVariance,
)

enum class DotNetCSharpTypeParameterVariance {
    INVARIANT,
    IN,
    OUT,
}

enum class DotNetCSharpMemberKind {
    METHOD,
    PROPERTY_GETTER,
    PROPERTY_SETTER,
}

enum class DotNetCSharpDefaultKind {
    ABSTRACT,
    PORTABLE_HELPER,
    DIM_WITH_HELPER,
}

enum class DotNetCSharpInterfaceView {
    DECLARED,
    EXACT,
}

enum class DotNetCSharpSlotRole {
    ERASED,
    DECLARED,
    EXACT,
    HELPER,
}

data class DotNetCSharpMemberContract(
    val logicalKey: String,
    val kind: DotNetCSharpMemberKind,
    val sourceName: String,
    val authoringView: DotNetCSharpInterfaceView,
    val defaultKind: DotNetCSharpDefaultKind,
    val semanticBodyView: DotNetCSharpInterfaceView?,
    val slots: List<DotNetCSharpMethodLocator>,
)

data class DotNetCSharpIntersectionContract(
    val logicalKey: String,
    val kind: DotNetCSharpMemberKind,
    val sourceName: String,
    val authoringView: DotNetCSharpInterfaceView,
    val contributingLogicalMemberKeys: List<String>,
    val slots: List<DotNetCSharpMethodLocator>,
)

/**
 * Stable lookup of one MethodDef in the containing DLL.
 *
 * CLR signatures and constraints stay authoritative in CLR metadata. The repeated signature here
 * is a locator and integrity check, not a second type system for a future generator.
 * [propertyName] identifies the Property row through which C# must implement an accessor.
 */
data class DotNetCSharpMethodLocator(
    val role: DotNetCSharpSlotRole,
    val ownerPath: List<String>,
    val methodName: String,
    val propertyName: String?,
    val genericArity: Int,
    val returnType: String,
    val parameterTypes: List<String>,
)

/**
 * Deterministic versioned codec plus its current assembly-metadata carrier.
 *
 * The record format is carrier-independent. ILAsm cannot put arbitrary bytes in the managed
 * resource section, so the prototype stores a base64 payload in indexed
 * `AssemblyMetadataAttribute` chunks. The ABI design requires a true managed resource once the
 * backend owns a capable PE writer; changing carriers must not change these records.
 */
object DotNetCSharpImplementationManifestCodec {
    const val CURRENT_SCHEMA_VERSION = 2
    const val ASSEMBLY_METADATA_KEY = "Kotlin.CSharpImplementationManifest"

    private const val HEADER_RECORD = "H"
    private const val INTERFACE_RECORD = "I"
    private const val MEMBER_RECORD = "M"
    private const val SLOT_RECORD = "S"
    private const val INTERSECTION_RECORD = "X"
    private const val INTERSECTION_SLOT_RECORD = "Y"
    private const val NULL_FIELD = "~"
    private const val CHUNK_CHARACTER_COUNT = 12_000
    private const val LIST_SEPARATOR = "\u0000"
    private const val TYPE_PARAMETER_SEPARATOR = "\u0001"

    fun encode(manifest: DotNetCSharpImplementationManifest): String = buildString {
        appendRecord(
            HEADER_RECORD,
            manifest.schemaVersion.toString(),
            manifest.assemblyName,
            manifest.targetProfile,
        )
        for (contract in manifest.interfaces.sortedBy(DotNetCSharpInterfaceContract::logicalKey)) {
            appendRecord(
                INTERFACE_RECORD,
                contract.logicalKey,
                contract.canonicalOwnerPath.encodeList(),
                contract.declaredOwnerPath.encodeList(),
                contract.exactOwnerPath?.encodeList(),
                contract.typeParameters.joinToString(LIST_SEPARATOR) { parameter ->
                    parameter.name + TYPE_PARAMETER_SEPARATOR + parameter.variance.name
                },
                contract.sourceAuthoringSupported.toString(),
                contract.unsupportedReasons.encodeList(),
            )
            for (member in contract.members.sortedBy(DotNetCSharpMemberContract::logicalKey)) {
                appendRecord(
                    MEMBER_RECORD,
                    contract.logicalKey,
                    member.logicalKey,
                    member.kind.name,
                    member.sourceName,
                    member.authoringView.name,
                    member.defaultKind.name,
                    member.semanticBodyView?.name,
                )
                for (slot in member.slots.sortedBy(DotNetCSharpMethodLocator::role)) {
                    appendRecord(
                        SLOT_RECORD,
                        member.logicalKey,
                        slot.role.name,
                        slot.ownerPath.encodeList(),
                        slot.methodName,
                        slot.propertyName,
                        slot.genericArity.toString(),
                        slot.returnType,
                        slot.parameterTypes.encodeList(),
                    )
                }
            }
            for (intersection in contract.intersections.sortedBy(DotNetCSharpIntersectionContract::logicalKey)) {
                appendRecord(
                    INTERSECTION_RECORD,
                    contract.logicalKey,
                    intersection.logicalKey,
                    intersection.kind.name,
                    intersection.sourceName,
                    intersection.authoringView.name,
                    intersection.contributingLogicalMemberKeys.encodeList(),
                )
                for (slot in intersection.slots.sortedBy(DotNetCSharpMethodLocator::role)) {
                    appendRecord(
                        INTERSECTION_SLOT_RECORD,
                        intersection.logicalKey,
                        slot.role.name,
                        slot.ownerPath.encodeList(),
                        slot.methodName,
                        slot.propertyName,
                        slot.genericArity.toString(),
                        slot.returnType,
                        slot.parameterTypes.encodeList(),
                    )
                }
            }
        }
    }

    fun decode(encoded: String): DotNetCSharpImplementationManifest {
        data class PendingMember(
            val interfaceKey: String,
            val logicalKey: String,
            val kind: DotNetCSharpMemberKind,
            val sourceName: String,
            val authoringView: DotNetCSharpInterfaceView,
            val defaultKind: DotNetCSharpDefaultKind,
            val semanticBodyView: DotNetCSharpInterfaceView?,
        )
        data class PendingIntersection(
            val interfaceKey: String,
            val logicalKey: String,
            val kind: DotNetCSharpMemberKind,
            val sourceName: String,
            val authoringView: DotNetCSharpInterfaceView,
            val contributingLogicalMemberKeys: List<String>,
        )

        val records = encoded.lineSequence()
            .filter(String::isNotEmpty)
            .map(::decodeRecord)
            .toList()
        val header = records.singleOrNull { it.first == HEADER_RECORD }
            ?: error("C# implementation manifest must contain exactly one header")
        require(header.second.size == 3) { "C# implementation manifest header has invalid arity" }
        val schemaVersion = header.second[0]?.toIntOrNull()
            ?: error("C# implementation manifest has no numeric schema version")
        require(schemaVersion == CURRENT_SCHEMA_VERSION) {
            "Unsupported C# implementation manifest schema '$schemaVersion'"
        }
        val assemblyName = requireNotNull(header.second[1]) {
            "C# implementation manifest has no assembly name"
        }
        val targetProfile = requireNotNull(header.second[2]) {
            "C# implementation manifest has no target profile"
        }
        require(targetProfile in DotNetTarget.entries.map(DotNetTarget::flagValue)) {
            "C# implementation manifest has unknown target profile '$targetProfile'"
        }

        val interfaceRecords = linkedMapOf<String, DotNetCSharpInterfaceContract>()
        for (record in records.filter { it.first == INTERFACE_RECORD }) {
            val fields = record.second
            require(fields.size == 7) { "C# implementation interface record has invalid arity" }
            val logicalKey = requireNotNull(fields[0]) { "C# implementation interface has no logical key" }
            val parameters = requireNotNull(fields[4]).decodeList().map { encodedParameter ->
                val components = encodedParameter.split(TYPE_PARAMETER_SEPARATOR)
                require(components.size == 2) {
                    "C# implementation interface '$logicalKey' has an invalid type parameter"
                }
                DotNetCSharpTypeParameter(
                    components[0],
                    enumValueOf<DotNetCSharpTypeParameterVariance>(components[1]),
                )
            }
            val supported = requireNotNull(fields[5]).toBooleanStrict()
            require(interfaceRecords.put(
                logicalKey,
                DotNetCSharpInterfaceContract(
                    logicalKey = logicalKey,
                    canonicalOwnerPath = requireNotNull(fields[1]).decodeList(),
                    declaredOwnerPath = requireNotNull(fields[2]).decodeList(),
                    exactOwnerPath = fields[3]?.decodeList(),
                    typeParameters = parameters,
                    sourceAuthoringSupported = supported,
                    unsupportedReasons = requireNotNull(fields[6]).decodeList(),
                    members = emptyList(),
                    intersections = emptyList(),
                )
            ) == null) {
                "Duplicate C# implementation interface '$logicalKey'"
            }
        }

        val pendingMembers = linkedMapOf<String, PendingMember>()
        for (record in records.filter { it.first == MEMBER_RECORD }) {
            val fields = record.second
            require(fields.size == 7) { "C# implementation member record has invalid arity" }
            val memberKey = requireNotNull(fields[1]) { "C# implementation member has no logical key" }
            val pending = PendingMember(
                interfaceKey = requireNotNull(fields[0]) {
                    "C# implementation member '$memberKey' has no interface key"
                },
                logicalKey = memberKey,
                kind = enumValueOf(requireNotNull(fields[2])),
                sourceName = requireNotNull(fields[3]),
                authoringView = enumValueOf(requireNotNull(fields[4])),
                defaultKind = enumValueOf(requireNotNull(fields[5])),
                semanticBodyView = fields[6]?.let { enumValueOf<DotNetCSharpInterfaceView>(it) },
            )
            require(pendingMembers.put(memberKey, pending) == null) {
                "Duplicate C# implementation member '$memberKey'"
            }
        }

        val slotsByMember = linkedMapOf<String, MutableList<DotNetCSharpMethodLocator>>()
        for (record in records.filter { it.first == SLOT_RECORD }) {
            val fields = record.second
            require(fields.size == 8) { "C# implementation slot record has invalid arity" }
            val memberKey = requireNotNull(fields[0]) { "C# implementation slot has no member key" }
            val locator = DotNetCSharpMethodLocator(
                role = enumValueOf(requireNotNull(fields[1])),
                ownerPath = requireNotNull(fields[2]).decodeList(),
                methodName = requireNotNull(fields[3]),
                propertyName = fields[4],
                genericArity = requireNotNull(fields[5]).toInt(),
                returnType = requireNotNull(fields[6]),
                parameterTypes = requireNotNull(fields[7]).decodeList(),
            )
            val slots = slotsByMember.getOrPut(memberKey, ::mutableListOf)
            require(slots.none { it.role == locator.role }) {
                "Duplicate ${locator.role.name.lowercase()} slot for C# implementation member '$memberKey'"
            }
            slots += locator
        }

        for (entry in pendingMembers) {
            val memberKey = entry.key
            val pending = entry.value
            val owner = interfaceRecords[pending.interfaceKey]
                ?: error("C# implementation member '$memberKey' names an unknown interface")
            val slots = slotsByMember.remove(memberKey).orEmpty().sortedBy(DotNetCSharpMethodLocator::role)
            validateMember(pending.defaultKind, pending.semanticBodyView, slots, targetProfile, memberKey)
            interfaceRecords[pending.interfaceKey] = owner.copy(
                members = owner.members + DotNetCSharpMemberContract(
                    logicalKey = memberKey,
                    kind = pending.kind,
                    sourceName = pending.sourceName,
                    authoringView = pending.authoringView,
                    defaultKind = pending.defaultKind,
                    semanticBodyView = pending.semanticBodyView,
                    slots = slots,
                )
            )
        }
        require(slotsByMember.isEmpty()) {
            "C# implementation manifest contains slots for unknown members: ${slotsByMember.keys.sorted()}"
        }

        val pendingIntersections = linkedMapOf<String, PendingIntersection>()
        for (record in records.filter { it.first == INTERSECTION_RECORD }) {
            val fields = record.second
            require(fields.size == 6) { "C# implementation intersection record has invalid arity" }
            val intersectionKey = requireNotNull(fields[1]) {
                "C# implementation intersection has no logical key"
            }
            val contributors = requireNotNull(fields[5]).decodeList()
            require(
                contributors.size >= 2 &&
                        contributors.all(String::isNotEmpty) &&
                        contributors == contributors.distinct().sorted()
            ) {
                "C# implementation intersection '$intersectionKey' has invalid contributors"
            }
            val pending = PendingIntersection(
                interfaceKey = requireNotNull(fields[0]) {
                    "C# implementation intersection '$intersectionKey' has no interface key"
                },
                logicalKey = intersectionKey,
                kind = enumValueOf(requireNotNull(fields[2])),
                sourceName = requireNotNull(fields[3]),
                authoringView = enumValueOf(requireNotNull(fields[4])),
                contributingLogicalMemberKeys = contributors,
            )
            require(pendingIntersections.put(intersectionKey, pending) == null) {
                "Duplicate C# implementation intersection '$intersectionKey'"
            }
        }
        val slotsByIntersection = linkedMapOf<String, MutableList<DotNetCSharpMethodLocator>>()
        for (record in records.filter { it.first == INTERSECTION_SLOT_RECORD }) {
            val fields = record.second
            require(fields.size == 8) {
                "C# implementation intersection slot record has invalid arity"
            }
            val intersectionKey = requireNotNull(fields[0]) {
                "C# implementation intersection slot has no intersection key"
            }
            val locator = DotNetCSharpMethodLocator(
                role = enumValueOf(requireNotNull(fields[1])),
                ownerPath = requireNotNull(fields[2]).decodeList(),
                methodName = requireNotNull(fields[3]),
                propertyName = fields[4],
                genericArity = requireNotNull(fields[5]).toInt(),
                returnType = requireNotNull(fields[6]),
                parameterTypes = requireNotNull(fields[7]).decodeList(),
            )
            require(
                locator.role == DotNetCSharpSlotRole.DECLARED ||
                        locator.role == DotNetCSharpSlotRole.EXACT
            ) {
                "C# implementation intersection '$intersectionKey' has a non-typed slot"
            }
            val slots = slotsByIntersection.getOrPut(intersectionKey, ::mutableListOf)
            require(slots.none { it.role == locator.role }) {
                "Duplicate ${locator.role.name.lowercase()} slot for C# implementation " +
                        "intersection '$intersectionKey'"
            }
            slots += locator
        }
        for (entry in pendingIntersections) {
            val intersectionKey = entry.key
            val pending = entry.value
            val owner = interfaceRecords[pending.interfaceKey]
                ?: error("C# implementation intersection '$intersectionKey' names an unknown interface")
            val slots = slotsByIntersection.remove(intersectionKey)
                .orEmpty()
                .sortedBy(DotNetCSharpMethodLocator::role)
            require(slots.any { slot -> slot.role.toManifestView() == pending.authoringView }) {
                "C# implementation intersection '$intersectionKey' has no authoring-view slot"
            }
            interfaceRecords[pending.interfaceKey] = owner.copy(
                intersections = owner.intersections + DotNetCSharpIntersectionContract(
                    logicalKey = intersectionKey,
                    kind = pending.kind,
                    sourceName = pending.sourceName,
                    authoringView = pending.authoringView,
                    contributingLogicalMemberKeys = pending.contributingLogicalMemberKeys,
                    slots = slots,
                )
            )
        }
        require(slotsByIntersection.isEmpty()) {
            "C# implementation manifest contains slots for unknown intersections: " +
                    slotsByIntersection.keys.sorted()
        }
        for (contract in interfaceRecords.values) {
            require(contract.canonicalOwnerPath.isNotEmpty() && contract.declaredOwnerPath.isNotEmpty()) {
                "C# implementation interface '${contract.logicalKey}' has an empty physical owner"
            }
            require(contract.sourceAuthoringSupported == contract.unsupportedReasons.isEmpty()) {
                "C# implementation interface '${contract.logicalKey}' has inconsistent support status"
            }
        }
        return DotNetCSharpImplementationManifest(
            schemaVersion = schemaVersion,
            assemblyName = assemblyName,
            targetProfile = targetProfile,
            interfaces = interfaceRecords.values.map { contract ->
                contract.copy(
                    members = contract.members.sortedBy(DotNetCSharpMemberContract::logicalKey),
                    intersections = contract.intersections
                        .sortedBy(DotNetCSharpIntersectionContract::logicalKey),
                )
            },
        )
    }

    fun encodeAssemblyMetadata(
        manifest: DotNetCSharpImplementationManifest,
    ): List<Pair<String, String>> {
        val bytes = encode(manifest).toByteArray(Charsets.UTF_8)
        val payload = Base64.getEncoder().encodeToString(bytes)
        val chunks = payload.chunked(CHUNK_CHARACTER_COUNT)
        val marker = listOf(
            manifest.schemaVersion.toString(),
            chunks.size.toString(),
            bytes.sha256Hex(),
        ).joinToString(":")
        return buildList {
            add(ASSEMBLY_METADATA_KEY to marker)
            chunks.forEachIndexed { index, chunk ->
                add("$ASSEMBLY_METADATA_KEY.${index.toString().padStart(4, '0')}" to chunk)
            }
        }
    }

    fun decodeAssemblyMetadata(
        metadata: Iterable<Pair<String, String>>,
    ): DotNetCSharpImplementationManifest {
        val relevant = metadata.filter { entry ->
            entry.first == ASSEMBLY_METADATA_KEY ||
                    entry.first.startsWith("$ASSEMBLY_METADATA_KEY.")
        }
        val byKey = linkedMapOf<String, String>()
        for (entry in relevant) {
            val key = entry.first
            val value = entry.second
            require(byKey.put(key, value) == null) {
                "Duplicate C# implementation assembly metadata key '$key'"
            }
        }
        val marker = byKey.remove(ASSEMBLY_METADATA_KEY)
            ?: error("Assembly has no C# implementation manifest")
        val markerFields = marker.split(':')
        require(markerFields.size == 3) { "C# implementation manifest marker is malformed" }
        val schemaVersion = markerFields[0].toIntOrNull()
            ?: error("C# implementation manifest marker has no numeric schema")
        require(schemaVersion == CURRENT_SCHEMA_VERSION) {
            "Unsupported C# implementation manifest schema '$schemaVersion'"
        }
        val chunkCount = markerFields[1].toIntOrNull()
            ?: error("C# implementation manifest marker has no numeric chunk count")
        require(chunkCount >= 1) { "C# implementation manifest must contain at least one chunk" }
        val payload = buildString {
            repeat(chunkCount) { index ->
                val key = "$ASSEMBLY_METADATA_KEY.${index.toString().padStart(4, '0')}"
                append(byKey.remove(key) ?: error("C# implementation manifest is missing chunk '$key'"))
            }
        }
        require(byKey.isEmpty()) {
            "C# implementation manifest has unexpected chunks: ${byKey.keys.sorted()}"
        }
        val bytes = try {
            Base64.getDecoder().decode(payload)
        } catch (failure: IllegalArgumentException) {
            throw IllegalArgumentException("C# implementation manifest payload is not base64", failure)
        }
        require(bytes.sha256Hex() == markerFields[2]) {
            "C# implementation manifest payload hash does not match its marker"
        }
        return decode(bytes.toString(Charsets.UTF_8)).also { manifest ->
            require(manifest.schemaVersion == schemaVersion) {
                "C# implementation manifest marker and payload schemas differ"
            }
        }
    }

    private fun validateMember(
        defaultKind: DotNetCSharpDefaultKind,
        semanticBodyView: DotNetCSharpInterfaceView?,
        slots: List<DotNetCSharpMethodLocator>,
        targetProfile: String,
        memberKey: String,
    ) {
        require(slots.any { it.role == DotNetCSharpSlotRole.ERASED }) {
            "C# implementation member '$memberKey' has no erased identity slot"
        }
        require(slots.any {
            it.role == DotNetCSharpSlotRole.DECLARED || it.role == DotNetCSharpSlotRole.EXACT
        }) {
            "C# implementation member '$memberKey' has no strongly typed slot"
        }
        when (defaultKind) {
            DotNetCSharpDefaultKind.ABSTRACT -> {
                require(semanticBodyView == null && slots.none { it.role == DotNetCSharpSlotRole.HELPER }) {
                    "Abstract C# implementation member '$memberKey' has default-body metadata"
                }
            }
            DotNetCSharpDefaultKind.PORTABLE_HELPER -> {
                require(targetProfile != DotNetTarget.NET10_0.flagValue && semanticBodyView == null) {
                    "Portable C# implementation member '$memberKey' has inconsistent profile/body metadata"
                }
                require(slots.any { it.role == DotNetCSharpSlotRole.HELPER }) {
                    "Portable C# implementation member '$memberKey' has no helper"
                }
            }
            DotNetCSharpDefaultKind.DIM_WITH_HELPER -> {
                require(targetProfile == DotNetTarget.NET10_0.flagValue && semanticBodyView != null) {
                    "DIM C# implementation member '$memberKey' has inconsistent profile/body metadata"
                }
                require(slots.any { it.role == DotNetCSharpSlotRole.HELPER }) {
                    "DIM C# implementation member '$memberKey' has no compatibility helper"
                }
            }
        }
    }

    private fun StringBuilder.appendRecord(tag: String, vararg fields: String?) {
        append(tag)
        fields.forEach { field ->
            append('\t')
            append(field?.let(::encodeField) ?: NULL_FIELD)
        }
        append('\n')
    }

    private fun decodeRecord(line: String): Pair<String, List<String?>> {
        val components = line.split('\t')
        require(components.isNotEmpty() && components[0] in setOf(
            HEADER_RECORD,
            INTERFACE_RECORD,
            MEMBER_RECORD,
            SLOT_RECORD,
            INTERSECTION_RECORD,
            INTERSECTION_SLOT_RECORD,
        )) {
            "Unknown C# implementation manifest record '${components.firstOrNull()}'"
        }
        return components[0] to components.drop(1).map { field ->
            if (field == NULL_FIELD) null else decodeField(field)
        }
    }

    private fun encodeField(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun decodeField(value: String): String =
        Base64.getUrlDecoder().decode(value).toString(Charsets.UTF_8)

    private fun List<String>.encodeList(): String = joinToString(LIST_SEPARATOR)

    private fun String.decodeList(): List<String> =
        if (isEmpty()) emptyList() else split(LIST_SEPARATOR)

    private fun ByteArray.sha256Hex(): String =
        MessageDigest.getInstance("SHA-256").digest(this)
            .joinToString("") { byte -> "%02x".format(byte) }
}

internal fun collectDotNetCSharpImplementationManifest(
    assemblyName: String,
    target: DotNetTarget,
    files: Set<IrFile>,
    genericInterfaces: Map<IrClass, DotNetGenericInterfaceInfo>,
    externalLibraries: List<DotNetExternalLibrary>,
    availableFunctions: Map<IrSimpleFunction, DotNetIlFunctionInfo>,
    typeMapper: DotNetIlTypeMapper,
    preLoweringDeclarationKeys: Map<IrDeclaration, String>,
    interfaceDefaultImplementations:
            Map<IrSimpleFunction, DotNetLoweredInterfaceDefaultImplementation>,
    genericInterfaceDefaults: List<DotNetLoweredGenericInterfaceDefault>,
    genericInterfaceIntersectionSlots: List<DotNetGenericInterfaceIntersectionSlot>,
): DotNetCSharpImplementationManifest {
    fun IrSimpleFunction.memberKind(): DotNetCSharpMemberKind {
        val property = correspondingPropertySymbol?.owner ?: return DotNetCSharpMemberKind.METHOD
        return if (property.getter == this) {
            DotNetCSharpMemberKind.PROPERTY_GETTER
        } else {
            DotNetCSharpMemberKind.PROPERTY_SETTER
        }
    }

    fun locator(
        role: DotNetCSharpSlotRole,
        function: IrSimpleFunction,
        info: DotNetIlFunctionInfo,
        physicalMethodName: String,
        propertyName: String?,
    ): DotNetCSharpMethodLocator = DotNetCSharpMethodLocator(
        role = role,
        ownerPath = info.owner.physicalPathComponents(),
        methodName = physicalMethodName,
        propertyName = propertyName,
        genericArity = function.typeParameters.size,
        returnType = info.signature.returnType.nameInSignature,
        parameterTypes = info.signature.parameterTypes
            .drop(if (info.signature.hasThis) 1 else 0)
            .map { type -> type.nameInSignature },
    )

    val interfaces = genericInterfaces.entries
        .asSequence()
        .filter { entry ->
            entry.key.fileOrNull in files &&
                    entry.key.visibility == DescriptorVisibilities.PUBLIC &&
                    preLoweringDeclarationKeys.containsKey(entry.key)
        }
        .map { entry ->
            val irClass = entry.key
            val interfaceInfo = entry.value
            val interfaceKey = checkNotNull(preLoweringDeclarationKeys[irClass]) {
                "Public generic interface has no pre-lowering logical key"
            }
            val directSuperInterfaces = irClass.dotNetDirectInterfaceTypes()
            val unsupportedReasons = buildList {
                directSuperInterfaces.forEach { superType ->
                    val superInterface = (superType.classifier as? IrClassSymbol)?.owner
                    val isSameAssemblyGenericParent =
                        superInterface in genericInterfaces && superInterface?.fileOrNull in files
                    val externalParentAssembly = superInterface
                        ?.let(typeMapper::genericInterfaceInfoOrNull)
                        ?.canonicalClassInfo
                        ?.assemblyName
                    val isExternalKotlinLibraryParent = externalLibraries.any { library ->
                        library.artifact.assemblyName.equals(externalParentAssembly, ignoreCase = true)
                    }
                    if (!isSameAssemblyGenericParent && !isExternalKotlinLibraryParent) {
                        add(
                            "non-generic or non-library inherited interface contracts are not supported " +
                                    "by the first C# authoring schema"
                        )
                    }
                }
            }
            fun canonicalPropertyName(source: IrSimpleFunction, fallbackMethodName: String): String? {
                val property = source.correspondingPropertySymbol?.owner ?: return null
                val getterMethodName = property.getter?.let { getter ->
                    availableFunctions[getter]?.let { info ->
                        info.physicalMethodName ?: getter.dotNetIlMethodName()
                    }
                }
                return dotNetPhysicalPropertyName(
                    property.name.asString(),
                    getterMethodName ?: fallbackMethodName,
                )
            }

            fun typedPropertyName(
                source: IrSimpleFunction,
                memberView: DotNetGenericInterfaceMemberView,
                fallbackMethodName: String,
            ): String? {
                val property = source.correspondingPropertySymbol?.owner ?: return null
                val getter = property.getter
                    ?.takeUnless { accessor -> accessor.isFakeOverride }
                    ?.takeIf { accessor ->
                        memberView in typeMapper.genericInterfaceMemberViews(accessor, irClass)
                    }
                val getterMethodName = getter?.let { accessor ->
                    val getterDefault = genericInterfaceDefaults.singleOrNull { it.source == accessor }
                    getterDefault
                        ?.let { typeMapper.genericInterfaceTypedMethodName(accessor) }
                        ?: accessor.dotNetExceptionCarrierMethodNameOrNull()
                        ?: accessor.dotNetIlMethodName()
                }
                return dotNetPhysicalPropertyName(
                    property.name.asString(),
                    getterMethodName ?: fallbackMethodName,
                )
            }

            val members = irClass.declarations.flatMap { declaration ->
                when (declaration) {
                    is IrSimpleFunction -> listOf(declaration)
                    is IrProperty -> listOfNotNull(declaration.getter, declaration.setter)
                    else -> emptyList()
                }
            }.asSequence()
                .filter { member ->
                    !member.isFakeOverride &&
                            member.visibility == DescriptorVisibilities.PUBLIC &&
                            preLoweringDeclarationKeys.containsKey(member) &&
                            !member.origin.isDotNetGenericInterfaceDefaultPhysicalMethod
                }
                .map { source ->
                    val memberKey = checkNotNull(preLoweringDeclarationKeys[source])
                    val genericDefault = genericInterfaceDefaults.singleOrNull { it.source == source }
                    val authoringMemberView = genericDefault?.canonicalView
                        ?: typeMapper.genericInterfaceMemberView(source, irClass)
                    val authoringView = authoringMemberView.toManifestView()
                    val canonicalInfo = checkNotNull(availableFunctions[source]) {
                        "C# implementation manifest source member did not survive physical emission"
                    }
                    val canonicalMethodName =
                        canonicalInfo.physicalMethodName ?: source.dotNetIlMethodName()
                    val slots = buildList {
                        add(locator(
                            DotNetCSharpSlotRole.ERASED,
                            source,
                            canonicalInfo,
                            canonicalMethodName,
                            canonicalPropertyName(source, canonicalMethodName),
                        ))
                        for (memberView in typeMapper.genericInterfaceMemberViews(source, irClass)) {
                            val physicalMember = when {
                                genericDefault == null -> source
                                genericDefault.canonicalView == memberView -> genericDefault.canonicalBody
                                else -> checkNotNull(genericDefault.typedAdapters[memberView])
                            }
                            val signatureMapper = typeMapper.genericInterfaceSignatureView(memberView)
                            val physicalMethodName = genericDefault
                                ?.let { typeMapper.genericInterfaceTypedMethodName(source) }
                                ?: source.dotNetExceptionCarrierMethodNameOrNull()
                                ?: source.dotNetIlMethodName()
                            val owner = checkNotNull(interfaceInfo.classInfo(memberView.physicalView))
                            val info = DotNetIlFunctionInfo(
                                owner,
                                physicalMember.dotNetSignature(signatureMapper),
                                physicalMethodName,
                            )
                            add(locator(
                                memberView.toManifestSlotRole(),
                                physicalMember,
                                info,
                                physicalMethodName,
                                typedPropertyName(source, memberView, physicalMethodName),
                            ))
                        }
                        interfaceDefaultImplementations[source]?.let { lowered ->
                            val helperInfo = checkNotNull(availableFunctions[lowered.helper]) {
                                "C# implementation manifest default helper did not survive physical emission"
                            }
                            val helperMethodName =
                                helperInfo.physicalMethodName ?: lowered.helper.dotNetIlMethodName()
                            add(locator(
                                DotNetCSharpSlotRole.HELPER,
                                lowered.helper,
                                helperInfo,
                                helperMethodName,
                                propertyName = null,
                            ))
                        }
                    }
                    val defaultImplementation = interfaceDefaultImplementations[source]
                    val defaultKind = when (defaultImplementation?.bodyPlacement) {
                        null -> DotNetCSharpDefaultKind.ABSTRACT
                        DotNetInterfaceDefaultBodyPlacement.HELPER_ONLY ->
                            DotNetCSharpDefaultKind.PORTABLE_HELPER
                        DotNetInterfaceDefaultBodyPlacement.DIM_WITH_HELPER ->
                            DotNetCSharpDefaultKind.DIM_WITH_HELPER
                    }
                    DotNetCSharpMemberContract(
                        logicalKey = memberKey,
                        kind = source.memberKind(),
                        sourceName = source.correspondingPropertySymbol?.owner?.name?.asString()
                            ?: source.name.asString(),
                        authoringView = authoringView,
                        defaultKind = defaultKind,
                        semanticBodyView = if (defaultKind == DotNetCSharpDefaultKind.DIM_WITH_HELPER) {
                            authoringView
                        } else {
                            null
                        },
                        slots = slots,
                    )
                }
                .sortedBy(DotNetCSharpMemberContract::logicalKey)
                .toList()
            val intersections = genericInterfaceIntersectionSlots
                .asSequence()
                .filter { slot -> slot.owner == irClass }
                .groupBy { slot -> slot.signatureSource.symbol }
                .values
                .map { intersectionSlots ->
                    val source = intersectionSlots.first().signatureSource
                    require(intersectionSlots.all { slot -> slot.signatureSource == source })
                    val contributorKeys = intersectionSlots
                        .flatMap { slot -> slot.contributingMembers }
                        .map { contributor ->
                            preLoweringDeclarationKeys[contributor]
                                ?: contributor.dotNetLibraryAbiKeyOrNull("F")
                                ?: error(
                                    "C# implementation intersection contributor has no logical identity"
                                )
                        }
                        .distinct()
                        .sorted()
                    require(contributorKeys.size >= 2) {
                        "C# implementation intersection has fewer than two logical contributors"
                    }
                    val kind = source.memberKind()
                    val intersectionKey = buildString {
                        append("X:")
                        append(interfaceKey)
                        append(':')
                        append(kind.name)
                        append(':')
                        append(
                            DotNetLibraryAbiCodec.logicalIdentityDigest(
                                contributorKeys.joinToString("\u0000")
                            )
                        )
                    }
                    val slots = intersectionSlots.map { intersection ->
                        val memberView = intersection.memberView
                        val signatureMapper = typeMapper.genericInterfaceSignatureView(memberView)
                        val owner = checkNotNull(interfaceInfo.classInfo(memberView.physicalView))
                        val info = DotNetIlFunctionInfo(
                            owner,
                            source.dotNetSignature(signatureMapper),
                            intersection.physicalMethodName,
                        )
                        locator(
                            memberView.toManifestSlotRole(),
                            source,
                            info,
                            intersection.physicalMethodName,
                            typedPropertyName(
                                source,
                                memberView,
                                intersection.physicalMethodName,
                            ),
                        )
                    }.sortedBy(DotNetCSharpMethodLocator::role)
                    val authoringView = if (
                        slots.any { slot -> slot.role == DotNetCSharpSlotRole.EXACT }
                    ) {
                        DotNetCSharpInterfaceView.EXACT
                    } else {
                        DotNetCSharpInterfaceView.DECLARED
                    }
                    DotNetCSharpIntersectionContract(
                        logicalKey = intersectionKey,
                        kind = kind,
                        sourceName = source.correspondingPropertySymbol?.owner?.name?.asString()
                            ?: source.name.asString(),
                        authoringView = authoringView,
                        contributingLogicalMemberKeys = contributorKeys,
                        slots = slots,
                    )
                }
                .sortedBy(DotNetCSharpIntersectionContract::logicalKey)
            DotNetCSharpInterfaceContract(
                logicalKey = interfaceKey,
                canonicalOwnerPath = interfaceInfo.canonicalClassInfo.physicalPathComponents(),
                declaredOwnerPath = interfaceInfo.declaredClassInfo.physicalPathComponents(),
                exactOwnerPath = interfaceInfo.exactClassInfo?.physicalPathComponents(),
                typeParameters = irClass.typeParameters.map { parameter ->
                    DotNetCSharpTypeParameter(
                        parameter.name.asString(),
                        when (parameter.variance) {
                            Variance.INVARIANT -> DotNetCSharpTypeParameterVariance.INVARIANT
                            Variance.IN_VARIANCE -> DotNetCSharpTypeParameterVariance.IN
                            Variance.OUT_VARIANCE -> DotNetCSharpTypeParameterVariance.OUT
                        },
                    )
                },
                sourceAuthoringSupported = unsupportedReasons.isEmpty(),
                unsupportedReasons = unsupportedReasons,
                members = members,
                intersections = intersections,
            )
        }
        .sortedBy(DotNetCSharpInterfaceContract::logicalKey)
        .toList()
    return DotNetCSharpImplementationManifest(
        schemaVersion = DotNetCSharpImplementationManifestCodec.CURRENT_SCHEMA_VERSION,
        assemblyName = assemblyName,
        targetProfile = target.flagValue,
        interfaces = interfaces,
    )
}

private fun DotNetGenericInterfaceMemberView.toManifestView(): DotNetCSharpInterfaceView = when (this) {
    DotNetGenericInterfaceMemberView.DECLARED -> DotNetCSharpInterfaceView.DECLARED
    DotNetGenericInterfaceMemberView.EXACT -> DotNetCSharpInterfaceView.EXACT
}

private fun DotNetGenericInterfaceMemberView.toManifestSlotRole(): DotNetCSharpSlotRole = when (this) {
    DotNetGenericInterfaceMemberView.DECLARED -> DotNetCSharpSlotRole.DECLARED
    DotNetGenericInterfaceMemberView.EXACT -> DotNetCSharpSlotRole.EXACT
}

private fun DotNetCSharpSlotRole.toManifestView(): DotNetCSharpInterfaceView = when (this) {
    DotNetCSharpSlotRole.DECLARED -> DotNetCSharpInterfaceView.DECLARED
    DotNetCSharpSlotRole.EXACT -> DotNetCSharpInterfaceView.EXACT
    DotNetCSharpSlotRole.ERASED,
    DotNetCSharpSlotRole.HELPER -> error("A canonical/helper slot has no typed C# authoring view")
}
