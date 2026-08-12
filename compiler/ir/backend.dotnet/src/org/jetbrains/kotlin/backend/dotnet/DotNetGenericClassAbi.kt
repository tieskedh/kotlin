/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.util.isInterface
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import java.security.MessageDigest
import java.util.Base64

/** The one non-generic physical CLR owner of a Kotlin-owned ordinary generic class. */
internal data class DotNetGenericClassInfo(
    val classInfo: DotNetIlClassInfo,
)

/**
 * Production-inert result of examining a Kotlin-owned generic class for a future CLR-generic
 * owner ABI. No value in this enum authorizes reified emission: the current physical owner stays
 * erased until the complete admission algorithm and one atomic ABI migration are accepted.
 */
enum class DotNetGenericOwnerCandidateDisposition {
    BLOCKED_METADATA_FIXED_CONDITIONAL_SUPERTYPE,
    BLOCKED_OPEN_OUTPUT_STATE_COHERENCE,
    REQUIRES_SEMANTIC_STATE_PROOF,
    REQUIRES_COMPLETE_FIELD_ACCESS_GRAPH,
    REQUIRES_TYPED_WRITE_VALUE_PROVENANCE,
    REQUIRES_EXTERNAL_OVERRIDE_BINDING_SCHEMA,
    REQUIRES_MEMBER_PHYSICALIZATION_PROOF,
}

/** The semantic authority required for one member's future canonical CLR entry point. */
enum class DotNetGenericOwnerMemberPolicy {
    /** Declaration variance proves that the ordinary typed member is the complete authority. */
    STRICT_TYPED,

    /** Kotlin defines a fixed type-test/default barrier for an incompatible widened candidate. */
    TYPE_SAFE_BARRIER,

    /** The Kotlin body remains authoritative even for an incompatible widened candidate. */
    SEMANTIC_BODY,
}

/** Physical roles which a future prototype must generate for one logical member. */
enum class DotNetGenericOwnerMemberFamilyRole {
    /** Natural CLR member over the owner's `!T` parameters and result. */
    TYPED_ENTRY,

    /** Object-domain virtual entry which preserves an authoritative broad Kotlin body. */
    SEMANTIC_HOOK,

    /** Non-generic capability slot which selects the typed entry or semantic behavior. */
    CAPABILITY_DISPATCHER,
}

/** Why a member family needs a separately overridable object-domain semantic entry. */
enum class DotNetGenericOwnerSemanticHookReason {
    GENERAL_WIDENED_BODY,
    PAIRED_OPEN_OUTPUT_STATE,
    INHERITED_SEMANTIC_OVERRIDE,
}

enum class DotNetGenericOwnerOverrideTargetKind {
    LOCAL_DETACHED_PROTOTYPE,
    EXTERNAL_LOGICAL_BINDING_REQUIRED,
    EXTERNAL_PHYSICAL_FAMILY_RECORD,
}

enum class DotNetGenericOwnerPhysicalMemberDispatch {
    FINAL,
    OVERRIDABLE,
    ABSTRACT,
}

data class DotNetGenericOwnerPrototypeOverrideBindingSnapshot(
    val role: DotNetGenericOwnerMemberFamilyRole,
    val overriddenOwnerName: String,
    val overriddenSourceName: String,
    val targetKind: DotNetGenericOwnerOverrideTargetKind,
    val overriddenLogicalBindingKey: String?,
    val overriddenPhysicalMethodName: String?,
    val overriddenPhysicalDispatch: DotNetGenericOwnerPhysicalMemberDispatch?,
    val overriddenPhysicalOwnerPath: List<String>?,
)

/** Exact logical target selected by one source-level `super` call. */
data class DotNetGenericOwnerDirectSuperCallSnapshot(
    val logicalMemberKey: String?,
    val logicalOwnerName: String,
    val superQualifierName: String,
)

/**
 * Production-inert generation requirements for one logical member. These roles are deliberately
 * unnamed: selecting MethodDef names and visibility belongs to the later physical prototype and
 * must not accidentally freeze a binding schema during semantic classification.
 */
internal data class DotNetGenericOwnerMemberFamilyPlan(
    val source: IrSimpleFunction,
    val policy: DotNetGenericOwnerMemberPolicy,
    val ownerDependentInputIndices: List<Int>,
    val hasOwnerDependentOutput: Boolean,
    val roles: Set<DotNetGenericOwnerMemberFamilyRole>,
    val semanticHookReasons: Set<DotNetGenericOwnerSemanticHookReason>,
    val requiresDirectSuperTargets: Boolean,
    val directSuperCallCount: Int,
    val directSuperCalls: List<DotNetGenericOwnerDirectSuperCallPlan>,
    val hasMaskedDefaultDispatcher: Boolean,
    val logicalBindingKey: String?,
)

internal data class DotNetGenericOwnerDirectSuperCallPlan(
    val target: IrSimpleFunction,
    val superQualifier: IrClass,
)

/** Producer-owned field/call effects for one logical member body. */
internal data class DotNetGenericOwnerMemberAccessPlan(
    val source: IrSimpleFunction,
    val directCalls: Set<IrFunction>,
    val transitiveCalls: Set<IrFunction>,
    val directReads: Set<IrField>,
    val directWrites: Set<IrField>,
    val transitiveReads: Set<IrField>,
    val transitiveWrites: Set<IrField>,
    val reachableFromSemanticEntry: Boolean,
)

