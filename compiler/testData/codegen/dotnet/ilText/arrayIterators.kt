fun intIterator(values: IntArray): IntIterator = values.iterator()

fun nextInt(values: IntIterator): Int = values.nextInt()

fun nextBoxed(values: Iterator<Int>): Int = values.next()

fun <T> nextGeneric(values: Iterator<T>): T = values.next()

fun <T> openIterator(values: Array<T>): Iterator<T> = values.iterator()

fun <T> firstFromOpenArray(values: Array<T>): T = openIterator(values).next()

fun stringIterator(values: Array<String>): Iterator<String> = values.iterator()

fun hasNext(values: Iterator<String>): Boolean = values.hasNext()

fun iteratorAsAny(values: IntArray): Any = values.iterator()

fun nextOrMinusOne(values: Iterator<Int>): Int = try {
    values.next()
} catch (_: NoSuchElementException) {
    -1
}

class StringValueIterator(private val value: String) : Iterator<String> {
    private var available = true

    override fun hasNext(): Boolean = available

    override fun next(): String {
        available = false
        return value
    }
}

class IntValueIterator(private val value: Int) : Iterator<Int> {
    private var available = true

    override fun hasNext(): Boolean = available

    override fun next(): Int {
        available = false
        return value
    }
}

class CustomIntIterator(private val value: Int) : IntIterator() {
    private var available = true

    override fun hasNext(): Boolean = available

    override fun nextInt(): Int {
        if (!hasNext()) throw NoSuchElementException()
        available = false
        return value
    }
}

class GenericValueIterator<T>(private val value: T) : Iterator<T> {
    private var available = true

    override fun hasNext(): Boolean = available

    override fun next(): T {
        available = false
        return value
    }
}

abstract class DeferredValueIterator<T> : Iterator<T>

class DeferredStringValueIterator(private val value: String) : DeferredValueIterator<String>() {
    override fun hasNext(): Boolean = true

    override fun next(): String = value
}

interface IteratorView<out T> : Iterator<T> {
    fun label(): String
}

class ViewedStringIterator(private val value: String) : IteratorView<String> {
    override fun hasNext(): Boolean = true

    override fun next(): String = value

    override fun label(): String = "view"
}

fun directStringNext(values: StringValueIterator): String = values.next()

fun erasedStringNext(values: Iterator<String>): String = values.next()

fun erasedIntNext(values: Iterator<Int>): Int = values.next()

fun <T> erasedGenericNext(values: Iterator<T>): T = values.next()

fun hasNextThroughView(values: IteratorView<String>): Boolean = values.hasNext()

fun nextThroughView(values: IteratorView<String>): String = values.next()

fun main() {
    println(nextInt(intArrayOf(7).iterator()))
    println(erasedStringNext(StringValueIterator("user")))
    println(erasedIntNext(IntValueIterator(8)))
    println(nextInt(CustomIntIterator(9)))
    println(erasedGenericNext(GenericValueIterator("generic")))
    println(erasedStringNext(DeferredStringValueIterator("deferred")))
    println(firstFromOpenArray(arrayOf("open")))
    val viewed = ViewedStringIterator("subinterface")
    println(hasNextThroughView(viewed))
    println(nextThroughView(viewed))
}
