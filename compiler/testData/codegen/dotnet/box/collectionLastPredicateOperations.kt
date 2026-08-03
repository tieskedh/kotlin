private class LastPredicateCountingIterable<T>(private val values: Array<T>) : Iterable<T> {
    var iteratorCalls: Int = 0
    var hasNextCalls: Int = 0
    var nextCalls: Int = 0

    override fun iterator(): Iterator<T> {
        iteratorCalls++
        return LastPredicateCountingIterator(values, this)
    }
}

private class LastPredicateCountingIterator<T>(
    private val values: Array<T>,
    private val owner: LastPredicateCountingIterable<T>,
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

private fun lastPredicateFail(message: String): String = "fail: $message"

fun box(): String {
    var predicateCalls = 0
    val empty = LastPredicateCountingIterable(emptyArray<Int>())
    try {
        empty.last { predicateCalls++; true }
        return lastPredicateFail("empty last returned")
    } catch (failure: NoSuchElementException) {
        if (failure.message != "Collection contains no element matching the predicate.") {
            return lastPredicateFail("empty last message")
        }
    }
    if (empty.lastOrNull { predicateCalls++; true } != null) {
        return lastPredicateFail("empty lastOrNull result")
    }
    if (predicateCalls != 0 || empty.iteratorCalls != 2 || empty.hasNextCalls != 2 || empty.nextCalls != 0) {
        return lastPredicateFail("empty protocol")
    }

    val matching = LastPredicateCountingIterable(arrayOf(1, 2, 3, 2))
    var trace = 0
    val last = matching.last { value ->
        trace = trace * 10 + value
        value == 2
    }
    if (last != 2 || trace != 1232) return lastPredicateFail("last result/order")
    if (matching.iteratorCalls != 1 || matching.hasNextCalls != 5 || matching.nextCalls != 4) {
        return lastPredicateFail("last traversal")
    }

    trace = 0
    val optional = matching.lastOrNull { value ->
        trace = trace * 10 + value
        value == 2
    }
    if (optional != 2 || trace != 1232) return lastPredicateFail("lastOrNull result/order")
    if (matching.iteratorCalls != 2 || matching.hasNextCalls != 10 || matching.nextCalls != 8) {
        return lastPredicateFail("lastOrNull traversal")
    }

    val missing = LastPredicateCountingIterable(arrayOf(4, 5))
    try {
        missing.last { value -> predicateCalls++; value == 9 }
        return lastPredicateFail("missing last returned")
    } catch (failure: NoSuchElementException) {
        if (failure.message != "Collection contains no element matching the predicate.") {
            return lastPredicateFail("missing last message")
        }
    }
    if (missing.lastOrNull { value -> predicateCalls++; value == 9 } != null) {
        return lastPredicateFail("missing lastOrNull result")
    }
    if (predicateCalls != 4 || missing.iteratorCalls != 2 || missing.hasNextCalls != 6 || missing.nextCalls != 4) {
        return lastPredicateFail("missing protocol")
    }

    val nullable = LastPredicateCountingIterable(arrayOf<String?>(null, "K", null))
    var nullableCalls = 0
    val nullableLast: String? = nullable.last { value -> nullableCalls++; value == null }
    val nullableOptional: String? = nullable.lastOrNull { value -> nullableCalls++; value == null }
    if (nullableLast != null || nullableOptional != null || nullableCalls != 6) {
        return lastPredicateFail("nullable last match")
    }
    if (nullable.nextCalls != 6 || nullable.hasNextCalls != 8) {
        return lastPredicateFail("nullable full traversal")
    }

    val widened: Iterable<Any?> = LastPredicateCountingIterable(arrayOf<String?>(null, "K", null))
    if (widened.last { it == "K" } != "K" || widened.lastOrNull { it == "K" } != "K") {
        return lastPredicateFail("widened elements")
    }

    val operationFailure = Error("last predicate failure")
    val failing = LastPredicateCountingIterable(arrayOf(1, 2, 3))
    predicateCalls = 0
    try {
        failing.lastOrNull { value ->
            predicateCalls++
            if (value == 2) throw operationFailure
            false
        }
        return lastPredicateFail("predicate failure was swallowed")
    } catch (caught: Throwable) {
        if (caught !== operationFailure) return lastPredicateFail("predicate failure identity")
    }
    if (predicateCalls != 2 || failing.hasNextCalls != 2 || failing.nextCalls != 2) {
        return lastPredicateFail("predicate failure timing")
    }

    var captured = 0
    if (matching.last { value -> captured += value; value == 2 } != 2 || captured != 8) {
        return lastPredicateFail("mutable capture")
    }

    return "OK"
}
