private class PairListIterator<T>(
    private val list: List<T>,
    private var position: Int,
) : ListIterator<T> {
    override fun hasNext(): Boolean = position < list.size

    override fun next(): T {
        if (!hasNext()) throw NoSuchElementException()
        return list.get(position++)
    }

    override fun hasPrevious(): Boolean = position > 0

    override fun previous(): T {
        if (!hasPrevious()) throw NoSuchElementException()
        return list.get(--position)
    }

    override fun nextIndex(): Int = position

    override fun previousIndex(): Int = position - 1
}

private class PairList<T> private constructor(
    private val first: T,
    private val second: T,
    private val fromIndex: Int,
    private val toIndex: Int,
) : List<T> {
    constructor(first: T, second: T) : this(first, second, 0, 2)

    var iteratorCalls: Int = 0
        private set

    override val size: Int get() = toIndex - fromIndex

    override fun isEmpty(): Boolean = size == 0

    override fun contains(element: T): Boolean = indexOf(element) >= 0

    override fun iterator(): Iterator<T> {
        iteratorCalls++
        return listIterator()
    }

    override fun containsAll(elements: Collection<T>): Boolean {
        val iterator = elements.iterator()
        while (iterator.hasNext()) {
            if (!contains(iterator.next())) return false
        }
        return true
    }

    override fun get(index: Int): T {
        if (index < 0 || index >= size) throw IndexOutOfBoundsException()
        return if (fromIndex + index == 0) first else second
    }

    override fun indexOf(element: T): Int {
        var index = 0
        while (index < size) {
            if (get(index) == element) return index
            index++
        }
        return -1
    }

    override fun lastIndexOf(element: T): Int {
        var index = size - 1
        while (index >= 0) {
            if (get(index) == element) return index
            index--
        }
        return -1
    }

    override fun listIterator(): ListIterator<T> = PairListIterator(this, 0)

    override fun listIterator(index: Int): ListIterator<T> {
        if (index < 0 || index > size) throw IndexOutOfBoundsException()
        return PairListIterator(this, index)
    }

    override fun subList(fromIndex: Int, toIndex: Int): List<T> {
        if (fromIndex < 0 || toIndex > size) throw IndexOutOfBoundsException()
        if (fromIndex > toIndex) throw IllegalArgumentException()
        return PairList(first, second, this.fromIndex + fromIndex, this.fromIndex + toIndex)
    }
}

private fun <T> openCalls(list: List<T>, first: T, second: T): Boolean =
    list.size == 2 &&
            !list.isEmpty() &&
            list.get(0) == first &&
            list.get(1) == second &&
            list.contains(first) &&
            list.indexOf(second) == 1 &&
            list.lastIndexOf(first) == 0

private fun <T> firstFromList(list: List<T>): T = list.first()

private fun <T> lastFromList(list: List<T>): T = list.last()

private fun <T> firstFromIterable(iterable: Iterable<T>): T = iterable.first()

private fun <T> lastFromIterable(iterable: Iterable<T>): T = iterable.last()

private fun nullableBottomAsString(value: Nothing?): String? = value

private fun nullableBottomAsInt(value: Nothing?): Int? = value

private var nullableBottomCalls: Int = 0

private fun sideEffectingNullableBottom(): Nothing? {
    nullableBottomCalls++
    return null
}