/**
 * A detached compiler IR member for the non-production physical prototype. [function] has the
 * selected signature and override modality, but is never inserted into [IrClass.declarations].
 */
internal data class DotNetGenericOwnerPrototypeMember(
    val source: IrSimpleFunction,
    val role: DotNetGenericOwnerMemberFamilyRole,
    val function: IrSimpleFunction,
)

/** Immutable, IR-free observation of one detached prototype member for compiler tests. */
data class DotNetGenericOwnerPrototypeMemberSnapshot(
    val sourceName: String,
    val sourceIndex: Int,
    val isAbstract: Boolean,
    val isOverridable: Boolean,
    val policy: DotNetGenericOwnerMemberPolicy,
    val roles: Set<DotNetGenericOwnerMemberFamilyRole>,
    val semanticHookReasons: Set<DotNetGenericOwnerSemanticHookReason>,
    val typedRetainsOwnerDependentInput: Boolean,
    val semanticErasesOwnerDependentInput: Boolean,
    val typedRetainsOwnerDependentOutput: Boolean,
    val semanticErasesOwnerDependentOutput: Boolean,
    val requiresDirectSuperTargets: Boolean,
    val directSuperCallCount: Int,
    val directSuperCalls: List<DotNetGenericOwnerDirectSuperCallSnapshot>,
    val hasMaskedDefaultDispatcher: Boolean,
    val logicalBindingKey: String?,
    val overrideBindings: List<DotNetGenericOwnerPrototypeOverrideBindingSnapshot>,
    val directProducerCallNames: List<String>,
    val transitiveProducerCallNames: List<String>,
    val directStateReadNames: List<String>,
    val directStateWriteNames: List<String>,
    val transitiveStateReadNames: List<String>,
    val transitiveStateWriteNames: List<String>,
    val reachableFromSemanticEntry: Boolean,
)

data class DotNetGenericOwnerPrototypeStateSnapshot(
    val fieldName: String,
    val requirement: DotNetGenericOwnerStateCarrierRequirement,
    val writes: List<DotNetGenericOwnerPrototypeStateWriteSnapshot>,
    val directReaderNames: List<String>,
    val directWriterNames: List<String>,
    val semanticReachableReaderNames: List<String>,
    val semanticReachableWriterNames: List<String>,
    val initializationReaderLabels: List<String>,
    val initializationWriterLabels: List<String>,
    val externalAccessGraphRequired: Boolean,
)

/** Immutable evidence for the physical domain of one producer-owned state write. */
data class DotNetGenericOwnerPrototypeStateWriteSnapshot(
    val producerName: String,
    val provenance: DotNetGenericOwnerWriteValueProvenance,
)

/**
 * In-memory evidence returned by the backend pipeline for tests and architecture tooling. It is
 * not serialized into the DLL/KLIB, consumed by codegen, or selected by a compiler option.
 */
data class DotNetGenericOwnerPrototypeSnapshot(
    val ownerName: String,
    val genericArity: Int,
    val disposition: DotNetGenericOwnerCandidateDisposition,
    val logicalBindingKey: String?,
    val members: List<DotNetGenericOwnerPrototypeMemberSnapshot>,
    val states: List<DotNetGenericOwnerPrototypeStateSnapshot>,
    val metadataFixedConditionalSupertypeCount: Int,
)

/** One producer-selected physical MethodDef role in a future CLR-generic member family. */
data class DotNetGenericOwnerPhysicalMemberSlotRecord(
    val role: DotNetGenericOwnerMemberFamilyRole,
    val physicalMethodName: String,
    val dispatch: DotNetGenericOwnerPhysicalMemberDispatch,
) {
    init {
        require(physicalMethodName.isNotEmpty()) { "a generic-owner physical member slot requires a method name" }
        if (role == DotNetGenericOwnerMemberFamilyRole.CAPABILITY_DISPATCHER) {
            require(dispatch == DotNetGenericOwnerPhysicalMemberDispatch.FINAL) {
                "a generic-owner capability dispatcher must remain a final non-override slot"
            }
        }
    }
}

data class DotNetGenericOwnerPhysicalDirectSuperTargetRecord(
    val role: DotNetGenericOwnerMemberFamilyRole,
    val logicalTargetMemberKey: String,
    val physicalOwnerPath: List<String>,
    val physicalMethodName: String,
) {
    init {
        require(role != DotNetGenericOwnerMemberFamilyRole.CAPABILITY_DISPATCHER) {
            "a capability dispatcher cannot be a direct-super target"
        }
        require(logicalTargetMemberKey.isNotEmpty()) { "a direct-super target requires a logical member key" }
        require(physicalOwnerPath.isNotEmpty() && physicalOwnerPath.all(String::isNotEmpty)) {
            "direct-super target '$logicalTargetMemberKey' requires a physical owner path"
        }
        require(physicalMethodName.isNotEmpty()) { "direct-super target '$logicalTargetMemberKey' requires a method name" }
    }
}

data class DotNetGenericOwnerPhysicalDefaultDispatcherRecord(
    val physicalOwnerPath: List<String>,
    val physicalMethodName: String,
) {
    init {
        require(physicalOwnerPath.isNotEmpty() && physicalOwnerPath.all(String::isNotEmpty)) {
            "a generic-owner default dispatcher requires a physical owner path"
        }
        require(physicalMethodName.isNotEmpty()) { "a generic-owner default dispatcher requires a method name" }
    }
}

