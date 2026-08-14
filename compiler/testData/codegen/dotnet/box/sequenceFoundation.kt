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

    return "OK"
}