fun box(): String {
    val ints: List<Int> = PairList(7, 9)
    val intsWide: List<Any?> = ints
    if (ints !== intsWide) return "fail 1: primitive widening changed identity"
    if (ints.get(0) != 7 || intsWide.get(1) != 9) return "fail 2: typed and erased get"
    if (ints.size != 2 || ints.isEmpty()) return "fail 3: size or isEmpty"
    if (!ints.contains(7) || !intsWide.contains(9)) return "fail 4: contains"
    if (intsWide.contains("7") || intsWide.contains(null)) return "fail 5: contains barrier"
    if (ints.indexOf(9) != 1 || ints.lastIndexOf(7) != 0) return "fail 6: search"
    if (intsWide.indexOf("9") != -1 || intsWide.lastIndexOf(null) != -1) return "fail 7: search barrier"

    val duplicates: List<Int> = PairList(7, 7)
    if (duplicates.indexOf(7) != 0 || duplicates.lastIndexOf(7) != 1) return "fail 8: duplicate search"

    val iterator = ints.iterator()
    if (!iterator.hasNext() || iterator.next() != 7 || iterator.next() != 9) return "fail 9: iterator"
    val listIterator = ints.listIterator(1)
    if (listIterator.nextIndex() != 1 || listIterator.next() != 9 || listIterator.previous() != 9) {
        return "fail 10: listIterator"
    }

    val tail: List<Any?> = intsWide.subList(1, 2)
    if (tail.size != 1 || tail.get(0) != 9) return "fail 11: subList"
    val empty = ints.subList(0, 0)
    if (!empty.isEmpty() || empty.size != 0) return "fail 12: empty subList"

    if (!ints.containsAll(PairList(7, 9))) return "fail 13: containsAll"
    if (!openCalls(ints, 7, 9)) return "fail 14: open primitive calls"

    val strings: List<String> = PairList("a", "b")
    val stringsWide: List<Any?> = strings
    if (strings !== stringsWide || stringsWide.get(0) != "a") return "fail 15: reference widening"
    if (stringsWide.contains(1) || stringsWide.indexOf(1) != -1 || stringsWide.lastIndexOf(null) != -1) {
        return "fail 16: reference barriers"
    }
    if (!openCalls(strings, "a", "b")) return "fail 17: open reference calls"

    val nullable: List<Int?> = PairList(null, 3)
    val nullableWide: List<Any?> = nullable
    if (nullable !== nullableWide || !nullableWide.contains(null) || nullableWide.indexOf(null) != 0) {
        return "fail 18: nullable element"
    }

    val asCollection: Collection<Int> = ints
    if (!asCollection.contains(7)) return "fail 19: exact Collection super-view"

    val fastPathList = PairList(11, 13)
    val fastPathWide: List<Any?> = fastPathList
    if (firstFromList(fastPathList) != 11 || lastFromList(fastPathWide) != 13) {
        return "fail 20: direct List first/last"
    }
    val fastPathIterable: Iterable<Int> = fastPathList
    if (firstFromIterable(fastPathIterable) != 11 || lastFromIterable(fastPathIterable) != 13) {
        return "fail 21: Iterable List fast path"
    }
    if (fastPathList.iteratorCalls != 0) return "fail 22: List fast path allocated an iterator"
    val emptyAsIterable: Iterable<Int> = empty
    try {
        emptyAsIterable.first()
        return "fail 23: empty List-as-Iterable first did not throw"
    } catch (failure: NoSuchElementException) {
        if (failure.message != "List is empty.") return "fail 24: first message: ${failure.message}"
    }
    try {
        empty.last()
        return "fail 25: empty List last did not throw"
    } catch (failure: NoSuchElementException) {
        if (failure.message != "List is empty.") return "fail 26: last message: ${failure.message}"
    }

    val stdlibInts = emptyList<Int>()
    val stdlibStrings = emptyList<String>()
    val stdlibWide: List<Any?> = stdlibInts
    if (stdlibWide !== stdlibStrings) return "fail 27: emptyList singleton identity"
    if (stdlibInts.size != 0 || !stdlibInts.isEmpty()) return "fail 28: emptyList size"
    if (stdlibInts !is RandomAccess) return "fail 29: emptyList RandomAccess"
    if (stdlibInts.contains(0) || stdlibWide.contains("x") || stdlibWide.contains(null)) {
        return "fail 30: emptyList contains"
    }
    if (!stdlibInts.containsAll(emptyList<Int>())) return "fail 31: emptyList containsAll"
    if (stdlibInts.indexOf(0) != -1 || stdlibWide.lastIndexOf("x") != -1) {
        return "fail 32: emptyList search"
    }
    if (stdlibInts.hashCode() != 1) return "fail 33a: emptyList hashCode ${stdlibInts.hashCode()}"
    if (stdlibInts.toString() != "[]") return "fail 33b: emptyList toString ${stdlibInts}"
    if (stdlibInts != empty) return "fail 33c: emptyList equality"

    val stdlibIterator = stdlibInts.iterator()
    val stdlibListIterator = stdlibStrings.listIterator()
    if (stdlibIterator !== stdlibListIterator) return "fail 34: EmptyIterator singleton identity"
    if (stdlibIterator.hasNext() || stdlibListIterator.hasPrevious()) return "fail 35: EmptyIterator state"
    if (stdlibListIterator.nextIndex() != 0 || stdlibListIterator.previousIndex() != -1) {
        return "fail 36: EmptyIterator indices"
    }
    try {
        stdlibIterator.next()
        return "fail 37: EmptyIterator.next did not throw"
    } catch (_: NoSuchElementException) {
    }
    try {
        stdlibListIterator.previous()
        return "fail 37b: EmptyIterator.previous did not throw"
    } catch (_: NoSuchElementException) {
    }
    try {
        stdlibStrings.listIterator(1)
        return "fail 38: EmptyList.listIterator accepted index 1"
    } catch (failure: IndexOutOfBoundsException) {
        if (failure.message != "Index: 1") return "fail 39: listIterator message: ${failure.message}"
    }
    try {
        stdlibInts.get(2)
        return "fail 40: EmptyList.get did not throw"
    } catch (failure: IndexOutOfBoundsException) {
        if (failure.message != "Empty list doesn't contain element at index 2.") {
            return "fail 41: get message: ${failure.message}"
        }
    }
    if (stdlibInts.subList(0, 0) !== stdlibInts) return "fail 42: empty subList identity"
    try {
        stdlibInts.subList(0, 1)
        return "fail 43: invalid empty subList did not throw"
    } catch (_: IndexOutOfBoundsException) {
    }

    val emptyArrayIterable = emptyArray<String>().asIterable()
    val emptyPrimitiveIterable = intArrayOf().asIterable()
    if (emptyArrayIterable !== stdlibStrings || emptyPrimitiveIterable !== stdlibInts) {
        return "fail 44: empty array asIterable singleton"
    }
    if (arrayOf("x").asIterable().first() != "x") return "fail 45: non-empty array asIterable"

    val nullableBottom: Nothing? = null
    val nullableString: String? = nullableBottom
    val nullableInt: Int? = nullableBottom
    if (nullableString != null || nullableInt != null) return "fail 46: local nullable bottom widening"
    if (nullableBottomAsString(null) != null || nullableBottomAsInt(null) != null) {
        return "fail 47: parameter nullable bottom widening"
    }
    val sideEffectingString: String? = sideEffectingNullableBottom()
    val sideEffectingInt: Int? = sideEffectingNullableBottom()
    if (sideEffectingString != null || sideEffectingInt != null || nullableBottomCalls != 2) {
        return "fail 48: side-effecting nullable bottom widening"
    }

    return "OK"
}
