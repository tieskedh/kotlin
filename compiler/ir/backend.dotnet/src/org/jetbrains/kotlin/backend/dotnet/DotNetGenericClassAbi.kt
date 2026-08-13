/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
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

/** Logical authority of one value position in a physical generic-owner MethodDef. */
enum class DotNetGenericOwnerPhysicalSlotDomain {
    DECLARATION_INDEPENDENT,
    OWNER_EXACT_RECEIVER,
    STRICT_OWNER_INPUT,
    STRICT_OWNER_OUTPUT,
    BROAD_CANDIDATE_INPUT,
}

/**
 * Preserves a widened Kotlin input contract across an override family. Strict and
 * declaration-independent positions remain properties of the overriding declaration after type
 * substitution, but a broad candidate is semantic authority inherited from every override root.
 */
internal fun mergeDotNetGenericOwnerParameterSlotDomains(
    local: List<DotNetGenericOwnerPhysicalSlotDomain>,
    inherited: List<DotNetGenericOwnerPhysicalSlotDomain>,
): List<DotNetGenericOwnerPhysicalSlotDomain> {
    require(local.size == inherited.size) {
        "generic-owner override families disagree on physical parameter count"
    }
    val validParameterDomains = setOf(
        DotNetGenericOwnerPhysicalSlotDomain.DECLARATION_INDEPENDENT,
        DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_INPUT,
        DotNetGenericOwnerPhysicalSlotDomain.BROAD_CANDIDATE_INPUT,
    )
    require(local.all { domain -> domain in validParameterDomains } &&
            inherited.all { domain -> domain in validParameterDomains }) {
        "generic-owner override families contain a non-parameter slot domain"
    }
    return local.zip(inherited) { localDomain, inheritedDomain ->
        if (localDomain == DotNetGenericOwnerPhysicalSlotDomain.BROAD_CANDIDATE_INPUT ||
            inheritedDomain == DotNetGenericOwnerPhysicalSlotDomain.BROAD_CANDIDATE_INPUT
        ) {
            DotNetGenericOwnerPhysicalSlotDomain.BROAD_CANDIDATE_INPUT
        } else {
            localDomain
        }
    }
}

/** Structural vocabulary for a profile-neutral CLR signature type. */
enum class DotNetGenericOwnerPhysicalTypeKind {
    VOID,
    BOOLEAN,
    INT32,
    STRING,
    OBJECT,
    OWNER_TYPE_PARAMETER,
    METHOD_TYPE_PARAMETER,
    NAMED,
    SZ_ARRAY,
}

/** Resolution scope of a named physical type. */
enum class DotNetGenericOwnerPhysicalTypeScope {
    PRODUCER,
    CORE_LIBRARY,
    ASSEMBLY,
}

enum class DotNetGenericOwnerPhysicalNamedTypeCategory {
    CLASS,
    VALUE_TYPE,
}

/**
 * Neutral physical type expression used by the architecture artifact.
 *
 * It deliberately contains no IL spelling. Core-library scope remains stable between mscorlib
 * and System.Runtime profiles, while producer scope is tied to the artifact fingerprint.
 */
data class DotNetGenericOwnerPhysicalTypeExpressionRecord(
    val kind: DotNetGenericOwnerPhysicalTypeKind,
    val parameterIndex: Int? = null,
    val scope: DotNetGenericOwnerPhysicalTypeScope? = null,
    val assemblyName: String? = null,
    val typePath: List<String> = emptyList(),
    val genericArity: Int = 0,
    val namedTypeCategory: DotNetGenericOwnerPhysicalNamedTypeCategory? = null,
    val arguments: List<DotNetGenericOwnerPhysicalTypeExpressionRecord> = emptyList(),
) {
    init {
        when (kind) {
            DotNetGenericOwnerPhysicalTypeKind.VOID,
            DotNetGenericOwnerPhysicalTypeKind.BOOLEAN,
            DotNetGenericOwnerPhysicalTypeKind.INT32,
            DotNetGenericOwnerPhysicalTypeKind.STRING,
            DotNetGenericOwnerPhysicalTypeKind.OBJECT,
            -> require(parameterIndex == null && scope == null && assemblyName == null &&
                    typePath.isEmpty() && genericArity == 0 && namedTypeCategory == null && arguments.isEmpty()) {
                "a built-in generic-owner physical type cannot carry structural payload"
            }
            DotNetGenericOwnerPhysicalTypeKind.OWNER_TYPE_PARAMETER,
            DotNetGenericOwnerPhysicalTypeKind.METHOD_TYPE_PARAMETER,
            -> require(parameterIndex != null && parameterIndex >= 0 && scope == null && assemblyName == null &&
                    typePath.isEmpty() && genericArity == 0 && namedTypeCategory == null && arguments.isEmpty()) {
                "a generic-owner physical type parameter requires only a non-negative index"
            }
            DotNetGenericOwnerPhysicalTypeKind.NAMED -> {
                require(parameterIndex == null && scope != null &&
                        typePath.isNotEmpty() && typePath.all(String::isNotEmpty) &&
                        genericArity >= 0 && arguments.size == genericArity && namedTypeCategory != null &&
                        arguments.none { argument -> argument.kind == DotNetGenericOwnerPhysicalTypeKind.VOID }) {
                    "a named generic-owner physical type requires scope, path, category, arity, and exact arguments"
                }
                require((scope == DotNetGenericOwnerPhysicalTypeScope.ASSEMBLY) == !assemblyName.isNullOrEmpty()) {
                    "only an assembly-scoped generic-owner physical type names an assembly"
                }
            }
            DotNetGenericOwnerPhysicalTypeKind.SZ_ARRAY -> require(
                parameterIndex == null && scope == null && assemblyName == null && typePath.isEmpty() &&
                        genericArity == 0 && namedTypeCategory == null && arguments.size == 1 &&
                        arguments.single().kind != DotNetGenericOwnerPhysicalTypeKind.VOID
            ) {
                "a generic-owner SZ-array type requires exactly one non-void element type"
            }
        }
    }

    companion object {
        fun voidType() = DotNetGenericOwnerPhysicalTypeExpressionRecord(DotNetGenericOwnerPhysicalTypeKind.VOID)
        fun booleanType() = DotNetGenericOwnerPhysicalTypeExpressionRecord(DotNetGenericOwnerPhysicalTypeKind.BOOLEAN)
        fun int32Type() = DotNetGenericOwnerPhysicalTypeExpressionRecord(DotNetGenericOwnerPhysicalTypeKind.INT32)
        fun stringType() = DotNetGenericOwnerPhysicalTypeExpressionRecord(DotNetGenericOwnerPhysicalTypeKind.STRING)
        fun objectType() = DotNetGenericOwnerPhysicalTypeExpressionRecord(DotNetGenericOwnerPhysicalTypeKind.OBJECT)
        fun ownerParameter(index: Int) = DotNetGenericOwnerPhysicalTypeExpressionRecord(
            DotNetGenericOwnerPhysicalTypeKind.OWNER_TYPE_PARAMETER,
            parameterIndex = index,
        )
        fun methodParameter(index: Int) = DotNetGenericOwnerPhysicalTypeExpressionRecord(
            DotNetGenericOwnerPhysicalTypeKind.METHOD_TYPE_PARAMETER,
            parameterIndex = index,
        )
        fun producerType(
            typePath: List<String>,
            category: DotNetGenericOwnerPhysicalNamedTypeCategory,
            arguments: List<DotNetGenericOwnerPhysicalTypeExpressionRecord> = emptyList(),
        ) = DotNetGenericOwnerPhysicalTypeExpressionRecord(
            kind = DotNetGenericOwnerPhysicalTypeKind.NAMED,
            scope = DotNetGenericOwnerPhysicalTypeScope.PRODUCER,
            typePath = typePath,
            genericArity = arguments.size,
            namedTypeCategory = category,
            arguments = arguments,
        )
        fun coreType(
            typePath: List<String>,
            category: DotNetGenericOwnerPhysicalNamedTypeCategory,
            arguments: List<DotNetGenericOwnerPhysicalTypeExpressionRecord> = emptyList(),
        ) = DotNetGenericOwnerPhysicalTypeExpressionRecord(
            kind = DotNetGenericOwnerPhysicalTypeKind.NAMED,
            scope = DotNetGenericOwnerPhysicalTypeScope.CORE_LIBRARY,
            typePath = typePath,
            genericArity = arguments.size,
            namedTypeCategory = category,
            arguments = arguments,
        )
        fun szArray(elementType: DotNetGenericOwnerPhysicalTypeExpressionRecord) =
            DotNetGenericOwnerPhysicalTypeExpressionRecord(
                kind = DotNetGenericOwnerPhysicalTypeKind.SZ_ARRAY,
                arguments = listOf(elementType),
            )
    }
}

