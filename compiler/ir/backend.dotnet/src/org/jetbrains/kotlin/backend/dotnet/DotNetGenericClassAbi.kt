/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.declarations.IrAnonymousInitializer
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrContainerExpression
import org.jetbrains.kotlin.ir.expressions.IrDelegatingConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrInstanceInitializerCall
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.isAny
import org.jetbrains.kotlin.ir.types.isBoolean
import org.jetbrains.kotlin.ir.types.isInt
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.types.isNullableAny
import org.jetbrains.kotlin.ir.types.isNullableString
import org.jetbrains.kotlin.ir.types.isString
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.util.isInterface
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.resolveFakeOverride
import org.jetbrains.kotlin.ir.util.resolveFakeOverrideMaybeAbstract
import org.jetbrains.kotlin.types.Variance
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

enum class DotNetGenericOwnerPhysicalizationProofKind {
    COMPILER_DERIVED_EXTERNAL_SUBCLASS,
}

enum class DotNetGenericOwnerPhysicalMemberDispatch {
    FINAL,
    OVERRIDABLE,
    ABSTRACT,
}

enum class DotNetGenericOwnerPhysicalTypeVisibility {
    PUBLIC,
    NOT_PUBLIC,
}

enum class DotNetGenericOwnerPhysicalTypeDispatch {
    FINAL,
    OVERRIDABLE,
    ABSTRACT,
    SEALED,
}

enum class DotNetGenericOwnerPhysicalMemberVisibility {
    PUBLIC,
    FAMILY,
    ASSEMBLY,
    FAMILY_OR_ASSEMBLY,
    PRIVATE,
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
    CURRENT_COMPILATION,
    CORE_LIBRARY,
    ASSEMBLY,
}

enum class DotNetGenericOwnerPhysicalNamedTypeCategory {
    CLASS,
    VALUE_TYPE,
}

enum class DotNetGenericOwnerPhysicalGenericParameterSpecialConstraint {
    REFERENCE_TYPE,
    NON_NULLABLE_VALUE_TYPE,
    DEFAULT_CONSTRUCTOR,
}

