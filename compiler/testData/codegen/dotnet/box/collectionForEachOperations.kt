private class ForEachCountingIterable<T>(private val values: Array<T>) : Iterable<T> {
    var iteratorCalls: Int = 0
    var hasNextCalls: Int = 0
    var nextCalls: Int = 0

    override fun iterator(): Iterator<T> {
        iteratorCalls++
        return ForEachCountingIterator(values, this)
    }
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

    return "OK"
}
