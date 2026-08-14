private data class ArraySortItem(
    val key: Int,
    val name: String,
) : Comparable<ArraySortItem> {
    override fun compareTo(other: ArraySortItem): Int = key.compareTo(other.key)
}

private fun primitiveWholeSorts(): String? {
    val bytes = byteArrayOf(3, -1, 2, Byte.MIN_VALUE, Byte.MAX_VALUE)
    bytes.sort()
    if (!bytes.contentEquals(byteArrayOf(Byte.MIN_VALUE, -1, 2, 3, Byte.MAX_VALUE))) {
        return "byte sort"
    }

    val shorts = shortArrayOf(3, -1, 2, Short.MIN_VALUE, Short.MAX_VALUE)
    shorts.sort()
    if (!shorts.contentEquals(shortArrayOf(Short.MIN_VALUE, -1, 2, 3, Short.MAX_VALUE))) {
        return "short sort"
    }

    val ints = intArrayOf(3, -1, 2, Int.MIN_VALUE, Int.MAX_VALUE)
    ints.sort()
    if (!ints.contentEquals(intArrayOf(Int.MIN_VALUE, -1, 2, 3, Int.MAX_VALUE))) {
        return "int sort"
    }

    val longs = longArrayOf(3L, -1L, 2L, Long.MIN_VALUE, Long.MAX_VALUE)
    longs.sort()
    if (!longs.contentEquals(longArrayOf(Long.MIN_VALUE, -1L, 2L, 3L, Long.MAX_VALUE))) {
        return "long sort"
    }

    val chars = charArrayOf('z', '\u0000', 'a', Char.MAX_VALUE)
    chars.sort()
    if (!chars.contentEquals(charArrayOf('\u0000', 'a', 'z', Char.MAX_VALUE))) {
        return "char sort"
    }

    val floats = floatArrayOf(Float.NaN, 0.0f, -0.0f, Float.POSITIVE_INFINITY, -1.0f)
    floats.sort()
    if (floats[0] != -1.0f || floats[1].compareTo(-0.0f) != 0 ||
        floats[2].compareTo(0.0f) != 0 || floats[3] != Float.POSITIVE_INFINITY ||
        floats[4].compareTo(Float.NaN) != 0
    ) {
        return "float total order"
    }

    val doubles = doubleArrayOf(Double.NaN, 0.0, -0.0, Double.POSITIVE_INFINITY, -1.0)
    doubles.sort()
    if (doubles[0] != -1.0 || doubles[1].compareTo(-0.0) != 0 ||
        doubles[2].compareTo(0.0) != 0 || doubles[3] != Double.POSITIVE_INFINITY ||
        doubles[4].compareTo(Double.NaN) != 0
    ) {
        return "double total order"
    }

    return null
}

private fun primitiveRangeSorts(): String? {
    val bytes = byteArrayOf(9, 4, 1, 3, 8)
    bytes.sort(1, 4)
    if (!bytes.contentEquals(byteArrayOf(9, 1, 3, 4, 8))) return "byte range"

    val shorts = shortArrayOf(9, 4, 1, 3, 8)
    shorts.sort(1, 4)
    if (!shorts.contentEquals(shortArrayOf(9, 1, 3, 4, 8))) return "short range"

    val ints = intArrayOf(9, 4, 1, 3, 8)
    ints.sort(1, 4)
    if (!ints.contentEquals(intArrayOf(9, 1, 3, 4, 8))) return "int range"

    val longs = longArrayOf(9, 4, 1, 3, 8)
    longs.sort(1, 4)
    if (!longs.contentEquals(longArrayOf(9, 1, 3, 4, 8))) return "long range"

    val floats = floatArrayOf(9.0f, Float.NaN, 0.0f, -0.0f, 8.0f)
    floats.sort(1, 4)
    if (floats[0] != 9.0f || floats[1].compareTo(-0.0f) != 0 ||
        floats[2].compareTo(0.0f) != 0 || floats[3].compareTo(Float.NaN) != 0 ||
        floats[4] != 8.0f
    ) return "float range"

    val doubles = doubleArrayOf(9.0, Double.NaN, 0.0, -0.0, 8.0)
    doubles.sort(1, 4)
    if (doubles[0] != 9.0 || doubles[1].compareTo(-0.0) != 0 ||
        doubles[2].compareTo(0.0) != 0 || doubles[3].compareTo(Double.NaN) != 0 ||
        doubles[4] != 8.0
    ) return "double range"

    val chars = charArrayOf('x', 'd', 'a', 'c', 'y')
    chars.sort(1, 4)
    if (!chars.contentEquals(charArrayOf('x', 'a', 'c', 'd', 'y'))) return "char range"

    return null
}

