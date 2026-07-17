private class OneIterator<T>(
    private val value: T,
    private var available: Boolean,
) : Iterator<T> {
    override fun hasNext(): Boolean = available

    override fun next(): T {
        if (!available) throw NoSuchElementException()
        available = false
        return value
    }
}

private class OneCollection<T>(
    private val value: T,
    private val present: Boolean,
) : Collection<T> {
    override val size: Int get() = if (present) 1 else 0

    override fun isEmpty(): Boolean = !present

    override fun contains(element: T): Boolean = present && element == value

    override fun iterator(): Iterator<T> = OneIterator(value, present)

    override fun containsAll(elements: Collection<T>): Boolean {
        val iterator = elements.iterator()
        while (iterator.hasNext()) {
            if (!contains(iterator.next())) return false
        }
        return true
    }
}

private class StrictStringCollection(private val value: String) : Collection<String> {
    override val size: Int get() = 1

    override fun isEmpty(): Boolean = false

    // `toString()` deliberately dereferences the non-null parameter: the canonical bridge must
    // reject null and other wrong shapes before entering this body.
    override fun contains(element: String): Boolean = element.toString() == value

    override fun iterator(): Iterator<String> = OneIterator(value, true)

    override fun containsAll(elements: Collection<String>): Boolean {
        val iterator = elements.iterator()
        while (iterator.hasNext()) {
            if (!contains(iterator.next())) return false
        }
        return true
    }
}

private interface UnsafeCheck<out T> {
    fun check(value: @UnsafeVariance T): Boolean
}

private class IntUnsafeCheck : UnsafeCheck<Int> {
    override fun check(value: Int): Boolean = value == 7
}

private fun <T> openCalls(collection: Collection<T>, value: T): Boolean {
    val iterator = collection.iterator()
    return collection.size == 1 &&
            !collection.isEmpty() &&
            collection.contains(value) &&
            iterator.hasNext() &&
            iterator.next() == value
}

fun box(): String {
    val ints: Collection<Int> = OneCollection(7, true)
    val intsWide: Collection<Any?> = ints
    if (ints !== intsWide) return "fail 1: primitive widening changed identity"
    if (!ints.contains(7)) return "fail 2: exact primitive contains"
    if (!intsWide.contains(7)) return "fail 3: widened primitive contains"
    if (intsWide.contains("7") || intsWide.contains(null)) return "fail 4: primitive wrong-shape barrier"
    if (ints.size != 1 || ints.isEmpty()) return "fail 5: size or isEmpty"

    val empty: Collection<Int> = OneCollection(0, false)
    if (empty.size != 0 || !empty.isEmpty() || !empty.containsAll(empty)) {
        return "fail 6: empty collection contract"
    }
    if (!ints.containsAll(OneCollection(7, true))) return "fail 7: containsAll true"
    if (ints.containsAll(OneCollection(8, true))) return "fail 8: containsAll false"
    val compatibleWide: Collection<Any?> = OneCollection(7, true)
    if (!intsWide.containsAll(compatibleWide)) return "fail 9: widened containsAll"

    val strings: Collection<String> = StrictStringCollection("s")
    val stringsWide: Collection<Any?> = strings
    if (strings !== stringsWide) return "fail 10: reference widening changed identity"
    if (!stringsWide.contains("s") || stringsWide.contains(1) || stringsWide.contains(null)) {
        return "fail 11: reference wrong-shape barrier"
    }

    val nullableInt: Collection<Int?> = OneCollection(null, true)
    val nullableIntWide: Collection<Any?> = nullableInt
    if (nullableInt !== nullableIntWide || !nullableInt.contains(null) || !nullableIntWide.contains(null)) {
        return "fail 12: nullable primitive null"
    }
    if (nullableIntWide.contains("bad")) return "fail 13: nullable primitive wrong shape"
    val boxedNullableInt: Collection<Any?> = OneCollection<Int?>(3, true)
    if (!boxedNullableInt.contains(3)) return "fail 14: nullable primitive value"

    val nullableString: Collection<String?> = OneCollection(null, true)
    val nullableStringWide: Collection<Any?> = nullableString
    if (nullableString !== nullableStringWide || !nullableStringWide.contains(null)) {
        return "fail 15: nullable reference null"
    }
    if (nullableStringWide.contains(1)) return "fail 16: nullable reference wrong shape"

    if (!openCalls(ints, 7)) return "fail 17: open primitive calls"
    if (!openCalls(strings, "s")) return "fail 18: open reference calls"
    if (!openCalls(nullableInt, null)) return "fail 19: open nullable calls"

    val iterator = ints.iterator()
    val wideIterator: Iterator<Any?> = iterator
    if (iterator !== wideIterator || wideIterator.next() != 7) return "fail 20: nested Iterator view"

    val ordinaryUnsafe: UnsafeCheck<Any?> = IntUnsafeCheck()
    try {
        ordinaryUnsafe.check("bad")
        return "fail 21: ordinary unsafe member received collection barrier"
    } catch (_: ClassCastException) {
    }

    return "OK"
}
