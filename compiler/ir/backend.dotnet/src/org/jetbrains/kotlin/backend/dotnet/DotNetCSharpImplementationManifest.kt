/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.backend.dotnet.serialization.DotNetIrMangler
import org.jetbrains.kotlin.backend.dotnet.lower.isDotNetGenericInterfaceDefaultPhysicalMethod
import org.jetbrains.kotlin.config.DotNetTarget
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.util.fileOrNull
import org.jetbrains.kotlin.ir.util.isFakeOverride
import org.jetbrains.kotlin.ir.util.isInterface
import org.jetbrains.kotlin.ir.util.isPublishedApi
import org.jetbrains.kotlin.types.Variance
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Base64

/**
 * DLL-owned contract used by the supported C# source-authoring path for Kotlin interfaces.
 *
 * This is deliberately separate from [DotNetPhysicalDeclaration]. The latter supplements a KLIB
 * and therefore keeps the KLIB authoritative for logical declarations. This manifest must be
 * sufficient together with ordinary CLR metadata in the same DLL: a Roslyn tool must not need the
 * private Kotlin metadata resource to discover physical interface views or implementation obligations.
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
    val erasedOwnerRelativeConstraints: List<DotNetCSharpErasedOwnerRelativeConstraint> = emptyList(),
    val overriddenLogicalMemberKeys: List<String> = emptyList(),
    val slots: List<DotNetCSharpMethodLocator>,
)

/**
 * Kotlin tooling guidance for a logical `R : T` relationship which cannot be placed on a split
 * CLR interface slot. These positional indices must never be reconstructed as executable C#
 * constraints; the generator/analyzer uses them to explain the deliberately weakened boundary.
 */
