private class FoldCountingIterable<T>(private val values: Array<T>) : Iterable<T> {
    var iteratorCalls: Int = 0
    var hasNextCalls: Int = 0
    var nextCalls: Int = 0

    override fun iterator(): Iterator<T> {
        iteratorCalls++
        return FoldCountingIterator(values, this)
    }
}

private class FoldCountingIterator<T>(
    private val values: Array<T>,
    private val owner: FoldCountingIterable<T>,
) : Iterator<T> {
    private var index: Int = 0

    override fun hasNext(): Boolean {
        owner.hasNextCalls++
        return index < values.size
    }

    override fun next(): T {
        if (index >= values.size) throw NoSuchElementException()
        owner.nextCalls++
        return values[index++]
    }
}

private class ReverseFoldList<T>(private val values: Array<T>) : List<T> {
    var listIteratorCalls: Int = 0
    var requestedIndex: Int = -1
    var events: String = ""

    override val size: Int get() = values.size

    override fun isEmpty(): Boolean = values.size == 0

    override fun get(index: Int): T = throw Error("foldRight used List.get()")

    override fun contains(element: T): Boolean = throw Error("foldRight used List.contains()")

    override fun containsAll(elements: Collection<T>): Boolean =
        throw Error("foldRight used List.containsAll()")

    override fun indexOf(element: T): Int = throw Error("foldRight used List.indexOf()")

    override fun lastIndexOf(element: T): Int = throw Error("foldRight used List.lastIndexOf()")

    override fun iterator(): Iterator<T> = throw Error("foldRight used List.iterator()")

    override fun listIterator(): ListIterator<T> = throw Error("foldRight used List.listIterator()")

    override fun listIterator(index: Int): ListIterator<T> {
        listIteratorCalls++
        requestedIndex = index
        events += "list($index)|"
        if (index != values.size) throw Error("foldRight did not start at List.size")
        return ReverseFoldIterator(values, this, index)
    }

    override fun subList(fromIndex: Int, toIndex: Int): List<T> =
        throw Error("foldRight used List.subList()")
}

private class ReverseFoldIterator<T>(
    private val values: Array<T>,
    private val owner: ReverseFoldList<T>,
    private var cursor: Int,
) : ListIterator<T> {
    override fun hasNext(): Boolean = throw Error("foldRight used ListIterator.hasNext()")

    override fun next(): T = throw Error("foldRight used ListIterator.next()")

    override fun nextIndex(): Int = throw Error("foldRight used ListIterator.nextIndex()")

    override fun hasPrevious(): Boolean {
        owner.events += "hasPrevious|"
        return cursor > 0
    }

    override fun previous(): T {
        owner.events += "previous|"
        if (cursor <= 0) throw NoSuchElementException()
        cursor--
        return values[cursor]
    }

    override fun previousIndex(): Int {
        owner.events += "previousIndex|"
        return cursor - 1
    }
}

private fun fail(message: String): String = "fail: $message"

