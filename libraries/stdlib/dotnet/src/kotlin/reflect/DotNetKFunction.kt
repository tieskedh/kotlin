/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin.reflect

/** A function reference whose declaration facts come from Kotlin metadata or imported declaration IR. */
public actual interface KFunction<out R> : KCallable<R>, Function<R> {
    /** `true` when the reflected Kotlin declaration is `inline`. */
    @SinceKotlin("1.1")
    public val isInline: Boolean

    /** `true` when the reflected declaration is implemented outside Kotlin. */
    @SinceKotlin("1.1")
    public val isExternal: Boolean

    /** `true` when the reflected declaration is an `operator` function. */
    @SinceKotlin("1.1")
    public val isOperator: Boolean

    /** `true` when the reflected declaration is an `infix` function. */
    @SinceKotlin("1.1")
    public val isInfix: Boolean

    /** `true` when the reflected declaration is a suspending function. */
    @SinceKotlin("1.1")
    public val isSuspend: Boolean
}
