private data class SortItem(
    val key: Int,
    val name: String,
)

private class IteratorOnlyIntList : AbstractMutableList<Int>() {
    private val backing = mutableListOf(4, 1, 3, 2)

    var indexedSetAttempts: Int = 0
    var iteratorSetCalls: Int = 0

    override val size: Int
        get() = backing.size

    override fun get(index: Int): Int = backing[index]

    override fun set(index: Int, element: Int): Int {
        indexedSetAttempts++
        throw IllegalStateException("sorting must write through the list iterator")
    }

    override fun add(index: Int, element: Int) {
        backing.add(index, element)
    }

    override fun removeAt(index: Int): Int = backing.removeAt(index)

    override fun listIterator(): MutableListIterator<Int> = listIterator(0)

    override fun listIterator(index: Int): MutableListIterator<Int> {
        val delegate = backing.listIterator(index)
        return object : MutableListIterator<Int> {
            override fun hasNext(): Boolean = delegate.hasNext()
            override fun next(): Int = delegate.next()
            override fun remove(): Unit = delegate.remove()
            override fun hasPrevious(): Boolean = delegate.hasPrevious()
            override fun previous(): Int = delegate.previous()
            override fun nextIndex(): Int = delegate.nextIndex()
            override fun previousIndex(): Int = delegate.previousIndex()
            override fun add(element: Int): Unit = delegate.add(element)

            override fun set(element: Int) {
                iteratorSetCalls++
                delegate.set(element)
            }
        }
    }
}

fun box(): String {
    val stable = mutableListOf(
        SortItem(2, "a"),
        SortItem(1, "b"),
        SortItem(2, "c"),
        SortItem(1, "d"),
    )
    stable.sortWith(compareBy { it.key })
    if (stable.map { it.name } != listOf("b", "d", "a", "c")) return "fail 1: list stability $stable"

    stable.sortByDescending { it.key }
    if (stable.map { it.name } != listOf("a", "c", "b", "d")) return "fail 2: sortByDescending $stable"
    stable.sortBy { it.key }
    if (stable.map { it.name } != listOf("b", "d", "a", "c")) return "fail 3: sortBy $stable"

    val arbitrary = IteratorOnlyIntList()
    arbitrary.sort()
    if (arbitrary.toList() != listOf(1, 2, 3, 4)) return "fail 4: arbitrary list $arbitrary"
    if (arbitrary.indexedSetAttempts != 0) return "fail 5: indexed writeback"
    if (arbitrary.iteratorSetCalls != 4) return "fail 6: iterator writeback ${arbitrary.iteratorSetCalls}"

    val failure = IllegalStateException("sort comparator failure")
    val unchanged = mutableListOf(4, 1, 3, 2)
    try {
        unchanged.sortWith(Comparator { _, _ -> throw failure })
        return "fail 7: comparator failure returned"
    } catch (caught: IllegalStateException) {
        if (caught !== failure) return "fail 8: comparator exception identity"
    }
    if (unchanged != listOf(4, 1, 3, 2)) return "fail 9: list changed before successful sort $unchanged"

    var comparisons = 0
    val counting = Comparator<Int> { left, right ->
        comparisons++
        left.compareTo(right)
    }
    mutableListOf(1).sortWith(counting)
    emptyList<Int>().toMutableList().sortWith(counting)
    arrayOf(1).sortWith(counting)
    emptyArray<Int>().sortWith(counting)
    if (comparisons != 0) return "fail 10: trivial comparator calls $comparisons"

    val objectArray = arrayOf(
        SortItem(2, "a"),
        SortItem(1, "b"),
        SortItem(2, "c"),
        SortItem(1, "d"),
    )
    objectArray.sortWith(compareBy { it.key })
    if (objectArray.asList().map { it.name } != listOf("b", "d", "a", "c")) return "fail 11: array stability"

    val naturalArray = arrayOf("z", "a", "m")
    naturalArray.sort()
    if (naturalArray.asList() != listOf("a", "m", "z")) return "fail 12: natural array"

    val doubles = arrayOf(Double.NaN, 0.0, -0.0, 1.0)
    doubles.sort()
    if (doubles[0].compareTo(doubles[1]) >= 0) return "fail 13: signed zero order"
    if (doubles[2] != 1.0 || doubles[3].compareTo(Double.NaN) != 0) return "fail 14: NaN order"

    val source = mutableListOf(3, 1, 2)
    val snapshot = source.sorted()
    source[0] = 99
    if (snapshot != listOf(1, 2, 3)) return "fail 15: eager snapshot $snapshot"
    if (source != listOf(99, 1, 2)) return "fail 16: source mutation $source"

    if (listOf(3, 1, 2).sortedDescending() != listOf(3, 2, 1)) return "fail 17: sortedDescending"
    if (listOf("aa", "b", "cc").sortedBy { it.length } != listOf("b", "aa", "cc")) {
        return "fail 18: sortedBy stability"
    }
    if (listOf("aa", "b", "cc").sortedByDescending { it.length } != listOf("aa", "cc", "b")) {
        return "fail 19: sortedByDescending stability"
    }
    if (listOf(null, "b", "a").sortedWith(nullsLast(naturalOrder())) != listOf("a", "b", null)) {
        return "fail 20: nullable sortedWith"
    }

    try {
        arrayOf(4, 1, 3, 2).sortWith(Comparator { _, _ -> throw failure })
        return "fail 21: array comparator failure returned"
    } catch (caught: IllegalStateException) {
        if (caught !== failure) return "fail 22: array exception identity"
    }

    return "OK"
}
