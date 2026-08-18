@file:OptIn(ExperimentalStdlibApi::class)

private class CountingIterable<T>(private val values: Array<T>) : Iterable<T> {
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

fun box(): String {
    val empty = CountingIterable(emptyArray<Int>())
    if (!empty.allEqual() || empty.iteratorCalls != 1 || empty.nextCalls != 0) {
        return "fail 1a: empty Iterable"
    }
    var emptySelectorCalls = 0
    if (!empty.allEqualBy { emptySelectorCalls++; it } || emptySelectorCalls != 0) {
        return "fail 1b: empty selector"
    }

    val singleton = CountingIterable(arrayOf(7))
    var singletonSelectorCalls = 0
    if (!singleton.allEqualBy { singletonSelectorCalls++; it } || singletonSelectorCalls != 0) {
        return "fail 1c: singleton selector"
    }
    if (singleton.iteratorCalls != 1 || singleton.nextCalls != 1) return "fail 1d: singleton traversal"

    val equal = CountingIterable(arrayOf("x", "x", "x"))
    if (!equal.allEqual() || equal.iteratorCalls != 1 || equal.nextCalls != 3) {
        return "fail 2a: equal Iterable"
    }
    val different = CountingIterable(arrayOf("x", "x", "y", "x"))
    if (different.allEqual() || different.nextCalls != 3) return "fail 2b: direct short circuit"

    val keyed = CountingIterable(arrayOf(1, 3, 5, 6, 7))
    var keyedCalls = 0
    if (keyed.allEqualBy { value -> keyedCalls++; value and 1 }) return "fail 2c: keyed mismatch"
    if (keyedCalls != 4 || keyed.nextCalls != 4) return "fail 2d: keyed short circuit"

    var nullableCalls = 0
    if (!listOf(1, 3, 5).allEqualBy { nullableCalls++; null as String? } || nullableCalls != 3) {
        return "fail 2e: nullable equal keys"
    }
    if (listOf(1, 2).allEqualBy { value -> if (value == 1) null else "value" }) {
        return "fail 2f: nullable mismatch"
    }

    val selectorFailure = IllegalStateException("allEqual selector failure")
    var selectorFailureCalls = 0
    try {
        listOf(1, 1, 1).allEqualBy { value ->
            selectorFailureCalls++
            if (selectorFailureCalls == 2) throw selectorFailure
            value
        }
        return "fail 3a: missing selector failure"
    } catch (caught: IllegalStateException) {
        if (caught !== selectorFailure) return "fail 3b: selector failure identity"
    }
    if (selectorFailureCalls != 2) return "fail 3c: selector failure calls"

    if (!arrayOf("a", "a").allEqual()) return "fail 4a: object array equal"
    if (arrayOf("a", "b").allEqual()) return "fail 4b: object array mismatch"
    if (!arrayOf(1, 3, 5).allEqualBy { it and 1 }) return "fail 4c: object array selector"
    var objectSingletonCalls = 0
    if (!arrayOf(1).allEqualBy { objectSingletonCalls++; it } || objectSingletonCalls != 0) {
        return "fail 4d: object singleton selector"
    }

    if (!byteArrayOf(1, 1).allEqual() || byteArrayOf(1, 2).allEqual()) return "fail 5a: ByteArray"
    if (!shortArrayOf(2, 2).allEqual() || shortArrayOf(2, 3).allEqual()) return "fail 5b: ShortArray"
    if (!intArrayOf(3, 3).allEqual() || intArrayOf(3, 4).allEqual()) return "fail 5c: IntArray"
    if (!longArrayOf(4L, 4L).allEqual() || longArrayOf(4L, 5L).allEqual()) return "fail 5d: LongArray"
    if (!booleanArrayOf(true, true).allEqual() || booleanArrayOf(true, false).allEqual()) {
        return "fail 5e: BooleanArray"
    }
    if (!charArrayOf('k', 'k').allEqual() || charArrayOf('k', 'l').allEqual()) return "fail 5f: CharArray"

    if (!floatArrayOf(Float.NaN, Float.NaN).allEqual()) return "fail 6a: Float NaN"
    if (floatArrayOf(-0.0f, 0.0f).allEqual()) return "fail 6b: Float signed zero"
    if (!doubleArrayOf(Double.NaN, Double.NaN).allEqual()) return "fail 6c: Double NaN"
    if (doubleArrayOf(-0.0, 0.0).allEqual()) return "fail 6d: Double signed zero"
    if (!arrayOf(Float.NaN, Float.NaN).allEqual()) return "fail 6e: boxed Float NaN"
    if (arrayOf(-0.0, 0.0).allEqual()) return "fail 6f: boxed Double signed zero"

    if (!byteArrayOf(1, 3).allEqualBy { it.toInt() and 1 }) return "fail 7a: ByteArray selector"
    if (!shortArrayOf(2, 4).allEqualBy { it.toInt() and 1 }) return "fail 7b: ShortArray selector"
    if (!intArrayOf(1, 3).allEqualBy { it and 1 }) return "fail 7c: IntArray selector"
    if (!longArrayOf(2L, 4L).allEqualBy { it and 1L }) return "fail 7d: LongArray selector"
    if (!floatArrayOf(1.25f, 1.75f).allEqualBy { it.toInt() }) return "fail 7e: FloatArray selector"
    if (!doubleArrayOf(2.25, 2.75).allEqualBy { it.toInt() }) return "fail 7f: DoubleArray selector"
    if (!booleanArrayOf(true, false).allEqualBy { 0 }) return "fail 7g: BooleanArray selector"
    if (!charArrayOf('a', 'c').allEqualBy { it.code and 1 }) return "fail 7h: CharArray selector"

    var primitiveSingletonCalls = 0
    if (!intArrayOf(1).allEqualBy { primitiveSingletonCalls++; it } || primitiveSingletonCalls != 0) {
        return "fail 8: primitive singleton selector"
    }

    val widened: Iterable<Any?> = listOf(null, null)
    if (!widened.allEqual()) return "fail 9a: widened nullable equal"
    if (listOf<Any?>(1, 1L).allEqual()) return "fail 9b: widened numeric mismatch"

    return "OK"
}