/** Complete producer-owned physical family for one logical Kotlin member. */
data class DotNetGenericOwnerPhysicalMemberFamilyRecord(
    val logicalMemberKey: String,
    val overrideRootLogicalMemberKeys: List<String>,
    val policy: DotNetGenericOwnerMemberPolicy,
    val roles: Set<DotNetGenericOwnerMemberFamilyRole>,
    val semanticHookReasons: Set<DotNetGenericOwnerSemanticHookReason>,
    val slots: List<DotNetGenericOwnerPhysicalMemberSlotRecord>,
    val directSuperTargets: List<DotNetGenericOwnerPhysicalDirectSuperTargetRecord>,
    val defaultDispatcher: DotNetGenericOwnerPhysicalDefaultDispatcherRecord?,
) {
    init {
        require(logicalMemberKey.isNotEmpty()) { "a generic-owner physical member family requires a logical key" }
        require(DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY in roles) {
            "generic-owner physical member family '$logicalMemberKey' lacks its typed entry"
        }
        require(overrideRootLogicalMemberKeys.isNotEmpty() &&
                overrideRootLogicalMemberKeys.all(String::isNotEmpty) &&
                overrideRootLogicalMemberKeys == overrideRootLogicalMemberKeys.distinct().sorted()) {
            "generic-owner physical member family '$logicalMemberKey' requires sorted unique override roots"
        }
        require(slots.map { slot -> slot.role }.toSet().size == slots.size) {
            "generic-owner physical member family '$logicalMemberKey' has duplicate role slots"
        }
        require(slots.map { slot -> slot.role }.toSet() == roles) {
            "generic-owner physical member family '$logicalMemberKey' has incomplete role slots"
        }
        require(
            semanticHookReasons.isEmpty() ==
                    (DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK !in roles)
        ) {
            "generic-owner physical member family '$logicalMemberKey' has inconsistent semantic-hook reasons"
        }
        if (DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK in roles) {
            require(DotNetGenericOwnerMemberFamilyRole.CAPABILITY_DISPATCHER in roles) {
                "generic-owner semantic member family '$logicalMemberKey' lacks its capability dispatcher"
            }
        }
        require(directSuperTargets.toSet().size == directSuperTargets.size) {
            "generic-owner physical member family '$logicalMemberKey' has duplicate direct-super targets"
        }
        require(directSuperTargets.all { target -> target.role in roles }) {
            "generic-owner physical member family '$logicalMemberKey' has a direct-super target outside its role set"
        }
    }
}

/** Producer-selected physical carrier for one logical owner-dependent field. */
data class DotNetGenericOwnerPhysicalStateRecord(
    val logicalFieldName: String,
    val physicalFieldName: String,
    val requirement: DotNetGenericOwnerStateCarrierRequirement,
) {
    init {
        require(logicalFieldName.isNotEmpty() && physicalFieldName.isNotEmpty()) {
            "a generic-owner physical state record requires logical and physical field names"
        }
    }
}

/**
 * Versioned cross-assembly prototype record for one future CLR-generic owner.
 *
 * This is deliberately not [DotNetPhysicalDeclaration] and is never placed in today's DLL/KLIB:
 * the production owner is still erased. It proves that a later producer can publish a complete
 * family atomically and that a consumer can bind it without reconstructing MethodDef names.
 */
data class DotNetGenericOwnerPhysicalFamilyRecord(
    val logicalOwnerKey: String,
    val physicalOwnerPath: List<String>,
    val physicalCapabilityOwnerPath: List<String>?,
    val genericArity: Int,
    val disposition: DotNetGenericOwnerCandidateDisposition,
    val members: List<DotNetGenericOwnerPhysicalMemberFamilyRecord>,
    val states: List<DotNetGenericOwnerPhysicalStateRecord>,
) {
    init {
        require(logicalOwnerKey.isNotEmpty()) { "a generic-owner physical family requires a logical owner key" }
        require(physicalOwnerPath.isNotEmpty() && physicalOwnerPath.all(String::isNotEmpty)) {
            "generic-owner physical family '$logicalOwnerKey' requires a complete physical owner path"
        }
        require(physicalCapabilityOwnerPath == null ||
                physicalCapabilityOwnerPath.isNotEmpty() && physicalCapabilityOwnerPath.all(String::isNotEmpty)) {
            "generic-owner physical family '$logicalOwnerKey' has an incomplete capability owner path"
        }
        require(genericArity > 0) { "generic-owner physical family '$logicalOwnerKey' requires positive arity" }
        require(members.map { member -> member.logicalMemberKey }.toSet().size == members.size) {
            "generic-owner physical family '$logicalOwnerKey' has duplicate logical members"
        }
        require(states.map { state -> state.logicalFieldName }.toSet().size == states.size) {
            "generic-owner physical family '$logicalOwnerKey' has duplicate logical state"
        }
        require(states.map { state -> state.physicalFieldName }.toSet().size == states.size) {
            "generic-owner physical family '$logicalOwnerKey' has duplicate physical state"
        }
        require(members.none { member ->
            DotNetGenericOwnerMemberFamilyRole.CAPABILITY_DISPATCHER in member.roles
        } || physicalCapabilityOwnerPath != null) {
            "generic-owner physical family '$logicalOwnerKey' lacks its capability owner"
        }
    }
}

