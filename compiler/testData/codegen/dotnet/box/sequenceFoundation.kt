private class CountingSequence<T>(private val values: Array<T>) : Sequence<T> {
    var iteratorCalls: Int = 0
    var nextCalls: Int = 0

    override fun iterator(): Iterator<T> {
        iteratorCalls++
        return object : Iterator<T> {
            private var index: Int = 0

            override fun hasNext(): Boolean = index < values.size

            override fun next(): T {
                nextCalls++
                return values[index++]
            }
        }
    }
}

fun box(): String {
    val counted = CountingSequence(arrayOf(1, 2, 3, 4))
    val pipeline = counted.map { it * 2 }.filter { it > 3 }.take(2)
    if (counted.iteratorCalls != 0 || counted.nextCalls != 0) return "fail 1: eager"
    if (pipeline.toList().toString() != "[4, 6]") return "fail 2: pipeline"
    if (counted.iteratorCalls != 1 || counted.nextCalls != 3) return "fail 3: laziness"

    val oneShot = arrayOf("a", "bb").iterator().asSequence()
    if (oneShot.joinToString(":") != "a:bb") return "fail 4: one-shot first"
    try {
        oneShot.iterator()
        return "fail 5: one-shot second"
    } catch (_: IllegalStateException) {
    }

    val covariant: Sequence<Any?> = sequenceOf(1, 2)
    if (covariant.toList().toString() != "[1, 2]") return "fail 6: covariance"
    if (intArrayOf(7, 8).asSequence().sum() != 15) return "fail 7: primitive array"
    if (sequenceOf(1, 2).flatMap { sequenceOf(it, -it) }.toList().toString() != "[1, -1, 2, -2]") {
        return "fail 8: Sequence flatMap"
    }
    if (sequenceOf(1, 2).flatMap { listOf(it, it + 10) }.toList().toString() != "[1, 11, 2, 12]") {
        return "fail 9: Iterable flatMap"
    }
    if (sequenceOf(sequenceOf(1, 2), sequenceOf(3)).flatten().toList().toString() != "[1, 2, 3]") {
        return "fail 10: Sequence flatten"
    }
    if (sequenceOf(listOf(1, 2), listOf(3)).flatten().toList().toString() != "[1, 2, 3]") {
        return "fail 11: Iterable flatten"
    }
    if (sequenceOf(3, 1, 2, 1).distinct().sorted().toList().toString() != "[1, 2, 3]") {
        return "fail 12: distinct/sorted"
    }
    if (sequenceOf<Any?>(1, "two", 3L, 4).filterIsInstance<Int>().toList().toString() != "[1, 4]") {
        return "fail 13: reified filtering"
    }

    val ints = sequenceOf(1, 4, 2)
    if (ints.sum() != 7 || ints.min() != 1 || ints.max() != 4) return "fail 14: aggregates"
    if (ints.sumOf { it * 2 } != 14 || ints.sumOf { it.toDouble() } != 7.0) return "fail 15: selectors"
    val doubles = sequenceOf(0.0, Double.NaN)
    if (!doubles.min().isNaN() || !doubles.maxOrNull()!!.isNaN()) return "fail 16: NaN"

    val builderTrace = arrayListOf<String>()
    val built: Sequence<Any?> = sequence {
        builderTrace.add("start")
        yield(1)
        builderTrace.add("after-one")
        yieldAll(listOf("two", null))
        builderTrace.add("after-iterable")
        yieldAll(sequenceOf(4L))
        builderTrace.add("done")
    }
    if (builderTrace.isNotEmpty()) return "fail 17: eager builder"
    val builtIterator = built.iterator()
    if (builderTrace.isNotEmpty()) return "fail 18: eager iterator"
    if (!builtIterator.hasNext() || builderTrace.toString() != "[start]") return "fail 19: first resume"
    if (builtIterator.next() != 1 || builderTrace.toString() != "[start]") return "fail 20: first value"
    if (builtIterator.next() != "two" || builderTrace.toString() != "[start, after-one]") {
        return "fail 21: iterable yieldAll"
    }
    if (builtIterator.next() != null || builtIterator.next() != 4L) return "fail 22: nullable/sequence yieldAll"
    if (builderTrace.toString() != "[start, after-one, after-iterable]") return "fail 23: sequence resume"
    if (builtIterator.hasNext() || builderTrace.toString() != "[start, after-one, after-iterable, done]") {
        return "fail 24: builder completion"
    }

    val directIterator = iterator<Int> {
        yieldAll(emptyList())
        yield(2)
        yieldAll(listOf(3).iterator())
    }
    if (directIterator.asSequence().toList().toString() != "[2, 3]") return "fail 25: iterator builder"

    val failure = IllegalStateException("builder failure")
    val failing = sequence<Int> {
        yield(7)
        throw failure
    }.iterator()
    if (failing.next() != 7) return "fail 26: failure prefix"
    try {
        failing.hasNext()
        return "fail 27: missing builder failure"
    } catch (caught: IllegalStateException) {
        if (caught !== failure) return "fail 28: failure identity"
    }

    var defaultCalls = 0
    val nonEmpty = sequenceOf(1).ifEmpty {
        defaultCalls++
        sequenceOf(9)
    }
    if (defaultCalls != 0 || nonEmpty.toList().toString() != "[1]" || defaultCalls != 0) {
        return "fail 29: non-empty fallback"
    }
    val emptyFallback = emptySequence<Int>().ifEmpty {
        defaultCalls++
        sequenceOf(9, 10)
    }
    if (defaultCalls != 0 || emptyFallback.toList().toString() != "[9, 10]" || defaultCalls != 1) {
        return "fail 30: empty fallback"
    }

    if (sequenceOf(2, 3).flatMapIndexed { index, value -> listOf(index, value) }
            .toList().toString() != "[0, 2, 1, 3]") {
        return "fail 31: indexed iterable flatMap"
    }
    if (sequenceOf(2, 3).flatMapIndexed { index, value -> sequenceOf(value + index) }
            .toList().toString() != "[2, 4]") {
        return "fail 32: indexed sequence flatMap"
    }
    if (sequenceOf(1, 2, 3).runningFold(10) { acc, value -> acc + value }
            .toList().toString() != "[10, 11, 13, 16]") {
        return "fail 33: runningFold"
    }
    if (sequenceOf(1, 2).runningFoldIndexed(10) { index, acc, value -> acc + index + value }
            .toList().toString() != "[10, 11, 14]") {
        return "fail 34: runningFoldIndexed"
    }
    if (sequenceOf(2, 3, 4).runningReduce { acc, value -> acc * value }
            .toList().toString() != "[2, 6, 24]") {
        return "fail 35: runningReduce"
    }
    if (sequenceOf(2, 3, 4).runningReduceIndexed { index, acc, value -> acc + index + value }
            .toList().toString() != "[2, 6, 12]") {
        return "fail 36: runningReduceIndexed"
    }
    if (sequenceOf(1, 2).scan(4) { acc, value -> acc + value }.toList().toString() != "[4, 5, 7]") {
        return "fail 37: scan"
    }
    if (sequenceOf(1, 2).scanIndexed(4) { index, acc, value -> acc + index + value }
            .toList().toString() != "[4, 5, 8]") {
        return "fail 38: scanIndexed"
    }

    if (sequenceOf(1, 2, 3, 4, 5).windowed(3, step = 2, partialWindows = true)
            .toList().toString() != "[[1, 2, 3], [3, 4, 5], [5]]") {
        return "fail 39: overlapping windows"
    }
    if (sequenceOf(1, 2, 3, 4, 5).windowed(2, step = 3, partialWindows = true)
            .toList().toString() != "[[1, 2], [4, 5]]") {
        return "fail 40: gapped windows"
    }
    if (sequenceOf(1, 2, 3, 4, 5).chunked(2).toList().toString() != "[[1, 2], [3, 4], [5]]") {
        return "fail 41: chunks"
    }
    if (sequenceOf(1, 2, 3).windowed(2) { window -> window[0] + window[1] }
            .toList().toString() != "[3, 5]") {
        return "fail 42: transformed windows"
    }
    if (generateSequence(0) { value -> if (value < 1024) value + 1 else null }
            .windowed(1025).single().size != 1025) {
        return "fail 43: expanded ring buffer"
    }
    try {
        sequenceOf(1).windowed(0)
        return "fail 44: missing window validation"
    } catch (_: IllegalArgumentException) {
    }
    if (sequenceOf(2, 4, 8).zipWithNext().toList().toString() != "[(2, 4), (4, 8)]") {
        return "fail 45: zipWithNext"
    }
    if (sequenceOf(2, 4, 8).zipWithNext { left, right -> right - left }
            .toList().toString() != "[2, 4]") {
        return "fail 46: transformed zipWithNext"
    }

    return "OK"
}
