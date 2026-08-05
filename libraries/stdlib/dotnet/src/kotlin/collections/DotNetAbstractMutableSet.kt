/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin.collections

/** CLR actual of the Common mutable-set skeletal implementation. */
@SinceKotlin("1.1")
public actual abstract class AbstractMutableSet<E> protected actual constructor() :
    AbstractMutableCollection<E>(), MutableSet<E> {

    @IgnorableReturnValue
    actual abstract override fun add(element: E): Boolean

    override fun equals(other: Any?): Boolean {
        if (other === this) return true
        if (other !is Set<*>) return false
        return AbstractSet.setEquals(this, other)
    }

    override fun hashCode(): Int = AbstractSet.unorderedHashCode(this)
}