/** One detached, producer-fingerprinted family artifact used only by architecture evidence. */
data class DotNetGenericOwnerPhysicalFamilyArtifact(
    val producerFingerprint: String,
    val owners: List<DotNetGenericOwnerPhysicalFamilyRecord>,
) {
    init {
        require(PRODUCER_FINGERPRINT.matches(producerFingerprint)) {
            "a generic-owner family artifact requires a lowercase SHA-256 producer fingerprint"
        }
        require(owners.map { owner -> owner.logicalOwnerKey }.toSet().size == owners.size) {
            "a generic-owner family artifact has duplicate logical owners"
        }
        require(owners.flatMap { owner -> owner.members }.map { member -> member.logicalMemberKey }.toSet().size ==
                owners.sumOf { owner -> owner.members.size }) {
            "a generic-owner family artifact has duplicate logical members across owners"
        }
    }

    private companion object {
        val PRODUCER_FINGERPRINT = Regex("[0-9a-f]{64}")
    }
}

/**
 * Deterministic codec for the production-inert generic-owner family artifact.
 *
 * Counts precede every nested block and decoding constructs the whole validated artifact before
 * returning it. A stale, truncated, duplicated, or wrong-producer record can therefore never
 * resolve only a subset of one consumer's override obligations.
 */
object DotNetGenericOwnerPhysicalFamilyCodec {
    const val SCHEMA_VERSION = 2
    private const val MAGIC = "kotlin-dotnet-generic-owner-families"
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun producerFingerprint(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }

    fun encode(artifact: DotNetGenericOwnerPhysicalFamilyArtifact): String = buildString {
        appendLine("$MAGIC\t$SCHEMA_VERSION")
        appendLine("P\t${artifact.producerFingerprint}")
        val owners = artifact.owners.sortedBy { owner -> owner.logicalOwnerKey }
        appendLine("N\t${owners.size}")
        owners.forEach { owner ->
            val members = owner.members.sortedBy { member -> member.logicalMemberKey }
            val states = owner.states.sortedBy { state -> state.logicalFieldName }
            appendLine(
                listOf(
                    "O",
                    owner.logicalOwnerKey.encoded(),
                    owner.physicalOwnerPath.joinToString("\u0000").encoded(),
                    owner.physicalCapabilityOwnerPath
                        ?.joinToString("\u0000")
                        ?.encoded()
                        ?: "-",
                    owner.genericArity.toString(),
                    owner.disposition.name,
                    members.size.toString(),
                    states.size.toString(),
                ).joinToString("\t")
            )
            members.forEach { member ->
                val roles = member.roles.sortedBy { role -> role.name }
                val reasons = member.semanticHookReasons.sortedBy { reason -> reason.name }
                val slots = member.slots.sortedBy { slot -> slot.role.name }
                appendLine(
                    listOf(
                        "M",
                        member.logicalMemberKey.encoded(),
                        member.policy.name,
                        roles.joinToString(",") { role -> role.name },
                        reasons.joinToString(",") { reason -> reason.name }.ifEmpty { "-" },
                        slots.size.toString(),
                        member.overrideRootLogicalMemberKeys.joinToString("\u0000").encoded(),
                        member.directSuperTargets.size.toString(),
                        if (member.defaultDispatcher == null) "0" else "1",
                    ).joinToString("\t")
                )
                slots.forEach { slot ->
                    appendLine(
                        listOf("R", slot.role.name, slot.dispatch.name, slot.physicalMethodName.encoded())
                            .joinToString("\t")
                    )
                }
                member.directSuperTargets.sortedWith(
                    compareBy<DotNetGenericOwnerPhysicalDirectSuperTargetRecord>(
                        { target -> target.role.name },
                        { target -> target.logicalTargetMemberKey },
                    )
                ).forEach { target ->
                    appendLine(
                        listOf(
                            "D",
                            target.role.name,
                            target.logicalTargetMemberKey.encoded(),
                            target.physicalOwnerPath.joinToString("\u0000").encoded(),
                            target.physicalMethodName.encoded(),
                        ).joinToString("\t")
                    )
                }
                member.defaultDispatcher?.let { dispatcher ->
                    appendLine(
                        listOf(
                            "A",
                            dispatcher.physicalOwnerPath.joinToString("\u0000").encoded(),
                            dispatcher.physicalMethodName.encoded(),
                        ).joinToString("\t")
                    )
                }
            }
            states.forEach { state ->
                appendLine(
                    listOf(
                        "S",
                        state.logicalFieldName.encoded(),
                        state.physicalFieldName.encoded(),
                        state.requirement.name,
                    ).joinToString("\t")
                )
            }
        }
    }

