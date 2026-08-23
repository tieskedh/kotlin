// MODULE: lib
// FILE: listApi.kt

// DOTNET_GENERIC_OWNER_RUNTIME_LIST_CSHARP_PROBE

package generic.owner.runtime.list

public fun <T> exactListGet(list: List<T>, index: Int): T = list[index]

public fun widenedListGet(list: List<Any?>, index: Int): Any? = list[index]

public fun widenedListContains(list: List<Any?>, value: Any?): Boolean =
    list.contains(value)

public fun widenedListContainsAll(list: List<Any?>, values: Collection<Any?>): Boolean =
    list.containsAll(values)

public fun widenedListIndexOf(list: List<Any?>, value: Any?): Int =
    list.indexOf(value)

public fun widenedListLastIndexOf(list: List<Any?>, value: Any?): Int =
    list.lastIndexOf(value)

public fun <T> exactListIterator(list: List<T>, index: Int): ListIterator<T> =
    list.listIterator(index)

public fun <T> exactListIterator(list: List<T>): ListIterator<T> =
    list.listIterator()

public fun widenedListIterator(list: List<Any?>, index: Int): ListIterator<Any?> =
    list.listIterator(index)

public fun widenedListIterator(list: List<Any?>): ListIterator<Any?> =
    list.listIterator()

public fun widenedSubList(list: List<Any?>, fromIndex: Int, toIndex: Int): List<Any?> =
    list.subList(fromIndex, toIndex)

public fun sameList(list: List<Any?>, expected: Any?): Boolean = list === expected

public fun sameListIterator(iterator: ListIterator<Any?>, expected: Any?): Boolean =
    iterator === expected

// MODULE: middle(lib)
// FILE: listImplementation.kt

package generic.owner.runtime.list

private class PairListIterator<T>(
    private val first: T,
    private val second: T,
    startIndex: Int,
) : ListIterator<T> {
    private var index: Int = startIndex

    override fun hasNext(): Boolean = index < 2

    override fun next(): T = when (index++) {
        0 -> first
        1 -> second
        else -> throw NoSuchElementException()
    }

    override fun hasPrevious(): Boolean = index > 0

    override fun previous(): T = when (--index) {
        0 -> first
        1 -> second
        else -> throw NoSuchElementException()
    }

    override fun nextIndex(): Int = index

    override fun previousIndex(): Int = index - 1
}

public class RuntimeListValue<T> private constructor(
    private val first: T,
    private val second: T,
    private val itemCount: Int,
) : List<T> {
    public constructor(first: T, second: T) : this(first, second, 2)

    override val size: Int
        get() = itemCount

    override fun isEmpty(): Boolean = itemCount == 0

    override fun contains(element: T): Boolean = indexOf(element) >= 0

    override fun containsAll(elements: Collection<T>): Boolean {
        for (element in elements) {
            if (!contains(element)) return false
        }
        return true
    }

    override fun get(index: Int): T = when (index) {
        0 -> first
        1 -> if (itemCount > 1) second else throw IndexOutOfBoundsException()
        else -> throw IndexOutOfBoundsException()
    }

    override fun indexOf(element: T): Int = when {
        itemCount > 0 && first == element -> 0
        itemCount > 1 && second == element -> 1
        else -> -1
    }

    override fun lastIndexOf(element: T): Int = when {
        itemCount > 1 && second == element -> 1
        itemCount > 0 && first == element -> 0
        else -> -1
    }

    override fun iterator(): Iterator<T> = PairListIterator(first, second, 0)

    override fun listIterator(): ListIterator<T> = PairListIterator(first, second, 0)

    override fun listIterator(index: Int): ListIterator<T> {
        if (index < 0 || index > itemCount) throw IndexOutOfBoundsException()
        return PairListIterator(first, second, index)
    }

    override fun subList(fromIndex: Int, toIndex: Int): List<T> {
        if (fromIndex < 0 || toIndex > itemCount || fromIndex > toIndex) {
            throw IndexOutOfBoundsException()
        }
        return when (toIndex - fromIndex) {
            2 -> this
            1 -> RuntimeListValue(get(fromIndex), get(fromIndex), 1)
            else -> RuntimeListValue(first, first, 0)
        }
    }
}

public fun stringList(first: String, second: String): List<String> =
    RuntimeListValue(first, second)

public fun intList(first: Int, second: Int): List<Int> = RuntimeListValue(first, second)

public fun widenList(value: List<Int>): List<Any?> = value

// MODULE: main(middle)
// FILE: main.kt

package generic.owner.runtime.list

fun box(): String {
    val strings = stringList("alpha", "beta")
    if (exactListGet(strings, 1) != "beta") return "exact get"
    if (exactListIterator(strings).next() != "alpha" ||
        exactListIterator(strings, 2).previous() != "beta"
    ) {
        return "exact iterators"
    }

    val ints = intList(11, 13)
    val wide = widenList(ints)
    if (!sameList(wide, ints)) return "list identity"
    if (widenedListGet(wide, 0) != 11) return "widened get"
    if (!widenedListContains(wide, 13) || widenedListContains(wide, "13")) {
        return "widened contains"
    }
    if (widenedListIndexOf(wide, 13) != 1 || widenedListIndexOf(wide, "13") != -1) {
        return "widened indexOf"
    }
    if (widenedListLastIndexOf(wide, 11) != 0 ||
        widenedListLastIndexOf(wide, "11") != -1
    ) {
        return "widened lastIndexOf"
    }
    val initialIterator = widenedListIterator(wide)
    if (!sameListIterator(initialIterator, initialIterator) || initialIterator.next() != 11) {
        return "widened initial list iterator"
    }
    val iterator = widenedListIterator(wide, 2)
    if (!sameListIterator(iterator, iterator) || iterator.previous() != 13 ||
        iterator.nextIndex() != 1 || iterator.previousIndex() != 0
    ) {
        return "widened list iterator"
    }
    val subList = widenedSubList(wide, 1, 2)
    if (widenedListGet(subList, 0) != 13 || widenedListIndexOf(subList, 13) != 0) {
        return "widened subList"
    }
    if (!widenedListContainsAll(wide, widenList(intList(11, 13))) ||
        widenedListContainsAll(wide, stringList("11", "13"))
    ) {
        return "widened containsAll"
    }
    return "OK"
}
