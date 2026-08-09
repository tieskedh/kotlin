/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.config.DotNetTarget
import org.jetbrains.kotlin.config.coreLibraryAssemblyName

/**
 * The CLR contract against which one emitted assembly's BCL references are compiled.
 *
 * Executable modules currently retain the core library selected by their actual runtime target.
 * Kotlin's platform libraries instead use the conservative .NET Standard 2.0 reference contract,
 * analogous to the JVM backend compiling a library against an API/release floor rather than
 * making that floor another execution engine.
 */
internal enum class DotNetCoreLibraryProfile(
    val assemblyName: String,
    private val assemblyVersionIl: String? = null,
    private val publicKeyTokenIl: String? = null,
    val targetFrameworkMoniker: String? = null,
) {
    NET48(
        assemblyName = DotNetTarget.NET48.coreLibraryAssemblyName,
        targetFrameworkMoniker = ".NETFramework,Version=v4.8",
    ),

    NETSTANDARD_2_0(
        assemblyName = DotNetTarget.NETSTANDARD_2_0.coreLibraryAssemblyName,
        assemblyVersionIl = "2:0:0:0",
        publicKeyTokenIl = "CC 7B 13 FF CD 2D DD 51",
        targetFrameworkMoniker = ".NETStandard,Version=v2.0",
    ),

    NET10_0(
        assemblyName = DotNetTarget.NET10_0.coreLibraryAssemblyName,
        targetFrameworkMoniker = ".NETCoreApp,Version=v10.0",
    );

    val reference: String
        get() = "[$assemblyName]"

    /** Assembly that physically owns EditorBrowsableAttribute for this reference contract. */
    val editorBrowsableReference: String
        get() = when (this) {
            NET48 -> "[System]"
            NETSTANDARD_2_0 -> reference
            NET10_0 -> "[System.Runtime]"
        }

    fun appendAssemblyReferenceTo(builder: StringBuilder) {
        if (assemblyVersionIl == null && publicKeyTokenIl == null) {
            builder.appendLine(".assembly extern $assemblyName {}")
            return
        }
        builder.appendLine(".assembly extern $assemblyName")
        builder.appendLine("{")
        assemblyVersionIl?.let { builder.appendLine("  .ver $it") }
        publicKeyTokenIl?.let { builder.appendLine("  .publickeytoken = ($it)") }
        builder.appendLine("}")
    }

    /** Adds the non-core Framework reference needed by compiler-ABI completion metadata. */
    fun appendEditorBrowsableAssemblyReferenceTo(builder: StringBuilder) {
        if (editorBrowsableReference == reference) return
        when (this) {
            NET48 -> {
                builder.appendLine(".assembly extern System")
                builder.appendLine("{")
                builder.appendLine("  .ver 4:0:0:0")
                builder.appendLine("  .publickeytoken = (B7 7A 5C 56 19 34 E0 89)")
                builder.appendLine("}")
            }
            NET10_0 -> {
                builder.appendLine(".assembly extern System.Runtime")
                builder.appendLine("{")
                builder.appendLine("  .ver 10:0:0:0")
                builder.appendLine("  .publickeytoken = (B0 3F 5F 7F 11 D5 0A 3A)")
                builder.appendLine("}")
            }
            NETSTANDARD_2_0 -> error("netstandard owns EditorBrowsableAttribute in its core facade")
        }
    }

    /** Adds the assembly that owns the admitted CodeAnalysis contract attributes. */
    fun appendCodeAnalysisAssemblyReferenceTo(builder: StringBuilder) {
        check(this == NET10_0) {
            "CodeAnalysis contract attributes are unavailable in the selected $name reference contract"
        }
        appendEditorBrowsableAssemblyReferenceTo(builder)
    }

    /**
     * Emits the standard TargetFrameworkAttribute custom-attribute blob inside an `.assembly`.
     * The blob is the ECMA-335 serialization of the single constructor string followed by zero
     * named arguments. The netstandard reference itself owns the attribute type.
     */
    fun appendTargetFrameworkAttributeTo(builder: StringBuilder) {
        if (targetFrameworkMoniker == null) return
        val monikerBytes = targetFrameworkMoniker.toByteArray(Charsets.UTF_8)
        require(monikerBytes.size < 0x80) { "target-framework moniker is too long for the one-byte blob encoding" }
        val blob = (listOf(0x01, 0x00, monikerBytes.size) +
                monikerBytes.map { it.toInt() and 0xff } +
                listOf(0x00, 0x00))
            .joinToString(" ") { byte -> byte.toString(16).padStart(2, '0') }
        builder.appendLine(
            "  .custom instance void ${reference}System.Runtime.Versioning.TargetFrameworkAttribute::.ctor(string) = (" +
                    "$blob)"
        )
    }

    /** Emits one standard AssemblyMetadataAttribute with two UTF-8 string arguments. */
    fun appendAssemblyMetadataAttributeTo(builder: StringBuilder, key: String, value: String) {
        val blob = (listOf(0x01, 0x00) +
                serializedCustomAttributeString(key) +
                serializedCustomAttributeString(value) +
                listOf(0x00, 0x00))
            .joinToString(" ") { byte -> byte.toString(16).padStart(2, '0') }
        builder.appendLine(
            "  .custom instance void ${reference}System.Reflection.AssemblyMetadataAttribute::.ctor(string, string) = (" +
                    "$blob)"
        )
    }

    /** Emits one producer-side CLR friend authorization inside the current `.assembly` block. */
    fun appendInternalsVisibleToAttributeTo(builder: StringBuilder, identity: DotNetFriendAssemblyIdentity) {
        val blob = (listOf(0x01, 0x00) +
                serializedCustomAttributeString(identity.displayName) +
                listOf(0x00, 0x00))
            .joinToString(" ") { byte -> byte.toString(16).padStart(2, '0') }
        builder.appendLine(
            "  .custom instance void ${reference}System.Runtime.CompilerServices.InternalsVisibleToAttribute::" +
                    ".ctor(string) = ($blob)"
        )
    }
}

/** Backend-owned mapping from a target-framework contract to textual CIL/BCL rendering. */
internal val DotNetTarget.coreLibrary: DotNetCoreLibraryProfile
    get() = when (this) {
        DotNetTarget.NET48 -> DotNetCoreLibraryProfile.NET48
        DotNetTarget.NETSTANDARD_2_0 -> DotNetCoreLibraryProfile.NETSTANDARD_2_0
        DotNetTarget.NET10_0 -> DotNetCoreLibraryProfile.NET10_0
    }

/** ECMA-335 SerString length prefix, including the multi-byte form needed by strong-name keys. */
private fun serializedCustomAttributeString(value: String): List<Int> {
    val bytes = value.toByteArray(Charsets.UTF_8).map { it.toInt() and 0xff }
    val size = bytes.size
    val length = when {
        size <= 0x7f -> listOf(size)
        size <= 0x3fff -> listOf(0x80 or (size shr 8), size and 0xff)
        size <= 0x1fffffff -> listOf(
            0xc0 or (size shr 24),
            (size shr 16) and 0xff,
            (size shr 8) and 0xff,
            size and 0xff,
        )
        else -> error("custom-attribute string is too large")
    }
    return length + bytes
}

/** Default used only by direct emitter tests and helpers that do not own compiler configuration. */
internal val DEFAULT_EXECUTABLE_CORE_LIBRARY = DotNetCoreLibraryProfile.NET48
