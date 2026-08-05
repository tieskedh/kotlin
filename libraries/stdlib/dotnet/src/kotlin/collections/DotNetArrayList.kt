/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin.collections

/**
 * CLR actual of Common [ArrayList].
 *
 * The algorithm follows the Native/Wasm implementation. The private backing vector is deliberately
 * `Array<Any?>`: a Kotlin-owned erased class has no CLR `E` token, and introducing a generic
 * physical owner or a second typed state would violate its authoritative erased identity.
 */
public actual class ArrayList<E> public actual constructor(initialCapacity: Int) :
    AbstractMutableList<E>(), RandomAccess {

    private var backing: Array<Any?> = objectArray(initialCapacity)
    private var length = 0
    private var isReadOnly = false

    private companion object {
        private val Empty = ArrayList<Nothing>(0).also { it.isReadOnly = true }
    }

    public actual constructor() : this(10)

    public actual constructor(elements: Collection<E>) : this(elements.size) {
        addAll(elements)
    }

    @PublishedApi
    internal fun build(): List<E> {
        checkIsMutable()
        isReadOnly = true
        return if (length > 0) this else Empty
    }

    actual override val size: Int
        get() = length

    actual override fun isEmpty(): Boolean = length == 0

    actual override fun get(index: Int): E {
        AbstractList.checkElementIndex(index, length)
        return elementAt(index)
    }

    @IgnorableReturnValue
    actual override fun set(index: Int, element: E): E {
        checkIsMutable()
        AbstractList.checkElementIndex(index, length)
        val old = elementAt(index)
        backing[index] = element
        return old
    }

    actual override fun indexOf(element: E): Int {
        var index = 0
        while (index < length) {
            if (backing[index] == element) return index
            index++
        }
        return -1
    }

    actual override fun lastIndexOf(element: E): Int {
        var index = length - 1
        while (index >= 0) {
            if (backing[index] == element) return index
            index--
        }
        return -1
    }

    actual override fun iterator(): MutableIterator<E> = listIterator(0)

    actual override fun listIterator(): MutableListIterator<E> = listIterator(0)

    actual override fun listIterator(index: Int): MutableListIterator<E> {
        AbstractList.checkPositionIndex(index, length)
        return Itr(this, index)
    }

    @IgnorableReturnValue
    actual override fun add(element: E): Boolean {
        checkIsMutable()
        addAtInternal(length, element)
        return true
    }

    actual override fun add(index: Int, element: E) {
        checkIsMutable()
        AbstractList.checkPositionIndex(index, length)
        addAtInternal(index, element)
    }

    @IgnorableReturnValue
    actual override fun addAll(elements: Collection<E>): Boolean {
        checkIsMutable()
        val elementCount = elements.size
        addAllInternal(length, elements, elementCount)
        return elementCount > 0
    }

    @IgnorableReturnValue
    actual override fun addAll(index: Int, elements: Collection<E>): Boolean {
        checkIsMutable()
        AbstractList.checkPositionIndex(index, length)
        val elementCount = elements.size
        addAllInternal(index, elements, elementCount)
        return elementCount > 0
    }

    actual override fun clear() {
        checkIsMutable()
        removeRangeInternal(0, length)
    }

    @IgnorableReturnValue
    actual override fun removeAt(index: Int): E {
        checkIsMutable()
        AbstractList.checkElementIndex(index, length)
        return removeAtInternal(index)
    }

    @IgnorableReturnValue
    actual override fun remove(element: E): Boolean {
        checkIsMutable()
        val index = indexOf(element)
        if (index >= 0) removeAt(index)
        return index >= 0
    }

    @IgnorableReturnValue
    actual override fun removeAll(elements: Collection<E>): Boolean {
        checkIsMutable()
        return retainOrRemoveAllInternal(0, length, elements, retain = false) > 0
    }

    @IgnorableReturnValue
    actual override fun retainAll(elements: Collection<E>): Boolean {
        checkIsMutable()
        return retainOrRemoveAllInternal(0, length, elements, retain = true) > 0
    }

    actual override fun subList(fromIndex: Int, toIndex: Int): MutableList<E> {
        AbstractList.checkRangeIndexes(fromIndex, toIndex, length)
        return ArraySubList(backing, fromIndex, toIndex - fromIndex, null, this)
    }

    override fun <T> toArray(array: Array<T>): Array<T> {
        val result = if (array.size < length) arrayOfNulls(array, length) else array
        var index = 0
        while (index < length) {
            @Suppress("UNCHECKED_CAST")
            val element = backing[index] as T
            result[index] = element
            index++
        }
        return terminateCollectionToArray(length, result)
    }

    override fun toArray(): Array<Any?> = copyObjectRange(backing, 0, length)

    public actual fun trimToSize() {
        registerModification()
        if (length < backing.size) backing = backing.copyOf(length)
    }

    public actual fun ensureCapacity(minCapacity: Int) {
        if (minCapacity <= backing.size) return
        registerModification()
        ensureCapacityInternal(minCapacity)
    }

    override fun equals(other: Any?): Boolean =
        other === this || (other is List<*> && objectRangeEquals(backing, 0, length, other))

    override fun hashCode(): Int = objectRangeHashCode(backing, 0, length)

    override fun toString(): String = objectRangeToString(backing, 0, length, this)

    @Suppress("UNCHECKED_CAST")
    private fun elementAt(index: Int): E = backing[index] as E

    private fun registerModification() {
        modCount += 1
    }

    private fun checkIsMutable() {
        if (isReadOnly) throw UnsupportedOperationException()
    }

    private fun ensureExtraCapacity(elementCount: Int) {
        ensureCapacityInternal(length + elementCount)
    }

    private fun ensureCapacityInternal(minCapacity: Int) {
        if (minCapacity < 0) throw OutOfMemoryError()
        if (minCapacity > backing.size) {
            backing = backing.copyOf(AbstractList.newCapacity(backing.size, minCapacity))
        }
    }

    private fun insertAtInternal(index: Int, elementCount: Int) {
        ensureExtraCapacity(elementCount)
        backing.copyInto(
            backing,
            destinationOffset = index + elementCount,
            startIndex = index,
            endIndex = length,
        )
        length += elementCount
    }

    private fun addAtInternal(index: Int, element: E) {
        registerModification()
        insertAtInternal(index, 1)
        backing[index] = element
    }

    private fun addAllInternal(index: Int, elements: Collection<E>, elementCount: Int) {
        registerModification()
        insertAtInternal(index, elementCount)
        var destinationIndex = index
        val iterator = elements.iterator()
        while (destinationIndex < index + elementCount) {
            backing[destinationIndex++] = iterator.next()
        }
    }

    private fun removeAtInternal(index: Int): E {
        registerModification()
        val old = elementAt(index)
        backing.copyInto(backing, destinationOffset = index, startIndex = index + 1, endIndex = length)
        backing[length - 1] = null
        length--
        return old
    }

    private fun removeRangeInternal(rangeOffset: Int, rangeLength: Int) {
        if (rangeLength > 0) registerModification()
        backing.copyInto(
            backing,
            destinationOffset = rangeOffset,
            startIndex = rangeOffset + rangeLength,
            endIndex = length,
        )
        clearObjectRange(backing, length - rangeLength, length)
        length -= rangeLength
    }

    private fun retainOrRemoveAllInternal(
        rangeOffset: Int,
        rangeLength: Int,
        elements: Collection<E>,
        retain: Boolean,
    ): Int {
        var readIndex = 0
        var writeIndex = 0
        while (readIndex < rangeLength) {
            val element = elementAt(rangeOffset + readIndex)
            if (elements.contains(element) == retain) {
                backing[rangeOffset + writeIndex++] = backing[rangeOffset + readIndex]
            }
            readIndex++
        }
        val removed = rangeLength - writeIndex
        backing.copyInto(
            backing,
            destinationOffset = rangeOffset + writeIndex,
            startIndex = rangeOffset + rangeLength,
            endIndex = length,
        )
        clearObjectRange(backing, length - removed, length)
        if (removed > 0) registerModification()
        length -= removed
        return removed
    }

    private class Itr<E>(
        private val list: ArrayList<E>,
        private var index: Int,
    ) : MutableListIterator<E> {
        private var lastIndex = -1
        private var expectedModCount = list.modCount

        override fun hasPrevious(): Boolean = index > 0
        override fun hasNext(): Boolean = index < list.length
        override fun previousIndex(): Int = index - 1
        override fun nextIndex(): Int = index

        override fun previous(): E {
            checkForComodification()
            if (index <= 0) throw NoSuchElementException()
            lastIndex = --index
            return list.elementAt(lastIndex)
        }

        override fun next(): E {
            checkForComodification()
            if (index >= list.length) throw NoSuchElementException()
            lastIndex = index++
            return list.elementAt(lastIndex)
        }

        override fun set(element: E) {
            checkForComodification()
            check(lastIndex != -1) {
                "Call next() or previous() before replacing element from the iterator."
            }
            list[lastIndex] = element
        }

        override fun add(element: E) {
            checkForComodification()
            list.add(index++, element)
            lastIndex = -1
            expectedModCount = list.modCount
        }

        override fun remove() {
            checkForComodification()
            check(lastIndex != -1) {
                "Call next() or previous() before removing element from the iterator."
            }
            list.removeAt(lastIndex)
            index = lastIndex
            lastIndex = -1
            expectedModCount = list.modCount
        }

        private fun checkForComodification() {
            if (list.modCount != expectedModCount) throw ConcurrentModificationException()
        }
    }

    private class ArraySubList<E>(
        private var backing: Array<Any?>,
        private val offset: Int,
        private var length: Int,
        private val parent: ArraySubList<E>?,
        private val root: ArrayList<E>,
    ) : AbstractMutableList<E>(), RandomAccess {

        init {
            modCount = root.modCount
        }

        override val size: Int
            get() {
                checkForComodification()
                return length
            }

        override fun isEmpty(): Boolean {
            checkForComodification()
            return length == 0
        }

        override fun get(index: Int): E {
            checkForComodification()
            AbstractList.checkElementIndex(index, length)
            return elementAt(index)
        }

        override fun set(index: Int, element: E): E {
            checkIsMutable()
            checkForComodification()
            AbstractList.checkElementIndex(index, length)
            val old = elementAt(index)
            backing[offset + index] = element
            return old
        }

        override fun indexOf(element: E): Int {
            checkForComodification()
            var index = 0
            while (index < length) {
                if (backing[offset + index] == element) return index
                index++
            }
            return -1
        }

        override fun lastIndexOf(element: E): Int {
            checkForComodification()
            var index = length - 1
            while (index >= 0) {
                if (backing[offset + index] == element) return index
                index--
            }
            return -1
        }

        override fun iterator(): MutableIterator<E> = listIterator(0)
        override fun listIterator(): MutableListIterator<E> = listIterator(0)

        override fun listIterator(index: Int): MutableListIterator<E> {
            checkForComodification()
            AbstractList.checkPositionIndex(index, length)
            return Itr(this, index)
        }

        override fun add(element: E): Boolean {
            checkIsMutable()
            checkForComodification()
            addAtInternal(offset + length, element)
            return true
        }

        override fun add(index: Int, element: E) {
            checkIsMutable()
            checkForComodification()
            AbstractList.checkPositionIndex(index, length)
            addAtInternal(offset + index, element)
        }

        override fun addAll(elements: Collection<E>): Boolean {
            checkIsMutable()
            checkForComodification()
            val elementCount = elements.size
            addAllInternal(offset + length, elements, elementCount)
            return elementCount > 0
        }

        override fun addAll(index: Int, elements: Collection<E>): Boolean {
            checkIsMutable()
            checkForComodification()
            AbstractList.checkPositionIndex(index, length)
            val elementCount = elements.size
            addAllInternal(offset + index, elements, elementCount)
            return elementCount > 0
        }

        override fun clear() {
            checkIsMutable()
            checkForComodification()
            removeRangeInternal(offset, length)
        }

        override fun removeAt(index: Int): E {
            checkIsMutable()
            checkForComodification()
            AbstractList.checkElementIndex(index, length)
            return removeAtInternal(offset + index)
        }

        override fun remove(element: E): Boolean {
            checkIsMutable()
            checkForComodification()
            val index = indexOf(element)
            if (index >= 0) removeAt(index)
            return index >= 0
        }

        override fun removeAll(elements: Collection<E>): Boolean {
            checkIsMutable()
            checkForComodification()
            return retainOrRemoveAllInternal(offset, length, elements, retain = false) > 0
        }

        override fun retainAll(elements: Collection<E>): Boolean {
            checkIsMutable()
            checkForComodification()
            return retainOrRemoveAllInternal(offset, length, elements, retain = true) > 0
        }

        override fun subList(fromIndex: Int, toIndex: Int): MutableList<E> {
            AbstractList.checkRangeIndexes(fromIndex, toIndex, length)
            return ArraySubList(backing, offset + fromIndex, toIndex - fromIndex, this, root)
        }

        override fun <T> toArray(array: Array<T>): Array<T> {
            checkForComodification()
            val result = if (array.size < length) arrayOfNulls(array, length) else array
            var index = 0
            while (index < length) {
                @Suppress("UNCHECKED_CAST")
                val element = backing[offset + index] as T
                result[index] = element
                index++
            }
            return terminateCollectionToArray(length, result)
        }

        override fun toArray(): Array<Any?> {
            checkForComodification()
            return copyObjectRange(backing, offset, length)
        }

        override fun equals(other: Any?): Boolean {
            checkForComodification()
            return other === this ||
                    (other is List<*> && objectRangeEquals(backing, offset, length, other))
        }

        override fun hashCode(): Int {
            checkForComodification()
            return objectRangeHashCode(backing, offset, length)
        }

        override fun toString(): String {
            checkForComodification()
            return objectRangeToString(backing, offset, length, this)
        }

        @Suppress("UNCHECKED_CAST")
        private fun elementAt(index: Int): E = backing[offset + index] as E

        private fun registerModification() {
            modCount += 1
        }

        private fun checkForComodification() {
            if (root.modCount != modCount) throw ConcurrentModificationException()
        }

        private fun checkIsMutable() {
            if (root.isReadOnly) throw UnsupportedOperationException()
        }

        private fun addAtInternal(index: Int, element: E) {
            registerModification()
            if (parent != null) parent.addAtInternal(index, element) else root.addAtInternal(index, element)
            backing = root.backing
            length++
        }

        private fun addAllInternal(index: Int, elements: Collection<E>, elementCount: Int) {
            registerModification()
            if (parent != null) {
                parent.addAllInternal(index, elements, elementCount)
            } else {
                root.addAllInternal(index, elements, elementCount)
            }
            backing = root.backing
            length += elementCount
        }

        private fun removeAtInternal(index: Int): E {
            registerModification()
            val old = if (parent != null) parent.removeAtInternal(index) else root.removeAtInternal(index)
            length--
            return old
        }

        private fun removeRangeInternal(rangeOffset: Int, rangeLength: Int) {
            if (rangeLength > 0) registerModification()
            if (parent != null) {
                parent.removeRangeInternal(rangeOffset, rangeLength)
            } else {
                root.removeRangeInternal(rangeOffset, rangeLength)
            }
            length -= rangeLength
        }

        private fun retainOrRemoveAllInternal(
            rangeOffset: Int,
            rangeLength: Int,
            elements: Collection<E>,
            retain: Boolean,
        ): Int {
            val removed = if (parent != null) {
                parent.retainOrRemoveAllInternal(rangeOffset, rangeLength, elements, retain)
            } else {
                root.retainOrRemoveAllInternal(rangeOffset, rangeLength, elements, retain)
            }
            if (removed > 0) registerModification()
            length -= removed
            return removed
        }

        private class Itr<E>(
            private val list: ArraySubList<E>,
            private var index: Int,
        ) : MutableListIterator<E> {
            private var lastIndex = -1
            private var expectedModCount = list.modCount

            override fun hasPrevious(): Boolean = index > 0
            override fun hasNext(): Boolean = index < list.length
            override fun previousIndex(): Int = index - 1
            override fun nextIndex(): Int = index

            override fun previous(): E {
                checkForComodification()
                if (index <= 0) throw NoSuchElementException()
                lastIndex = --index
                return list.elementAt(lastIndex)
            }

            override fun next(): E {
                checkForComodification()
                if (index >= list.length) throw NoSuchElementException()
                lastIndex = index++
                return list.elementAt(lastIndex)
            }

            override fun set(element: E) {
                checkForComodification()
                check(lastIndex != -1) {
                    "Call next() or previous() before replacing element from the iterator."
                }
                list[lastIndex] = element
            }

            override fun add(element: E) {
                checkForComodification()
                list.add(index++, element)
                lastIndex = -1
                expectedModCount = list.modCount
            }

            override fun remove() {
                checkForComodification()
                check(lastIndex != -1) {
                    "Call next() or previous() before removing element from the iterator."
                }
                list.removeAt(lastIndex)
                index = lastIndex
                lastIndex = -1
                expectedModCount = list.modCount
            }

            private fun checkForComodification() {
                if (list.root.modCount != expectedModCount) throw ConcurrentModificationException()
            }
        }
    }
}

