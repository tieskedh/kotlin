private class Ranked(private val rank: Int) : Comparable<Ranked> {
    override fun compareTo(other: Ranked): Int = rank - other.rank

    override fun equals(other: Any?): Boolean = other is Ranked && rank == other.rank

    override fun hashCode(): Int = rank

    override fun toString(): String = "R$rank"
}

private class PlainIntIterator(private var available: Boolean = true) : Iterator<Int> {
    override fun hasNext(): Boolean = available

    override fun next(): Int {
        if (!available) throw NoSuchElementException()
        available = false
        return 41
    }
}

private fun repeatEscape(): Int {
    repeat(5) { index ->
        if (index == 2) return index
    }
    return -1
}

private fun countIntRange(first: Int, last: Int): Int {
    var count = 0
    for (value in first..last) {
        if (value == 0) continue
        count = count + 1
    }
    return count
}

private fun countLongRange(first: Long, last: Long): Int {
    var count = 0
    for (value in first..last) count = count + 1
    return count
}

private fun countCharRange(first: Char, last: Char): Int {
    var count = 0
    for (value in first..last) count = count + 1
    return count
}

private fun iteratorExhausts(iterator: Iterator<Any>): Boolean {
    if (iterator.hasNext()) return false
    try {
        iterator.next()
        return false
    } catch (_: NoSuchElementException) {
        return !iterator.hasNext()
    }
}

