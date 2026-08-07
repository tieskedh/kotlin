/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin.reflect

/** The Common callable floor plus Native-shaped logical return type and annotation discovery. */
public actual interface KCallable<out R> : KAnnotatedElement {
    @kotlin.internal.IntrinsicConstEvaluation
    public actual val name: String

    /** The KLIB/importer-IR return type of the reflected declaration, never its CLR erasure. */
    public val returnType: KType
}
