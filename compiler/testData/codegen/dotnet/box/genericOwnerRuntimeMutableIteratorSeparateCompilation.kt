// MODULE: lib
// FILE: mutableIteratorApi.kt

// DOTNET_GENERIC_OWNER_RUNTIME_MUTABLE_ITERATOR_CSHARP_PROBE

package generic.owner.runtime.mutable.iterator

public fun <T> exactMutableNext(iterator: MutableIterator<T>): T = iterator.next()

public fun widenedMutableNext(iterator: MutableIterator<Any?>): Any? = iterator.next()

public fun <T> exactMutableRemove(iterator: MutableIterator<T>) {
    iterator.remove()
}

public fun widenedMutableRemove(iterator: MutableIterator<Any?>) {
    iterator.remove()
}

public fun sameMutableIterator(iterator: MutableIterator<Any?>, expected: Any?): Boolean =
    iterator === expected

public fun <T> exactMutableIterator(iterable: MutableIterable<T>): MutableIterator<T> =
    iterable.iterator()

public fun widenedMutableIterator(iterable: MutableIterable<Any?>): MutableIterator<Any?> =
    iterable.iterator()

public fun sameMutableIterable(iterable: MutableIterable<Any?>, expected: Any?): Boolean =
    iterable === expected

// MODULE: middle(lib)
// FILE: mutableIteratorImplementation.kt

package generic.owner.runtime.mutable.iterator

public class RuntimeMutableIteratorValue<T>(private val value: T) : MutableIterator<T> {
    private var consumed: Boolean = false
    private var removed: Boolean = false

    override fun hasNext(): Boolean = !consumed

    override fun next(): T {
        if (consumed) throw NoSuchElementException()
        consumed = true
        return value
    }

    override fun remove() {
        if (!consumed || removed) throw IllegalStateException()
        removed = true
    }

    public fun wasRemoved(): Boolean = removed
}

public class RuntimeMutableIterableValue<T>(private val value: T) : MutableIterable<T> {
    override fun iterator(): MutableIterator<T> = RuntimeMutableIteratorValue(value)
}

public fun stringMutableIterator(value: String): RuntimeMutableIteratorValue<String> =
    RuntimeMutableIteratorValue(value)

public fun intMutableIterator(value: Int): RuntimeMutableIteratorValue<Int> =
    RuntimeMutableIteratorValue(value)

public fun widenMutableIterator(
    value: MutableIterator<Int>,
): MutableIterator<Any?> = value

public fun stringMutableIterable(value: String): MutableIterable<String> =
    RuntimeMutableIterableValue(value)

public fun intMutableIterable(value: Int): MutableIterable<Int> =
    RuntimeMutableIterableValue(value)

public fun widenMutableIterable(value: MutableIterable<Int>): MutableIterable<Any?> = value

// MODULE: main(middle)
// FILE: main.kt

package generic.owner.runtime.mutable.iterator

fun box(): String {
    val strings = stringMutableIterator("typed")
    if (exactMutableNext(strings) != "typed") return "typed next"
    exactMutableRemove(strings)
    if (!strings.wasRemoved()) return "typed remove"

    val ints = intMutableIterator(52)
    val wide = widenMutableIterator(ints)
    if (!sameMutableIterator(wide, ints)) return "iterator identity"
    if (widenedMutableNext(wide) != 52) return "widened next"
    widenedMutableRemove(wide)
    if (!ints.wasRemoved()) return "widened remove"

    val stringsIterable = stringMutableIterable("nested")
    val stringsIterator = exactMutableIterator(stringsIterable)
    if (exactMutableNext(stringsIterator) != "nested") return "typed nested next"
    exactMutableRemove(stringsIterator)

    val intsIterable = intMutableIterable(53)
    val wideIterable = widenMutableIterable(intsIterable)
    if (!sameMutableIterable(wideIterable, intsIterable)) return "iterable identity"
    val wideIterator = widenedMutableIterator(wideIterable)
    if (widenedMutableNext(wideIterator) != 53) return "widened nested next"
    widenedMutableRemove(wideIterator)
    return "OK"
}
