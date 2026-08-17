private class CountingIterable<T>(
    private val values: Array<T>,
    private val events: MutableList<String>? = null,
) : Iterable<T> {
    var iteratorCalls: Int = 0
    var nextCalls: Int = 0

    override fun iterator(): Iterator<T> {
        iteratorCalls++
        events?.add("receiver.iterator")
        return object : Iterator<T> {
            private var index = 0

            override fun hasNext(): Boolean = index < values.size

            override fun next(): T {
                nextCalls++
                events?.add("receiver.next:${values[index]}")
                return values[index++]
            }
        }
    }
}

private class CountingSequence<T>(
    private val values: Array<T>,
    private val events: MutableList<String>? = null,
) : Sequence<T> {
    var iteratorCalls: Int = 0
    var nextCalls: Int = 0

    override fun iterator(): Iterator<T> {
        iteratorCalls++
        events?.add("sequence.iterator")
        return object : Iterator<T> {
            private var index = 0

            override fun hasNext(): Boolean = index < values.size

            override fun next(): T {
                nextCalls++
                events?.add("sequence.next:${values[index]}")
                return values[index++]
            }
        }
    }
}

fun box(): String {
    val flatMapped = listOf(1, 2, 3).flatMap { value -> sequenceOf(value, -value) }
    if (flatMapped != listOf(1, -1, 2, -2, 3, -3)) return "fail 1a: flatMap Sequence"

    val destination = arrayListOf(99)
    val destinationResult = listOf(2, 4).flatMapTo(destination) { value -> sequenceOf(value, value + 1) }
    if (destinationResult !== destination || destination != listOf(99, 2, 3, 4, 5)) {
        return "fail 1b: flatMapTo destination"
    }

    val indexed = listOf("a", "b", "c").flatMapIndexed { index, value ->
        sequenceOf("$index$value", value)
    }
    if (indexed != listOf("0a", "a", "1b", "b", "2c", "c")) {
        return "fail 1c: flatMapIndexed Sequence"
    }

    val indexedDestination = arrayListOf("seed")
    val indexedDestinationResult = listOf("x", "y").flatMapIndexedTo(indexedDestination) { index, value ->
        sequenceOf("$index$value")
    }
    if (indexedDestinationResult !== indexedDestination || indexedDestination != listOf("seed", "0x", "1y")) {
        return "fail 1d: flatMapIndexedTo destination"
    }

    val eagerEvents = mutableListOf<String>()
    val eagerFailure = IllegalStateException("eager transform failure")
    var eagerTransforms = 0
    try {
        listOf(1, 2, 3).flatMap { value ->
            eagerEvents.add("transform:$value")
            eagerTransforms++
            if (value == 2) throw eagerFailure
            CountingSequence(arrayOf(value, value + 10), eagerEvents)
        }
        return "fail 2a: missing transform failure"
    } catch (caught: IllegalStateException) {
        if (caught !== eagerFailure) return "fail 2b: transform failure identity"
    }
    if (eagerTransforms != 2 || eagerEvents != listOf(
            "transform:1", "sequence.iterator", "sequence.next:1", "sequence.next:11", "transform:2"
        )) {
        return "fail 2c: eager nested order $eagerEvents"
    }

    val plusRight = CountingSequence(arrayOf(3, 4))
    val iterableLeft: Iterable<Int> = CountingIterable(arrayOf(1, 2))
    if (iterableLeft + plusRight != listOf(1, 2, 3, 4)) return "fail 3a: Iterable plus Sequence"
    if (plusRight.iteratorCalls != 1 || plusRight.nextCalls != 2) return "fail 3b: plus traversal"

    val collectionRight = CountingSequence(arrayOf(3, 4))
    val collectionLeft: Collection<Int> = arrayListOf(1, 2)
    if (collectionLeft + collectionRight != listOf(1, 2, 3, 4)) return "fail 3c: Collection plus Sequence"
    if (collectionRight.iteratorCalls != 1 || collectionRight.nextCalls != 2) return "fail 3d: collection plus traversal"

    val minusRight = CountingSequence(arrayOf(2, 9, 2))
    val minusLeft = CountingIterable(arrayOf(1, 2, 2, 3))
    if (minusLeft - minusRight != listOf(1, 3)) return "fail 4a: minus Sequence membership"
    if (minusRight.iteratorCalls != 1 || minusRight.nextCalls != 3) return "fail 4b: minus RHS traversal"
    if (minusLeft.iteratorCalls != 1 || minusLeft.nextCalls != 4) return "fail 4c: minus receiver traversal"

    val emptyMinusLeft = CountingIterable(arrayOf(5, 6))
    val emptyMinus = emptyMinusLeft - emptySequence()
    if (emptyMinus != listOf(5, 6) || emptyMinusLeft.iteratorCalls != 1 || emptyMinusLeft.nextCalls != 2) {
        return "fail 4d: empty minus snapshot"
    }

    val orderEvents = mutableListOf<String>()
    val orderedLeft = CountingIterable(arrayOf(1, 2), orderEvents)
    val orderedRight = CountingSequence(arrayOf(2), orderEvents)
    if (orderedLeft - orderedRight != listOf(1)) return "fail 5a: ordered minus value"
    if (orderEvents != listOf(
            "sequence.iterator", "sequence.next:2", "receiver.iterator", "receiver.next:1", "receiver.next:2"
        )) {
        return "fail 5b: minus materialization order $orderEvents"
    }

    val nullableLeft: Iterable<Int?> = listOf(1, null, 2, null)
    val nullableRight: Sequence<Int?> = sequenceOf(null)
    if (nullableLeft - nullableRight != listOf(1, 2)) return "fail 6a: nullable minus"
    val widened: Iterable<Any?> = listOf(1, "two")
    if (widened + sequenceOf(null, 3L) != listOf<Any?>(1, "two", null, 3L)) {
        return "fail 6b: widened plus"
    }

    return "OK"
}
