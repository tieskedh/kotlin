/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin

// Common owns the expect declaration. This is the same overflow-free Int comparison used by the
// generated JVM, JS, Wasm, and Native actuals; no CLR-specific ordering rule is substituted.
@SinceKotlin("1.1")
@kotlin.internal.InlineOnly
public actual inline fun minOf(a: Int, b: Int): Int = if (a <= b) a else b
