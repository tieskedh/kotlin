/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

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
    MSCORLIB(assemblyName = "mscorlib"),

    NETSTANDARD_2_0(
        assemblyName = "netstandard",
        assemblyVersionIl = "2:0:0:0",
        publicKeyTokenIl = "CC 7B 13 FF CD 2D DD 51",
        targetFrameworkMoniker = ".NETStandard,Version=v2.0",
    );

    val reference: String
        get() = "[$assemblyName]"

    fun appendAssemblyReferenceTo(builder: StringBuilder) {
        builder.appendLine(".assembly extern $assemblyName")
        if (assemblyVersionIl == null && publicKeyTokenIl == null) {
            builder.appendLine("{}")
            return
        }
        builder.appendLine("{")
        assemblyVersionIl?.let { builder.appendLine("  .ver $it") }
        publicKeyTokenIl?.let { builder.appendLine("  .publickeytoken = ($it)") }
        builder.appendLine("}")
    }

    /**
     * Emits the standard TargetFrameworkAttribute custom-attribute blob inside an `.assembly`.
     * The blob is the ECMA-335 serialization of the single constructor string followed by zero
     * named arguments. The netstandard reference itself owns the attribute type.
     */
    fun appendTargetFrameworkAttributeTo(builder: StringBuilder) {
        if (targetFrameworkMoniker == null) return
        require(this == NETSTANDARD_2_0) { "unsupported target-framework attribute profile $this" }
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
}

/** The default core library of executable/user IL until each runtime target gets its own profile. */
internal val DEFAULT_EXECUTABLE_CORE_LIBRARY = DotNetCoreLibraryProfile.MSCORLIB

/** The single portable API floor of Kotlin.Runtime and Kotlin.Stdlib. */
internal val DOTNET_PLATFORM_LIBRARY_CORE_LIBRARY = DotNetCoreLibraryProfile.NETSTANDARD_2_0
