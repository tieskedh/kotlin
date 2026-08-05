/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin.collections

/**
 * CLR actual of the Common mutable-collection skeleton.
 *
 * The algorithms follow the shared JS/Native/Wasm implementation. Kotlin collection identity and
 * iteration remain authoritative; no BCL collection participates in the implementation.
 */
public actual abstract class AbstractMutableCollection<E> protected actual constructor() :
    MutableCollection<E>, AbstractCollection<E>() {

    @IgnorableReturnValue
    actual override fun addAll(elements: Collection<E>): Boolean {
        var changed = false
        for (element in elements) {
            if (add(element)) changed = true
        }
        return changed
    }

    @IgnorableReturnValue
    actual override fun remove(element: E): Boolean {
        val iterator = iterator()
        while (iterator.hasNext()) {
            if (iterator.next() == element) {
                iterator.remove()
                return true
            }
        }
        return false
    }

    @IgnorableReturnValue
    actual override fun removeAll(elements: Collection<E>): Boolean =
        (this as MutableIterable<E>).removeAll { it in elements }

    @IgnorableReturnValue
    actual override fun retainAll(elements: Collection<E>): Boolean =
        (this as MutableIterable<E>).retainAll { it in elements }

    actual override fun clear() {
        val iterator = iterator()
        while (iterator.hasNext()) {
            iterator.next()
            iterator.remove()
        }
    }
}
