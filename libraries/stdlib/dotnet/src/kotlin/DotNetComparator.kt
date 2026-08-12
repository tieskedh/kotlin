/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin

/** The ordinary Kotlin-owned comparator interface for the .NET target. */
public actual fun interface Comparator<T> {
    public actual fun compare(a: T, b: T): Int
}
