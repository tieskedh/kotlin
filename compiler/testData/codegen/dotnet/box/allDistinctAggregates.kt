@file:OptIn(ExperimentalStdlibApi::class)

private class DistinctCountingIterable<T>(private val values: Array<T>) : Iterable<T> {
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
    val empty = DistinctCountingIterable(emptyArray<Int>())
    if (!empty.allDistinct() || empty.iteratorCalls != 1 || empty.nextCalls != 0) {
        return "fail 1a: empty Iterable"
    }

    val singleton = DistinctCountingIterable(arrayOf(7))
    var singletonSelectorCalls = 0
    if (!singleton.allDistinctBy { singletonSelectorCalls++; it } || singletonSelectorCalls != 0) {
        return "fail 1b: singleton selector"
    }
    if (singleton.iteratorCalls != 1 || singleton.nextCalls != 1) return "fail 1c: singleton traversal"

    val distinct = DistinctCountingIterable(arrayOf("a", "b", "c"))
    if (!distinct.allDistinct() || distinct.nextCalls != 3) return "fail 2a: distinct Iterable"
    val duplicate = DistinctCountingIterable(arrayOf("a", "b", "a", "c"))
    if (duplicate.allDistinct() || duplicate.nextCalls != 3) return "fail 2b: duplicate short circuit"

    val keyed = DistinctCountingIterable(arrayOf(1, 3, 2, 4))
    var keyedCalls = 0
    if (keyed.allDistinctBy { value -> keyedCalls++; value and 1 }) return "fail 2c: keyed duplicate"
    if (keyedCalls != 2 || keyed.nextCalls != 2) return "fail 2d: keyed short circuit"

    val selectorFailure = IllegalStateException("allDistinct selector failure")
    var selectorFailureCalls = 0
    try {
        listOf(1, 2, 3).allDistinctBy { value ->
            selectorFailureCalls++
            if (selectorFailureCalls == 2) throw selectorFailure
            value
        }
        return "fail 3a: missing selector failure"
    } catch (caught: IllegalStateException) {
        if (caught !== selectorFailure) return "fail 3b: selector failure identity"
    }
    if (selectorFailureCalls != 2) return "fail 3c: selector failure calls"

    if (!arrayOf("a", "b").allDistinct()) return "fail 4a: object array distinct"
    if (arrayOf("a", "a").allDistinct()) return "fail 4b: object array duplicate"
    if (arrayOf(1, 2, 3).allDistinctBy { it and 1 }) return "fail 4c: selector duplicate"

    val everyByte = ByteArray(256) { it.toByte() }
    if (!everyByte.allDistinct()) return "fail 5a: complete Byte domain"
    if (ByteArray(257) { it.toByte() }.allDistinct()) return "fail 5b: oversized Byte domain"
    if (byteArrayOf(Byte.MIN_VALUE, Byte.MAX_VALUE, Byte.MIN_VALUE).allDistinct()) {
        return "fail 5c: signed Byte normalization"
    }
    if (!shortArrayOf(1, -1).allDistinct() || shortArrayOf(1, 1).allDistinct()) return "fail 5d: ShortArray"
    if (!intArrayOf(1, 2).allDistinct() || intArrayOf(1, 1).allDistinct()) return "fail 5e: IntArray"
    if (!longArrayOf(1L, 2L).allDistinct() || longArrayOf(1L, 1L).allDistinct()) return "fail 5f: LongArray"
    if (!booleanArrayOf(true, false).allDistinct() || booleanArrayOf(true, false, true).allDistinct()) {
        return "fail 5g: BooleanArray"
    }
    if (!charArrayOf('a', 'b').allDistinct() || charArrayOf('a', 'a').allDistinct()) return "fail 5h: CharArray"

    if (floatArrayOf(Float.NaN, Float.NaN).allDistinct()) return "fail 6a: Float NaN duplicate"
    if (!floatArrayOf(Float.NaN, 1.0f).allDistinct()) return "fail 6b: Float NaN distinct"
    if (!floatArrayOf(-0.0f, 0.0f).allDistinct()) return "fail 6c: Float signed zero"
    if (doubleArrayOf(Double.NaN, Double.NaN).allDistinct()) return "fail 6d: Double NaN duplicate"
    if (!doubleArrayOf(Double.NaN, 1.0).allDistinct()) return "fail 6e: Double NaN distinct"
    if (!doubleArrayOf(-0.0, 0.0).allDistinct()) return "fail 6f: Double signed zero"
    if (arrayOf(Float.NaN, Float.NaN).allDistinctBy { it }) return "fail 6g: boxed Float NaN"
    if (!arrayOf(-0.0, 0.0).allDistinctBy { it }) return "fail 6h: boxed Double signed zero"

    if (!byteArrayOf(1, 2).allDistinctBy { it.toInt() and 1 }) return "fail 7a: ByteArray selector"
    if (!shortArrayOf(1, 2).allDistinctBy { it.toInt() and 1 }) return "fail 7b: ShortArray selector"
    if (!intArrayOf(1, 2).allDistinctBy { it and 1 }) return "fail 7c: IntArray selector"
    if (!longArrayOf(1L, 2L).allDistinctBy { it and 1L }) return "fail 7d: LongArray selector"
    if (!floatArrayOf(1.25f, 2.25f).allDistinctBy { it.toInt() }) return "fail 7e: FloatArray selector"
    if (!doubleArrayOf(1.25, 2.25).allDistinctBy { it.toInt() }) return "fail 7f: DoubleArray selector"
    if (!booleanArrayOf(true, false).allDistinctBy { it }) return "fail 7g: BooleanArray selector"
    if (!charArrayOf('a', 'b').allDistinctBy { it.code and 1 }) return "fail 7h: CharArray selector"

    var primitiveSingletonCalls = 0
    if (!intArrayOf(1).allDistinctBy { primitiveSingletonCalls++; it } || primitiveSingletonCalls != 0) {
        return "fail 8a: primitive singleton selector"
    }
    if (listOf(1, 2).allDistinctBy { null as String? }) return "fail 8b: nullable duplicate keys"
    val widened: Iterable<Any?> = listOf<Any?>(null, 1, 1L)
    if (!widened.allDistinct()) return "fail 8c: widened values"

    return "OK"
}
