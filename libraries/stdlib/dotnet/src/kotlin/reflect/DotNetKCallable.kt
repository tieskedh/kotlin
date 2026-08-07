/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin.reflect

/** The Common callable floor plus declaration-owned logical signature and annotation discovery. */
public actual interface KCallable<out R> : KAnnotatedElement {
    @kotlin.internal.IntrinsicConstEvaluation
    public actual val name: String

    /** The KLIB/importer-IR return type of the reflected declaration, never its CLR erasure. */
    public val returnType: KType

    /** JVM-shaped declaration parameters on this exact bound or unbound callable object. */
    public val parameters: List<KParameter>

    /** Declaration-owned parameters; constructors expose their constructed class's own parameters. */
    public val typeParameters: List<KTypeParameter>
}
