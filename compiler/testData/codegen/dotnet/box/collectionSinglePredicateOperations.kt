private class SinglePredicateCountingIterable<T>(private val values: Array<T>) : Iterable<T> {
    var iteratorCalls: Int = 0
    var hasNextCalls: Int = 0
    var nextCalls: Int = 0

    override fun iterator(): Iterator<T> {
        iteratorCalls++
        return SinglePredicateCountingIterator(values, this)
    }
}

private class SinglePredicateCountingIterator<T>(
    private val values: Array<T>,
    private val owner: SinglePredicateCountingIterable<T>,
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

private fun singlePredicateFail(message: String): String = "fail: $message"

fun box(): String {
    var predicateCalls = 0
    val empty = SinglePredicateCountingIterable(emptyArray<Int>())
    try {
        empty.single { predicateCalls++; true }
        return singlePredicateFail("empty single returned")
    } catch (failure: NoSuchElementException) {
        if (failure.message != "Collection contains no element matching the predicate.") {
            return singlePredicateFail("empty single message")
        }
    }
    if (empty.singleOrNull { predicateCalls++; true } != null) {
        return singlePredicateFail("empty singleOrNull result")
    }
    if (predicateCalls != 0 || empty.iteratorCalls != 2 || empty.hasNextCalls != 2 || empty.nextCalls != 0) {
        return singlePredicateFail("empty protocol")
    }

    val unique = SinglePredicateCountingIterable(arrayOf(1, 2, 3))
    var trace = 0
    val single = unique.single { value ->
        trace = trace * 10 + value
        value == 2
    }
    if (single != 2 || trace != 123) return singlePredicateFail("single result/order")
    trace = 0
    val optional = unique.singleOrNull { value ->
        trace = trace * 10 + value
        value == 2
    }
    if (optional != 2 || trace != 123) return singlePredicateFail("singleOrNull result/order")
    if (unique.iteratorCalls != 2 || unique.hasNextCalls != 8 || unique.nextCalls != 6) {
        return singlePredicateFail("unique protocol")
    }

    val multiple = SinglePredicateCountingIterable(arrayOf(2, 1, 2, 3))
    predicateCalls = 0
    try {
        multiple.single { value -> predicateCalls++; value == 2 }
        return singlePredicateFail("multiple single returned")
    } catch (failure: IllegalArgumentException) {
        if (failure.message != "Collection contains more than one matching element.") {
            return singlePredicateFail("multiple single message")
        }
    }
    if (predicateCalls != 3 || multiple.hasNextCalls != 3 || multiple.nextCalls != 3) {
        return singlePredicateFail("multiple single stopping point")
    }
    predicateCalls = 0
    if (multiple.singleOrNull { value -> predicateCalls++; value == 2 } != null) {
        return singlePredicateFail("multiple singleOrNull result")
    }
    if (predicateCalls != 3 || multiple.hasNextCalls != 6 || multiple.nextCalls != 6) {
        return singlePredicateFail("multiple singleOrNull stopping point")
    }

    val nullable = SinglePredicateCountingIterable(arrayOf<String?>(null, "K"))
    var nullableCalls = 0
    val nullableSingle: String? = nullable.single { value -> nullableCalls++; value == null }
    val nullableOptional: String? = nullable.singleOrNull { value -> nullableCalls++; value == null }
    if (nullableSingle != null || nullableOptional != null || nullableCalls != 4) {
        return singlePredicateFail("nullable unique match")
    }

    val widened: Iterable<Any?> = SinglePredicateCountingIterable(arrayOf<String?>(null, "K"))
    if (widened.single { it == "K" } != "K" || widened.singleOrNull { it == "K" } != "K") {
        return singlePredicateFail("widened elements")
    }

    val operationFailure = Error("single predicate failure")
    val failing = SinglePredicateCountingIterable(arrayOf(1, 2, 3))
    predicateCalls = 0
    try {
        failing.singleOrNull { value ->
            predicateCalls++
            if (value == 2) throw operationFailure
            false
        }
        return singlePredicateFail("predicate failure was swallowed")
    } catch (caught: Throwable) {
        if (caught !== operationFailure) return singlePredicateFail("predicate failure identity")
    }
    if (predicateCalls != 2 || failing.hasNextCalls != 2 || failing.nextCalls != 2) {
        return singlePredicateFail("predicate failure timing")
    }

    var captured = 0
    if (unique.single { value -> captured += value; value == 2 } != 2 || captured != 6) {
        return singlePredicateFail("mutable capture")
    }
    return "OK"
}
