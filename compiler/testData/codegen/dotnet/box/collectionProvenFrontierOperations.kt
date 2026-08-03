@file:Suppress("DEPRECATION")

private class FrontierCountingIterable<T>(private val values: Array<T>) : Iterable<T> {
    var iteratorCalls: Int = 0
    var hasNextCalls: Int = 0
    var nextCalls: Int = 0

    override fun iterator(): Iterator<T> {
        iteratorCalls++
        return FrontierCountingIterator(values, this)
    }
}

private class FrontierCountingIterator<T>(
    private val values: Array<T>,
    private val owner: FrontierCountingIterable<T>,
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

private class FrontierNamedNullableIterable(private val values: Array<String?>) : Iterable<String?> {
    var iteratorCalls: Int = 0
    var hasNextCalls: Int = 0
    var nextCalls: Int = 0

    override fun iterator(): Iterator<String?> {
        iteratorCalls++
        return FrontierNamedNullableIterator(values, this)
    }

    override fun toString(): String = "guarded"
}

private class FrontierNamedNullableIterator(
    private val values: Array<String?>,
    private val owner: FrontierNamedNullableIterable,
) : Iterator<String?> {
    private var index: Int = 0

    override fun hasNext(): Boolean {
        owner.hasNextCalls++
        return index < values.size
    }

    override fun next(): String? {
        if (index >= values.size) throw NoSuchElementException()
        owner.nextCalls++
        return values[index++]
    }
}

private class FrontierFailingIterable(private val failure: Throwable) : Iterable<String?> {
    var iteratorCalls: Int = 0
    var nextCalls: Int = 0

    override fun iterator(): Iterator<String?> {
        iteratorCalls++
        return FrontierFailingIterator(this, failure)
    }
}

private class FrontierFailingIterator(
    private val owner: FrontierFailingIterable,
    private val failure: Throwable,
) : Iterator<String?> {
    override fun hasNext(): Boolean = true

    override fun next(): String? {
        owner.nextCalls++
        throw failure
    }
}

private class FrontierNamedNullableList(private val values: Array<String?>) : List<String?> {
    var iteratorCalls: Int = 0
    var hasNextCalls: Int = 0
    var nextCalls: Int = 0
    var getCalls: Int = 0

    override val size: Int get() = values.size

    override fun isEmpty(): Boolean = values.size == 0

    override fun get(index: Int): String? {
        getCalls++
        throw Error("requireNoNulls used List.get()")
    }

    override fun contains(element: String?): Boolean = throw Error("requireNoNulls used List.contains()")

    override fun containsAll(elements: Collection<String?>): Boolean =
        throw Error("requireNoNulls used List.containsAll()")

    override fun indexOf(element: String?): Int = throw Error("requireNoNulls used List.indexOf()")

    override fun lastIndexOf(element: String?): Int = throw Error("requireNoNulls used List.lastIndexOf()")

    override fun iterator(): Iterator<String?> {
        iteratorCalls++
        return FrontierNamedNullableListIterator(values, this)
    }

    override fun listIterator(): ListIterator<String?> =
        throw Error("requireNoNulls used List.listIterator()")

    override fun listIterator(index: Int): ListIterator<String?> =
        throw Error("requireNoNulls used List.listIterator(index)")

    override fun subList(fromIndex: Int, toIndex: Int): List<String?> =
        throw Error("requireNoNulls used List.subList()")

    override fun toString(): String = "guarded-list"
}

private class FrontierNamedNullableListIterator(
    private val values: Array<String?>,
    private val owner: FrontierNamedNullableList,
) : Iterator<String?> {
    private var index: Int = 0

    override fun hasNext(): Boolean {
        owner.hasNextCalls++
        return index < values.size
    }

    override fun next(): String? {
        if (index >= values.size) throw NoSuchElementException()
        owner.nextCalls++
        return values[index++]
    }
}

private fun frontierFail(message: String): String = "fail: $message"

@Suppress("DEPRECATION")
fun box(): String {
    var selectorCalls = 0
    val empty = emptyArray<Int>().asIterable().sumBy { value ->
        selectorCalls++
        value
    }
    if (empty != 0 || selectorCalls != 0) return frontierFail("empty sumBy")
    val emptyDouble = emptyArray<Int>().asIterable().sumByDouble { value ->
        selectorCalls++
        value.toDouble()
    }
    if (emptyDouble != 0.0 || selectorCalls != 0) return frontierFail("empty sumByDouble")

    if (arrayOf(Int.MAX_VALUE, 1).asIterable().sumBy { it } != Int.MIN_VALUE) {
        return frontierFail("sumBy overflow")
    }

    val counting = FrontierCountingIterable(arrayOf(1, 2, 3))
    var trace = 0
    var captured = 4
    val counted = counting.sumBy { value ->
        trace = trace * 10 + value
        captured += value
        value
    }
    if (counted != 6 || trace != 123 || captured != 10) return frontierFail("sumBy result")
    if (counting.iteratorCalls != 1 || counting.hasNextCalls != 4 || counting.nextCalls != 3) {
        return frontierFail("sumBy traversal")
    }

    val widened: Iterable<Any?> = arrayOf<Any?>(null, "K", 3).asIterable()
    if (widened.sumBy { value -> if (value == null) 1 else 2 } != 5) {
        return frontierFail("sumBy widened nullable")
    }
    val ordered = arrayOf(1.0e16, 1.0, -1.0e16).asIterable().sumByDouble { it }
    if (ordered != 0.0) return frontierFail("sumByDouble order")
    val sumNaN = arrayOf(1.0, Double.NaN).asIterable().sumByDouble { it }
    if (sumNaN == sumNaN) return frontierFail("sumByDouble NaN")

    val selectorFailure = Error("selector failure")
    var failingSelectorCalls = 0
    try {
        arrayOf(1, 2, 3).asIterable().sumByDouble { value ->
            failingSelectorCalls++
            if (value == 2) throw selectorFailure
            value.toDouble()
        }
        return frontierFail("selector failure swallowed")
    } catch (caught: Throwable) {
        if (caught !== selectorFailure) return frontierFail("selector failure identity")
    }
    if (failingSelectorCalls != 2) return frontierFail("selector failure timing")

    val guarded = FrontierNamedNullableIterable(arrayOf("a", "b"))
    val guardedResult: Iterable<String> = guarded.requireNoNulls()
    if ((guardedResult as Any) !== (guarded as Any)) return frontierFail("Iterable guard identity")
    if (guarded.iteratorCalls != 1 || guarded.hasNextCalls != 3 || guarded.nextCalls != 2) {
        return frontierFail("Iterable guard traversal")
    }

    val guardedList = FrontierNamedNullableList(arrayOf("a", "b"))
    val guardedListResult: List<String> = guardedList.requireNoNulls()
    if ((guardedListResult as Any) !== (guardedList as Any)) return frontierFail("List guard identity")
    if (
        guardedList.iteratorCalls != 1 || guardedList.hasNextCalls != 3 ||
        guardedList.nextCalls != 2 || guardedList.getCalls != 0
    ) {
        return frontierFail("List guard traversal")
    }

    val containsNull = FrontierNamedNullableIterable(arrayOf("a", null, "later"))
    try {
        containsNull.requireNoNulls()
        return frontierFail("null guard returned")
    } catch (caught: IllegalArgumentException) {
        if (caught.message != "null element found in guarded.") return frontierFail("null guard message")
    }
    if (containsNull.iteratorCalls != 1 || containsNull.hasNextCalls != 2 || containsNull.nextCalls != 2) {
        return frontierFail("null guard stopping point")
    }

    val listContainsNull = FrontierNamedNullableList(arrayOf("a", null, "later"))
    try {
        listContainsNull.requireNoNulls()
        return frontierFail("List null guard returned")
    } catch (caught: IllegalArgumentException) {
        if (caught.message != "null element found in guarded-list.") return frontierFail("List null guard message")
    }
    if (
        listContainsNull.iteratorCalls != 1 || listContainsNull.hasNextCalls != 2 ||
        listContainsNull.nextCalls != 2 || listContainsNull.getCalls != 0
    ) {
        return frontierFail("List null guard stopping point")
    }

    val iteratorFailure = Error("guard iterator failure")
    val failingIterator = FrontierFailingIterable(iteratorFailure)
    try {
        failingIterator.requireNoNulls()
        return frontierFail("iterator failure swallowed")
    } catch (caught: Throwable) {
        if (caught !== iteratorFailure) return frontierFail("iterator failure identity")
    }
    if (failingIterator.iteratorCalls != 1 || failingIterator.nextCalls != 1) {
        return frontierFail("iterator failure timing")
    }

    return "OK"
}
