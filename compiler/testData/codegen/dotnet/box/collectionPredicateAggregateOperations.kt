private class PredicateAggregateCountingIterable<T>(private val values: Array<T>) : Iterable<T> {
    var iteratorCalls: Int = 0
    var hasNextCalls: Int = 0
    var nextCalls: Int = 0

    override fun iterator(): Iterator<T> {
        iteratorCalls++
        return PredicateAggregateCountingIterator(values, this)
    }
}

private class PredicateAggregateCountingIterator<T>(
    private val values: Array<T>,
    private val owner: PredicateAggregateCountingIterable<T>,
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

private class PredicateAggregateHostileEmptyCollection<T> : Collection<T> {
    var isEmptyCalls: Int = 0

    override val size: Int get() = throw Error("empty fast path requested size")

    override fun isEmpty(): Boolean {
        isEmptyCalls++
        return true
    }

    override fun contains(element: T): Boolean = throw Error("empty fast path requested contains")

    override fun containsAll(elements: Collection<T>): Boolean =
        throw Error("empty fast path requested containsAll")

    override fun iterator(): Iterator<T> = throw Error("empty fast path requested iterator")
}

private fun predicateAggregateFail(message: String): String = "fail: $message"

fun box(): String {
    val empty = PredicateAggregateHostileEmptyCollection<Int>()
    var predicateCalls = 0
    if (!empty.none { predicateCalls++; true }) return predicateAggregateFail("empty none")
    if (empty.count { predicateCalls++; true } != 0) return predicateAggregateFail("empty count")
    if (predicateCalls != 0 || empty.isEmptyCalls != 2) {
        return predicateAggregateFail("empty Collection fast path")
    }

    val noneCounting = PredicateAggregateCountingIterable(arrayOf(1, 2, 3, 4))
    var trace = 0
    if (noneCounting.none { value -> trace = trace * 10 + value; value == 2 }) {
        return predicateAggregateFail("none decisive result")
    }
    if (trace != 12 || noneCounting.iteratorCalls != 1 || noneCounting.hasNextCalls != 2 || noneCounting.nextCalls != 2) {
        return predicateAggregateFail("none stopping point")
    }

    val noMatch = PredicateAggregateCountingIterable(arrayOf(1, 3))
    predicateCalls = 0
    if (!noMatch.none { value -> predicateCalls++; value == 2 }) {
        return predicateAggregateFail("none no-match result")
    }
    if (predicateCalls != 2 || noMatch.hasNextCalls != 3 || noMatch.nextCalls != 2) {
        return predicateAggregateFail("none no-match traversal")
    }

    val countCounting = PredicateAggregateCountingIterable(arrayOf(1, 2, 3, 4))
    trace = 0
    val evenCount = countCounting.count { value -> trace = trace * 10 + value; value % 2 == 0 }
    if (evenCount != 2 || trace != 1234) return predicateAggregateFail("count result/order")
    if (countCounting.iteratorCalls != 1 || countCounting.hasNextCalls != 5 || countCounting.nextCalls != 4) {
        return predicateAggregateFail("count traversal")
    }

    val nullable: Iterable<Any?> =
        PredicateAggregateCountingIterable(arrayOf<String?>(null, "K", null))
    if (!nullable.none { it == "missing" } || nullable.count { it == null } != 2) {
        return predicateAggregateFail("nullable/widened predicates")
    }

    val operationFailure = Error("predicate aggregate failure")
    val failing = PredicateAggregateCountingIterable(arrayOf(1, 2, 3))
    predicateCalls = 0
    try {
        failing.count { value ->
            predicateCalls++
            if (value == 2) throw operationFailure
            true
        }
        return predicateAggregateFail("predicate failure was swallowed")
    } catch (caught: Throwable) {
        if (caught !== operationFailure) return predicateAggregateFail("predicate failure identity")
    }
    if (predicateCalls != 2 || failing.hasNextCalls != 2 || failing.nextCalls != 2) {
        return predicateAggregateFail("predicate failure timing")
    }

    var captured = 0
    if (countCounting.count { value -> captured += value; value > 2 } != 2 || captured != 10) {
        return predicateAggregateFail("mutable capture")
    }

    return "OK"
}