data class DotNetCSharpErasedOwnerRelativeConstraint(
    val methodTypeParameterIndex: Int,
    val ownerTypeParameterIndex: Int,
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
    val erasedOwnerRelativeConstraints: List<DotNetCSharpErasedOwnerRelativeConstraint> = emptyList(),
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

/** Deterministic versioned records and their self-contained managed-resource carrier. */
object DotNetCSharpImplementationManifestCodec {
    const val CURRENT_SCHEMA_VERSION = 9
    const val MANAGED_RESOURCE_NAME = "Kotlin.CSharpImplementationManifest"

    private const val HEADER_RECORD = "H"
    private const val INTERFACE_RECORD = "I"
    private const val MEMBER_RECORD = "M"
    private const val SLOT_RECORD = "S"
    private const val INTERSECTION_RECORD = "X"
    private const val INTERSECTION_SLOT_RECORD = "Y"
    private const val NULL_FIELD = "~"
    private const val LIST_SEPARATOR = "\u0000"
    private const val TYPE_PARAMETER_SEPARATOR = "\u0001"
    private const val MANAGED_RESOURCE_HEADER_SIZE = 48
    private const val MAXIMUM_MANAGED_RESOURCE_PAYLOAD_BYTES = 4 * 1_024 * 1_024
    private val MANAGED_RESOURCE_MAGIC = "KDNCSM01".toByteArray(Charsets.US_ASCII)

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
            requireKotlinLogicalKey("C", contract.logicalKey, "interface")
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
                requireKotlinLogicalKey("F", member.logicalKey, "member")
                require(
                    member.overriddenLogicalMemberKeys ==
                            member.overriddenLogicalMemberKeys.distinct().sorted() &&
                            member.logicalKey !in member.overriddenLogicalMemberKeys
                ) {
                    "C# implementation member '${member.logicalKey}' has invalid overridden members"
                }
                member.overriddenLogicalMemberKeys.forEach { overridden ->
                    requireKotlinLogicalKey("F", overridden, "overridden member")
                }
                validateErasedOwnerRelativeConstraints(
                    contract,
                    member.authoringView,
                    member.erasedOwnerRelativeConstraints,
                    member.slots,
                    "member '${member.logicalKey}'",
                )
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
                    member.erasedOwnerRelativeConstraints.encodeErasedOwnerRelativeConstraints(),
                    member.overriddenLogicalMemberKeys.encodeList(),
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
                    requireKotlinLogicalKey("F", contributor, "intersection contributor")
                }
                validateErasedOwnerRelativeConstraints(
                    contract,
                    intersection.authoringView,
                    intersection.erasedOwnerRelativeConstraints,
                    intersection.slots,
                    "intersection '${intersection.logicalKey}'",
                )
                appendRecord(
                    INTERSECTION_RECORD,
                    contract.logicalKey,
                    intersection.logicalKey,
                    intersection.kind.name,
                    intersection.sourceName,
                    intersection.authoringView.name,
                    intersection.contributingLogicalMemberKeys.encodeList(),
                    intersection.erasedOwnerRelativeConstraints.encodeErasedOwnerRelativeConstraints(),
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
            val erasedOwnerRelativeConstraints: List<DotNetCSharpErasedOwnerRelativeConstraint>,
            val overriddenLogicalMemberKeys: List<String>,
        )
        data class PendingIntersection(
            val interfaceKey: String,
            val logicalKey: String,
            val kind: DotNetCSharpMemberKind,
            val sourceName: String,
            val authoringView: DotNetCSharpInterfaceView,
            val contributingLogicalMemberKeys: List<String>,
            val erasedOwnerRelativeConstraints: List<DotNetCSharpErasedOwnerRelativeConstraint>,
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
        require(targetProfile in DotNetTarget.entries.map(DotNetTarget::description)) {
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
            requireKotlinLogicalKey("C", logicalKey, "interface")
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
            require(fields.size == 12) { "C# implementation member record has invalid arity" }
            val memberKey = requireNotNull(fields[1]) { "C# implementation member has no logical key" }
            requireKotlinLogicalKey("F", memberKey, "member")
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
                erasedOwnerRelativeConstraints = requireNotNull(fields[10])
                    .decodeErasedOwnerRelativeConstraints(memberKey),
                overriddenLogicalMemberKeys = requireNotNull(fields[11]).decodeList().also {
                    overridden ->
                    require(
                        overridden == overridden.distinct().sorted() &&
                                memberKey !in overridden
                    ) {
                        "C# implementation member '$memberKey' has invalid overridden members"
                    }
                    overridden.forEach { overriddenKey ->
                        requireKotlinLogicalKey("F", overriddenKey, "overridden member")
                    }
                },
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
                    erasedOwnerRelativeConstraints = pending.erasedOwnerRelativeConstraints,
                    overriddenLogicalMemberKeys = pending.overriddenLogicalMemberKeys,
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
            require(fields.size == 7) { "C# implementation intersection record has invalid arity" }
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
                requireKotlinLogicalKey("F", contributor, "intersection contributor")
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
                erasedOwnerRelativeConstraints = requireNotNull(fields[6])
                    .decodeErasedOwnerRelativeConstraints(intersectionKey),
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
                    erasedOwnerRelativeConstraints = pending.erasedOwnerRelativeConstraints,
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
                    "Non-generic C# implementation interface '${contract.logicalKey}' has an alternate runtime owner"
                }
                require(contract.members.all { member ->
                    member.authoringView == DotNetCSharpInterfaceView.CANONICAL &&
                            member.slots.any { slot -> slot.role == DotNetCSharpSlotRole.CANONICAL } &&
                            member.slots.none { slot ->
                                slot.role == DotNetCSharpSlotRole.ERASED ||
                                        slot.role == DotNetCSharpSlotRole.DECLARED ||
                                        slot.role == DotNetCSharpSlotRole.EXACT
                            }
                }) {
                    "Non-generic C# implementation interface '${contract.logicalKey}' has split member views"
                }
            } else {
                require(contract.declaredOwnerPath?.isNotEmpty() == true &&
                        (contract.exactOwnerPath == null || contract.exactOwnerPath.isNotEmpty()) &&
                        contract.declaredOwnerPath != contract.canonicalOwnerPath &&
                        contract.exactOwnerPath != contract.canonicalOwnerPath &&
                        contract.exactOwnerPath != contract.declaredOwnerPath
                ) {
                    "Reified generic C# implementation interface '${contract.logicalKey}' has invalid physical owners"
                }
                require(contract.members.all { member ->
                    val typedRole = when (member.authoringView) {
                        DotNetCSharpInterfaceView.DECLARED -> DotNetCSharpSlotRole.DECLARED
                        DotNetCSharpInterfaceView.EXACT -> DotNetCSharpSlotRole.EXACT
                        DotNetCSharpInterfaceView.CANONICAL -> return@all false
                    }
                    member.slots.any { slot -> slot.role == DotNetCSharpSlotRole.ERASED } &&
                            member.slots.count { slot ->
                                slot.role == DotNetCSharpSlotRole.DECLARED ||
                                        slot.role == DotNetCSharpSlotRole.EXACT
                            } == 1 &&
                            member.slots.any { slot -> slot.role == typedRole } &&
                            member.slots.none { slot -> slot.role == DotNetCSharpSlotRole.CANONICAL } &&
                            (member.authoringView != DotNetCSharpInterfaceView.EXACT ||
                                    contract.exactOwnerPath != null)
                }) {
                    "Reified generic C# implementation interface '${contract.logicalKey}' has an incomplete semantic family"
                }
                require((contract.exactOwnerPath != null) == contract.members.any { member ->
                    member.authoringView == DotNetCSharpInterfaceView.EXACT
                }) {
                    "Reified generic C# implementation interface '${contract.logicalKey}' has an unused or missing exact owner"
                }
            }
            require(contract.sourceAuthoringSupported == contract.unsupportedReasons.isEmpty()) {
                "C# implementation interface '${contract.logicalKey}' has inconsistent support status"
            }
            contract.members.forEach { member ->
                validateErasedOwnerRelativeConstraints(
                    contract,
                    member.authoringView,
                    member.erasedOwnerRelativeConstraints,
                    member.slots,
                    "member '${member.logicalKey}'",
                )
            }
            contract.intersections.forEach { intersection ->
                validateErasedOwnerRelativeConstraints(
                    contract,
                    intersection.authoringView,
                    intersection.erasedOwnerRelativeConstraints,
                    intersection.slots,
                    "intersection '${intersection.logicalKey}'",
                )
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

    fun encodeManagedResource(manifest: DotNetCSharpImplementationManifest): ByteArray {
        val payload = encode(manifest).toByteArray(Charsets.UTF_8)
        require(payload.size <= MAXIMUM_MANAGED_RESOURCE_PAYLOAD_BYTES) {
            "C# implementation manifest payload exceeds the supported size"
        }
        return ByteBuffer.allocate(MANAGED_RESOURCE_HEADER_SIZE + payload.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(MANAGED_RESOURCE_MAGIC)
            .putInt(manifest.schemaVersion)
            .putInt(payload.size)
            .put(payload.sha256())
            .put(payload)
            .array()
    }

    fun decodeManagedResource(resource: ByteArray): DotNetCSharpImplementationManifest {
        require(resource.size >= MANAGED_RESOURCE_HEADER_SIZE) {
            "C# implementation manifest resource is truncated"
        }
        val buffer = ByteBuffer.wrap(resource).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(MANAGED_RESOURCE_MAGIC.size).also(buffer::get)
        require(magic.contentEquals(MANAGED_RESOURCE_MAGIC)) {
            "C# implementation manifest resource has an invalid magic"
        }
        val schemaVersion = buffer.int
        require(schemaVersion == CURRENT_SCHEMA_VERSION) {
            "Unsupported C# implementation manifest schema '$schemaVersion'"
        }
        val payloadSize = buffer.int
        require(payloadSize in 0..MAXIMUM_MANAGED_RESOURCE_PAYLOAD_BYTES) {
            "C# implementation manifest resource has an invalid payload size"
        }
        require(resource.size == MANAGED_RESOURCE_HEADER_SIZE + payloadSize) {
            "C# implementation manifest resource size does not match its header"
        }
        val expectedDigest = ByteArray(32).also(buffer::get)
        val payload = ByteArray(payloadSize).also(buffer::get)
        require(payload.sha256().contentEquals(expectedDigest)) {
            "C# implementation manifest resource payload hash does not match its header"
        }
        val encoded = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(payload))
                .toString()
        } catch (failure: CharacterCodingException) {
            throw IllegalArgumentException(
                "C# implementation manifest resource payload is not valid UTF-8",
                failure,
            )
        }
        return decode(encoded).also { manifest ->
            require(manifest.schemaVersion == schemaVersion) {
                "C# implementation manifest resource and payload schemas differ"
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
                require(targetProfile != DotNetTarget.NET10_0.description && semanticBodyView == null) {
                    "Portable C# implementation member '$memberKey' has inconsistent profile/body metadata"
                }
                require(slots.any { it.role == DotNetCSharpSlotRole.HELPER }) {
                    "Portable C# implementation member '$memberKey' has no helper"
                }
            }
            DotNetCSharpDefaultKind.DIM_WITH_HELPER -> {
                require(targetProfile == DotNetTarget.NET10_0.description && semanticBodyView != null) {
                    "DIM C# implementation member '$memberKey' has inconsistent profile/body metadata"
                }
                require(slots.any { it.role == DotNetCSharpSlotRole.HELPER }) {
                    "DIM C# implementation member '$memberKey' has no compatibility helper"
                }
            }
        }
    }

    private fun requireKotlinLogicalKey(expectedKind: String, key: String, recordKind: String) {
        require(key.startsWith("$expectedKind:") && key.length > expectedKind.length + 1) {
            "C# implementation $recordKind '$key' is not a Kotlin declaration identity"
        }
    }

    private fun List<DotNetCSharpErasedOwnerRelativeConstraint>
            .encodeErasedOwnerRelativeConstraints(): String =
        joinToString(LIST_SEPARATOR) { constraint ->
            constraint.methodTypeParameterIndex.toString() +
                    TYPE_PARAMETER_SEPARATOR +
                    constraint.ownerTypeParameterIndex
        }

    private fun String.decodeErasedOwnerRelativeConstraints(
        recordKey: String,
    ): List<DotNetCSharpErasedOwnerRelativeConstraint> =
        decodeList().map { encodedConstraint ->
            val components = encodedConstraint.split(TYPE_PARAMETER_SEPARATOR)
            require(components.size == 2) {
                "C# implementation record '$recordKey' has an invalid erased owner-relative constraint"
            }
            DotNetCSharpErasedOwnerRelativeConstraint(
                methodTypeParameterIndex = components[0].toInt(),
                ownerTypeParameterIndex = components[1].toInt(),
            )
        }

    private fun validateErasedOwnerRelativeConstraints(
        contract: DotNetCSharpInterfaceContract,
        authoringView: DotNetCSharpInterfaceView,
        constraints: List<DotNetCSharpErasedOwnerRelativeConstraint>,
        slots: List<DotNetCSharpMethodLocator>,
        recordDescription: String,
    ) {
        if (constraints.isEmpty()) return
        require(contract.typeParameters.isNotEmpty()) {
            "C# implementation $recordDescription has an owner-relative constraint on a non-generic interface"
        }
        require(
            constraints == constraints
                .distinct()
                .sortedWith(
                    compareBy(
                        DotNetCSharpErasedOwnerRelativeConstraint::methodTypeParameterIndex,
                        DotNetCSharpErasedOwnerRelativeConstraint::ownerTypeParameterIndex,
                    )
                )
        ) {
            "C# implementation $recordDescription has duplicate or unordered erased owner-relative constraints"
        }
        val authoringRole = when (authoringView) {
            DotNetCSharpInterfaceView.CANONICAL -> DotNetCSharpSlotRole.CANONICAL
            DotNetCSharpInterfaceView.DECLARED -> DotNetCSharpSlotRole.DECLARED
            DotNetCSharpInterfaceView.EXACT -> DotNetCSharpSlotRole.EXACT
        }
        val authoringSlots = slots.filter { slot -> slot.role == authoringRole }
        require(authoringSlots.size == 1) {
            "C# implementation $recordDescription has no unique authoring-view slot"
        }
        val authoringSlot = authoringSlots.single()
        constraints.forEach { constraint ->
            require(constraint.methodTypeParameterIndex in 0 until authoringSlot.genericArity) {
                "C# implementation $recordDescription has an invalid method type-parameter index"
            }
            require(constraint.ownerTypeParameterIndex in contract.typeParameters.indices) {
                "C# implementation $recordDescription has an invalid owner type-parameter index"
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

    private fun ByteArray.sha256(): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(this)
}

internal fun collectDotNetCSharpImplementationManifest(
    assemblyName: String,
    target: DotNetTarget,
    files: Set<IrFile>,
    availableClasses: Map<IrClass, DotNetIlClassInfo>,
    externalLibraries: List<DotNetExternalLibrary>,
    availableFunctions: Map<IrSimpleFunction, DotNetIlFunctionInfo>,
    typeMapper: DotNetIlTypeMapper,
    preLoweringDeclarationKeys: Map<IrDeclaration, String>,
    interfaceDefaultImplementations:
            Map<IrSimpleFunction, DotNetLoweredInterfaceDefaultImplementation>,
    reifiedGenericInterfaces: Set<IrClass>,
    genericOwnerCapabilities: Map<IrClass, DotNetIlClassInfo>,
    genericOwnerCapabilitySlots: Map<IrSimpleFunction, IrSimpleFunction>,
    genericOwnerCSharpWrongShapePolicies: Map<IrSimpleFunction, DotNetCSharpWrongShapePolicy>,
): DotNetCSharpImplementationManifest {
    fun IrClass.isCSharpSourceAuthorableInterface(): Boolean {
        if (!isInterface || fileOrNull !in files || this !in preLoweringDeclarationKeys) return false
        // Production-erased generic interfaces still need an explicit export. The atomic
        // rehearsal may instead make a structurally admitted interface's natural I<T> the public
        // owner and records its separate declaration-semantic capability below.
        if (typeParameters.isNotEmpty() && this !in reifiedGenericInterfaces) return false
        var owner: IrClass? = this
        while (owner != null) {
            when (owner.visibility) {
                DescriptorVisibilities.PUBLIC -> {}
                DescriptorVisibilities.INTERNAL -> if (owner.isPublishedApi()) return false
                else -> return false
            }
            owner = owner.parent as? IrClass
        }
        return true
    }

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
        parameterTypes = info.signature.physicalParameterTypes
            .drop(if (info.signature.hasThis) 1 else 0)
            .map { type -> type.nameInSignature },
    )

    val interfaces = availableClasses.keys
        .asSequence()
        .filter(IrClass::isCSharpSourceAuthorableInterface)
        .map { irClass ->
            val naturalClassInfo = availableClasses.getValue(irClass)
            val semanticClassInfo = genericOwnerCapabilities[irClass]
            val isReifiedGenericInterface = irClass.typeParameters.isNotEmpty() &&
                    irClass in reifiedGenericInterfaces && semanticClassInfo != null
            val exactClassInfo = typeMapper.genericInterfaceInfoOrNull(irClass)
                ?.exactClassInfo
                ?.takeIf { isReifiedGenericInterface }
            val canonicalClassInfo = semanticClassInfo ?: naturalClassInfo
            val interfaceKey = checkNotNull(preLoweringDeclarationKeys[irClass]) {
                "Source-authorable interface has no pre-lowering logical key"
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
                        superInterface?.let(DotNetRuntimeTypes::supportsCSharpInheritedSourceAuthoring) == true
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

            fun overriddenLogicalMemberKeys(source: IrSimpleFunction): List<String> =
                source.overriddenSymbols
                    .mapNotNull { overridden ->
                        preLoweringDeclarationKeys[overridden.owner]
                            ?: overridden.owner.dotNetLibraryAbiKeyOrNull("F")
                    }
                    .filter { overriddenKey -> overriddenKey != preLoweringDeclarationKeys[source] }
                    .distinct()
                    .sorted()

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
                    val typedMemberView = if (exactClassInfo != null) {
                        typeMapper.genericInterfaceMemberView(source, irClass)
                    } else {
                        null
                    }
                    val authoringView = when (typedMemberView) {
                        DotNetGenericInterfaceMemberView.DECLARED ->
                            DotNetCSharpInterfaceView.DECLARED
                        DotNetGenericInterfaceMemberView.EXACT ->
                            DotNetCSharpInterfaceView.EXACT
                        null -> if (isReifiedGenericInterface) {
                            DotNetCSharpInterfaceView.DECLARED
                        } else {
                            DotNetCSharpInterfaceView.CANONICAL
                        }
                    }
                    val naturalInfo = checkNotNull(availableFunctions[source]) {
                        "C# implementation manifest source member did not survive physical emission"
                    }
                    val naturalMethodName = naturalInfo.physicalMethodName ?: source.dotNetIlMethodName()
                    val slots = buildList {
                        if (isReifiedGenericInterface) {
                            val semanticSlot = checkNotNull(genericOwnerCapabilitySlots[source]) {
                                "Reified C# implementation member has no declaration-semantic slot"
                            }
                            val semanticInfo = checkNotNull(availableFunctions[semanticSlot]) {
                                "Reified C# implementation semantic slot did not survive physical emission"
                            }
                            add(locator(
                                DotNetCSharpSlotRole.ERASED,
                                semanticSlot,
                                semanticInfo,
                                semanticInfo.physicalMethodName ?: semanticSlot.dotNetIlMethodName(),
                                propertyName = null,
                            ))
                            add(locator(
                                when (typedMemberView) {
                                    DotNetGenericInterfaceMemberView.EXACT ->
                                        DotNetCSharpSlotRole.EXACT
                                    else -> DotNetCSharpSlotRole.DECLARED
                                },
                                source,
                                naturalInfo,
                                naturalMethodName,
                                canonicalPropertyName(source, naturalMethodName),
                            ))
                        } else {
                            add(locator(
                                DotNetCSharpSlotRole.CANONICAL,
                                source,
                                naturalInfo,
                                naturalMethodName,
                                canonicalPropertyName(source, naturalMethodName),
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
                    val erasedOwnerRelativeConstraints = if (isReifiedGenericInterface) {
                        source.dotNetDirectOwnerRelativeMethodBoundsOrNull(irClass)
                            ?.mapIndexedNotNull { methodIndex, bound ->
                                val ownerParameter = ((bound as? IrSimpleType)?.classifier as?
                                        IrTypeParameterSymbol)?.owner
                                    ?: return@mapIndexedNotNull null
                                DotNetCSharpErasedOwnerRelativeConstraint(
                                    methodTypeParameterIndex = methodIndex,
                                    ownerTypeParameterIndex = ownerParameter.index,
                                )
                            }
                            .orEmpty()
                    } else {
                        emptyList()
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
                        wrongShapePolicy = genericOwnerCSharpWrongShapePolicies[source],
                        erasedOwnerRelativeConstraints = erasedOwnerRelativeConstraints,
                        overriddenLogicalMemberKeys = overriddenLogicalMemberKeys(source),
                        slots = slots,
                    )
                }
                .sortedBy(DotNetCSharpMemberContract::logicalKey)
                .toList()
            DotNetCSharpInterfaceContract(
                logicalKey = interfaceKey,
                canonicalOwnerPath = canonicalClassInfo.physicalPathComponents(),
                declaredOwnerPath = naturalClassInfo.physicalPathComponents()
                    .takeIf { isReifiedGenericInterface },
                exactOwnerPath = exactClassInfo?.physicalPathComponents(),
                typeParameters = if (isReifiedGenericInterface) {
                    irClass.typeParameters.map { parameter ->
                        DotNetCSharpTypeParameter(
                            parameter.name.asString(),
                            when (parameter.variance) {
                                Variance.INVARIANT -> DotNetCSharpTypeParameterVariance.INVARIANT
                                Variance.IN_VARIANCE -> DotNetCSharpTypeParameterVariance.IN
                                Variance.OUT_VARIANCE -> DotNetCSharpTypeParameterVariance.OUT
                            },
                        )
                    }
                } else {
                    emptyList()
                },
                sourceAuthoringSupported = unsupportedReasons.isEmpty(),
                unsupportedReasons = unsupportedReasons,
                members = members,
                intersections = emptyList(),
            )
        }
        .sortedBy(DotNetCSharpInterfaceContract::logicalKey)
        .toList()
    return DotNetCSharpImplementationManifest(
        schemaVersion = DotNetCSharpImplementationManifestCodec.CURRENT_SCHEMA_VERSION,
        assemblyName = assemblyName,
        targetProfile = target.description,
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
    val runtimeInterfaces = listOf(irBuiltIns.charSequenceClass.owner)
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
        physicalPropertyName: String?,
    ): DotNetCSharpMethodLocator {
        val signature = source.dotNetSignature(signatureMapper)
        return DotNetCSharpMethodLocator(
            role = role,
            ownerPath = owner.physicalPathComponents(),
            methodName = physicalMethodName,
            propertyName = physicalPropertyName,
            genericArity = source.typeParameters.size,
            returnType = signature.returnType.nameInSignature,
            parameterTypes = signature.physicalParameterTypes
                .drop(if (signature.hasThis) 1 else 0)
                .map { type -> type.nameInSignature },
        )
    }

    val contracts = runtimeInterfaces.map { irClass ->
        val canonicalClassInfo = checkNotNull(
            DotNetRuntimeTypes.charSequenceImplementationClassInfo(irClass)
        ) {
            "Runtime C# interface contract has no physical interface registry entry"
        }
        val interfaceKey = checkNotNull(irClass.dotNetLibraryAbiKeyOrNull("C")) {
            "Runtime C# interface contract has no Kotlin public identity"
        }
        val members = sourceMembers.getValue(irClass).map { source ->
            val memberKey = checkNotNull(source.dotNetLibraryAbiKeyOrNull("F")) {
                "Runtime C# interface member has no Kotlin public identity"
            }
            val authoringView = DotNetCSharpInterfaceView.CANONICAL
            val slots = listOf(
                locator(
                    DotNetCSharpSlotRole.CANONICAL,
                    source,
                    canonicalClassInfo,
                    typeMapper,
                    source.dotNetIlMethodName(),
                    source.correspondingPropertySymbol?.owner?.name?.asString(),
                )
            )
            DotNetCSharpMemberContract(
                logicalKey = memberKey,
                kind = source.memberKind(),
                sourceName = source.correspondingPropertySymbol?.owner?.name?.asString()
                    ?: source.name.asString(),
                authoringView = authoringView,
                defaultKind = DotNetCSharpDefaultKind.ABSTRACT,
                semanticBodyView = null,
                wrongShapePolicy = null,
                overriddenLogicalMemberKeys = source.overriddenSymbols
                    .mapNotNull { overridden ->
                        overridden.owner.dotNetLibraryAbiKeyOrNull("F")
                    }
                    .filter { overriddenKey -> overriddenKey != memberKey }
                    .distinct()
                    .sorted(),
                slots = slots,
            )
        }.sortedBy(DotNetCSharpMemberContract::logicalKey)
        DotNetCSharpInterfaceContract(
            logicalKey = interfaceKey,
            canonicalOwnerPath = canonicalClassInfo.physicalPathComponents(),
            declaredOwnerPath = null,
            exactOwnerPath = null,
            typeParameters = emptyList(),
            sourceAuthoringSupported = true,
            unsupportedReasons = emptyList(),
            members = members,
            intersections = emptyList(),
        )
    }.sortedBy(DotNetCSharpInterfaceContract::logicalKey)

    return DotNetCSharpImplementationManifest(
        schemaVersion = DotNetCSharpImplementationManifestCodec.CURRENT_SCHEMA_VERSION,
        assemblyName = DotNetRuntimeLibrary.ASSEMBLY_NAME,
        targetProfile = target.description,
        logicalIdentityScheme = DotNetLibraryAbiCodec.LOGICAL_IDENTITY_SCHEME,
        interfaces = contracts,
    )
}

private fun DotNetCSharpSlotRole.toManifestView(): DotNetCSharpInterfaceView = when (this) {
    DotNetCSharpSlotRole.CANONICAL,
    DotNetCSharpSlotRole.ERASED -> DotNetCSharpInterfaceView.CANONICAL
    DotNetCSharpSlotRole.DECLARED -> DotNetCSharpInterfaceView.DECLARED
    DotNetCSharpSlotRole.EXACT -> DotNetCSharpInterfaceView.EXACT
    DotNetCSharpSlotRole.HELPER -> error("A canonical/helper slot has no typed C# authoring view")
}
