private class AverageCountingIterable<T>(private val values: Array<T>) : Iterable<T> {
    var iteratorCalls: Int = 0
    var hasNextCalls: Int = 0
    var nextCalls: Int = 0

    override fun iterator(): Iterator<T> {
        iteratorCalls++
        return AverageCountingIterator(values, this)
    }
}

private class AverageCountingIterator<T>(
    private val values: Array<T>,
    private val owner: AverageCountingIterable<T>,
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

private class AverageFailingIterable(private val failure: Throwable) : Iterable<Double> {
    var iteratorCalls: Int = 0
    var hasNextCalls: Int = 0
    var nextCalls: Int = 0

    override fun iterator(): Iterator<Double> {
        iteratorCalls++
        return AverageFailingIterator(this, failure)
    }
}

private class AverageFailingIterator(
    private val owner: AverageFailingIterable,
    private val failure: Throwable,
) : Iterator<Double> {
    override fun hasNext(): Boolean {
        owner.hasNextCalls++
        return true
    }

    override fun next(): Double {
        owner.nextCalls++
        throw failure
    }
}

private fun averageFail(message: String): String = "fail: $message"

fun box(): String {
    val emptyByteAverage = emptyArray<Byte>().asIterable().average()
    val emptyShortAverage = emptyArray<Short>().asIterable().average()
    val emptyIntAverage = emptyArray<Int>().asIterable().average()
    val emptyLongAverage = emptyArray<Long>().asIterable().average()
    val emptyFloatAverage = emptyArray<Float>().asIterable().average()
    val emptyDoubleAverage = emptyArray<Double>().asIterable().average()
    if (
        emptyByteAverage == emptyByteAverage ||
        emptyShortAverage == emptyShortAverage ||
        emptyIntAverage == emptyIntAverage ||
        emptyLongAverage == emptyLongAverage ||
        emptyFloatAverage == emptyFloatAverage ||
        emptyDoubleAverage == emptyDoubleAverage
    ) {
        return averageFail("empty average is not NaN")
    }

    if (arrayOf((-128).toByte(), 127.toByte()).asIterable().average() != -0.5) {
        return averageFail("Byte average")
    }
    if (arrayOf((-32768).toShort(), 32767.toShort()).asIterable().average() != -0.5) {
        return averageFail("Short average")
    }
    if (arrayOf(Int.MIN_VALUE, Int.MAX_VALUE).asIterable().average() != -0.5) {
        return averageFail("Int average")
    }
    if (arrayOf(1L, 2L).asIterable().average() != 1.5) return averageFail("Long average")
    if (arrayOf(1.0f, 2.0f).asIterable().average() != 1.5) return averageFail("Float average")
    if (arrayOf(1.0, 2.0).asIterable().average() != 1.5) return averageFail("Double average")

    val counting = AverageCountingIterable(arrayOf(1, 2, 3))
    if (counting.average() != 2.0) return averageFail("counting result")
    if (counting.iteratorCalls != 1 || counting.hasNextCalls != 4 || counting.nextCalls != 3) {
        return averageFail("full traversal")
    }

    val ordered = arrayOf(1.0e16, 1.0, -1.0e16).asIterable().average()
    if (ordered != 0.0) return averageFail("encounter-order rounding")

    val propagatedNaN = arrayOf(1.0, Double.NaN).asIterable().average()
    if (propagatedNaN == propagatedNaN) return averageFail("NaN propagation")

    val operationFailure = Error("average iterator failure")
    val failing = AverageFailingIterable(operationFailure)
    try {
        failing.average()
        return averageFail("iterator failure was swallowed")
    } catch (caught: Throwable) {
        if (caught !== operationFailure) return averageFail("iterator failure identity")
    }
    if (failing.iteratorCalls != 1 || failing.hasNextCalls != 1 || failing.nextCalls != 1) {
        return averageFail("iterator failure timing")
    }

    return "OK"
}
