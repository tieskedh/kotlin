// MODULE: lib
// FILE: iteratorApi.kt

// DOTNET_GENERIC_OWNER_RUNTIME_ITERATOR_CSHARP_PROBE

package generic.owner.runtime.iterator

public fun <T> exactNext(iterator: Iterator<T>): T = iterator.next()

public fun exactHasNext(iterator: Iterator<String>): Boolean = iterator.hasNext()

public fun widenedNext(iterator: Iterator<Any?>): Any? = iterator.next()

public fun sameIterator(iterator: Iterator<Any?>, expected: Any?): Boolean = iterator === expected

public fun <T> exactIterator(iterable: Iterable<T>): Iterator<T> = iterable.iterator()

public fun widenedIterator(iterable: Iterable<Any?>): Iterator<Any?> = iterable.iterator()

public fun sameIterable(iterable: Iterable<Any?>, expected: Any?): Boolean = iterable === expected

// MODULE: middle(lib)
// FILE: iteratorImplementation.kt

package generic.owner.runtime.iterator

public class RuntimeIteratorValue<T>(private val value: T) : Iterator<T> {
    private var consumed: Boolean = false

    override fun hasNext(): Boolean = !consumed

    override fun next(): T {
        if (consumed) throw NoSuchElementException()
        consumed = true
        return value
    }
}

public class RuntimeIterableValue<T>(private val value: T) : Iterable<T> {
    override fun iterator(): Iterator<T> = RuntimeIteratorValue(value)
}

public fun stringIterator(value: String): Iterator<String> = RuntimeIteratorValue(value)

public fun intIterator(value: Int): Iterator<Int> = RuntimeIteratorValue(value)

public fun widenIterator(value: Iterator<Int>): Iterator<Any?> = value

public fun stringIterable(value: String): Iterable<String> = RuntimeIterableValue(value)

public fun intIterable(value: Int): Iterable<Int> = RuntimeIterableValue(value)

public fun widenIterable(value: Iterable<Int>): Iterable<Any?> = value

// MODULE: main(middle)
// FILE: main.kt

package generic.owner.runtime.iterator

fun box(): String {
    val strings = stringIterator("typed")
    if (!exactHasNext(strings) || exactNext(strings) != "typed") return "typed reference"

    val ints = intIterator(49)
    val wide = widenIterator(ints)
    if (!sameIterator(wide, ints)) return "identity"
    if (widenedNext(wide) != 49) return "semantic widening"

    val stringsIterable = stringIterable("nested")
    if (exactNext(exactIterator(stringsIterable)) != "nested") return "typed nested return"

    val intsIterable = intIterable(73)
    val wideIterable = widenIterable(intsIterable)
    if (!sameIterable(wideIterable, intsIterable)) return "iterable identity"
    if (widenedNext(widenedIterator(wideIterable)) != 73) return "iterable semantic widening"
    return "OK"
}