private fun objectArray(size: Int): Array<Any?> {
    if (size < 0) throw IllegalArgumentException("Negative initial capacity: $size")
    return arrayOfNulls<Any?>(size)
}

private fun clearObjectRange(array: Array<Any?>, fromIndex: Int, toIndex: Int) {
    var index = fromIndex
    while (index < toIndex) array[index++] = null
}

private fun copyObjectRange(array: Array<Any?>, offset: Int, length: Int): Array<Any?> {
    val result = arrayOfNulls<Any?>(length)
    array.copyInto(result, destinationOffset = 0, startIndex = offset, endIndex = offset + length)
    return result
}

private fun objectRangeHashCode(array: Array<Any?>, offset: Int, length: Int): Int {
    var result = 1
    var index = 0
    while (index < length) {
        result = result * 31 + (array[offset + index]?.hashCode() ?: 0)
        index++
    }
    return result
}

private fun objectRangeEquals(array: Array<Any?>, offset: Int, length: Int, other: List<*>): Boolean {
    if (length != other.size) return false
    var index = 0
    while (index < length) {
        if (array[offset + index] != other[index]) return false
        index++
    }
    return true
}

private fun objectRangeToString(
    array: Array<Any?>,
    offset: Int,
    length: Int,
    collection: Collection<*>,
): String {
    val result = StringBuilder(2 + length * 3)
    result.append("[")
    var index = 0
    while (index < length) {
        if (index > 0) result.append(", ")
        val element = array[offset + index]
        if (element === collection) result.append("(this Collection)") else result.append(element)
        index++
    }
    result.append("]")
    return result.toString()
}
