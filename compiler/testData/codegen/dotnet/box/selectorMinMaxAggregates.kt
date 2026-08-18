private class SelectorMinMaxCountingIterable<T>(private val values: Array<T>) : Iterable<T> {
    var iteratorCalls: Int = 0
    var nextCalls: Int = 0

    override fun iterator(): Iterator<T> {
        iteratorCalls++
        return object : Iterator<T> {
            private var index = 0

            override fun hasNext(): Boolean = index < values.size

            override fun next(): T {
                nextCalls++
                return values[index++]
            }
        }
    }
}

private class SelectorMinMaxElement(val key: Int, val id: String)

private class SelectorMinMaxKey(private val value: Int) : Comparable<SelectorMinMaxKey> {
    override fun compareTo(other: SelectorMinMaxKey): Int = value.compareTo(other.value)
}

private class SelectorMinMaxFailure : RuntimeException()

fun box(): String {
    var selectorCalls = 0
    val empty = SelectorMinMaxCountingIterable(emptyArray<SelectorMinMaxElement>())
    try {
        empty.minBy { selectorCalls++; SelectorMinMaxKey(it.key) }
        return "fail 1a: missing minBy failure"
    } catch (_: NoSuchElementException) {
    }
    try {
        empty.maxBy { selectorCalls++; SelectorMinMaxKey(it.key) }
        return "fail 1b: missing maxBy failure"
    } catch (_: NoSuchElementException) {
    }
    if (empty.minByOrNull { selectorCalls++; SelectorMinMaxKey(it.key) } != null) return "fail 1c: minByOrNull empty"
    if (empty.maxByOrNull { selectorCalls++; SelectorMinMaxKey(it.key) } != null) return "fail 1d: maxByOrNull empty"
    if (selectorCalls != 0 || empty.iteratorCalls != 4 || empty.nextCalls != 0) return "fail 1e: empty evaluation"

    val only = SelectorMinMaxElement(7, "only")
    val singleton = SelectorMinMaxCountingIterable(arrayOf(only))
    if (singleton.minBy { selectorCalls++; SelectorMinMaxKey(it.key) } !== only) return "fail 2a: singleton minBy"
    if (singleton.maxBy { selectorCalls++; SelectorMinMaxKey(it.key) } !== only) return "fail 2b: singleton maxBy"
    if (singleton.minByOrNull { selectorCalls++; SelectorMinMaxKey(it.key) } !== only) return "fail 2c: singleton minByOrNull"
    if (singleton.maxByOrNull { selectorCalls++; SelectorMinMaxKey(it.key) } !== only) return "fail 2d: singleton maxByOrNull"
    if (selectorCalls != 0 || singleton.iteratorCalls != 4 || singleton.nextCalls != 4) return "fail 2e: singleton evaluation"

    val firstMin = SelectorMinMaxElement(1, "first min")
    val secondMin = SelectorMinMaxElement(1, "second min")
    val firstMax = SelectorMinMaxElement(3, "first max")
    val secondMax = SelectorMinMaxElement(3, "second max")
    val tied = SelectorMinMaxCountingIterable(arrayOf(firstMax, firstMin, secondMin, secondMax))
    if (tied.minBy { selectorCalls++; SelectorMinMaxKey(it.key) } !== firstMin) return "fail 3a: first minBy tie"
    if (tied.maxBy { selectorCalls++; SelectorMinMaxKey(it.key) } !== firstMax) return "fail 3b: first maxBy tie"
    if (tied.minByOrNull { selectorCalls++; SelectorMinMaxKey(it.key) } !== firstMin) return "fail 3c: first nullable min tie"
    if (tied.maxByOrNull { selectorCalls++; SelectorMinMaxKey(it.key) } !== firstMax) return "fail 3d: first nullable max tie"
    if (selectorCalls != 16 || tied.iteratorCalls != 4 || tied.nextCalls != 16) return "fail 3e: tie evaluation"

    val failure = SelectorMinMaxFailure()
    val failing = SelectorMinMaxCountingIterable(arrayOf(firstMin, firstMax, secondMin))
    var failureSelectorCalls = 0
    try {
        failing.minByOrNull {
            failureSelectorCalls++
            if (it === firstMax) throw failure
            SelectorMinMaxKey(it.key)
        }
        return "fail 4a: missing selector failure"
    } catch (caught: SelectorMinMaxFailure) {
        if (caught !== failure) return "fail 4b: selector failure identity"
    }
    if (failureSelectorCalls != 2 || failing.iteratorCalls != 1 || failing.nextCalls != 2) return "fail 4c: failure stopping"

    val objects = arrayOf(firstMax, firstMin, secondMin, secondMax)
    if (objects.minBy { SelectorMinMaxKey(it.key) } !== firstMin) return "fail 5a: object array minBy"
    if (objects.maxByOrNull { SelectorMinMaxKey(it.key) } !== firstMax) return "fail 5b: object array maxByOrNull"
    var emptyArraySelectorCalls = 0
    if (emptyArray<SelectorMinMaxElement>().minByOrNull { emptyArraySelectorCalls++; SelectorMinMaxKey(it.key) } != null) {
        return "fail 5c: empty object array"
    }
    if (emptyArraySelectorCalls != 0) return "fail 5d: empty array selector"

    if (byteArrayOf(3, -2, 1).minBy { -it.toInt() } != 3.toByte()) return "fail 6a: ByteArray"
    if (shortArrayOf(3, -2, 1).maxByOrNull { -it.toInt() } != (-2).toShort()) return "fail 6b: ShortArray"
    if (intArrayOf(3, -2, 1).minByOrNull { it } != -2) return "fail 6c: IntArray"
    if (longArrayOf(3L, -2L, 1L).maxBy { it } != 3L) return "fail 6d: LongArray"
    if (charArrayOf('z', 'a', 'm').minBy { it.code } != 'a') return "fail 6e: CharArray"
    if (booleanArrayOf(true, false, true).minByOrNull { if (it) 1 else 0 } != false) return "fail 6f: BooleanArray min"
    if (booleanArrayOf(false, true, false).maxBy { if (it) 1 else 0 } != true) return "fail 6g: BooleanArray max"
    var primitiveSingletonCalls = 0
    if (intArrayOf(7).minBy { primitiveSingletonCalls++; it } != 7 || primitiveSingletonCalls != 0) {
        return "fail 6h: primitive singleton selector"
    }

    if (!floatArrayOf(Float.NaN, 1.0f).maxBy { it }.isNaN()) return "fail 7a: Float max NaN"
    if (floatArrayOf(Float.NaN, 1.0f).minByOrNull { it } != 1.0f) return "fail 7b: Float min NaN"
    if (!doubleArrayOf(1.0, Double.NaN).maxByOrNull { it }!!.isNaN()) return "fail 7c: Double max NaN"
    if (doubleArrayOf(1.0, Double.NaN).minBy { it } != 1.0) return "fail 7d: Double min NaN"
    if (1.0f / floatArrayOf(0.0f, -0.0f).minBy { it } != Float.NEGATIVE_INFINITY) return "fail 7e: Float min zero"
    if (1.0f / floatArrayOf(-0.0f, 0.0f).maxByOrNull { it }!! != Float.POSITIVE_INFINITY) return "fail 7f: Float max zero"
    if (1.0 / doubleArrayOf(0.0, -0.0).minByOrNull { it }!! != Double.NEGATIVE_INFINITY) return "fail 7g: Double min zero"
    if (1.0 / doubleArrayOf(-0.0, 0.0).maxBy { it } != Double.POSITIVE_INFINITY) return "fail 7h: Double max zero"

    return "OK"
}