data class DotNetGenericOwnerPhysicalValueSlotRecord(
    val domain: DotNetGenericOwnerPhysicalSlotDomain,
    val type: DotNetGenericOwnerPhysicalTypeExpressionRecord,
)

/** Complete physical MethodDef signature plus the logical domain of every value position. */
data class DotNetGenericOwnerPhysicalMethodSignatureRecord(
    val isInstance: Boolean,
    val genericArity: Int,
    val returnSlot: DotNetGenericOwnerPhysicalValueSlotRecord,
    val parameterSlots: List<DotNetGenericOwnerPhysicalValueSlotRecord>,
) {
    init {
        require(genericArity >= 0) { "a generic-owner physical method requires non-negative generic arity" }
        require(returnSlot.domain !in setOf(
            DotNetGenericOwnerPhysicalSlotDomain.OWNER_EXACT_RECEIVER,
            DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_INPUT,
            DotNetGenericOwnerPhysicalSlotDomain.BROAD_CANDIDATE_INPUT,
        )) { "a generic-owner physical return has an input-only slot domain" }
        require(returnSlot.type.kind != DotNetGenericOwnerPhysicalTypeKind.VOID ||
                returnSlot.domain == DotNetGenericOwnerPhysicalSlotDomain.DECLARATION_INDEPENDENT) {
            "a void generic-owner physical return must be declaration-independent"
        }
        require(parameterSlots.none { slot ->
            slot.domain == DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_OUTPUT ||
                    slot.type.kind == DotNetGenericOwnerPhysicalTypeKind.VOID
        }) { "a generic-owner physical parameter has an invalid domain or void type" }
        require(!isInstance || parameterSlots.none { slot ->
            slot.domain == DotNetGenericOwnerPhysicalSlotDomain.OWNER_EXACT_RECEIVER
        }) {
            "an instance generic-owner physical method cannot expose a separate exact receiver"
        }
        require(returnSlot.type.kind == DotNetGenericOwnerPhysicalTypeKind.VOID ||
                returnSlot.domain != DotNetGenericOwnerPhysicalSlotDomain.DECLARATION_INDEPENDENT ||
                !returnSlot.type.referencesOwnerParameter()) {
            "an owner-dependent physical return requires an owner slot domain"
        }
        require(parameterSlots.none { slot ->
            slot.domain == DotNetGenericOwnerPhysicalSlotDomain.DECLARATION_INDEPENDENT &&
                    slot.type.referencesOwnerParameter()
        }) { "an owner-dependent physical parameter requires an owner slot domain" }
        require(allTypes().flatMap { type -> type.typeParameterReferences() }.all { reference ->
            reference.first != DotNetGenericOwnerPhysicalTypeKind.METHOD_TYPE_PARAMETER || reference.second < genericArity
        }) { "a generic-owner physical signature references a missing method type parameter" }
    }

    internal fun allTypes(): List<DotNetGenericOwnerPhysicalTypeExpressionRecord> =
        listOf(returnSlot.type) + parameterSlots.map { slot -> slot.type }
}

data class DotNetGenericOwnerPhysicalMethodIdentityRecord(
    val physicalOwnerPath: List<String>,
    val physicalMethodName: String,
    val signature: DotNetGenericOwnerPhysicalMethodSignatureRecord,
) {
    init {
        require(physicalOwnerPath.isNotEmpty() && physicalOwnerPath.all(String::isNotEmpty)) {
            "a generic-owner physical method identity requires an owner path"
        }
        require(physicalMethodName.isNotEmpty()) {
            "a generic-owner physical method identity requires a method name"
        }
    }
}

/** Exact target profile whose reference-assembly contract interprets one artifact. */
enum class DotNetGenericOwnerPhysicalTargetProfile {
    NET48,
    NETSTANDARD_2_0,
    NET10_0,
}

/** Runtime classification is rooted in recorded TypeDef ancestry, never name/arity heuristics. */
enum class DotNetGenericOwnerRuntimeClassificationMode {
    OPEN_TYPEDEF_ANCESTRY,
}

/** Construction paths admitted by the current production-inert architecture record. */
enum class DotNetGenericOwnerConstructionMode {
    STATIC_EXACT,
}

enum class DotNetGenericOwnerConstructorDelegationKind {
    THIS,
    BASE,
}

enum class DotNetGenericOwnerPhysicalConstructorVisibility {
    PUBLIC,
    FAMILY,
    ASSEMBLY,
    FAMILY_OR_ASSEMBLY,
    PRIVATE,
}

/** Exact `this`/`base` constructor MemberRef selected by one constructor body. */
data class DotNetGenericOwnerPhysicalDelegatingConstructorRecord(
    val kind: DotNetGenericOwnerConstructorDelegationKind,
    val logicalConstructorKey: String?,
    val physicalOwnerType: DotNetGenericOwnerPhysicalTypeExpressionRecord,
    val physicalMethodName: String,
    val signature: DotNetGenericOwnerPhysicalMethodSignatureRecord,
) {
    init {
        require(logicalConstructorKey == null || logicalConstructorKey.isNotEmpty()) {
            "a generic-owner delegated constructor has an empty logical key"
        }
        require(physicalOwnerType.kind == DotNetGenericOwnerPhysicalTypeKind.NAMED) {
            "a generic-owner delegated constructor requires a named physical owner type"
        }
        require(physicalMethodName == ".ctor" && signature.isInstance &&
                signature.returnSlot.type.kind == DotNetGenericOwnerPhysicalTypeKind.VOID) {
            "a generic-owner delegated constructor requires an instance .ctor signature"
        }
    }
}

/** Producer-selected physical constructor and its exact first construction edge. */
data class DotNetGenericOwnerPhysicalConstructorRecord(
    val logicalConstructorKey: String,
    val constructionMode: DotNetGenericOwnerConstructionMode,
    val visibility: DotNetGenericOwnerPhysicalConstructorVisibility,
    val constructedOwnerType: DotNetGenericOwnerPhysicalTypeExpressionRecord,
    val physicalConstructor: DotNetGenericOwnerPhysicalMethodIdentityRecord,
    val delegation: DotNetGenericOwnerPhysicalDelegatingConstructorRecord,
) {
    init {
        require(logicalConstructorKey.isNotEmpty()) {
            "a generic-owner physical constructor requires a logical key"
        }
        require(physicalConstructor.physicalMethodName == ".ctor" &&
                physicalConstructor.signature.isInstance &&
                physicalConstructor.signature.returnSlot.type.kind == DotNetGenericOwnerPhysicalTypeKind.VOID) {
            "a generic-owner physical constructor requires an instance .ctor MethodDef"
        }
        require(constructedOwnerType.kind == DotNetGenericOwnerPhysicalTypeKind.NAMED &&
                constructedOwnerType.scope == DotNetGenericOwnerPhysicalTypeScope.PRODUCER &&
                constructedOwnerType.typePath == physicalConstructor.physicalOwnerPath) {
            "a generic-owner physical constructor requires its exact constructed producer owner"
        }
        when (delegation.kind) {
            DotNetGenericOwnerConstructorDelegationKind.THIS -> require(
                delegation.logicalConstructorKey != null && delegation.physicalOwnerType == constructedOwnerType
            ) {
                "a generic-owner this-constructor edge requires an exact constructor on the same construction"
            }
            DotNetGenericOwnerConstructorDelegationKind.BASE -> require(
                delegation.physicalOwnerType != constructedOwnerType
            ) {
                "a generic-owner base-constructor edge cannot target the constructed owner"
            }
        }
    }
}

/** Producer-scoped open TypeDef identity used for logical classifier normalization. */
data class DotNetGenericOwnerPhysicalOpenTypeDefinitionRecord(
    val physicalTypePath: List<String>,
    val genericArity: Int,
) {
    init {
        require(physicalTypePath.isNotEmpty() && physicalTypePath.all(String::isNotEmpty)) {
            "a generic-owner open TypeDef requires a complete physical path"
        }
        require(genericArity > 0) { "a generic-owner open TypeDef requires positive arity" }
    }
}

enum class DotNetGenericOwnerReflectionClassifierNormalizationMode {
    EXACT_OPEN_TYPEDEF,
}

enum class DotNetGenericOwnerReflectionTypeArgumentAuthority {
    KLIB_LOGICAL_GRAPH,
}

