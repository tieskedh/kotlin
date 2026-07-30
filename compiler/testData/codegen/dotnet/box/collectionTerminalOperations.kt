private class CountingIterable<T>(private val values: Array<T>) : Iterable<T> {
    var iteratorCalls: Int = 0
    var nextCalls: Int = 0

    override fun iterator(): Iterator<T> {
        iteratorCalls++
        return CountingIterator(values, this)
    }
}

private class CountingIterator<T>(
    private val values: Array<T>,
    private val owner: CountingIterable<T>,
) : Iterator<T> {
    private var index: Int = 0

    override fun hasNext(): Boolean = index < values.size

    override fun next(): T {
        if (!hasNext()) throw NoSuchElementException()
        owner.nextCalls++
        return values[index++]
    }
}

private class IteratorTrapList<T>(private val value: T) : List<T> {
    override val size: Int get() = 1
    override fun isEmpty(): Boolean = false
    override fun get(index: Int): T =
        if (index == 0) value else throw IndexOutOfBoundsException("index: $index")

    override fun contains(element: T): Boolean = element == value
    override fun containsAll(elements: Collection<T>): Boolean = false
    override fun indexOf(element: T): Int = if (element == value) 0 else -1
    override fun lastIndexOf(element: T): Int = indexOf(element)

    override fun iterator(): Iterator<T> = throw Error("List terminal operation used iterator()")
    override fun listIterator(): ListIterator<T> = throw Error("List terminal operation used listIterator()")
    override fun listIterator(index: Int): ListIterator<T> =
        throw Error("List terminal operation used listIterator(index)")

    override fun subList(fromIndex: Int, toIndex: Int): List<T> =
        throw Error("List terminal operation used subList()")
}

private fun <T> genericFirstOrNull(values: Iterable<T>): T? = values.firstOrNull()

private fun <T> genericLastOrNull(values: Iterable<T>): T? = values.lastOrNull()

private fun <T> genericLast(values: Iterable<T>): T = values.last()

fun box(): String {
    val primitive = CountingIterable(arrayOf(7, 9))
    val primitiveFirst: Int? = genericFirstOrNull(primitive)
    if (primitiveFirst != 7 || primitive.iteratorCalls != 1 || primitive.nextCalls != 1) {
        return "fail 1: primitive firstOrNull"
    }
    val primitiveLast: Int? = genericLastOrNull(primitive)
    if (primitiveLast != 9 || primitive.iteratorCalls != 2 || primitive.nextCalls != 3) {
        return "fail 2: primitive lastOrNull"
    }

    val widened: Iterable<Any?> = primitive
    if (widened !== primitive || widened.lastOrNull() != 9) {
        return "fail 3: widened identity/result"
    }
    if (primitive.iteratorCalls != 3 || primitive.nextCalls != 5) {
        return "fail 4: widened traversal"
    }

    val nullable = CountingIterable(arrayOf<String?>(null, "tail"))
    if (nullable.firstOrNull() != null || nullable.iteratorCalls != 1 || nullable.nextCalls != 1) {
        return "fail 5: nullable element"
    }
    if (nullable.lastOrNull() != "tail" || nullable.iteratorCalls != 2 || nullable.nextCalls != 3) {
        return "fail 6: nullable tail"
    }

    val empty = CountingIterable(emptyArray<String>())
    if (empty.firstOrNull() != null || empty.lastOrNull() != null) {
        return "fail 7: empty iterable"
    }
    if (empty.iteratorCalls != 2 || empty.nextCalls != 0) {
        return "fail 8: empty traversal"
    }

    val trap: List<Int> = IteratorTrapList(42)
    val trapAsIterable: Iterable<Int> = trap
    if (
        trap.firstOrNull() != 42 ||
        trap.lastOrNull() != 42 ||
        trap.lastIndex != 0 ||
        genericLast(trapAsIterable) != 42
    ) {
        return "fail 9: List fast path"
    }

    if (
        emptyList<Int>().firstOrNull() != null ||
        emptyList<Int>().lastOrNull() != null ||
        emptyList<Int>().lastIndex != -1
    ) {
        return "fail 10: stdlib empty singleton"
    }
    return "OK"
}
