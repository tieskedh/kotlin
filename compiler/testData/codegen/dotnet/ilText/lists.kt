// List keeps one canonical Kotlin identity. Output-only members live on the covariant declared
// capability; contains/indexOf/lastIndexOf live on the invariant exact capability.

private class PairListIterator<T>(
    private val list: List<T>,
    private var position: Int,
) : ListIterator<T> {
    override fun hasNext(): Boolean = position < list.size

    override fun next(): T = list.get(position++)

    override fun hasPrevious(): Boolean = position > 0

    override fun previous(): T = list.get(--position)

    override fun nextIndex(): Int = position

    override fun previousIndex(): Int = position - 1
}

class PairList<T>(
    private val first: T,
    private val second: T,
) : List<T> {
    override val size: Int get() = 2

    override fun isEmpty(): Boolean = false

    override fun contains(element: T): Boolean = first == element || second == element

    override fun iterator(): Iterator<T> = listIterator()

    override fun containsAll(elements: Collection<T>): Boolean {
        val iterator = elements.iterator()
        while (iterator.hasNext()) {
            if (!contains(iterator.next())) return false
        }
        return true
    }

    override fun get(index: Int): T = if (index == 0) first else second

    override fun indexOf(element: T): Int = if (first == element) 0 else if (second == element) 1 else -1

    override fun lastIndexOf(element: T): Int = if (second == element) 1 else if (first == element) 0 else -1

    override fun listIterator(): ListIterator<T> = PairListIterator(this, 0)

    override fun listIterator(index: Int): ListIterator<T> = PairListIterator(this, index)

    override fun subList(fromIndex: Int, toIndex: Int): List<T> = this
}

fun exactGet(list: List<Int>): Int = list.get(0)

fun wideGet(list: List<Any?>): Any? = list.get(0)

fun exactIndexOf(list: List<Int>): Int = list.indexOf(7)

fun wideIndexOf(list: List<Any?>): Int = list.indexOf(7)

fun wrongIndexOf(list: List<Any?>): Int = list.indexOf("7")

fun exactListIterator(list: List<Int>): ListIterator<Int> = list.listIterator()

fun exactSubList(list: List<Int>): List<Int> = list.subList(0, 1)

fun <T> openGet(list: List<T>): T = list.get(0)

fun <T> firstFromList(list: List<T>): T = list.first()

fun <T> lastFromList(list: List<T>): T = list.last()

fun <T> firstFromIterable(iterable: Iterable<T>): T = iterable.first()

fun <T> lastFromIterable(iterable: Iterable<T>): T = iterable.last()

fun emptyInts(): List<Int> = emptyList()

fun emptyStrings(): List<String> = emptyList()

fun nullableBottomAsString(value: Nothing?): String? = value

fun nullableBottomAsInt(value: Nothing?): Int? = value

fun neverReturns(): Nothing = throw NoSuchElementException()

fun guardedNeverReturn(): String {
    neverReturns()
}

fun widen(list: List<Int>): List<Any?> = list

fun main() {
    val list: List<Int> = PairList(7, 9)
    println(exactGet(list))
    println(wideGet(widen(list)))
    println(exactIndexOf(list))
    println(wideIndexOf(widen(list)))
    println(wrongIndexOf(widen(list)))
    println(exactListIterator(list).next())
    println(exactSubList(list).get(0))
    println(openGet(list))
    println(firstFromList(list))
    println(lastFromList(list))
    println(firstFromIterable(list))
    println(lastFromIterable(list))
    println(widen(list) === list)
    val empty = emptyInts()
    println(empty.size)
    println(empty is RandomAccess)
    println(empty === emptyStrings())
    println(emptyArray<String>().asIterable() === emptyStrings())
    println(nullableBottomAsString(null) == null)
    println(nullableBottomAsInt(null) == null)
}
