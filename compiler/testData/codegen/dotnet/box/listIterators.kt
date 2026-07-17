private class PairCursor<T>(
    private val first: T,
    private val second: T,
) : ListIterator<T> {
    private var position: Int = 0

    override fun hasNext(): Boolean = position < 2

    override fun next(): T {
        val result = if (position == 0) first else if (position == 1) second else throw NoSuchElementException()
        position++
        return result
    }

    override fun hasPrevious(): Boolean = position > 0

    override fun previous(): T {
        if (position == 0) throw NoSuchElementException()
        position--
        return if (position == 0) first else second
    }

    override fun nextIndex(): Int = position

    override fun previousIndex(): Int = position - 1
}

private open class ForwardCursor<T>(
    protected val first: T,
    protected val second: T,
) : Iterator<T> {
    protected var position: Int = 0

    override fun hasNext(): Boolean = position < 2

    override fun next(): T {
        val result = if (position == 0) first else if (position == 1) second else throw NoSuchElementException()
        position++
        return result
    }
}

private class InheritedPairCursor<T>(first: T, second: T) :
    ForwardCursor<T>(first, second), ListIterator<T> {
    override fun hasPrevious(): Boolean = position > 0

    override fun previous(): T {
        if (position == 0) throw NoSuchElementException()
        position--
        return if (position == 0) first else second
    }

    override fun nextIndex(): Int = position

    override fun previousIndex(): Int = position - 1
}

private fun <T> openRoundTrip(iterator: ListIterator<T>, first: T, second: T): Boolean =
    iterator.nextIndex() == 0 &&
            iterator.previousIndex() == -1 &&
            iterator.hasNext() &&
            iterator.next() == first &&
            iterator.next() == second &&
            !iterator.hasNext() &&
            iterator.hasPrevious() &&
            iterator.previous() == second &&
            iterator.previous() == first &&
            !iterator.hasPrevious()

fun box(): String {
    val ints: ListIterator<Int> = PairCursor(4, 9)
    val intsWide: ListIterator<Any?> = ints
    if (ints !== intsWide) return "fail 1: primitive widening changed identity"
    if (ints.nextIndex() != 0 || ints.previousIndex() != -1) return "fail 2: initial indices"
    if (ints.next() != 4 || intsWide.next() != 9) return "fail 3: forward primitive calls"
    if (intsWide.hasNext() || !intsWide.hasPrevious()) return "fail 4: end state"
    if (intsWide.previous() != 9 || ints.previous() != 4) return "fail 5: backward primitive calls"
    if (intsWide.hasPrevious()) return "fail 6: beginning state"

    val strings: ListIterator<String> = PairCursor("a", "b")
    val stringsWide: ListIterator<Any?> = strings
    if (strings !== stringsWide || strings.next() != "a" || stringsWide.previous() != "a") {
        return "fail 7: reference widening"
    }

    if (!openRoundTrip(PairCursor(1, 2), 1, 2)) return "fail 8: open primitive calls"
    if (!openRoundTrip(PairCursor("x", "y"), "x", "y")) return "fail 9: open reference calls"

    val asIterator: Iterator<Any?> = PairCursor<Int>(3, 5)
    if (asIterator.next() != 3) return "fail 10: Iterator super-view"

    if (!openRoundTrip(InheritedPairCursor(6, 8), 6, 8)) {
        return "fail 11: inherited Iterator implementation"
    }

    return "OK"
}