fun box(): String {
    var operations = 0
    val empty = FoldCountingIterable(emptyArray<Int>())
    if (empty.fold(41) { accumulator, element -> operations++; accumulator + element } != 41) {
        return fail("empty fold result")
    }
    if (empty.iteratorCalls != 1 || empty.hasNextCalls != 1 || empty.nextCalls != 0 || operations != 0) {
        return fail("empty fold traversal")
    }
    if (empty.foldIndexed(42) { index, accumulator, element -> operations++; index + accumulator + element } != 42) {
        return fail("empty foldIndexed result")
    }
    if (empty.iteratorCalls != 2 || empty.hasNextCalls != 2 || empty.nextCalls != 0 || operations != 0) {
        return fail("empty foldIndexed traversal")
    }

    val left = FoldCountingIterable(arrayOf(2, 3, 5))
    var leftTrace = ""
    val primitive: Int = left.fold(7) { accumulator, element ->
        leftTrace += "$accumulator+$element|"
        accumulator + element
    }
    if (primitive != 17 || leftTrace != "7+2|9+3|12+5|") return fail("fold order/result")
    if (left.iteratorCalls != 1 || left.hasNextCalls != 4 || left.nextCalls != 3) {
        return fail("fold traversal")
    }

    val indexed = FoldCountingIterable(arrayOf<String?>("a", null, "c"))
    var indexedTrace = ""
    val indexedResult = indexed.foldIndexed("seed") { index, accumulator, element ->
        indexedTrace += "$index:$element|"
        "$accumulator/$index:$element"
    }
    if (indexedResult != "seed/0:a/1:null/2:c" || indexedTrace != "0:a|1:null|2:c|") {
        return fail("foldIndexed index/value association")
    }

    val nullableAccumulator: String? = FoldCountingIterable(arrayOf("a", "b")).fold(null as String?) { accumulator, element ->
        if (accumulator == null) element else accumulator + element
    }
    if (nullableAccumulator != "ab") return fail("nullable accumulator")

    val widened: Iterable<Any?> = FoldCountingIterable(arrayOf<Any?>(null, 2, "x"))
    if (widened.fold(0) { accumulator, element -> accumulator + if (element == null) 10 else 1 } != 12) {
        return fail("widened nullable elements")
    }

    var captured = 1
    val capturedResult = FoldCountingIterable(arrayOf(2, 4)).fold(0) { accumulator, element ->
        captured += element
        accumulator + captured
    }
    if (captured != 7 || capturedResult != 10) return fail("captured mutation")
    val failure = Error("operation failure")
    val failing = FoldCountingIterable(arrayOf(1, 2, 3))
    operations = 0
    try {
        failing.fold(0) { accumulator, element ->
            operations++
            if (element == 2) throw failure
            accumulator + element
        }
        return fail("operation failure was swallowed")
    } catch (caught: Throwable) {
        if (caught !== failure) return fail("operation failure identity")
    }
    if (operations != 2 || failing.hasNextCalls != 2 || failing.nextCalls != 2) {
        return fail("operation failure timing")
    }

    val emptyRight = ReverseFoldList(emptyArray<Int>())
    if (emptyRight.foldRight(13) { element, accumulator -> operations++; element + accumulator } != 13) {
        return fail("empty foldRight result")
    }
    if (emptyRight.foldRightIndexed(17) { index, element, accumulator -> operations++; index + element + accumulator } != 17) {
        return fail("empty foldRightIndexed result")
    }
    if (emptyRight.listIteratorCalls != 0) return fail("empty right fold requested iterator")

    val right = ReverseFoldList(arrayOf(1, 2, 3))
    var rightTrace = ""
    val rightResult = right.foldRight("z") { element, accumulator ->
        rightTrace += "$element:$accumulator|"
        "($element$accumulator)"
    }
    if (rightResult != "(1(2(3z)))" || rightTrace != "3:z|2:(3z)|1:(2(3z))|") {
        return fail("foldRight order/result")
    }
    if (right.listIteratorCalls != 1 || right.requestedIndex != 3) return fail("foldRight iterator origin")
    if (right.events != "list(3)|hasPrevious|previous|hasPrevious|previous|hasPrevious|previous|hasPrevious|") {
        return fail("foldRight iterator protocol: ${right.events}")
    }

    val rightIndexed = ReverseFoldList(arrayOf("a", "b", "c"))
    var rightIndexedTrace = ""
    val rightIndexedResult = rightIndexed.foldRightIndexed("z") { index, element, accumulator ->
        rightIndexedTrace += "$index:$element:$accumulator|"
        "$index$element$accumulator"
    }
    if (rightIndexedResult != "0a1b2cz" || rightIndexedTrace != "2:c:z|1:b:2cz|0:a:1b2cz|") {
        return fail("foldRightIndexed index/value association")
    }
    if (
        rightIndexed.events !=
        "list(3)|hasPrevious|previousIndex|previous|hasPrevious|previousIndex|previous|hasPrevious|previousIndex|previous|hasPrevious|"
    ) {
        return fail("foldRightIndexed iterator protocol: ${rightIndexed.events}")
    }

    return "OK"
}
