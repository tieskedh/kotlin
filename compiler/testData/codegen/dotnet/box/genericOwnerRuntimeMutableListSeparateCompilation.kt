// MODULE: lib
// FILE: mutableListApi.kt

// DOTNET_GENERIC_OWNER_RUNTIME_MUTABLE_LIST_CSHARP_PROBE

package generic.owner.runtime.mutable.list

public fun <T> exactListAdd(list: MutableList<T>, value: T): Boolean =
    list.add(value)

public fun widenedListValueAddAll(
    list: MutableList<Any?>,
    elements: Collection<Int>,
): Boolean = list.addAll(elements)

public fun widenedListValueAddAllAt(
    list: MutableList<Any?>,
    index: Int,
    elements: Collection<Int>,
): Boolean = list.addAll(index, elements)

public fun widenedCollectionValueAddAll(
    collection: MutableCollection<Any?>,
    elements: Collection<Int>,
): Boolean = collection.addAll(elements)

public fun <T> projectedListAddAll(
    list: MutableList<in T>,
    elements: Collection<T>,
): Boolean = list.addAll(elements)

public fun <T> projectedListAdd(list: MutableList<in T>, element: T): Boolean =
    list.add(element)

public fun <T> projectedListSet(
    list: MutableList<in T>,
    index: Int,
    element: T,
): Any? = list.set(index, element)

public fun <T> projectedListAddAt(list: MutableList<in T>, index: Int, element: T) {
    list.add(index, element)
}

public fun widenedListRemoveAt(list: MutableList<Any?>, index: Int): Any? =
    list.removeAt(index)

public fun widenedReadOnlyListContains(list: List<Any?>, element: Any?): Boolean =
    list.contains(element)

public fun widenedReadOnlyListIndexOf(list: List<Any?>, element: Any?): Int =
    list.indexOf(element)

public fun <T> firstMutableListElement(list: MutableList<T>): T =
    list.listIterator().next()

public fun <T> firstMutableListElementFrom(list: MutableList<T>, index: Int): T =
    list.listIterator(index).next()

public fun <T> fullMutableSubList(list: MutableList<T>): MutableList<T> =
    list.subList(0, list.size)

public fun starListFirst(list: MutableList<*>): Any? = list[0]

public fun starListClear(list: MutableList<*>) {
    list.clear()
}

public fun sameMutableList(list: MutableList<*>, expected: Any?): Boolean =
    list === expected

// MODULE: middle(lib)
// FILE: mutableListImplementation.kt

package generic.owner.runtime.mutable.list

private class SingleIterator<T>(private val value: T) : Iterator<T> {
    private var available: Boolean = true

    override fun hasNext(): Boolean = available

    override fun next(): T {
        if (!available) throw NoSuchElementException()
        available = false
        return value
    }
}

public class SingleCollection<T>(private val value: T) : Collection<T> {
    override val size: Int get() = 1
    override fun isEmpty(): Boolean = false
    override fun contains(element: T): Boolean = value == element

    override fun containsAll(elements: Collection<T>): Boolean {
        val iterator = elements.iterator()
        while (iterator.hasNext()) {
            if (!contains(iterator.next())) return false
        }
        return true
    }

    override fun iterator(): Iterator<T> = SingleIterator(value)
}

private class OneMutableListIterator<T>(
    private val owner: RuntimeMutableListValue<T>,
    startIndex: Int,
) : MutableListIterator<T> {
    private var cursor: Int = startIndex
    private var canModify: Boolean = false

    override fun hasNext(): Boolean = owner.hasValue() && cursor == 0

    override fun next(): T {
        if (!hasNext()) throw NoSuchElementException()
        cursor = 1
        canModify = true
        return owner.current()
    }

    override fun hasPrevious(): Boolean = owner.hasValue() && cursor == 1

    override fun previous(): T {
        if (!hasPrevious()) throw NoSuchElementException()
        cursor = 0
        canModify = true
        return owner.current()
    }

    override fun nextIndex(): Int = cursor
    override fun previousIndex(): Int = cursor - 1

    override fun remove() {
        if (!canModify) throw IllegalStateException()
        owner.removeCurrent()
        cursor = 0
        canModify = false
    }

    override fun set(element: T) {
        if (!canModify) throw IllegalStateException()
        owner.replaceCurrent(element)
    }

    override fun add(element: T) {
        if (owner.hasValue()) throw IllegalStateException("bounded list is full")
        owner.addAt(cursor, element)
        cursor = 1
        canModify = false
    }
}

public class RuntimeMutableListValue<T>(private var value: T) : MutableList<T> {
    private var present: Boolean = true
    private var lastBulkArgument: Any? = null
    private var lastBulkIndex: Int = -1

    override val size: Int get() = if (present) 1 else 0
    override fun isEmpty(): Boolean = !present
    override fun contains(element: T): Boolean = present && value == element
    override fun get(index: Int): T {
        requirePresentIndex(index)
        return value
    }
    override fun indexOf(element: T): Int = if (contains(element)) 0 else -1
    override fun lastIndexOf(element: T): Int = indexOf(element)

    override fun containsAll(elements: Collection<T>): Boolean {
        val iterator = elements.iterator()
        while (iterator.hasNext()) {
            if (!contains(iterator.next())) return false
        }
        return true
    }

