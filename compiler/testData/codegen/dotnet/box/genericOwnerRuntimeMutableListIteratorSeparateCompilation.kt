// MODULE: lib
// FILE: mutableListIteratorApi.kt

// DOTNET_GENERIC_OWNER_RUNTIME_MUTABLE_LIST_ITERATOR_CSHARP_PROBE

package generic.owner.runtime.mutable.list.iterator

public fun <T> exactMutableListNext(iterator: MutableListIterator<T>): T = iterator.next()

public fun starMutableListNext(iterator: MutableListIterator<*>): Any? = iterator.next()

public fun <T> exactMutableListSet(iterator: MutableListIterator<T>, value: T) {
    iterator.set(value)
}

public fun <T> projectedMutableListSet(iterator: MutableListIterator<in T>, value: T) {
    iterator.set(value)
}

public fun <T> exactMutableListAdd(iterator: MutableListIterator<T>, value: T) {
    iterator.add(value)
}

public fun <T> projectedMutableListAdd(iterator: MutableListIterator<in T>, value: T) {
    iterator.add(value)
}

public fun starMutableListRemove(iterator: MutableListIterator<*>) {
    iterator.remove()
}

public fun sameMutableListIterator(
    iterator: MutableListIterator<*>,
    expected: Any?,
): Boolean = iterator === expected

// MODULE: middle(lib)
// FILE: mutableListIteratorImplementation.kt

package generic.owner.runtime.mutable.list.iterator

public class RuntimeMutableListIteratorValue<T>(private var value: T) :
    MutableListIterator<T> {
    private var cursor: Int = 0
    private var removed: Boolean = false

    override fun hasNext(): Boolean = cursor == 0 && !removed

    override fun next(): T {
        if (!hasNext()) throw NoSuchElementException()
        cursor = 1
        return value
    }

    override fun hasPrevious(): Boolean = cursor == 1 && !removed

    override fun previous(): T {
        if (!hasPrevious()) throw NoSuchElementException()
        cursor = 0
        return value
    }

    override fun nextIndex(): Int = cursor

    override fun previousIndex(): Int = cursor - 1

    override fun remove() {
        if (cursor != 1 || removed) throw IllegalStateException()
        removed = true
    }

    override fun set(element: T) {
        if (cursor != 1 || removed) throw IllegalStateException()
        value = element
    }

    override fun add(element: T) {
        value = element
        cursor = 1
        removed = false
    }

    public fun current(): T = value

    public fun wasRemoved(): Boolean = removed
}

public fun stringMutableListIterator(value: String): RuntimeMutableListIteratorValue<String> =
    RuntimeMutableListIteratorValue(value)

public fun intMutableListIterator(value: Int): RuntimeMutableListIteratorValue<Int> =
    RuntimeMutableListIteratorValue(value)

// MODULE: main(middle)
// FILE: main.kt

package generic.owner.runtime.mutable.list.iterator

fun box(): String {
    val exact = stringMutableListIterator("exact")
    if (exactMutableListNext(exact) != "exact") return "exact next"
    exactMutableListSet(exact, "set")
    if (exact.current() != "set") return "exact set"

    val projected = stringMutableListIterator("projected")
    if (starMutableListNext(projected) != "projected") return "star next"
    projectedMutableListSet(projected, "projected set")
    projectedMutableListAdd(projected, "projected add")
    if (projected.current() != "projected add") return "projected input"
    if (!sameMutableListIterator(projected, projected)) return "reference identity"

    val removed = intMutableListIterator(56)
    if (starMutableListNext(removed) != 56) return "value star next"
    starMutableListRemove(removed)
    if (!removed.wasRemoved()) return "value star remove"

    val valueInput = intMutableListIterator(57)
    projectedMutableListAdd(valueInput, 58)
    if (valueInput.current() != 58) return "value projected add"
    projectedMutableListSet(valueInput, 59)
    if (valueInput.current() != 59) return "value projected set"
    if (!sameMutableListIterator(valueInput, valueInput)) return "value identity"
    return "OK"
}
