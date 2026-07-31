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
        throw Error("cardinality operation used List.iterator()")

    override fun listIterator(): ListIterator<T> =
        throw Error("cardinality operation used List.listIterator()")

    override fun listIterator(index: Int): ListIterator<T> =
        throw Error("cardinality operation used List.listIterator(index)")

    override fun subList(fromIndex: Int, toIndex: Int): List<T> =
        throw Error("cardinality operation used List.subList()")
}

private class CountFastPathCollection<T>(private val reportedSize: Int) : Collection<T> {
    var sizeCalls: Int = 0

    override val size: Int
        get() {
            sizeCalls++
            return reportedSize
        }

    override fun isEmpty(): Boolean = throw Error("count used Collection.isEmpty()")

    override fun contains(element: T): Boolean = throw Error("count used Collection.contains()")

    override fun containsAll(elements: Collection<T>): Boolean =
        throw Error("count used Collection.containsAll()")

    override fun iterator(): Iterator<T> = throw Error("count used Collection.iterator()")
}

private class FailingCountIterable<T>(private val failure: Throwable) : Iterable<T> {
    override fun iterator(): Iterator<T> = FailingCountIterator(failure)
}

private class FailingCountIterator<T>(private val failure: Throwable) : Iterator<T> {
    override fun hasNext(): Boolean = true

    override fun next(): T = throw failure
}

private fun <T> genericCount(values: Iterable<T>): Int = values.count()

private fun <T> genericSingle(values: Iterable<T>): T = values.single()

private fun <T> genericSingleOrNull(values: Iterable<T>): T? = values.singleOrNull()

private fun fail(message: String): String = "fail: $message"

fun box(): String {
    val countedEmpty = CountingIterable(emptyArray<String>())
    if (genericCount(countedEmpty) != 0) return fail("empty count")
    if (countedEmpty.iteratorCalls != 1 || countedEmpty.hasNextCalls != 1 || countedEmpty.nextCalls != 0) {
        return fail("empty count traversal")
    }

    val countedSingleton = CountingIterable(arrayOf<String?>(null))
    if (genericCount(countedSingleton) != 1) return fail("nullable singleton count")
    if (
        countedSingleton.iteratorCalls != 1 ||
        countedSingleton.hasNextCalls != 2 ||
        countedSingleton.nextCalls != 1
    ) {
        return fail("singleton count traversal")
    }

    val countedMultiple = CountingIterable(arrayOf(2, 3, 5))
    val countedWidened: Iterable<Any?> = countedMultiple
    if (genericCount(countedWidened) != 3) return fail("widened multiple count")
    if (
        countedMultiple.iteratorCalls != 1 ||
        countedMultiple.hasNextCalls != 4 ||
        countedMultiple.nextCalls != 3
    ) {
        return fail("multiple count traversal")
    }

    val countFastPath = CountFastPathCollection<String>(Int.MAX_VALUE)
    val collectionAsIterable: Iterable<String> = countFastPath
    if (genericCount(collectionAsIterable) != Int.MAX_VALUE) return fail("Collection count fast path")
    if (countFastPath.sizeCalls != 1) return fail("Collection count size reads ${countFastPath.sizeCalls}")

    val countFailure = IllegalStateException("count next failure")
    try {
        genericCount(FailingCountIterable<Int>(countFailure))
        return fail("count next failure result")
    } catch (caught: Throwable) {
        if (caught !== countFailure) return fail("count next failure identity")
    }

    val nonEmpty = CountingIterable(arrayOf(3, 5))
    if (!nonEmpty.any() || nonEmpty.none()) return fail("non-empty query")
    if (
        nonEmpty.iteratorCalls != 2 ||
        nonEmpty.hasNextCalls != 2 ||
        nonEmpty.nextCalls != 0
    ) {
        return fail("query traversal")
    }

    val empty = CountingIterable(emptyArray<String>())
    if (empty.any() || !empty.none()) return fail("empty query")
    if (empty.iteratorCalls != 2 || empty.hasNextCalls != 2 || empty.nextCalls != 0) {
        return fail("empty query traversal")
    }

    val singleton = CountingIterable(arrayOf(7))
    val widened: Iterable<Any?> = singleton
    if (genericSingle(widened) != 7) return fail("widened single")
    if (
        singleton.iteratorCalls != 1 ||
        singleton.hasNextCalls != 2 ||
        singleton.nextCalls != 1
    ) {
        return fail("single traversal")
    }
    if (genericSingleOrNull(singleton) != 7) return fail("singleOrNull")
    if (
        singleton.iteratorCalls != 2 ||
        singleton.hasNextCalls != 4 ||
        singleton.nextCalls != 2
    ) {
        return fail("singleOrNull traversal")
    }

    val nullable = CountingIterable(arrayOf<String?>(null))
    if (nullable.singleOrNull() != null) return fail("nullable singleton")
    if (nullable.iteratorCalls != 1 || nullable.hasNextCalls != 2 || nullable.nextCalls != 1) {
        return fail("nullable traversal")
    }

    val multiple = CountingIterable(arrayOf("a", "b"))
    try {
        multiple.single()
        return fail("multiple single result")
    } catch (failure: IllegalArgumentException) {
        if (failure.message != "Collection has more than one element.") {
            return fail("multiple single message ${failure.message}")
        }
    }
    if (multiple.iteratorCalls != 1 || multiple.hasNextCalls != 2 || multiple.nextCalls != 1) {
        return fail("multiple single traversal")
    }
    if (multiple.singleOrNull() != null) return fail("multiple singleOrNull")
    if (multiple.iteratorCalls != 2 || multiple.hasNextCalls != 4 || multiple.nextCalls != 2) {
        return fail("multiple singleOrNull traversal")
    }

    try {
        empty.single()
        return fail("empty single result")
    } catch (failure: NoSuchElementException) {
        if (failure.message != "Collection is empty.") {
            return fail("empty single message ${failure.message}")
        }
    }
    if (empty.singleOrNull() != null) return fail("empty singleOrNull")
    if (empty.iteratorCalls != 4 || empty.hasNextCalls != 4 || empty.nextCalls != 0) {
        return fail("empty cardinality traversal")
    }

    val list = IteratorTrapList(arrayOf(11))
    val listAsIterable: Iterable<Int> = list
    if (
        !listAsIterable.any() ||
        listAsIterable.none() ||
        genericSingle(listAsIterable) != 11 ||
        genericSingleOrNull(listAsIterable) != 11
    ) {
        return fail("List fast path")
    }
    if (list.getCalls != 2) return fail("singleton List indexed access")

    val emptyList = IteratorTrapList(emptyArray<Int>())
    if (emptyList.any() || !emptyList.none() || genericSingleOrNull(emptyList) != null) {
        return fail("empty List query")
    }
    try {
        genericSingle(emptyList)
        return fail("empty List single result")
    } catch (failure: NoSuchElementException) {
        if (failure.message != "List is empty.") {
            return fail("empty List single message ${failure.message}")
        }
    }
    if (emptyList.getCalls != 0) return fail("empty List get")

    val multipleList = IteratorTrapList(arrayOf(1, 2))
    if (!multipleList.any() || multipleList.none() || genericSingleOrNull(multipleList) != null) {
        return fail("multiple List query")
    }
    try {
        genericSingle(multipleList)
        return fail("multiple List single result")
    } catch (failure: IllegalArgumentException) {
        if (failure.message != "List has more than one element.") {
            return fail("multiple List single message ${failure.message}")
        }
    }
    if (multipleList.getCalls != 0) return fail("multiple List get")

    return "OK"
}