    override fun iterator(): MutableIterator<T> = OneMutableListIterator(this, 0)
    override fun listIterator(): MutableListIterator<T> = OneMutableListIterator(this, 0)
    override fun listIterator(index: Int): MutableListIterator<T> {
        require(index == 0 || index == size)
        return OneMutableListIterator(this, index)
    }

    override fun add(element: T): Boolean {
        if (present) throw IllegalStateException("bounded list is full")
        value = element
        present = true
        return true
    }

    override fun add(index: Int, element: T) {
        addAt(index, element)
    }

    override fun remove(element: T): Boolean {
        if (!contains(element)) return false
        present = false
        return true
    }

    override fun addAll(elements: Collection<T>): Boolean = addAllAt(size, elements)

    override fun addAll(index: Int, elements: Collection<T>): Boolean =
        addAllAt(index, elements)

    override fun removeAll(elements: Collection<T>): Boolean {
        lastBulkArgument = elements
        val iterator = elements.iterator()
        while (iterator.hasNext()) {
            if (remove(iterator.next())) return true
        }
        return false
    }

    override fun retainAll(elements: Collection<T>): Boolean {
        lastBulkArgument = elements
        if (!present || elements.contains(value)) return false
        present = false
        return true
    }

    override fun clear() {
        present = false
    }

    override fun set(index: Int, element: T): T {
        requirePresentIndex(index)
        val previous = value
        value = element
        return previous
    }

    override fun removeAt(index: Int): T {
        requirePresentIndex(index)
        val previous = value
        present = false
        return previous
    }

    override fun subList(fromIndex: Int, toIndex: Int): MutableList<T> {
        require(fromIndex == 0 && toIndex == size)
        return this
    }

    public fun current(): T = value
    public fun hasValue(): Boolean = present
    public fun removeCurrent() {
        present = false
    }
    public fun replaceCurrent(element: T) {
        if (!present) throw IllegalStateException()
        value = element
    }
    public fun addAt(index: Int, element: T) {
        require(index == size)
        if (present) throw IllegalStateException("bounded list is full")
        value = element
        present = true
    }
    public fun sawBulk(elements: Any?, index: Int): Boolean =
        lastBulkArgument === elements && lastBulkIndex == index

    private fun addAllAt(index: Int, elements: Collection<T>): Boolean {
        lastBulkArgument = elements
        lastBulkIndex = index
        val iterator = elements.iterator()
        if (!iterator.hasNext()) return false
        addAt(index, iterator.next())
        if (iterator.hasNext()) throw IllegalStateException("bounded list is full")
        return true
    }

    private fun requirePresentIndex(index: Int) {
        if (!present || index != 0) throw IndexOutOfBoundsException()
    }
}

public fun objectMutableList(value: Any?): RuntimeMutableListValue<Any?> =
    RuntimeMutableListValue(value)

public fun intMutableList(value: Int): RuntimeMutableListValue<Int> =
    RuntimeMutableListValue(value)

public fun intCollection(value: Int): SingleCollection<Int> = SingleCollection(value)

// MODULE: main(middle)
// FILE: main.kt

package generic.owner.runtime.mutable.list

fun box(): String {
    val list = objectMutableList("initial")
    if (starListFirst(list) != "initial") return "star read"
    if (projectedListSet(list, 0, "set") != "initial" || list.current() != "set") {
        return "set input/result"
    }
    if (widenedListRemoveAt(list, 0) != "set" || list.hasValue()) return "removeAt"
    projectedListAddAt(list, 0, "indexed")
    if (list.current() != "indexed") return "indexed add"

    starListClear(list)
    if (!projectedListAdd(list, "projected")) return "projected add"

    starListClear(list)
    val values = intCollection(51)
    if (!widenedListValueAddAll(list, values) || !list.sawBulk(values, 0)) {
        return "value addAll"
    }

    starListClear(list)
    val indexedValues = intCollection(52)
    if (!widenedListValueAddAllAt(list, 0, indexedValues) ||
        !list.sawBulk(indexedValues, 0)
    ) {
        return "indexed value addAll"
    }

    starListClear(list)
    val collectionValues = intCollection(53)
    if (!widenedCollectionValueAddAll(list, collectionValues) ||
        !list.sawBulk(collectionValues, 0)
    ) {
        return "collection diamond"
    }

    starListClear(list)
    val projectedValues = intCollection(54)
    if (!projectedListAddAll(list, projectedValues) ||
        !list.sawBulk(projectedValues, 0)
    ) {
        return "projected addAll"
    }
    if (firstMutableListElement(list) != 54 || firstMutableListElementFrom(list, 0) != 54) {
        return "mutable list iterators"
    }
    if (fullMutableSubList(list) !== list) return "live subList identity"

    val ints = intMutableList(55)
    if (!widenedReadOnlyListContains(ints, 55)) return "read-only contains"
    if (widenedReadOnlyListContains(ints, "wrong")) return "read-only contains barrier"
    if (widenedReadOnlyListIndexOf(ints, "wrong") != -1) return "indexOf barrier"
    if (!sameMutableList(list, list)) return "identity"
    return "OK"
}