    fun decode(
        text: String,
        expectedProducerFingerprint: String? = null,
    ): DotNetGenericOwnerPhysicalFamilyArtifact {
        val lines = text.removeSuffix("\n").split('\n').map { line -> line.removeSuffix("\r") }
        var index = 0

        fun read(kind: String, fieldCount: Int): List<String> {
            require(index < lines.size) { "generic-owner family artifact is truncated before '$kind'" }
            val fields = lines[index++].split('\t')
            require(fields.size == fieldCount && fields.firstOrNull() == kind) {
                "generic-owner family artifact expected '$kind' with $fieldCount fields"
            }
            return fields
        }

        fun count(value: String, role: String): Int = value.toIntOrNull()?.takeIf { it >= 0 }
            ?: throw IllegalArgumentException("generic-owner family artifact has invalid $role count '$value'")

        fun <E : Enum<E>> enumValue(value: String, values: Array<E>, role: String): E =
            values.firstOrNull { candidate -> candidate.name == value }
                ?: throw IllegalArgumentException("generic-owner family artifact has unknown $role '$value'")

        fun <E : Enum<E>> enumSet(value: String, values: Array<E>, role: String): Set<E> {
            if (value == "-") return emptySet()
            val names = value.split(',')
            require(names.size == names.toSet().size) {
                "generic-owner family artifact has duplicate $role entries"
            }
            return names.mapTo(linkedSetOf()) { name -> enumValue(name, values, role) }
        }

        val header = read(MAGIC, 2)
        require(header[1] == SCHEMA_VERSION.toString()) {
            "stale generic-owner family schema '${header[1]}'; expected '$SCHEMA_VERSION'"
        }
        val producerFingerprint = read("P", 2)[1]
        if (expectedProducerFingerprint != null) {
            require(producerFingerprint == expectedProducerFingerprint) {
                "generic-owner family artifact does not describe the selected producer"
            }
        }
        val ownerCount = count(read("N", 2)[1], "owner")
        val owners = List(ownerCount) {
            val fields = read("O", 8)
            val logicalOwnerKey = fields[1].decoded()
            val ownerPath = fields[2].decoded().split('\u0000')
            val capabilityOwnerPath = fields[3].takeUnless { it == "-" }?.decoded()?.split('\u0000')
            val genericArity = count(fields[4], "generic-arity")
            val disposition = enumValue(
                fields[5],
                DotNetGenericOwnerCandidateDisposition.entries.toTypedArray(),
                "owner disposition",
            )
            val memberCount = count(fields[6], "member")
            val stateCount = count(fields[7], "state")
            val members = List(memberCount) {
                val memberFields = read("M", 9)
                val logicalMemberKey = memberFields[1].decoded()
                val policy = enumValue(
                    memberFields[2],
                    DotNetGenericOwnerMemberPolicy.entries.toTypedArray(),
                    "member policy",
                )
                val roles = enumSet(
                    memberFields[3],
                    DotNetGenericOwnerMemberFamilyRole.entries.toTypedArray(),
                    "member role",
                )
                val reasons = enumSet(
                    memberFields[4],
                    DotNetGenericOwnerSemanticHookReason.entries.toTypedArray(),
                    "semantic-hook reason",
                )
                val slotCount = count(memberFields[5], "slot")
                val overrideRoots = memberFields[6].decoded().split('\u0000')
                val directSuperCount = count(memberFields[7], "direct-super")
                val hasDefaultDispatcher = when (memberFields[8]) {
                    "0" -> false
                    "1" -> true
                    else -> throw IllegalArgumentException(
                        "generic-owner family artifact has invalid default-dispatcher marker '${memberFields[8]}'"
                    )
                }
                val slots = List(slotCount) {
                    val slotFields = read("R", 4)
                    DotNetGenericOwnerPhysicalMemberSlotRecord(
                        role = enumValue(
                            slotFields[1],
                            DotNetGenericOwnerMemberFamilyRole.entries.toTypedArray(),
                            "slot role",
                        ),
                        dispatch = enumValue(
                            slotFields[2],
                            DotNetGenericOwnerPhysicalMemberDispatch.entries.toTypedArray(),
                            "slot dispatch",
                        ),
                        physicalMethodName = slotFields[3].decoded(),
                    )
                }
                val directSuperTargets = List(directSuperCount) {
                    val targetFields = read("D", 5)
                    DotNetGenericOwnerPhysicalDirectSuperTargetRecord(
                        role = enumValue(
                            targetFields[1],
                            DotNetGenericOwnerMemberFamilyRole.entries.toTypedArray(),
                            "direct-super role",
                        ),
                        logicalTargetMemberKey = targetFields[2].decoded(),
                        physicalOwnerPath = targetFields[3].decoded().split('\u0000'),
                        physicalMethodName = targetFields[4].decoded(),
                    )
                }
                val defaultDispatcher = if (hasDefaultDispatcher) {
                    val dispatcherFields = read("A", 3)
                    DotNetGenericOwnerPhysicalDefaultDispatcherRecord(
                        physicalOwnerPath = dispatcherFields[1].decoded().split('\u0000'),
                        physicalMethodName = dispatcherFields[2].decoded(),
                    )
                } else {
                    null
                }
                DotNetGenericOwnerPhysicalMemberFamilyRecord(
                    logicalMemberKey = logicalMemberKey,
                    overrideRootLogicalMemberKeys = overrideRoots,
                    policy = policy,
                    roles = roles,
                    semanticHookReasons = reasons,
                    slots = slots,
                    directSuperTargets = directSuperTargets,
                    defaultDispatcher = defaultDispatcher,
                )
            }
            val states = List(stateCount) {
                val stateFields = read("S", 4)
                DotNetGenericOwnerPhysicalStateRecord(
                    logicalFieldName = stateFields[1].decoded(),
                    physicalFieldName = stateFields[2].decoded(),
                    requirement = enumValue(
                        stateFields[3],
                        DotNetGenericOwnerStateCarrierRequirement.entries.toTypedArray(),
                        "state requirement",
                    ),
                )
            }
            DotNetGenericOwnerPhysicalFamilyRecord(
                logicalOwnerKey = logicalOwnerKey,
                physicalOwnerPath = ownerPath,
                physicalCapabilityOwnerPath = capabilityOwnerPath,
                genericArity = genericArity,
                disposition = disposition,
                members = members,
                states = states,
            )
        }
        require(index == lines.size) { "generic-owner family artifact has trailing records" }
        return DotNetGenericOwnerPhysicalFamilyArtifact(producerFingerprint, owners)
    }

