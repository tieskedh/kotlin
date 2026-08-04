/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin

/** The reference-class actual consumed by the .NET enum lowering. */
public actual abstract class Enum<E : Enum<E>> actual constructor(
    @kotlin.internal.IntrinsicConstEvaluation
    public actual val name: String,
    public actual val ordinal: Int,
) : Comparable<E> {
    public actual final override fun compareTo(other: E): Int =
        ordinal.compareTo(other.ordinal)

    public actual final override fun equals(other: Any?): Boolean =
        this === other

    public actual final override fun hashCode(): Int =
        super.hashCode()

    public actual override fun toString(): String =
        name

    public actual companion object
}
