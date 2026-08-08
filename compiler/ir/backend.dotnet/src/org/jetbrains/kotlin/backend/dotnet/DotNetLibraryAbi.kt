/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.backend.dotnet.serialization.DotNetIrMangler
import org.jetbrains.kotlin.backend.common.serialization.signature.PublicIdSignatureComputer
import org.jetbrains.kotlin.backend.dotnet.lower.DOTNET_STATIC_HOLDER
import org.jetbrains.kotlin.backend.dotnet.lower.DOTNET_DEFAULT_IMPLS
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithVisibility
import org.jetbrains.kotlin.ir.declarations.IrEnumEntry
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
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
     * KLIB alone retains logical type parameters for Kotlin-owned generic classes and interfaces.
     * Typed foreign-language exports are separate artifacts and are never alternate owner paths
     * in this runtime index.
     */
    data class Class(
        override val ownerPath: List<String>,
        val staticInitialization: DotNetStaticInitialization? = null,
        val objectInstance: DotNetObjectInstance? = null,
    ) : DotNetPhysicalDeclaration

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

    /** A final interface MethodImpl adapting one inherited split-generic physical view. */
    data class GenericInterfaceViewBridge(
        override val ownerPath: List<String>,
        val ownerLogicalKey: String,
        val inheritedLogicalMemberKey: String,
        val physicalView: DotNetInterfaceDefaultPromotionView,
        val implementationMethodName: String,
    ) : DotNetPhysicalDeclaration {
        init {
            require(ownerPath.isNotEmpty()) { "a generic-interface view bridge requires an owning CLR interface" }
            require(ownerLogicalKey.isNotEmpty()) {
                "a generic-interface view bridge requires an owning logical interface"
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
    const val ABI_VERSION = "25"
    const val ABI_VERSION_PROPERTY = "dotnet_abi_version"
    const val LOGICAL_IDENTITY_SCHEME = "kotlin-public-id-signature-legacy-v1"
    const val LOGICAL_IDENTITY_SCHEME_PROPERTY = "dotnet_logical_identity_scheme"
    const val PHYSICAL_NAME_GRAMMAR_VERSION = "3"
    const val PHYSICAL_NAME_GRAMMAR_VERSION_PROPERTY = "dotnet_physical_name_grammar_version"
    const val CURRENT_RUNTIME_SURFACE_LEVEL = 26
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
                is DotNetPhysicalDeclaration.EnumEntry -> declaration.encodeFields()
                is DotNetPhysicalDeclaration.InterfaceDefaultPromotion -> declaration.encodeFields()
                is DotNetPhysicalDeclaration.GenericInterfaceViewBridge -> declaration.encodeFields()
                is DotNetPhysicalDeclaration.GenericInterfaceIntersectionSlot ->
                    error("Typed generic-interface intersection slots were removed in ABI 19")
                is DotNetPhysicalDeclaration.CovariantReturnBridge -> declaration.encodeFields()
                is DotNetPhysicalDeclaration.InterfaceDefaultClassForwarder -> declaration.encodeFields()
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
                "E" -> decodeEnumEntry(fields, logicalKey)
                "FD" -> decodeInterfaceDefaultFunction(fields, logicalKey)
                "P" -> decodeInterfaceDefaultPromotion(fields, logicalKey)
                "B" -> decodeGenericInterfaceViewBridge(fields, logicalKey)
                "I" -> throw IllegalArgumentException(
                    "declaration '$logicalKey' uses a removed typed generic-interface intersection slot"
                )
                "R" -> decodeCovariantReturnBridge(fields, logicalKey)
                "W" -> decodeInterfaceDefaultClassForwarder(fields, logicalKey)
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
        return listOf(
            "C",
            ownerPath.size.toString(),
            initializationPath.size.toString(),
            staticInitialization?.methodName.orEmpty(),
            objectInstancePath.size.toString(),
            objectInstance?.fieldName.orEmpty(),
        ) + ownerPath + initializationPath + objectInstancePath
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
        require(fields.size >= 7) {
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
        val initializationSize = pathSize(2, "static-initialization", allowAbsent = true)
        val objectInstanceSize = pathSize(4, "object-instance", allowAbsent = true)
        val expectedSize = 6 + ownerSize + initializationSize + objectInstanceSize
        require(fields.size == expectedSize) {
            "class declaration '$logicalKey' has an inconsistent CLR owner-path payload"
        }
        require((initializationSize == 0) == fields[3].isEmpty()) {
            "class declaration '$logicalKey' has an inconsistent static-initialization identity"
        }
        require((objectInstanceSize == 0) == fields[5].isEmpty()) {
            "class declaration '$logicalKey' has an inconsistent object-instance identity"
        }
        var offset = 6
        fun takePath(size: Int): List<String> = fields.subList(offset, offset + size).also { offset += size }
        val ownerPath = takePath(ownerSize).requireOwnerPath(logicalKey, "runtime")
        val initialization = if (initializationSize == 0) {
            null
        } else {
            DotNetStaticInitialization(
                ownerPath = takePath(initializationSize).requireOwnerPath(logicalKey, "static-initialization"),
                methodName = fields[3].requireMethodName(logicalKey, "static-initialization"),
            )
        }
        val objectInstance = if (objectInstanceSize == 0) {
            null
        } else {
            DotNetObjectInstance(
                ownerPath = takePath(objectInstanceSize).requireOwnerPath(logicalKey, "object-instance"),
                fieldName = fields[5].requireFieldName(logicalKey, "object-instance"),
            )
        }
        return DotNetPhysicalDeclaration.Class(
            ownerPath = ownerPath,
            staticInitialization = initialization,
            objectInstance = objectInstance,
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

    /**
     * Whether [irClass] has a producer-recorded physical CLR class in a bound library.
     *
     * Shape gates that merely authorize an external base or interface test declaration
     * membership here. They must not infer an optional host capability from the Kotlin
     * declaration: Kotlin-owned generic classes and interfaces both record one erased owner.
     */
    fun hasClass(irClass: IrClass): Boolean {
        val logicalKey = irClass.computeDotNetLibraryAbiKeyOrNull("C", signatureComputer) ?: return false
        return declarations[logicalKey]?.declaration is DotNetPhysicalDeclaration.Class
    }

    /** Whether KLIB kind plus the producer index select an erased Kotlin-owned generic class. */
    fun hasGenericClass(irClass: IrClass): Boolean {
        if (irClass.isInterface || irClass.typeParameters.isEmpty()) return false
        val logicalKey = irClass.computeDotNetLibraryAbiKeyOrNull("C", signatureComputer) ?: return false
        return declarations[logicalKey]?.declaration is DotNetPhysicalDeclaration.Class
    }

    /** Whether KLIB kind plus the producer index select an erased Kotlin-owned generic interface. */
    fun hasGenericInterface(irClass: IrClass): Boolean {
        if (!irClass.isInterface || irClass.typeParameters.isEmpty()) return false
        val logicalKey = irClass.computeDotNetLibraryAbiKeyOrNull("C", signatureComputer) ?: return false
        return declarations[logicalKey]?.declaration is DotNetPhysicalDeclaration.Class
    }

    fun classInfoOrNull(irClass: IrClass, typeMapper: DotNetIlTypeMapper): DotNetIlClassInfo? {
        val logicalKey = irClass.computeDotNetLibraryAbiKeyOrNull("C", signatureComputer) ?: return null
        canonicalClassInfoByLogicalKey[logicalKey]?.let { return it }
        val bound = declarations[logicalKey] ?: return null
        val declaration = bound.declaration as? DotNetPhysicalDeclaration.Class ?: return null
        val canonicalVariances = if (irClass.typeParameters.isNotEmpty()) {
            emptyList()
        } else {
            irClass.typeParameters.map { it.variance }
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
                    irClass.dotNetBaseSuperTypeOrNull()?.let(typeMapper::toDotNetIlValueType)
                }
                classInfo.interfaces = irClass.dotNetDirectInterfaceTypes()
                    .mapNotNull(typeMapper::toDotNetIlImplementedInterfaceType)
            } finally {
                classLinksInProgress.remove(logicalKey)
            }
        }
        return classInfo
    }

    /** Kotlin-owned runtime classifiers do not have an alternate typed CLR owner. */
    fun declaredClassInfoOrNull(@Suppress("UNUSED_PARAMETER") irClass: IrClass): DotNetIlClassInfo? = null

    /** The single erased owner recorded for an ordinary Kotlin generic class. */
    fun genericClassInfoOrNull(irClass: IrClass, typeMapper: DotNetIlTypeMapper): DotNetGenericClassInfo? {
        if (irClass.isInterface || irClass.typeParameters.isEmpty()) return null
        if (!hasGenericClass(irClass)) return null
        return classInfoOrNull(irClass, typeMapper)?.let(::DotNetGenericClassInfo)
    }

    /** Kotlin-owned runtime classifiers do not have an alternate exact CLR owner. */
    fun exactClassInfoOrNull(@Suppress("UNUSED_PARAMETER") irClass: IrClass): DotNetIlClassInfo? = null

    fun staticInitializationOrNull(irClass: IrClass): DotNetBoundStaticInitialization? {
        val logicalKey = irClass.computeDotNetLibraryAbiKeyOrNull("C", signatureComputer) ?: return null
        val bound = declarations[logicalKey] ?: return null
        val declaration = bound.declaration as? DotNetPhysicalDeclaration.Class ?: return null
        val initialization = declaration.staticInitialization ?: return null
        return DotNetBoundStaticInitialization(bound.library, declaration, initialization)
    }

    fun objectInstanceOrNull(irClass: IrClass): DotNetBoundObjectInstance? {
        val logicalKey = irClass.computeDotNetLibraryAbiKeyOrNull("C", signatureComputer) ?: return null
        val bound = declarations[logicalKey] ?: return null
        val declaration = bound.declaration as? DotNetPhysicalDeclaration.Class ?: return null
        val objectInstance = declaration.objectInstance ?: return null
        return DotNetBoundObjectInstance(bound.library, declaration, objectInstance)
    }

    fun enumEntryOrNull(entry: IrEnumEntry): DotNetBoundEnumEntry? {
        val logicalKey = entry.computeDotNetLibraryAbiKeyOrNull("E", signatureComputer) ?: return null
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
        val logicalKey = function.computeDotNetLibraryAbiKeyOrNull("F", signatureComputer) ?: return null
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
        val signature = function.dotNetSignature(typeMapper)
        require(signature.hasThis == declaration.isInstance) {
            "external function '$logicalKey' has a CLR dispatch shape inconsistent with its metadata"
        }
        return DotNetIlFunctionInfo(owner, signature, declaration.methodName)
    }

    fun interfaceDefaultImplementationOrNull(
        function: IrSimpleFunction,
    ): DotNetBoundInterfaceDefaultImplementation? {
        val logicalKey = function.computeDotNetLibraryAbiKeyOrNull("F", signatureComputer) ?: return null
        val bound = declarations[logicalKey] ?: return null
        val declaration = bound.declaration as? DotNetPhysicalDeclaration.Function ?: return null
        val implementation = declaration.interfaceDefaultImplementation ?: return null
        return DotNetBoundInterfaceDefaultImplementation(bound.library, declaration, implementation)
    }

    fun defaultArgumentDispatcherOrNull(
        function: IrSimpleFunction,
    ): DotNetBoundDefaultArgumentDispatcher? {
        val logicalKey = function.computeDotNetLibraryAbiKeyOrNull("F", signatureComputer) ?: return null
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
        val ownerLogicalKey = owner.computeDotNetLibraryAbiKeyOrNull("C", signatureComputer) ?: return null
        val memberLogicalKey = inheritedMember.computeDotNetLibraryAbiKeyOrNull("F", signatureComputer) ?: return null
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
        val ownerLogicalKey = owner.computeDotNetLibraryAbiKeyOrNull("C", signatureComputer) ?: return null
        val memberLogicalKey = inheritedMember.computeDotNetLibraryAbiKeyOrNull("F", signatureComputer) ?: return null
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
        val ownerLogicalKey = owner.computeDotNetLibraryAbiKeyOrNull("C", signatureComputer) ?: return null
        val memberLogicalKey = inheritedMember.computeDotNetLibraryAbiKeyOrNull("F", signatureComputer) ?: return null
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
        val ownerLogicalKey = owner.computeDotNetLibraryAbiKeyOrNull("C", signatureComputer) ?: return null
        val memberLogicalKey = inheritedMember.computeDotNetLibraryAbiKeyOrNull("F", signatureComputer) ?: return null
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
    typeMapper: DotNetIlTypeMapper? = null,
): Map<String, DotNetPhysicalDeclaration> = buildMap {
    val signatureComputer = PublicIdSignatureComputer(DotNetIrMangler)
    val compilerAbiFunctions = buildSet {
        interfaceDefaultImplementations.values.mapTo(this) { it.helper }
        defaultArgumentDispatchers.values.toCollection(this)
        staticInitializations.values.mapTo(this) { it.entry }
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
    for (entry in availableClasses) {
        val irClass = entry.key
        val classInfo = entry.value
        if (irClass in compilerAbiClasses) continue
        if (irClass.fileOrNull !in files || irClass.isOriginallyLocalDeclaration) continue
        val logicalKey = preLoweringDeclarationKeys[irClass] ?: continue
        val genericInterface = genericInterfaces[irClass]
        val genericClass = genericClasses[irClass]
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
        if (genericInterface == null) {
            genericClass?.let { erasedClass ->
                require(erasedClass.classInfo.physicalPathComponents() == classInfo.physicalPathComponents()) {
                    "generic class '${irClass.render()}' has an erased CLR owner inconsistent with its class index"
                }
            }
            put(
                logicalKey,
                DotNetPhysicalDeclaration.Class(
                    ownerPath = classInfo.physicalPathComponents(),
                    staticInitialization = staticInitialization,
                    objectInstance = objectInstance,
                )
            )
        } else {
            val canonicalOwnerPath = genericInterface.canonicalClassInfo.physicalPathComponents()
            require(canonicalOwnerPath == classInfo.physicalPathComponents()) {
                "generic interface '${irClass.render()}' has a canonical CLR owner inconsistent with its class index"
            }
            put(
                logicalKey,
                DotNetPhysicalDeclaration.Class(
                    ownerPath = canonicalOwnerPath,
                    staticInitialization = staticInitialization,
                    objectInstance = objectInstance,
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
        if (function.fileOrNull !in files || function.isOriginallyLocalDeclaration || function.isFakeOverride) continue
        val logicalKey = preLoweringDeclarationKeys[function] ?: continue
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