enum class DotNetGenericOwnerReflectionCapabilityExposure {
    HIDDEN_COMPILER_ABI,
}

enum class DotNetGenericOwnerReflectionCallableExposure {
    SINGLE_LOGICAL_DECLARATION,
}

/** One logical callable and every physical MethodDef collapsed into that declaration. */
data class DotNetGenericOwnerPhysicalCallableReflectionRecord(
    val logicalMemberKey: String,
    val exposure: DotNetGenericOwnerReflectionCallableExposure,
    val invocationRole: DotNetGenericOwnerMemberFamilyRole,
    val invocationMethod: DotNetGenericOwnerPhysicalMethodIdentityRecord,
    val physicalMethods: List<DotNetGenericOwnerPhysicalMethodIdentityRecord>,
) {
    init {
        require(logicalMemberKey.isNotEmpty()) { "a generic-owner reflected callable requires a logical key" }
        require(invocationRole == DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY ||
                invocationRole == DotNetGenericOwnerMemberFamilyRole.CAPABILITY_DISPATCHER) {
            "a generic-owner reflected callable requires a typed or semantic dispatcher invocation entry"
        }
        require(physicalMethods.isNotEmpty() && physicalMethods.toSet().size == physicalMethods.size &&
                invocationMethod in physicalMethods) {
            "a generic-owner reflected callable requires one complete physical MethodDef family"
        }
    }
}

/** Producer-selected normalization from physical CLR evidence to one logical Kotlin classifier. */
data class DotNetGenericOwnerPhysicalReflectionRecord(
    val logicalClassifierKey: String,
    val physicalOpenTypeDefinition: DotNetGenericOwnerPhysicalOpenTypeDefinitionRecord,
    val classifierNormalizationMode: DotNetGenericOwnerReflectionClassifierNormalizationMode,
    val instanceClassificationMode: DotNetGenericOwnerRuntimeClassificationMode,
    val typeArgumentAuthority: DotNetGenericOwnerReflectionTypeArgumentAuthority,
    val capabilityExposure: DotNetGenericOwnerReflectionCapabilityExposure,
    val callables: List<DotNetGenericOwnerPhysicalCallableReflectionRecord>,
) {
    init {
        require(logicalClassifierKey.isNotEmpty()) {
            "a generic-owner reflection record requires a logical classifier key"
        }
        require(callables.map { callable -> callable.logicalMemberKey }.toSet().size == callables.size) {
            "a generic-owner reflection record has duplicate logical callables"
        }
    }
}

enum class DotNetGenericOwnerPhysicalStateAccessDomain {
    TYPED,
    SEMANTIC,
}

enum class DotNetGenericOwnerPhysicalStateAccessOperation {
    READ,
    WRITE,
}

/** Explicit conversion at the one physical state boundary. */
enum class DotNetGenericOwnerPhysicalStateAccessConversion {
    IDENTITY,
    INPUT_TO_STATE_BOX_OR_REFERENCE_WIDEN,
    STATE_TO_OUTPUT_CHECKED_CAST_OR_UNBOX,
}

enum class DotNetGenericOwnerPhysicalStateVisibility {
    PRIVATE,
}

/** One exact member-family path which reads or writes a producer-owned physical field. */
data class DotNetGenericOwnerPhysicalStateAccessRecord(
    val domain: DotNetGenericOwnerPhysicalStateAccessDomain,
    val operation: DotNetGenericOwnerPhysicalStateAccessOperation,
    val conversion: DotNetGenericOwnerPhysicalStateAccessConversion,
    val logicalMemberKey: String,
    val role: DotNetGenericOwnerMemberFamilyRole,
    val physicalMethod: DotNetGenericOwnerPhysicalMethodIdentityRecord,
) {
    init {
        require(logicalMemberKey.isNotEmpty()) { "a generic-owner state access requires a logical member key" }
        require(role == when (domain) {
            DotNetGenericOwnerPhysicalStateAccessDomain.TYPED -> DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY
            DotNetGenericOwnerPhysicalStateAccessDomain.SEMANTIC -> DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK
        }) { "a generic-owner state access domain disagrees with its member-family role" }
        require(physicalMethod.signature.isInstance) {
            "a generic-owner state access requires an instance MethodDef"
        }
        when (operation) {
            DotNetGenericOwnerPhysicalStateAccessOperation.READ -> require(
                physicalMethod.signature.parameterSlots.isEmpty() &&
                        physicalMethod.signature.returnSlot.type.kind != DotNetGenericOwnerPhysicalTypeKind.VOID
            ) { "a generic-owner state read requires a parameterless value-returning MethodDef" }
            DotNetGenericOwnerPhysicalStateAccessOperation.WRITE -> require(
                physicalMethod.signature.parameterSlots.size == 1 &&
                        physicalMethod.signature.returnSlot.type.kind == DotNetGenericOwnerPhysicalTypeKind.VOID
            ) { "a generic-owner state write requires one parameter and a void MethodDef" }
        }
        require(conversion == DotNetGenericOwnerPhysicalStateAccessConversion.IDENTITY ||
                domain == DotNetGenericOwnerPhysicalStateAccessDomain.TYPED) {
            "a semantic generic-owner state access cannot perform a typed conversion"
        }
        require(conversion != DotNetGenericOwnerPhysicalStateAccessConversion.INPUT_TO_STATE_BOX_OR_REFERENCE_WIDEN ||
                operation == DotNetGenericOwnerPhysicalStateAccessOperation.WRITE) {
            "a state-input widening conversion belongs only to writes"
        }
        require(conversion != DotNetGenericOwnerPhysicalStateAccessConversion.STATE_TO_OUTPUT_CHECKED_CAST_OR_UNBOX ||
                operation == DotNetGenericOwnerPhysicalStateAccessOperation.READ) {
            "a checked state-output conversion belongs only to reads"
        }
    }
}

private fun DotNetGenericOwnerPhysicalTypeExpressionRecord.referencesOwnerParameter(): Boolean =
    kind == DotNetGenericOwnerPhysicalTypeKind.OWNER_TYPE_PARAMETER || arguments.any { it.referencesOwnerParameter() }

private fun DotNetGenericOwnerPhysicalTypeExpressionRecord.typeParameterReferences(): List<Pair<DotNetGenericOwnerPhysicalTypeKind, Int>> =
    buildList {
        parameterIndex?.let { index -> add(kind to index) }
        arguments.forEach { argument -> addAll(argument.typeParameterReferences()) }
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
    val overriddenPhysicalSignature: DotNetGenericOwnerPhysicalMethodSignatureRecord?,
    val overriddenCapabilitySlot: DotNetGenericOwnerPhysicalMethodIdentityRecord?,
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
    val returnSlotDomain: DotNetGenericOwnerPhysicalSlotDomain,
    val parameterSlotDomains: List<DotNetGenericOwnerPhysicalSlotDomain>,
    val roles: Set<DotNetGenericOwnerMemberFamilyRole>,
    val semanticHookReasons: Set<DotNetGenericOwnerSemanticHookReason>,
    val requiresDirectSuperTargets: Boolean,
    val directSuperCallCount: Int,
    val directSuperCalls: List<DotNetGenericOwnerDirectSuperCallPlan>,
    val hasMaskedDefaultDispatcher: Boolean,
    val logicalBindingKey: String?,
)