/** Exact CLR GenericParam constraint row for one physical owner parameter. */
data class DotNetGenericOwnerPhysicalGenericParameterRecord(
    val index: Int,
    val specialConstraints: Set<DotNetGenericOwnerPhysicalGenericParameterSpecialConstraint>,
    val typeConstraints: List<DotNetGenericOwnerPhysicalTypeExpressionRecord>,
) {
    init {
        require(index >= 0 && typeConstraints.none { constraint ->
            constraint.kind == DotNetGenericOwnerPhysicalTypeKind.VOID
        }) {
            "a generic-owner parameter requires a non-negative index and non-void constraints"
        }
        require(
            DotNetGenericOwnerPhysicalGenericParameterSpecialConstraint.REFERENCE_TYPE !in specialConstraints ||
                    DotNetGenericOwnerPhysicalGenericParameterSpecialConstraint.NON_NULLABLE_VALUE_TYPE !in specialConstraints
        ) {
            "a generic-owner parameter cannot be both a reference type and a non-nullable value type"
        }
        require(typeConstraints.size == typeConstraints.toSet().size) {
            "a generic-owner parameter has duplicate type constraints"
        }
        require(typeConstraints.flatMap { constraint -> constraint.typeParameterReferences() }
            .none { reference -> reference.first == DotNetGenericOwnerPhysicalTypeKind.METHOD_TYPE_PARAMETER }) {
            "a TypeDef generic parameter constraint cannot reference a method parameter"
        }
    }
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
        fun currentCompilationType(
            typePath: List<String>,
            category: DotNetGenericOwnerPhysicalNamedTypeCategory,
            arguments: List<DotNetGenericOwnerPhysicalTypeExpressionRecord> = emptyList(),
        ) = DotNetGenericOwnerPhysicalTypeExpressionRecord(
            kind = DotNetGenericOwnerPhysicalTypeKind.NAMED,
            scope = DotNetGenericOwnerPhysicalTypeScope.CURRENT_COMPILATION,
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

enum class DotNetGenericOwnerConstructionPlanProofKind {
    FINITE_OPEN_NULLABLE_WITH_SEMANTIC_FALLBACK,
}

enum class DotNetGenericOwnerConstructionDispatchKind {
    FINITE_RUNTIME_TYPE_TOKEN_TABLE,
}

enum class DotNetGenericOwnerConstructionResultCarrierKind {
    SEMANTIC_CAPABILITY,
}

enum class DotNetGenericOwnerConstructionRouteKind {
    RUNTIME_EXACT,
    SEMANTIC_FALLBACK,
}

enum class DotNetGenericOwnerConstructorDelegationKind {
    THIS,
    BASE,
}

enum class DotNetGenericOwnerConstructorArgumentMapping {
    POSITIONAL_IDENTITY,
    UNSUPPORTED,
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

private fun DotNetGenericOwnerPhysicalTypeExpressionRecord.referencesScope(
    expectedScope: DotNetGenericOwnerPhysicalTypeScope,
): Boolean = scope == expectedScope || arguments.any { argument -> argument.referencesScope(expectedScope) }

private fun DotNetGenericOwnerPhysicalTypeExpressionRecord.containsTypeParameter(): Boolean =
    kind == DotNetGenericOwnerPhysicalTypeKind.OWNER_TYPE_PARAMETER ||
            kind == DotNetGenericOwnerPhysicalTypeKind.METHOD_TYPE_PARAMETER ||
            arguments.any { argument -> argument.containsTypeParameter() }

private fun DotNetGenericOwnerPhysicalTypeExpressionRecord.isCoreNullableTypePath(): Boolean =
    kind == DotNetGenericOwnerPhysicalTypeKind.NAMED &&
            scope == DotNetGenericOwnerPhysicalTypeScope.CORE_LIBRARY &&
            typePath == listOf("System", "Nullable")

private fun DotNetGenericOwnerPhysicalTypeExpressionRecord.isCoreNullableValueType(): Boolean =
    isCoreNullableTypePath() && genericArity == 1 &&
            namedTypeCategory == DotNetGenericOwnerPhysicalNamedTypeCategory.VALUE_TYPE

private fun DotNetGenericOwnerPhysicalTypeExpressionRecord.isConcreteNonNullableValueType(): Boolean = when (kind) {
    DotNetGenericOwnerPhysicalTypeKind.BOOLEAN,
    DotNetGenericOwnerPhysicalTypeKind.INT32,
    -> true
    DotNetGenericOwnerPhysicalTypeKind.NAMED ->
        namedTypeCategory == DotNetGenericOwnerPhysicalNamedTypeCategory.VALUE_TYPE && !isCoreNullableValueType() &&
                !containsTypeParameter()
    DotNetGenericOwnerPhysicalTypeKind.VOID,
    DotNetGenericOwnerPhysicalTypeKind.STRING,
    DotNetGenericOwnerPhysicalTypeKind.OBJECT,
    DotNetGenericOwnerPhysicalTypeKind.OWNER_TYPE_PARAMETER,
    DotNetGenericOwnerPhysicalTypeKind.METHOD_TYPE_PARAMETER,
    DotNetGenericOwnerPhysicalTypeKind.SZ_ARRAY,
    -> false
}

private fun DotNetGenericOwnerPhysicalTypeExpressionRecord.openNullableRuntimeArgumentOrNull():
        DotNetGenericOwnerPhysicalTypeExpressionRecord? {
    if (containsTypeParameter() || kind == DotNetGenericOwnerPhysicalTypeKind.VOID) return null
    if (isCoreNullableTypePath() && !isCoreNullableValueType()) return null
    return when (kind) {
        DotNetGenericOwnerPhysicalTypeKind.BOOLEAN,
        DotNetGenericOwnerPhysicalTypeKind.INT32,
        -> DotNetGenericOwnerPhysicalTypeExpressionRecord.coreType(
            typePath = listOf("System", "Nullable"),
            category = DotNetGenericOwnerPhysicalNamedTypeCategory.VALUE_TYPE,
            arguments = listOf(this),
        )
        DotNetGenericOwnerPhysicalTypeKind.STRING,
        DotNetGenericOwnerPhysicalTypeKind.OBJECT,
        DotNetGenericOwnerPhysicalTypeKind.SZ_ARRAY,
        -> this
        DotNetGenericOwnerPhysicalTypeKind.NAMED -> when (namedTypeCategory) {
            DotNetGenericOwnerPhysicalNamedTypeCategory.VALUE_TYPE -> if (isCoreNullableValueType()) {
                takeIf { arguments.single().isConcreteNonNullableValueType() }
            } else {
                DotNetGenericOwnerPhysicalTypeExpressionRecord.coreType(
                    typePath = listOf("System", "Nullable"),
                    category = DotNetGenericOwnerPhysicalNamedTypeCategory.VALUE_TYPE,
                    arguments = listOf(this),
                )
            }
            DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS -> this
            null -> null
        }
        DotNetGenericOwnerPhysicalTypeKind.OWNER_TYPE_PARAMETER,
        DotNetGenericOwnerPhysicalTypeKind.METHOD_TYPE_PARAMETER,
        DotNetGenericOwnerPhysicalTypeKind.VOID,
        -> null
    }
}

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
    val maskedDefaultDispatcher: IrSimpleFunction?,
    val logicalBindingKey: String?,
)

internal data class DotNetGenericOwnerConstructorPlan(
    val source: IrConstructor,
    val logicalBindingKey: String?,
    val parameterSlotDomains: List<DotNetGenericOwnerPhysicalSlotDomain>,
    val delegationArgumentMapping: DotNetGenericOwnerConstructorArgumentMapping,
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
    val physicalBaseName: String,
    val sourceIndex: Int,
    val isFakeOverride: Boolean,
    val isAbstract: Boolean,
    val isOverridable: Boolean,
    val physicalVisibility: DotNetGenericOwnerPhysicalMemberVisibility,
    val policy: DotNetGenericOwnerMemberPolicy,
    val roles: Set<DotNetGenericOwnerMemberFamilyRole>,
    val semanticHookReasons: Set<DotNetGenericOwnerSemanticHookReason>,
    val typedRetainsOwnerDependentInput: Boolean,
    val semanticErasesOwnerDependentInput: Boolean,
    val typedRetainsOwnerDependentOutput: Boolean,
    val semanticErasesOwnerDependentOutput: Boolean,
    val returnSlotDomain: DotNetGenericOwnerPhysicalSlotDomain,
    val parameterSlotDomains: List<DotNetGenericOwnerPhysicalSlotDomain>,
    val exactPathUnboundSignatures:
            Map<DotNetGenericOwnerMemberFamilyRole, DotNetGenericOwnerPrototypeMethodSignatureSnapshot>?,
    val requiresDirectSuperTargets: Boolean,
    val directSuperCallCount: Int,
    val directSuperCalls: List<DotNetGenericOwnerDirectSuperCallSnapshot>,
    val hasMaskedDefaultDispatcher: Boolean,
    val exactMaskedDefaultDispatcher: DotNetGenericOwnerPrototypeDefaultDispatcherSnapshot?,
    val logicalBindingKey: String?,
    val overrideBindings: List<DotNetGenericOwnerPrototypeOverrideBindingSnapshot>,
    val directProducerCallNames: List<String>,
    val transitiveProducerCallNames: List<String>,
    val directStateReadNames: List<String>,
    val directStateWriteNames: List<String>,
    val transitiveStateReadNames: List<String>,
    val transitiveStateWriteNames: List<String>,
    val reachableFromSemanticEntry: Boolean,
) {
    init {
        exactPathUnboundSignatures?.let { signatures ->
            require(signatures.keys == roles) {
                "an exact generic-owner prototype signature family must cover every selected role"
            }
            require(signatures.values.all { signature ->
                signature.returnSlot.domain == returnSlotDomain &&
                        signature.parameterSlots.map { slot -> slot.domain } == parameterSlotDomains
            }) {
                "an exact generic-owner prototype signature family disagrees with its slot-domain vector"
            }
            require(signatures[DotNetGenericOwnerMemberFamilyRole.CAPABILITY_DISPATCHER]
                ?.allTypes()
                ?.none { type -> type.referencesOwnerParameter() } != false
            ) {
                "an exact generic-owner capability signature cannot reference an owner parameter"
            }
        }
        require(exactMaskedDefaultDispatcher == null || hasMaskedDefaultDispatcher) {
            "an exact generic-owner masked dispatcher requires a lowered source dispatcher"
        }
    }
}

/** Compiler-derived static default helper shape before the producer TypeDef path is selected. */
data class DotNetGenericOwnerPrototypeDefaultDispatcherSnapshot(
    val genericArity: Int,
    val returnSlot: DotNetGenericOwnerPrototypeValueSlotSnapshot,
    val parameterSlotsAfterReceiver: List<DotNetGenericOwnerPrototypeValueSlotSnapshot>,
) {
    init {
        require(genericArity >= 0) { "a generic-owner default helper requires non-negative generic arity" }
        require(parameterSlotsAfterReceiver.none { slot ->
            slot.domain == DotNetGenericOwnerPhysicalSlotDomain.OWNER_EXACT_RECEIVER
        }) { "a generic-owner default helper tail cannot contain another owner receiver" }
    }

    fun physicalSignature(
        physicalOwnerPath: List<String>,
        ownerGenericArity: Int,
        physicalOwnerPathsByLogicalKey: Map<String, List<String>>,
    ): DotNetGenericOwnerPhysicalMethodSignatureRecord {
        require(physicalOwnerPath.isNotEmpty() && ownerGenericArity > 0) {
            "a generic-owner default helper requires an exact generic producer owner"
        }
        val receiverType = DotNetGenericOwnerPhysicalTypeExpressionRecord.producerType(
            typePath = physicalOwnerPath,
            category = DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS,
            arguments = List(ownerGenericArity) { index ->
                DotNetGenericOwnerPhysicalTypeExpressionRecord.ownerParameter(index)
            },
        )
        return DotNetGenericOwnerPhysicalMethodSignatureRecord(
            isInstance = false,
            genericArity = genericArity,
            returnSlot = returnSlot.bindProducerTypes(physicalOwnerPathsByLogicalKey),
            parameterSlots = listOf(DotNetGenericOwnerPhysicalValueSlotRecord(
                domain = DotNetGenericOwnerPhysicalSlotDomain.OWNER_EXACT_RECEIVER,
                type = receiverType,
            )) + parameterSlotsAfterReceiver.map { slot ->
                slot.bindProducerTypes(physicalOwnerPathsByLogicalKey)
            },
        )
    }
}

/** Logical construction join retained before any future physical constructor is selected. */
data class DotNetGenericOwnerPrototypeConstructorSnapshot(
    val sourceIndex: Int,
    val logicalBindingKey: String?,
    val physicalVisibility: DotNetGenericOwnerPhysicalConstructorVisibility,
    val parameterSlotDomains: List<DotNetGenericOwnerPhysicalSlotDomain>,
    val exactPathUnboundSignature: DotNetGenericOwnerPrototypeMethodSignatureSnapshot?,
    val delegationArgumentMapping: DotNetGenericOwnerConstructorArgumentMapping,
    val hasOnlyDelegationAndInstanceInitializer: Boolean,
    val delegatedConstructorLogicalBindingKey: String?,
    val delegatedOwnerName: String?,
    val delegatesToThis: Boolean,
)

/**
 * Path-unbound CLR carrier grammar for producer-owned state and callables.
 *
 * A referenced Kotlin generic owner is retained by its logical declaration key until the
 * artifact builder selects one physical TypeDef path for the complete producer family. This
 * prevents an IR/source display name from silently becoming physical ABI.
 */
enum class DotNetGenericOwnerPrototypeTypeKind {
    VOID,
    BOOLEAN,
    INT32,
    STRING,
    OBJECT,
    SYSTEM_ARRAY,
    OWNER_TYPE_PARAMETER,
    METHOD_TYPE_PARAMETER,
    LOGICAL_GENERIC_CLASSIFIER,
    SZ_ARRAY,
}

data class DotNetGenericOwnerPrototypeTypeSnapshot(
    val kind: DotNetGenericOwnerPrototypeTypeKind,
    val parameterIndex: Int? = null,
    val logicalClassifierKey: String? = null,
    val arguments: List<DotNetGenericOwnerPrototypeTypeSnapshot> = emptyList(),
) {
    init {
        when (kind) {
            DotNetGenericOwnerPrototypeTypeKind.VOID,
            DotNetGenericOwnerPrototypeTypeKind.BOOLEAN,
            DotNetGenericOwnerPrototypeTypeKind.INT32,
            DotNetGenericOwnerPrototypeTypeKind.STRING,
            DotNetGenericOwnerPrototypeTypeKind.OBJECT,
            DotNetGenericOwnerPrototypeTypeKind.SYSTEM_ARRAY,
            -> require(parameterIndex == null && logicalClassifierKey == null && arguments.isEmpty()) {
                "a leaf generic-owner prototype carrier cannot contain a parameter, classifier, or arguments"
            }
            DotNetGenericOwnerPrototypeTypeKind.OWNER_TYPE_PARAMETER,
            DotNetGenericOwnerPrototypeTypeKind.METHOD_TYPE_PARAMETER,
            -> require(
                parameterIndex != null && parameterIndex >= 0 && logicalClassifierKey == null && arguments.isEmpty()
            ) { "a generic-parameter prototype carrier requires only a non-negative parameter index" }
            DotNetGenericOwnerPrototypeTypeKind.LOGICAL_GENERIC_CLASSIFIER -> require(
                parameterIndex == null && !logicalClassifierKey.isNullOrEmpty() && arguments.isNotEmpty() &&
                        arguments.none { argument -> argument.kind == DotNetGenericOwnerPrototypeTypeKind.VOID }
            ) { "a logical generic prototype classifier requires only a declaration key and type arguments" }
            DotNetGenericOwnerPrototypeTypeKind.SZ_ARRAY -> require(
                parameterIndex == null && logicalClassifierKey == null && arguments.size == 1 &&
                        arguments.single().kind != DotNetGenericOwnerPrototypeTypeKind.VOID
            ) { "a generic-owner prototype SZ array requires exactly one non-void element type" }
        }
    }

    fun referencesOwnerParameter(): Boolean =
        kind == DotNetGenericOwnerPrototypeTypeKind.OWNER_TYPE_PARAMETER ||
                arguments.any(DotNetGenericOwnerPrototypeTypeSnapshot::referencesOwnerParameter)

    internal fun methodParameterIndices(): List<Int> = buildList {
        if (kind == DotNetGenericOwnerPrototypeTypeKind.METHOD_TYPE_PARAMETER) {
            add(checkNotNull(parameterIndex))
        }
        arguments.forEach { argument -> addAll(argument.methodParameterIndices()) }
    }

    /** Binds every logical producer classifier only after the artifact owns its physical path. */
    fun bindProducerTypes(
        physicalOwnerPathsByLogicalKey: Map<String, List<String>>,
    ): DotNetGenericOwnerPhysicalTypeExpressionRecord = when (kind) {
        DotNetGenericOwnerPrototypeTypeKind.VOID ->
            DotNetGenericOwnerPhysicalTypeExpressionRecord.voidType()
        DotNetGenericOwnerPrototypeTypeKind.BOOLEAN ->
            DotNetGenericOwnerPhysicalTypeExpressionRecord.booleanType()
        DotNetGenericOwnerPrototypeTypeKind.INT32 ->
            DotNetGenericOwnerPhysicalTypeExpressionRecord.int32Type()
        DotNetGenericOwnerPrototypeTypeKind.STRING ->
            DotNetGenericOwnerPhysicalTypeExpressionRecord.stringType()
        DotNetGenericOwnerPrototypeTypeKind.OBJECT ->
            DotNetGenericOwnerPhysicalTypeExpressionRecord.objectType()
        DotNetGenericOwnerPrototypeTypeKind.SYSTEM_ARRAY ->
            DotNetGenericOwnerPhysicalTypeExpressionRecord.coreType(
                typePath = listOf("System", "Array"),
                category = DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS,
            )
        DotNetGenericOwnerPrototypeTypeKind.OWNER_TYPE_PARAMETER ->
            DotNetGenericOwnerPhysicalTypeExpressionRecord.ownerParameter(checkNotNull(parameterIndex))
        DotNetGenericOwnerPrototypeTypeKind.METHOD_TYPE_PARAMETER ->
            DotNetGenericOwnerPhysicalTypeExpressionRecord.methodParameter(checkNotNull(parameterIndex))
        DotNetGenericOwnerPrototypeTypeKind.LOGICAL_GENERIC_CLASSIFIER -> {
            val logicalKey = checkNotNull(logicalClassifierKey)
            val physicalPath = requireNotNull(physicalOwnerPathsByLogicalKey[logicalKey]) {
                "generic-owner prototype classifier '$logicalKey' has no selected producer TypeDef path"
            }
            DotNetGenericOwnerPhysicalTypeExpressionRecord.producerType(
                typePath = physicalPath,
                category = DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS,
                arguments = arguments.map { argument ->
                    argument.bindProducerTypes(physicalOwnerPathsByLogicalKey)
                },
            )
        }
        DotNetGenericOwnerPrototypeTypeKind.SZ_ARRAY ->
            DotNetGenericOwnerPhysicalTypeExpressionRecord.szArray(
                arguments.single().bindProducerTypes(physicalOwnerPathsByLogicalKey),
            )
    }

    companion object {
        fun voidType() = leaf(DotNetGenericOwnerPrototypeTypeKind.VOID)
        fun booleanType() = leaf(DotNetGenericOwnerPrototypeTypeKind.BOOLEAN)
        fun int32Type() = leaf(DotNetGenericOwnerPrototypeTypeKind.INT32)
        fun stringType() = leaf(DotNetGenericOwnerPrototypeTypeKind.STRING)
        fun objectType() = leaf(DotNetGenericOwnerPrototypeTypeKind.OBJECT)
        fun systemArrayType() = leaf(DotNetGenericOwnerPrototypeTypeKind.SYSTEM_ARRAY)
        fun ownerParameter(index: Int) = DotNetGenericOwnerPrototypeTypeSnapshot(
            kind = DotNetGenericOwnerPrototypeTypeKind.OWNER_TYPE_PARAMETER,
            parameterIndex = index,
        )
        fun methodParameter(index: Int) = DotNetGenericOwnerPrototypeTypeSnapshot(
            kind = DotNetGenericOwnerPrototypeTypeKind.METHOD_TYPE_PARAMETER,
            parameterIndex = index,
        )
        fun logicalGenericClassifier(
            logicalClassifierKey: String,
            arguments: List<DotNetGenericOwnerPrototypeTypeSnapshot>,
        ) = DotNetGenericOwnerPrototypeTypeSnapshot(
            kind = DotNetGenericOwnerPrototypeTypeKind.LOGICAL_GENERIC_CLASSIFIER,
            logicalClassifierKey = logicalClassifierKey,
            arguments = arguments,
        )
        fun szArray(elementType: DotNetGenericOwnerPrototypeTypeSnapshot) =
            DotNetGenericOwnerPrototypeTypeSnapshot(
                kind = DotNetGenericOwnerPrototypeTypeKind.SZ_ARRAY,
                arguments = listOf(elementType),
            )

        private fun leaf(kind: DotNetGenericOwnerPrototypeTypeKind) =
            DotNetGenericOwnerPrototypeTypeSnapshot(kind)
    }
}

data class DotNetGenericOwnerPrototypeValueSlotSnapshot(
    val domain: DotNetGenericOwnerPhysicalSlotDomain,
    val type: DotNetGenericOwnerPrototypeTypeSnapshot,
) {
    fun bindProducerTypes(
        physicalOwnerPathsByLogicalKey: Map<String, List<String>>,
    ): DotNetGenericOwnerPhysicalValueSlotRecord = DotNetGenericOwnerPhysicalValueSlotRecord(
        domain = domain,
        type = type.bindProducerTypes(physicalOwnerPathsByLogicalKey),
    )
}

/** Complete path-unbound MethodDef signature; binding is atomic after TypeDef selection. */
data class DotNetGenericOwnerPrototypeMethodSignatureSnapshot(
    val isInstance: Boolean,
    val genericArity: Int,
    val returnSlot: DotNetGenericOwnerPrototypeValueSlotSnapshot,
    val parameterSlots: List<DotNetGenericOwnerPrototypeValueSlotSnapshot>,
) {
    init {
        require(genericArity >= 0) { "a generic-owner prototype method requires non-negative generic arity" }
        require(returnSlot.domain !in setOf(
            DotNetGenericOwnerPhysicalSlotDomain.OWNER_EXACT_RECEIVER,
            DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_INPUT,
            DotNetGenericOwnerPhysicalSlotDomain.BROAD_CANDIDATE_INPUT,
        )) { "a generic-owner prototype return has an input-only slot domain" }
        require(returnSlot.type.kind != DotNetGenericOwnerPrototypeTypeKind.VOID ||
                returnSlot.domain == DotNetGenericOwnerPhysicalSlotDomain.DECLARATION_INDEPENDENT) {
            "a void generic-owner prototype return must be declaration-independent"
        }
        require(parameterSlots.none { slot ->
            slot.domain == DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_OUTPUT ||
                    slot.type.kind == DotNetGenericOwnerPrototypeTypeKind.VOID
        }) { "a generic-owner prototype parameter has an invalid domain or void type" }
        require(!isInstance || parameterSlots.none { slot ->
            slot.domain == DotNetGenericOwnerPhysicalSlotDomain.OWNER_EXACT_RECEIVER
        }) { "an instance generic-owner prototype method cannot expose a separate exact receiver" }
        require(returnSlot.type.kind == DotNetGenericOwnerPrototypeTypeKind.VOID ||
                returnSlot.domain != DotNetGenericOwnerPhysicalSlotDomain.DECLARATION_INDEPENDENT ||
                !returnSlot.type.referencesOwnerParameter()) {
            "an owner-dependent prototype return requires an owner slot domain"
        }
        require(parameterSlots.none { slot ->
            slot.domain == DotNetGenericOwnerPhysicalSlotDomain.DECLARATION_INDEPENDENT &&
                    slot.type.referencesOwnerParameter()
        }) { "an owner-dependent prototype parameter requires an owner slot domain" }
        require(allTypes().flatMap { type -> type.methodParameterIndices() }.all { index -> index < genericArity }) {
            "a generic-owner prototype signature references a missing method type parameter"
        }
    }

    fun allTypes(): List<DotNetGenericOwnerPrototypeTypeSnapshot> =
        listOf(returnSlot.type) + parameterSlots.map { slot -> slot.type }

    fun bindProducerTypes(
        physicalOwnerPathsByLogicalKey: Map<String, List<String>>,
    ): DotNetGenericOwnerPhysicalMethodSignatureRecord = DotNetGenericOwnerPhysicalMethodSignatureRecord(
        isInstance = isInstance,
        genericArity = genericArity,
        returnSlot = returnSlot.bindProducerTypes(physicalOwnerPathsByLogicalKey),
        parameterSlots = parameterSlots.map { slot -> slot.bindProducerTypes(physicalOwnerPathsByLogicalKey) },
    )
}

data class DotNetGenericOwnerPrototypeStateSnapshot(
    val fieldName: String,
    val requirement: DotNetGenericOwnerStateCarrierRequirement,
    /** Null means the field type is outside the bounded path-unbound carrier grammar. */
    val exactTypedCarrierType: DotNetGenericOwnerPrototypeTypeSnapshot?,
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

/** Compiler-proven physical provenance of one Kotlin generic-owner call receiver. */
enum class DotNetGenericOwnerCallReceiverProvenance {
    EXACT_CONSTRUCTION,
    SEMANTIC_VIEW,
    UNRESOLVED,
}

/** Production-inert route requirement; this is evidence and never an emitter selection. */
enum class DotNetGenericOwnerCallRouteRequirement {
    EXACT_TYPED_ENTRY,
    SEMANTIC_CAPABILITY,
    MISSING_CAPABILITY,
    PRODUCER_ERASED_OWNER,
    EXTERNAL_FAMILY_RECORD_REQUIRED,
}

/** Immutable, IR-free static call-site evidence for representative route censuses. */
data class DotNetGenericOwnerCallRouteSnapshot(
    val callerName: String,
    val callerLogicalBindingKey: String?,
    val callSiteIndex: Int,
    val calleeOwnerName: String,
    val calleeName: String,
    val calleeLogicalBindingKey: String?,
    val receiverProvenance: DotNetGenericOwnerCallReceiverProvenance,
    val routeRequirement: DotNetGenericOwnerCallRouteRequirement,
) {
    init {
        require(callSiteIndex >= 0) { "a generic-owner call-site index cannot be negative" }
        require(callerName.isNotEmpty() && calleeOwnerName.isNotEmpty() && calleeName.isNotEmpty()) {
            "a generic-owner call route requires non-empty caller and callee identities"
        }
        require(
            routeRequirement != DotNetGenericOwnerCallRouteRequirement.EXACT_TYPED_ENTRY ||
                    receiverProvenance == DotNetGenericOwnerCallReceiverProvenance.EXACT_CONSTRUCTION
        ) { "an exact generic-owner call requires exact receiver-construction provenance" }
        require(
            routeRequirement != DotNetGenericOwnerCallRouteRequirement.SEMANTIC_CAPABILITY ||
                    receiverProvenance != DotNetGenericOwnerCallReceiverProvenance.EXACT_CONSTRUCTION
        ) { "a semantic generic-owner capability cannot replace a proven exact typed entry" }
    }
}

/** Stable, diagnostic-name-free identity and outcome for one producer-owned application call site. */
data class DotNetGenericOwnerCallRouteManifestRecord(
    val compilationCallSiteIndex: Int,
    val callerLogicalBindingKey: String?,
    val calleeLogicalBindingKey: String,
    val receiverProvenance: DotNetGenericOwnerCallReceiverProvenance,
    val routeRequirement: DotNetGenericOwnerCallRouteRequirement,
) {
    init {
        require(compilationCallSiteIndex >= 0 && callerLogicalBindingKey?.isNotEmpty() != false &&
                calleeLogicalBindingKey.isNotEmpty()) {
            "a generic-owner application route requires valid compiler and logical identities"
        }
        require(routeRequirement != DotNetGenericOwnerCallRouteRequirement.EXTERNAL_FAMILY_RECORD_REQUIRED) {
            "an unresolved external generic-owner route cannot enter an application manifest"
        }
        require(
            routeRequirement != DotNetGenericOwnerCallRouteRequirement.EXACT_TYPED_ENTRY ||
                    receiverProvenance == DotNetGenericOwnerCallReceiverProvenance.EXACT_CONSTRUCTION
        ) { "an exact generic-owner application route requires exact construction provenance" }
        require(
            routeRequirement != DotNetGenericOwnerCallRouteRequirement.SEMANTIC_CAPABILITY ||
                    receiverProvenance != DotNetGenericOwnerCallReceiverProvenance.EXACT_CONSTRUCTION
        ) { "a semantic application route cannot replace a proven exact typed entry" }
    }
}

/**
 * Profile-neutral application census derived from one complete compiler call-route graph.
 *
 * [compilationCallSiteIndex][DotNetGenericOwnerCallRouteManifestRecord.compilationCallSiteIndex]
 * deliberately retains its possibly sparse index in the unfiltered compilation census. This is
 * the join key for later execution weights. Logical keys identify declarations; source names and
 * physical CLR names are excluded because neither is binding authority.
 */
data class DotNetGenericOwnerCallRouteManifest(
    val routes: List<DotNetGenericOwnerCallRouteManifestRecord>,
) {
    init {
        require(routes.isNotEmpty()) { "a generic-owner application route manifest cannot be empty" }
        require(routes == routes.sortedBy { route -> route.compilationCallSiteIndex } &&
                routes.map { route -> route.compilationCallSiteIndex }.toSet().size == routes.size) {
            "generic-owner application routes require unique compiler-ordered call-site indices"
        }
    }

    companion object {
        fun fromResolvedCallRoutes(
            routes: List<DotNetGenericOwnerCallRouteSnapshot>,
        ): DotNetGenericOwnerCallRouteManifest = DotNetGenericOwnerCallRouteManifest(
            routes.sortedBy { route -> route.callSiteIndex }.map { route ->
                DotNetGenericOwnerCallRouteManifestRecord(
                    compilationCallSiteIndex = route.callSiteIndex,
                    callerLogicalBindingKey = route.callerLogicalBindingKey,
                    calleeLogicalBindingKey = requireNotNull(route.calleeLogicalBindingKey) {
                        "a resolved generic-owner application route lacks its logical member binding"
                    },
                    receiverProvenance = route.receiverProvenance,
                    routeRequirement = route.routeRequirement,
                )
            },
        )
    }
}

/** Deterministic, profile-neutral codec for compiler-derived application call-route censuses. */
object DotNetGenericOwnerCallRouteManifestCodec {
    const val SCHEMA_VERSION = 1
    private const val MAGIC = "kotlin-dotnet-generic-owner-call-routes"
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(manifest: DotNetGenericOwnerCallRouteManifest): String = buildString {
        appendLine("$MAGIC\t$SCHEMA_VERSION")
        appendLine("N\t${manifest.routes.size}")
        manifest.routes.forEach { route ->
            appendLine(
                listOf(
                    "R",
                    route.compilationCallSiteIndex.toString(),
                    if (route.callerLogicalBindingKey == null) "0" else "1",
                    route.callerLogicalBindingKey?.encodedRouteText() ?: "-",
                    route.calleeLogicalBindingKey.encodedRouteText(),
                    route.receiverProvenance.name,
                    route.routeRequirement.name,
                ).joinToString("\t")
            )
        }
    }

    fun decode(text: String): DotNetGenericOwnerCallRouteManifest {
        val lines = text.removeSuffix("\n").split('\n')
        var index = 0

        fun read(kind: String, fieldCount: Int): List<String> {
            require(index < lines.size) { "generic-owner application route manifest is truncated before '$kind'" }
            val fields = lines[index++].split('\t')
            require(fields.size == fieldCount && fields.firstOrNull() == kind) {
                "generic-owner application route manifest expected '$kind' with $fieldCount fields"
            }
            return fields
        }

        fun <E : Enum<E>> enumValue(value: String, values: Array<E>, role: String): E =
            values.singleOrNull { candidate -> candidate.name == value }
                ?: throw IllegalArgumentException(
                    "generic-owner application route manifest has unknown $role '$value'"
                )

        val header = read(MAGIC, 2)
        require(header[1] == SCHEMA_VERSION.toString()) {
            "stale generic-owner application route schema '${header[1]}'; expected '$SCHEMA_VERSION'"
        }
        val routeCount = read("N", 2)[1].toIntOrNull()?.takeIf { count -> count >= 0 }
            ?: throw IllegalArgumentException("generic-owner application route manifest has an invalid route count")
        val manifest = DotNetGenericOwnerCallRouteManifest(
            List(routeCount) {
                val fields = read("R", 7)
                val callerLogicalBindingKey = when (fields[2]) {
                    "0" -> {
                        require(fields[3] == "-") {
                            "a generic-owner application route has text for an absent caller binding"
                        }
                        null
                    }
                    "1" -> fields[3].decodedRouteText()
                    else -> throw IllegalArgumentException(
                        "generic-owner application route manifest has invalid caller-binding presence '${fields[2]}'"
                    )
                }
                DotNetGenericOwnerCallRouteManifestRecord(
                    compilationCallSiteIndex = fields[1].toIntOrNull()?.takeIf { callSiteIndex ->
                        callSiteIndex >= 0
                    } ?: throw IllegalArgumentException(
                        "generic-owner application route manifest has an invalid call-site index"
                    ),
                    callerLogicalBindingKey = callerLogicalBindingKey,
                    calleeLogicalBindingKey = fields[4].decodedRouteText(),
                    receiverProvenance = enumValue(
                        fields[5],
                        DotNetGenericOwnerCallReceiverProvenance.entries.toTypedArray(),
                        "receiver provenance",
                    ),
                    routeRequirement = enumValue(
                        fields[6],
                        DotNetGenericOwnerCallRouteRequirement.entries.toTypedArray(),
                        "route requirement",
                    ),
                )
            },
        )
        require(index == lines.size) { "generic-owner application route manifest has trailing records" }
        require(encode(manifest) == text) { "generic-owner application route manifest is not canonical" }
        return manifest
    }

    private fun String.encodedRouteText(): String = encoder.encodeToString(toByteArray(Charsets.UTF_8))

    private fun String.decodedRouteText(): String = try {
        decoder.decode(this).toString(Charsets.UTF_8).also { decoded ->
            require(decoded.encodedRouteText() == this) {
                "generic-owner application route manifest contains non-canonical encoded text"
            }
        }
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("generic-owner application route manifest contains invalid encoded text")
    }
}

/**
 * In-memory evidence returned by the backend pipeline for tests and architecture tooling. It is
 * not serialized into the DLL/KLIB, consumed by codegen, or selected by a compiler option.
 */
data class DotNetGenericOwnerPrototypeSnapshot(
    val ownerName: String,
    val genericArity: Int,
    val physicalGenericParameters: List<DotNetGenericOwnerPhysicalGenericParameterRecord>?,
    val physicalVisibility: DotNetGenericOwnerPhysicalTypeVisibility,
    val physicalDispatch: DotNetGenericOwnerPhysicalTypeDispatch,
    val isInner: Boolean,
    val directSupertypeCount: Int,
    val directFieldCount: Int,
    val anonymousInitializerCount: Int,
    val directNestedClassCount: Int,
    val disposition: DotNetGenericOwnerCandidateDisposition,
    val logicalBindingKey: String?,
    val constructors: List<DotNetGenericOwnerPrototypeConstructorSnapshot>,
    val members: List<DotNetGenericOwnerPrototypeMemberSnapshot>,
    val states: List<DotNetGenericOwnerPrototypeStateSnapshot>,
    val metadataFixedConditionalSupertypeCount: Int,
)

/** One compiler-derived MethodDef override for a future Kotlin-produced generic subclass. */
data class DotNetGenericOwnerPhysicalizedOverrideSlotRecord(
    val role: DotNetGenericOwnerMemberFamilyRole,
    val physicalMethod: DotNetGenericOwnerPhysicalMethodIdentityRecord,
    val visibility: DotNetGenericOwnerPhysicalMemberVisibility,
    val dispatch: DotNetGenericOwnerPhysicalMemberDispatch,
    val overriddenLogicalMemberKey: String,
    val overriddenPhysicalMethod: DotNetGenericOwnerPhysicalMethodIdentityRecord,
    val overriddenPhysicalDispatch: DotNetGenericOwnerPhysicalMemberDispatch,
) {
    init {
        require(role == DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY ||
                role == DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK) {
            "a Kotlin generic subclass may physicalize only typed or semantic override slots"
        }
        require(role != DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK ||
                visibility == DotNetGenericOwnerPhysicalMemberVisibility.FAMILY) {
            "a Kotlin generic subclass semantic hook must retain protected physical visibility"
        }
        require(physicalMethod.physicalMethodName == overriddenPhysicalMethod.physicalMethodName &&
                physicalMethod.signature == overriddenPhysicalMethod.signature) {
            "a Kotlin generic subclass override must retain the producer-selected MethodDef name and signature"
        }
        require(overriddenPhysicalDispatch != DotNetGenericOwnerPhysicalMemberDispatch.FINAL) {
            "a Kotlin generic subclass cannot override a final producer slot"
        }
        require(overriddenLogicalMemberKey.isNotEmpty()) {
            "a Kotlin generic subclass override requires its producer logical-member join"
        }
    }
}

data class DotNetGenericOwnerPhysicalizedMemberRecord(
    val sourceIndex: Int,
    val sourceName: String,
    val logicalMemberKey: String?,
    val slots: List<DotNetGenericOwnerPhysicalizedOverrideSlotRecord>,
    val directSuperTargets: List<DotNetGenericOwnerPhysicalDirectSuperTargetRecord>,
) {
    init {
        require(sourceIndex >= 0 && sourceName.isNotEmpty() && slots.isNotEmpty()) {
            "a Kotlin generic subclass physical member requires a source identity and override slots"
        }
        require(slots.map { slot -> slot.role }.toSet().size == slots.size) {
            "a Kotlin generic subclass physical member has duplicate role slots"
        }
        require(directSuperTargets.map { target -> target.role }.toSet().size == directSuperTargets.size &&
                directSuperTargets.all { target ->
                    slots.singleOrNull { slot -> slot.role == target.role }?.let { slot ->
                        target.logicalTargetMemberKey == slot.overriddenLogicalMemberKey &&
                                target.physicalOwnerPath == slot.overriddenPhysicalMethod.physicalOwnerPath &&
                                target.physicalMethodName == slot.overriddenPhysicalMethod.physicalMethodName &&
                                target.signature == slot.overriddenPhysicalMethod.signature
                    } == true
                }) {
            "a Kotlin generic subclass direct-super target disagrees with its override slot"
        }
    }
}

data class DotNetGenericOwnerPhysicalizedConstructorRecord(
    val sourceIndex: Int,
    val logicalConstructorKey: String?,
    val visibility: DotNetGenericOwnerPhysicalConstructorVisibility,
    val constructedOwnerType: DotNetGenericOwnerPhysicalTypeExpressionRecord,
    val physicalConstructor: DotNetGenericOwnerPhysicalMethodIdentityRecord,
    val delegatedLogicalConstructorKey: String,
    val constructedBaseOwner: DotNetGenericOwnerPhysicalTypeExpressionRecord,
    val delegatedPhysicalConstructor: DotNetGenericOwnerPhysicalMethodIdentityRecord,
) {
    init {
        require(sourceIndex >= 0 && logicalConstructorKey?.isNotEmpty() != false &&
                delegatedLogicalConstructorKey.isNotEmpty()) {
            "a Kotlin generic subclass constructor requires complete source and base identities"
        }
        require(physicalConstructor.physicalMethodName == ".ctor" &&
                delegatedPhysicalConstructor.physicalMethodName == ".ctor" &&
                physicalConstructor.signature == delegatedPhysicalConstructor.signature) {
            "a Kotlin generic subclass constructor must retain the producer-selected base signature"
        }
        require(constructedOwnerType.kind == DotNetGenericOwnerPhysicalTypeKind.NAMED &&
                constructedOwnerType.scope == DotNetGenericOwnerPhysicalTypeScope.CURRENT_COMPILATION &&
                constructedOwnerType.typePath == physicalConstructor.physicalOwnerPath &&
                constructedBaseOwner.kind == DotNetGenericOwnerPhysicalTypeKind.NAMED &&
                constructedBaseOwner.scope == DotNetGenericOwnerPhysicalTypeScope.PRODUCER &&
                constructedBaseOwner.typePath == delegatedPhysicalConstructor.physicalOwnerPath &&
                constructedOwnerType != constructedBaseOwner) {
            "a Kotlin generic subclass constructor requires distinct exact owner and base constructions"
        }
    }
}

/** Complete production-inert physicalization proof for one external Kotlin subclass. */
data class DotNetGenericOwnerPhysicalizedSubclassRecord(
    val proofKind: DotNetGenericOwnerPhysicalizationProofKind,
    val logicalOwnerKey: String?,
    val physicalOwnerPath: List<String>,
    val genericArity: Int,
    val physicalGenericParameters: List<DotNetGenericOwnerPhysicalGenericParameterRecord>,
    val visibility: DotNetGenericOwnerPhysicalTypeVisibility,
    val dispatch: DotNetGenericOwnerPhysicalTypeDispatch,
    val constructor: DotNetGenericOwnerPhysicalizedConstructorRecord,
    val members: List<DotNetGenericOwnerPhysicalizedMemberRecord>,
) {
    init {
        require(physicalOwnerPath.isNotEmpty() && physicalOwnerPath.all(String::isNotEmpty) && genericArity > 0) {
            "a Kotlin generic subclass physicalization requires an open implementation TypeDef"
        }
        require(physicalGenericParameters.map { parameter -> parameter.index } == (0 until genericArity).toList()) {
            "a Kotlin generic subclass physicalization requires every ordered GenericParam constraint row"
        }
        require(visibility == DotNetGenericOwnerPhysicalTypeVisibility.PUBLIC &&
                dispatch == DotNetGenericOwnerPhysicalTypeDispatch.OVERRIDABLE) {
            "the current Kotlin generic subclass proof requires a public open TypeDef"
        }
        val exactOwnerParameterVector = (0 until genericArity).toList()
        require(constructor.constructedOwnerType.arguments.map { argument -> argument.parameterIndex } ==
                exactOwnerParameterVector &&
                constructor.constructedOwnerType.arguments.all { argument ->
                    argument.kind == DotNetGenericOwnerPhysicalTypeKind.OWNER_TYPE_PARAMETER
                } &&
                constructor.constructedBaseOwner.arguments.map { argument -> argument.parameterIndex } ==
                exactOwnerParameterVector &&
                constructor.constructedBaseOwner.arguments.all { argument ->
                    argument.kind == DotNetGenericOwnerPhysicalTypeKind.OWNER_TYPE_PARAMETER
                }) {
            "a Kotlin generic subclass physicalization requires an exact producer base construction"
        }
        require(constructor.physicalConstructor.physicalOwnerPath == physicalOwnerPath &&
                constructor.constructedOwnerType.typePath == physicalOwnerPath &&
                constructor.constructedBaseOwner.typePath != physicalOwnerPath) {
            "a Kotlin generic subclass physical constructor disagrees with its exact base construction"
        }
        require(members.isNotEmpty() && members.map { member -> member.sourceIndex }.toSet().size == members.size) {
            "a Kotlin generic subclass physicalization requires unique compiler-derived members"
        }
        require(members.flatMap { member -> member.slots }.all { slot ->
            slot.physicalMethod.physicalOwnerPath == physicalOwnerPath
        }) {
            "a Kotlin generic subclass physicalized MethodDef belongs to another owner"
        }
    }
}

/** One statically rooted route in a finite open-nullable construction plan. */
data class DotNetGenericOwnerPhysicalConstructionRouteRecord(
    val kind: DotNetGenericOwnerConstructionRouteKind,
    val runtimeArgumentType: DotNetGenericOwnerPhysicalTypeExpressionRecord?,
    val constructedOwnerType: DotNetGenericOwnerPhysicalTypeExpressionRecord,
    val logicalConstructorKey: String,
    val physicalConstructor: DotNetGenericOwnerPhysicalMethodIdentityRecord,
) {
    init {
        require(logicalConstructorKey.isNotEmpty()) {
            "a generic-owner construction route requires a logical constructor"
        }
        require(constructedOwnerType.kind == DotNetGenericOwnerPhysicalTypeKind.NAMED &&
                constructedOwnerType.scope == DotNetGenericOwnerPhysicalTypeScope.PRODUCER &&
                constructedOwnerType.genericArity == 1 &&
                constructedOwnerType.typePath == physicalConstructor.physicalOwnerPath &&
                !constructedOwnerType.containsTypeParameter()) {
            "a generic-owner construction route requires one concrete producer owner"
        }
        require(physicalConstructor.physicalMethodName == ".ctor" &&
                physicalConstructor.signature.isInstance &&
                physicalConstructor.signature.genericArity == 0 &&
                physicalConstructor.signature.returnSlot.type.kind == DotNetGenericOwnerPhysicalTypeKind.VOID &&
                physicalConstructor.signature.parameterSlots.singleOrNull()?.let { parameter ->
                    parameter.domain == DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_INPUT &&
                            parameter.type == DotNetGenericOwnerPhysicalTypeExpressionRecord.ownerParameter(0)
                } == true) {
            "the finite open-nullable proof requires one exact owner-parameter constructor"
        }
        when (kind) {
            DotNetGenericOwnerConstructionRouteKind.RUNTIME_EXACT -> {
                val runtimeType = requireNotNull(runtimeArgumentType) {
                    "a runtime-exact generic-owner route requires its concrete runtime token"
                }
                require(runtimeType.openNullableRuntimeArgumentOrNull() == constructedOwnerType.arguments.single()) {
                    "a runtime-exact generic-owner route has an inexact open-nullable construction"
                }
            }
            DotNetGenericOwnerConstructionRouteKind.SEMANTIC_FALLBACK -> require(
                runtimeArgumentType == null &&
                        constructedOwnerType.arguments.single() ==
                        DotNetGenericOwnerPhysicalTypeExpressionRecord.objectType()
            ) {
                "an open-nullable semantic fallback must be the default C<object> route"
            }
        }
    }
}

/**
 * Consumer/application construction evidence. It is separate from the producer artifact because
 * the finite runtime-token roots belong to the final compilation, not the generic TypeDef.
 */
data class DotNetGenericOwnerPhysicalConstructionPlanRecord(
    val proofKind: DotNetGenericOwnerConstructionPlanProofKind,
    val producerFingerprint: String,
    val targetProfile: DotNetGenericOwnerPhysicalTargetProfile,
    val logicalConstructionKey: String,
    val logicalOwnerKey: String,
    val dispatchKind: DotNetGenericOwnerConstructionDispatchKind,
    val resultCarrierKind: DotNetGenericOwnerConstructionResultCarrierKind,
    val physicalCapabilityOwnerPath: List<String>,
    val routes: List<DotNetGenericOwnerPhysicalConstructionRouteRecord>,
) {
    init {
        require(producerFingerprint.matches(Regex("[0-9a-f]{64}")) &&
                logicalConstructionKey.isNotEmpty() && logicalOwnerKey.isNotEmpty()) {
            "a generic-owner construction plan requires producer and logical identities"
        }
        require(physicalCapabilityOwnerPath.isNotEmpty() && physicalCapabilityOwnerPath.all(String::isNotEmpty)) {
            "a generic-owner construction plan requires its semantic capability"
        }
        val exactRoutes = routes.filter { route ->
            route.kind == DotNetGenericOwnerConstructionRouteKind.RUNTIME_EXACT
        }
        val fallbackRoutes = routes.filter { route ->
            route.kind == DotNetGenericOwnerConstructionRouteKind.SEMANTIC_FALLBACK
        }
        require(exactRoutes.isNotEmpty() && fallbackRoutes.size == 1 &&
                exactRoutes.map { route -> route.runtimeArgumentType }.toSet().size == exactRoutes.size) {
            "a finite generic-owner construction plan requires unique exact roots and one fallback"
        }
        require(routes.map { route -> route.logicalConstructorKey }.toSet().size == 1 &&
                routes.map { route -> route.physicalConstructor }.toSet().size == 1 &&
                routes.map { route -> route.constructedOwnerType.typePath }.toSet().size == 1 &&
                routes.map { route -> route.constructedOwnerType.namedTypeCategory }.toSet().size == 1 &&
                routes.single { route ->
                    route.kind == DotNetGenericOwnerConstructionRouteKind.SEMANTIC_FALLBACK
                }.constructedOwnerType.typePath != physicalCapabilityOwnerPath) {
            "a generic-owner construction plan cannot mix owners or constructors"
        }
    }

    fun selectRoute(
        runtimeArgumentType: DotNetGenericOwnerPhysicalTypeExpressionRecord,
    ): DotNetGenericOwnerPhysicalConstructionRouteRecord {
        require(runtimeArgumentType.openNullableRuntimeArgumentOrNull() != null) {
            "a generic-owner construction selector requires one concrete runtime type"
        }
        return routes.singleOrNull { route ->
            route.kind == DotNetGenericOwnerConstructionRouteKind.RUNTIME_EXACT &&
                    route.runtimeArgumentType == runtimeArgumentType
        } ?: routes.single { route ->
            route.kind == DotNetGenericOwnerConstructionRouteKind.SEMANTIC_FALLBACK
        }
    }
}

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
    val physicalGenericParameters: List<DotNetGenericOwnerPhysicalGenericParameterRecord>,
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
        require(physicalGenericParameters.map { parameter -> parameter.index } == (0 until genericArity).toList()) {
            "generic-owner physical family '$logicalOwnerKey' requires every ordered GenericParam constraint row"
        }
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
        val physicalMethodDefinitions = members.flatMap { member ->
            buildList {
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
        }
        require(physicalMethodDefinitions.toSet().size == physicalMethodDefinitions.size) {
            "generic-owner physical family '$logicalOwnerKey' has colliding physical MethodDefs"
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
                states.map { state -> state.physicalType } +
                physicalGenericParameters.flatMap { parameter -> parameter.typeConstraints })
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
            constructor.physicalConstructor.signature.allTypes() + constructor.delegation.signature.allTypes() + listOf(
                constructor.constructedOwnerType,
                constructor.delegation.physicalOwnerType,
            )
        }).flatMap { type -> type.typeParameterReferences() }.all { reference ->
            reference.first != DotNetGenericOwnerPhysicalTypeKind.OWNER_TYPE_PARAMETER ||
                    reference.second < genericArity
        }) {
            "generic-owner physical family '$logicalOwnerKey' has a construction record with a missing owner parameter"
        }
        require((recordedMethods.flatMap { recordedMethod -> recordedMethod.second.allTypes() } +
                states.map { state -> state.physicalType } +
                physicalGenericParameters.flatMap { parameter -> parameter.typeConstraints } +
                constructors.flatMap { constructor ->
                    constructor.physicalConstructor.signature.allTypes() +
                            constructor.delegation.signature.allTypes() + listOf(
                        constructor.constructedOwnerType,
                        constructor.delegation.physicalOwnerType,
                    )
                }).none { type ->
            type.referencesScope(DotNetGenericOwnerPhysicalTypeScope.CURRENT_COMPILATION)
        }) {
            "generic-owner producer artifact cannot reference a consumer compilation TypeDef"
        }
    }
}

