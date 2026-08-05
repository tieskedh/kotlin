private class ForEachCountingIterable<T>(private val values: Array<T>) : Iterable<T> {
    var iteratorCalls: Int = 0
    var hasNextCalls: Int = 0
    var nextCalls: Int = 0
    var callbackState: Int = 0

    override fun iterator(): Iterator<T> {
        iteratorCalls++
        return ForEachCountingIterator(values, this)
    }
}

private class ForEachFailingIterable<T>(private val failure: Throwable) : Iterable<T> {
    var iteratorCalls: Int = 0

    override fun iterator(): Iterator<T> {
        iteratorCalls++
        throw failure
    }
}

private fun onEachNonLocal(values: Iterable<Int>): Int {
    values.onEach { value ->
        if (value == 2) return 23
    }
    return -1
}

private fun onEachIndexedNonLocal(values: Iterable<Int>): Int {
    values.onEachIndexed { index, _ ->
        if (index == 1) return 29
    }
    return -1
}

private class ForEachCountingIterator<T>(
    private val values: Array<T>,
    private val owner: ForEachCountingIterable<T>,
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

private fun forEachFail(message: String): String = "fail: $message"

fun box(): String {
    val empty = ForEachCountingIterable(emptyArray<Int>())
    var callbacks = 0
    empty.forEach { callbacks++ }
    empty.forEachIndexed { _, _ -> callbacks++ }
    if (callbacks != 0 || empty.iteratorCalls != 2 || empty.hasNextCalls != 2 || empty.nextCalls != 0) {
        return forEachFail("empty protocol")
    }

    val observedEmpty = ForEachCountingIterable(emptyArray<Int>())
    val observedEmptyResult: ForEachCountingIterable<Int> = observedEmpty.onEach { callbacks++ }
    val observedEmptyIndexedResult: ForEachCountingIterable<Int> =
        observedEmpty.onEachIndexed { _, _ -> callbacks++ }
    if (observedEmptyResult !== observedEmpty || observedEmptyIndexedResult !== observedEmpty) {
        return forEachFail("empty onEach identity")
    }
    if (
        callbacks != 0 || observedEmpty.iteratorCalls != 2 ||
        observedEmpty.hasNextCalls != 2 || observedEmpty.nextCalls != 0
    ) {
        return forEachFail("empty onEach protocol")
    }

    val singleton = ForEachCountingIterable(arrayOf<Int?>(null))
    var singletonTrace = ""
    singleton.forEach { value -> singletonTrace += "v=$value|" }
    singleton.forEachIndexed { index, value -> singletonTrace += "$index=$value|" }
    if (singletonTrace != "v=null|0=null|") return forEachFail("nullable singleton callbacks")
    if (singleton.iteratorCalls != 2 || singleton.hasNextCalls != 4 || singleton.nextCalls != 2) {
        return forEachFail("singleton protocol")
    }

    val values = ForEachCountingIterable(arrayOf(4, 5, 6))
    var valueTrace = ""
    values.forEach { value -> valueTrace += "$value|" }
    if (valueTrace != "4|5|6|") return forEachFail("forEach order")

    var indexedTrace = ""
    values.forEachIndexed { index, value -> indexedTrace += "$index:$value|" }
    if (indexedTrace != "0:4|1:5|2:6|") return forEachFail("forEachIndexed order")
    if (values.iteratorCalls != 2 || values.hasNextCalls != 8 || values.nextCalls != 6) {
        return forEachFail("value traversal")
    }

    val observed = ForEachCountingIterable(arrayOf(4, 5, 6))
    val observedResult: ForEachCountingIterable<Int> = observed.onEach { value ->
        observed.callbackState = observed.callbackState * 10 + value
    }
    if (observedResult !== observed || observed.callbackState != 456) {
        return forEachFail("onEach same receiver")
    }
    val observedIndexedResult: ForEachCountingIterable<Int> = observed.onEachIndexed { index, value ->
        observed.callbackState += index * value
    }
    if (observedIndexedResult !== observed || observed.callbackState != 473) {
        return forEachFail("onEachIndexed same receiver")
    }
    if (observed.iteratorCalls != 2 || observed.hasNextCalls != 8 || observed.nextCalls != 6) {
        return forEachFail("onEach traversal")
    }

    val widened = ForEachCountingIterable<Any?>(arrayOf<Any?>(null, "K", 3))
    var widenedTrace = ""
    val widenedResult: ForEachCountingIterable<Any?> = widened.onEachIndexed { index, value ->
        widenedTrace += "$index=$value|"
    }
    if (widenedResult !== widened || widenedTrace != "0=null|1=K|2=3|") {
        return forEachFail("widened nullable onEachIndexed")
    }

    var captured = 1
    values.forEach { value -> captured = captured * 10 + value }
    if (captured != 1456) return forEachFail("mutable capture")

    val operationFailure = Error("forEach operation failure")
    val failing = ForEachCountingIterable(arrayOf(1, 2, 3))
    callbacks = 0
    try {
        failing.forEach { value ->
            callbacks++
            if (value == 2) throw operationFailure
        }
        return forEachFail("operation failure was swallowed")
    } catch (caught: Throwable) {
        if (caught !== operationFailure) return forEachFail("operation failure identity")
    }
    if (callbacks != 2 || failing.hasNextCalls != 2 || failing.nextCalls != 2) {
        return forEachFail("operation failure timing")
    }

    val onEachFailure = Error("onEach operation failure")
    val failingOnEach = ForEachCountingIterable(arrayOf(1, 2, 3))
    callbacks = 0
    try {
        failingOnEach.onEachIndexed { index, _ ->
            callbacks++
            if (index == 1) throw onEachFailure
        }
        return forEachFail("onEach operation failure was swallowed")
    } catch (caught: Throwable) {
        if (caught !== onEachFailure) return forEachFail("onEach operation failure identity")
    }
    if (callbacks != 2 || failingOnEach.hasNextCalls != 2 || failingOnEach.nextCalls != 2) {
        return forEachFail("onEach operation failure timing")
    }

    val iteratorFailure = Error("onEach iterator failure")
    val failingIterator = ForEachFailingIterable<Int>(iteratorFailure)
    try {
        failingIterator.onEach { }
        return forEachFail("onEach iterator failure was swallowed")
    } catch (caught: Throwable) {
        if (caught !== iteratorFailure) return forEachFail("onEach iterator failure identity")
    }
    if (failingIterator.iteratorCalls != 1) return forEachFail("onEach iterator failure timing")

    if (onEachNonLocal(arrayOf(1, 2, 3).asIterable()) != 23) {
        return forEachFail("onEach non-local return")
    }
    if (onEachIndexedNonLocal(arrayOf(1, 2, 3).asIterable()) != 29) {
        return forEachFail("onEachIndexed non-local return")
    }

    return "OK"
}
