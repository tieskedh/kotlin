/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.backend.common.lower.SpecialBridgeDefaultValueKind
import org.jetbrains.kotlin.backend.common.lower.SpecialBridgeMethods
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
import org.jetbrains.kotlin.ir.util.isInterface
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
    val logicalIdentityScheme: String,
    val interfaces: List<DotNetCSharpInterfaceContract>,
)

data class DotNetCSharpInterfaceContract(
    val logicalKey: String,
    val canonicalOwnerPath: List<String>,
    val declaredOwnerPath: List<String>?,
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
    CANONICAL,
    DECLARED,
    EXACT,
}

enum class DotNetCSharpSlotRole {
    CANONICAL,
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
    val wrongShapePolicy: DotNetCSharpWrongShapePolicy?,
    val slots: List<DotNetCSharpMethodLocator>,
)

data class DotNetCSharpWrongShapePolicy(
    val checkedParameterCount: Int,
    val fallback: DotNetCSharpWrongShapeFallback,
    val fallbackParameterIndex: Int?,
)

enum class DotNetCSharpWrongShapeFallback {
    FALSE,
    NULL,
    MINUS_ONE,
    ARGUMENT,
}

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
    const val CURRENT_SCHEMA_VERSION = 5
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
        require(manifest.schemaVersion == CURRENT_SCHEMA_VERSION) {
            "Unsupported C# implementation manifest schema '${manifest.schemaVersion}'"
        }
        require(manifest.logicalIdentityScheme == DotNetLibraryAbiCodec.LOGICAL_IDENTITY_SCHEME) {
            "Unsupported C# implementation manifest logical identity scheme " +
                    "'${manifest.logicalIdentityScheme}'"
        }
        appendRecord(
            HEADER_RECORD,
            manifest.schemaVersion.toString(),
            manifest.assemblyName,
            manifest.targetProfile,
            manifest.logicalIdentityScheme,
        )
        for (contract in manifest.interfaces.sortedBy(DotNetCSharpInterfaceContract::logicalKey)) {
            requirePublicLogicalKey("C", contract.logicalKey, "interface")
            appendRecord(
                INTERFACE_RECORD,
                contract.logicalKey,
                contract.canonicalOwnerPath.encodeList(),
                contract.declaredOwnerPath?.encodeList(),
                contract.exactOwnerPath?.encodeList(),
                contract.typeParameters.joinToString(LIST_SEPARATOR) { parameter ->
                    parameter.name + TYPE_PARAMETER_SEPARATOR + parameter.variance.name
                },
                contract.sourceAuthoringSupported.toString(),
                contract.unsupportedReasons.encodeList(),
            )
            for (member in contract.members.sortedBy(DotNetCSharpMemberContract::logicalKey)) {
                requirePublicLogicalKey("F", member.logicalKey, "member")
                appendRecord(
                    MEMBER_RECORD,
                    contract.logicalKey,
                    member.logicalKey,
                    member.kind.name,
                    member.sourceName,
                    member.authoringView.name,
                    member.defaultKind.name,
                    member.semanticBodyView?.name,
                    member.wrongShapePolicy?.checkedParameterCount?.toString(),
                    member.wrongShapePolicy?.fallback?.name,
                    member.wrongShapePolicy?.fallbackParameterIndex?.toString(),
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
                require(intersection.logicalKey.startsWith("X:") && intersection.logicalKey.length > 2) {
                    "C# implementation intersection '${intersection.logicalKey}' has no physical identity"
                }
                intersection.contributingLogicalMemberKeys.forEach { contributor ->
                    requirePublicLogicalKey("F", contributor, "intersection contributor")
                }
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
            val wrongShapePolicy: DotNetCSharpWrongShapePolicy?,
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
        require(header.second.size == 4) { "C# implementation manifest header has invalid arity" }
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
        val logicalIdentityScheme = requireNotNull(header.second[3]) {
            "C# implementation manifest has no logical identity scheme"
        }
        require(logicalIdentityScheme == DotNetLibraryAbiCodec.LOGICAL_IDENTITY_SCHEME) {
            "Unsupported C# implementation manifest logical identity scheme '$logicalIdentityScheme'"
        }

        val interfaceRecords = linkedMapOf<String, DotNetCSharpInterfaceContract>()
        for (record in records.filter { it.first == INTERFACE_RECORD }) {
            val fields = record.second
            require(fields.size == 7) { "C# implementation interface record has invalid arity" }
            val logicalKey = requireNotNull(fields[0]) { "C# implementation interface has no logical key" }
            requirePublicLogicalKey("C", logicalKey, "interface")
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
                    declaredOwnerPath = fields[2]?.decodeList(),
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
            require(fields.size == 10) { "C# implementation member record has invalid arity" }
            val memberKey = requireNotNull(fields[1]) { "C# implementation member has no logical key" }
            requirePublicLogicalKey("F", memberKey, "member")
            val wrongShapePolicy = fields[7]?.let { checkedParameterCount ->
                DotNetCSharpWrongShapePolicy(
                    checkedParameterCount = checkedParameterCount.toInt(),
                    fallback = enumValueOf(requireNotNull(fields[8]) {
                        "C# implementation member '$memberKey' has no wrong-shape fallback"
                    }),
                    fallbackParameterIndex = fields[9]?.toInt(),
                )
            }
            require(wrongShapePolicy != null || fields[8] == null && fields[9] == null) {
                "C# implementation member '$memberKey' has an incomplete wrong-shape policy"
            }
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
                wrongShapePolicy = wrongShapePolicy,
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
            validateMember(
                pending.authoringView,
                pending.defaultKind,
                pending.semanticBodyView,
                pending.wrongShapePolicy,
                slots,
                targetProfile,
                memberKey,
            )
            interfaceRecords[pending.interfaceKey] = owner.copy(
                members = owner.members + DotNetCSharpMemberContract(
                    logicalKey = memberKey,
                    kind = pending.kind,
                    sourceName = pending.sourceName,
                    authoringView = pending.authoringView,
                    defaultKind = pending.defaultKind,
                    semanticBodyView = pending.semanticBodyView,
                    wrongShapePolicy = pending.wrongShapePolicy,
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
            require(intersectionKey.startsWith("X:") && intersectionKey.length > 2) {
                "C# implementation intersection '$intersectionKey' has no physical identity"
            }
            val contributors = requireNotNull(fields[5]).decodeList()
            require(
                contributors.size >= 2 &&
                        contributors.all(String::isNotEmpty) &&
                        contributors == contributors.distinct().sorted()
            ) {
                "C# implementation intersection '$intersectionKey' has invalid contributors"
            }
            contributors.forEach { contributor ->
                requirePublicLogicalKey("F", contributor, "intersection contributor")
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
            require(contract.canonicalOwnerPath.isNotEmpty()) {
                "C# implementation interface '${contract.logicalKey}' has an empty canonical owner"
            }
            if (contract.typeParameters.isEmpty()) {
                require(contract.declaredOwnerPath == null && contract.exactOwnerPath == null) {
                    "Non-generic C# implementation interface '${contract.logicalKey}' has a split owner"
                }
                require(contract.members.all { member ->
                    member.authoringView == DotNetCSharpInterfaceView.CANONICAL &&
                            member.slots.any { slot ->
                                slot.role == DotNetCSharpSlotRole.CANONICAL
                            } &&
                            member.slots.none { slot ->
                                slot.role == DotNetCSharpSlotRole.ERASED ||
                                        slot.role == DotNetCSharpSlotRole.DECLARED ||
                                        slot.role == DotNetCSharpSlotRole.EXACT
                            }
                }) {
                    "Non-generic C# implementation interface '${contract.logicalKey}' has split member views"
                }
            } else {
                require(contract.declaredOwnerPath?.isNotEmpty() == true) {
                    "Generic C# implementation interface '${contract.logicalKey}' has no declared owner"
                }
                require(contract.members.all { member ->
                    member.slots.any { slot -> slot.role == DotNetCSharpSlotRole.ERASED } &&
                            member.slots.none { slot ->
                                slot.role == DotNetCSharpSlotRole.CANONICAL
                            }
                }) {
                    "Generic C# implementation interface '${contract.logicalKey}' has a non-erased canonical slot"
                }
            }
            require(contract.sourceAuthoringSupported == contract.unsupportedReasons.isEmpty()) {
                "C# implementation interface '${contract.logicalKey}' has inconsistent support status"
            }
        }
        return DotNetCSharpImplementationManifest(
            schemaVersion = schemaVersion,
            assemblyName = assemblyName,
            targetProfile = targetProfile,
            logicalIdentityScheme = logicalIdentityScheme,
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
        authoringView: DotNetCSharpInterfaceView,
        defaultKind: DotNetCSharpDefaultKind,
        semanticBodyView: DotNetCSharpInterfaceView?,
        wrongShapePolicy: DotNetCSharpWrongShapePolicy?,
        slots: List<DotNetCSharpMethodLocator>,
        targetProfile: String,
        memberKey: String,
    ) {
        require(
            slots.any { slot ->
                slot.role == DotNetCSharpSlotRole.CANONICAL ||
                        slot.role == DotNetCSharpSlotRole.ERASED
            }
        ) {
            "C# implementation member '$memberKey' has no canonical identity slot"
        }
        require(slots.any { slot -> slot.role.toManifestView() == authoringView }) {
            "C# implementation member '$memberKey' has no authoring-view slot"
        }
        wrongShapePolicy?.let { policy ->
            val canonicalSlot = slots.single { slot ->
                slot.role == DotNetCSharpSlotRole.ERASED
            }
            require(
                policy.checkedParameterCount in 1..canonicalSlot.parameterTypes.size
            ) {
                "C# implementation member '$memberKey' has an invalid wrong-shape check count"
            }
            when (policy.fallback) {
                DotNetCSharpWrongShapeFallback.ARGUMENT -> require(
                    policy.fallbackParameterIndex != null &&
                            policy.fallbackParameterIndex in canonicalSlot.parameterTypes.indices &&
                            policy.fallbackParameterIndex >= policy.checkedParameterCount
                ) {
                    "C# implementation member '$memberKey' has an invalid fallback parameter"
                }
                DotNetCSharpWrongShapeFallback.FALSE,
                DotNetCSharpWrongShapeFallback.NULL,
                DotNetCSharpWrongShapeFallback.MINUS_ONE -> require(
                    policy.fallbackParameterIndex == null
                ) {
                    "C# implementation member '$memberKey' has an unexpected fallback parameter"
                }
            }
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

    private fun requirePublicLogicalKey(expectedKind: String, key: String, recordKind: String) {
        require(key.startsWith("$expectedKind:") && key.length > expectedKind.length + 1) {
            "C# implementation $recordKind '$key' is not a Kotlin public declaration identity"
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
    availableClasses: Map<IrClass, DotNetIlClassInfo>,
    genericInterfaces: Map<IrClass, DotNetGenericInterfaceInfo>,
    externalLibraries: List<DotNetExternalLibrary>,
    availableFunctions: Map<IrSimpleFunction, DotNetIlFunctionInfo>,
    typeMapper: DotNetIlTypeMapper,
    preLoweringDeclarationKeys: Map<IrDeclaration, String>,
    interfaceDefaultImplementations:
            Map<IrSimpleFunction, DotNetLoweredInterfaceDefaultImplementation>,
    genericInterfaceDefaults: List<DotNetLoweredGenericInterfaceDefault>,
    genericInterfaceIntersectionSlots: List<DotNetGenericInterfaceIntersectionSlot>,
    wrongShapePolicies: Map<IrSimpleFunction, DotNetCSharpWrongShapePolicy>,
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

    val interfaces = availableClasses.keys
        .asSequence()
        .filter { irClass ->
            irClass.isInterface &&
                    irClass.fileOrNull in files &&
                    irClass.visibility == DescriptorVisibilities.PUBLIC &&
                    preLoweringDeclarationKeys.containsKey(irClass)
        }
        .map { irClass ->
            val interfaceInfo = genericInterfaces[irClass]
            val canonicalClassInfo = availableClasses.getValue(irClass)
            val interfaceKey = checkNotNull(preLoweringDeclarationKeys[irClass]) {
                "Public interface has no pre-lowering logical key"
            }
            val directSuperInterfaces = irClass.dotNetDirectInterfaceTypes()
            val unsupportedReasons = buildList {
                directSuperInterfaces.forEach { superType ->
                    val superInterface = (superType.classifier as? IrClassSymbol)?.owner
                    val isSameAssemblyParent =
                        superInterface in availableClasses && superInterface?.fileOrNull in files
                    val externalParentAssembly = superInterface
                        ?.let(typeMapper::classInfoOrNull)
                        ?.assemblyName
                    val isExternalKotlinLibraryParent = externalLibraries.any { library ->
                        library.artifact.assemblyName.equals(externalParentAssembly, ignoreCase = true)
                    }
                    val isRuntimeManifestParent =
                        superInterface?.let(DotNetRuntimeTypes::supportsCSharpSourceAuthoring) == true
                    if (
                        !isSameAssemblyParent &&
                        !isExternalKotlinLibraryParent &&
                        !isRuntimeManifestParent
                    ) {
                        add(
                            "non-library inherited interface contracts are not supported " +
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
                    val authoringView = if (interfaceInfo == null) {
                        DotNetCSharpInterfaceView.CANONICAL
                    } else {
                        val authoringMemberView = genericDefault?.canonicalView
                            ?: typeMapper.genericInterfaceMemberView(source, irClass)
                        authoringMemberView.toManifestView()
                    }
                    val canonicalInfo = checkNotNull(availableFunctions[source]) {
                        "C# implementation manifest source member did not survive physical emission"
                    }
                    val canonicalMethodName =
                        canonicalInfo.physicalMethodName ?: source.dotNetIlMethodName()
                    val slots = buildList {
                        add(locator(
                            if (interfaceInfo == null) {
                                DotNetCSharpSlotRole.CANONICAL
                            } else {
                                DotNetCSharpSlotRole.ERASED
                            },
                            source,
                            canonicalInfo,
                            canonicalMethodName,
                            canonicalPropertyName(source, canonicalMethodName),
                        ))
                        val memberViews = if (interfaceInfo == null) {
                            emptySet()
                        } else {
                            typeMapper.genericInterfaceMemberViews(source, irClass)
                        }
                        for (memberView in memberViews) {
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
                            val owner = checkNotNull(
                                checkNotNull(interfaceInfo).classInfo(memberView.physicalView)
                            )
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
                        wrongShapePolicy = if (interfaceInfo == null) {
                            null
                        } else {
                            wrongShapePolicies[source]
                        },
                        slots = slots,
                    )
                }
                .sortedBy(DotNetCSharpMemberContract::logicalKey)
                .toList()
            val ownerIntersectionSlots = if (interfaceInfo == null) {
                emptyList()
            } else {
                genericInterfaceIntersectionSlots.filter { slot -> slot.owner == irClass }
            }
            val intersections = ownerIntersectionSlots
                .asSequence()
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
                        val owner = checkNotNull(
                            checkNotNull(interfaceInfo).classInfo(memberView.physicalView)
                        )
                        val info = DotNetIlFunctionInfo(
                            owner,
                            source.dotNetSignature(signatureMapper),
                            intersection.physicalMethodName,
                        )
                        val propertyName = source.correspondingPropertySymbol?.owner?.let { property ->
                            val getterMethodName = ownerIntersectionSlots.singleOrNull { candidate ->
                                candidate.memberView == memberView &&
                                        candidate.signatureSource == property.getter
                            }?.physicalMethodName
                            dotNetPhysicalPropertyName(
                                property.name.asString(),
                                getterMethodName ?: intersection.physicalMethodName,
                            )
                        }
                        locator(
                            memberView.toManifestSlotRole(),
                            source,
                            info,
                            intersection.physicalMethodName,
                            propertyName,
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
                canonicalOwnerPath = canonicalClassInfo.physicalPathComponents(),
                declaredOwnerPath = interfaceInfo?.declaredClassInfo?.physicalPathComponents(),
                exactOwnerPath = interfaceInfo?.exactClassInfo?.physicalPathComponents(),
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
        logicalIdentityScheme = DotNetLibraryAbiCodec.LOGICAL_IDENTITY_SCHEME,
        interfaces = interfaces,
    )
}

/**
 * Builds Kotlin.Runtime's source-authoring contract from the actual common built-in declarations.
 *
 * Runtime IL is hand-written during the bootstrap stage, but its logical identities are not. The
 * same [dotNetLibraryAbiKeyOrNull] path used by compiler-produced libraries computes every class
 * and member key from [DotNetIrMangler]. This registry contributes only the CLR projection.
 */
internal fun collectDotNetRuntimeCSharpImplementationManifest(
    context: DotNetBackendContext,
    target: DotNetTarget,
): DotNetCSharpImplementationManifest {
    val irBuiltIns = context.irBuiltIns
    val runtimeInterfaces = listOf(
        irBuiltIns.iteratorClass.owner,
        irBuiltIns.listIteratorClass.owner,
        irBuiltIns.iterableClass.owner,
        irBuiltIns.collectionClass.owner,
        irBuiltIns.listClass.owner,
    )
    val typeMapper = DotNetIlTypeMapper(
        availableClasses = emptyMap(),
        coreLibrary = target.coreLibrary,
    )
    val sourceMembers = runtimeInterfaces.associateWith { irClass ->
        irClass.declarations.flatMap { declaration ->
            when (declaration) {
                is IrSimpleFunction -> listOf(declaration)
                is IrProperty -> listOfNotNull(declaration.getter, declaration.setter)
                else -> emptyList()
            }
        }.filterNot { function -> function.isFakeOverride }
    }
    val wrongShapePolicies = collectDotNetCSharpWrongShapePolicies(
        context,
        sourceMembers.values.flatten().toSet(),
    )

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
        source: IrSimpleFunction,
        owner: DotNetIlClassInfo,
        signatureMapper: DotNetIlTypeMapper,
        physicalMethodName: String,
    ): DotNetCSharpMethodLocator {
        val signature = source.dotNetSignature(signatureMapper)
        return DotNetCSharpMethodLocator(
            role = role,
            ownerPath = owner.physicalPathComponents(),
            methodName = physicalMethodName,
            propertyName = source.correspondingPropertySymbol?.owner?.let {
                checkNotNull(DotNetRuntimeTypes.genericInterfacePropertyNameOrNull(source)) {
                    "Runtime C# property accessor '${source.name}' has no physical Property name"
                }
            },
            genericArity = source.typeParameters.size,
            returnType = signature.returnType.nameInSignature,
            parameterTypes = signature.parameterTypes
                .drop(if (signature.hasThis) 1 else 0)
                .map { type -> type.nameInSignature },
        )
    }

    val contracts = runtimeInterfaces.map { irClass ->
        val interfaceInfo = checkNotNull(DotNetRuntimeTypes.genericInterfaceInfoFor(irClass)) {
            "Runtime C# interface contract has no physical interface registry entry"
        }
        val interfaceKey = checkNotNull(irClass.dotNetLibraryAbiKeyOrNull("C")) {
            "Runtime C# interface contract has no Kotlin public identity"
        }
        val members = sourceMembers.getValue(irClass).map { source ->
            val memberKey = checkNotNull(source.dotNetLibraryAbiKeyOrNull("F")) {
                "Runtime C# interface member has no Kotlin public identity"
            }
            val canonicalMethodName = checkNotNull(
                DotNetRuntimeTypes.genericInterfaceCanonicalMethodNameOrNull(source)
            ) {
                "Runtime C# interface member '${source.name}' has no canonical physical name"
            }
            val memberViews = typeMapper.genericInterfaceMemberViews(source, irClass)
            val authoringMemberView = typeMapper.genericInterfaceMemberView(source, irClass)
            val slots = buildList {
                add(
                    locator(
                        DotNetCSharpSlotRole.ERASED,
                        source,
                        interfaceInfo.canonicalClassInfo,
                        typeMapper,
                        canonicalMethodName,
                    )
                )
                for (memberView in memberViews) {
                    val owner = checkNotNull(interfaceInfo.classInfo(memberView.physicalView))
                    val typedMethodName = checkNotNull(
                        DotNetRuntimeTypes.genericInterfaceTypedMethodNameOrNull(source)
                    ) {
                        "Runtime C# interface member '${source.name}' has no typed physical name"
                    }
                    add(
                        locator(
                            memberView.toManifestSlotRole(),
                            source,
                            owner,
                            typeMapper.genericInterfaceSignatureView(memberView),
                            typedMethodName,
                        )
                    )
                }
            }
            DotNetCSharpMemberContract(
                logicalKey = memberKey,
                kind = source.memberKind(),
                sourceName = source.correspondingPropertySymbol?.owner?.name?.asString()
                    ?: source.name.asString(),
                authoringView = authoringMemberView.toManifestView(),
                defaultKind = DotNetCSharpDefaultKind.ABSTRACT,
                semanticBodyView = null,
                wrongShapePolicy = wrongShapePolicies[source],
                slots = slots,
            )
        }.sortedBy(DotNetCSharpMemberContract::logicalKey)
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
            sourceAuthoringSupported = true,
            unsupportedReasons = emptyList(),
            members = members,
            intersections = emptyList(),
        )
    }.sortedBy(DotNetCSharpInterfaceContract::logicalKey)

    return DotNetCSharpImplementationManifest(
        schemaVersion = DotNetCSharpImplementationManifestCodec.CURRENT_SCHEMA_VERSION,
        assemblyName = DotNetRuntimeLibrary.ASSEMBLY_NAME,
        targetProfile = target.flagValue,
        logicalIdentityScheme = DotNetLibraryAbiCodec.LOGICAL_IDENTITY_SCHEME,
        interfaces = contracts,
    )
}

internal fun collectDotNetCSharpWrongShapePolicies(
    context: DotNetBackendContext,
    declarations: Set<IrDeclaration>,
): Map<IrSimpleFunction, DotNetCSharpWrongShapePolicy> {
    val specialBridgeMethods = SpecialBridgeMethods(context)
    return declarations.asSequence()
        .filterIsInstance<IrSimpleFunction>()
        .mapNotNull { function ->
            val info = specialBridgeMethods.findSpecialWithOverride(
                function,
                includeSelf = true,
            )?.second ?: return@mapNotNull null
            val fallback = when (info.defaultValueKind) {
                SpecialBridgeDefaultValueKind.FALSE -> DotNetCSharpWrongShapeFallback.FALSE
                SpecialBridgeDefaultValueKind.NULL -> DotNetCSharpWrongShapeFallback.NULL
                SpecialBridgeDefaultValueKind.MINUS_ONE -> DotNetCSharpWrongShapeFallback.MINUS_ONE
                SpecialBridgeDefaultValueKind.SECOND_ARGUMENT ->
                    DotNetCSharpWrongShapeFallback.ARGUMENT
            }
            function to DotNetCSharpWrongShapePolicy(
                checkedParameterCount = info.argumentsToCheck,
                fallback = fallback,
                fallbackParameterIndex = if (
                    info.defaultValueKind == SpecialBridgeDefaultValueKind.SECOND_ARGUMENT
                ) {
                    1
                } else {
                    null
                },
            )
        }
        .toMap()
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
    DotNetCSharpSlotRole.CANONICAL,
    DotNetCSharpSlotRole.ERASED -> DotNetCSharpInterfaceView.CANONICAL
    DotNetCSharpSlotRole.DECLARED -> DotNetCSharpInterfaceView.DECLARED
    DotNetCSharpSlotRole.EXACT -> DotNetCSharpInterfaceView.EXACT
    DotNetCSharpSlotRole.HELPER -> error("A canonical/helper slot has no typed C# authoring view")
}