/**
 * Producer-authoritative classification of one logically bindable generic owner.
 *
 * This catalog is deliberately separate from [DotNetGenericOwnerPhysicalFamilyRecord]: a
 * declaration may be known and deterministically kept erased without publishing a prototype
 * CLR-generic family. Constructor/member keys make that absence distinguishable from an
 * incomplete or unrelated producer artifact.
 */
data class DotNetGenericOwnerCandidateClassificationRecord(
    val logicalOwnerKey: String,
    val genericArity: Int,
    val disposition: DotNetGenericOwnerCandidateDisposition,
    val logicalConstructorKeys: List<String>,
    val logicalMemberKeys: List<String>,
) {
    init {
        require(logicalOwnerKey.isNotEmpty()) {
            "a generic-owner candidate classification requires a logical owner key"
        }
        require(genericArity > 0) {
            "generic-owner candidate '$logicalOwnerKey' requires positive arity"
        }
        require(logicalConstructorKeys.all(String::isNotEmpty) &&
                logicalConstructorKeys == logicalConstructorKeys.distinct().sorted()) {
            "generic-owner candidate '$logicalOwnerKey' has unordered or duplicate logical constructors"
        }
        require(logicalMemberKeys.all(String::isNotEmpty) &&
                logicalMemberKeys == logicalMemberKeys.distinct().sorted()) {
            "generic-owner candidate '$logicalOwnerKey' has unordered or duplicate logical members"
        }
    }
}

