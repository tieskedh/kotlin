private class ReduceCountingIterable<T>(private val values: Array<T>) : Iterable<T> {
    var iteratorCalls: Int = 0
    var hasNextCalls: Int = 0
    var nextCalls: Int = 0

    override fun iterator(): Iterator<T> {
        iteratorCalls++
        return ReduceCountingIterator(values, this)
    }
}

private class ReduceCountingIterator<T>(
    private val values: Array<T>,
    private val owner: ReduceCountingIterable<T>,
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

private class ReduceReverseList<T>(private val values: Array<T>) : List<T> {
    var listIteratorCalls: Int = 0
    var requestedIndex: Int = -1
    var events: String = ""

    override val size: Int get() = values.size

    override fun isEmpty(): Boolean = throw Error("reduceRight used List.isEmpty()")

    override fun get(index: Int): T = throw Error("reduceRight used List.get()")

    override fun contains(element: T): Boolean = throw Error("reduceRight used List.contains()")

    override fun containsAll(elements: Collection<T>): Boolean =
        throw Error("reduceRight used List.containsAll()")

    override fun indexOf(element: T): Int = throw Error("reduceRight used List.indexOf()")

    override fun lastIndexOf(element: T): Int = throw Error("reduceRight used List.lastIndexOf()")

    override fun iterator(): Iterator<T> = throw Error("reduceRight used List.iterator()")

    override fun listIterator(): ListIterator<T> = throw Error("reduceRight used List.listIterator()")

    override fun listIterator(index: Int): ListIterator<T> {
        listIteratorCalls++
        requestedIndex = index
        events += "list($index)|"
        if (index != values.size) throw Error("reduceRight did not start at List.size")
        return ReduceReverseIterator(values, this, index)
    }

    override fun subList(fromIndex: Int, toIndex: Int): List<T> =
        throw Error("reduceRight used List.subList()")
}

private class ReduceReverseIterator<T>(
    private val values: Array<T>,
    private val owner: ReduceReverseList<T>,
    private var cursor: Int,
) : ListIterator<T> {
    override fun hasNext(): Boolean = throw Error("reduceRight used ListIterator.hasNext()")

    override fun next(): T = throw Error("reduceRight used ListIterator.next()")

    override fun nextIndex(): Int = throw Error("reduceRight used ListIterator.nextIndex()")

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

private fun reduceFail(message: String): String = "fail: $message"

fun box(): String {
    var operations = 0
    val empty = ReduceCountingIterable(emptyArray<Int>())
    try {
        empty.reduce { accumulator, element -> operations++; accumulator + element }
        return reduceFail("empty reduce returned")
    } catch (failure: UnsupportedOperationException) {
        if (failure.message != "Empty collection can't be reduced.") return reduceFail("empty reduce message")
    }
    try {
        empty.reduceIndexed { index, accumulator, element -> operations++; index + accumulator + element }
        return reduceFail("empty reduceIndexed returned")
    } catch (failure: UnsupportedOperationException) {
        if (failure.message != "Empty collection can't be reduced.") return reduceFail("empty reduceIndexed message")
    }
    if (empty.reduceOrNull { accumulator, element -> operations++; accumulator + element } != null) {
        return reduceFail("empty reduceOrNull result")
    }
    if (empty.reduceIndexedOrNull { index, accumulator, element -> operations++; index + accumulator + element } != null) {
        return reduceFail("empty reduceIndexedOrNull result")
    }
    if (empty.iteratorCalls != 4 || empty.hasNextCalls != 4 || empty.nextCalls != 0 || operations != 0) {
        return reduceFail("empty left protocol")
    }

    val singleton = ReduceCountingIterable(arrayOf(7))
    if (singleton.reduce { accumulator, element -> operations++; accumulator + element } != 7) {
        return reduceFail("singleton reduce")
    }
    if (singleton.reduceIndexed { index, accumulator, element -> operations++; index + accumulator + element } != 7) {
        return reduceFail("singleton reduceIndexed")
    }
    if (singleton.reduceOrNull { accumulator, element -> operations++; accumulator + element } != 7) {
        return reduceFail("singleton reduceOrNull")
    }
    if (singleton.reduceIndexedOrNull { index, accumulator, element -> operations++; index + accumulator + element } != 7) {
        return reduceFail("singleton reduceIndexedOrNull")
    }
    if (singleton.iteratorCalls != 4 || singleton.hasNextCalls != 8 || singleton.nextCalls != 4 || operations != 0) {
        return reduceFail("singleton callback/protocol")
    }

    val left = ReduceCountingIterable(arrayOf(2, 3, 5))
    var leftTrace = ""
    val leftResult = left.reduce { accumulator, element ->
        leftTrace += "$accumulator+$element|"
        accumulator + element
    }
    if (leftResult != 10 || leftTrace != "2+3|5+5|") return reduceFail("reduce order/result")
    var indexedTrace = ""
    val indexedResult = left.reduceIndexed { index, accumulator, element ->
        indexedTrace += "$index:$accumulator+$element|"
        accumulator + index * element
    }
    if (indexedResult != 15 || indexedTrace != "1:2+3|2:5+5|") {
        return reduceFail("reduceIndexed order/result")
    }
    if (left.iteratorCalls != 2 || left.hasNextCalls != 8 || left.nextCalls != 6) {
        return reduceFail("left traversal")
    }

    val widened: Any? = ReduceCountingIterable(arrayOf(1, 2, 3)).reduce<Any?, Int> { accumulator, element ->
        (accumulator as Int) + element
    }
    if (widened != 6) return reduceFail("widened S/T bound")
    val nullableElements = ReduceCountingIterable(arrayOf<Int?>(null, 2, 3))
    val nullableResult: Int? = nullableElements.reduceOrNull { accumulator, element ->
        (accumulator ?: 0) + (element ?: 0)
    }
    if (nullableResult != 5) return reduceFail("nullable T/S result")

    val operationFailure = Error("reduce operation failure")
    val failing = ReduceCountingIterable(arrayOf(1, 2, 3))
    operations = 0
    try {
        failing.reduce { accumulator, element ->
            operations++
            if (element == 2) throw operationFailure
            accumulator + element
        }
        return reduceFail("operation failure was swallowed")
    } catch (caught: Throwable) {
        if (caught !== operationFailure) return reduceFail("operation failure identity")
    }
    if (operations != 1 || failing.hasNextCalls != 2 || failing.nextCalls != 2) {
        return reduceFail("operation failure timing")
    }

    operations = 0
    val emptyRight = ReduceReverseList(emptyArray<Int>())
    try {
        emptyRight.reduceRight { element, accumulator -> operations++; element + accumulator }
        return reduceFail("empty reduceRight returned")
    } catch (failure: UnsupportedOperationException) {
        if (failure.message != "Empty list can't be reduced.") return reduceFail("empty reduceRight message")
    }
    try {
        emptyRight.reduceRightIndexed { index, element, accumulator -> operations++; index + element + accumulator }
        return reduceFail("empty reduceRightIndexed returned")
    } catch (failure: UnsupportedOperationException) {
        if (failure.message != "Empty list can't be reduced.") return reduceFail("empty reduceRightIndexed message")
    }
    if (emptyRight.reduceRightOrNull { element, accumulator -> operations++; element + accumulator } != null) {
        return reduceFail("empty reduceRightOrNull result")
    }
    if (emptyRight.reduceRightIndexedOrNull { index, element, accumulator ->
            operations++
            index + element + accumulator
        } != null
    ) {
        return reduceFail("empty reduceRightIndexedOrNull result")
    }
    if (emptyRight.listIteratorCalls != 4 || emptyRight.requestedIndex != 0 || operations != 0) {
        return reduceFail("empty right callback/protocol")
    }
    if (emptyRight.events != "list(0)|hasPrevious|list(0)|hasPrevious|list(0)|hasPrevious|list(0)|hasPrevious|") {
        return reduceFail("empty right events: ${emptyRight.events}")
    }

    operations = 0
    val singletonRight = ReduceReverseList(arrayOf(9))
    if (singletonRight.reduceRight { element, accumulator -> operations++; element + accumulator } != 9 ||
        singletonRight.reduceRightIndexed { index, element, accumulator -> operations++; index + element + accumulator } != 9 ||
        singletonRight.reduceRightOrNull { element, accumulator -> operations++; element + accumulator } != 9 ||
        singletonRight.reduceRightIndexedOrNull { index, element, accumulator ->
            operations++
            index + element + accumulator
        } != 9
    ) {
        return reduceFail("singleton right result")
    }
    if (operations != 0 || singletonRight.listIteratorCalls != 4) return reduceFail("singleton right callbacks")

    val right = ReduceReverseList(arrayOf(1, 2, 3))
    var rightTrace = ""
    val rightResult = right.reduceRight { element, accumulator ->
        rightTrace += "$element:$accumulator|"
        element - accumulator
    }
    if (rightResult != 2 || rightTrace != "2:3|1:-1|") return reduceFail("reduceRight order/result")
    if (right.events != "list(3)|hasPrevious|previous|hasPrevious|previous|hasPrevious|previous|hasPrevious|") {
        return reduceFail("reduceRight events: ${right.events}")
    }

    val rightIndexed = ReduceReverseList(arrayOf(1, 2, 3))
    var rightIndexedTrace = ""
    val rightIndexedResult = rightIndexed.reduceRightIndexedOrNull { index, element, accumulator ->
        rightIndexedTrace += "$index:$element:$accumulator|"
        index + element + accumulator
    }
    if (rightIndexedResult != 7 || rightIndexedTrace != "1:2:3|0:1:6|") {
        return reduceFail("reduceRightIndexedOrNull order/result")
    }
    if (
        rightIndexed.events !=
        "list(3)|hasPrevious|previous|hasPrevious|previousIndex|previous|hasPrevious|previousIndex|previous|hasPrevious|"
    ) {
        return reduceFail("reduceRightIndexed events: ${rightIndexed.events}")
    }

    return "OK"
}