    private fun String.encoded(): String = encoder.encodeToString(toByteArray(Charsets.UTF_8))

    private fun String.decoded(): String = try {
        decoder.decode(this).toString(Charsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("generic-owner family artifact contains invalid encoded text")
    }
}

/**
 * Resolves every external detached override through a fully decoded producer artifact.
 * The returned snapshot remains production-inert, but now contains exact producer-selected
 * typed/semantic MethodDef names and no unresolved external logical binding.
 */
fun DotNetGenericOwnerPrototypeSnapshot.resolveExternalPhysicalFamilies(
    artifact: DotNetGenericOwnerPhysicalFamilyArtifact,
): DotNetGenericOwnerPrototypeSnapshot {
    val externalMembers:
            Map<String, Pair<DotNetGenericOwnerPhysicalFamilyRecord, DotNetGenericOwnerPhysicalMemberFamilyRecord>> =
        buildMap {
            artifact.owners.forEach { owner ->
                owner.members.forEach { member ->
                    check(put(member.logicalMemberKey, owner to member) == null) {
                        "producer generic-owner family artifact has duplicate logical member '${member.logicalMemberKey}'"
                    }
                }
            }
        }
    var resolvedAny = false
    val resolvedMembers = members.map { member ->
        val unresolved = member.overrideBindings.filter { binding ->
            binding.targetKind == DotNetGenericOwnerOverrideTargetKind.EXTERNAL_LOGICAL_BINDING_REQUIRED
        }
        if (unresolved.isEmpty()) return@map member
        resolvedAny = true
        val retained = member.overrideBindings - unresolved.toSet()
        val resolved = unresolved.flatMap { binding ->
            val logicalKey = requireNotNull(binding.overriddenLogicalBindingKey) {
                "external generic-owner override '${member.sourceName}' lacks a logical member key"
            }
            val producerEntry = externalMembers[logicalKey]
                ?: error("producer generic-owner family artifact lacks logical member '$logicalKey'")
            val producerOwner = producerEntry.first
            val producerMember = producerEntry.second
            producerMember.slots.filter { slot ->
                slot.role == DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY ||
                        slot.role == DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK
            }.map { slot ->
                require(slot.dispatch != DotNetGenericOwnerPhysicalMemberDispatch.FINAL) {
                    "consumer override '${member.sourceName}' targets final producer slot '${slot.physicalMethodName}'"
                }
                binding.copy(
                    role = slot.role,
                    targetKind = DotNetGenericOwnerOverrideTargetKind.EXTERNAL_PHYSICAL_FAMILY_RECORD,
                    overriddenPhysicalMethodName = slot.physicalMethodName,
                    overriddenPhysicalDispatch = slot.dispatch,
                    overriddenPhysicalOwnerPath = producerOwner.physicalOwnerPath,
                )
            }
        }
        val semanticProducerMembers = unresolved.map { binding ->
            externalMembers.getValue(requireNotNull(binding.overriddenLogicalBindingKey)).second
        }.filter { producerMember ->
            DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK in producerMember.roles
        }
        member.copy(
            roles = member.roles + resolved.map { binding -> binding.role } +
                    if (semanticProducerMembers.isNotEmpty()) {
                        setOf(DotNetGenericOwnerMemberFamilyRole.CAPABILITY_DISPATCHER)
                    } else {
                        emptySet()
                    },
            semanticHookReasons = member.semanticHookReasons +
                    semanticProducerMembers.flatMap { producerMember -> producerMember.semanticHookReasons } +
                    if (semanticProducerMembers.isNotEmpty()) {
                        setOf(DotNetGenericOwnerSemanticHookReason.INHERITED_SEMANTIC_OVERRIDE)
                    } else {
                        emptySet()
                    },
            overrideBindings = (retained + resolved).distinctBy { binding ->
                Triple(binding.role, binding.overriddenLogicalBindingKey, binding.overriddenPhysicalMethodName)
            },
        )
    }
    if (!resolvedAny) return this
    check(resolvedMembers.flatMap { member -> member.overrideBindings }.none { binding ->
        binding.targetKind == DotNetGenericOwnerOverrideTargetKind.EXTERNAL_LOGICAL_BINDING_REQUIRED
    }) {
        "generic-owner external family resolution left an unresolved override binding"
    }
    return copy(
        disposition = if (disposition ==
            DotNetGenericOwnerCandidateDisposition.REQUIRES_EXTERNAL_OVERRIDE_BINDING_SCHEMA
        ) {
            DotNetGenericOwnerCandidateDisposition.REQUIRES_MEMBER_PHYSICALIZATION_PROOF
        } else {
            disposition
        },
        members = resolvedMembers,
    )
}

/** The only safe conclusion currently available for one owner-dependent field. */
enum class DotNetGenericOwnerStateCarrierRequirement {
    /** A complete field/call/access graph is still required before selecting a physical carrier. */
    COMPLETE_ACCESS_GRAPH_REQUIRED,

