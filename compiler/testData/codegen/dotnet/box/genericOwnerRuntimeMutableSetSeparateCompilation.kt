// MODULE: lib
// FILE: mutableSetApi.kt

// DOTNET_GENERIC_OWNER_RUNTIME_MUTABLE_SET_CSHARP_PROBE

package generic.owner.runtime.mutable.set

public fun <T> exactSetAdd(set: MutableSet<T>, value: T): Boolean =
    set.add(value)

public fun widenedSetReferenceAddAll(
    set: MutableSet<Any?>,
    elements: Collection<String>,
): Boolean = set.addAll(elements)

public fun widenedSetValueAddAll(
    set: MutableSet<Any?>,
    elements: Collection<Int>,
): Boolean = set.addAll(elements)

public fun widenedCollectionValueAddAll(
    collection: MutableCollection<Any?>,
    elements: Collection<Int>,
): Boolean = collection.addAll(elements)

public fun <T> projectedSetAddAll(
    set: MutableSet<in T>,
    elements: Collection<T>,
): Boolean = set.addAll(elements)

public fun widenedSetValueRemoveAll(
    set: MutableSet<Any?>,
    elements: Collection<Int>,
): Boolean = set.removeAll(elements)

public fun widenedSetValueRetainAll(
    set: MutableSet<Any?>,
    elements: Collection<Int>,
): Boolean = set.retainAll(elements)

public fun widenedReadOnlySetContains(set: Set<Any?>, element: Any?): Boolean =
    set.contains(element)

public fun <T> firstMutableSetElement(set: MutableSet<T>): T =
    set.iterator().next()

public fun starSetClear(set: MutableSet<*>) {
    set.clear()
}

public fun sameMutableSet(set: MutableSet<*>, expected: Any?): Boolean =
    set === expected

// MODULE: middle(lib)
// FILE: mutableSetImplementation.kt

package generic.owner.runtime.mutable.set

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

private class OneMutableSetIterator<T>(
    private val owner: RuntimeMutableSetValue<T>,
) : MutableIterator<T> {
    private var available: Boolean = owner.hasValue()
    private var returned: Boolean = false

    override fun hasNext(): Boolean = available

    override fun next(): T {
        if (!available) throw NoSuchElementException()
        available = false
        returned = true
        return owner.current()
    }

    override fun remove() {
        if (!returned) throw IllegalStateException()
        returned = false
        owner.removeCurrent()
    }
}

public class RuntimeMutableSetValue<T>(private var value: T) : MutableSet<T> {
    private var present: Boolean = true
    private var lastBulkArgument: Any? = null

    override val size: Int get() = if (present) 1 else 0
    override fun isEmpty(): Boolean = !present
    override fun contains(element: T): Boolean = present && value == element

    override fun containsAll(elements: Collection<T>): Boolean {
        val iterator = elements.iterator()
        while (iterator.hasNext()) {
            if (!contains(iterator.next())) return false
        }
        return true
    }

    override fun iterator(): MutableIterator<T> = OneMutableSetIterator(this)

    override fun add(element: T): Boolean {
        if (present) {
            if (value == element) return false
            throw IllegalStateException("bounded set is full")
        }
        value = element
        present = true
        return true
    }

    override fun remove(element: T): Boolean {
        if (!contains(element)) return false
        present = false
        return true
    }

    override fun addAll(elements: Collection<T>): Boolean {
        lastBulkArgument = elements
        var changed = false
        val iterator = elements.iterator()
        while (iterator.hasNext()) {
            changed = add(iterator.next()) || changed
        }
        return changed
    }

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

    public fun current(): T = value
    public fun hasValue(): Boolean = present
    public fun removeCurrent() {
        present = false
    }
    public fun sawBulk(elements: Any?): Boolean = lastBulkArgument === elements
}

public fun objectMutableSet(value: Any?): RuntimeMutableSetValue<Any?> =
    RuntimeMutableSetValue(value)

public fun intMutableSet(value: Int): RuntimeMutableSetValue<Int> =
    RuntimeMutableSetValue(value)

public fun intCollection(value: Int): SingleCollection<Int> = SingleCollection(value)

public fun stringCollection(value: String): SingleCollection<String> = SingleCollection(value)

// MODULE: main(middle)
// FILE: main.kt

package generic.owner.runtime.mutable.set

fun box(): String {
    val set = objectMutableSet("initial")
    if (exactSetAdd(set, "initial")) return "duplicate add"
    starSetClear(set)
    if (!exactSetAdd(set, "exact") || set.current() != "exact") return "exact add"

    starSetClear(set)
    val references = stringCollection("reference")
    if (!widenedSetReferenceAddAll(set, references)) return "reference addAll result"
    if (set.current() != "reference" || !set.sawBulk(references)) {
        return "reference addAll identity"
    }

    starSetClear(set)
    val values = intCollection(55)
    if (!widenedSetValueAddAll(set, values)) return "value addAll result"
    if (set.current() != 55 || !set.sawBulk(values)) return "value addAll identity"

    starSetClear(set)
    val collectionValues = intCollection(56)
    if (!widenedCollectionValueAddAll(set, collectionValues)) return "collection diamond"
    if (set.current() != 56 || !set.sawBulk(collectionValues)) {
        return "collection diamond identity"
    }

    starSetClear(set)
    val projectedValues = intCollection(57)
    if (!projectedSetAddAll(set, projectedValues)) return "projected addAll result"
    if (set.current() != 57 || !set.sawBulk(projectedValues)) {
        return "projected addAll identity"
    }
    if (firstMutableSetElement(set) != 57) return "mutable iterator result"

    val valueSet = intMutableSet(58)
    if (!widenedReadOnlySetContains(valueSet, 58)) return "read-only parent contains"
    if (widenedReadOnlySetContains(valueSet, "wrong")) return "read-only parent barrier"

    if (!widenedSetValueRetainAll(set, intCollection(59)) || set.hasValue()) {
        return "value retainAll"
    }
    if (!exactSetAdd(set, 60)) return "add before removeAll"
    if (!widenedSetValueRemoveAll(set, intCollection(60)) || set.hasValue()) {
        return "value removeAll"
    }
    if (!exactSetAdd(set, "iterator")) return "add before iterator"
    val iterator = set.iterator()
    if (!iterator.hasNext() || iterator.next() != "iterator") return "iterator read"
    iterator.remove()
    if (set.hasValue()) return "iterator remove"
    if (!sameMutableSet(set, set)) return "identity"
    return "OK"
}
