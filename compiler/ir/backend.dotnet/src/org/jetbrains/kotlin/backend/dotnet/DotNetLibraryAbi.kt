/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.backend.common.suspendFunction
import org.jetbrains.kotlin.backend.dotnet.serialization.DotNetIrMangler
import org.jetbrains.kotlin.backend.common.serialization.signature.PublicIdSignatureComputer
import org.jetbrains.kotlin.backend.dotnet.lower.DOTNET_STATIC_HOLDER
import org.jetbrains.kotlin.backend.dotnet.lower.DOTNET_DEFAULT_IMPLS
import org.jetbrains.kotlin.backend.dotnet.lower.DOTNET_VALUE_CLASS_BOX_HELPER
import org.jetbrains.kotlin.backend.dotnet.lower.DOTNET_VALUE_CLASS_UNBOX_HELPER
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetValueClassBoxingHelpers
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.ValueClassBackendAgnosticApi
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithVisibility
import org.jetbrains.kotlin.ir.declarations.IrEnumEntry
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.isInlineClass
import org.jetbrains.kotlin.ir.declarations.isStaticMethodOfClass
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrStarProjection
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.util.IdSignatureRenderer
import org.jetbrains.kotlin.ir.util.allOverridden
import org.jetbrains.kotlin.ir.util.fileOrNull
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.isAnnotationClass
import org.jetbrains.kotlin.ir.util.isFakeOverride
import org.jetbrains.kotlin.ir.util.isInterface
import org.jetbrains.kotlin.ir.util.isOriginallyLocalDeclaration
import org.jetbrains.kotlin.ir.util.render
import org.jetbrains.kotlin.types.Variance
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.IdentityHashMap
import java.util.Properties

/** Physical placement of one Kotlin-owned interface member's default implementation. */
enum class DotNetInterfaceDefaultBodyPlacement {
    HELPER_ONLY,
    DIM_WITH_HELPER,
}

/** Compiler-ABI helper binding associated with one logical Kotlin interface member. */
data class DotNetInterfaceDefaultImplementation(
    val bodyPlacement: DotNetInterfaceDefaultBodyPlacement,
    val helperOwnerPath: List<String>,
    val helperMethodName: String,
)

/** Physical compiler-ABI binding for a Kotlin default-argument mask dispatcher. */
data class DotNetDefaultArgumentDispatcher(
    val ownerPath: List<String>,
    val methodName: String,
)

/** Stable compiler-ABI entry which enters one classifier's Kotlin initialization event. */
data class DotNetStaticInitialization(
    val ownerPath: List<String>,
    val methodName: String,
) {
    init {
        require(ownerPath.isNotEmpty()) { "a static-initialization entry requires a CLR owner" }
        require(methodName.isNotEmpty()) { "a static-initialization entry requires a CLR method name" }
    }
}

/** Stable physical field carrying the single instance of one Kotlin object declaration. */
data class DotNetObjectInstance(
    val ownerPath: List<String>,
    val fieldName: String,
) {
    init {
        require(ownerPath.isNotEmpty()) { "an object-instance field requires a CLR owner" }
        require(fieldName.isNotEmpty()) { "an object-instance field requires a CLR field name" }
    }
}

/** Producer-owned compiler ABI for constructing and crossing one value class's two representations. */
data class DotNetValueClassAbi(
    val primaryConstructorMethodName: String,
    val boxMethodName: String,
    val unboxMethodName: String,
) {
    init {
        require(primaryConstructorMethodName.isNotEmpty()) {
            "a value-class primary constructor implementation requires a CLR method name"
        }
        require(boxMethodName.isNotEmpty()) { "a value-class box helper requires a CLR method name" }
        require(unboxMethodName.isNotEmpty()) { "a value-class unbox helper requires a CLR method name" }
    }
}

/** Producer-owned non-generic classifier identity implemented by every constructed `C<T>`. */
data class DotNetGenericOwnerAbi(
    val capabilityAssemblyName: String,
    val capabilityOwnerPath: List<String>,
) {
    init {
        require(CLR_ASSEMBLY_NAME.matches(capabilityAssemblyName)) {
            "'$capabilityAssemblyName' is not a supported CLR-generic owner capability assembly name"
        }
        require(capabilityOwnerPath.isNotEmpty()) {
            "a CLR-generic Kotlin owner requires a semantic capability TypeDef"
        }
    }

    private companion object {
        val CLR_ASSEMBLY_NAME = Regex("[A-Za-z_][A-Za-z0-9_.-]*")
    }
}

/** Physical carrier selected for one generic-owner slot in an otherwise ordinary function. */
enum class DotNetGenericOwnerFunctionCarrierKind {
    SEMANTIC_CAPABILITY,
    OBJECT,
}

enum class DotNetInterfaceDefaultPromotionView {
    CANONICAL,
    DECLARED,
    EXACT,
}

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
     * One logical Kotlin classifier and its single physical CLR runtime owner.
     *
     * KLIB retains the logical type graph and Kotlin variance. The physical owner is either the
     * accepted arity-zero epoch or one exact full-arity CLR TypeDef; it is never reconstructed
     * from the metadata-name backtick suffix and never has an alternate implementation path.
     */
    data class Class(
        override val ownerPath: List<String>,
        /** Exact GenericParam arity of this physical TypeDef; zero is the erased owner epoch. */
        val physicalTypeParameterCount: Int = 0,
        val staticInitialization: DotNetStaticInitialization? = null,
        val objectInstance: DotNetObjectInstance? = null,
        val valueClassAbi: DotNetValueClassAbi? = null,
        val genericOwnerAbi: DotNetGenericOwnerAbi? = null,
    ) : DotNetPhysicalDeclaration {
        init {
            require(physicalTypeParameterCount >= 0) {
                "a physical CLR class cannot have negative generic arity"
            }
            require(genericOwnerAbi == null || physicalTypeParameterCount > 0) {
                "an erased or non-generic physical class cannot publish a generic-owner capability"
            }
        }
    }

    data class Function(
        override val ownerPath: List<String>,
        val methodName: String,
        val isInstance: Boolean,
        val interfaceDefaultImplementation: DotNetInterfaceDefaultImplementation? = null,
        val defaultArgumentDispatcher: DotNetDefaultArgumentDispatcher? = null,
    ) : DotNetPhysicalDeclaration {
        init {
            interfaceDefaultImplementation?.let { implementation ->
                require(implementation.helperOwnerPath.isNotEmpty()) {
                    "an interface-default helper requires a CLR owner"
                }
                require(implementation.helperMethodName.isNotEmpty()) {
                    "an interface-default helper requires a CLR method name"
                }
            }
            defaultArgumentDispatcher?.let { dispatcher ->
                require(dispatcher.ownerPath.isNotEmpty()) {
                    "a default-argument dispatcher requires a CLR owner"
                }
                require(dispatcher.methodName.isNotEmpty()) {
                    "a default-argument dispatcher requires a CLR method name"
                }
            }
        }
    }

    /** Producer-selected non-natural carriers in one otherwise ordinary function ABI. */
    data class GenericOwnerFunctionCarrier(
        override val ownerPath: List<String>,
        val logicalFunctionKey: String,
        val returnCarrier: DotNetGenericOwnerFunctionCarrierKind?,
        val parameterCarriers: Map<Int, DotNetGenericOwnerFunctionCarrierKind>,
    ) : DotNetPhysicalDeclaration {
        init {
            require(ownerPath.isNotEmpty()) {
                "a generic-owner function carrier requires a CLR owner"
            }
            require(logicalFunctionKey.isNotEmpty()) {
                "a generic-owner function carrier requires a logical function identity"
            }
            require(returnCarrier != null || parameterCarriers.isNotEmpty()) {
                "a generic-owner function carrier must select at least one signature slot"
            }
            require(parameterCarriers.keys.all { index -> index >= 0 }) {
                "generic-owner function carrier parameter indices must be non-negative"
            }
        }
    }

    /** Alternate compiler ABI for a classifier-derived object input of a natural function. */
    data class GenericOwnerFunctionInputEntry(
        override val ownerPath: List<String>,
        val logicalFunctionKey: String,
        val methodName: String,
        val isInstance: Boolean,
        val objectParameterIndices: Set<Int>,
    ) : DotNetPhysicalDeclaration {
        init {
            require(ownerPath.isNotEmpty()) {
                "a generic-owner function input entry requires a CLR owner"
            }
            require(logicalFunctionKey.isNotEmpty()) {
                "a generic-owner function input entry requires a logical function identity"
            }
            require(methodName.isNotEmpty()) {
                "a generic-owner function input entry requires a CLR MethodDef name"
            }
            require(objectParameterIndices.isNotEmpty() && objectParameterIndices.all { index -> index >= 0 }) {
                "a generic-owner function input entry requires non-negative object parameter indices"
            }
        }
    }

    /** The public static singleton field carrying one logical Kotlin enum entry. */
    data class EnumEntry(
        override val ownerPath: List<String>,
        val fieldName: String,
    ) : DotNetPhysicalDeclaration {
        init {
            require(ownerPath.isNotEmpty()) { "an enum-entry field requires a CLR owner" }
            require(fieldName.isNotEmpty()) { "an enum-entry field requires a CLR field name" }
        }
    }

    /** A DIM supplied by a derived net10 interface for an inherited helper-only default. */
    data class InterfaceDefaultPromotion(
        override val ownerPath: List<String>,
        val ownerLogicalKey: String,
        val inheritedLogicalMemberKey: String,
        val physicalView: DotNetInterfaceDefaultPromotionView,
        val inheritedAssemblyName: String,
        val inheritedOwnerPath: List<String>,
        val inheritedMethodName: String,
        val implementationMethodName: String,
    ) : DotNetPhysicalDeclaration {
        init {
            require(ownerPath.isNotEmpty()) { "an interface-default promotion requires an owning CLR interface" }
            require(ownerLogicalKey.isNotEmpty()) { "an interface-default promotion requires an owning logical interface" }
            require(inheritedLogicalMemberKey.isNotEmpty()) {
                "an interface-default promotion requires an inherited logical member"
            }
            require(inheritedAssemblyName.isNotEmpty()) {
                "an interface-default promotion requires an inherited CLR assembly"
            }
            require(inheritedOwnerPath.isNotEmpty()) {
                "an interface-default promotion requires an inherited CLR slot owner"
            }
            require(inheritedMethodName.isNotEmpty() && implementationMethodName.isNotEmpty()) {
                "an interface-default promotion requires inherited and implementing CLR method names"
            }
        }
    }

    /** A final interface-slot MethodImpl adapting one inherited split-generic physical view. */
    data class GenericInterfaceViewBridge(
        override val ownerPath: List<String>,
        val ownerLogicalKey: String,
        val inheritedLogicalMemberKey: String,
        val physicalView: DotNetInterfaceDefaultPromotionView,
        val implementationMethodName: String,
    ) : DotNetPhysicalDeclaration {
        init {
            require(ownerPath.isNotEmpty()) { "a generic-interface view bridge requires an owning CLR type" }
            require(ownerLogicalKey.isNotEmpty()) {
                "a generic-interface view bridge requires an owning logical declaration"
            }
            require(inheritedLogicalMemberKey.isNotEmpty()) {
                "a generic-interface view bridge requires an inherited logical member"
            }
            require(implementationMethodName.isNotEmpty()) {
                "a generic-interface view bridge requires a CLR implementation method name"
            }
        }
    }

    /** One derived typed slot representing a Kotlin intersection of inherited generic members. */
    data class GenericInterfaceIntersectionSlot(
        override val ownerPath: List<String>,
        val ownerLogicalKey: String,
        val contributingLogicalMemberKeys: List<String>,
        val physicalView: DotNetInterfaceDefaultPromotionView,
        val methodName: String,
    ) : DotNetPhysicalDeclaration {
        init {
            require(ownerPath.isNotEmpty()) {
                "a generic-interface intersection slot requires an owning CLR interface"
            }
            require(ownerLogicalKey.isNotEmpty()) {
                "a generic-interface intersection slot requires an owning logical interface"
            }
            require(contributingLogicalMemberKeys.size >= 2 &&
                    contributingLogicalMemberKeys.all(String::isNotEmpty) &&
                    contributingLogicalMemberKeys == contributingLogicalMemberKeys.distinct().sorted()
            ) {
                "a generic-interface intersection slot requires at least two sorted unique logical members"
            }
            require(physicalView != DotNetInterfaceDefaultPromotionView.CANONICAL) {
                "a generic-interface intersection slot requires a typed CLR capability"
            }
            require(methodName.isNotEmpty()) {
                "a generic-interface intersection slot requires a CLR method name"
            }
        }
    }

    /** A final MethodImpl adapting one ordinary Kotlin slot to a wider CLR return. */
    data class CovariantReturnBridge(
        override val ownerPath: List<String>,
        val ownerLogicalKey: String,
        val inheritedLogicalMemberKey: String,
        val implementationMethodName: String,
    ) : DotNetPhysicalDeclaration {
        init {
            require(ownerPath.isNotEmpty()) { "a covariant-return bridge requires an owning CLR type" }
            require(ownerLogicalKey.isNotEmpty()) {
                "a covariant-return bridge requires an owning logical type"
            }
            require(inheritedLogicalMemberKey.isNotEmpty()) {
                "a covariant-return bridge requires an inherited logical member"
            }
            require(implementationMethodName.isNotEmpty()) {
                "a covariant-return bridge requires a CLR implementation method name"
            }
        }
    }

    /** A hidden class MethodImpl emitted to make one helper-backed Kotlin default effective. */
    data class InterfaceDefaultClassForwarder(
        override val ownerPath: List<String>,
        val ownerLogicalKey: String,
        val inheritedLogicalMemberKey: String,
        val physicalView: DotNetInterfaceDefaultPromotionView,
        val implementationMethodName: String,
    ) : DotNetPhysicalDeclaration {
        init {
            require(ownerPath.isNotEmpty()) { "an interface-default class forwarder requires an owning CLR class" }
            require(ownerLogicalKey.isNotEmpty()) {
                "an interface-default class forwarder requires an owning logical class"
            }
            require(inheritedLogicalMemberKey.isNotEmpty()) {
                "an interface-default class forwarder requires an inherited logical member"
            }
            require(implementationMethodName.isNotEmpty()) {
                "an interface-default class forwarder requires a CLR implementation method name"
            }
        }
    }

    /** Producer-recorded non-generic capability slot and optional semantic hook for one `C<T>` member. */
    data class GenericOwnerMemberFamily(
        override val ownerPath: List<String>,
        val ownerLogicalKey: String,
        val logicalMemberKey: String,
        val capabilityMethodName: String,
        val defaultCapabilityMethodName: String?,
        val semanticHookOwnerPath: List<String>?,
        val semanticHookMethodName: String?,
        val foreignOverrideProbeMethodName: String?,
    ) : DotNetPhysicalDeclaration {
        init {
            require(ownerPath.isNotEmpty()) { "a generic-owner member capability requires a CLR owner" }
            require(ownerLogicalKey.isNotEmpty() && logicalMemberKey.isNotEmpty()) {
                "a generic-owner member family requires owner and member logical identities"
            }
            require(capabilityMethodName.isNotEmpty()) {
                "a generic-owner member family requires a capability MethodDef"
            }
            require(defaultCapabilityMethodName == null || defaultCapabilityMethodName.isNotEmpty()) {
                "a generic-owner default capability requires a MethodDef name"
            }
            require((semanticHookOwnerPath == null) == (semanticHookMethodName == null)) {
                "a generic-owner semantic hook requires both owner and MethodDef identities"
            }
            require(semanticHookOwnerPath == null || semanticHookOwnerPath.isNotEmpty()) {
                "a generic-owner semantic hook requires a CLR owner"
            }
            require(semanticHookMethodName == null || semanticHookMethodName.isNotEmpty()) {
                "a generic-owner semantic hook requires a MethodDef name"
            }
            require(foreignOverrideProbeMethodName == null || semanticHookMethodName != null) {
                "a generic-owner foreign-override probe requires a semantic hook"
            }
            require(foreignOverrideProbeMethodName == null || foreignOverrideProbeMethodName.isNotEmpty()) {
                "a generic-owner foreign-override probe requires a MethodDef name"
            }
        }
    }

}