fun box(): String {
    val range = 1..3
    if (range.first != 1 || range.last != 3 || range.step != 1) return "fail 1: IntRange state"
    if (range != IntRange(1, 3) || range.hashCode() != 34 || range.toString() != "1..3") {
        return "fail 2: IntRange value semantics"
    }
    if (!(3..1).isEmpty() || (3..1) != IntRange.EMPTY || (3..1).hashCode() != -1) {
        return "fail 3: empty IntRange"
    }

    val intIterator = range.iterator()
    val intIteratorAsAny: Any = intIterator
    if (intIteratorAsAny !is IntIterator || intIterator.nextInt() != 1 || intIterator.next() != 2 || intIterator.nextInt() != 3) {
        return "fail 4: Int progression iterator"
    }
    val exhaustedInt: Iterator<Any> = intIterator
    if (!iteratorExhausts(exhaustedInt)) return "fail 5: Int progression exhaustion"
    val plainAsAny: Any = PlainIntIterator()
    if (plainAsAny is IntIterator) return "fail 6: primitive iterator identity"

    val stepped = 1..7 step 3
    val steppedIterator = stepped.iterator()
    if (stepped.first != 1 || stepped.last != 7 || stepped.step != 3 ||
        steppedIterator.nextInt() != 1 || steppedIterator.nextInt() != 4 || steppedIterator.nextInt() != 7 ||
        steppedIterator.hasNext()
    ) return "fail 7: step"

    val descending = (7 downTo 1 step 3).reversed()
    val descendingIterator = descending.iterator()
    if (descending.first != 1 || descending.last != 7 || descending.step != 3 ||
        descendingIterator.nextInt() != 1 || descendingIterator.nextInt() != 4 || descendingIterator.nextInt() != 7
    ) return "fail 8: downTo/reversed"

    try {
        1..4 step 0
        return "fail 9: zero step accepted"
    } catch (exception: IllegalArgumentException) {
        if (exception.message != "Step must be positive, was: 0.") return "fail 10: zero step message"
    }
    try {
        1 downTo 4 step -2
        return "fail 11: negative step accepted"
    } catch (exception: IllegalArgumentException) {
        if (exception.message != "Step must be positive, was: -2.") return "fail 12: negative step message"
    }

    val untilRange = 1 until 4
    val openRange: OpenEndRange<Int> = 1..<4
    val closedRange: ClosedRange<Int> = range
    if (untilRange != range || openRange.start != 1 || openRange.endExclusive != 4 || 3 !in openRange || 4 in openRange) {
        return "fail 13: end-exclusive ranges"
    }
    if (2 !in closedRange || 4 in closedRange) return "fail 14: ClosedRange dispatch"
    if (!(Int.MIN_VALUE until Int.MIN_VALUE).isEmpty()) return "fail 15: minimum until"

    val nullableInside: Int? = 2
    val nullableOutside: Int? = null
    if (nullableInside !in range || nullableOutside in range || 2L !in range || Long.MAX_VALUE in range) {
        return "fail 16: nullable/mixed contains"
    }

    val longProgression = 5L downTo 1L step 2L
    val longIterator = longProgression.iterator()
    val longIteratorAsAny: Any = longIterator
    if (longIteratorAsAny !is LongIterator || longIterator.nextLong() != 5L ||
        longIterator.nextLong() != 3L || longIterator.nextLong() != 1L || longIterator.hasNext()
    ) return "fail 17: Long progression"

    val chars = 'a'..'e' step 2
    val charIterator = chars.iterator()
    val charIteratorAsAny: Any = charIterator
    if (charIteratorAsAny !is CharIterator || charIterator.nextChar() != 'a' ||
        charIterator.nextChar() != 'c' || charIterator.nextChar() != 'e' || charIterator.hasNext()
    ) return "fail 18: Char progression"

    if (countIntRange(Int.MAX_VALUE - 2, Int.MAX_VALUE) != 3) return "fail 19: Int maximum loop"
    if (countIntRange(Int.MIN_VALUE, Int.MIN_VALUE + 2) != 3) return "fail 20: Int minimum loop"
    if (countIntRange(-1, 1) != 2) return "fail 21: continue loop"
    if (countLongRange(Long.MAX_VALUE - 2L, Long.MAX_VALUE) != 3) return "fail 22: Long maximum loop"
    if (countCharRange('\uFFFD', '\uFFFF') != 3) return "fail 23: Char maximum loop"

    var descendingTotal = 0
    for (value in Int.MIN_VALUE + 2 downTo Int.MIN_VALUE) descendingTotal = descendingTotal + 1
    if (descendingTotal != 3) return "fail 24: descending minimum loop"
    var steppedTotal = 0
    for (value in 0..10 step 3) steppedTotal = steppedTotal + value
    if (steppedTotal != 18) return "fail 25: stepped loop"

    var repeats = 0
    repeat(-1) { repeats = repeats + 1 }
    repeat(0) { repeats = repeats + 1 }
    repeat(3) { index -> repeats = repeats + index + 1 }
    if (repeats != 6 || repeatEscape() != 2) return "fail 26: repeat"

    if (arrayOf("x", "y").indices != 0..1 || booleanArrayOf(true).indices != 0..0 ||
        byteArrayOf(1).indices != 0..0 || shortArrayOf(1).indices != 0..0 ||
        intArrayOf(1).indices != 0..0 || longArrayOf(1L).indices != 0..0 ||
        floatArrayOf(1.0f).indices != 0..0 || doubleArrayOf(1.0).indices != 0..0 ||
        charArrayOf('x').indices != 0..0 || emptyArray<String>().indices != IntRange.EMPTY
    ) return "fail 27: array indices"

    val doubleRange: ClosedFloatingPointRange<Double> = 1.0..2.0
    val openDouble: OpenEndRange<Double> = 1.0..<2.0
    if (1.5 !in doubleRange || 2.0 !in doubleRange || 2.0 in openDouble || openDouble.isEmpty()) {
        return "fail 28: floating ranges"
    }

    val rankedStart = Ranked(1)
    val rankedMiddle = Ranked(2)
    val rankedEnd = Ranked(3)
    val rankedRange = rankedStart..rankedEnd
    val rankedOpen = rankedStart..<rankedEnd
    if (rankedMiddle !in rankedRange || rankedEnd !in rankedRange || rankedEnd in rankedOpen ||
        rankedRange.toString() != "R1..R3" || rankedOpen.toString() != "R1..<R3"
    ) return "fail 29: comparable ranges"

    return "OK"
}
