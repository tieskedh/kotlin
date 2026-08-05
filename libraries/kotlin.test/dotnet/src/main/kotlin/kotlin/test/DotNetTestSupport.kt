/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin.test

/** CLR marker used by the first dependency-closed upstream stdlib test product. */
public actual annotation class Test

/**
 * The staged .NET kotlin.test product has no external framework adapter yet. Common assertion
 * bodies therefore use the authoritative Common [DefaultAsserter].
 */
public val asserter: Asserter
    get() = DefaultAsserter

internal actual fun AssertionErrorWithCause(message: String?, cause: Throwable?): AssertionError =
    AssertionError(message, cause)