internal fun DotNetPhysicalDeclaration.InterfaceDefaultPromotion.indexKey(): String =
    "P:$ownerLogicalKey:$inheritedLogicalMemberKey:${physicalView.name}"

internal fun DotNetPhysicalDeclaration.GenericInterfaceViewBridge.indexKey(): String =
    "B:$ownerLogicalKey:$inheritedLogicalMemberKey:${physicalView.name}"

internal fun DotNetPhysicalDeclaration.GenericInterfaceIntersectionSlot.indexKey(): String =
    "I:$ownerLogicalKey:${physicalView.name}:" +
            DotNetLibraryAbiCodec.logicalIdentityDigest(contributingLogicalMemberKeys.joinToString("\u0000"))

internal fun DotNetPhysicalDeclaration.CovariantReturnBridge.indexKey(): String =
    "R:$ownerLogicalKey:$inheritedLogicalMemberKey"

internal fun DotNetPhysicalDeclaration.InterfaceDefaultClassForwarder.indexKey(): String =
    "W:$ownerLogicalKey:$inheritedLogicalMemberKey:${physicalView.name}"

internal fun DotNetPhysicalDeclaration.GenericOwnerMemberFamily.indexKey(): String =
    "G:$logicalMemberKey"

internal fun DotNetPhysicalDeclaration.GenericOwnerFunctionCarrier.indexKey(): String =
    "S:$logicalFunctionKey"

internal fun DotNetPhysicalDeclaration.GenericOwnerFunctionInputEntry.indexKey(): String =
    "Q:$logicalFunctionKey"

/** One portable Kotlin/CLR binding that is absent or physically different in a platform variant. */
data class DotNetPortablePhysicalAbiDifference(
    val logicalKey: String,
    val portableDeclaration: DotNetPhysicalDeclaration,
    val platformDeclaration: DotNetPhysicalDeclaration?,
)

/** One self-describing Kotlin/.NET assembly and its decoded declaration index. */
data class DotNetExternalLibrary(
    val artifact: DotNetLibraryArtifact,
    val assemblyFile: File,
    val declarations: Map<String, DotNetPhysicalDeclaration>,
    val friendAssemblies: Set<DotNetFriendAssemblyIdentity>,
)

/** One producer-authorized CLR friend identity used by InternalsVisibleTo. */
data class DotNetFriendAssemblyIdentity(
    val assemblyName: String,
    val publicKey: String? = null,
) {
    init {
        require(ASSEMBLY_NAME.matches(assemblyName)) {
            "'$assemblyName' is not a supported CLR friend assembly name"
        }
        require(publicKey == null || publicKey.length > PUBLIC_KEY_TOKEN_HEX_LENGTH && publicKey.matches(PUBLIC_KEY)) {
            "friend assembly '$assemblyName' has an invalid full public key"
        }
    }

    val displayName: String
        get() = if (publicKey == null) assemblyName else "$assemblyName, PublicKey=$publicKey"

    fun authorizes(assemblyName: String, publicKey: String? = null): Boolean =
        this.assemblyName.equals(assemblyName, ignoreCase = true) &&
                this.publicKey.equals(publicKey, ignoreCase = true)

    companion object {
        private val ASSEMBLY_NAME = Regex("[A-Za-z_][A-Za-z0-9_.-]*")
        private val PUBLIC_KEY = Regex("(?:[0-9A-F]{2})+")
        private const val PUBLIC_KEY_TOKEN_HEX_LENGTH = 16

        fun parse(value: String): DotNetFriendAssemblyIdentity {
            val components = value.split(',').map(String::trim)
            require(components.size in 1..2 && components[0].isNotEmpty()) {
                "expected '<assembly-name>' or '<assembly-name>, PublicKey=<full-hex-public-key>'"
            }
            val publicKey = components.getOrNull(1)?.let { component ->
                val separator = component.indexOf('=')
                require(separator > 0 && component.substring(0, separator).trim().equals("PublicKey", ignoreCase = true)) {
                    "expected the strong-name component 'PublicKey=<full-hex-public-key>'"
                }
                component.substring(separator + 1).filterNot(Char::isWhitespace).uppercase()
            }
            return DotNetFriendAssemblyIdentity(components[0], publicKey)
        }
    }
}

