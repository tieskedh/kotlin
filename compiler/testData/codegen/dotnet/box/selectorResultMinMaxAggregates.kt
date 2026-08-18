private class SelectorResultCountingIterable<T>(private val values: Array<T>) : Iterable<T> {
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

private class SelectorResultKey(val value: Int, val id: String) : Comparable<SelectorResultKey> {
    override fun compareTo(other: SelectorResultKey): Int = value.compareTo(other.value)
}

private class SelectorResultElement(val key: SelectorResultKey, val floatValue: Float, val doubleValue: Double)

private class SelectorResultFailure : RuntimeException()

fun box(): String {
    var selectorCalls = 0
    val empty = SelectorResultCountingIterable(emptyArray<SelectorResultElement>())
    try {
        empty.minOf { selectorCalls++; it.key }
        return "fail 1a: missing generic minOf failure"
    } catch (_: NoSuchElementException) {
    }
    try {
        empty.maxOf { selectorCalls++; it.floatValue }
        return "fail 1b: missing Float maxOf failure"
    } catch (_: NoSuchElementException) {
    }
    if (empty.minOfOrNull { selectorCalls++; it.doubleValue } != null) return "fail 1c: Double nullable empty"
    if (empty.maxOfOrNull { selectorCalls++; it.key } != null) return "fail 1d: generic nullable empty"
    if (selectorCalls != 0 || empty.iteratorCalls != 4 || empty.nextCalls != 0) return "fail 1e: empty evaluation"
    if (intArrayOf().minOfOrNull { it } != null) return "fail 1f: nullable value empty"

    val onlyKey = SelectorResultKey(7, "only")
    val only = SelectorResultElement(onlyKey, 7.0f, 7.0)
    val singleton = SelectorResultCountingIterable(arrayOf(only))
    if (singleton.minOf { selectorCalls++; it.key } !== onlyKey) return "fail 2a: singleton generic"
    if (singleton.maxOf { selectorCalls++; it.floatValue } != 7.0f) return "fail 2b: singleton Float"
    if (singleton.minOfOrNull { selectorCalls++; it.doubleValue } != 7.0) return "fail 2c: singleton Double nullable"
    if (singleton.maxOfOrNull { selectorCalls++; it.key } !== onlyKey) return "fail 2d: singleton generic nullable"
    if (selectorCalls != 4 || singleton.iteratorCalls != 4 || singleton.nextCalls != 4) return "fail 2e: singleton evaluation"

    val firstMaxKey = SelectorResultKey(3, "first max")
    val firstMinKey = SelectorResultKey(1, "first min")
    val secondMinKey = SelectorResultKey(1, "second min")
    val secondMaxKey = SelectorResultKey(3, "second max")
    val tied = SelectorResultCountingIterable(
        arrayOf(
            SelectorResultElement(firstMaxKey, 3.0f, 3.0),
            SelectorResultElement(firstMinKey, 1.0f, 1.0),
            SelectorResultElement(secondMinKey, 1.0f, 1.0),
            SelectorResultElement(secondMaxKey, 3.0f, 3.0),
        )
    )
    if (tied.minOf { selectorCalls++; it.key } !== firstMinKey) return "fail 3a: first min result"
    if (tied.maxOf { selectorCalls++; it.key } !== firstMaxKey) return "fail 3b: first max result"
    if (tied.minOfOrNull { selectorCalls++; it.key } !== firstMinKey) return "fail 3c: first nullable min result"
    if (tied.maxOfOrNull { selectorCalls++; it.key } !== firstMaxKey) return "fail 3d: first nullable max result"
    if (selectorCalls != 20 || tied.iteratorCalls != 4 || tied.nextCalls != 16) return "fail 3e: tie evaluation"

    val failure = SelectorResultFailure()
    var failureSelectorCalls = 0
    try {
        intArrayOf(1, 2, 3).minOfOrNull {
            failureSelectorCalls++
            if (it == 2) throw failure
            it
        }
        return "fail 4a: missing selector failure"
    } catch (caught: SelectorResultFailure) {
        if (caught !== failure) return "fail 4b: selector failure identity"
    }
    if (failureSelectorCalls != 2) return "fail 4c: failure stopping"

    val objectValues = arrayOf(3, -2, 1)
    if (objectValues.minOf { it } != -2 || objectValues.maxOfOrNull { it } != 3) return "fail 5a: object generic"
    if (arrayOf(3.0f, -2.0f, 1.0f).minOf { it } != -2.0f) return "fail 5b: object Float"
    if (arrayOf(3.0, -2.0, 1.0).maxOfOrNull { it } != 3.0) return "fail 5c: object Double"

    if (byteArrayOf(3, -2, 1).minOf { it.toInt() } != -2) return "fail 6a: ByteArray"
    if (shortArrayOf(3, -2, 1).maxOfOrNull { it.toInt() } != 3) return "fail 6b: ShortArray"
    if (intArrayOf(3, -2, 1).minOfOrNull { it } != -2) return "fail 6c: IntArray"
    if (longArrayOf(3L, -2L, 1L).maxOf { it } != 3L) return "fail 6d: LongArray"
    if (charArrayOf('z', 'a', 'm').minOf { it.code } != 'a'.code) return "fail 6e: CharArray"
    if (booleanArrayOf(true, false, true).minOfOrNull { if (it) 1 else 0 } != 0) return "fail 6f: BooleanArray"

    if (!floatArrayOf(1.0f, Float.NaN, -1.0f).minOf { it }.isNaN()) return "fail 7a: Float min NaN"
    if (!floatArrayOf(1.0f, Float.NaN, -1.0f).maxOfOrNull { it }!!.isNaN()) return "fail 7b: Float max NaN"
    if (!doubleArrayOf(1.0, Double.NaN, -1.0).minOfOrNull { it }!!.isNaN()) return "fail 7c: Double min NaN"
    if (!doubleArrayOf(1.0, Double.NaN, -1.0).maxOf { it }.isNaN()) return "fail 7d: Double max NaN"
    if (1.0f / floatArrayOf(0.0f, -0.0f).minOf { it } != Float.NEGATIVE_INFINITY) return "fail 7e: Float min zero"
    if (1.0f / floatArrayOf(-0.0f, 0.0f).maxOfOrNull { it }!! != Float.POSITIVE_INFINITY) return "fail 7f: Float max zero"
    if (1.0 / doubleArrayOf(0.0, -0.0).minOfOrNull { it }!! != Double.NEGATIVE_INFINITY) return "fail 7g: Double min zero"
    if (1.0 / doubleArrayOf(-0.0, 0.0).maxOf { it } != Double.POSITIVE_INFINITY) return "fail 7h: Double max zero"

    return "OK"
}
