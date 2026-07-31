private class CountingIterable<T>(private val values: Array<T>) : Iterable<T> {
    var iteratorCalls: Int = 0
    var hasNextCalls: Int = 0
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

    override fun hasNext(): Boolean {
        owner.hasNextCalls++
        return index < values.size
    }

    override fun next(): T {
        if (index >= values.size) throw NoSuchElementException()
        owner.nextCalls++
        return values[index++]
    }
}

private class IteratorTrapList<T>(private val values: Array<T>) : List<T> {
    var getCalls: Int = 0

    override val size: Int get() = values.size

    override fun isEmpty(): Boolean = size == 0

    override fun get(index: Int): T {
        getCalls++
        return values[index]
    }

    override fun contains(element: T): Boolean = indexOf(element) >= 0

    override fun containsAll(elements: Collection<T>): Boolean {
        val iterator = elements.iterator()
        while (iterator.hasNext()) {
            if (!contains(iterator.next())) return false
        }
        return true
    }

    override fun indexOf(element: T): Int {
        var index = 0
        while (index < size) {
            if (values[index] == element) return index
            index++
        }
        return -1
    }

    override fun lastIndexOf(element: T): Int {
        var index = size - 1
        while (index >= 0) {
            if (values[index] == element) return index
            index--
        }
        return -1
    }

    override fun iterator(): Iterator<T> =
        throw Error("indexed optional operation used List.iterator()")

    override fun listIterator(): ListIterator<T> =
        throw Error("indexed optional operation used List.listIterator()")

    override fun listIterator(index: Int): ListIterator<T> =
        throw Error("indexed optional operation used List.listIterator(index)")

    override fun subList(fromIndex: Int, toIndex: Int): List<T> =
        throw Error("indexed optional operation used List.subList()")
}

private class FailingGetList(private val failure: Throwable) : List<String> {
    override val size: Int get() = 1
    override fun isEmpty(): Boolean = false
    override fun get(index: Int): String = throw failure
    override fun contains(element: String): Boolean = false
    override fun containsAll(elements: Collection<String>): Boolean = false
    override fun indexOf(element: String): Int = -1
    override fun lastIndexOf(element: String): Int = -1
    override fun iterator(): Iterator<String> = throw Error("unexpected iterator")
    override fun listIterator(): ListIterator<String> = throw Error("unexpected listIterator")
    override fun listIterator(index: Int): ListIterator<String> = throw Error("unexpected listIterator(index)")
    override fun subList(fromIndex: Int, toIndex: Int): List<String> = throw Error("unexpected subList")
}

private fun <T> optionalAt(values: Iterable<T>, index: Int): T? =
    values.elementAtOrNull(index)

private fun fail(message: String): String = "fail: $message"

fun box(): String {
    val empty = CountingIterable(emptyArray<String>())
    if (optionalAt(empty, 0) != null) return fail("empty result")
    if (empty.iteratorCalls != 1 || empty.hasNextCalls != 1 || empty.nextCalls != 0) {
        return fail("empty traversal")
    }

    val singleton = CountingIterable(arrayOf("only"))
    if (optionalAt(singleton, 0) != "only") return fail("singleton result")
    if (optionalAt(singleton, 1) != null) return fail("singleton past-end result")
    if (singleton.iteratorCalls != 2 || singleton.hasNextCalls != 3 || singleton.nextCalls != 2) {
        return fail("singleton traversal")
    }

    val values = CountingIterable(arrayOf(3, 5, 7))
    if (optionalAt(values, -1) != null) return fail("negative result")
    if (values.iteratorCalls != 0 || values.hasNextCalls != 0 || values.nextCalls != 0) {
        return fail("negative traversal")
    }

    if (optionalAt(values, 0) != 3) return fail("first result")
    if (values.iteratorCalls != 1 || values.hasNextCalls != 1 || values.nextCalls != 1) {
        return fail("first traversal")
    }

    if (optionalAt(values, 2) != 7) return fail("last result")
    if (values.iteratorCalls != 2 || values.hasNextCalls != 4 || values.nextCalls != 4) {
        return fail("last traversal")
    }

    if (optionalAt(values, 3) != null) return fail("past-end result")
    if (values.iteratorCalls != 3 || values.hasNextCalls != 8 || values.nextCalls != 7) {
        return fail("past-end traversal")
    }

    val nullable = CountingIterable(arrayOf<String?>(null, "tail"))
    if (nullable.elementAtOrNull(0) != null) return fail("nullable element")
    if (nullable.iteratorCalls != 1 || nullable.hasNextCalls != 1 || nullable.nextCalls != 1) {
        return fail("nullable traversal")
    }

    val list = IteratorTrapList(arrayOf(11, 13))
    val emptyList = IteratorTrapList(emptyArray<Int>())
    if (emptyList.getOrNull(0) != null || emptyList.elementAtOrNull(0) != null) {
        return fail("empty List result")
    }
    if (emptyList.getCalls != 0) return fail("empty List get")

    val widened: Iterable<Any?> = list
    if (optionalAt(widened, 1) != 13) return fail("widened List result")
    if (list.getCalls != 1) return fail("widened List get")
    if (optionalAt(widened, -1) != null || optionalAt(widened, 2) != null) {
        return fail("List bounds")
    }
    if (list.getCalls != 1) return fail("out-of-bounds List get")
    if (list.getOrNull(0) != 11 || list.getCalls != 2) return fail("direct List getOrNull")

    val nullableList = IteratorTrapList(arrayOf<String?>(null))
    if (nullableList.getOrNull(0) != null || nullableList.getCalls != 1) {
        return fail("nullable List element")
    }

    val failure = IllegalStateException("get failed")
    try {
        FailingGetList(failure).elementAtOrNull(0)
        return fail("get failure returned")
    } catch (caught: IllegalStateException) {
        if (caught !== failure) return fail("get failure identity")
    }

    return "OK"
}