/** Manifest codec for the provisional declaration-index schema. */
object DotNetLibraryAbiCodec {
    const val ABI_VERSION = "40"
    const val ABI_VERSION_PROPERTY = "dotnet_abi_version"
    const val LOGICAL_IDENTITY_SCHEME = "kotlin-public-id-signature-legacy-v1"
    const val LOGICAL_IDENTITY_SCHEME_PROPERTY = "dotnet_logical_identity_scheme"
    const val PHYSICAL_NAME_GRAMMAR_VERSION = "3"
    const val PHYSICAL_NAME_GRAMMAR_VERSION_PROPERTY = "dotnet_physical_name_grammar_version"
    const val CURRENT_RUNTIME_SURFACE_LEVEL = 38
    const val RUNTIME_SURFACE_LEVEL_PROPERTY = "dotnet_runtime_surface_level"
    const val RUNTIME_SURFACE_METADATA_KEY = "Kotlin.RuntimeSurfaceLevel"
    const val IMPLEMENTATION_SHA256_PROPERTY = "dotnet_implementation_sha256"
    const val FRIEND_ASSEMBLIES_PROPERTY = "dotnet_friend_assembly_identities"
    const val DECLARATION_PROPERTY_PREFIX = "dotnet_decl_"

    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    /** Version-1 digest algorithm used when a physical name is derived from logical identity. */
    fun logicalIdentityDigest(logicalIdentity: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(logicalIdentity.toByteArray(Charsets.UTF_8))
            .take(16)
            .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

    fun encodeFriendAssemblies(identities: Collection<DotNetFriendAssemblyIdentity>): String =
        identities.sortedBy { it.displayName.lowercase() }
            .joinToString(",") { encodeText(it.displayName) }

    fun decodeFriendAssemblies(value: String): Set<DotNetFriendAssemblyIdentity> = buildSet {
        if (value.isEmpty()) return@buildSet
        for (encodedIdentity in value.split(',')) {
            require(encodedIdentity.isNotEmpty()) { "friend-assembly identity list contains an empty entry" }
            val identity = DotNetFriendAssemblyIdentity.parse(decodeText(encodedIdentity))
            require(none { existing -> existing.displayName.equals(identity.displayName, ignoreCase = true) }) {
                "duplicate friend-assembly identity '${identity.displayName}'"
            }
            add(identity)
        }
    }

    fun encode(declarations: Map<String, DotNetPhysicalDeclaration>): Map<String, String> =
        declarations.toSortedMap().mapKeys { entry ->
            DECLARATION_PROPERTY_PREFIX + encodeText(entry.key)
        }.mapValues { entry ->
            val declaration = entry.value
            val fields = when (declaration) {
                is DotNetPhysicalDeclaration.Class -> declaration.encodeFields()
                is DotNetPhysicalDeclaration.Function -> declaration.encodeFields()
                is DotNetPhysicalDeclaration.GenericOwnerFunctionCarrier -> declaration.encodeFields()
                is DotNetPhysicalDeclaration.GenericOwnerFunctionInputEntry -> declaration.encodeFields()
                is DotNetPhysicalDeclaration.EnumEntry -> declaration.encodeFields()
                is DotNetPhysicalDeclaration.InterfaceDefaultPromotion -> declaration.encodeFields()
                is DotNetPhysicalDeclaration.GenericInterfaceViewBridge -> declaration.encodeFields()
                is DotNetPhysicalDeclaration.GenericInterfaceIntersectionSlot ->
                    error("Typed generic-interface intersection slots were removed in ABI 19")
                is DotNetPhysicalDeclaration.CovariantReturnBridge -> declaration.encodeFields()
                is DotNetPhysicalDeclaration.InterfaceDefaultClassForwarder -> declaration.encodeFields()
                is DotNetPhysicalDeclaration.GenericOwnerMemberFamily -> declaration.encodeFields()
            }
            encodeText(fields.joinToString("\u0000"))
        }

    fun decode(properties: Properties): Map<String, DotNetPhysicalDeclaration> = buildMap {
        for (propertyName in properties.stringPropertyNames().sorted()) {
            if (!propertyName.startsWith(DECLARATION_PROPERTY_PREFIX)) continue
            val logicalKey = decodeText(propertyName.removePrefix(DECLARATION_PROPERTY_PREFIX))
            val fields = decodeText(properties.getProperty(propertyName)).split('\u0000')
            val declaration = when (fields.firstOrNull()) {
                "C" -> decodeClass(fields, logicalKey)
                "F" -> decodeFunction(fields, logicalKey)
                "S" -> decodeGenericOwnerFunctionCarrier(fields, logicalKey)
                "Q" -> decodeGenericOwnerFunctionInputEntry(fields, logicalKey)
                "E" -> decodeEnumEntry(fields, logicalKey)
                "FD" -> decodeInterfaceDefaultFunction(fields, logicalKey)
                "P" -> decodeInterfaceDefaultPromotion(fields, logicalKey)
                "B" -> decodeGenericInterfaceViewBridge(fields, logicalKey)
                "I" -> throw IllegalArgumentException(
                    "declaration '$logicalKey' uses a removed typed generic-interface intersection slot"
                )
                "R" -> decodeCovariantReturnBridge(fields, logicalKey)
                "W" -> decodeInterfaceDefaultClassForwarder(fields, logicalKey)
                "G" -> decodeGenericOwnerMemberFamily(fields, logicalKey)
                "FA" -> decodeDefaultArgumentFunction(fields, logicalKey)
                "FDA" -> decodeInterfaceDefaultArgumentFunction(fields, logicalKey)
                else -> throw IllegalArgumentException("declaration '$logicalKey' has an unknown CLR identity kind")
            }
            require(put(logicalKey, declaration) == null) { "duplicate CLR declaration identity '$logicalKey'" }
        }
    }

    /**
     * Compares the assembly-independent physical declaration indexes of a portable library and a
     * runtime-profile variant. A platform variant may add declarations, but every portable source
     * declaration key must retain the same CLR owner/member binding. Hidden class-forwarder and
     * covariant-bridge records are profile-specific dispatch facts: they may move or disappear
     * when a platform DIM replaces portable class machinery and are therefore not portable
     * callable-superset requirements. The sorted result is suitable for build diagnostics and
     * deterministic tests.
     */
    fun portablePhysicalAbiDifferences(
        portableDeclarations: Map<String, DotNetPhysicalDeclaration>,
        platformDeclarations: Map<String, DotNetPhysicalDeclaration>,
    ): List<DotNetPortablePhysicalAbiDifference> = portableDeclarations.toSortedMap().mapNotNull { entry ->
        if (entry.value is DotNetPhysicalDeclaration.InterfaceDefaultClassForwarder ||
            entry.value is DotNetPhysicalDeclaration.CovariantReturnBridge
        ) {
            return@mapNotNull null
        }
        val platformDeclaration = platformDeclarations[entry.key]
        if (platformDeclaration != null && entry.value.isPortablePhysicalAbiCompatibleWith(platformDeclaration)) {
            null
        } else {
            DotNetPortablePhysicalAbiDifference(entry.key, entry.value, platformDeclaration)
        }
    }

    private fun DotNetPhysicalDeclaration.isPortablePhysicalAbiCompatibleWith(
        platformDeclaration: DotNetPhysicalDeclaration,
    ): Boolean {
        if (this !is DotNetPhysicalDeclaration.Function || platformDeclaration !is DotNetPhysicalDeclaration.Function) {
            return this == platformDeclaration
        }
        if (
            ownerPath != platformDeclaration.ownerPath ||
            methodName != platformDeclaration.methodName ||
            isInstance != platformDeclaration.isInstance
        ) {
            return false
        }
        val portableDefault = interfaceDefaultImplementation
        val platformDefault = platformDeclaration.interfaceDefaultImplementation
        if (defaultArgumentDispatcher != platformDeclaration.defaultArgumentDispatcher) {
            return false
        }
        if (portableDefault == null || platformDefault == null) return portableDefault == platformDefault
        if (
            portableDefault.helperOwnerPath != platformDefault.helperOwnerPath ||
            portableDefault.helperMethodName != platformDefault.helperMethodName
        ) {
            return false
        }
        return portableDefault.bodyPlacement == DotNetInterfaceDefaultBodyPlacement.HELPER_ONLY ||
                platformDefault.bodyPlacement == DotNetInterfaceDefaultBodyPlacement.DIM_WITH_HELPER
    }

    private fun DotNetPhysicalDeclaration.Function.encodeFields(): List<String> {
        val dispatch = if (isInstance) "1" else "0"
        val implementation = interfaceDefaultImplementation
        val dispatcher = defaultArgumentDispatcher
        if (implementation == null && dispatcher == null) {
            return listOf("F", dispatch, methodName) + ownerPath
        }
        if (implementation == null) {
            checkNotNull(dispatcher)
            return listOf(
                "FA",
                dispatch,
                methodName,
                ownerPath.size.toString(),
                dispatcher.methodName,
            ) + ownerPath + dispatcher.ownerPath
        }
        val placement = when (implementation.bodyPlacement) {
            DotNetInterfaceDefaultBodyPlacement.HELPER_ONLY -> "H"
            DotNetInterfaceDefaultBodyPlacement.DIM_WITH_HELPER -> "D"
        }
        if (dispatcher == null) return listOf(
            "FD",
            dispatch,
            methodName,
            placement,
            ownerPath.size.toString(),
            implementation.helperMethodName,
        ) + ownerPath + implementation.helperOwnerPath
        return listOf(
            "FDA",
            dispatch,
            methodName,
            placement,
            ownerPath.size.toString(),
            implementation.helperOwnerPath.size.toString(),
            implementation.helperMethodName,
            dispatcher.methodName,
        ) + ownerPath + implementation.helperOwnerPath + dispatcher.ownerPath
    }

    private fun decodeFunction(fields: List<String>, logicalKey: String): DotNetPhysicalDeclaration.Function {
        require(fields.size >= 4) { "function declaration '$logicalKey' has an incomplete CLR identity" }
        return DotNetPhysicalDeclaration.Function(
            ownerPath = fields.drop(3).requireOwnerPath(logicalKey),
            methodName = fields[2].requireMethodName(logicalKey),
            isInstance = fields[1].decodeDispatch(logicalKey),
        )
    }

    private fun DotNetPhysicalDeclaration.GenericOwnerFunctionCarrier.encodeFields(): List<String> =
        listOf(
            "S",
            logicalFunctionKey,
            returnCarrier.encodeCarrierKind(),
            parameterCarriers.toSortedMap().entries.joinToString(",") { entry ->
                "${entry.key}:${entry.value.encodeCarrierKind()}"
            },
            ownerPath.size.toString(),
        ) + ownerPath

    private fun DotNetPhysicalDeclaration.GenericOwnerFunctionInputEntry.encodeFields(): List<String> =
        listOf(
            "Q",
            logicalFunctionKey,
            if (isInstance) "1" else "0",
            methodName,
            objectParameterIndices.sorted().joinToString(","),
            ownerPath.size.toString(),
        ) + ownerPath

    private fun DotNetGenericOwnerFunctionCarrierKind?.encodeCarrierKind(): String = when (this) {
        null -> "N"
        DotNetGenericOwnerFunctionCarrierKind.SEMANTIC_CAPABILITY -> "C"
        DotNetGenericOwnerFunctionCarrierKind.OBJECT -> "O"
    }

    private fun String.decodeCarrierKind(
        logicalKey: String,
        slotDescription: String,
        allowsNatural: Boolean,
    ): DotNetGenericOwnerFunctionCarrierKind? = when (this) {
        "N" -> {
            require(allowsNatural) {
                "generic-owner function carrier '$logicalKey' has a natural $slotDescription"
            }
            null
        }
        "C" -> DotNetGenericOwnerFunctionCarrierKind.SEMANTIC_CAPABILITY
        "O" -> DotNetGenericOwnerFunctionCarrierKind.OBJECT
        else -> throw IllegalArgumentException(
            "generic-owner function carrier '$logicalKey' has invalid $slotDescription carrier '$this'"
        )
    }

    private fun decodeGenericOwnerFunctionCarrier(
        fields: List<String>,
        logicalKey: String,
    ): DotNetPhysicalDeclaration.GenericOwnerFunctionCarrier {
        require(fields.size >= 6) {
            "generic-owner function carrier '$logicalKey' has an incomplete CLR identity"
        }
        val ownerSize = fields[4].toIntOrNull()
        require(ownerSize != null && ownerSize > 0 && fields.size == 5 + ownerSize) {
            "generic-owner function carrier '$logicalKey' has an invalid CLR owner-path payload"
        }
        val returnCarrier = fields[2].decodeCarrierKind(logicalKey, "return", allowsNatural = true)
        val parameterCarriers = buildMap {
            if (fields[3].isNotEmpty()) fields[3].split(',').forEach { encodedEntry ->
                val components = encodedEntry.split(':')
                require(components.size == 2) {
                    "generic-owner function carrier '$logicalKey' has invalid parameter entry '$encodedEntry'"
                }
                val index = components[0].toIntOrNull()
                    ?: throw IllegalArgumentException(
                        "generic-owner function carrier '$logicalKey' has invalid parameter index " +
                                "'${components[0]}'"
                    )
                val carrier = components[1].decodeCarrierKind(
                    logicalKey,
                    "parameter $index",
                    allowsNatural = false,
                )
                require(put(index, checkNotNull(carrier)) == null) {
                    "generic-owner function carrier '$logicalKey' repeats parameter index $index"
                }
            }
        }
        return DotNetPhysicalDeclaration.GenericOwnerFunctionCarrier(
            ownerPath = fields.drop(5).requireOwnerPath(logicalKey, "generic-owner function carrier"),
            logicalFunctionKey = fields[1],
            returnCarrier = returnCarrier,
            parameterCarriers = parameterCarriers,
        ).also { carrier ->
            require(carrier.indexKey() == logicalKey) {
                "generic-owner function carrier '$logicalKey' is inconsistent with its structured identity"
            }
        }
    }

    private fun decodeGenericOwnerFunctionInputEntry(
        fields: List<String>,
        logicalKey: String,
    ): DotNetPhysicalDeclaration.GenericOwnerFunctionInputEntry {
        require(fields.size >= 7) {
            "generic-owner function input entry '$logicalKey' has an incomplete CLR identity"
        }
        val ownerSize = fields[5].toIntOrNull()
        require(ownerSize != null && ownerSize > 0 && fields.size == 6 + ownerSize) {
            "generic-owner function input entry '$logicalKey' has an invalid CLR owner-path payload"
        }
        val parameterIndices = fields[4].split(',').mapTo(linkedSetOf()) { encodedIndex ->
            encodedIndex.toIntOrNull()
                ?: throw IllegalArgumentException(
                    "generic-owner function input entry '$logicalKey' has invalid parameter index " +
                            "'$encodedIndex'"
                )
        }
        return DotNetPhysicalDeclaration.GenericOwnerFunctionInputEntry(
            ownerPath = fields.drop(6).requireOwnerPath(logicalKey, "generic-owner function input entry"),
            logicalFunctionKey = fields[1],
            methodName = fields[3].requireMethodName(logicalKey, "classifier-input entry"),
            isInstance = fields[2].decodeDispatch(logicalKey),
            objectParameterIndices = parameterIndices,
        ).also { entry ->
            require(entry.indexKey() == logicalKey) {
                "generic-owner function input entry '$logicalKey' is inconsistent with its structured identity"
            }
        }
    }

    private fun decodeInterfaceDefaultFunction(
        fields: List<String>,
        logicalKey: String,
    ): DotNetPhysicalDeclaration.Function {
        require(fields.size >= 8) {
            "interface-default function declaration '$logicalKey' has an incomplete CLR identity"
        }
        val bodyPlacement = when (fields[3]) {
            "H" -> DotNetInterfaceDefaultBodyPlacement.HELPER_ONLY
            "D" -> DotNetInterfaceDefaultBodyPlacement.DIM_WITH_HELPER
            else -> throw IllegalArgumentException(
                "interface-default function declaration '$logicalKey' has invalid body placement '${fields[3]}'"
            )
        }
        val ownerSize = fields[4].toIntOrNull()
        require(ownerSize != null && ownerSize > 0 && 6 + ownerSize < fields.size) {
            "interface-default function declaration '$logicalKey' has an invalid CLR owner-path size '${fields[4]}'"
        }
        val ownerPath = fields.subList(6, 6 + ownerSize).requireOwnerPath(logicalKey)
        val helperOwnerPath = fields.drop(6 + ownerSize).requireOwnerPath(logicalKey, "helper")
        return DotNetPhysicalDeclaration.Function(
            ownerPath = ownerPath,
            methodName = fields[2].requireMethodName(logicalKey),
            isInstance = fields[1].decodeDispatch(logicalKey),
            interfaceDefaultImplementation = DotNetInterfaceDefaultImplementation(
                bodyPlacement = bodyPlacement,
                helperOwnerPath = helperOwnerPath,
                helperMethodName = fields[5].requireMethodName(logicalKey, "helper"),
            ),
        )
    }

    private fun decodeDefaultArgumentFunction(
        fields: List<String>,
        logicalKey: String,
    ): DotNetPhysicalDeclaration.Function {
        require(fields.size >= 7) {
            "default-argument function declaration '$logicalKey' has an incomplete CLR identity"
        }
        val ownerSize = fields[3].toIntOrNull()
        require(ownerSize != null && ownerSize > 0 && 5 + ownerSize < fields.size) {
            "default-argument function declaration '$logicalKey' has an invalid CLR owner-path size '${fields[3]}'"
        }
        val ownerPath = fields.subList(5, 5 + ownerSize).requireOwnerPath(logicalKey)
        val dispatcherOwnerPath = fields.drop(5 + ownerSize).requireOwnerPath(logicalKey, "default-argument dispatcher")
        return DotNetPhysicalDeclaration.Function(
            ownerPath = ownerPath,
            methodName = fields[2].requireMethodName(logicalKey),
            isInstance = fields[1].decodeDispatch(logicalKey),
            defaultArgumentDispatcher = DotNetDefaultArgumentDispatcher(
                ownerPath = dispatcherOwnerPath,
                methodName = fields[4].requireMethodName(logicalKey, "default-argument dispatcher"),
            ),
        )
    }

    private fun decodeInterfaceDefaultArgumentFunction(
        fields: List<String>,
        logicalKey: String,
    ): DotNetPhysicalDeclaration.Function {
        require(fields.size >= 11) {
            "interface-default/default-argument function declaration '$logicalKey' has an incomplete CLR identity"
        }
        val bodyPlacement = when (fields[3]) {
            "H" -> DotNetInterfaceDefaultBodyPlacement.HELPER_ONLY
            "D" -> DotNetInterfaceDefaultBodyPlacement.DIM_WITH_HELPER
            else -> throw IllegalArgumentException(
                "interface-default/default-argument function declaration '$logicalKey' has invalid body placement '${fields[3]}'"
            )
        }
        val ownerSize = fields[4].toIntOrNull()
        val helperOwnerSize = fields[5].toIntOrNull()
        require(
            ownerSize != null && ownerSize > 0 &&
                    helperOwnerSize != null && helperOwnerSize > 0 &&
                    8 + ownerSize + helperOwnerSize < fields.size
        ) {
            "interface-default/default-argument function declaration '$logicalKey' has invalid CLR owner-path sizes"
        }
        val ownerEnd = 8 + ownerSize
        val helperOwnerEnd = ownerEnd + helperOwnerSize
        val ownerPath = fields.subList(8, ownerEnd).requireOwnerPath(logicalKey)
        val helperOwnerPath = fields.subList(ownerEnd, helperOwnerEnd).requireOwnerPath(logicalKey, "helper")
        val dispatcherOwnerPath = fields.drop(helperOwnerEnd).requireOwnerPath(logicalKey, "default-argument dispatcher")
        return DotNetPhysicalDeclaration.Function(
            ownerPath = ownerPath,
            methodName = fields[2].requireMethodName(logicalKey),
            isInstance = fields[1].decodeDispatch(logicalKey),
            interfaceDefaultImplementation = DotNetInterfaceDefaultImplementation(
                bodyPlacement = bodyPlacement,
                helperOwnerPath = helperOwnerPath,
                helperMethodName = fields[6].requireMethodName(logicalKey, "helper"),
            ),
            defaultArgumentDispatcher = DotNetDefaultArgumentDispatcher(
                ownerPath = dispatcherOwnerPath,
                methodName = fields[7].requireMethodName(logicalKey, "default-argument dispatcher"),
            ),
        )
    }

    private fun String.decodeDispatch(logicalKey: String): Boolean = when (this) {
        "0" -> false
        "1" -> true
        else -> throw IllegalArgumentException(
            "function declaration '$logicalKey' has invalid dispatch flag '$this'"
        )
    }

    private fun String.requireMethodName(logicalKey: String, role: String = "CLR"): String = also {
        require(isNotEmpty()) { "function declaration '$logicalKey' has an empty $role method name" }
    }

    private fun DotNetPhysicalDeclaration.InterfaceDefaultPromotion.encodeFields(): List<String> =
        listOf(
            "P",
            when (physicalView) {
                DotNetInterfaceDefaultPromotionView.CANONICAL -> "C"
                DotNetInterfaceDefaultPromotionView.DECLARED -> "D"
                DotNetInterfaceDefaultPromotionView.EXACT -> "E"
            },
            ownerLogicalKey,
            inheritedLogicalMemberKey,
            inheritedAssemblyName,
            inheritedMethodName,
            implementationMethodName,
            ownerPath.size.toString(),
            inheritedOwnerPath.size.toString(),
        ) + ownerPath + inheritedOwnerPath

    private fun decodeInterfaceDefaultPromotion(
        fields: List<String>,
        logicalKey: String,
    ): DotNetPhysicalDeclaration.InterfaceDefaultPromotion {
        require(fields.size >= 11) {
            "interface-default promotion '$logicalKey' has an incomplete CLR identity"
        }
        val physicalView = when (fields[1]) {
            "C" -> DotNetInterfaceDefaultPromotionView.CANONICAL
            "D" -> DotNetInterfaceDefaultPromotionView.DECLARED
            "E" -> DotNetInterfaceDefaultPromotionView.EXACT
            else -> throw IllegalArgumentException(
                "interface-default promotion '$logicalKey' has invalid physical view '${fields[1]}'"
            )
        }
        val ownerSize = fields[7].toIntOrNull()
        val inheritedOwnerSize = fields[8].toIntOrNull()
        require(ownerSize != null && ownerSize > 0 && inheritedOwnerSize != null && inheritedOwnerSize > 0) {
            "interface-default promotion '$logicalKey' has invalid CLR owner-path sizes"
        }
        require(fields.size == 9 + ownerSize + inheritedOwnerSize) {
            "interface-default promotion '$logicalKey' has an inconsistent CLR owner-path payload"
        }
        val ownerPath = fields.subList(9, 9 + ownerSize).requireOwnerPath(logicalKey, "promotion")
        val inheritedOwnerPath = fields.drop(9 + ownerSize).requireOwnerPath(logicalKey, "inherited")
        require(fields[2].isNotEmpty() && fields[3].isNotEmpty() && fields[4].isNotEmpty()) {
            "interface-default promotion '$logicalKey' has an empty logical or assembly identity"
        }
        return DotNetPhysicalDeclaration.InterfaceDefaultPromotion(
            ownerPath = ownerPath,
            ownerLogicalKey = fields[2],
            inheritedLogicalMemberKey = fields[3],
            physicalView = physicalView,
            inheritedAssemblyName = fields[4],
            inheritedOwnerPath = inheritedOwnerPath,
            inheritedMethodName = fields[5].requireMethodName(logicalKey, "inherited"),
            implementationMethodName = fields[6].requireMethodName(logicalKey, "implementation"),
        ).also { promotion ->
            require(promotion.indexKey() == logicalKey) {
                "interface-default promotion '$logicalKey' is inconsistent with its structured identity"
            }
        }
    }

    private fun DotNetPhysicalDeclaration.GenericInterfaceViewBridge.encodeFields(): List<String> =
        listOf(
            "B",
            when (physicalView) {
                DotNetInterfaceDefaultPromotionView.CANONICAL -> "C"
                DotNetInterfaceDefaultPromotionView.DECLARED -> "D"
                DotNetInterfaceDefaultPromotionView.EXACT -> "E"
            },
            ownerLogicalKey,
            inheritedLogicalMemberKey,
            implementationMethodName,
            ownerPath.size.toString(),
        ) + ownerPath

    private fun decodeGenericInterfaceViewBridge(
        fields: List<String>,
        logicalKey: String,
    ): DotNetPhysicalDeclaration.GenericInterfaceViewBridge {
        require(fields.size >= 7) {
            "generic-interface view bridge '$logicalKey' has an incomplete CLR identity"
        }
        val physicalView = when (fields[1]) {
            "C" -> DotNetInterfaceDefaultPromotionView.CANONICAL
            "D" -> DotNetInterfaceDefaultPromotionView.DECLARED
            "E" -> DotNetInterfaceDefaultPromotionView.EXACT
            else -> throw IllegalArgumentException(
                "generic-interface view bridge '$logicalKey' has invalid physical view '${fields[1]}'"
            )
        }
        val ownerSize = fields[5].toIntOrNull()
        require(ownerSize != null && ownerSize > 0 && fields.size == 6 + ownerSize) {
            "generic-interface view bridge '$logicalKey' has an invalid CLR owner-path payload"
        }
        require(fields[2].isNotEmpty() && fields[3].isNotEmpty()) {
            "generic-interface view bridge '$logicalKey' has an empty logical identity"
        }
        return DotNetPhysicalDeclaration.GenericInterfaceViewBridge(
            ownerPath = fields.drop(6).requireOwnerPath(logicalKey, "generic-interface view bridge"),
            ownerLogicalKey = fields[2],
            inheritedLogicalMemberKey = fields[3],
            physicalView = physicalView,
            implementationMethodName = fields[4].requireMethodName(logicalKey, "implementation"),
        ).also { bridge ->
            require(bridge.indexKey() == logicalKey) {
                "generic-interface view bridge '$logicalKey' is inconsistent with its structured identity"
            }
        }
    }

    private fun DotNetPhysicalDeclaration.GenericInterfaceIntersectionSlot.encodeFields(): List<String> =
        listOf(
            "I",
            when (physicalView) {
                DotNetInterfaceDefaultPromotionView.DECLARED -> "D"
                DotNetInterfaceDefaultPromotionView.EXACT -> "E"
                DotNetInterfaceDefaultPromotionView.CANONICAL -> error("intersection slot cannot be canonical")
            },
            ownerLogicalKey,
            methodName,
            ownerPath.size.toString(),
            contributingLogicalMemberKeys.size.toString(),
        ) + ownerPath + contributingLogicalMemberKeys

    private fun decodeGenericInterfaceIntersectionSlot(
        fields: List<String>,
        logicalKey: String,
    ): DotNetPhysicalDeclaration.GenericInterfaceIntersectionSlot {
        require(fields.size >= 9) {
            "generic-interface intersection slot '$logicalKey' has an incomplete CLR identity"
        }
        val physicalView = when (fields[1]) {
            "D" -> DotNetInterfaceDefaultPromotionView.DECLARED
            "E" -> DotNetInterfaceDefaultPromotionView.EXACT
            else -> throw IllegalArgumentException(
                "generic-interface intersection slot '$logicalKey' has invalid physical view '${fields[1]}'"
            )
        }
        val ownerSize = fields[4].toIntOrNull()
        val contributorCount = fields[5].toIntOrNull()
        require(ownerSize != null && ownerSize > 0 && contributorCount != null && contributorCount >= 2 &&
                fields.size == 6 + ownerSize + contributorCount
        ) {
            "generic-interface intersection slot '$logicalKey' has an invalid CLR payload size"
        }
        val ownerPath = fields.subList(6, 6 + ownerSize)
            .requireOwnerPath(logicalKey, "generic-interface intersection slot")
        val contributors = fields.drop(6 + ownerSize)
        require(fields[2].isNotEmpty()) {
            "generic-interface intersection slot '$logicalKey' has an empty logical owner"
        }
        return DotNetPhysicalDeclaration.GenericInterfaceIntersectionSlot(
            ownerPath = ownerPath,
            ownerLogicalKey = fields[2],
            contributingLogicalMemberKeys = contributors,
            physicalView = physicalView,
            methodName = fields[3].requireMethodName(logicalKey, "intersection"),
        ).also { slot ->
            require(slot.indexKey() == logicalKey) {
                "generic-interface intersection slot '$logicalKey' is inconsistent with its structured identity"
            }
        }
    }

    private fun DotNetPhysicalDeclaration.CovariantReturnBridge.encodeFields(): List<String> =
        listOf(
            "R",
            ownerLogicalKey,
            inheritedLogicalMemberKey,
            implementationMethodName,
            ownerPath.size.toString(),
        ) + ownerPath

    private fun decodeCovariantReturnBridge(
        fields: List<String>,
        logicalKey: String,
    ): DotNetPhysicalDeclaration.CovariantReturnBridge {
        require(fields.size >= 6) {
            "covariant-return bridge '$logicalKey' has an incomplete CLR identity"
        }
        val ownerSize = fields[4].toIntOrNull()
        require(ownerSize != null && ownerSize > 0 && fields.size == 5 + ownerSize) {
            "covariant-return bridge '$logicalKey' has an invalid CLR owner-path payload"
        }
        require(fields[1].isNotEmpty() && fields[2].isNotEmpty()) {
            "covariant-return bridge '$logicalKey' has an empty logical identity"
        }
        return DotNetPhysicalDeclaration.CovariantReturnBridge(
            ownerPath = fields.drop(5).requireOwnerPath(logicalKey, "covariant-return bridge"),
            ownerLogicalKey = fields[1],
            inheritedLogicalMemberKey = fields[2],
            implementationMethodName = fields[3].requireMethodName(logicalKey, "implementation"),
        ).also { bridge ->
            require(bridge.indexKey() == logicalKey) {
                "covariant-return bridge '$logicalKey' is inconsistent with its structured identity"
            }
        }
    }

    private fun DotNetPhysicalDeclaration.InterfaceDefaultClassForwarder.encodeFields(): List<String> =
        listOf(
            "W",
            when (physicalView) {
                DotNetInterfaceDefaultPromotionView.CANONICAL -> "C"
                DotNetInterfaceDefaultPromotionView.DECLARED -> "D"
                DotNetInterfaceDefaultPromotionView.EXACT -> "E"
            },
            ownerLogicalKey,
            inheritedLogicalMemberKey,
            implementationMethodName,
            ownerPath.size.toString(),
        ) + ownerPath

    private fun DotNetPhysicalDeclaration.GenericOwnerMemberFamily.encodeFields(): List<String> {
        val semanticOwnerPath = semanticHookOwnerPath.orEmpty()
        return listOf(
            "G",
            ownerLogicalKey,
            logicalMemberKey,
            capabilityMethodName,
            defaultCapabilityMethodName.orEmpty(),
            semanticHookMethodName.orEmpty(),
            foreignOverrideProbeMethodName.orEmpty(),
            ownerPath.size.toString(),
            semanticOwnerPath.size.toString(),
        ) + ownerPath + semanticOwnerPath
    }

    private fun decodeGenericOwnerMemberFamily(
        fields: List<String>,
        logicalKey: String,
    ): DotNetPhysicalDeclaration.GenericOwnerMemberFamily {
        require(fields.size >= 10) {
            "generic-owner member family '$logicalKey' has an incomplete CLR identity"
        }
        val capabilityOwnerSize = fields[7].toIntOrNull()
        val semanticOwnerSize = fields[8].toIntOrNull()
        require(capabilityOwnerSize != null && capabilityOwnerSize > 0 &&
                semanticOwnerSize != null && semanticOwnerSize >= 0 &&
                fields.size == 9 + capabilityOwnerSize + semanticOwnerSize
        ) {
            "generic-owner member family '$logicalKey' has an invalid CLR owner-path payload"
        }
        require((semanticOwnerSize == 0) == fields[5].isEmpty()) {
            "generic-owner member family '$logicalKey' has an inconsistent semantic-hook identity"
        }
        val family = DotNetPhysicalDeclaration.GenericOwnerMemberFamily(
            ownerPath = fields.subList(9, 9 + capabilityOwnerSize)
                .requireOwnerPath(logicalKey, "generic-owner capability"),
            ownerLogicalKey = fields[1],
            logicalMemberKey = fields[2],
            capabilityMethodName = fields[3].requireMethodName(logicalKey, "generic-owner capability"),
            defaultCapabilityMethodName = fields[4].takeIf(String::isNotEmpty),
            semanticHookOwnerPath = if (semanticOwnerSize == 0) null else fields.drop(9 + capabilityOwnerSize)
                .requireOwnerPath(logicalKey, "generic-owner semantic hook"),
            semanticHookMethodName = fields[5].takeIf(String::isNotEmpty),
            foreignOverrideProbeMethodName = fields[6].takeIf(String::isNotEmpty),
        )
        require(family.indexKey() == logicalKey) {
            "generic-owner member family '$logicalKey' is inconsistent with its structured identity"
        }
        return family
    }

    private fun decodeInterfaceDefaultClassForwarder(
        fields: List<String>,
        logicalKey: String,
    ): DotNetPhysicalDeclaration.InterfaceDefaultClassForwarder {
        require(fields.size >= 7) {
            "interface-default class forwarder '$logicalKey' has an incomplete CLR identity"
        }
        val physicalView = when (fields[1]) {
            "C" -> DotNetInterfaceDefaultPromotionView.CANONICAL
            "D" -> DotNetInterfaceDefaultPromotionView.DECLARED
            "E" -> DotNetInterfaceDefaultPromotionView.EXACT
            else -> throw IllegalArgumentException(
                "interface-default class forwarder '$logicalKey' has invalid physical view '${fields[1]}'"
            )
        }
        val ownerSize = fields[5].toIntOrNull()
        require(ownerSize != null && ownerSize > 0 && fields.size == 6 + ownerSize) {
            "interface-default class forwarder '$logicalKey' has an invalid CLR owner-path payload"
        }
        require(fields[2].isNotEmpty() && fields[3].isNotEmpty()) {
            "interface-default class forwarder '$logicalKey' has an empty logical identity"
        }
        return DotNetPhysicalDeclaration.InterfaceDefaultClassForwarder(
            ownerPath = fields.drop(6).requireOwnerPath(logicalKey, "class forwarder"),
            ownerLogicalKey = fields[2],
            inheritedLogicalMemberKey = fields[3],
            physicalView = physicalView,
            implementationMethodName = fields[4].requireMethodName(logicalKey, "implementation"),
        ).also { forwarder ->
            require(forwarder.indexKey() == logicalKey) {
                "interface-default class forwarder '$logicalKey' is inconsistent with its structured identity"
            }
        }
    }

    private fun DotNetPhysicalDeclaration.Class.encodeFields(): List<String> {
        val initializationPath = staticInitialization?.ownerPath.orEmpty()
        val objectInstancePath = objectInstance?.ownerPath.orEmpty()
        val genericOwnerCapabilityPath = genericOwnerAbi?.capabilityOwnerPath.orEmpty()
        return listOf(
            "C",
            ownerPath.size.toString(),
            physicalTypeParameterCount.toString(),
            initializationPath.size.toString(),
            staticInitialization?.methodName.orEmpty(),
            objectInstancePath.size.toString(),
            objectInstance?.fieldName.orEmpty(),
            valueClassAbi?.primaryConstructorMethodName.orEmpty(),
            valueClassAbi?.boxMethodName.orEmpty(),
            valueClassAbi?.unboxMethodName.orEmpty(),
            genericOwnerAbi?.capabilityAssemblyName.orEmpty(),
            genericOwnerCapabilityPath.size.toString(),
        ) + ownerPath + initializationPath + objectInstancePath + genericOwnerCapabilityPath
    }

    private fun DotNetPhysicalDeclaration.EnumEntry.encodeFields(): List<String> =
        listOf("E", ownerPath.size.toString(), fieldName) + ownerPath

    private fun decodeEnumEntry(
        fields: List<String>,
        logicalKey: String,
    ): DotNetPhysicalDeclaration.EnumEntry {
        require(fields.size >= 4) {
            "enum-entry declaration '$logicalKey' has an incomplete CLR identity"
        }
        val ownerSize = fields[1].toIntOrNull()
        require(ownerSize != null && ownerSize > 0 && fields.size == 3 + ownerSize) {
            "enum-entry declaration '$logicalKey' has an invalid CLR owner-path payload"
        }
        return DotNetPhysicalDeclaration.EnumEntry(
            ownerPath = fields.drop(3).requireOwnerPath(logicalKey, "enum-entry"),
            fieldName = fields[2].requireFieldName(logicalKey, "enum-entry"),
        )
    }

    private fun decodeClass(
        fields: List<String>,
        logicalKey: String,
    ): DotNetPhysicalDeclaration.Class {
        require(fields.size >= 13) {
            "class declaration '$logicalKey' has an incomplete CLR identity"
        }
        fun pathSize(fieldIndex: Int, view: String, allowAbsent: Boolean = false): Int {
            val size = fields[fieldIndex].toIntOrNull()
            require(size != null && (size > 0 || allowAbsent && size == 0)) {
                "generic-interface declaration '$logicalKey' has invalid $view owner-path size '${fields[fieldIndex]}'"
            }
            return size
        }

        val ownerSize = pathSize(1, "runtime")
        val physicalTypeParameterCount = fields[2].toIntOrNull()
        require(physicalTypeParameterCount != null && physicalTypeParameterCount >= 0) {
            "class declaration '$logicalKey' has invalid physical generic arity '${fields[2]}'"
        }
        val initializationSize = pathSize(3, "static-initialization", allowAbsent = true)
        val objectInstanceSize = pathSize(5, "object-instance", allowAbsent = true)
        val genericOwnerCapabilitySize = pathSize(11, "generic-owner capability", allowAbsent = true)
        val expectedSize = 12 + ownerSize + initializationSize + objectInstanceSize + genericOwnerCapabilitySize
        require(fields.size == expectedSize) {
            "class declaration '$logicalKey' has an inconsistent CLR owner-path payload"
        }
        require((initializationSize == 0) == fields[4].isEmpty()) {
            "class declaration '$logicalKey' has an inconsistent static-initialization identity"
        }
        require((objectInstanceSize == 0) == fields[6].isEmpty()) {
            "class declaration '$logicalKey' has an inconsistent object-instance identity"
        }
        require(fields[7].isEmpty() == fields[8].isEmpty() && fields[8].isEmpty() == fields[9].isEmpty()) {
            "class declaration '$logicalKey' has an incomplete value-class compiler ABI"
        }
        require((genericOwnerCapabilitySize == 0) == fields[10].isEmpty()) {
            "class declaration '$logicalKey' has an inconsistent generic-owner capability assembly"
        }
        var offset = 12
        fun takePath(size: Int): List<String> = fields.subList(offset, offset + size).also { offset += size }
        val ownerPath = takePath(ownerSize).requireOwnerPath(logicalKey, "runtime")
        val initialization = if (initializationSize == 0) {
            null
        } else {
            DotNetStaticInitialization(
                ownerPath = takePath(initializationSize).requireOwnerPath(logicalKey, "static-initialization"),
                methodName = fields[4].requireMethodName(logicalKey, "static-initialization"),
            )
        }
        val objectInstance = if (objectInstanceSize == 0) {
            null
        } else {
            DotNetObjectInstance(
                ownerPath = takePath(objectInstanceSize).requireOwnerPath(logicalKey, "object-instance"),
                fieldName = fields[6].requireFieldName(logicalKey, "object-instance"),
            )
        }
        val genericOwnerAbi = if (genericOwnerCapabilitySize == 0) {
            null
        } else {
            DotNetGenericOwnerAbi(
                capabilityAssemblyName = fields[10],
                capabilityOwnerPath =
                    takePath(genericOwnerCapabilitySize).requireOwnerPath(logicalKey, "generic-owner capability"),
            )
        }
        return DotNetPhysicalDeclaration.Class(
            ownerPath = ownerPath,
            physicalTypeParameterCount = physicalTypeParameterCount,
            staticInitialization = initialization,
            objectInstance = objectInstance,
            valueClassAbi = fields[7].takeIf(String::isNotEmpty)?.let { primaryConstructorMethodName ->
                DotNetValueClassAbi(
                    primaryConstructorMethodName = primaryConstructorMethodName,
                    boxMethodName = fields[8],
                    unboxMethodName = fields[9],
                )
            },
            genericOwnerAbi = genericOwnerAbi,
        )
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

    private fun String.requireFieldName(logicalKey: String, role: String = "CLR"): String = also {
        require(isNotEmpty()) { "class declaration '$logicalKey' has an empty $role field name" }
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

/** The logical member from which Common created this value-class static implementation. */
@OptIn(ValueClassBackendAgnosticApi::class)
internal fun IrSimpleFunction.dotNetValueClassImplementationSourceOrNull(): IrSimpleFunction? {
    if (!isStaticMethodOfClass || !name.asString().removeSuffix(">").endsWith("-impl")) return null
    val owner = parent as? IrClass ?: return null
    if (!owner.isInlineClass(treatCompatibleFullValueClassesAsInline = true)) return null
    val source = attributeOwnerId as? IrSimpleFunction ?: return null
    return source.takeUnless { it === this }
}

/** The primary constructor from which Common created this value-class static implementation. */
@OptIn(ValueClassBackendAgnosticApi::class)
internal fun IrSimpleFunction.dotNetValueClassConstructorImplementationSourceOrNull(): IrConstructor? {
    if (!isStaticMethodOfClass || !name.asString().removeSuffix(">").endsWith("<init>-impl")) return null
    val owner = parent as? IrClass ?: return null
    if (!owner.isInlineClass(treatCompatibleFullValueClassesAsInline = true)) return null
    val source = attributeOwnerId as? IrConstructor ?: return null
    return source.takeIf { it.isPrimary && it.parent == owner }
}

/**
 * Binding-index-local rendered identity table for the DLL's Kotlin-to-CLR binding index.
 *
 * The shared KLIB serializer's declaration table likewise computes one public
 * [org.jetbrains.kotlin.ir.util.IdSignature] per declaration. The .NET binder additionally needs its stable textual
 * form and declaration-kind prefix, so retain that final lookup key by IR identity instead of rebuilding and rendering
 * the same signature at every binding query. Keep the lifetime on one [DotNetExternalDeclarations] instance: unlike
 * external symbol signatures, local IR can still be changed by a later lowering, just as JVM signature caches must not
 * outlive the IR shape from which they were derived.
 */
private class DotNetLibraryAbiKeyCache {
    private data class Entry(
        val kind: String,
        val key: String?,
    )

    private val entries = IdentityHashMap<IrDeclaration, Entry>()
    private val signatureComputer = PublicIdSignatureComputer(DotNetIrMangler)

    fun keyOrNull(declaration: IrDeclaration, kind: String): String? {
        entries[declaration]?.let { entry ->
            check(entry.kind == kind) {
                "declaration ABI identity was requested as both '${entry.kind}' and '$kind'"
            }
            return entry.key
        }
        return declaration.computeDotNetLibraryAbiKeyOrNull(kind, signatureComputer).also { key ->
            entries[declaration] = Entry(kind, key)
        }
    }
}

/** Public logical identity for metadata-backed records outside the KLIB physical-index builder. */
internal fun IrDeclaration.dotNetLibraryAbiKeyOrNull(kind: String): String? =
    computeDotNetLibraryAbiKeyOrNull(
        kind,
        PublicIdSignatureComputer(DotNetIrMangler),
    )

/**
 * Captures the public logical declarations which need a CLR binding before backend lowerings can
 * mutate signatures or introduce synthetic declarations. KLIB remains the authority for which
 * declarations are visible; this set is only the linkage domain that must be covered by the
 * companion physical index.
 *
 * Type aliases and const accessors intentionally have no physical entry. Compiler-intrinsic
 * declarations and mapped exception stubs are likewise resolved by target-owned mappings rather
 * than by a member in the produced assembly.
 */
internal fun collectDotNetMetadataLinkageKeys(
    moduleFragment: IrModuleFragment,
    emissionScope: DotNetIlEmissionScope,
    isIntrinsicDeclaration: (IrSimpleFunction) -> Boolean,
): Map<IrDeclaration, String> = buildMap {
    val signatureComputer = PublicIdSignatureComputer(DotNetIrMangler)

    fun addFunction(function: IrSimpleFunction) {
        if (!function.isDotNetCrossModuleDeclaration ||
            function.isFakeOverride ||
            isIntrinsicDeclaration(function)
        ) {
            return
        }
        if (!with(DotNetIrMangler) { function.isExported(compatibleMode = false) }) return
        function.computeDotNetLibraryAbiKeyOrNull("F", signatureComputer)?.let { key -> put(function, key) }
    }

    fun addProperty(property: IrProperty) {
        if (property.isConst) return
        val accessors = listOfNotNull(property.getter, property.setter)
        if (accessors.any(isIntrinsicDeclaration)) return
        accessors.forEach(::addFunction)
    }

    fun addEnumEntry(entry: IrEnumEntry) {
        val owner = entry.parent as? IrClass ?: return
        if (!owner.isDotNetCrossModuleDeclaration) return
        if (!with(DotNetIrMangler) { entry.isExported(compatibleMode = false) }) return
        entry.computeDotNetLibraryAbiKeyOrNull("E", signatureComputer)?.let { key -> put(entry, key) }
    }

    fun addClass(irClass: IrClass) {
        if (irClass.isDotNetResolutionOnlyStdlibDeclaration) return
        if (irClass.isDotNetCrossModuleDeclaration &&
            with(DotNetIrMangler) { irClass.isExported(compatibleMode = false) }
        ) {
            irClass.computeDotNetLibraryAbiKeyOrNull("C", signatureComputer)?.let { key -> put(irClass, key) }
        }
        for (declaration in irClass.declarations) {
            when (declaration) {
                is IrClass -> addClass(declaration)
                is IrEnumEntry -> addEnumEntry(declaration)
                is IrSimpleFunction -> addFunction(declaration)
                is IrProperty -> addProperty(declaration)
                else -> {}
            }
        }
    }

    val files = when (emissionScope) {
        DotNetIlEmissionScope.USER -> moduleFragment.files
        DotNetIlEmissionScope.STDLIB -> moduleFragment.files.filter(IrFile::isDotNetStdlibImplementationSource)
    }
    for (file in files) {
        for (declaration in file.declarations) {
            when (declaration) {
                is IrClass -> if (emissionScope.owns(declaration)) addClass(declaration)
                is IrSimpleFunction -> if (emissionScope.owns(declaration)) addFunction(declaration)
                is IrProperty -> if (emissionScope.owns(declaration)) addProperty(declaration)
                else -> {}
            }
        }
    }
}

/**
 * The DLL's physical declaration index is a cross-module binding contract. File-private and local
 * declarations have valid file-local IdSignatures for backend-internal use, but exporting those
 * signatures would leak checkout paths and make a private implementation look externally bindable.
 */
private val IrDeclaration.isDotNetCrossModuleDeclaration: Boolean
    get() {
        var declaration: IrDeclaration? = this
        while (declaration is IrDeclarationWithVisibility) {
            if (declaration.visibility == DescriptorVisibilities.PRIVATE ||
                declaration.visibility == DescriptorVisibilities.PRIVATE_TO_THIS ||
                declaration.visibility == DescriptorVisibilities.LOCAL
            ) {
                return false
            }
            declaration = declaration.parent as? IrDeclaration
        }
        return true
    }

/**
 * Stable identity suffix for one logical generic-interface slot.
 *
 * Public declarations use the same Kotlin [org.jetbrains.kotlin.ir.util.IdSignature] that keys
 * the companion KLIB index. Non-exported declarations use an explicit structural type codec;
 * they do not cross module boundaries, but still need deterministic collision-free names inside
 * their owner. Neither `IrType.render()` nor the general IR mangler is safe here: lowered
 * private/local type-parameter identities in both contain process-local identity. The bounded
 * codec mirrors the existing covariant-slot identity rule and uses only classifier names,
 * projections, nullability, parameter kinds, and owner/method type-parameter indices. The digest
 * is deliberately independent of declaration order and source offsets.
 */
internal fun IrSimpleFunction.dotNetGenericInterfaceCanonicalSlotId(): String {
    val signatureComputer = PublicIdSignatureComputer(DotNetIrMangler)
    val logicalIdentity = takeIf { function -> function.isDotNetCrossModuleDeclaration }
        ?.computeDotNetLibraryAbiKeyOrNull("F", signatureComputer)
        ?: buildString {
            append((parent as? IrClass)?.fqNameWhenAvailable?.asString().orEmpty())
            append('|')
            append(dotNetIlMethodName())
            append('|')
            append(typeParameters.size)
            append('|')
            parameters.forEach { parameter ->
                append(parameter.kind)
                append(':')
                append(parameter.type.dotNetCanonicalSlotStructuralTypeKey())
                append(';')
            }
            append("->")
            append(returnType.dotNetCanonicalSlotStructuralTypeKey())
        }
    val digest = DotNetLibraryAbiCodec.logicalIdentityDigest(logicalIdentity)
    return digest
}

private fun IrType.dotNetCanonicalSlotStructuralTypeKey(): String {
    val simpleType = this as? IrSimpleType ?: return javaClass.simpleName
    val classifierKey = when (val classifier = simpleType.classifier) {
        is IrClassSymbol -> classifier.owner.fqNameWhenAvailable?.asString()
            ?: classifier.owner.name.asString()
        is IrTypeParameterSymbol -> {
            val parameter = classifier.owner
            val ownerParameters = when (val parameterOwner = parameter.parent) {
                is IrClass -> parameterOwner.typeParameters
                is IrSimpleFunction -> parameterOwner.typeParameters
                else -> emptyList()
            }
            "${parameter.parent.javaClass.simpleName}#${ownerParameters.indexOf(parameter)}"
        }
        else -> classifier.javaClass.simpleName
    }
    val arguments = simpleType.arguments.joinToString(",", prefix = "<", postfix = ">") { argument ->
        when (argument) {
            is IrStarProjection -> "*"
            is IrTypeProjection -> "${argument.variance}:${argument.type.dotNetCanonicalSlotStructuralTypeKey()}"
        }
    }
    return classifierKey + arguments + if (simpleType.isMarkedNullable()) "?" else ""
}

/** Reserved physical name of a canonical erased slot; typed capabilities keep the source name. */
internal fun IrSimpleFunction.dotNetGenericInterfaceCanonicalMethodName(): String =
    "${dotNetIlMethodName()}__KotlinErased__${dotNetGenericInterfaceCanonicalSlotId()}"

internal data class DotNetBoundInterfaceDefaultImplementation(
    val library: DotNetExternalLibrary,
    val function: DotNetPhysicalDeclaration.Function,
    val implementation: DotNetInterfaceDefaultImplementation,
)

internal data class DotNetBoundDefaultArgumentDispatcher(
    val library: DotNetExternalLibrary,
    val function: DotNetPhysicalDeclaration.Function,
    val dispatcher: DotNetDefaultArgumentDispatcher,
)

internal data class DotNetBoundStaticInitialization(
    val library: DotNetExternalLibrary,
    val declaration: DotNetPhysicalDeclaration.Class,
    val initialization: DotNetStaticInitialization,
)

internal data class DotNetBoundObjectInstance(
    val library: DotNetExternalLibrary,
    val declaration: DotNetPhysicalDeclaration.Class,
    val objectInstance: DotNetObjectInstance,
)

internal data class DotNetBoundEnumEntry(
    val library: DotNetExternalLibrary,
    val enumEntry: DotNetPhysicalDeclaration.EnumEntry,
)

internal data class DotNetBoundInterfaceDefaultPromotion(
    val library: DotNetExternalLibrary,
    val promotion: DotNetPhysicalDeclaration.InterfaceDefaultPromotion,
)

internal data class DotNetBoundGenericInterfaceViewBridge(
    val library: DotNetExternalLibrary,
    val bridge: DotNetPhysicalDeclaration.GenericInterfaceViewBridge,
)

internal data class DotNetBoundCovariantReturnBridge(
    val library: DotNetExternalLibrary,
    val bridge: DotNetPhysicalDeclaration.CovariantReturnBridge,
)

internal data class DotNetBoundInterfaceDefaultClassForwarder(
    val library: DotNetExternalLibrary,
    val forwarder: DotNetPhysicalDeclaration.InterfaceDefaultClassForwarder,
)

internal data class DotNetBoundGenericOwnerMemberFamily(
    val library: DotNetExternalLibrary,
    val family: DotNetPhysicalDeclaration.GenericOwnerMemberFamily,
)

internal data class DotNetBoundGenericOwnerFunctionCarrier(
    val library: DotNetExternalLibrary,
    val carrier: DotNetPhysicalDeclaration.GenericOwnerFunctionCarrier,
)

internal data class DotNetBoundGenericOwnerFunctionInputEntry(
    val library: DotNetExternalLibrary,
    val entry: DotNetPhysicalDeclaration.GenericOwnerFunctionInputEntry,
)

internal data class DotNetBoundGenericOwnerPhysicalSlot(
    val library: DotNetExternalLibrary,
    val family: DotNetPhysicalDeclaration.GenericOwnerMemberFamily,
    val ownerPath: List<String>,
    val physicalMethodName: String,
) {
    init {
        require(ownerPath.isNotEmpty()) {
            "a bound generic-owner physical slot requires a CLR owner"
        }
        require(physicalMethodName.isNotEmpty()) {
            "a bound generic-owner physical slot requires a MethodDef name"
        }
    }
}

/** Immutable library-side indexes shared by the lowering-local external-declaration resolvers. */
internal class DotNetExternalDeclarationIndex(
    val libraries: List<DotNetExternalLibrary>,
) {
    internal data class BoundDeclaration(
        val library: DotNetExternalLibrary,
        val declaration: DotNetPhysicalDeclaration,
    )

    internal val declarations: Map<String, BoundDeclaration> = buildMap {
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
    internal val genericOwnerMemberFamiliesByLogicalKey:
        Map<String, DotNetBoundGenericOwnerMemberFamily> = buildMap {
            libraries.forEach { library ->
                library.declarations.values.filterIsInstance<DotNetPhysicalDeclaration.GenericOwnerMemberFamily>()
                    .forEach { family ->
                        require(put(
                            family.logicalMemberKey,
                            DotNetBoundGenericOwnerMemberFamily(library, family),
                        ) == null) {
                            "duplicate external Kotlin/.NET generic-owner member family " +
                                    "'${family.logicalMemberKey}'"
                        }
                    }
            }
        }
    internal val genericOwnerFunctionCarriersByLogicalKey:
        Map<String, DotNetBoundGenericOwnerFunctionCarrier> = buildMap {
            libraries.forEach { library ->
                library.declarations.values
                    .filterIsInstance<DotNetPhysicalDeclaration.GenericOwnerFunctionCarrier>()
                    .forEach { carrier ->
                        require(put(
                            carrier.logicalFunctionKey,
                            DotNetBoundGenericOwnerFunctionCarrier(library, carrier),
                        ) == null) {
                            "duplicate external Kotlin/.NET generic-owner function carrier " +
                                    "'${carrier.logicalFunctionKey}'"
                        }
                    }
            }
        }
    internal val genericOwnerFunctionInputEntriesByLogicalKey:
        Map<String, DotNetBoundGenericOwnerFunctionInputEntry> = buildMap {
            libraries.forEach { library ->
                library.declarations.values
                    .filterIsInstance<DotNetPhysicalDeclaration.GenericOwnerFunctionInputEntry>()
                    .forEach { entry ->
                        require(put(
                            entry.logicalFunctionKey,
                            DotNetBoundGenericOwnerFunctionInputEntry(library, entry),
                        ) == null) {
                            "duplicate external Kotlin/.NET generic-owner function input entry " +
                                    "'${entry.logicalFunctionKey}'"
                        }
                    }
            }
        }
}

/**
 * Resolves metadata-deserialized declarations through the bound physical identities of their
 * companion assemblies. Only declarations present in the index become physical CLR references.
 *
 * The immutable library index may be shared across sequential lowerings. The IR-key and emitted
 * class-info caches deliberately remain resolver-local because a later lowering may have changed
 * the local IR shape from which those values are derived.
 */
internal class DotNetExternalDeclarations(
    private val index: DotNetExternalDeclarationIndex,
) {
    constructor(libraries: List<DotNetExternalLibrary>) : this(DotNetExternalDeclarationIndex(libraries))

    val libraries: List<DotNetExternalLibrary> = index.libraries
    private val declarations = index.declarations
    private val genericOwnerMemberFamiliesByLogicalKey = index.genericOwnerMemberFamiliesByLogicalKey
    private val genericOwnerFunctionCarriersByLogicalKey = index.genericOwnerFunctionCarriersByLogicalKey
    private val genericOwnerFunctionInputEntriesByLogicalKey =
        index.genericOwnerFunctionInputEntriesByLogicalKey
    private val canonicalClassInfoByLogicalKey = hashMapOf<String, DotNetIlClassInfo>()
    private val genericOwnerCapabilityInfoByLogicalKey = hashMapOf<String, DotNetIlClassInfo>()
    private val classLinksInProgress = hashSetOf<String>()
    private val facadeInfoByPhysicalIdentity = hashMapOf<Pair<String, List<String>>, DotNetIlClassInfo>()
    private val logicalKeys = DotNetLibraryAbiKeyCache()

    /**
     * Whether [irClass] has a producer-recorded physical CLR class in a bound library.
     *
     * Shape gates that merely authorize an external base or interface test declaration
     * membership here. They must not infer an optional host capability from the Kotlin
     * declaration. A class or rehearsal-admitted interface record may select either an erased or
     * full-arity physical owner; consumers must obey the producer-recorded arity.
     */
    fun hasClass(irClass: IrClass): Boolean {
        val logicalKey = logicalKeys.keyOrNull(irClass, "C") ?: return false
        return declarations[logicalKey]?.declaration is DotNetPhysicalDeclaration.Class
    }

    /** Whether KLIB kind plus the producer index select an erased Kotlin-owned generic class. */
    fun hasGenericClass(irClass: IrClass): Boolean {
        if (irClass.isInterface || irClass.typeParameters.isEmpty()) return false
        val logicalKey = logicalKeys.keyOrNull(irClass, "C") ?: return false
        val declaration = declarations[logicalKey]?.declaration as? DotNetPhysicalDeclaration.Class
            ?: return false
        return declaration.physicalTypeParameterCount == 0
    }

    /** Whether KLIB kind plus the producer index select an erased Kotlin-owned generic interface. */
    fun hasGenericInterface(irClass: IrClass): Boolean {
        if (!irClass.isInterface || irClass.typeParameters.isEmpty()) return false
        val logicalKey = logicalKeys.keyOrNull(irClass, "C") ?: return false
        val declaration = declarations[logicalKey]?.declaration as? DotNetPhysicalDeclaration.Class
            ?: return false
        return declaration.physicalTypeParameterCount == 0
    }

    /** Whether producer ABI selected one natural CLR `I<T>` plus its semantic capability. */
    fun hasReifiedGenericInterface(irClass: IrClass): Boolean {
        if (!irClass.isInterface || irClass.typeParameters.isEmpty()) return false
        val logicalKey = logicalKeys.keyOrNull(irClass, "C") ?: return false
        val declaration = declarations[logicalKey]?.declaration as? DotNetPhysicalDeclaration.Class
            ?: return false
        return declaration.physicalTypeParameterCount == irClass.typeParameters.size &&
                declaration.genericOwnerAbi != null
    }

    fun classInfoOrNull(irClass: IrClass, typeMapper: DotNetIlTypeMapper): DotNetIlClassInfo? {
        val logicalKey = logicalKeys.keyOrNull(irClass, "C") ?: return null
        canonicalClassInfoByLogicalKey[logicalKey]?.let { return it }
        val bound = declarations[logicalKey] ?: return null
        val declaration = bound.declaration as? DotNetPhysicalDeclaration.Class ?: return null
        require(declaration.physicalTypeParameterCount == 0 ||
                declaration.physicalTypeParameterCount == irClass.typeParameters.size
        ) {
            "external Kotlin/.NET class '$logicalKey' records physical arity " +
                    "${declaration.physicalTypeParameterCount}; expected erased arity 0 or complete logical arity " +
                    irClass.typeParameters.size
        }
        // CLR class GenericParams remain invariant. A producer-recorded reified interface instead
        // owns the same declaration-site variance as its natural I<T> metadata; its separate
        // capability handles Kotlin views which cannot name one constructed interface.
        val canonicalVariances = if (irClass.isInterface && declaration.genericOwnerAbi != null) {
            irClass.typeParameters.map { parameter -> parameter.variance }
        } else {
            List(declaration.physicalTypeParameterCount) { Variance.INVARIANT }
        }
        val classInfo = buildClassInfo(
            bound.library.artifact.assemblyName,
            declaration.ownerPath,
            canonicalVariances,
        )
        canonicalClassInfoByLogicalKey[logicalKey] = classInfo

        if (classLinksInProgress.add(logicalKey)) {
            try {
                classInfo.baseType = if (irClass.isAnnotationClass) {
                    // KLIB authoritatively records kotlin.Annotation, while the producer's
                    // physical CLR declaration derives from System.Attribute. Reconstruct that
                    // physical edge for a separately compiled consumer just as the producer
                    // emitter does for declarations in the current module.
                    DotNetIlValueType.MappedClass("${typeMapper.coreLibrary.reference}System.Attribute")
                } else {
                    irClass.dotNetBaseSuperTypeOrNull()?.let(typeMapper::toDotNetIlBaseClassType)
                }
                classInfo.interfaces = buildList {
                    addAll(irClass.dotNetDirectInterfaceTypes()
                        .mapNotNull(typeMapper::toDotNetIlImplementedInterfaceType))
                    // The semantic capability is a physical InterfaceImpl, not a second logical
                    // KLIB supertype. Reconstruct it solely from the producer ABI so ordinary
                    // constructed C<T> values are assignable to projected/star consumer slots.
                    genericOwnerCapabilityInfoOrNull(irClass)?.let { capability ->
                        add(DotNetIlValueType.UserClass(capability))
                    }
                }
            } finally {
                classLinksInProgress.remove(logicalKey)
            }
        }
        return classInfo
    }

    /** Producer-recorded non-generic Kotlin semantic/classifier capability for external `C<T>`. */
    fun genericOwnerCapabilityInfoOrNull(irClass: IrClass): DotNetIlClassInfo? {
        if (irClass.typeParameters.isEmpty()) return null
        val logicalKey = logicalKeys.keyOrNull(irClass, "C") ?: return null
        genericOwnerCapabilityInfoByLogicalKey[logicalKey]?.let { return it }
        val bound = declarations[logicalKey] ?: return null
        val declaration = bound.declaration as? DotNetPhysicalDeclaration.Class ?: return null
        val genericOwnerAbi = declaration.genericOwnerAbi ?: return null
        require(declaration.physicalTypeParameterCount == irClass.typeParameters.size) {
            "external Kotlin/.NET class '$logicalKey' publishes a generic-owner capability " +
                    "without the complete CLR owner arity"
        }
        val capabilityInfo = buildClassInfo(
            genericOwnerAbi.capabilityAssemblyName,
            genericOwnerAbi.capabilityOwnerPath,
            emptyList(),
        )
        genericOwnerCapabilityInfoByLogicalKey[logicalKey] = capabilityInfo
        if (irClass.isInterface) {
            capabilityInfo.interfaces = irClass.dotNetDirectInterfaceTypes().mapNotNull { parentType ->
                val parent = (parentType.classifier as? IrClassSymbol)?.owner ?: return@mapNotNull null
                genericOwnerCapabilityInfoOrNull(parent)?.takeUnless { parentCapability ->
                    parentCapability.assemblyName == capabilityInfo.assemblyName &&
                            parentCapability.physicalPathComponents() == capabilityInfo.physicalPathComponents()
                }?.let { parentCapability -> DotNetIlValueType.UserClass(parentCapability) }
            }.distinct()
        }
        return capabilityInfo
    }

    fun genericOwnerMemberFamilyOrNull(function: IrSimpleFunction): DotNetBoundGenericOwnerMemberFamily? {
        val logicalKey = logicalKeys.keyOrNull(function, "F") ?: return null
        return genericOwnerMemberFamiliesByLogicalKey[logicalKey]
    }

    fun genericOwnerFunctionCarrierOrNull(function: IrSimpleFunction): DotNetBoundGenericOwnerFunctionCarrier? {
        val logicalKey = logicalKeys.keyOrNull(function, "F") ?: return null
        return genericOwnerFunctionCarriersByLogicalKey[logicalKey]
    }

    fun genericOwnerFunctionInputEntryOrNull(
        function: IrSimpleFunction,
    ): DotNetBoundGenericOwnerFunctionInputEntry? {
        val logicalKey = logicalKeys.keyOrNull(function, "F") ?: return null
        return genericOwnerFunctionInputEntriesByLogicalKey[logicalKey]
    }

    fun genericOwnerFunctionInputEntryInfo(
        function: IrSimpleFunction,
        binding: DotNetBoundGenericOwnerFunctionInputEntry,
        typeMapper: DotNetIlTypeMapper,
    ): DotNetIlFunctionInfo {
        require(binding.library in libraries) {
            "generic-owner function input entry belongs to an unbound external library"
        }
        val entry = binding.entry
        val containingClass = (function.parent as? IrClass)?.let { classInfoOrNull(it, typeMapper) }
        val owner = if (containingClass?.physicalPathComponents() == entry.ownerPath) {
            containingClass
        } else {
            require(!entry.isInstance) {
                "external generic-owner function input entry is outside its containing CLR class"
            }
            facadeInfoByPhysicalIdentity.getOrPut(
                binding.library.artifact.assemblyName to entry.ownerPath
            ) {
                buildClassInfo(binding.library.artifact.assemblyName, entry.ownerPath, emptyList())
            }
        }
        val signature = function.dotNetSignature(typeMapper)
        require(signature.hasThis == entry.isInstance) {
            "external generic-owner function input entry has inconsistent CLR dispatch"
        }
        require(entry.objectParameterIndices.all { index ->
            signature.parameterTypes.getOrNull(index) == DotNetIlValueType.Object
        }) {
            "external generic-owner function input entry did not reconstruct its object parameters"
        }
        return DotNetIlFunctionInfo(owner, signature, entry.methodName)
    }

    fun genericOwnerPhysicalFunctionInfo(
        function: IrSimpleFunction,
        binding: DotNetBoundGenericOwnerPhysicalSlot,
        typeMapper: DotNetIlTypeMapper,
    ): DotNetIlFunctionInfo {
        require(binding.library in libraries) {
            "generic-owner capability binding belongs to an unbound external library"
        }
        val owner = buildClassInfo(
            binding.library.artifact.assemblyName,
            binding.ownerPath,
            List((function.parent as? IrClass)?.typeParameters?.size ?: 0) { Variance.INVARIANT }
                .takeIf { binding.ownerPath != binding.family.ownerPath }
                .orEmpty(),
        )
        val signature = function.dotNetSignature(typeMapper)
        require(signature.hasThis) { "a generic-owner physical slot must be an instance method" }
        return DotNetIlFunctionInfo(owner, signature, binding.physicalMethodName)
    }

    /** Kotlin-owned runtime classifiers do not have an alternate typed CLR owner. */
    fun declaredClassInfoOrNull(@Suppress("UNUSED_PARAMETER") irClass: IrClass): DotNetIlClassInfo? = null

    /** The arity-zero owner recorded for a Kotlin generic class that remains in the erased epoch. */
    fun genericClassInfoOrNull(irClass: IrClass, typeMapper: DotNetIlTypeMapper): DotNetGenericClassInfo? {
        if (irClass.isInterface || irClass.typeParameters.isEmpty()) return null
        if (!hasGenericClass(irClass)) return null
        return classInfoOrNull(irClass, typeMapper)?.let(::DotNetGenericClassInfo)
    }

    /** Kotlin-owned runtime classifiers do not have an alternate exact CLR owner. */
    fun exactClassInfoOrNull(@Suppress("UNUSED_PARAMETER") irClass: IrClass): DotNetIlClassInfo? = null

    fun staticInitializationOrNull(irClass: IrClass): DotNetBoundStaticInitialization? {
        val logicalKey = logicalKeys.keyOrNull(irClass, "C") ?: return null
        val bound = declarations[logicalKey] ?: return null
        val declaration = bound.declaration as? DotNetPhysicalDeclaration.Class ?: return null
        val initialization = declaration.staticInitialization ?: return null
        return DotNetBoundStaticInitialization(bound.library, declaration, initialization)
    }

    fun objectInstanceOrNull(irClass: IrClass): DotNetBoundObjectInstance? {
        val logicalKey = logicalKeys.keyOrNull(irClass, "C") ?: return null
        val bound = declarations[logicalKey] ?: return null
        val declaration = bound.declaration as? DotNetPhysicalDeclaration.Class ?: return null
        val objectInstance = declaration.objectInstance ?: return null
        return DotNetBoundObjectInstance(bound.library, declaration, objectInstance)
    }

    /** Binds a synthetic external value-class compiler-ABI stub to its producer MethodDef. */
    fun valueClassCompilerAbiFunctionInfoOrNull(
        function: IrSimpleFunction,
        typeMapper: DotNetIlTypeMapper,
    ): DotNetIlFunctionInfo? {
        val methodSelector: (DotNetValueClassAbi) -> String = when {
            function.origin == DOTNET_VALUE_CLASS_BOX_HELPER -> DotNetValueClassAbi::boxMethodName
            function.origin == DOTNET_VALUE_CLASS_UNBOX_HELPER -> DotNetValueClassAbi::unboxMethodName
            function.dotNetValueClassConstructorImplementationSourceOrNull() != null ->
                DotNetValueClassAbi::primaryConstructorMethodName
            else -> return null
        }
        val irClass = function.parent as? IrClass ?: return null
        val logicalKey = logicalKeys.keyOrNull(irClass, "C") ?: return null
        val bound = declarations[logicalKey] ?: return null
        val declaration = bound.declaration as? DotNetPhysicalDeclaration.Class ?: return null
        val valueClassAbi = declaration.valueClassAbi ?: return null
        val owner = classInfoOrNull(irClass, typeMapper) ?: return null
        val physicalTypeMapper = typeMapper.erasedGenericValueClassImplementationView(function)
        return DotNetIlFunctionInfo(
            owner = owner,
            signature = function.dotNetSignature(physicalTypeMapper),
            physicalMethodName = methodSelector(valueClassAbi),
        )
    }

    fun enumEntryOrNull(entry: IrEnumEntry): DotNetBoundEnumEntry? {
        val logicalKey = logicalKeys.keyOrNull(entry, "E") ?: return null
        val bound = declarations[logicalKey] ?: return null
        val enumEntry = bound.declaration as? DotNetPhysicalDeclaration.EnumEntry ?: return null
        return DotNetBoundEnumEntry(bound.library, enumEntry)
    }

    fun objectInstanceOwnerInfo(binding: DotNetBoundObjectInstance): DotNetIlClassInfo {
        require(binding.library in libraries) {
            "object-instance binding belongs to an unbound external library"
        }
        return facadeInfoByPhysicalIdentity.getOrPut(
            binding.library.artifact.assemblyName to binding.objectInstance.ownerPath
        ) {
            buildClassInfo(
                binding.library.artifact.assemblyName,
                binding.objectInstance.ownerPath,
                emptyList(),
            )
        }
    }

    fun functionInfoOrNull(
        function: IrSimpleFunction,
        typeMapper: DotNetIlTypeMapper,
    ): DotNetIlFunctionInfo? {
        // Common continuation lowering gives a deserialized suspend declaration a physical
        // `(args, Continuation) -> Any?` stub. Producer indexing records that MethodDef under the
        // original suspend KLIB identity; consumer lookup must select the same identity instead
        // of asking the binding index for the lowering-only stub.
        val logicalDeclaration = function.dotNetValueClassImplementationSourceOrNull()
            ?: function.suspendFunction
            ?: function
        val logicalKey = logicalKeys.keyOrNull(logicalDeclaration, "F") ?: return null
        val bound = declarations[logicalKey] ?: return null
        val declaration = bound.declaration as? DotNetPhysicalDeclaration.Function ?: return null
        val containingClass = (function.parent as? IrClass)?.let { classInfoOrNull(it, typeMapper) }
        val owner = if (containingClass?.physicalPathComponents() == declaration.ownerPath) {
            containingClass
        } else {
            require(!declaration.isInstance) {
                "external instance function '$logicalKey' is bound outside its containing CLR class"
            }
            facadeInfoByPhysicalIdentity.getOrPut(
                bound.library.artifact.assemblyName to declaration.ownerPath
            ) {
                buildClassInfo(bound.library.artifact.assemblyName, declaration.ownerPath, emptyList())
            }
        }
        val logicalSignature = function.dotNetSignature(
            typeMapper.erasedGenericValueClassImplementationView(function)
        )
        val carrier = genericOwnerFunctionCarriersByLogicalKey[logicalKey]
        val signature = carrier?.let { binding ->
            require(binding.library == bound.library) {
                "external function '$logicalKey' and its generic-owner carrier belong to different libraries"
            }
            require(binding.carrier.ownerPath == declaration.ownerPath) {
                "external function '$logicalKey' has a generic-owner carrier on a different CLR owner"
            }
            logicalSignature.withGenericOwnerFunctionCarrier(function, binding.carrier, typeMapper)
        } ?: logicalSignature
        require(signature.hasThis == declaration.isInstance) {
            "external function '$logicalKey' has a CLR dispatch shape inconsistent with its metadata"
        }
        return DotNetIlFunctionInfo(owner, signature, declaration.methodName)
    }

    private fun DotNetIlMethodSignature.withGenericOwnerFunctionCarrier(
        function: IrSimpleFunction,
        carrier: DotNetPhysicalDeclaration.GenericOwnerFunctionCarrier,
        typeMapper: DotNetIlTypeMapper,
    ): DotNetIlMethodSignature {
        val physicalParameters = parameterTypes.toMutableList()
        for (entry in carrier.parameterCarriers.entries) {
            val index = entry.key
            val carrierKind = entry.value
            val parameter = function.parameters.getOrNull(index)
                ?: error(
                    "external function '${carrier.logicalFunctionKey}' records missing generic-owner " +
                            "parameter index $index"
                )
            if (index !in physicalParameters.indices) {
                error(
                    "external function '${carrier.logicalFunctionKey}' has no physical parameter " +
                            "at recorded index $index"
                )
            }
            physicalParameters[index] = when (carrierKind) {
                DotNetGenericOwnerFunctionCarrierKind.SEMANTIC_CAPABILITY ->
                    typeMapper.genericOwnerSemanticCapabilityTypeOrNull(parameter.type)
                        ?: error(
                            "external function '${carrier.logicalFunctionKey}' records parameter $index as a " +
                                    "generic-owner capability without a producer capability"
                        )
                DotNetGenericOwnerFunctionCarrierKind.OBJECT -> {
                    require(typeMapper.genericOwnerSemanticCapabilityTypeOrNull(parameter.type) != null) {
                        "external function '${carrier.logicalFunctionKey}' records parameter $index as object " +
                                "without a producer generic-owner capability"
                    }
                    DotNetIlValueType.Object
                }
            }
        }
        val physicalReturn = when (carrier.returnCarrier) {
            null -> returnType
            DotNetGenericOwnerFunctionCarrierKind.SEMANTIC_CAPABILITY -> DotNetIlReturnType.Value(
                typeMapper.genericOwnerSemanticCapabilityTypeOrNull(function.returnType)
                    ?: error(
                        "external function '${carrier.logicalFunctionKey}' records its return as a " +
                                "generic-owner capability without a producer capability"
                    )
            )
            DotNetGenericOwnerFunctionCarrierKind.OBJECT -> {
                require(typeMapper.genericOwnerSemanticCapabilityTypeOrNull(function.returnType) != null) {
                    "external function '${carrier.logicalFunctionKey}' records its return as object without a " +
                            "producer generic-owner capability"
                }
                DotNetIlReturnType.Value(DotNetIlValueType.Object)
            }
        }
        return copy(returnType = physicalReturn, parameterTypes = physicalParameters)
    }

    fun interfaceDefaultImplementationOrNull(
        function: IrSimpleFunction,
    ): DotNetBoundInterfaceDefaultImplementation? {
        val logicalKey = logicalKeys.keyOrNull(function, "F") ?: return null
        val bound = declarations[logicalKey] ?: return null
        val declaration = bound.declaration as? DotNetPhysicalDeclaration.Function ?: return null
        val implementation = declaration.interfaceDefaultImplementation ?: return null
        return DotNetBoundInterfaceDefaultImplementation(bound.library, declaration, implementation)
    }

    fun defaultArgumentDispatcherOrNull(
        function: IrSimpleFunction,
    ): DotNetBoundDefaultArgumentDispatcher? {
        val logicalKey = logicalKeys.keyOrNull(function, "F") ?: return null
        val bound = declarations[logicalKey] ?: return null
        val declaration = bound.declaration as? DotNetPhysicalDeclaration.Function ?: return null
        val dispatcher = declaration.defaultArgumentDispatcher ?: return null
        return DotNetBoundDefaultArgumentDispatcher(bound.library, declaration, dispatcher)
    }

    fun interfaceDefaultPromotionOrNull(
        owner: IrClass,
        inheritedMember: IrSimpleFunction,
        physicalView: DotNetInterfaceDefaultPromotionView = DotNetInterfaceDefaultPromotionView.CANONICAL,
    ): DotNetBoundInterfaceDefaultPromotion? {
        val ownerLogicalKey = logicalKeys.keyOrNull(owner, "C") ?: return null
        val memberLogicalKey = logicalKeys.keyOrNull(inheritedMember, "F") ?: return null
        val indexKey = "P:$ownerLogicalKey:$memberLogicalKey:${physicalView.name}"
        val bound = declarations[indexKey] ?: return null
        val promotion = bound.declaration as? DotNetPhysicalDeclaration.InterfaceDefaultPromotion ?: return null
        require(promotion.ownerLogicalKey == ownerLogicalKey &&
                promotion.inheritedLogicalMemberKey == memberLogicalKey &&
                promotion.physicalView == physicalView) {
            "external interface-default promotion '$indexKey' is internally inconsistent"
        }
        return DotNetBoundInterfaceDefaultPromotion(bound.library, promotion)
    }

    fun genericInterfaceViewBridgeOrNull(
        owner: IrClass,
        inheritedMember: IrSimpleFunction,
        physicalView: DotNetInterfaceDefaultPromotionView,
    ): DotNetBoundGenericInterfaceViewBridge? {
        val ownerLogicalKey = logicalKeys.keyOrNull(owner, "C") ?: return null
        val memberLogicalKey = logicalKeys.keyOrNull(inheritedMember, "F") ?: return null
        val indexKey = "B:$ownerLogicalKey:$memberLogicalKey:${physicalView.name}"
        val bound = declarations[indexKey] ?: return null
        val bridge = bound.declaration as? DotNetPhysicalDeclaration.GenericInterfaceViewBridge ?: return null
        require(
            bridge.ownerLogicalKey == ownerLogicalKey &&
                    bridge.inheritedLogicalMemberKey == memberLogicalKey &&
                    bridge.physicalView == physicalView
        ) {
            "external generic-interface view bridge '$indexKey' is internally inconsistent"
        }
        return DotNetBoundGenericInterfaceViewBridge(bound.library, bridge)
    }

    fun covariantReturnBridgeOrNull(
        owner: IrClass,
        inheritedMember: IrSimpleFunction,
    ): DotNetBoundCovariantReturnBridge? {
        val ownerLogicalKey = logicalKeys.keyOrNull(owner, "C") ?: return null
        val memberLogicalKey = logicalKeys.keyOrNull(inheritedMember, "F") ?: return null
        val indexKey = "R:$ownerLogicalKey:$memberLogicalKey"
        val bound = declarations[indexKey] ?: return null
        val bridge = bound.declaration as? DotNetPhysicalDeclaration.CovariantReturnBridge ?: return null
        require(
            bridge.ownerLogicalKey == ownerLogicalKey &&
                    bridge.inheritedLogicalMemberKey == memberLogicalKey
        ) {
            "external covariant-return bridge '$indexKey' is internally inconsistent"
        }
        return DotNetBoundCovariantReturnBridge(bound.library, bridge)
    }

    fun interfaceDefaultClassForwarderOrNull(
        owner: IrClass,
        inheritedMember: IrSimpleFunction,
        physicalView: DotNetInterfaceDefaultPromotionView = DotNetInterfaceDefaultPromotionView.CANONICAL,
    ): DotNetBoundInterfaceDefaultClassForwarder? {
        val ownerLogicalKey = logicalKeys.keyOrNull(owner, "C") ?: return null
        val memberLogicalKey = logicalKeys.keyOrNull(inheritedMember, "F") ?: return null
        val indexKey = "W:$ownerLogicalKey:$memberLogicalKey:${physicalView.name}"
        val bound = declarations[indexKey] ?: return null
        val forwarder = bound.declaration as? DotNetPhysicalDeclaration.InterfaceDefaultClassForwarder ?: return null
        require(
            forwarder.ownerLogicalKey == ownerLogicalKey &&
                    forwarder.inheritedLogicalMemberKey == memberLogicalKey &&
                    forwarder.physicalView == physicalView
        ) {
            "external interface-default class forwarder '$indexKey' is internally inconsistent"
        }
        return DotNetBoundInterfaceDefaultClassForwarder(bound.library, forwarder)
    }

    fun interfaceDefaultHelperFunctionInfo(
        helper: IrSimpleFunction,
        binding: DotNetBoundInterfaceDefaultImplementation,
        typeMapper: DotNetIlTypeMapper,
    ): DotNetIlFunctionInfo {
        require(binding.library in libraries) {
            "interface-default helper binding belongs to an unbound external library"
        }
        val owner = buildClassInfo(
            binding.library.artifact.assemblyName,
            binding.implementation.helperOwnerPath,
            emptyList(),
        )
        val signature = helper.dotNetSignature(typeMapper)
        require(!signature.hasThis) { "an interface-default compatibility helper must be static" }
        return DotNetIlFunctionInfo(owner, signature, binding.implementation.helperMethodName)
    }

    fun defaultArgumentDispatcherFunctionInfo(
        helper: IrSimpleFunction,
        binding: DotNetBoundDefaultArgumentDispatcher,
        typeMapper: DotNetIlTypeMapper,
    ): DotNetIlFunctionInfo {
        require(binding.library in libraries) {
            "default-argument dispatcher binding belongs to an unbound external library"
        }
        val owner = buildClassInfo(
            binding.library.artifact.assemblyName,
            binding.dispatcher.ownerPath,
            emptyList(),
        )
        val signature = helper.dotNetSignature(typeMapper)
        require(!signature.hasThis) { "a default-argument dispatcher must be static" }
        return DotNetIlFunctionInfo(owner, signature, binding.dispatcher.methodName)
    }

    fun staticInitializationFunctionInfo(
        entry: IrSimpleFunction,
        binding: DotNetBoundStaticInitialization,
        typeMapper: DotNetIlTypeMapper,
    ): DotNetIlFunctionInfo {
        require(binding.library in libraries) {
            "static-initialization binding belongs to an unbound external library"
        }
        val owner = buildClassInfo(
            binding.library.artifact.assemblyName,
            binding.initialization.ownerPath,
            emptyList(),
        )
        val signature = entry.dotNetSignature(typeMapper)
        require(!signature.hasThis && entry.typeParameters.isEmpty()) {
            "a static-initialization entry must be a non-generic static method"
        }
        return DotNetIlFunctionInfo(owner, signature, binding.initialization.methodName)
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
    genericClasses: Map<IrClass, DotNetGenericClassInfo> = emptyMap(),
    currentAssemblyName: String,
    genericOwnerCapabilities: Map<IrClass, DotNetIlClassInfo> = emptyMap(),
    genericOwnerCapabilitySlots: Map<IrSimpleFunction, IrSimpleFunction> = emptyMap(),
    genericOwnerDefaultCapabilitySlots: Map<IrSimpleFunction, IrSimpleFunction> = emptyMap(),
    genericOwnerSemanticHooks: Map<IrSimpleFunction, IrSimpleFunction> = emptyMap(),
    genericOwnerFunctionInputEntries: Map<IrSimpleFunction, IrSimpleFunction> = emptyMap(),
    genericOwnerForeignOverrideProbeTargets: Map<IrSimpleFunction, IrSimpleFunction> = emptyMap(),
    preLoweringDeclarationKeys: Map<IrDeclaration, String> = emptyMap(),
    interfaceDefaultImplementations: Map<IrSimpleFunction, DotNetLoweredInterfaceDefaultImplementation> = emptyMap(),
    defaultArgumentDispatchers: Map<IrSimpleFunction, IrSimpleFunction> = emptyMap(),
    interfaceDefaultPromotions: List<DotNetLoweredInterfaceDefaultPromotion> = emptyList(),
    genericInterfaceViewBridges: List<DotNetLoweredGenericInterfaceViewBridge> = emptyList(),
    covariantReturnBridges: List<DotNetLoweredCovariantReturnBridge> = emptyList(),
    interfaceDefaultClassForwarders: List<DotNetLoweredInterfaceDefaultClassForwarder> = emptyList(),
    staticInitializations: Map<IrClass, DotNetLoweredStaticInitialization> = emptyMap(),
    objectInstanceFields: Map<IrClass, IrField> = emptyMap(),
    enumEntryFields: Map<IrEnumEntry, IrField> = emptyMap(),
    valueClassBoxingHelpers: Map<IrClass, DotNetValueClassBoxingHelpers> = emptyMap(),
    typeMapper: DotNetIlTypeMapper? = null,
): Map<String, DotNetPhysicalDeclaration> = buildMap {
    val signatureComputer = PublicIdSignatureComputer(DotNetIrMangler)
    val valueClassConstructorImplementations = availableFunctions.keys.mapNotNull { implementation ->
        implementation.dotNetValueClassConstructorImplementationSourceOrNull()?.let { source ->
            (source.parent as IrClass) to implementation
        }
    }.toMap()
    val compilerAbiFunctions = buildSet {
        interfaceDefaultImplementations.values.mapTo(this) { it.helper }
        defaultArgumentDispatchers.values.toCollection(this)
        staticInitializations.values.mapTo(this) { it.entry }
        valueClassBoxingHelpers.values.forEach { helpers ->
            add(helpers.box)
            add(helpers.unbox)
        }
        genericOwnerFunctionInputEntries.values.toCollection(this)
        valueClassConstructorImplementations.values.toCollection(this)
    }
    val genericOwnerForeignOverrideProbesBySource = buildMap {
        genericOwnerForeignOverrideProbeTargets.forEach { entry ->
            require(put(entry.value, entry.key) == null) {
                "one generic-owner source member produced multiple foreign-override probes"
            }
        }
    }
    val genericInterfaceViewBridgeFunctions =
        genericInterfaceViewBridges.mapTo(hashSetOf()) { it.implementation }
    val covariantReturnBridgeFunctions =
        covariantReturnBridges.mapTo(hashSetOf()) { it.implementation }
    val interfaceDefaultClassForwarderFunctions =
        interfaceDefaultClassForwarders.mapTo(hashSetOf()) { it.implementation }
    val compilerAbiClasses = compilerAbiFunctions
        .mapNotNullTo(hashSetOf()) { function -> function.parent as? IrClass }
        .filterTo(hashSetOf()) { irClass ->
            irClass.origin == DOTNET_DEFAULT_IMPLS || irClass.origin == DOTNET_STATIC_HOLDER
        }
    val valueClassImplementationSources = availableFunctions.keys.mapNotNull { implementation ->
        implementation.dotNetValueClassImplementationSourceOrNull()?.let { source -> implementation to source }
    }.toMap()
    val valueClassImplementedSources = valueClassImplementationSources.values.toHashSet()
    for (entry in availableClasses) {
        val irClass = entry.key
        val classInfo = entry.value
        if (irClass in compilerAbiClasses) continue
        if (irClass.fileOrNull !in files || irClass.isOriginallyLocalDeclaration) continue
        val logicalKey = preLoweringDeclarationKeys[irClass] ?: continue
        val genericInterface = genericInterfaces[irClass]
        val genericClass = genericClasses[irClass]
        val genericOwnerAbi = genericOwnerCapabilities[irClass]?.let { capabilityInfo ->
            require(capabilityInfo.typeParameterCount == 0) {
                "generic-owner capability for '${irClass.render()}' must be a non-generic CLR TypeDef"
            }
            DotNetGenericOwnerAbi(
                capabilityAssemblyName = capabilityInfo.assemblyName ?: currentAssemblyName,
                capabilityOwnerPath = capabilityInfo.physicalPathComponents(),
            )
        }
        val staticInitialization = staticInitializations[irClass]?.let { lowered ->
            val entryInfo = availableFunctions[lowered.entry]
                ?: error(
                    "Internal .NET backend error: static-initialization entry for " +
                            "'${irClass.render()}' did not survive physical emission"
                )
            DotNetStaticInitialization(
                ownerPath = entryInfo.owner.physicalPathComponents(),
                methodName = entryInfo.physicalMethodName ?: lowered.entry.dotNetIlMethodName(),
            )
        }
        val objectInstance = objectInstanceFields[irClass]?.let { field ->
            val physicalOwner = field.parent as? IrClass
                ?: error("Internal .NET backend error: object-instance field has no CLR class owner")
            val ownerInfo = availableClasses[physicalOwner]
                ?: error(
                    "Internal .NET backend error: object-instance owner for " +
                            "'${irClass.render()}' did not survive physical emission"
                )
            DotNetObjectInstance(
                ownerPath = ownerInfo.physicalPathComponents(),
                fieldName = field.name.asString(),
            )
        }
        val valueClassAbi = valueClassBoxingHelpers[irClass]?.let { helpers ->
            fun helperMethodName(helper: IrSimpleFunction, role: String): String {
                val helperInfo = availableFunctions[helper]
                    ?: error(
                        "Internal .NET backend error: value-class $role helper for " +
                                "'${irClass.render()}' did not survive physical emission"
                    )
                require(helperInfo.owner.physicalPathComponents() == classInfo.physicalPathComponents()) {
                    "value-class $role helper for '${irClass.render()}' has a CLR owner inconsistent with its class index"
                }
                return helperInfo.physicalMethodName ?: helper.dotNetIlMethodName()
            }
            val constructorImplementation = valueClassConstructorImplementations[irClass]
                ?: error(
                    "Internal .NET backend error: value-class primary constructor implementation for " +
                            "'${irClass.render()}' did not survive physical emission"
                )
            DotNetValueClassAbi(
                primaryConstructorMethodName = helperMethodName(constructorImplementation, "primary constructor"),
                boxMethodName = helperMethodName(helpers.box, "box"),
                unboxMethodName = helperMethodName(helpers.unbox, "unbox"),
            )
        }
        if (genericInterface == null) {
            require(classInfo.typeParameterCount == 0 ||
                    classInfo.typeParameterCount == irClass.typeParameters.size
            ) {
                "generic class '${irClass.render()}' has partial physical arity " +
                        "${classInfo.typeParameterCount}; expected 0 or ${irClass.typeParameters.size}"
            }
            genericClass?.let { erasedClass ->
                require(erasedClass.classInfo.physicalPathComponents() == classInfo.physicalPathComponents()) {
                    "generic class '${irClass.render()}' has an erased CLR owner inconsistent with its class index"
                }
            }
            put(
                logicalKey,
                DotNetPhysicalDeclaration.Class(
                    ownerPath = classInfo.physicalPathComponents(),
                    physicalTypeParameterCount = classInfo.typeParameterCount,
                    staticInitialization = staticInitialization,
                    objectInstance = objectInstance,
                    valueClassAbi = valueClassAbi,
                    genericOwnerAbi = genericOwnerAbi,
                )
            )
        } else {
            val canonicalOwnerPath = genericInterface.canonicalClassInfo.physicalPathComponents()
            require(genericInterface.canonicalClassInfo.typeParameterCount == 0) {
                "generic interface '${irClass.render()}' cannot publish a CLR-generic canonical owner"
            }
            require(canonicalOwnerPath == classInfo.physicalPathComponents()) {
                "generic interface '${irClass.render()}' has a canonical CLR owner inconsistent with its class index"
            }
            put(
                logicalKey,
                DotNetPhysicalDeclaration.Class(
                    ownerPath = canonicalOwnerPath,
                    physicalTypeParameterCount = genericInterface.canonicalClassInfo.typeParameterCount,
                    staticInitialization = staticInitialization,
                    objectInstance = objectInstance,
                    valueClassAbi = valueClassAbi,
                    genericOwnerAbi = null,
                )
            )
        }
    }
    for ([enumEntry, field] in enumEntryFields) {
        if (enumEntry.fileOrNull !in files) continue
        val logicalKey = preLoweringDeclarationKeys[enumEntry] ?: continue
        val physicalOwner = field.parent as? IrClass
            ?: error("Internal .NET backend error: enum-entry field has no CLR class owner")
        val ownerInfo = availableClasses[physicalOwner]
            ?: error(
                "Internal .NET backend error: enum-entry owner for " +
                        "'${enumEntry.render()}' did not survive physical emission"
            )
        put(
            logicalKey,
            DotNetPhysicalDeclaration.EnumEntry(
                ownerPath = ownerInfo.physicalPathComponents(),
                fieldName = field.name.asString(),
            )
        )
    }
    for (entry in availableFunctions) {
        val function = entry.key
        val functionInfo = entry.value
        if (function in compilerAbiFunctions || function in genericInterfaceViewBridgeFunctions ||
            function in covariantReturnBridgeFunctions ||
            function in interfaceDefaultClassForwarderFunctions
        ) {
            continue
        }
        if (function in valueClassImplementedSources) continue
        if (function.fileOrNull !in files || function.isOriginallyLocalDeclaration || function.isFakeOverride) continue
        val logicalDeclaration = valueClassImplementationSources[function] ?: function.suspendFunction ?: function
        val logicalKey = preLoweringDeclarationKeys[logicalDeclaration] ?: continue
        val interfaceDefaultImplementation = interfaceDefaultImplementations[function]?.let { lowered ->
            val helperInfo = availableFunctions[lowered.helper]
                ?: error(
                    "Internal .NET backend error: interface-default helper for '${function.render()}' " +
                            "did not survive physical emission"
                )
            DotNetInterfaceDefaultImplementation(
                bodyPlacement = lowered.bodyPlacement,
                helperOwnerPath = helperInfo.owner.physicalPathComponents(),
                helperMethodName = helperInfo.physicalMethodName ?: lowered.helper.dotNetIlMethodName(),
            )
        }
        val defaultArgumentDispatcher = defaultArgumentDispatchers[function]?.let { dispatcher ->
            val dispatcherInfo = availableFunctions[dispatcher]
                ?: error(
                    "Internal .NET backend error: default-argument dispatcher for '${function.render()}' " +
                            "did not survive physical emission"
                )
            DotNetDefaultArgumentDispatcher(
                ownerPath = dispatcherInfo.owner.physicalPathComponents(),
                methodName = dispatcherInfo.physicalMethodName ?: dispatcher.dotNetIlMethodName(),
            )
        }
        put(
            logicalKey,
            DotNetPhysicalDeclaration.Function(
                ownerPath = functionInfo.owner.physicalPathComponents(),
                methodName = functionInfo.physicalMethodName ?: function.dotNetIlMethodName(),
                isInstance = functionInfo.isInstance,
                interfaceDefaultImplementation = interfaceDefaultImplementation,
                defaultArgumentDispatcher = defaultArgumentDispatcher,
            )
        )
        typeMapper?.let { mapper ->
            val capabilityReturn = mapper.genericOwnerSemanticCapabilityTypeOrNull(function.returnType)
            val returnCarrier = when {
                mapper.isGenericOwnerForeignDispatchDeclaration(function) &&
                        functionInfo.signature.returnType == DotNetIlReturnType.Value(DotNetIlValueType.Object) ->
                    DotNetGenericOwnerFunctionCarrierKind.OBJECT
                mapper.isGenericOwnerCapabilityDeclaration(function) &&
                        functionInfo.signature.returnType == capabilityReturn?.let(DotNetIlReturnType::Value) ->
                    DotNetGenericOwnerFunctionCarrierKind.SEMANTIC_CAPABILITY
                else -> null
            }
            val parameterCarriers = function.parameters.mapIndexedNotNull { index, parameter ->
                val capability = mapper.genericOwnerSemanticCapabilityTypeOrNull(parameter.type)
                val carrier = when {
                    mapper.isGenericOwnerForeignDispatchDeclaration(parameter) &&
                            functionInfo.signature.parameterTypes.getOrNull(index) == DotNetIlValueType.Object ->
                        DotNetGenericOwnerFunctionCarrierKind.OBJECT
                    mapper.isGenericOwnerCapabilityDeclaration(parameter) &&
                            functionInfo.signature.parameterTypes.getOrNull(index) == capability ->
                        DotNetGenericOwnerFunctionCarrierKind.SEMANTIC_CAPABILITY
                    else -> null
                }
                carrier?.let { index to it }
            }.toMap()
            if (returnCarrier != null || parameterCarriers.isNotEmpty()) {
                val carrier = DotNetPhysicalDeclaration.GenericOwnerFunctionCarrier(
                    ownerPath = functionInfo.owner.physicalPathComponents(),
                    logicalFunctionKey = logicalKey,
                    returnCarrier = returnCarrier,
                    parameterCarriers = parameterCarriers,
                )
                put(carrier.indexKey(), carrier)
            }
        }
    }
    for ([source, inputEntry] in genericOwnerFunctionInputEntries) {
        if (source.fileOrNull !in files || source.isFakeOverride) continue
        val logicalFunctionKey = preLoweringDeclarationKeys[source] ?: continue
        val inputEntryInfo = availableFunctions[inputEntry]
            ?: error(
                "Internal .NET backend error: generic-owner function input entry for " +
                        "'${source.render()}' did not survive physical emission"
            )
        val mapper = checkNotNull(typeMapper) {
            "Internal .NET backend error: generic-owner function input entry requires a type mapper"
        }
        val objectParameterIndices = inputEntry.parameters.mapIndexedNotNull { index, parameter ->
            index.takeIf {
                mapper.isGenericOwnerForeignDispatchDeclaration(parameter) &&
                        inputEntryInfo.signature.parameterTypes.getOrNull(index) == DotNetIlValueType.Object
            }
        }.toSet()
        val physicalEntry = DotNetPhysicalDeclaration.GenericOwnerFunctionInputEntry(
            ownerPath = inputEntryInfo.owner.physicalPathComponents(),
            logicalFunctionKey = logicalFunctionKey,
            methodName = inputEntryInfo.physicalMethodName ?: inputEntry.dotNetIlMethodName(),
            isInstance = inputEntryInfo.isInstance,
            objectParameterIndices = objectParameterIndices,
        )
        put(physicalEntry.indexKey(), physicalEntry)
    }
    for ([source, slot] in genericOwnerCapabilitySlots) {
        if (source.fileOrNull !in files || source.isFakeOverride) continue
        val logicalMemberKey = preLoweringDeclarationKeys[source] ?: continue
        val owner = source.parent as? IrClass
            ?: error("Internal .NET backend error: generic-owner member has no class owner")
        val ownerLogicalKey = preLoweringDeclarationKeys[owner]
            ?: error("Internal .NET backend error: published generic-owner member has no owner identity")
        val slotInfo = availableFunctions[slot]
            ?: error(
                "Internal .NET backend error: generic-owner capability slot for " +
                        "'${source.render()}' did not survive physical emission"
            )
        val semanticHook = genericOwnerSemanticHooks[source]
        val semanticHookInfo = semanticHook?.let { hook ->
            availableFunctions[hook]
                ?: error(
                    "Internal .NET backend error: generic-owner semantic hook for " +
                            "'${source.render()}' did not survive physical emission"
                )
        }
        val foreignOverrideProbe = genericOwnerForeignOverrideProbesBySource[source]
        val foreignOverrideProbeInfo = foreignOverrideProbe?.let { probe ->
            availableFunctions[probe]
                ?: error(
                    "Internal .NET backend error: generic-owner foreign-override probe for " +
                            "'${source.render()}' did not survive physical emission"
                )
        }
        require(foreignOverrideProbeInfo == null ||
                foreignOverrideProbeInfo.owner.physicalPathComponents() ==
                semanticHookInfo?.owner?.physicalPathComponents()
        ) {
            "generic-owner semantic hook and foreign-override probe have inconsistent CLR owners"
        }
        val defaultCapabilityMethodName = genericOwnerDefaultCapabilitySlots[source]?.let { defaultSlot ->
            val defaultSlotInfo = availableFunctions[defaultSlot]
                ?: error(
                    "Internal .NET backend error: generic-owner default capability slot for " +
                            "'${source.render()}' did not survive physical emission"
                )
            require(defaultSlotInfo.owner.physicalPathComponents() == slotInfo.owner.physicalPathComponents()) {
                "generic-owner member and default capability slots have inconsistent CLR owners"
            }
            defaultSlotInfo.physicalMethodName ?: defaultSlot.dotNetIlMethodName()
        }
        val family = DotNetPhysicalDeclaration.GenericOwnerMemberFamily(
            ownerPath = slotInfo.owner.physicalPathComponents(),
            ownerLogicalKey = ownerLogicalKey,
            logicalMemberKey = logicalMemberKey,
            capabilityMethodName = slotInfo.physicalMethodName ?: slot.dotNetIlMethodName(),
            defaultCapabilityMethodName = defaultCapabilityMethodName,
            semanticHookOwnerPath = semanticHookInfo?.owner?.physicalPathComponents(),
            semanticHookMethodName = semanticHookInfo?.physicalMethodName
                ?: semanticHook?.dotNetIlMethodName(),
            foreignOverrideProbeMethodName = foreignOverrideProbeInfo?.physicalMethodName
                ?: foreignOverrideProbe?.dotNetIlMethodName(),
        )
        put(family.indexKey(), family)
    }
    for (promotion in interfaceDefaultPromotions) {
        val ownerInfo = availableClasses[promotion.owner] ?: continue
        val implementationInfo = availableFunctions[promotion.implementation]
            ?: error(
                "Internal .NET backend error: interface-default promotion implementation for " +
                        "'${promotion.inheritedMember.render()}' did not survive physical emission"
            )
        val ownerLogicalKey = preLoweringDeclarationKeys[promotion.owner]
            ?: promotion.owner.computeDotNetLibraryAbiKeyOrNull("C", signatureComputer)
            ?: error("Internal .NET backend error: interface-default promotion owner has no logical identity")
        val inheritedLogicalKey = promotion.inheritedMember.computeDotNetLibraryAbiKeyOrNull("F", signatureComputer)
            ?: error("Internal .NET backend error: promoted interface member has no logical identity")
        val inheritedFunction = promotion.inheritedDefault.function
        val declaration = DotNetPhysicalDeclaration.InterfaceDefaultPromotion(
            ownerPath = ownerInfo.physicalPathComponents(),
            ownerLogicalKey = ownerLogicalKey,
            inheritedLogicalMemberKey = inheritedLogicalKey,
            physicalView = promotion.physicalView,
            inheritedAssemblyName = promotion.inheritedDefault.library.artifact.assemblyName,
            inheritedOwnerPath = inheritedFunction.ownerPath,
            inheritedMethodName = inheritedFunction.methodName,
            implementationMethodName = implementationInfo.physicalMethodName
                ?: promotion.implementation.dotNetIlMethodName(),
        )
        put(declaration.indexKey(), declaration)
    }
    for (bridge in genericInterfaceViewBridges) {
        val ownerInfo = availableClasses[bridge.owner] ?: continue
        val implementationInfo = availableFunctions[bridge.implementation]
            ?: error(
                "Internal .NET backend error: generic-interface view bridge for " +
                        "'${bridge.inheritedMember.render()}' did not survive physical emission"
            )
        val ownerLogicalKey = preLoweringDeclarationKeys[bridge.owner]
            ?: bridge.owner.computeDotNetLibraryAbiKeyOrNull("C", signatureComputer)
            ?: error("Internal .NET backend error: generic-interface view bridge owner has no logical identity")
        val inheritedLogicalKey = bridge.inheritedMember.computeDotNetLibraryAbiKeyOrNull("F", signatureComputer)
            ?: error("Internal .NET backend error: bridged generic-interface member has no logical identity")
        val declaration = DotNetPhysicalDeclaration.GenericInterfaceViewBridge(
            ownerPath = ownerInfo.physicalPathComponents(),
            ownerLogicalKey = ownerLogicalKey,
            inheritedLogicalMemberKey = inheritedLogicalKey,
            physicalView = bridge.physicalView,
            implementationMethodName = implementationInfo.physicalMethodName
                ?: bridge.implementation.dotNetIlMethodName(),
        )
        put(declaration.indexKey(), declaration)
    }
    for (bridge in covariantReturnBridges) {
        val ownerInfo = availableClasses[bridge.owner] ?: continue
        val implementationInfo = availableFunctions[bridge.implementation]
            ?: error(
                "Internal .NET backend error: covariant-return bridge for " +
                        "'${bridge.inheritedMember.render()}' did not survive physical emission"
            )
        // A bridge on a file-private implementation is part of that DLL's internal virtual
        // layout, not a cross-module binding. Recomputing its process-local/file signature here
        // would leak an unstable owner identity into the self-describing library manifest.
        val ownerLogicalKey = preLoweringDeclarationKeys[bridge.owner] ?: continue
        val inheritedLogicalKey = bridge.inheritedMember.computeDotNetLibraryAbiKeyOrNull("F", signatureComputer)
            ?: continue
        val declaration = DotNetPhysicalDeclaration.CovariantReturnBridge(
            ownerPath = ownerInfo.physicalPathComponents(),
            ownerLogicalKey = ownerLogicalKey,
            inheritedLogicalMemberKey = inheritedLogicalKey,
            implementationMethodName = implementationInfo.physicalMethodName
                ?: bridge.implementation.dotNetIlMethodName(),
        )
        require(put(declaration.indexKey(), declaration) == null) {
            "multiple covariant-return bridges claim '${declaration.indexKey()}'"
        }
    }
    for (forwarder in interfaceDefaultClassForwarders) {
        val ownerInfo = availableClasses[forwarder.owner] ?: continue
        val implementationInfo = availableFunctions[forwarder.implementation]
            ?: error(
                "Internal .NET backend error: interface-default class forwarder for " +
                        "'${forwarder.inheritedMember.render()}' did not survive physical emission"
            )
        val ownerLogicalKey = preLoweringDeclarationKeys[forwarder.owner]
            ?: forwarder.owner.computeDotNetLibraryAbiKeyOrNull("C", signatureComputer)
            ?: continue
        val inheritedLogicalKey = forwarder.inheritedMember.computeDotNetLibraryAbiKeyOrNull("F", signatureComputer)
            ?: continue
        val declaration = DotNetPhysicalDeclaration.InterfaceDefaultClassForwarder(
            ownerPath = ownerInfo.physicalPathComponents(),
            ownerLogicalKey = ownerLogicalKey,
            inheritedLogicalMemberKey = inheritedLogicalKey,
            physicalView = forwarder.physicalView,
            implementationMethodName = implementationInfo.physicalMethodName
                ?: forwarder.implementation.dotNetIlMethodName(),
        )
        put(declaration.indexKey(), declaration)
    }
}