    /** The closed producer graph proves that only typed-domain writes can reach this private field. */
    TYPED_STORAGE_PRODUCER_GRAPH_PROVEN,

    /** Function writes exist, but their complete value provenance is not proved physically typed. */
    TYPED_WRITE_VALUE_PROVENANCE_REQUIRED,

    /** A widened semantic write has been observed, so the one field must accept object-domain state. */
    SEMANTIC_OBJECT_REQUIRED,
}

/**
 * Conservative result of tracing the value stored by one owner-dependent field write.
 *
 * A logical Kotlin type of `T` is deliberately insufficient for [PHYSICALLY_TYPED]: an explicit
 * `Any? as T` retains the provenance of its object-domain input rather than manufacturing typed
 * evidence. [UNRESOLVED] is fail-closed and cannot select typed storage.
 */
enum class DotNetGenericOwnerWriteValueProvenance {
    PHYSICALLY_TYPED,
    SEMANTIC_OBJECT,
    UNRESOLVED,
}

internal data class DotNetGenericOwnerStateWriteProvenancePlan(
    val producerName: String,
    val provenance: DotNetGenericOwnerWriteValueProvenance,
)

internal data class DotNetGenericOwnerOverrideBindingPlan(
    val source: IrSimpleFunction,
    val role: DotNetGenericOwnerMemberFamilyRole,
    val overriddenSource: IrSimpleFunction,
    val targetKind: DotNetGenericOwnerOverrideTargetKind,
    val overriddenLogicalBindingKey: String?,
)

internal data class DotNetGenericOwnerStateCarrierPlan(
    val field: IrField,
    val requirement: DotNetGenericOwnerStateCarrierRequirement,
    val writes: List<DotNetGenericOwnerStateWriteProvenancePlan>,
    val directReaders: Set<IrFunction>,
    val directWriters: Set<IrFunction>,
    val semanticReachableReaders: Set<IrFunction>,
    val semanticReachableWriters: Set<IrFunction>,
    val initializationReaderLabels: Set<String>,
    val initializationWriterLabels: Set<String>,
    val externalAccessGraphRequired: Boolean,
)

/**
 * Conservative architecture evidence retained for every local Kotlin-owned generic class.
 *
 * This record deliberately contains blockers and incomplete proof obligations only. In
 * particular, absence from [directSemanticWriteFields] is not evidence that typed `!T` storage
 * is safe: [semanticReachableWriteFields] includes writes reached through the producer call graph,
 * and externally accessible fields retain an explicit cross-assembly proof obligation. Even a
 * producer-proven typed field does not admit its owner or authorize reified emission.
 */
internal data class DotNetGenericOwnerArchitecturePlan(
    val owner: IrClass,
    val logicalBindingKey: String?,
    val disposition: DotNetGenericOwnerCandidateDisposition,
    val memberPolicies: Map<IrSimpleFunction, DotNetGenericOwnerMemberPolicy>,
    val memberFamilies: Map<IrSimpleFunction, DotNetGenericOwnerMemberFamilyPlan>,
    val memberAccesses: Map<IrSimpleFunction, DotNetGenericOwnerMemberAccessPlan>,
    val prototypeMembers: Map<IrSimpleFunction, Map<DotNetGenericOwnerMemberFamilyRole, DotNetGenericOwnerPrototypeMember>>,
    val metadataFixedConditionalSupertypes: List<IrType>,
    val directSemanticWriteFields: Set<IrField>,
    val semanticReachableWriteFields: Set<IrField>,
    val semanticValueWriteFields: Set<IrField>,
    val overrideBindings: Map<IrSimpleFunction, List<DotNetGenericOwnerOverrideBindingPlan>>,
    val stateCarriers: Map<IrField, DotNetGenericOwnerStateCarrierPlan>,
    val openOwnerOutputs: Set<IrSimpleFunction>,
)

internal fun DotNetGenericOwnerArchitecturePlan.toPrototypeSnapshot(): DotNetGenericOwnerPrototypeSnapshot {
    fun prototypeFor(
        source: IrSimpleFunction,
        role: DotNetGenericOwnerMemberFamilyRole,
    ): IrSimpleFunction? = prototypeMembers[source]?.get(role)?.function

    fun IrSimpleFunction.hasOwnerDependentInput(): Boolean = parameters.any { parameter ->
        parameter.kind != IrParameterKind.DispatchReceiver &&
                parameter.type.referencesTypeParameterOf(owner)
    }

    return DotNetGenericOwnerPrototypeSnapshot(
        ownerName = owner.fqNameWhenAvailable?.asString() ?: owner.name.asString(),
        genericArity = owner.typeParameters.size,
        disposition = disposition,
        logicalBindingKey = logicalBindingKey,
        members = memberFamilies.entries.mapIndexed { sourceIndex, entry ->
            val source = entry.key
            val family = entry.value
            val access = memberAccesses.getValue(source)
            val typed = prototypeFor(source, DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY)
            val semantic = prototypeFor(source, DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK)
                ?: prototypeFor(source, DotNetGenericOwnerMemberFamilyRole.CAPABILITY_DISPATCHER)
            DotNetGenericOwnerPrototypeMemberSnapshot(
                sourceName = source.name.asString(),
                sourceIndex = sourceIndex,
                isAbstract = source.modality == Modality.ABSTRACT,
                isOverridable = source.modality != Modality.FINAL,
                policy = family.policy,
                roles = family.roles,
                semanticHookReasons = family.semanticHookReasons,
                typedRetainsOwnerDependentInput = typed?.hasOwnerDependentInput() == true,
                semanticErasesOwnerDependentInput = semantic?.hasOwnerDependentInput() == false,
                typedRetainsOwnerDependentOutput = typed?.returnType?.referencesTypeParameterOf(owner) == true,
                semanticErasesOwnerDependentOutput =
                    semantic?.returnType?.referencesTypeParameterOf(owner) == false,
                requiresDirectSuperTargets = family.requiresDirectSuperTargets,
                directSuperCallCount = family.directSuperCallCount,
                directSuperCalls = family.directSuperCalls.map { call ->
                    val logicalOwner = call.target.parent as IrClass
                    DotNetGenericOwnerDirectSuperCallSnapshot(
                        logicalMemberKey = call.target.dotNetLibraryAbiKeyOrNull("F"),
                        logicalOwnerName = logicalOwner.fqNameWhenAvailable?.asString()
                            ?: logicalOwner.name.asString(),
                        superQualifierName = call.superQualifier.fqNameWhenAvailable?.asString()
                            ?: call.superQualifier.name.asString(),
                    )
                },
                hasMaskedDefaultDispatcher = family.hasMaskedDefaultDispatcher,
                logicalBindingKey = family.logicalBindingKey,
                overrideBindings = overrideBindings[source].orEmpty().map { binding ->
                    val overriddenOwner = binding.overriddenSource.parent as IrClass
                    DotNetGenericOwnerPrototypeOverrideBindingSnapshot(
                        role = binding.role,
                        overriddenOwnerName = overriddenOwner.fqNameWhenAvailable?.asString()
                            ?: overriddenOwner.name.asString(),
                        overriddenSourceName = binding.overriddenSource.name.asString(),
                        targetKind = binding.targetKind,
                        overriddenLogicalBindingKey = binding.overriddenLogicalBindingKey,
                        overriddenPhysicalMethodName = null,
                        overriddenPhysicalDispatch = null,
                        overriddenPhysicalOwnerPath = null,
                    )
                },
                directProducerCallNames = access.directCalls.map { it.name.asString() }.sorted(),
                transitiveProducerCallNames = access.transitiveCalls.map { it.name.asString() }.sorted(),
                directStateReadNames = access.directReads
                    .filter { it in stateCarriers }
                    .map { it.name.asString() }
                    .sorted(),
                directStateWriteNames = access.directWrites
                    .filter { it in stateCarriers }
                    .map { it.name.asString() }
                    .sorted(),
                transitiveStateReadNames = access.transitiveReads
                    .filter { it in stateCarriers }
                    .map { it.name.asString() }
                    .sorted(),
                transitiveStateWriteNames = access.transitiveWrites
                    .filter { it in stateCarriers }
                    .map { it.name.asString() }
                    .sorted(),
                reachableFromSemanticEntry = access.reachableFromSemanticEntry,
            )
        },
        states = stateCarriers.values.map { state ->
            DotNetGenericOwnerPrototypeStateSnapshot(
                fieldName = state.field.name.asString(),
                requirement = state.requirement,
                writes = state.writes.map { write ->
                    DotNetGenericOwnerPrototypeStateWriteSnapshot(
                        producerName = write.producerName,
                        provenance = write.provenance,
                    )
                },
                directReaderNames = state.directReaders.map { it.name.asString() }.sorted(),
                directWriterNames = state.directWriters.map { it.name.asString() }.sorted(),
                semanticReachableReaderNames = state.semanticReachableReaders.map { it.name.asString() }.sorted(),
                semanticReachableWriterNames = state.semanticReachableWriters.map { it.name.asString() }.sorted(),
                initializationReaderLabels = state.initializationReaderLabels.sorted(),
                initializationWriterLabels = state.initializationWriterLabels.sorted(),
                externalAccessGraphRequired = state.externalAccessGraphRequired,
            )
        },
        metadataFixedConditionalSupertypeCount = metadataFixedConditionalSupertypes.size,
    )
}

/** Ordinary Kotlin-owned classes whose declaration parameters use the erased class ABI. */
internal val IrClass.isDotNetGenericClassDeclaration: Boolean
    get() = !isInterface && typeParameters.isNotEmpty()

/** Whether a logical type still mentions a parameter owned by [owner]. */
internal fun IrType.referencesTypeParameterOf(owner: IrClass): Boolean {
    val simpleType = this as? IrSimpleType ?: return false
    val parameter = (simpleType.classifier as? IrTypeParameterSymbol)?.owner
    if (parameter?.parent == owner) return true
    return simpleType.arguments.any { argument ->
        (argument as? IrTypeProjection)?.type?.referencesTypeParameterOf(owner) == true
    }
}