private fun rangeFailures(): String? {
    val source = intArrayOf(4, 3, 2, 1)
    try {
        source.sort(-1, 2)
        return "negative range returned"
    } catch (_: IndexOutOfBoundsException) {
    }
    if (!source.contentEquals(intArrayOf(4, 3, 2, 1))) return "negative range mutated"

    try {
        source.sort(3, 2)
        return "reversed range returned"
    } catch (_: IllegalArgumentException) {
    }
    if (!source.contentEquals(intArrayOf(4, 3, 2, 1))) return "reversed range mutated"

    try {
        source.reverse(0, 5)
        return "oversized reverse range returned"
    } catch (_: IndexOutOfBoundsException) {
    }
    if (!source.contentEquals(intArrayOf(4, 3, 2, 1))) return "reverse range mutated"

    source.sort(2, 2)
    source.reverse(1, 2)
    if (!source.contentEquals(intArrayOf(4, 3, 2, 1))) return "empty/singleton range mutated"
    return null
}

fun box(): String {
    primitiveWholeSorts()?.let { return "fail 1: $it" }
    primitiveRangeSorts()?.let { return "fail 2: $it" }
    rangeFailures()?.let { return "fail 3: $it" }

    val stable = arrayOf(
        ArraySortItem(9, "outside-left"),
        ArraySortItem(2, "a"),
        ArraySortItem(1, "b"),
        ArraySortItem(2, "c"),
        ArraySortItem(1, "d"),
        ArraySortItem(8, "outside-right"),
    )
    stable.sortWith(compareBy { it.key }, 1, 5)
    if (stable.asList().map { it.name } !=
        listOf("outside-left", "b", "d", "a", "c", "outside-right")
    ) return "fail 4: stable object range ${stable.asList().map { it.name }}"

    stable.sortDescending(1, 5)
    if (stable.asList().map { it.name } !=
        listOf("outside-left", "a", "c", "b", "d", "outside-right")
    ) return "fail 5: descending stable object range ${stable.asList().map { it.name }}"

    val failure = IllegalStateException("array range comparator failure")
    try {
        stable.sortWith(Comparator { _, _ -> throw failure }, 1, 5)
        return "fail 6: comparator failure returned"
    } catch (caught: IllegalStateException) {
        if (caught !== failure) return "fail 7: comparator failure identity"
    }
    if (stable[0].name != "outside-left" || stable[5].name != "outside-right") {
        return "fail 8: comparator failure escaped range"
    }

    val source = intArrayOf(3, 1, 2)
    val sortedSnapshot = source.sortedArray()
    if (sortedSnapshot === source || !sortedSnapshot.contentEquals(intArrayOf(1, 2, 3))) {
        return "fail 9: primitive sortedArray"
    }
    source[0] = 99
    if (!sortedSnapshot.contentEquals(intArrayOf(1, 2, 3))) return "fail 10: snapshot alias"
    if (!source.sortedArrayDescending().contentEquals(intArrayOf(99, 2, 1))) {
        return "fail 11: primitive sortedArrayDescending"
    }

    val empty = intArrayOf()
    if (empty.sortedArray() !== empty || empty.reversedArray() !== empty) {
        return "fail 12: empty snapshot identity"
    }

    val reversed = booleanArrayOf(true, true, false, false)
    reversed.reverse(1, 4)
    if (!reversed.contentEquals(booleanArrayOf(true, false, false, true))) {
        return "fail 13: boolean reverse range"
    }
    if (booleanArrayOf(true, false, true).sortedWith(compareBy { it }) !=
        listOf(false, true, true)
    ) return "fail 14: boolean comparator ordering"
    if (booleanArrayOf(true, false, true).sortedByDescending { it } !=
        listOf(true, true, false)
    ) return "fail 15: boolean selector ordering"

    if (byteArrayOf(2, 4, 1, 3).sortedBy { it.toInt() % 2 } != listOf<Byte>(2, 4, 1, 3)) {
        return "fail 16: primitive stable selector"
    }
    if (charArrayOf('a', 'c', 'b').reversed() != listOf('b', 'c', 'a')) {
        return "fail 17: primitive reversed list"
    }
    if (!charArrayOf('a', 'b', 'c').reversedArray().contentEquals(charArrayOf('c', 'b', 'a'))) {
        return "fail 18: primitive reversedArray"
    }

    val objectSource = arrayOf(
        ArraySortItem(2, "a"),
        ArraySortItem(1, "b"),
        ArraySortItem(2, "c"),
        ArraySortItem(1, "d"),
    )
    val objectSnapshot = objectSource.sortedArrayWith(compareBy { it.key })
    if (objectSnapshot === objectSource || objectSnapshot.asList().map { it.name } != listOf("b", "d", "a", "c")) {
        return "fail 19: object sortedArrayWith"
    }
    objectSource[0] = ArraySortItem(0, "mutated")
    if (objectSnapshot[0].name != "b") return "fail 20: object snapshot alias"

    if (!intArrayOf(1, 2, 2, 3).isSorted()) return "fail 21: isSorted"
    if (!intArrayOf(3, 2, 2, 1).isSortedDescending()) return "fail 22: isSortedDescending"
    if (intArrayOf(1, 3, 2).isSorted()) return "fail 23: unsorted accepted"
    if (!booleanArrayOf(false, true).isSortedWith(compareBy { it })) return "fail 24: boolean isSortedWith"
    if (!intArrayOf(2, 4, 1).isSortedBy { it % 2 }) return "fail 25: isSortedBy"

    return "OK"
}
