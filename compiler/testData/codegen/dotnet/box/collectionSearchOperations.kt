private class CountingSearchIterable<T>(private val values: Array<T>) : Iterable<T> {
    var iteratorCalls: Int = 0
    var hasNextCalls: Int = 0
    var nextCalls: Int = 0

    override fun iterator(): Iterator<T> {
        iteratorCalls++
        return CountingSearchIterator(values, this)
    }
}

private class CountingSearchIterator<T>(
    private val values: Array<T>,
    private val owner: CountingSearchIterable<T>,
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

private class SearchHaystackElement(val id: Int) {
    override fun equals(other: Any?): Boolean = throw Error("search compared from the item side")

    override fun hashCode(): Int = id
}

private class SearchNeedle(private val id: Int) {
    var equalsCalls: Int = 0

    override fun equals(other: Any?): Boolean {
        equalsCalls++
        return other is SearchHaystackElement && other.id == id
    }

    override fun hashCode(): Int = id
}

private class FailingSearchNeedle(private val failure: Throwable) {
    override fun equals(other: Any?): Boolean = throw failure

    override fun hashCode(): Int = 0
}

private class SearchFastPathCollection<T>(
    private val result: Boolean,
    private val failure: Throwable? = null,
) : Collection<T> {
    var containsCalls: Int = 0

    override val size: Int get() = throw Error("contains used Collection.size")

    override fun isEmpty(): Boolean = throw Error("contains used Collection.isEmpty()")

    override fun contains(element: T): Boolean {
        containsCalls++
        val currentFailure = failure
        if (currentFailure != null) throw currentFailure
        return result
    }

    override fun containsAll(elements: Collection<T>): Boolean =
        throw Error("contains used Collection.containsAll()")

    override fun iterator(): Iterator<T> = throw Error("contains used Collection.iterator()")
}

private class SearchFastPathList<T>(
    private val firstResult: Int,
    private val lastResult: Int,
    private val failure: Throwable? = null,
) : List<T> {
    var indexOfCalls: Int = 0
    var lastIndexOfCalls: Int = 0

    override val size: Int get() = throw Error("search used List.size")

    override fun isEmpty(): Boolean = throw Error("search used List.isEmpty()")

    override fun get(index: Int): T = throw Error("search used List.get()")

    override fun contains(element: T): Boolean = throw Error("search used List.contains()")

    override fun containsAll(elements: Collection<T>): Boolean =
        throw Error("search used List.containsAll()")

    override fun indexOf(element: T): Int {
        indexOfCalls++
        val currentFailure = failure
        if (currentFailure != null) throw currentFailure
        return firstResult
    }

    override fun lastIndexOf(element: T): Int {
        lastIndexOfCalls++
        val currentFailure = failure
        if (currentFailure != null) throw currentFailure
        return lastResult
    }

    override fun iterator(): Iterator<T> = throw Error("search used List.iterator()")

    override fun listIterator(): ListIterator<T> = throw Error("search used List.listIterator()")

    override fun listIterator(index: Int): ListIterator<T> =
        throw Error("search used List.listIterator(index)")

    override fun subList(fromIndex: Int, toIndex: Int): List<T> =
        throw Error("search used List.subList()")
}

private fun <T> genericContains(values: Iterable<T>, element: T): Boolean = values.contains(element)

private fun <T> genericIndexOf(values: Iterable<T>, element: T): Int = values.indexOf(element)

private fun <T> genericLastIndexOf(values: Iterable<T>, element: T): Int = values.lastIndexOf(element)

private fun fail(message: String): String = "fail: $message"

fun box(): String {
    val firstNeedle = SearchNeedle(2)
    val firstSearch = CountingSearchIterable<Any?>(
        arrayOf(SearchHaystackElement(1), SearchHaystackElement(2), SearchHaystackElement(2))
    )
    if (genericIndexOf(firstSearch, firstNeedle) != 1) return fail("first index")
    if (
        firstSearch.iteratorCalls != 1 ||
        firstSearch.hasNextCalls != 2 ||
        firstSearch.nextCalls != 2 ||
        firstNeedle.equalsCalls != 2
    ) {
        return fail("first index traversal")
    }

    val lastNeedle = SearchNeedle(2)
    val lastSearch = CountingSearchIterable<Any?>(
        arrayOf(SearchHaystackElement(2), SearchHaystackElement(1), SearchHaystackElement(2))
    )
    if (genericLastIndexOf(lastSearch, lastNeedle) != 2) return fail("last index")
    if (
        lastSearch.iteratorCalls != 1 ||
        lastSearch.hasNextCalls != 4 ||
        lastSearch.nextCalls != 3 ||
        lastNeedle.equalsCalls != 3
    ) {
        return fail("last index traversal")
    }

    val containsNeedle = SearchNeedle(2)
    val containsSearch = CountingSearchIterable<Any?>(
        arrayOf(SearchHaystackElement(1), SearchHaystackElement(2), SearchHaystackElement(3))
    )
    if (!genericContains(containsSearch, containsNeedle)) return fail("contains result")
    if (
        containsSearch.iteratorCalls != 1 ||
        containsSearch.hasNextCalls != 2 ||
        containsSearch.nextCalls != 2 ||
        containsNeedle.equalsCalls != 2
    ) {
        return fail("contains delegates to first search")
    }

    val empty = CountingSearchIterable(emptyArray<String>())
    if (genericContains(empty, "x") || genericIndexOf(empty, "x") != -1 || genericLastIndexOf(empty, "x") != -1) {
        return fail("empty search")
    }
    if (empty.iteratorCalls != 3 || empty.hasNextCalls != 3 || empty.nextCalls != 0) {
        return fail("empty traversal")
    }

    val nullable = CountingSearchIterable(arrayOf<String?>(null, "value", null))
    if (
        genericIndexOf(nullable, null) != 0 ||
        genericLastIndexOf(nullable, null) != 2 ||
        !genericContains(nullable, "value")
    ) {
        return fail("nullable search")
    }

    val primitives = CountingSearchIterable(arrayOf(1, 2, 1))
    val widened: Iterable<Any?> = primitives
    if (
        genericIndexOf(primitives, 1) != 0 ||
        genericLastIndexOf(primitives, 1) != 2 ||
        genericContains(widened, "1") ||
        genericIndexOf(widened, 2) != 1
    ) {
        return fail("primitive and widened search")
    }

    val collection = SearchFastPathCollection<String>(true)
    val collectionAsIterable: Iterable<String> = collection
    if (!genericContains(collectionAsIterable, "present")) return fail("Collection contains fast path")
    if (collection.containsCalls != 1) return fail("Collection contains calls ${collection.containsCalls}")

    val list = SearchFastPathList<String>(4, 9)
    val listAsIterable: Iterable<String> = list
    if (genericIndexOf(listAsIterable, "first") != 4) return fail("List indexOf fast path")
    if (genericLastIndexOf(listAsIterable, "last") != 9) return fail("List lastIndexOf fast path")
    if (list.indexOfCalls != 1 || list.lastIndexOfCalls != 1) return fail("List fast-path calls")

    val equalityFailure = IllegalStateException("search equality failure")
    try {
        genericIndexOf(
            CountingSearchIterable<Any?>(arrayOf(SearchHaystackElement(1))),
            FailingSearchNeedle(equalityFailure),
        )
        return fail("equality failure result")
    } catch (caught: Throwable) {
        if (caught !== equalityFailure) return fail("equality failure identity")
    }

    val collectionFailure = IllegalArgumentException("Collection contains failure")
    val failingCollection = SearchFastPathCollection<String>(false, collectionFailure)
    try {
        genericContains(failingCollection, "x")
        return fail("Collection failure result")
    } catch (caught: Throwable) {
        if (caught !== collectionFailure) return fail("Collection failure identity")
    }
    if (failingCollection.containsCalls != 1) return fail("Collection failure calls")

    val listFailure = UnsupportedOperationException("List search failure")
    val failingList = SearchFastPathList<String>(-1, -1, listFailure)
    try {
        genericLastIndexOf(failingList, "x")
        return fail("List failure result")
    } catch (caught: Throwable) {
        if (caught !== listFailure) return fail("List failure identity")
    }
    if (failingList.indexOfCalls != 0 || failingList.lastIndexOfCalls != 1) {
        return fail("List failure calls")
    }

    return "OK"
}
