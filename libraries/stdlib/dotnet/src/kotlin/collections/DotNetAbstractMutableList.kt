/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin.collections

/**
 * CLR actual of the Common mutable-list skeleton, derived from the shared JS/Native/Wasm
 * implementation. Its fail-fast state belongs to the one erased Kotlin object.
 */
public actual abstract class AbstractMutableList<E> protected actual constructor() :
    AbstractMutableCollection<E>(), MutableList<E> {

    protected actual var modCount: Int = 0

    abstract override fun add(index: Int, element: E)

    @IgnorableReturnValue
    abstract override fun removeAt(index: Int): E

    @IgnorableReturnValue
    abstract override fun set(index: Int, element: E): E

    @IgnorableReturnValue
    actual override fun add(element: E): Boolean {
        add(size, element)
        return true
    }

    @IgnorableReturnValue
    actual override fun addAll(index: Int, elements: Collection<E>): Boolean {
        AbstractList.checkPositionIndex(index, size)
        var insertionIndex = index
        var changed = false
        for (element in elements) {
            add(insertionIndex++, element)
            changed = true
        }
        return changed
    }

    actual override fun clear() {
        removeRange(0, size)
    }

    @IgnorableReturnValue
    actual override fun removeAll(elements: Collection<E>): Boolean = removeAll { it in elements }

    @IgnorableReturnValue
    actual override fun retainAll(elements: Collection<E>): Boolean = removeAll { it !in elements }

    actual override fun iterator(): MutableIterator<E> = IteratorImpl()

    actual override fun contains(element: E): Boolean = indexOf(element) >= 0

    actual override fun indexOf(element: E): Int = indexOfFirst { it == element }

    actual override fun lastIndexOf(element: E): Int = indexOfLast { it == element }

    actual override fun listIterator(): MutableListIterator<E> = listIterator(0)

    actual override fun listIterator(index: Int): MutableListIterator<E> = ListIteratorImpl(index)

    actual override fun subList(fromIndex: Int, toIndex: Int): MutableList<E> =
        SubList(this, fromIndex, toIndex)

    protected actual open fun removeRange(fromIndex: Int, toIndex: Int) {
        val iterator = listIterator(fromIndex)
        var remaining = toIndex - fromIndex
        while (remaining > 0) {
            iterator.next()
            iterator.remove()
            remaining--
        }
    }

    override fun equals(other: Any?): Boolean {
        if (other === this) return true
        if (other !is List<*>) return false
        return AbstractList.orderedEquals(this, other)
    }

    override fun hashCode(): Int = AbstractList.orderedHashCode(this)

    private open inner class IteratorImpl : MutableIterator<E> {
        protected var index = 0
        protected var last = -1
        protected var expectedModCount = modCount

        override fun hasNext(): Boolean = index < size

        override fun next(): E {
            checkForComodification()
            if (!hasNext()) throw NoSuchElementException()
            last = index++
            return get(last)
        }

        override fun remove() {
            checkForComodification()
            check(last != -1) { "Call next() or previous() before removing element from the iterator." }
            removeAt(last)
            index = last
            last = -1
            expectedModCount = modCount
        }

        protected fun checkForComodification() {
            if (modCount != expectedModCount) throw ConcurrentModificationException()
        }
    }

    private inner class ListIteratorImpl(index: Int) : IteratorImpl(), MutableListIterator<E> {
        init {
            AbstractList.checkPositionIndex(index, this@AbstractMutableList.size)
            this.index = index
        }

        override fun hasPrevious(): Boolean = index > 0

        override fun nextIndex(): Int = index

        override fun previous(): E {
            checkForComodification()
            if (!hasPrevious()) throw NoSuchElementException()
            last = --index
            return get(last)
        }

        override fun previousIndex(): Int = index - 1

        override fun add(element: E) {
            checkForComodification()
            add(index, element)
            index++
            last = -1
            expectedModCount = modCount
        }

        override fun set(element: E) {
            checkForComodification()
            check(last != -1) { "Call next() or previous() before updating element value with the iterator." }
            this@AbstractMutableList[last] = element
            expectedModCount = modCount
        }
    }

    private class SubList<E>(
        private val list: AbstractMutableList<E>,
        private val fromIndex: Int,
        toIndex: Int,
    ) : AbstractMutableList<E>(), RandomAccess {
        private var subListSize: Int = 0

        init {
            AbstractList.checkRangeIndexes(fromIndex, toIndex, list.size)
            subListSize = toIndex - fromIndex
            modCount = list.modCount
        }

        override fun add(index: Int, element: E) {
            checkForComodification()
            AbstractList.checkPositionIndex(index, subListSize)
            list.add(fromIndex + index, element)
            subListSize++
            modCount = list.modCount
        }

        override fun get(index: Int): E {
            checkForComodification()
            AbstractList.checkElementIndex(index, subListSize)
            return list[fromIndex + index]
        }

        override fun removeAt(index: Int): E {
            checkForComodification()
            AbstractList.checkElementIndex(index, subListSize)
            val result = list.removeAt(fromIndex + index)
            subListSize--
            modCount = list.modCount
            return result
        }

        override fun set(index: Int, element: E): E {
            checkForComodification()
            AbstractList.checkElementIndex(index, subListSize)
            return list.set(fromIndex + index, element)
        }

        override fun removeRange(fromIndex: Int, toIndex: Int) {
            checkForComodification()
            list.removeRange(this.fromIndex + fromIndex, this.fromIndex + toIndex)
            subListSize -= toIndex - fromIndex
            modCount = list.modCount
        }

        override val size: Int
            get() {
                checkForComodification()
                return subListSize
            }

        override fun iterator(): MutableIterator<E> {
            checkForComodification()
            return super.iterator()
        }

        override fun listIterator(index: Int): MutableListIterator<E> {
            checkForComodification()
            return super.listIterator(index)
        }

        private fun checkForComodification() {
            if (list.modCount != modCount) throw ConcurrentModificationException()
        }
    }
}