internal data class DotNetGenericOwnerConstructorPlan(
    val source: IrConstructor,
    val logicalBindingKey: String?,
    val parameterSlotDomains: List<DotNetGenericOwnerPhysicalSlotDomain>,
    val delegatedConstructorLogicalBindingKey: String?,
    val delegatedOwnerName: String?,
    val delegatesToThis: Boolean,
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
    val returnSlotDomain: DotNetGenericOwnerPhysicalSlotDomain,
    val parameterSlotDomains: List<DotNetGenericOwnerPhysicalSlotDomain>,
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

/** Logical construction join retained before any future physical constructor is selected. */
data class DotNetGenericOwnerPrototypeConstructorSnapshot(
    val sourceIndex: Int,
    val logicalBindingKey: String?,
    val parameterSlotDomains: List<DotNetGenericOwnerPhysicalSlotDomain>,
    val delegatedConstructorLogicalBindingKey: String?,
    val delegatedOwnerName: String?,
    val delegatesToThis: Boolean,
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
    val constructors: List<DotNetGenericOwnerPrototypeConstructorSnapshot>,
    val members: List<DotNetGenericOwnerPrototypeMemberSnapshot>,
    val states: List<DotNetGenericOwnerPrototypeStateSnapshot>,
    val metadataFixedConditionalSupertypeCount: Int,
)

/** One producer-selected physical MethodDef role in a future CLR-generic member family. */
data class DotNetGenericOwnerPhysicalMemberSlotRecord(
    val role: DotNetGenericOwnerMemberFamilyRole,
    val physicalOwnerPath: List<String>,
    val physicalMethodName: String,
    val dispatch: DotNetGenericOwnerPhysicalMemberDispatch,
    val signature: DotNetGenericOwnerPhysicalMethodSignatureRecord,
    val capabilitySlot: DotNetGenericOwnerPhysicalMethodIdentityRecord?,
) {
    init {
        require(physicalOwnerPath.isNotEmpty() && physicalOwnerPath.all(String::isNotEmpty)) {
            "a generic-owner physical member slot requires an owner path"
        }
        require(physicalMethodName.isNotEmpty()) { "a generic-owner physical member slot requires a method name" }
        if (role == DotNetGenericOwnerMemberFamilyRole.CAPABILITY_DISPATCHER) {
            require(dispatch == DotNetGenericOwnerPhysicalMemberDispatch.FINAL) {
                "a generic-owner capability dispatcher must remain a final non-override slot"
            }
            require(capabilitySlot != null && capabilitySlot.signature == signature) {
                "a generic-owner capability dispatcher requires its exact matching capability slot"
            }
            require(signature.allTypes().none { type -> type.referencesOwnerParameter() }) {
                "a non-generic capability slot cannot expose an owner type parameter"
            }
        } else {
            require(capabilitySlot == null) {
                "only a generic-owner capability dispatcher may implement a capability slot"
            }
        }
    }
}

data class DotNetGenericOwnerPhysicalDirectSuperTargetRecord(
    val role: DotNetGenericOwnerMemberFamilyRole,
    val logicalTargetMemberKey: String,
    val physicalOwnerPath: List<String>,
    val physicalMethodName: String,
    val signature: DotNetGenericOwnerPhysicalMethodSignatureRecord,
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
    val signature: DotNetGenericOwnerPhysicalMethodSignatureRecord,
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
        require(slots.map { slot ->
            slot.signature.returnSlot.domain to
                    slot.signature.parameterSlots.map { parameter -> parameter.domain }
        }.distinct().size == 1) {
            "generic-owner physical member family '$logicalMemberKey' has inconsistent role slot-domain vectors"
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
        require(directSuperTargets.all { target ->
            slots.single { slot -> slot.role == target.role }.signature == target.signature
        }) {
            "generic-owner physical member family '$logicalMemberKey' has a direct-super signature mismatch"
        }
    }
}

/** Producer-selected physical carrier for one logical owner-dependent field. */
data class DotNetGenericOwnerPhysicalStateRecord(
    val logicalFieldName: String,
    val physicalFieldName: String,
    val physicalVisibility: DotNetGenericOwnerPhysicalStateVisibility,
    val requirement: DotNetGenericOwnerStateCarrierRequirement,
    val physicalType: DotNetGenericOwnerPhysicalTypeExpressionRecord,
    val accessPaths: List<DotNetGenericOwnerPhysicalStateAccessRecord>,
) {
    init {
        require(logicalFieldName.isNotEmpty() && physicalFieldName.isNotEmpty()) {
            "a generic-owner physical state record requires logical and physical field names"
        }
        require(physicalType.kind != DotNetGenericOwnerPhysicalTypeKind.VOID) {
            "a generic-owner physical state record cannot use void storage"
        }
        require(accessPaths.map { access -> access.domain to access.operation }.toSet().size == accessPaths.size) {
            "a generic-owner physical state record has duplicate domain/operation access paths"
        }
        require(accessPaths.isNotEmpty()) {
            "a generic-owner physical state record requires exact access paths"
        }
        when (requirement) {
            DotNetGenericOwnerStateCarrierRequirement.SEMANTIC_OBJECT_REQUIRED -> {
                require(physicalType == DotNetGenericOwnerPhysicalTypeExpressionRecord.objectType()) {
                    "semantic-object generic-owner state requires object storage"
                }
                val pathsByOperation = accessPaths.groupBy { access -> access.operation }
                require(pathsByOperation.keys == DotNetGenericOwnerPhysicalStateAccessOperation.entries.toSet() &&
                        pathsByOperation.values.all { operationPaths ->
                            operationPaths.map { access -> access.domain }.toSet() ==
                                    DotNetGenericOwnerPhysicalStateAccessDomain.entries.toSet()
                        }) {
                    "semantic-object generic-owner state requires paired typed and semantic reads and writes"
                }
            }
            DotNetGenericOwnerStateCarrierRequirement.TYPED_STORAGE_PRODUCER_GRAPH_PROVEN -> require(
                physicalType.referencesOwnerParameter() &&
                        accessPaths.map { access -> access.operation }.toSet() ==
                        DotNetGenericOwnerPhysicalStateAccessOperation.entries.toSet() &&
                        accessPaths.all { access ->
                            access.domain == DotNetGenericOwnerPhysicalStateAccessDomain.TYPED &&
                                    access.conversion == DotNetGenericOwnerPhysicalStateAccessConversion.IDENTITY
                        }
            ) {
                "typed generic-owner state requires exact typed identity read and write paths"
            }
            DotNetGenericOwnerStateCarrierRequirement.COMPLETE_ACCESS_GRAPH_REQUIRED,
            DotNetGenericOwnerStateCarrierRequirement.TYPED_WRITE_VALUE_PROVENANCE_REQUIRED,
            -> error("an unresolved generic-owner state requirement cannot enter a physical family")
        }
        accessPaths.forEach { access ->
            val valueType = when (access.operation) {
                DotNetGenericOwnerPhysicalStateAccessOperation.READ -> access.physicalMethod.signature.returnSlot.type
                DotNetGenericOwnerPhysicalStateAccessOperation.WRITE ->
                    access.physicalMethod.signature.parameterSlots.single().type
            }
            require(if (access.conversion == DotNetGenericOwnerPhysicalStateAccessConversion.IDENTITY) {
                valueType == physicalType
            } else {
                physicalType == DotNetGenericOwnerPhysicalTypeExpressionRecord.objectType() &&
                        valueType.referencesOwnerParameter()
            }) {
                "generic-owner state '$logicalFieldName' has an incompatible access conversion"
            }
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
    val runtimeClassificationMode: DotNetGenericOwnerRuntimeClassificationMode,
    val constructionModes: Set<DotNetGenericOwnerConstructionMode>,
    val constructors: List<DotNetGenericOwnerPhysicalConstructorRecord>,
    val reflection: DotNetGenericOwnerPhysicalReflectionRecord,
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
        require(constructionModes == constructors.map { constructor -> constructor.constructionMode }.toSet() &&
                constructors.isNotEmpty()) {
            "generic-owner physical family '$logicalOwnerKey' has incomplete construction modes"
        }
        require(constructors.map { constructor -> constructor.logicalConstructorKey }.toSet().size == constructors.size) {
            "generic-owner physical family '$logicalOwnerKey' has duplicate logical constructors"
        }
        require(constructors.map { constructor -> constructor.physicalConstructor }.toSet().size == constructors.size) {
            "generic-owner physical family '$logicalOwnerKey' has duplicate physical constructors"
        }
        require(constructors.all { constructor ->
            constructor.physicalConstructor.physicalOwnerPath == physicalOwnerPath &&
                    constructor.constructedOwnerType.genericArity == genericArity &&
                    constructor.constructedOwnerType.arguments.map { argument -> argument.parameterIndex } ==
                    (0 until genericArity).toList() &&
                    constructor.constructedOwnerType.arguments.all { argument ->
                        argument.kind == DotNetGenericOwnerPhysicalTypeKind.OWNER_TYPE_PARAMETER
                    }
        }) {
            "generic-owner physical family '$logicalOwnerKey' has an invalid constructed owner"
        }
        require(reflection.logicalClassifierKey == logicalOwnerKey &&
                reflection.physicalOpenTypeDefinition.physicalTypePath == physicalOwnerPath &&
                reflection.physicalOpenTypeDefinition.genericArity == genericArity &&
                reflection.instanceClassificationMode == runtimeClassificationMode) {
            "generic-owner physical family '$logicalOwnerKey' has an inconsistent reflection classifier"
        }
        require(members.map { member -> member.logicalMemberKey }.toSet().size == members.size) {
            "generic-owner physical family '$logicalOwnerKey' has duplicate logical members"
        }
        val membersByLogicalKey = members.associateBy { member -> member.logicalMemberKey }
        require(reflection.callables.map { callable -> callable.logicalMemberKey }.toSet() ==
                membersByLogicalKey.keys) {
            "generic-owner physical family '$logicalOwnerKey' has incomplete reflected callables"
        }
        require(reflection.callables.all { callable ->
            val member = membersByLogicalKey.getValue(callable.logicalMemberKey)
            val expectedInvocationRole = if (DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK in member.roles) {
                DotNetGenericOwnerMemberFamilyRole.CAPABILITY_DISPATCHER
            } else {
                DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY
            }
            val invocationSlot = member.slots.singleOrNull { slot -> slot.role == callable.invocationRole }
            val expectedPhysicalMethods = buildSet {
                member.slots.forEach { slot ->
                    add(DotNetGenericOwnerPhysicalMethodIdentityRecord(
                        slot.physicalOwnerPath,
                        slot.physicalMethodName,
                        slot.signature,
                    ))
                    slot.capabilitySlot?.let(::add)
                }
                member.defaultDispatcher?.let { dispatcher ->
                    add(DotNetGenericOwnerPhysicalMethodIdentityRecord(
                        dispatcher.physicalOwnerPath,
                        dispatcher.physicalMethodName,
                        dispatcher.signature,
                    ))
                }
            }
            callable.invocationRole == expectedInvocationRole && invocationSlot != null &&
                    callable.invocationMethod == DotNetGenericOwnerPhysicalMethodIdentityRecord(
                        invocationSlot.physicalOwnerPath,
                        invocationSlot.physicalMethodName,
                        invocationSlot.signature,
                    ) && callable.physicalMethods.toSet() == expectedPhysicalMethods
        }) {
            "generic-owner physical family '$logicalOwnerKey' has a reflected callable outside its physical family"
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
        val recordedMethods = buildList {
            members.forEach { member ->
                member.slots.forEach { slot ->
                    add(slot.physicalOwnerPath to slot.signature)
                    slot.capabilitySlot?.let { capability -> add(capability.physicalOwnerPath to capability.signature) }
                }
                member.directSuperTargets.forEach { target -> add(target.physicalOwnerPath to target.signature) }
                member.defaultDispatcher?.let { dispatcher -> add(dispatcher.physicalOwnerPath to dispatcher.signature) }
            }
        }
        require((recordedMethods.flatMap { recordedMethod -> recordedMethod.second.allTypes() } +
                states.map { state -> state.physicalType })
            .flatMap { type -> type.typeParameterReferences() }
            .all { reference ->
                reference.first != DotNetGenericOwnerPhysicalTypeKind.OWNER_TYPE_PARAMETER ||
                        reference.second < genericArity
            }) {
            "generic-owner physical family '$logicalOwnerKey' references a missing owner type parameter"
        }
        require(members.flatMap { member -> member.slots }.all { slot ->
            slot.role != DotNetGenericOwnerMemberFamilyRole.CAPABILITY_DISPATCHER ||
                    slot.capabilitySlot?.physicalOwnerPath == physicalCapabilityOwnerPath
        }) {
            "generic-owner physical family '$logicalOwnerKey' has a dispatcher for another capability owner"
        }
        val memberSlots = members.associate { member -> member.logicalMemberKey to member.slots.associateBy { it.role } }
        require(states.flatMap { state -> state.accessPaths }.all { access ->
            memberSlots[access.logicalMemberKey]?.get(access.role)?.let { slot ->
                slot.physicalOwnerPath == access.physicalMethod.physicalOwnerPath &&
                        slot.physicalMethodName == access.physicalMethod.physicalMethodName &&
                        slot.signature == access.physicalMethod.signature
            } == true
        }) {
            "generic-owner physical family '$logicalOwnerKey' has a state access outside its member slots"
        }
        require((constructors.flatMap { constructor ->
            constructor.physicalConstructor.signature.allTypes() + constructor.delegation.signature.allTypes() +
                    constructor.delegation.physicalOwnerType
        }).flatMap { type -> type.typeParameterReferences() }.all { reference ->
            reference.first != DotNetGenericOwnerPhysicalTypeKind.OWNER_TYPE_PARAMETER ||
                    reference.second < genericArity
        }) {
            "generic-owner physical family '$logicalOwnerKey' has a construction record with a missing owner parameter"
        }
    }
}

/** One detached, producer-fingerprinted family artifact used only by architecture evidence. */
data class DotNetGenericOwnerPhysicalFamilyArtifact(
    val producerFingerprint: String,
    val targetProfile: DotNetGenericOwnerPhysicalTargetProfile,
    val owners: List<DotNetGenericOwnerPhysicalFamilyRecord>,
) {
    init {
        require(PRODUCER_FINGERPRINT.matches(producerFingerprint)) {
            "a generic-owner family artifact requires a lowercase SHA-256 producer fingerprint"
        }
        require(owners.map { owner -> owner.logicalOwnerKey }.toSet().size == owners.size) {
            "a generic-owner family artifact has duplicate logical owners"
        }
        require(owners.map { owner -> owner.physicalOwnerPath }.toSet().size == owners.size) {
            "a generic-owner family artifact has duplicate physical owner TypeDefs"
        }
        val capabilityPaths = owners.mapNotNull { owner -> owner.physicalCapabilityOwnerPath }
        require(capabilityPaths.none { capabilityPath ->
            owners.any { owner -> owner.physicalOwnerPath == capabilityPath }
        }) {
            "a generic-owner family artifact confuses a capability TypeDef with a classifier TypeDef"
        }
        require(owners.flatMap { owner -> owner.members }.map { member -> member.logicalMemberKey }.toSet().size ==
                owners.sumOf { owner -> owner.members.size }) {
            "a generic-owner family artifact has duplicate logical members across owners"
        }
        val constructors = owners.flatMap { owner -> owner.constructors }
        require(constructors.map { constructor -> constructor.logicalConstructorKey }.toSet().size == constructors.size) {
            "a generic-owner family artifact has duplicate logical constructors across owners"
        }
        val constructorsByLogicalKey = constructors.associateBy { constructor -> constructor.logicalConstructorKey }
        require(constructors.all { constructor ->
            val delegation = constructor.delegation
            val target = delegation.logicalConstructorKey?.let(constructorsByLogicalKey::get)
            if (target == null) {
                delegation.physicalOwnerType.scope != DotNetGenericOwnerPhysicalTypeScope.PRODUCER
            } else {
                delegation.physicalOwnerType.scope == DotNetGenericOwnerPhysicalTypeScope.PRODUCER &&
                        delegation.physicalOwnerType.typePath == target.physicalConstructor.physicalOwnerPath &&
                        delegation.signature == target.physicalConstructor.signature &&
                        (delegation.kind != DotNetGenericOwnerConstructorDelegationKind.THIS ||
                                constructor.physicalConstructor.physicalOwnerPath ==
                                target.physicalConstructor.physicalOwnerPath) &&
                        (delegation.kind != DotNetGenericOwnerConstructorDelegationKind.BASE ||
                                constructor.physicalConstructor.physicalOwnerPath !=
                                target.physicalConstructor.physicalOwnerPath)
            }
        }) {
            "a generic-owner family artifact has a delegated construction edge which disagrees with its target"
        }
        constructors.forEach { root ->
            val visited = mutableSetOf<String>()
            var current: DotNetGenericOwnerPhysicalConstructorRecord? = root
            while (current != null) {
                require(visited.add(current.logicalConstructorKey)) {
                    "a generic-owner family artifact has a cyclic local constructor delegation"
                }
                current = current.delegation.logicalConstructorKey?.let(constructorsByLogicalKey::get)
            }
        }
    }

    private companion object {
        val PRODUCER_FINGERPRINT = Regex("[0-9a-f]{64}")
    }
}

/** Finds only an exact recorded producer open TypeDef; capability and foreign types return null. */
fun DotNetGenericOwnerPhysicalFamilyArtifact.reflectionClassifierForExactOpenTypeDefinitionOrNull(
    physicalOpenTypeDefinition: DotNetGenericOwnerPhysicalOpenTypeDefinitionRecord,
): DotNetGenericOwnerPhysicalReflectionRecord? = owners.singleOrNull { owner ->
    owner.reflection.physicalOpenTypeDefinition == physicalOpenTypeDefinition
}?.reflection

/** Tests a logical classifier against objective runtime ancestry already normalized to open TypeDefs. */
fun DotNetGenericOwnerPhysicalFamilyArtifact.reflectionClassifierMatchesAncestry(
    logicalClassifierKey: String,
    physicalOpenTypeDefinitionAncestry: List<DotNetGenericOwnerPhysicalOpenTypeDefinitionRecord>,
): Boolean {
    val reflection = owners.singleOrNull { owner -> owner.logicalOwnerKey == logicalClassifierKey }?.reflection
        ?: return false
    return reflection.physicalOpenTypeDefinition in physicalOpenTypeDefinitionAncestry
}

/**
 * Deterministic codec for the production-inert generic-owner family artifact.
 *
 * Counts precede every nested block and decoding constructs the whole validated artifact before
 * returning it. A stale, truncated, duplicated, or wrong-producer record can therefore never
 * resolve only a subset of one consumer's override obligations.
 */
object DotNetGenericOwnerPhysicalFamilyCodec {
    const val SCHEMA_VERSION = 5
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
        appendLine("Q\t${artifact.targetProfile.name}")
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
                    owner.runtimeClassificationMode.name,
                    owner.constructionModes.sortedBy { mode -> mode.name }.joinToString(",") { mode -> mode.name },
                    owner.constructors.size.toString(),
                    members.size.toString(),
                    states.size.toString(),
                ).joinToString("\t")
            )
            owner.constructors.sortedBy { constructor -> constructor.logicalConstructorKey }.forEach { constructor ->
                val delegation = constructor.delegation
                appendLine(
                    listOf(
                        "K",
                        constructor.logicalConstructorKey.encoded(),
                        constructor.constructionMode.name,
                        constructor.visibility.name,
                        constructor.constructedOwnerType.serialized().encoded(),
                        constructor.physicalConstructor.physicalOwnerPath.joinToString("\u0000").encoded(),
                        constructor.physicalConstructor.physicalMethodName.encoded(),
                        constructor.physicalConstructor.signature.serialized().encoded(),
                        delegation.kind.name,
                        delegation.logicalConstructorKey?.encoded() ?: "-",
                        delegation.physicalOwnerType.serialized().encoded(),
                        delegation.physicalMethodName.encoded(),
                        delegation.signature.serialized().encoded(),
                    ).joinToString("\t")
                )
            }
            val reflection = owner.reflection
            appendLine(
                listOf(
                    "F",
                    reflection.logicalClassifierKey.encoded(),
                    reflection.physicalOpenTypeDefinition.physicalTypePath.joinToString("\u0000").encoded(),
                    reflection.physicalOpenTypeDefinition.genericArity.toString(),
                    reflection.classifierNormalizationMode.name,
                    reflection.instanceClassificationMode.name,
                    reflection.typeArgumentAuthority.name,
                    reflection.capabilityExposure.name,
                    reflection.callables.size.toString(),
                ).joinToString("\t")
            )
            reflection.callables.sortedBy { callable -> callable.logicalMemberKey }.forEach { callable ->
                appendLine(
                    listOf(
                        "Y",
                        callable.logicalMemberKey.encoded(),
                        callable.exposure.name,
                        callable.invocationRole.name,
                        callable.invocationMethod.physicalOwnerPath.joinToString("\u0000").encoded(),
                        callable.invocationMethod.physicalMethodName.encoded(),
                        callable.invocationMethod.signature.serialized().encoded(),
                        callable.physicalMethods.size.toString(),
                    ).joinToString("\t")
                )
                callable.physicalMethods.sortedWith(
                    compareBy(
                        { method -> method.physicalOwnerPath.joinToString("\u0000") },
                        { method -> method.physicalMethodName },
                        { method -> method.signature.serialized() },
                    )
                ).forEach { method ->
                    appendLine(
                        listOf(
                            "Z",
                            method.physicalOwnerPath.joinToString("\u0000").encoded(),
                            method.physicalMethodName.encoded(),
                            method.signature.serialized().encoded(),
                        ).joinToString("\t")
                    )
                }
            }
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
                        listOf(
                            "R",
                            slot.role.name,
                            slot.dispatch.name,
                            slot.physicalOwnerPath.joinToString("\u0000").encoded(),
                            slot.physicalMethodName.encoded(),
                            slot.signature.serialized().encoded(),
                            if (slot.capabilitySlot == null) "0" else "1",
                        )
                            .joinToString("\t")
                    )
                    slot.capabilitySlot?.let { capability ->
                        appendLine(
                            listOf(
                                "C",
                                capability.physicalOwnerPath.joinToString("\u0000").encoded(),
                                capability.physicalMethodName.encoded(),
                                capability.signature.serialized().encoded(),
                            ).joinToString("\t")
                        )
                    }
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
                            target.signature.serialized().encoded(),
                        ).joinToString("\t")
                    )
                }
                member.defaultDispatcher?.let { dispatcher ->
                    appendLine(
                        listOf(
                            "A",
                            dispatcher.physicalOwnerPath.joinToString("\u0000").encoded(),
                            dispatcher.physicalMethodName.encoded(),
                            dispatcher.signature.serialized().encoded(),
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
                        state.physicalVisibility.name,
                        state.requirement.name,
                        state.physicalType.serialized().encoded(),
                        state.accessPaths.size.toString(),
                    ).joinToString("\t")
                )
                state.accessPaths.sortedWith(compareBy({ it.domain.name }, { it.operation.name })).forEach { access ->
                    appendLine(
                        listOf(
                            "X",
                            access.domain.name,
                            access.operation.name,
                            access.conversion.name,
                            access.logicalMemberKey.encoded(),
                            access.role.name,
                            access.physicalMethod.physicalOwnerPath.joinToString("\u0000").encoded(),
                            access.physicalMethod.physicalMethodName.encoded(),
                            access.physicalMethod.signature.serialized().encoded(),
                        ).joinToString("\t")
                    )
                }
            }
        }
    }

    fun decode(
        text: String,
        expectedProducerFingerprint: String? = null,
        expectedTargetProfile: DotNetGenericOwnerPhysicalTargetProfile? = null,
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
        val targetProfile = enumValue(
            read("Q", 2)[1],
            DotNetGenericOwnerPhysicalTargetProfile.entries.toTypedArray(),
            "target profile",
        )
        if (expectedTargetProfile != null) {
            require(targetProfile == expectedTargetProfile) {
                "generic-owner family artifact targets '$targetProfile', not '$expectedTargetProfile'"
            }
        }
        val ownerCount = count(read("N", 2)[1], "owner")
        val owners = List(ownerCount) {
            val fields = read("O", 11)
            val logicalOwnerKey = fields[1].decoded()
            val ownerPath = fields[2].decoded().split('\u0000')
            val capabilityOwnerPath = fields[3].takeUnless { it == "-" }?.decoded()?.split('\u0000')
            val genericArity = count(fields[4], "generic-arity")
            val disposition = enumValue(
                fields[5],
                DotNetGenericOwnerCandidateDisposition.entries.toTypedArray(),
                "owner disposition",
            )
            val runtimeClassificationMode = enumValue(
                fields[6],
                DotNetGenericOwnerRuntimeClassificationMode.entries.toTypedArray(),
                "runtime classification mode",
            )
            val constructionModes = enumSet(
                fields[7],
                DotNetGenericOwnerConstructionMode.entries.toTypedArray(),
                "construction mode",
            )
            val constructorCount = count(fields[8], "constructor")
            val memberCount = count(fields[9], "member")
            val stateCount = count(fields[10], "state")
            val constructors = List(constructorCount) {
                val constructorFields = read("K", 13)
                val physicalConstructor = DotNetGenericOwnerPhysicalMethodIdentityRecord(
                    physicalOwnerPath = constructorFields[5].decoded().split('\u0000'),
                    physicalMethodName = constructorFields[6].decoded(),
                    signature = constructorFields[7].decoded().deserializedSignature(),
                )
                DotNetGenericOwnerPhysicalConstructorRecord(
                    logicalConstructorKey = constructorFields[1].decoded(),
                    constructionMode = enumValue(
                        constructorFields[2],
                        DotNetGenericOwnerConstructionMode.entries.toTypedArray(),
                        "constructor mode",
                    ),
                    visibility = enumValue(
                        constructorFields[3],
                        DotNetGenericOwnerPhysicalConstructorVisibility.entries.toTypedArray(),
                        "constructor visibility",
                    ),
                    constructedOwnerType = constructorFields[4].decoded().deserializedType(),
                    physicalConstructor = physicalConstructor,
                    delegation = DotNetGenericOwnerPhysicalDelegatingConstructorRecord(
                        kind = enumValue(
                            constructorFields[8],
                            DotNetGenericOwnerConstructorDelegationKind.entries.toTypedArray(),
                            "constructor delegation kind",
                        ),
                        logicalConstructorKey = constructorFields[9].takeUnless { it == "-" }?.decoded(),
                        physicalOwnerType = constructorFields[10].decoded().deserializedType(),
                        physicalMethodName = constructorFields[11].decoded(),
                        signature = constructorFields[12].decoded().deserializedSignature(),
                    ),
                )
            }
            val reflectionFields = read("F", 9)
            val reflectedCallableCount = count(reflectionFields[8], "reflected callable")
            val reflection = DotNetGenericOwnerPhysicalReflectionRecord(
                logicalClassifierKey = reflectionFields[1].decoded(),
                physicalOpenTypeDefinition = DotNetGenericOwnerPhysicalOpenTypeDefinitionRecord(
                    physicalTypePath = reflectionFields[2].decoded().split('\u0000'),
                    genericArity = count(reflectionFields[3], "reflected open-TypeDef arity"),
                ),
                classifierNormalizationMode = enumValue(
                    reflectionFields[4],
                    DotNetGenericOwnerReflectionClassifierNormalizationMode.entries.toTypedArray(),
                    "classifier normalization mode",
                ),
                instanceClassificationMode = enumValue(
                    reflectionFields[5],
                    DotNetGenericOwnerRuntimeClassificationMode.entries.toTypedArray(),
                    "reflected instance classification mode",
                ),
                typeArgumentAuthority = enumValue(
                    reflectionFields[6],
                    DotNetGenericOwnerReflectionTypeArgumentAuthority.entries.toTypedArray(),
                    "reflection type-argument authority",
                ),
                capabilityExposure = enumValue(
                    reflectionFields[7],
                    DotNetGenericOwnerReflectionCapabilityExposure.entries.toTypedArray(),
                    "reflection capability exposure",
                ),
                callables = List(reflectedCallableCount) {
                    val callableFields = read("Y", 8)
                    val physicalMethodCount = count(callableFields[7], "reflected physical method")
                    val invocationMethod = DotNetGenericOwnerPhysicalMethodIdentityRecord(
                        physicalOwnerPath = callableFields[4].decoded().split('\u0000'),
                        physicalMethodName = callableFields[5].decoded(),
                        signature = callableFields[6].decoded().deserializedSignature(),
                    )
                    DotNetGenericOwnerPhysicalCallableReflectionRecord(
                        logicalMemberKey = callableFields[1].decoded(),
                        exposure = enumValue(
                            callableFields[2],
                            DotNetGenericOwnerReflectionCallableExposure.entries.toTypedArray(),
                            "reflected callable exposure",
                        ),
                        invocationRole = enumValue(
                            callableFields[3],
                            DotNetGenericOwnerMemberFamilyRole.entries.toTypedArray(),
                            "reflected callable invocation role",
                        ),
                        invocationMethod = invocationMethod,
                        physicalMethods = List(physicalMethodCount) {
                            val methodFields = read("Z", 4)
                            DotNetGenericOwnerPhysicalMethodIdentityRecord(
                                physicalOwnerPath = methodFields[1].decoded().split('\u0000'),
                                physicalMethodName = methodFields[2].decoded(),
                                signature = methodFields[3].decoded().deserializedSignature(),
                            )
                        },
                    )
                },
            )
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
                    val slotFields = read("R", 7)
                    val hasCapabilitySlot = when (slotFields[6]) {
                        "0" -> false
                        "1" -> true
                        else -> throw IllegalArgumentException(
                            "generic-owner family artifact has invalid capability-slot marker '${slotFields[6]}'"
                        )
                    }
                    val capabilitySlot = if (hasCapabilitySlot) {
                        val capabilityFields = read("C", 4)
                        DotNetGenericOwnerPhysicalMethodIdentityRecord(
                            physicalOwnerPath = capabilityFields[1].decoded().split('\u0000'),
                            physicalMethodName = capabilityFields[2].decoded(),
                            signature = capabilityFields[3].decoded().deserializedSignature(),
                        )
                    } else {
                        null
                    }
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
                        physicalOwnerPath = slotFields[3].decoded().split('\u0000'),
                        physicalMethodName = slotFields[4].decoded(),
                        signature = slotFields[5].decoded().deserializedSignature(),
                        capabilitySlot = capabilitySlot,
                    )
                }
                val directSuperTargets = List(directSuperCount) {
                    val targetFields = read("D", 6)
                    DotNetGenericOwnerPhysicalDirectSuperTargetRecord(
                        role = enumValue(
                            targetFields[1],
                            DotNetGenericOwnerMemberFamilyRole.entries.toTypedArray(),
                            "direct-super role",
                        ),
                        logicalTargetMemberKey = targetFields[2].decoded(),
                        physicalOwnerPath = targetFields[3].decoded().split('\u0000'),
                        physicalMethodName = targetFields[4].decoded(),
                        signature = targetFields[5].decoded().deserializedSignature(),
                    )
                }
                val defaultDispatcher = if (hasDefaultDispatcher) {
                    val dispatcherFields = read("A", 4)
                    DotNetGenericOwnerPhysicalDefaultDispatcherRecord(
                        physicalOwnerPath = dispatcherFields[1].decoded().split('\u0000'),
                        physicalMethodName = dispatcherFields[2].decoded(),
                        signature = dispatcherFields[3].decoded().deserializedSignature(),
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
                val stateFields = read("S", 7)
                val accessCount = count(stateFields[6], "state access")
                DotNetGenericOwnerPhysicalStateRecord(
                    logicalFieldName = stateFields[1].decoded(),
                    physicalFieldName = stateFields[2].decoded(),
                    physicalVisibility = enumValue(
                        stateFields[3],
                        DotNetGenericOwnerPhysicalStateVisibility.entries.toTypedArray(),
                        "state visibility",
                    ),
                    requirement = enumValue(
                        stateFields[4],
                        DotNetGenericOwnerStateCarrierRequirement.entries.toTypedArray(),
                        "state requirement",
                    ),
                    physicalType = stateFields[5].decoded().deserializedType(),
                    accessPaths = List(accessCount) {
                        val accessFields = read("X", 9)
                        DotNetGenericOwnerPhysicalStateAccessRecord(
                            domain = enumValue(
                                accessFields[1],
                                DotNetGenericOwnerPhysicalStateAccessDomain.entries.toTypedArray(),
                                "state access domain",
                            ),
                            operation = enumValue(
                                accessFields[2],
                                DotNetGenericOwnerPhysicalStateAccessOperation.entries.toTypedArray(),
                                "state access operation",
                            ),
                            conversion = enumValue(
                                accessFields[3],
                                DotNetGenericOwnerPhysicalStateAccessConversion.entries.toTypedArray(),
                                "state access conversion",
                            ),
                            logicalMemberKey = accessFields[4].decoded(),
                            role = enumValue(
                                accessFields[5],
                                DotNetGenericOwnerMemberFamilyRole.entries.toTypedArray(),
                                "state access member role",
                            ),
                            physicalMethod = DotNetGenericOwnerPhysicalMethodIdentityRecord(
                                physicalOwnerPath = accessFields[6].decoded().split('\u0000'),
                                physicalMethodName = accessFields[7].decoded(),
                                signature = accessFields[8].decoded().deserializedSignature(),
                            ),
                        )
                    },
                )
            }
            DotNetGenericOwnerPhysicalFamilyRecord(
                logicalOwnerKey = logicalOwnerKey,
                physicalOwnerPath = ownerPath,
                physicalCapabilityOwnerPath = capabilityOwnerPath,
                genericArity = genericArity,
                disposition = disposition,
                runtimeClassificationMode = runtimeClassificationMode,
                constructionModes = constructionModes,
                constructors = constructors,
                reflection = reflection,
                members = members,
                states = states,
            )
        }
        require(index == lines.size) { "generic-owner family artifact has trailing records" }
        return DotNetGenericOwnerPhysicalFamilyArtifact(producerFingerprint, targetProfile, owners)
    }

    private fun DotNetGenericOwnerPhysicalTypeExpressionRecord.serialized(): String =
        buildList {
            add(kind.name)
            add(parameterIndex?.toString() ?: "-")
            add(scope?.name ?: "-")
            add(assemblyName?.encoded() ?: "-")
            add(if (typePath.isEmpty()) "-" else typePath.joinToString("\u0000").encoded())
            add(genericArity.toString())
            add(namedTypeCategory?.name ?: "-")
            add(arguments.size.toString())
            arguments.forEach { argument -> add(argument.serialized().encoded()) }
        }.joinToString(";")

    private fun String.deserializedType(): DotNetGenericOwnerPhysicalTypeExpressionRecord {
        val fields = split(';')
        require(fields.size >= 8) { "generic-owner physical type expression is truncated" }
        val argumentCount = fields[7].toIntOrNull()?.takeIf { count -> count >= 0 }
            ?: throw IllegalArgumentException("generic-owner physical type expression has invalid argument count")
        require(fields.size == 8 + argumentCount) {
            "generic-owner physical type expression has an inconsistent argument count"
        }
        fun <E : Enum<E>> named(value: String, values: Array<E>, role: String): E =
            values.firstOrNull { candidate -> candidate.name == value }
                ?: throw IllegalArgumentException("generic-owner physical type expression has unknown $role '$value'")
        return DotNetGenericOwnerPhysicalTypeExpressionRecord(
            kind = named(fields[0], DotNetGenericOwnerPhysicalTypeKind.entries.toTypedArray(), "kind"),
            parameterIndex = if (fields[1] == "-") {
                null
            } else {
                fields[1].toIntOrNull()
                    ?: throw IllegalArgumentException(
                        "generic-owner physical type expression has invalid parameter index"
                    )
            },
            scope = fields[2].takeUnless { it == "-" }?.let { value ->
                named(value, DotNetGenericOwnerPhysicalTypeScope.entries.toTypedArray(), "scope")
            },
            assemblyName = fields[3].takeUnless { it == "-" }?.decoded(),
            typePath = fields[4].takeUnless { it == "-" }?.decoded()?.split('\u0000').orEmpty(),
            genericArity = fields[5].toIntOrNull()?.takeIf { arity -> arity >= 0 }
                ?: throw IllegalArgumentException("generic-owner physical type expression has invalid generic arity"),
            namedTypeCategory = fields[6].takeUnless { it == "-" }?.let { value ->
                named(
                    value,
                    DotNetGenericOwnerPhysicalNamedTypeCategory.entries.toTypedArray(),
                    "named-type category",
                )
            },
            arguments = fields.drop(8).map { argument -> argument.decoded().deserializedType() },
        )
    }

    private fun DotNetGenericOwnerPhysicalValueSlotRecord.serialized(): String =
        "${domain.name};${type.serialized().encoded()}"

    private fun String.deserializedValueSlot(): DotNetGenericOwnerPhysicalValueSlotRecord {
        val fields = split(';')
        require(fields.size == 2) { "generic-owner physical value slot has an invalid field count" }
        val domain = DotNetGenericOwnerPhysicalSlotDomain.entries.firstOrNull { candidate ->
            candidate.name == fields[0]
        } ?: throw IllegalArgumentException("generic-owner physical value slot has unknown domain '${fields[0]}'")
        return DotNetGenericOwnerPhysicalValueSlotRecord(domain, fields[1].decoded().deserializedType())
    }

    private fun DotNetGenericOwnerPhysicalMethodSignatureRecord.serialized(): String =
        buildList {
            add(if (isInstance) "1" else "0")
            add(genericArity.toString())
            add(returnSlot.serialized().encoded())
            add(parameterSlots.size.toString())
            parameterSlots.forEach { parameter -> add(parameter.serialized().encoded()) }
        }.joinToString(";")

    private fun String.deserializedSignature(): DotNetGenericOwnerPhysicalMethodSignatureRecord {
        val fields = split(';')
        require(fields.size >= 4) { "generic-owner physical signature is truncated" }
        val isInstance = when (fields[0]) {
            "0" -> false
            "1" -> true
            else -> throw IllegalArgumentException("generic-owner physical signature has invalid instance marker")
        }
        val genericArity = fields[1].toIntOrNull()?.takeIf { arity -> arity >= 0 }
            ?: throw IllegalArgumentException("generic-owner physical signature has invalid generic arity")
        val parameterCount = fields[3].toIntOrNull()?.takeIf { count -> count >= 0 }
            ?: throw IllegalArgumentException("generic-owner physical signature has invalid parameter count")
        require(fields.size == 4 + parameterCount) {
            "generic-owner physical signature has an inconsistent parameter count"
        }
        return DotNetGenericOwnerPhysicalMethodSignatureRecord(
            isInstance = isInstance,
            genericArity = genericArity,
            returnSlot = fields[2].decoded().deserializedValueSlot(),
            parameterSlots = fields.drop(4).map { parameter -> parameter.decoded().deserializedValueSlot() },
        )
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
        val producerMembers = unresolved.map { binding ->
            val logicalKey = requireNotNull(binding.overriddenLogicalBindingKey) {
                "external generic-owner override '${member.sourceName}' lacks a logical member key"
            }
            externalMembers[logicalKey]?.second
                ?: error("producer generic-owner family artifact lacks logical member '$logicalKey'")
        }
        val mergedParameterSlotDomains = producerMembers.fold(member.parameterSlotDomains) { domains, producerMember ->
            val producerDomains = producerMember.slots.single {
                slot -> slot.role == DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY
            }.signature.parameterSlots.map { parameter -> parameter.domain }
            mergeDotNetGenericOwnerParameterSlotDomains(domains, producerDomains)
        }
        val resolved = unresolved.flatMap { binding ->
            val logicalKey = requireNotNull(binding.overriddenLogicalBindingKey) {
                "external generic-owner override '${member.sourceName}' lacks a logical member key"
            }
            val producerEntry = externalMembers[logicalKey]
                ?: error("producer generic-owner family artifact lacks logical member '$logicalKey'")
            val producerMember = producerEntry.second
            fun slot(role: DotNetGenericOwnerMemberFamilyRole): DotNetGenericOwnerPhysicalMemberSlotRecord =
                producerMember.slots.single { slot -> slot.role == role }
            val typedSlot = slot(DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY)
            val semanticSlot = producerMember.slots.singleOrNull { slot ->
                slot.role == DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK
            }
            require(producerMember.slots.all { slot ->
                slot.signature.returnSlot.domain == member.returnSlotDomain
            }) {
                "consumer override '${member.sourceName}' disagrees with the producer return-slot domain"
            }
            require(typedSlot.signature.isInstance &&
                    typedSlot.signature.parameterSlots.any { parameter ->
                        parameter.type.referencesOwnerParameter()
                    } == member.typedRetainsOwnerDependentInput &&
                    typedSlot.signature.returnSlot.type.referencesOwnerParameter() ==
                    member.typedRetainsOwnerDependentOutput) {
                "consumer override '${member.sourceName}' disagrees with the producer typed slot-domain signature"
            }
            if (semanticSlot != null) {
                require(semanticSlot.signature.isInstance &&
                        semanticSlot.signature.parameterSlots.none { parameter ->
                            parameter.type.referencesOwnerParameter()
                        } == member.semanticErasesOwnerDependentInput &&
                        !semanticSlot.signature.returnSlot.type.referencesOwnerParameter() ==
                        member.semanticErasesOwnerDependentOutput) {
                    "consumer override '${member.sourceName}' disagrees with the producer semantic slot-domain signature"
                }
            }
            producerMember.slots.singleOrNull { slot ->
                slot.role == DotNetGenericOwnerMemberFamilyRole.CAPABILITY_DISPATCHER
            }?.let { dispatcher ->
                require(dispatcher.capabilitySlot != null &&
                        dispatcher.capabilitySlot.signature == dispatcher.signature) {
                    "consumer override '${member.sourceName}' lacks an exact producer capability-slot identity"
                }
            }
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
                    overriddenPhysicalOwnerPath = slot.physicalOwnerPath,
                    overriddenPhysicalSignature = slot.signature,
                    overriddenCapabilitySlot = slot.capabilitySlot,
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
            parameterSlotDomains = mergedParameterSlotDomains,
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
    val constructors: List<DotNetGenericOwnerConstructorPlan>,
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
        constructors = constructors.mapIndexed { sourceIndex, constructor ->
            DotNetGenericOwnerPrototypeConstructorSnapshot(
                sourceIndex = sourceIndex,
                logicalBindingKey = constructor.logicalBindingKey,
                parameterSlotDomains = constructor.parameterSlotDomains,
                delegatedConstructorLogicalBindingKey = constructor.delegatedConstructorLogicalBindingKey,
                delegatedOwnerName = constructor.delegatedOwnerName,
                delegatesToThis = constructor.delegatesToThis,
            )
        },
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
                returnSlotDomain = family.returnSlotDomain,
                parameterSlotDomains = family.parameterSlotDomains,
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
                        overriddenPhysicalSignature = null,
                        overriddenCapabilitySlot = null,
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
