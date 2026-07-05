package org.jetbrains.kotlin.backend.dotnet

/**
 * Source text of a fake Kotlin/.NET standard library that is injected as an additional source root
 * during compilation.
 *
 * This is a temporary stand-in until a real .NET stdlib exists: the backend recognizes these
 * declarations and emits the corresponding CIL intrinsics, but the frontend still needs the
 * declarations to resolve against.
 */
const val DOTNET_STDLIB_SOURCE = """@file:Suppress("UNUSED_PARAMETER")
package kotlin.io

public fun println() {}

public fun println(message: String) {}

public fun println(message: Int) {}

public fun println(message: Long) {}

public fun println(message: Double) {}

public fun println(message: Char) {}

public fun println(message: Boolean) {}

public fun println(message: Any?) {}
"""
