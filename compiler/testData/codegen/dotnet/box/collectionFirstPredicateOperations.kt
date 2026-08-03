private class FirstPredicateCountingIterable<T>(private val values: Array<T>) : Iterable<T> {
    var iteratorCalls: Int = 0
    var hasNextCalls: Int = 0
    var nextCalls: Int = 0

    override fun iterator(): Iterator<T> {
        iteratorCalls++
        return FirstPredicateCountingIterator(values, this)
    }
}

private class FirstPredicateCountingIterator<T>(
    private val values: Array<T>,
    private val owner: FirstPredicateCountingIterable<T>,
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

private fun firstPredicateFail(message: String): String = "fail: $message"

fun box(): String {
    var predicateCalls = 0
    val empty = FirstPredicateCountingIterable(emptyArray<Int>())
    try {
        empty.first { predicateCalls++; true }
        return firstPredicateFail("empty first returned")
    } catch (failure: NoSuchElementException) {
        if (failure.message != "Collection contains no element matching the predicate.") {
            return firstPredicateFail("empty first message")
        }
    }
    if (empty.firstOrNull { predicateCalls++; true } != null) {
        return firstPredicateFail("empty firstOrNull result")
    }
    if (predicateCalls != 0 || empty.iteratorCalls != 2 || empty.hasNextCalls != 2 || empty.nextCalls != 0) {
        return firstPredicateFail("empty protocol")
    }

    val matching = FirstPredicateCountingIterable(arrayOf(1, 2, 3))
    var trace = 0
    val first = matching.first { value ->
        trace = trace * 10 + value
        value == 2
    }
    if (first != 2 || trace != 12) return firstPredicateFail("first short circuit")
    if (matching.iteratorCalls != 1 || matching.hasNextCalls != 2 || matching.nextCalls != 2) {
        return firstPredicateFail("first traversal")
    }

    trace = 0
    val optional = matching.firstOrNull { value ->
        trace = trace * 10 + value
        value == 2
    }
    if (optional != 2 || trace != 12) return firstPredicateFail("firstOrNull short circuit")
    if (matching.iteratorCalls != 2 || matching.hasNextCalls != 4 || matching.nextCalls != 4) {
        return firstPredicateFail("firstOrNull traversal")
    }

    val missing = FirstPredicateCountingIterable(arrayOf(4, 5, 6))
    try {
        missing.first { value -> predicateCalls++; value == 9 }
        return firstPredicateFail("missing first returned")
    } catch (failure: NoSuchElementException) {
        if (failure.message != "Collection contains no element matching the predicate.") {
            return firstPredicateFail("missing first message")
        }
    }
    if (missing.firstOrNull { value -> predicateCalls++; value == 9 } != null) {
        return firstPredicateFail("missing firstOrNull result")
    }
    if (predicateCalls != 6 || missing.iteratorCalls != 2 || missing.hasNextCalls != 8 || missing.nextCalls != 6) {
        return firstPredicateFail("missing protocol")
    }

    val nullable = FirstPredicateCountingIterable(arrayOf<String?>(null, "K"))
    var nullableCalls = 0
    val nullableFirst: String? = nullable.first { value -> nullableCalls++; value == null }
    val nullableOptional: String? = nullable.firstOrNull { value -> nullableCalls++; value == null }
    if (nullableFirst != null || nullableOptional != null || nullableCalls != 2) {
        return firstPredicateFail("nullable match")
    }
    if (nullable.nextCalls != 2 || nullable.hasNextCalls != 2) {
        return firstPredicateFail("nullable match short circuit")
    }

    val widened: Iterable<Any?> = FirstPredicateCountingIterable(arrayOf<String?>(null, "K"))
    if (widened.first { it == "K" } != "K" || widened.firstOrNull { it == null } != null) {
        return firstPredicateFail("widened elements")
    }

    val operationFailure = Error("first predicate failure")
    val failing = FirstPredicateCountingIterable(arrayOf(1, 2, 3))
    predicateCalls = 0
    try {
        failing.firstOrNull { value ->
            predicateCalls++
            if (value == 2) throw operationFailure
            false
        }
        return firstPredicateFail("predicate failure was swallowed")
    } catch (caught: Throwable) {
        if (caught !== operationFailure) return firstPredicateFail("predicate failure identity")
    }
    if (predicateCalls != 2 || failing.hasNextCalls != 2 || failing.nextCalls != 2) {
        return firstPredicateFail("predicate failure timing")
    }

    var captured = 0
    if (matching.first { value -> captured += value; value == 3 } != 3 || captured != 6) {
        return firstPredicateFail("mutable capture")
    }

    return "OK"
}
