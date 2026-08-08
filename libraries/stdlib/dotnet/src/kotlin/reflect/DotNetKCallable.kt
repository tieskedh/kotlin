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

    /** JVM-shaped Kotlin declaration visibility; `null` means that Kotlin cannot represent it. */
    public val visibility: KVisibility?

    /** `true` when the reflected Kotlin declaration has final modality. */
    public val isFinal: Boolean

    /** `true` when the reflected Kotlin declaration has open modality. */
    public val isOpen: Boolean

    /** `true` when the reflected Kotlin declaration has abstract modality. */
    public val isAbstract: Boolean

    /**
     * Calls this callable with positional arguments in [parameters] order.
     *
     * A vararg declaration parameter occupies one position and therefore expects its array as one
     * argument. Default values are deliberately not applied; [callBy] is the reflective operation
     * that may omit optional parameters.
     */
    public fun call(vararg args: Any?): R

    /**
     * Calls this callable with arguments keyed by [parameters] using [KParameter] equality.
     * Optional value parameters and varargs may be absent; explicit `null` remains a supplied
     * value.
     */
    public fun callBy(args: Map<KParameter, Any?>): R
}
