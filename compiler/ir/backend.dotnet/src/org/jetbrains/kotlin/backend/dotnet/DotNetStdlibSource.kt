package org.jetbrains.kotlin.backend.dotnet

/**
 * Source texts of a fake Kotlin/.NET standard library that are injected as additional sources
 * during compilation, keyed by file name (one file per package, because a Kotlin file has a
 * single package directive).
 *
 * This is a temporary stand-in until a real .NET stdlib exists: the backend recognizes these
 * declarations and emits the corresponding CIL intrinsics, but the frontend still needs the
 * declarations to resolve against.
 */
val DOTNET_STDLIB_SOURCES: Map<String, String> = mapOf(
    "DotNetStdlibIo.kt" to """@file:Suppress("UNUSED_PARAMETER")
package kotlin.io

public fun println() {}

public fun println(message: String) {}

public fun println(message: Int) {}

public fun println(message: Long) {}

public fun println(message: Double) {}

public fun println(message: Char) {}

public fun println(message: Boolean) {}

public fun println(message: Any?) {}
""",
    "DotNetStdlibKotlin.kt" to """package kotlin

// The real stdlib's `Char.code` is an `@InlineOnly` extension property; it is declared here as
// a plain property because this backend does not run an IR inliner — the getter call reaches
// codegen and is intercepted by the intrinsic registry. The body is never emitted, but it must
// produce no diagnostics: test infrastructure maps every reported diagnostic back to a test
// file, and injected files have none (hence the suppressed `Char.toInt()` deprecation).
public val Char.code: Int
    @Suppress("DEPRECATION")
    get() = this.toInt()
""",
)
