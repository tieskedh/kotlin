/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin.collections

/** CLR actual of the Common mutable-map skeletal implementation. */
@SinceKotlin("1.1")
public actual abstract class AbstractMutableMap<K, V> protected actual constructor() :
    AbstractMap<K, V>(), MutableMap<K, V> {

    @IgnorableReturnValue
    actual abstract override fun put(key: K, value: V): V?

    actual override fun putAll(from: Map<out K, V>) {
        for (entry in from.entries) {
            put(entry.key, entry.value)
        }
    }

    @IgnorableReturnValue
    actual override fun remove(key: K): V? {
        val iterator = entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (key == entry.key) {
                val value = entry.value
                iterator.remove()
                return value
            }
        }
        return null
    }

    actual override fun clear() {
        entries.clear()
    }

    private var keysView: MutableSet<K>? = null
    actual override val keys: MutableSet<K>
        get() {
            val current = keysView
            if (current != null) return current
            val created = object : AbstractMutableSet<K>() {
                override val size: Int get() = this@AbstractMutableMap.size

                override fun add(element: K): Boolean =
                    throw UnsupportedOperationException("Add is not supported on keys")

                override fun clear() = this@AbstractMutableMap.clear()

                override fun contains(element: K): Boolean = containsKey(element)

                override fun iterator(): MutableIterator<K> {
                    val entryIterator = entries.iterator()
                    return object : MutableIterator<K> {
                        override fun hasNext(): Boolean = entryIterator.hasNext()
                        override fun next(): K = entryIterator.next().key
                        override fun remove() = entryIterator.remove()
                    }
                }

                override fun remove(element: K): Boolean {
                    if (!containsKey(element)) return false
                    this@AbstractMutableMap.remove(element)
                    return true
                }
            }
            keysView = created
            return created
        }

    private var valuesView: MutableCollection<V>? = null
    actual override val values: MutableCollection<V>
        get() {
            val current = valuesView
            if (current != null) return current
            val created = object : AbstractMutableCollection<V>() {
                override val size: Int get() = this@AbstractMutableMap.size

                override fun add(element: V): Boolean =
                    throw UnsupportedOperationException("Add is not supported on values")

                override fun clear() = this@AbstractMutableMap.clear()

                override fun contains(element: V): Boolean = containsValue(element)

                override fun iterator(): MutableIterator<V> {
                    val entryIterator = entries.iterator()
                    return object : MutableIterator<V> {
                        override fun hasNext(): Boolean = entryIterator.hasNext()
                        override fun next(): V = entryIterator.next().value
                        override fun remove() = entryIterator.remove()
                    }
                }
            }
            valuesView = created
            return created
        }
}
