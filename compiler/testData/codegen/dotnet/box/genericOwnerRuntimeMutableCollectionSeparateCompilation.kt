// MODULE: lib
// FILE: mutableCollectionApi.kt

// DOTNET_GENERIC_OWNER_RUNTIME_MUTABLE_COLLECTION_CSHARP_PROBE

package generic.owner.runtime.mutable.collection

public fun <T> exactAdd(collection: MutableCollection<T>, value: T): Boolean =
    collection.add(value)

public fun widenedReferenceAddAll(
    collection: MutableCollection<Any?>,
    elements: Collection<String>,
): Boolean = collection.addAll(elements)

public fun widenedValueAddAll(
    collection: MutableCollection<Any?>,
    elements: Collection<Int>,
): Boolean = collection.addAll(elements)

public fun widenedValueRemoveAll(
    collection: MutableCollection<Any?>,
    elements: Collection<Int>,
): Boolean = collection.removeAll(elements)

public fun widenedValueRetainAll(
    collection: MutableCollection<Any?>,
    elements: Collection<Int>,
): Boolean = collection.retainAll(elements)

public fun <T> projectedAddAll(
    collection: MutableCollection<in T>,
    elements: Collection<T>,
): Boolean = collection.addAll(elements)

public fun starClear(collection: MutableCollection<*>) {
    collection.clear()
}

public fun sameMutableCollection(
    collection: MutableCollection<*>,
    expected: Any?,
): Boolean = collection === expected

// MODULE: middle(lib)
// FILE: mutableCollectionImplementation.kt

package generic.owner.runtime.mutable.collection

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

private class OneMutableIterator<T>(
    private val owner: RuntimeMutableCollectionValue<T>,
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

public class RuntimeMutableCollectionValue<T>(private var value: T) : MutableCollection<T> {
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

    override fun iterator(): MutableIterator<T> = OneMutableIterator(this)

    override fun add(element: T): Boolean {
        val changed = !present || value != element
        value = element
        present = true
        return changed
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

public fun objectMutableCollection(value: Any?): RuntimeMutableCollectionValue<Any?> =
    RuntimeMutableCollectionValue(value)

public fun intCollection(value: Int): SingleCollection<Int> = SingleCollection(value)

public fun stringCollection(value: String): SingleCollection<String> = SingleCollection(value)

// MODULE: main(middle)
// FILE: main.kt

package generic.owner.runtime.mutable.collection

fun box(): String {
    val exact = objectMutableCollection("initial")
    if (!exactAdd(exact, "exact") || exact.current() != "exact") return "exact add"

    val references = stringCollection("reference")
    if (!widenedReferenceAddAll(exact, references)) return "reference addAll result"
    if (exact.current() != "reference" || !exact.sawBulk(references)) {
        return "reference addAll identity"
    }

    val values = intCollection(54)
    if (!widenedValueAddAll(exact, values)) return "value addAll result"
    if (exact.current() != 54 || !exact.sawBulk(values)) return "value addAll identity"

    val projectedValues = intCollection(55)
    if (!projectedAddAll(exact, projectedValues)) return "projected addAll result"
    if (exact.current() != 55 || !exact.sawBulk(projectedValues)) {
        return "projected addAll identity"
    }

    if (!widenedValueRetainAll(exact, intCollection(56)) || exact.hasValue()) {
        return "value retainAll"
    }
    exact.add(57)
    if (!widenedValueRemoveAll(exact, intCollection(57)) || exact.hasValue()) {
        return "value removeAll"
    }
    exact.add("clear")
    starClear(exact)
    if (exact.hasValue()) return "star clear"
    if (!sameMutableCollection(exact, exact)) return "identity"
    return "OK"
}
