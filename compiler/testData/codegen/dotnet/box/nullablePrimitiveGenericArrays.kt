@file:Suppress("UNCHECKED_CAST")

// Concrete nullable primitive elements remain ordinary Kotlin Array<E> values. The CLR carrier is
// the exact closed Nullable<V>[] vector; this test deliberately keeps specialized arrays, open
// Array<T?>, star projections, and value-vector covariance out of the admitted slice.

private class NullableArrayHolder<T>(val values: Array<T>) {
    fun first(): T = values[0]

    fun replace(index: Int, value: T) {
        values[index] = value
    }
}

private fun <T> genericFirst(values: Array<T>): T = values[0]

private fun <T> genericIdentity(values: Array<T>): Array<T> = values

private fun nullableIntTotal(vararg values: Int?): Int {
    var result = 0
    for (value in values) result += value ?: 0
    return result
}

fun box(): String {
    val booleans = arrayOf<Boolean?>(true, null, false)
    val bytes = arrayOf<Byte?>((-128).toByte(), null, 127.toByte())
    val shorts = arrayOf<Short?>((-32768).toShort(), null, 32767.toShort())
    val ints = arrayOf<Int?>(1, null, 3)
    val longs = arrayOf<Long?>(4L, null, 6L)
    val floats = arrayOf<Float?>(1.25f, null, -2.5f)
    val doubles = arrayOf<Double?>(3.5, null, -4.75)
    val chars = arrayOf<Char?>('A', null, 'Z')
    if (booleans[0] != true || booleans[1] != null || booleans[2] != false) {
        return "fail 1: Boolean? literal"
    }
    if (bytes[0] != (-128).toByte() || bytes[1] != null || bytes[2] != 127.toByte()) {
        return "fail 2: Byte? literal"
    }
    if (shorts[0] != (-32768).toShort() || shorts[1] != null || shorts[2] != 32767.toShort()) {
        return "fail 3: Short? literal"
    }
    if (ints[0] != 1 || ints[1] != null || ints[2] != 3) return "fail 4: Int? literal"
    if (longs[0] != 4L || longs[1] != null || longs[2] != 6L) return "fail 5: Long? literal"
    if (floats[0] != 1.25f || floats[1] != null || floats[2] != -2.5f) return "fail 6: Float? literal"
    if (doubles[0] != 3.5 || doubles[1] != null || doubles[2] != -4.75) return "fail 7: Double? literal"
    if (chars[0] != 'A' || chars[1] != null || chars[2] != 'Z') return "fail 8: Char? literal"

    val nullInts = arrayOfNulls<Int>(3)
    if (nullInts.size != 3 || nullInts[0] != null || nullInts[2] != null) {
        return "fail 9: arrayOfNulls"
    }
    nullInts[0] = 40
    nullInts[2] = 2
    if ((nullInts[0] ?: 0) + (nullInts[1] ?: 0) + (nullInts[2] ?: 0) != 42) {
        return "fail 10: get/set/elvis"
    }
    val empty = emptyArray<Int?>()
    if (empty.size != 0) return "fail 11: emptyArray"

    var initializerTrace = ""
    val initialized = Array<Short?>(3) { index ->
        initializerTrace += index
        if (index == 1) null else (index * 10).toShort()
    }
    if (initializerTrace != "012" || initialized[0] != 0.toShort() || initialized[1] != null ||
        initialized[2] != 20.toShort()
    ) return "fail 12: initializer"

    if (genericFirst(ints) != 1 || genericIdentity(ints) !== ints) {
        return "fail 13: generic function substitution"
    }
    val holder = NullableArrayHolder(ints)
    if (holder.first() != 1) return "fail 14: generic class read"
    holder.replace(1, 2)
    if (ints[1] != 2) return "fail 15: generic class write"

    if (nullableIntTotal(null, *ints, 4) != 10) return "fail 16: nullable vararg/spread"

    var longTotal = 0L
    for (value in longs) longTotal += value ?: 0L
    if (longTotal != 10L) return "fail 17: direct loop"
    val charIterator: Iterator<Char?> = chars.iterator()
    if (charIterator.next() != 'A' || charIterator.next() != null || charIterator.next() != 'Z' ||
        charIterator.hasNext()
    ) return "fail 18: escaping iterator"
    val floatIterable: Iterable<Float?> = floats.asIterable()
    if (floatIterable.iterator().next() != 1.25f) return "fail 19: asIterable"

    val copied = ints.copyOf(4)
    if (copied === ints || copied[0] != 1 || copied[1] != 2 || copied[2] != 3 || copied[3] != null) {
        return "fail 20: copyOf"
    }
    val destination = arrayOfNulls<Int>(4)
    val copyResult = ints.copyInto(destination, destinationOffset = 1, startIndex = 1)
    if (copyResult !== destination || destination[0] != null || destination[1] != 2 ||
        destination[2] != 3 || destination[3] != null
    ) return "fail 21: copyInto"
    if (!(arrayOf<Int?>(1, null) contentEquals arrayOf<Int?>(1, null))) {
        return "fail 22: contentEquals"
    }
    if (arrayOf<Int?>(1, null).contentHashCode() != 992) return "fail 23: contentHashCode"
    if (arrayOf<Int?>(1, null).contentToString() != "[1, null]") return "fail 24: contentToString"

    val nested = arrayOf(ints, arrayOf<Int?>(null, 5))
    if (nested[0] !== ints || nested[1][0] != null || nested[1][1] != 5) {
        return "fail 25: nested arrays"
    }

    val asAny: Any = ints
    if (asAny as Array<Int?> !== ints) return "fail 26: exact checked cast"
    if (asAny as? Array<Long?> != null) return "fail 27: exact safe cast"

    val nullableOuter: Array<Int?>? = null
    if (nullableOuter != null) return "fail 28: nullable outer"
    try {
        arrayOfNulls<Int>(-1)
        return "fail 29: negative size"
    } catch (_: Exception) {
    }
    return "OK"
}
