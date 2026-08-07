/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin.reflect

/** The Common callable-name contract plus truthful declaration-owned annotation discovery. */
public actual interface KCallable<out R> : KAnnotatedElement {
    @kotlin.internal.IntrinsicConstEvaluation
    public actual val name: String
}
