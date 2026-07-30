/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:Suppress("UNUSED_PARAMETER")

package kotlin.io

// Target-local inert marker, matching the JS/Native/Wasm actual rather than claiming the BCL's
// serialization protocol. It is internal Kotlin API and exists only so common implementations
// retain their source-level marker relationship.
internal actual interface Serializable

public actual fun println() {}

public fun println(message: String) {}

public fun println(message: Int) {}

public fun println(message: Long) {}

public fun println(message: Double) {}

public fun println(message: Char) {}

public fun println(message: Boolean) {}

public actual fun println(message: Any?) {}

public actual fun print(message: Any?) {}

@SinceKotlin("1.6")
public actual fun readln(): String =
    readlnOrNull() ?: throw ReadAfterEOFException("EOF has already been reached")

@SinceKotlin("1.6")
public actual fun readlnOrNull(): String? = dotNetReadLine()

// The public functions remain ordinary Kotlin.Stdlib implementations. Only this irreducible CLR
// operation is intrinsic, matching the JVM/WASI split between the Kotlin EOF policy and host I/O.
private external fun dotNetReadLine(): String?
