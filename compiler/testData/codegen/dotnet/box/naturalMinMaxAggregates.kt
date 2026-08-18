private class MinMaxCountingIterable<T>(private val values: Array<T>) : Iterable<T> {
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

private class Ranked(val rank: Int, val id: String) : Comparable<Ranked> {
    override fun compareTo(other: Ranked): Int = rank.compareTo(other.rank)
}

fun box(): String {
    val empty = MinMaxCountingIterable(emptyArray<Ranked>())
    try {
        empty.min()
        return "fail 1a: missing min failure"
    } catch (_: NoSuchElementException) {
    }
    try {
        empty.max()
        return "fail 1b: missing max failure"
    } catch (_: NoSuchElementException) {
    }
    if (empty.minOrNull() != null || empty.maxOrNull() != null) return "fail 1c: nullable empty"
    if (empty.iteratorCalls != 4 || empty.nextCalls != 0) return "fail 1d: empty traversal"

    val firstMin = Ranked(1, "first min")
    val secondMin = Ranked(1, "second min")
    val firstMax = Ranked(3, "first max")
    val secondMax = Ranked(3, "second max")
    val ranked = MinMaxCountingIterable(arrayOf(firstMax, firstMin, secondMin, secondMax))
    if (ranked.min() !== firstMin || ranked.nextCalls != 4) return "fail 2a: first minimum"
    if (ranked.max() !== firstMax || ranked.nextCalls != 8) return "fail 2b: first maximum"
    if (ranked.minOrNull() !== firstMin || ranked.maxOrNull() !== firstMax) return "fail 2c: nullable ties"
    if (ranked.iteratorCalls != 4 || ranked.nextCalls != 16) return "fail 2d: ranked traversal"

    val objectRanks = arrayOf(Ranked(2, "middle"), firstMin, firstMax)
    if (objectRanks.min() !== firstMin || objectRanks.max() !== firstMax) return "fail 3a: object array"
    if (emptyArray<Ranked>().minOrNull() != null || emptyArray<Ranked>().maxOrNull() != null) {
        return "fail 3b: empty object array"
    }

    if (byteArrayOf(3, -2, 1).min() != (-2).toByte() || byteArrayOf(3, -2, 1).max() != 3.toByte()) {
        return "fail 4a: ByteArray"
    }
    if (shortArrayOf(3, -2, 1).minOrNull() != (-2).toShort() || shortArrayOf(3, -2, 1).maxOrNull() != 3.toShort()) {
        return "fail 4b: ShortArray"
    }
    if (intArrayOf(3, -2, 1).min() != -2 || intArrayOf(3, -2, 1).maxOrNull() != 3) return "fail 4c: IntArray"
    if (longArrayOf(3L, -2L, 1L).minOrNull() != -2L || longArrayOf(3L, -2L, 1L).max() != 3L) {
        return "fail 4d: LongArray"
    }
    if (charArrayOf('z', 'a', 'm').min() != 'a' || charArrayOf('z', 'a', 'm').maxOrNull() != 'z') {
        return "fail 4e: CharArray"
    }
    try {
        intArrayOf().min()
        return "fail 4f: missing primitive failure"
    } catch (_: NoSuchElementException) {
    }
    if (doubleArrayOf().maxOrNull() != null) return "fail 4g: empty primitive nullable"

    if (!floatArrayOf(1.0f, Float.NaN, -1.0f).min().isNaN()) return "fail 5a: Float min NaN"
    if (!floatArrayOf(1.0f, Float.NaN, -1.0f).maxOrNull()!!.isNaN()) return "fail 5b: Float max NaN"
    if (!doubleArrayOf(1.0, Double.NaN, -1.0).minOrNull()!!.isNaN()) return "fail 5c: Double min NaN"
    if (!doubleArrayOf(1.0, Double.NaN, -1.0).max().isNaN()) return "fail 5d: Double max NaN"
    if (1.0f / floatArrayOf(0.0f, -0.0f).min() != Float.NEGATIVE_INFINITY) return "fail 5e: Float min zero"
    if (1.0f / floatArrayOf(-0.0f, 0.0f).max() != Float.POSITIVE_INFINITY) return "fail 5f: Float max zero"
    if (1.0 / doubleArrayOf(0.0, -0.0).min() != Double.NEGATIVE_INFINITY) return "fail 5g: Double min zero"
    if (1.0 / doubleArrayOf(-0.0, 0.0).max() != Double.POSITIVE_INFINITY) return "fail 5h: Double max zero"

    if (!arrayOf(1.0f, Float.NaN).min().isNaN()) return "fail 6a: object Float min"
    if (!arrayOf(1.0, Double.NaN).maxOrNull()!!.isNaN()) return "fail 6b: object Double max"
    if (1.0f / arrayOf(0.0f, -0.0f).minOrNull()!! != Float.NEGATIVE_INFINITY) return "fail 6c: object Float zero"
    if (1.0 / arrayOf(-0.0, 0.0).max() != Double.POSITIVE_INFINITY) return "fail 6d: object Double zero"

    return "OK"
}
