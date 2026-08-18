private class ComparatorAggregateCountingIterable<T>(private val values: Array<T>) : Iterable<T> {
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

private class ComparatorAggregateComparator<T>(private val comparison: (T, T) -> Int) : Comparator<T> {
    var calls: Int = 0

    override fun compare(a: T, b: T): Int {
        calls++
        return comparison(a, b)
    }
}

private class ComparatorAggregateKey(val value: Int, val id: String)

private class ComparatorAggregateFailure : RuntimeException()

fun box(): String {
    val keyComparator = ComparatorAggregateComparator<ComparatorAggregateKey> { a, b -> a.value.compareTo(b.value) }
    val empty = ComparatorAggregateCountingIterable(emptyArray<ComparatorAggregateKey>())
    var selectorCalls = 0
    try {
        empty.minWith(keyComparator)
        return "fail 1a: missing minWith failure"
    } catch (_: NoSuchElementException) {
    }
    if (empty.maxWithOrNull(keyComparator) != null) return "fail 1b: maxWithOrNull empty"
    try {
        empty.minOfWith(keyComparator) { selectorCalls++; it }
        return "fail 1c: missing minOfWith failure"
    } catch (_: NoSuchElementException) {
    }
    if (empty.maxOfWithOrNull(keyComparator) { selectorCalls++; it } != null) return "fail 1d: maxOfWithOrNull empty"
    if (selectorCalls != 0 || keyComparator.calls != 0 || empty.iteratorCalls != 4 || empty.nextCalls != 0) {
        return "fail 1e: empty evaluation"
    }

    val only = ComparatorAggregateKey(7, "only")
    val singleton = ComparatorAggregateCountingIterable(arrayOf(only))
    if (singleton.minWith(keyComparator) !== only) return "fail 2a: singleton minWith"
    if (singleton.maxWithOrNull(keyComparator) !== only) return "fail 2b: singleton maxWithOrNull"
    if (singleton.minOfWith(keyComparator) { selectorCalls++; it } !== only) return "fail 2c: singleton minOfWith"
    if (singleton.maxOfWithOrNull(keyComparator) { selectorCalls++; it } !== only) return "fail 2d: singleton maxOfWithOrNull"
    if (selectorCalls != 2 || keyComparator.calls != 0 || singleton.iteratorCalls != 4 || singleton.nextCalls != 4) {
        return "fail 2e: singleton evaluation"
    }

    val firstMax = ComparatorAggregateKey(3, "first max")
    val firstMin = ComparatorAggregateKey(1, "first min")
    val secondMin = ComparatorAggregateKey(1, "second min")
    val secondMax = ComparatorAggregateKey(3, "second max")
    val tied = arrayOf(firstMax, firstMin, secondMin, secondMax)
    if (tied.minWith(keyComparator) !== firstMin) return "fail 3a: minWith first tie"
    if (tied.maxWith(keyComparator) !== firstMax) return "fail 3b: maxWith first tie"
    if (tied.minWithOrNull(keyComparator) !== firstMin) return "fail 3c: minWithOrNull first tie"
    if (tied.maxWithOrNull(keyComparator) !== firstMax) return "fail 3d: maxWithOrNull first tie"
    if (tied.minOfWith(keyComparator) { selectorCalls++; it } !== firstMin) return "fail 3e: minOfWith first tie"
    if (tied.maxOfWith(keyComparator) { selectorCalls++; it } !== firstMax) return "fail 3f: maxOfWith first tie"
    if (tied.minOfWithOrNull(keyComparator) { selectorCalls++; it } !== firstMin) return "fail 3g: minOfWithOrNull first tie"
    if (tied.maxOfWithOrNull(keyComparator) { selectorCalls++; it } !== firstMax) return "fail 3h: maxOfWithOrNull first tie"
    if (selectorCalls != 18 || keyComparator.calls != 24) return "fail 3i: tie evaluation"

    val comparisonFailure = ComparatorAggregateFailure()
    val failingComparator = ComparatorAggregateComparator<Int> { a, b ->
        if (a == 2) throw comparisonFailure
        a.compareTo(b)
    }
    try {
        intArrayOf(3, 2, 1).minWith(failingComparator)
        return "fail 4a: missing comparator failure"
    } catch (caught: ComparatorAggregateFailure) {
        if (caught !== comparisonFailure) return "fail 4b: comparator failure identity"
    }
    if (failingComparator.calls != 2) return "fail 4c: comparator failure stopping"

    val selectorFailure = ComparatorAggregateFailure()
    val intComparator = ComparatorAggregateComparator<Int> { a, b -> a.compareTo(b) }
    var failingSelectorCalls = 0
    try {
        intArrayOf(1, 2, 3).maxOfWith(intComparator) {
            failingSelectorCalls++
            if (it == 2) throw selectorFailure
            it
        }
        return "fail 4d: missing selector failure"
    } catch (caught: ComparatorAggregateFailure) {
        if (caught !== selectorFailure) return "fail 4e: selector failure identity"
    }
    if (failingSelectorCalls != 2 || intComparator.calls != 0) return "fail 4f: selector failure stopping"

    val nullableComparator = ComparatorAggregateComparator<String?> { a, b ->
        when {
            a === b -> 0
            a == null -> -1
            b == null -> 1
            else -> a.compareTo(b)
        }
    }
    if (arrayOf<String?>("x", null, "y").minOfWithOrNull(nullableComparator) { it } != null) {
        return "fail 5a: nullable selector result"
    }
    val broadComparator = ComparatorAggregateComparator<Any?> { a, b ->
        a.toString().length.compareTo(b.toString().length)
    }
    if (arrayOf("long", "x", "mid").minWith(broadComparator) != "x") return "fail 5b: broad comparator"

    if (byteArrayOf(3, -2, 1).minWith(Comparator { a, b -> a.compareTo(b) }) != (-2).toByte()) return "fail 6a: ByteArray"
    if (shortArrayOf(3, -2, 1).maxWithOrNull(Comparator { a, b -> a.compareTo(b) }) != 3.toShort()) return "fail 6b: ShortArray"
    if (intArrayOf(3, -2, 1).minOfWith(Comparator { a, b -> a.compareTo(b) }) { -it } != -3) return "fail 6c: IntArray"
    if (longArrayOf(3L, -2L, 1L).maxOfWithOrNull(Comparator { a, b -> a.compareTo(b) }) { -it } != 2L) return "fail 6d: LongArray"
    if (!floatArrayOf(1.0f, Float.NaN, -1.0f).maxWith(naturalOrder()).isNaN()) return "fail 6e: FloatArray"
    if (1.0 / doubleArrayOf(0.0, -0.0).minOfWith(naturalOrder()) { it } != Double.NEGATIVE_INFINITY) return "fail 6f: DoubleArray"
    if (charArrayOf('z', 'a', 'm').minWith(Comparator { a, b -> a.compareTo(b) }) != 'a') return "fail 6g: CharArray"
    if (booleanArrayOf(false, true).maxOfWith(Comparator { a, b -> a.compareTo(b) }) { if (it) 1 else 0 } != 1) return "fail 6h: BooleanArray"

    return "OK"
}