/** One detached, producer-fingerprinted family artifact used only by architecture evidence. */
data class DotNetGenericOwnerPhysicalFamilyArtifact(
    val producerFingerprint: String,
    val targetProfile: DotNetGenericOwnerPhysicalTargetProfile,
    val classifications: List<DotNetGenericOwnerCandidateClassificationRecord>,
    val owners: List<DotNetGenericOwnerPhysicalFamilyRecord>,
) {
    init {
        require(PRODUCER_FINGERPRINT.matches(producerFingerprint)) {
            "a generic-owner family artifact requires a lowercase SHA-256 producer fingerprint"
        }
        require(classifications.isNotEmpty()) {
            "a generic-owner family artifact requires a complete producer classification catalog"
        }
        require(classifications.map { classification -> classification.logicalOwnerKey }.toSet().size ==
                classifications.size) {
            "a generic-owner family artifact has duplicate candidate classifications"
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
        val classificationsByOwner = classifications.associateBy { classification ->
            classification.logicalOwnerKey
        }
        require(owners.all { owner ->
            classificationsByOwner[owner.logicalOwnerKey]?.let { classification ->
                classification.genericArity == owner.genericArity &&
                        classification.disposition == owner.disposition &&
                        classification.logicalConstructorKeys ==
                        owner.constructors.map { constructor -> constructor.logicalConstructorKey }.sorted() &&
                        classification.logicalMemberKeys ==
                        owner.members.map { member -> member.logicalMemberKey }.sorted()
            } == true
        }) {
            "a generic-owner physical family disagrees with its producer candidate classification"
        }
        val constructors = owners.flatMap { owner -> owner.constructors }
        require(constructors.map { constructor -> constructor.logicalConstructorKey }.toSet().size == constructors.size) {
            "a generic-owner family artifact has duplicate logical constructors across owners"
        }
        val constructorsByLogicalKey = constructors.associateBy { constructor -> constructor.logicalConstructorKey }
        val ownersByPhysicalPath = owners.associateBy { owner -> owner.physicalOwnerPath }
        require(constructors.all { constructor ->
            val delegation = constructor.delegation
            val target = delegation.logicalConstructorKey?.let(constructorsByLogicalKey::get)
            if (target == null) {
                delegation.physicalOwnerType.scope != DotNetGenericOwnerPhysicalTypeScope.PRODUCER
            } else {
                delegation.physicalOwnerType.scope == DotNetGenericOwnerPhysicalTypeScope.PRODUCER &&
                        delegation.physicalOwnerType.typePath == target.physicalConstructor.physicalOwnerPath &&
                        ownersByPhysicalPath.getValue(constructor.physicalConstructor.physicalOwnerPath)
                            .physicalGenericParameters ==
                        ownersByPhysicalPath.getValue(target.physicalConstructor.physicalOwnerPath)
                            .physicalGenericParameters &&
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

/**
 * Returns one producer-selected physical family or fails with its recorded candidate
 * classification. This never treats the absence of a family as permission to reconstruct one.
 */
fun DotNetGenericOwnerPhysicalFamilyArtifact.requirePhysicalFamily(
    logicalOwnerKey: String,
): DotNetGenericOwnerPhysicalFamilyRecord {
    val classification = classifications.singleOrNull { candidate ->
        candidate.logicalOwnerKey == logicalOwnerKey
    } ?: error("generic-owner family artifact lacks producer classification '$logicalOwnerKey'")
    return owners.singleOrNull { owner -> owner.logicalOwnerKey == logicalOwnerKey }
        ?: error(
            "generic-owner candidate '$logicalOwnerKey' has no physical family " +
                    "(${classification.disposition})"
        )
}

/**
 * Resolves one consumer call-site obligation exclusively through a decoded producer catalog.
 * Diagnostic source names are deliberately ignored: the logical member key selects either one
 * published physical family or one producer-authoritative erased-owner classification.
 */
fun DotNetGenericOwnerCallRouteSnapshot.resolveExternalPhysicalFamilyRoute(
    artifact: DotNetGenericOwnerPhysicalFamilyArtifact,
): DotNetGenericOwnerCallRouteSnapshot {
    if (routeRequirement != DotNetGenericOwnerCallRouteRequirement.EXTERNAL_FAMILY_RECORD_REQUIRED) return this
    val logicalMemberKey = requireNotNull(calleeLogicalBindingKey) {
        "an external generic-owner call route lacks its logical member binding"
    }
    val classifications = artifact.classifications.filter { classification ->
        logicalMemberKey in classification.logicalMemberKeys
    }
    if (classifications.isEmpty()) return this
    require(classifications.size == 1) {
        "generic-owner producer artifact has duplicate classifications for '$logicalMemberKey'"
    }
    val physicalFamilies = artifact.owners.mapNotNull { owner ->
        owner.members.singleOrNull { member -> member.logicalMemberKey == logicalMemberKey }
    }
    require(physicalFamilies.size <= 1) {
        "generic-owner producer artifact has duplicate physical families for '$logicalMemberKey'"
    }
    val resolvedRequirement = physicalFamilies.singleOrNull()?.let { family ->
        when {
            receiverProvenance == DotNetGenericOwnerCallReceiverProvenance.EXACT_CONSTRUCTION ->
                DotNetGenericOwnerCallRouteRequirement.EXACT_TYPED_ENTRY
            DotNetGenericOwnerMemberFamilyRole.CAPABILITY_DISPATCHER in family.roles ->
                DotNetGenericOwnerCallRouteRequirement.SEMANTIC_CAPABILITY
            else -> DotNetGenericOwnerCallRouteRequirement.MISSING_CAPABILITY
        }
    } ?: run {
        check(artifact.owners.none { owner -> owner.logicalOwnerKey == classifications.single().logicalOwnerKey }) {
            "generic-owner producer family omitted classified member '$logicalMemberKey'"
        }
        DotNetGenericOwnerCallRouteRequirement.PRODUCER_ERASED_OWNER
    }
    return copy(routeRequirement = resolvedRequirement)
}

/**
 * Builds a finite, statically rootable open-nullable construction table from a decoded producer.
 * The caller contributes only final-compilation runtime types and a logical construction identity;
 * the producer record remains authoritative for the owner, capability, and constructor MethodDef.
 */
fun DotNetGenericOwnerPhysicalFamilyArtifact.planFiniteOpenNullableConstruction(
    logicalConstructionKey: String,
    logicalOwnerKey: String,
    logicalConstructorKey: String,
    exactRuntimeArgumentTypes: List<DotNetGenericOwnerPhysicalTypeExpressionRecord>,
): DotNetGenericOwnerPhysicalConstructionPlanRecord {
    require(logicalConstructionKey.isNotEmpty()) {
        "a finite generic-owner construction plan requires a logical construction identity"
    }
    val owner = owners.singleOrNull { candidate -> candidate.logicalOwnerKey == logicalOwnerKey }
        ?: error("generic-owner construction plan lacks producer owner '$logicalOwnerKey'")
    require(owner.genericArity == 1) {
        "the finite open-nullable construction proof currently requires one owner parameter"
    }
    require(owner.physicalGenericParameters.singleOrNull()?.let { parameter ->
        parameter.index == 0 && parameter.specialConstraints.isEmpty() && parameter.typeConstraints.isEmpty()
    } == true) {
        "the finite C<object> fallback proof currently requires one unconstrained producer parameter"
    }
    val capabilityPath = requireNotNull(owner.physicalCapabilityOwnerPath) {
        "open-nullable construction requires a producer semantic capability"
    }
    val constructor = owner.constructors.singleOrNull { candidate ->
        candidate.logicalConstructorKey == logicalConstructorKey
    } ?: error("generic-owner construction plan lacks constructor '$logicalConstructorKey'")
    require(constructor.constructionMode == DotNetGenericOwnerConstructionMode.STATIC_EXACT &&
            constructor.visibility == DotNetGenericOwnerPhysicalConstructorVisibility.PUBLIC) {
        "the finite construction table requires one public statically exact producer constructor"
    }
    require(exactRuntimeArgumentTypes.isNotEmpty() &&
            exactRuntimeArgumentTypes.toSet().size == exactRuntimeArgumentTypes.size) {
        "a finite generic-owner construction plan requires unique exact runtime roots"
    }
    val routes = exactRuntimeArgumentTypes.map { runtimeType ->
        val physicalArgument = requireNotNull(runtimeType.openNullableRuntimeArgumentOrNull()) {
            "generic-owner construction root '$runtimeType' is not one concrete runtime type"
        }
        DotNetGenericOwnerPhysicalConstructionRouteRecord(
            kind = DotNetGenericOwnerConstructionRouteKind.RUNTIME_EXACT,
            runtimeArgumentType = runtimeType,
            constructedOwnerType = DotNetGenericOwnerPhysicalTypeExpressionRecord.producerType(
                typePath = owner.physicalOwnerPath,
                category = checkNotNull(constructor.constructedOwnerType.namedTypeCategory),
                arguments = listOf(physicalArgument),
            ),
            logicalConstructorKey = logicalConstructorKey,
            physicalConstructor = constructor.physicalConstructor,
        )
    } + DotNetGenericOwnerPhysicalConstructionRouteRecord(
        kind = DotNetGenericOwnerConstructionRouteKind.SEMANTIC_FALLBACK,
        runtimeArgumentType = null,
        constructedOwnerType = DotNetGenericOwnerPhysicalTypeExpressionRecord.producerType(
            typePath = owner.physicalOwnerPath,
            category = checkNotNull(constructor.constructedOwnerType.namedTypeCategory),
            arguments = listOf(DotNetGenericOwnerPhysicalTypeExpressionRecord.objectType()),
        ),
        logicalConstructorKey = logicalConstructorKey,
        physicalConstructor = constructor.physicalConstructor,
    )
    return DotNetGenericOwnerPhysicalConstructionPlanRecord(
        proofKind = DotNetGenericOwnerConstructionPlanProofKind.FINITE_OPEN_NULLABLE_WITH_SEMANTIC_FALLBACK,
        producerFingerprint = producerFingerprint,
        targetProfile = targetProfile,
        logicalConstructionKey = logicalConstructionKey,
        logicalOwnerKey = logicalOwnerKey,
        dispatchKind = DotNetGenericOwnerConstructionDispatchKind.FINITE_RUNTIME_TYPE_TOKEN_TABLE,
        resultCarrierKind = DotNetGenericOwnerConstructionResultCarrierKind.SEMANTIC_CAPABILITY,
        physicalCapabilityOwnerPath = capabilityPath,
        routes = routes,
    )
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
    const val SCHEMA_VERSION = 7
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
        val classifications = artifact.classifications.sortedBy { classification ->
            classification.logicalOwnerKey
        }
        appendLine("E\t${classifications.size}")
        classifications.forEach { classification ->
            appendLine(
                listOf(
                    "L",
                    classification.logicalOwnerKey.encoded(),
                    classification.genericArity.toString(),
                    classification.disposition.name,
                    classification.logicalConstructorKeys.size.toString(),
                    classification.logicalMemberKeys.size.toString(),
                ).joinToString("\t")
            )
            classification.logicalConstructorKeys.forEach { logicalConstructorKey ->
                appendLine("U\t${logicalConstructorKey.encoded()}")
            }
            classification.logicalMemberKeys.forEach { logicalMemberKey ->
                appendLine("V\t${logicalMemberKey.encoded()}")
            }
        }
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
                    owner.physicalGenericParameters.size.toString(),
                    owner.disposition.name,
                    owner.runtimeClassificationMode.name,
                    owner.constructionModes.sortedBy { mode -> mode.name }.joinToString(",") { mode -> mode.name },
                    owner.constructors.size.toString(),
                    members.size.toString(),
                    states.size.toString(),
                ).joinToString("\t")
            )
            owner.physicalGenericParameters.forEach { parameter ->
                appendLine(
                    listOf(
                        "G",
                        parameter.index.toString(),
                        parameter.specialConstraints.sortedBy { constraint -> constraint.name }
                            .joinToString(",") { constraint -> constraint.name }
                            .ifEmpty { "-" },
                        parameter.typeConstraints.size.toString(),
                    ).joinToString("\t")
                )
                parameter.typeConstraints.forEach { constraint ->
                    appendLine("B\t${constraint.serialized().encoded()}")
                }
            }
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
        val classificationCount = count(read("E", 2)[1], "candidate classification")
        val classifications = List(classificationCount) {
            val fields = read("L", 6)
            val logicalOwnerKey = fields[1].decoded()
            val genericArity = count(fields[2], "candidate generic-arity")
            val disposition = enumValue(
                fields[3],
                DotNetGenericOwnerCandidateDisposition.entries.toTypedArray(),
                "candidate disposition",
            )
            val logicalConstructorCount = count(fields[4], "candidate logical constructor")
            val logicalMemberCount = count(fields[5], "candidate logical member")
            DotNetGenericOwnerCandidateClassificationRecord(
                logicalOwnerKey = logicalOwnerKey,
                genericArity = genericArity,
                disposition = disposition,
                logicalConstructorKeys = List(logicalConstructorCount) {
                    read("U", 2)[1].decoded()
                },
                logicalMemberKeys = List(logicalMemberCount) {
                    read("V", 2)[1].decoded()
                },
            )
        }
        val ownerCount = count(read("N", 2)[1], "owner")
        val owners = List(ownerCount) {
            val fields = read("O", 12)
            val logicalOwnerKey = fields[1].decoded()
            val ownerPath = fields[2].decoded().split('\u0000')
            val capabilityOwnerPath = fields[3].takeUnless { it == "-" }?.decoded()?.split('\u0000')
            val genericArity = count(fields[4], "generic-arity")
            val physicalGenericParameterCount = count(fields[5], "physical generic parameter")
            val disposition = enumValue(
                fields[6],
                DotNetGenericOwnerCandidateDisposition.entries.toTypedArray(),
                "owner disposition",
            )
            val runtimeClassificationMode = enumValue(
                fields[7],
                DotNetGenericOwnerRuntimeClassificationMode.entries.toTypedArray(),
                "runtime classification mode",
            )
            val constructionModes = enumSet(
                fields[8],
                DotNetGenericOwnerConstructionMode.entries.toTypedArray(),
                "construction mode",
            )
            val constructorCount = count(fields[9], "constructor")
            val memberCount = count(fields[10], "member")
            val stateCount = count(fields[11], "state")
            val physicalGenericParameters = List(physicalGenericParameterCount) {
                val parameterFields = read("G", 4)
                val typeConstraintCount = count(parameterFields[3], "generic parameter type constraint")
                DotNetGenericOwnerPhysicalGenericParameterRecord(
                    index = count(parameterFields[1], "generic parameter index"),
                    specialConstraints = enumSet(
                        parameterFields[2],
                        DotNetGenericOwnerPhysicalGenericParameterSpecialConstraint.entries.toTypedArray(),
                        "generic parameter special constraint",
                    ),
                    typeConstraints = List(typeConstraintCount) {
                        read("B", 2)[1].decoded().deserializedType()
                    },
                )
            }
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
                physicalGenericParameters = physicalGenericParameters,
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
        return DotNetGenericOwnerPhysicalFamilyArtifact(
            producerFingerprint,
            targetProfile,
            classifications,
            owners,
        )
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
    val physicalOwnerPathsByLogicalKey = artifact.owners.associate { owner ->
        owner.logicalOwnerKey to owner.physicalOwnerPath
    }
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
    val classificationsByLogicalMember = artifact.classifications.flatMap { classification ->
        classification.logicalMemberKeys.map { logicalMemberKey -> logicalMemberKey to classification }
    }.groupBy({ entry -> entry.first }, { entry -> entry.second })
    fun externalMember(
        logicalMemberKey: String,
    ): Pair<DotNetGenericOwnerPhysicalFamilyRecord, DotNetGenericOwnerPhysicalMemberFamilyRecord> =
        externalMembers[logicalMemberKey] ?: run {
            val classifications = classificationsByLogicalMember[logicalMemberKey].orEmpty()
            if (classifications.isEmpty()) {
                error("producer generic-owner family artifact lacks logical member '$logicalMemberKey'")
            }
            error(
                "producer generic-owner candidate for logical member '$logicalMemberKey' has no physical family: " +
                        classifications.joinToString { classification ->
                            "${classification.logicalOwnerKey} (${classification.disposition})"
                        }
            )
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
            externalMember(logicalKey).second
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
            val producerEntry = externalMember(logicalKey)
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
            fun consumerSignature(role: DotNetGenericOwnerMemberFamilyRole):
                    DotNetGenericOwnerPhysicalMethodSignatureRecord? =
                member.exactPathUnboundSignatures?.get(role)?.let { signature ->
                    signature.bindProducerTypes(physicalOwnerPathsByLogicalKey).let { physicalSignature ->
                        physicalSignature.copy(
                            parameterSlots = physicalSignature.parameterSlots.mapIndexed { index, parameter ->
                                parameter.copy(domain = mergedParameterSlotDomains[index])
                            },
                        )
                    }
                }
            require(typedSlot.signature == consumerSignature(DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY)) {
                "consumer override '${member.sourceName}' disagrees with the producer typed physical signature"
            }
            if (semanticSlot != null) {
                require(semanticSlot.signature ==
                        (consumerSignature(DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK)
                            ?: consumerSignature(DotNetGenericOwnerMemberFamilyRole.CAPABILITY_DISPATCHER))) {
                    "consumer override '${member.sourceName}' disagrees with the producer semantic physical signature"
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
            // The local detached signatures predate producer-family binding and are no longer
            // authoritative after inherited broad domains and semantic roles are merged.
            exactPathUnboundSignatures = null,
            exactMaskedDefaultDispatcher = null,
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

/**
 * Turns a completely resolved external-subclass snapshot into an exact physical override record.
 * The caller selects only the target-owned TypeDef path; every construction, MethodDef,
 * signature, visibility, role, and direct-super target comes from compiler/KLIB evidence joined
 * to the decoded producer artifact.
 */
fun DotNetGenericOwnerPrototypeSnapshot.physicalizeExternalSubclass(
    artifact: DotNetGenericOwnerPhysicalFamilyArtifact,
    physicalOwnerPath: List<String>,
): DotNetGenericOwnerPhysicalizedSubclassRecord {
    require(disposition == DotNetGenericOwnerCandidateDisposition.REQUIRES_EXTERNAL_OVERRIDE_BINDING_SCHEMA) {
        "only an unresolved external Kotlin generic subclass may enter physicalization"
    }
    require(physicalVisibility == DotNetGenericOwnerPhysicalTypeVisibility.PUBLIC &&
            physicalDispatch == DotNetGenericOwnerPhysicalTypeDispatch.OVERRIDABLE &&
            !isInner && directSupertypeCount == 1 &&
            directFieldCount == 0 && anonymousInitializerCount == 0 && directNestedClassCount == 0 &&
            metadataFixedConditionalSupertypeCount == 0 && states.isEmpty()) {
        "the current Kotlin generic subclass proof requires one public external base and no local state or nested types"
    }
    require(physicalOwnerPath.isNotEmpty() && physicalOwnerPath.all(String::isNotEmpty) &&
            artifact.owners.none { owner ->
                owner.physicalOwnerPath == physicalOwnerPath || owner.physicalCapabilityOwnerPath == physicalOwnerPath
            }) {
        "a Kotlin generic subclass physicalization requires a distinct selected TypeDef path"
    }
    val resolved = resolveExternalPhysicalFamilies(artifact)
    require(resolved.disposition == DotNetGenericOwnerCandidateDisposition.REQUIRES_MEMBER_PHYSICALIZATION_PROOF) {
        "external Kotlin generic subclass resolution did not reach physicalization proof"
    }
    val producerMembers = artifact.owners.flatMap { owner ->
        owner.members.map { member -> member.logicalMemberKey to (owner to member) }
    }.toMap()
    val physicalMembers = resolved.members.mapNotNull { member ->
        val bindings = member.overrideBindings.filter { binding ->
            binding.targetKind == DotNetGenericOwnerOverrideTargetKind.EXTERNAL_PHYSICAL_FAMILY_RECORD
        }
        if (bindings.isEmpty()) return@mapNotNull null
        val slots = bindings.map { binding ->
            val logicalKey = requireNotNull(binding.overriddenLogicalBindingKey)
            val producerEntry = producerMembers[logicalKey]
                ?: error("producer generic-owner artifact lost logical member '$logicalKey'")
            val overridden = producerEntry.second.slots.single { slot -> slot.role == binding.role }
            require(binding.overriddenPhysicalOwnerPath == overridden.physicalOwnerPath &&
                    binding.overriddenPhysicalMethodName == overridden.physicalMethodName &&
                    binding.overriddenPhysicalSignature == overridden.signature &&
                    binding.overriddenPhysicalDispatch == overridden.dispatch) {
                "resolved Kotlin subclass member '${member.sourceName}' disagrees with its producer MethodDef"
            }
            DotNetGenericOwnerPhysicalizedOverrideSlotRecord(
                role = binding.role,
                physicalMethod = DotNetGenericOwnerPhysicalMethodIdentityRecord(
                    physicalOwnerPath = physicalOwnerPath,
                    physicalMethodName = overridden.physicalMethodName,
                    signature = overridden.signature,
                ),
                visibility = if (binding.role == DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK) {
                    DotNetGenericOwnerPhysicalMemberVisibility.FAMILY
                } else {
                    member.physicalVisibility
                },
                dispatch = when {
                    member.isAbstract -> DotNetGenericOwnerPhysicalMemberDispatch.ABSTRACT
                    member.isOverridable -> DotNetGenericOwnerPhysicalMemberDispatch.OVERRIDABLE
                    else -> DotNetGenericOwnerPhysicalMemberDispatch.FINAL
                },
                overriddenLogicalMemberKey = logicalKey,
                overriddenPhysicalMethod = DotNetGenericOwnerPhysicalMethodIdentityRecord(
                    overridden.physicalOwnerPath,
                    overridden.physicalMethodName,
                    overridden.signature,
                ),
                overriddenPhysicalDispatch = overridden.dispatch,
            )
        }
        require(slots.map { slot -> slot.role }.toSet().size == slots.size) {
            "external Kotlin subclass member '${member.sourceName}' has duplicate physical override roles"
        }
        val directSuperTargets = if (member.directSuperCallCount == 0) {
            emptyList()
        } else {
            require(member.directSuperCallCount == 1 && member.directSuperCalls.size == 1) {
                "external Kotlin subclass member '${member.sourceName}' has an unsupported direct-super graph"
            }
            val call = member.directSuperCalls.single()
            slots.map { slot ->
                require(call.logicalMemberKey == slot.overriddenLogicalMemberKey) {
                    "external Kotlin subclass direct-super logical target disagrees with its override family"
                }
                DotNetGenericOwnerPhysicalDirectSuperTargetRecord(
                    role = slot.role,
                    logicalTargetMemberKey = slot.overriddenLogicalMemberKey,
                    physicalOwnerPath = slot.overriddenPhysicalMethod.physicalOwnerPath,
                    physicalMethodName = slot.overriddenPhysicalMethod.physicalMethodName,
                    signature = slot.overriddenPhysicalMethod.signature,
                )
            }
        }
        DotNetGenericOwnerPhysicalizedMemberRecord(
            sourceIndex = member.sourceIndex,
            sourceName = member.sourceName,
            logicalMemberKey = member.logicalBindingKey,
            slots = slots,
            directSuperTargets = directSuperTargets,
        )
    }
    val omittedMembers = resolved.members.filter { member ->
        member.overrideBindings.none { binding ->
            binding.targetKind == DotNetGenericOwnerOverrideTargetKind.EXTERNAL_PHYSICAL_FAMILY_RECORD
        }
    }
    require(omittedMembers.all { member -> member.isFakeOverride }) {
        "the current Kotlin generic subclass proof cannot omit members " +
                omittedMembers.filterNot { member -> member.isFakeOverride }
                    .joinToString { member -> member.sourceName }
    }
    val sourceConstructor = resolved.constructors.singleOrNull { constructor -> !constructor.delegatesToThis }
        ?: error("a Kotlin generic subclass physicalization requires one primary base-delegating constructor")
    require(resolved.constructors.size == 1) {
        "the current Kotlin generic subclass physicalization cannot omit secondary constructors"
    }
    val delegatedLogicalKey = requireNotNull(sourceConstructor.delegatedConstructorLogicalBindingKey) {
        "a Kotlin generic subclass constructor lacks its external logical base constructor"
    }
    val delegatedConstructorEntry = artifact.owners.flatMap { owner ->
        owner.constructors.map { constructor -> owner to constructor }
    }.singleOrNull { entry -> entry.second.logicalConstructorKey == delegatedLogicalKey }
        ?: error("producer generic-owner artifact lacks unique delegated constructor '$delegatedLogicalKey'")
    val producerOwner = delegatedConstructorEntry.first
    val delegatedConstructor = delegatedConstructorEntry.second
    require(producerOwner.genericArity == genericArity) {
        "the current Kotlin generic subclass proof requires an exact owner-parameter base vector"
    }
    val sourceGenericParameters = requireNotNull(physicalGenericParameters) {
        "the Kotlin generic subclass owner is outside the exact CLR GenericParam constraint grammar"
    }
    require(sourceGenericParameters == producerOwner.physicalGenericParameters) {
        "the current Kotlin generic subclass proof requires exact producer GenericParam constraints"
    }
    require(physicalMembers.flatMap { member -> member.slots }.all { slot ->
        artifact.owners.any { owner -> owner.physicalOwnerPath == slot.overriddenPhysicalMethod.physicalOwnerPath }
    }) {
        "a Kotlin generic subclass override targets a MethodDef outside its producer artifact"
    }
    val sourceConstructorSignature = requireNotNull(sourceConstructor.exactPathUnboundSignature) {
        "the Kotlin generic subclass constructor is outside the exact physical signature grammar"
    }.bindProducerTypes(
        artifact.owners.associate { owner -> owner.logicalOwnerKey to owner.physicalOwnerPath } +
                logicalBindingKey?.let { key -> mapOf(key to physicalOwnerPath) }.orEmpty(),
    )
    require(sourceConstructor.delegationArgumentMapping ==
            DotNetGenericOwnerConstructorArgumentMapping.POSITIONAL_IDENTITY &&
            sourceConstructor.hasOnlyDelegationAndInstanceInitializer) {
        "the Kotlin generic subclass constructor has a transformed delegation or additional body effects"
    }
    require(sourceConstructorSignature == delegatedConstructor.physicalConstructor.signature) {
        "a Kotlin generic subclass constructor disagrees with the exact producer signature"
    }
    val ownerArguments = List(genericArity) { index ->
        DotNetGenericOwnerPhysicalTypeExpressionRecord.ownerParameter(index)
    }
    val constructedOwner = DotNetGenericOwnerPhysicalTypeExpressionRecord.currentCompilationType(
        typePath = physicalOwnerPath,
        category = DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS,
        arguments = ownerArguments,
    )
    val constructedBase = delegatedConstructor.constructedOwnerType
    return DotNetGenericOwnerPhysicalizedSubclassRecord(
        proofKind = DotNetGenericOwnerPhysicalizationProofKind.COMPILER_DERIVED_EXTERNAL_SUBCLASS,
        logicalOwnerKey = logicalBindingKey,
        physicalOwnerPath = physicalOwnerPath,
        genericArity = genericArity,
        physicalGenericParameters = sourceGenericParameters,
        visibility = physicalVisibility,
        dispatch = physicalDispatch,
        constructor = DotNetGenericOwnerPhysicalizedConstructorRecord(
            sourceIndex = sourceConstructor.sourceIndex,
            logicalConstructorKey = sourceConstructor.logicalBindingKey,
            visibility = sourceConstructor.physicalVisibility,
            constructedOwnerType = constructedOwner,
            physicalConstructor = DotNetGenericOwnerPhysicalMethodIdentityRecord(
                physicalOwnerPath,
                ".ctor",
                sourceConstructorSignature,
            ),
            delegatedLogicalConstructorKey = delegatedLogicalKey,
            constructedBaseOwner = constructedBase,
            delegatedPhysicalConstructor = delegatedConstructor.physicalConstructor,
        ),
        members = physicalMembers,
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

internal data class DotNetGenericOwnerCallRoutePlan(
    val callerName: String,
    val callerLogicalBindingKey: String?,
    val callSiteIndex: Int,
    /** Exact lowered call retained only for optional architecture-test tracing. */
    val call: IrCall,
    val callee: IrSimpleFunction,
    val calleeOwner: IrClass,
    val calleeLogicalBindingKey: String?,
    val receiverProvenance: DotNetGenericOwnerCallReceiverProvenance,
    val routeRequirement: DotNetGenericOwnerCallRouteRequirement,
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

private fun IrSimpleFunction.genericOwnerPhysicalVisibility(): DotNetGenericOwnerPhysicalMemberVisibility =
    when (visibility) {
        DescriptorVisibilities.PUBLIC -> DotNetGenericOwnerPhysicalMemberVisibility.PUBLIC
        DescriptorVisibilities.PROTECTED -> DotNetGenericOwnerPhysicalMemberVisibility.FAMILY
        DescriptorVisibilities.INTERNAL -> DotNetGenericOwnerPhysicalMemberVisibility.ASSEMBLY
        DescriptorVisibilities.PRIVATE -> DotNetGenericOwnerPhysicalMemberVisibility.PRIVATE
        else -> error("unsupported generic-owner prototype member visibility '$visibility'")
    }

private fun IrConstructor.genericOwnerPhysicalVisibility(): DotNetGenericOwnerPhysicalConstructorVisibility =
    when (visibility) {
        DescriptorVisibilities.PUBLIC -> DotNetGenericOwnerPhysicalConstructorVisibility.PUBLIC
        DescriptorVisibilities.PROTECTED -> DotNetGenericOwnerPhysicalConstructorVisibility.FAMILY
        DescriptorVisibilities.INTERNAL -> DotNetGenericOwnerPhysicalConstructorVisibility.ASSEMBLY
        DescriptorVisibilities.PRIVATE -> DotNetGenericOwnerPhysicalConstructorVisibility.PRIVATE
        else -> error("unsupported generic-owner prototype constructor visibility '$visibility'")
    }

private enum class DotNetGenericOwnerPrototypeTypeUse {
    CALLABLE,
    STATE,
}

/**
 * Captures the bounded CLR carrier grammar before producer TypeDef paths are selected.
 * Callable fallbacks preserve non-exact Kotlin positions; state remains exact or unavailable.
 */
private fun IrType.genericOwnerPrototypeType(
    owner: IrClass,
    preLoweringDeclarationKeys: Map<IrDeclaration, String>,
    use: DotNetGenericOwnerPrototypeTypeUse,
    method: IrSimpleFunction? = null,
    eraseOwnerDependentCarrier: Boolean = false,
): DotNetGenericOwnerPrototypeTypeSnapshot? {
    if (isUnit()) {
        return if (use == DotNetGenericOwnerPrototypeTypeUse.CALLABLE) {
            DotNetGenericOwnerPrototypeTypeSnapshot.voidType()
        } else {
            null
        }
    }
    if (isBoolean()) return DotNetGenericOwnerPrototypeTypeSnapshot.booleanType()
    if (isInt()) return DotNetGenericOwnerPrototypeTypeSnapshot.int32Type()
    if (isString() || isNullableString()) return DotNetGenericOwnerPrototypeTypeSnapshot.stringType()
    if (isAny() || isNullableAny()) return DotNetGenericOwnerPrototypeTypeSnapshot.objectType()

    val simpleType = this as? IrSimpleType ?: return null
    val typeParameter = (simpleType.classifier as? IrTypeParameterSymbol)?.owner
    if (typeParameter != null) {
        if (simpleType.isMarkedNullable()) {
            return if (use == DotNetGenericOwnerPrototypeTypeUse.CALLABLE) {
                DotNetGenericOwnerPrototypeTypeSnapshot.objectType()
            } else {
                null
            }
        }
        val ownerParameterIndex = owner.typeParameters.indexOf(typeParameter)
        if (ownerParameterIndex >= 0) {
            return if (use == DotNetGenericOwnerPrototypeTypeUse.CALLABLE && eraseOwnerDependentCarrier) {
                DotNetGenericOwnerPrototypeTypeSnapshot.objectType()
            } else {
                DotNetGenericOwnerPrototypeTypeSnapshot.ownerParameter(ownerParameterIndex)
            }
        }
        val methodParameterIndex = method?.typeParameters?.indexOf(typeParameter) ?: -1
        return methodParameterIndex.takeIf { index ->
            use == DotNetGenericOwnerPrototypeTypeUse.CALLABLE && index >= 0
        }?.let(DotNetGenericOwnerPrototypeTypeSnapshot::methodParameter)
    }

    val classifier = (simpleType.classifier as? IrClassSymbol)?.owner ?: return null
    if (classifier.fqNameWhenAvailable?.asString() == "kotlin.Array") {
        val elementProjection = simpleType.arguments.singleOrNull() as? IrTypeProjection ?: return null
        if (elementProjection.variance != Variance.INVARIANT) {
            return if (use == DotNetGenericOwnerPrototypeTypeUse.CALLABLE) {
                DotNetGenericOwnerPrototypeTypeSnapshot.systemArrayType()
            } else {
                null
            }
        }
        val elementSimpleType = elementProjection.type as? IrSimpleType
        val elementParameter = (elementSimpleType?.classifier as? IrTypeParameterSymbol)?.owner
        if (use == DotNetGenericOwnerPrototypeTypeUse.CALLABLE &&
                elementSimpleType?.isMarkedNullable() == true &&
                (elementParameter in owner.typeParameters || elementParameter in method?.typeParameters.orEmpty())
        ) {
            return DotNetGenericOwnerPrototypeTypeSnapshot.systemArrayType()
        }
        val elementType = elementProjection.type.genericOwnerPrototypeType(
            owner = owner,
            preLoweringDeclarationKeys = preLoweringDeclarationKeys,
            use = use,
            method = method,
            eraseOwnerDependentCarrier = false,
        ) ?: return null
        if (elementType.kind == DotNetGenericOwnerPrototypeTypeKind.VOID) return null
        if (use == DotNetGenericOwnerPrototypeTypeUse.CALLABLE &&
                eraseOwnerDependentCarrier && referencesTypeParameterOf(owner)
        ) {
            return DotNetGenericOwnerPrototypeTypeSnapshot.systemArrayType()
        }
        return DotNetGenericOwnerPrototypeTypeSnapshot.szArray(elementType)
    }

    if (!classifier.isDotNetGenericClassDeclaration || classifier.isValue ||
            simpleType.arguments.size != classifier.typeParameters.size
    ) return null
    val logicalClassifierKey = preLoweringDeclarationKeys[classifier] ?: return null
    val arguments = simpleType.arguments.map { argument ->
        val projection = argument as? IrTypeProjection ?: return null
        if (projection.variance != Variance.INVARIANT) return null
        projection.type.genericOwnerPrototypeType(
            owner = owner,
            preLoweringDeclarationKeys = preLoweringDeclarationKeys,
            use = use,
            method = method,
            eraseOwnerDependentCarrier = false,
        ) ?: return null
    }
    if (use == DotNetGenericOwnerPrototypeTypeUse.CALLABLE &&
            eraseOwnerDependentCarrier && referencesTypeParameterOf(owner)
    ) {
        return DotNetGenericOwnerPrototypeTypeSnapshot.objectType()
    }
    return DotNetGenericOwnerPrototypeTypeSnapshot.logicalGenericClassifier(
        logicalClassifierKey = logicalClassifierKey,
        arguments = arguments,
    )
}

private fun IrType.genericOwnerPrototypeStateType(
    owner: IrClass,
    preLoweringDeclarationKeys: Map<IrDeclaration, String>,
): DotNetGenericOwnerPrototypeTypeSnapshot? = genericOwnerPrototypeType(
    owner = owner,
    preLoweringDeclarationKeys = preLoweringDeclarationKeys,
    use = DotNetGenericOwnerPrototypeTypeUse.STATE,
)

private fun DotNetGenericOwnerConstructorPlan.exactPrototypePathUnboundSignature(
    owner: IrClass,
    preLoweringDeclarationKeys: Map<IrDeclaration, String>,
): DotNetGenericOwnerPrototypeMethodSignatureSnapshot? {
    if (source.parameters.size != parameterSlotDomains.size) return null
    val parameterSlots = source.parameters.mapIndexed { index, parameter ->
        val physicalType = parameter.type.genericOwnerPrototypeType(
            owner = owner,
            preLoweringDeclarationKeys = preLoweringDeclarationKeys,
            use = DotNetGenericOwnerPrototypeTypeUse.CALLABLE,
        ) ?: return null
        val domain = parameterSlotDomains[index]
        if (physicalType.kind == DotNetGenericOwnerPrototypeTypeKind.VOID ||
                domain == DotNetGenericOwnerPhysicalSlotDomain.DECLARATION_INDEPENDENT &&
                physicalType.referencesOwnerParameter()
        ) return null
        DotNetGenericOwnerPrototypeValueSlotSnapshot(
            domain = domain,
            type = physicalType,
        )
    }
    return DotNetGenericOwnerPrototypeMethodSignatureSnapshot(
        isInstance = true,
        genericArity = 0,
        returnSlot = DotNetGenericOwnerPrototypeValueSlotSnapshot(
            domain = DotNetGenericOwnerPhysicalSlotDomain.DECLARATION_INDEPENDENT,
            type = DotNetGenericOwnerPrototypeTypeSnapshot.voidType(),
        ),
        parameterSlots = parameterSlots,
    )
}

private fun DotNetGenericOwnerMemberFamilyPlan.exactPrototypePathUnboundSignatures(
    owner: IrClass,
    preLoweringDeclarationKeys: Map<IrDeclaration, String>,
): Map<DotNetGenericOwnerMemberFamilyRole, DotNetGenericOwnerPrototypeMethodSignatureSnapshot>? {
    val explicitParameters = source.parameters.filter { parameter ->
        parameter.kind != IrParameterKind.DispatchReceiver
    }
    if (explicitParameters.size != parameterSlotDomains.size) return null
    return roles.associateWith { role ->
        val eraseOwnerDependentCarrier = role != DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY
        val returnType = source.returnType.genericOwnerPrototypeType(
            owner = owner,
            preLoweringDeclarationKeys = preLoweringDeclarationKeys,
            use = DotNetGenericOwnerPrototypeTypeUse.CALLABLE,
            method = source,
            eraseOwnerDependentCarrier = eraseOwnerDependentCarrier,
        ) ?: return null
        if (returnSlotDomain == DotNetGenericOwnerPhysicalSlotDomain.DECLARATION_INDEPENDENT &&
                returnType.referencesOwnerParameter()
        ) return null
        val parameterSlots = explicitParameters.mapIndexed { index, parameter ->
            val physicalType = parameter.type.genericOwnerPrototypeType(
                owner = owner,
                preLoweringDeclarationKeys = preLoweringDeclarationKeys,
                use = DotNetGenericOwnerPrototypeTypeUse.CALLABLE,
                method = source,
                eraseOwnerDependentCarrier = eraseOwnerDependentCarrier,
            ) ?: return null
            val domain = parameterSlotDomains[index]
            if (physicalType.kind == DotNetGenericOwnerPrototypeTypeKind.VOID ||
                    domain == DotNetGenericOwnerPhysicalSlotDomain.DECLARATION_INDEPENDENT &&
                    physicalType.referencesOwnerParameter()
            ) return null
            DotNetGenericOwnerPrototypeValueSlotSnapshot(
                domain = domain,
                type = physicalType,
            )
        }
        DotNetGenericOwnerPrototypeMethodSignatureSnapshot(
            isInstance = true,
            genericArity = source.typeParameters.size,
            returnSlot = DotNetGenericOwnerPrototypeValueSlotSnapshot(returnSlotDomain, returnType),
            parameterSlots = parameterSlots,
        )
    }
}

private fun DotNetGenericOwnerMemberFamilyPlan.exactPrototypeDefaultDispatcher(
    owner: IrClass,
    preLoweringDeclarationKeys: Map<IrDeclaration, String>,
): DotNetGenericOwnerPrototypeDefaultDispatcherSnapshot? {
    val dispatcher = maskedDefaultDispatcher ?: return null
    if (dispatcher.dispatchReceiverParameter != null || dispatcher.typeParameters.size != source.typeParameters.size) return null
    val sourceParameters = source.parameters.filter { parameter ->
        parameter.kind != IrParameterKind.DispatchReceiver
    }
    if (sourceParameters.size != parameterSlotDomains.size) return null
    val dispatcherParameters = dispatcher.parameters
    if (dispatcherParameters.size <= sourceParameters.size) return null
    val receiver = dispatcherParameters.first()
    val receiverClassifier = ((receiver.type as? IrSimpleType)?.classifier as? IrClassSymbol)?.owner
    if (receiverClassifier != owner) return null
    val sourceDispatcherParameters = dispatcherParameters.drop(1).take(sourceParameters.size)
    val maskParameters = dispatcherParameters.drop(1 + sourceParameters.size)
    if (maskParameters.isEmpty() || maskParameters.any { parameter ->
            parameter.origin != IrDeclarationOrigin.MASK_FOR_DEFAULT_FUNCTION
        }
    ) return null
    if (sourceDispatcherParameters.any { parameter ->
            parameter.origin == IrDeclarationOrigin.MASK_FOR_DEFAULT_FUNCTION
        }) return null

    val returnType = dispatcher.returnType.genericOwnerPrototypeType(
        owner = owner,
        preLoweringDeclarationKeys = preLoweringDeclarationKeys,
        use = DotNetGenericOwnerPrototypeTypeUse.CALLABLE,
        method = dispatcher,
    ) ?: return null
    if (returnSlotDomain == DotNetGenericOwnerPhysicalSlotDomain.DECLARATION_INDEPENDENT &&
            returnType.referencesOwnerParameter()
    ) return null
    val sourceSlots = sourceDispatcherParameters.mapIndexed { index, parameter ->
        val physicalType = parameter.type.genericOwnerPrototypeType(
            owner = owner,
            preLoweringDeclarationKeys = preLoweringDeclarationKeys,
            use = DotNetGenericOwnerPrototypeTypeUse.CALLABLE,
            method = dispatcher,
        ) ?: return null
        val domain = parameterSlotDomains[index]
        if (physicalType.kind == DotNetGenericOwnerPrototypeTypeKind.VOID ||
                domain == DotNetGenericOwnerPhysicalSlotDomain.DECLARATION_INDEPENDENT &&
                physicalType.referencesOwnerParameter()
        ) return null
        DotNetGenericOwnerPrototypeValueSlotSnapshot(
            domain = domain,
            type = physicalType,
        )
    }
    val maskSlots = maskParameters.map { parameter ->
        val physicalType = parameter.type.genericOwnerPrototypeType(
            owner = owner,
            preLoweringDeclarationKeys = preLoweringDeclarationKeys,
            use = DotNetGenericOwnerPrototypeTypeUse.CALLABLE,
            method = dispatcher,
        ) ?: return null
        if (physicalType.kind == DotNetGenericOwnerPrototypeTypeKind.VOID) return null
        DotNetGenericOwnerPrototypeValueSlotSnapshot(
            domain = DotNetGenericOwnerPhysicalSlotDomain.DECLARATION_INDEPENDENT,
            type = physicalType,
        )
    }
    return DotNetGenericOwnerPrototypeDefaultDispatcherSnapshot(
        genericArity = dispatcher.typeParameters.size,
        returnSlot = DotNetGenericOwnerPrototypeValueSlotSnapshot(returnSlotDomain, returnType),
        parameterSlotsAfterReceiver = sourceSlots + maskSlots,
    )
}

private fun IrConstructor.hasOnlyPrototypePhysicalizableBody(): Boolean =
    (body as? IrBlockBody)?.statements?.all { statement ->
        statement is IrDelegatingConstructorCall || statement is IrInstanceInitializerCall ||
                statement is IrContainerExpression && statement.statements.isEmpty()
    } == true

private fun IrClass.genericOwnerPrototypePhysicalGenericParameters():
        List<DotNetGenericOwnerPhysicalGenericParameterRecord>? = typeParameters.mapIndexed { index, parameter ->
    val nonTrivialBounds = parameter.superTypes.filterNot { bound -> bound.isAny() || bound.isNullableAny() }
    if (nonTrivialBounds.isNotEmpty()) return null
    DotNetGenericOwnerPhysicalGenericParameterRecord(
        index = index,
        specialConstraints = emptySet(),
        typeConstraints = emptyList(),
    )
}

internal fun DotNetGenericOwnerArchitecturePlan.toPrototypeSnapshot(
    preLoweringDeclarationKeys: Map<IrDeclaration, String>,
): DotNetGenericOwnerPrototypeSnapshot {
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
        physicalGenericParameters = owner.genericOwnerPrototypePhysicalGenericParameters(),
        physicalVisibility = if (owner.visibility == DescriptorVisibilities.PUBLIC) {
            DotNetGenericOwnerPhysicalTypeVisibility.PUBLIC
        } else {
            DotNetGenericOwnerPhysicalTypeVisibility.NOT_PUBLIC
        },
        physicalDispatch = when (owner.modality) {
            Modality.FINAL -> DotNetGenericOwnerPhysicalTypeDispatch.FINAL
            Modality.OPEN -> DotNetGenericOwnerPhysicalTypeDispatch.OVERRIDABLE
            Modality.ABSTRACT -> DotNetGenericOwnerPhysicalTypeDispatch.ABSTRACT
            Modality.SEALED -> DotNetGenericOwnerPhysicalTypeDispatch.SEALED
        },
        isInner = owner.isInner,
        directSupertypeCount = owner.superTypes.size,
        directFieldCount = owner.declarations.count { declaration -> declaration is IrField },
        anonymousInitializerCount = owner.declarations.count { declaration ->
            declaration is IrAnonymousInitializer
        },
        directNestedClassCount = owner.declarations.count { declaration -> declaration is IrClass },
        disposition = disposition,
        logicalBindingKey = logicalBindingKey,
        constructors = constructors.mapIndexed { sourceIndex, constructor ->
            DotNetGenericOwnerPrototypeConstructorSnapshot(
                sourceIndex = sourceIndex,
                logicalBindingKey = constructor.logicalBindingKey,
                physicalVisibility = constructor.source.genericOwnerPhysicalVisibility(),
                parameterSlotDomains = constructor.parameterSlotDomains,
                exactPathUnboundSignature = constructor.exactPrototypePathUnboundSignature(
                    owner,
                    preLoweringDeclarationKeys,
                ),
                delegationArgumentMapping = constructor.delegationArgumentMapping,
                hasOnlyDelegationAndInstanceInitializer = constructor.source.hasOnlyPrototypePhysicalizableBody(),
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
                physicalBaseName = source.dotNetIlMethodName(),
                sourceIndex = sourceIndex,
                isFakeOverride = source.isFakeOverride,
                isAbstract = source.modality == Modality.ABSTRACT,
                isOverridable = source.modality != Modality.FINAL,
                physicalVisibility = source.genericOwnerPhysicalVisibility(),
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
                exactPathUnboundSignatures = family.exactPrototypePathUnboundSignatures(
                    owner,
                    preLoweringDeclarationKeys,
                ),
                requiresDirectSuperTargets = family.requiresDirectSuperTargets,
                directSuperCallCount = family.directSuperCallCount,
                directSuperCalls = family.directSuperCalls.map { call ->
                    val logicalTarget = if (call.target.isFakeOverride) {
                        call.target.resolveFakeOverride()
                            ?: call.target.resolveFakeOverrideMaybeAbstract()
                            ?: error("generic-owner direct-super fake override has no declaring Kotlin root")
                    } else {
                        call.target
                    }
                    val logicalOwner = logicalTarget.parent as IrClass
                    DotNetGenericOwnerDirectSuperCallSnapshot(
                        logicalMemberKey = logicalTarget.dotNetLibraryAbiKeyOrNull("F"),
                        logicalOwnerName = logicalOwner.fqNameWhenAvailable?.asString()
                            ?: logicalOwner.name.asString(),
                        superQualifierName = call.superQualifier.fqNameWhenAvailable?.asString()
                            ?: call.superQualifier.name.asString(),
                    )
                },
                hasMaskedDefaultDispatcher = family.maskedDefaultDispatcher != null,
                exactMaskedDefaultDispatcher = family.exactPrototypeDefaultDispatcher(
                    owner,
                    preLoweringDeclarationKeys,
                ),
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
                exactTypedCarrierType = state.field.type.genericOwnerPrototypeStateType(
                    owner,
                    preLoweringDeclarationKeys,
                )
                    ?.takeIf(DotNetGenericOwnerPrototypeTypeSnapshot::referencesOwnerParameter),
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

internal fun DotNetGenericOwnerCallRoutePlan.toCallRouteSnapshot(): DotNetGenericOwnerCallRouteSnapshot =
    DotNetGenericOwnerCallRouteSnapshot(
        callerName = callerName,
        callerLogicalBindingKey = callerLogicalBindingKey,
        callSiteIndex = callSiteIndex,
        calleeOwnerName = calleeOwner.fqNameWhenAvailable?.asString() ?: calleeOwner.name.asString(),
        calleeName = callee.name.asString(),
        calleeLogicalBindingKey = calleeLogicalBindingKey,
        receiverProvenance = receiverProvenance,
        routeRequirement = routeRequirement,
    )

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
