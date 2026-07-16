class CountingIterator(private val limit: Int) : Iterator<Int> {
    private var value = 0

    override fun hasNext(): Boolean = value < limit

    override fun next(): Int = value++
}

class CountingIterable(private val limit: Int) : Iterable<Int> {
    override fun iterator(): Iterator<Int> = CountingIterator(limit)
}

class StringIterator(private val value: String) : Iterator<String> {
    override fun hasNext(): Boolean = true

    override fun next(): String = value
}

interface IterableView<out T> : Iterable<T> {
    fun label(): String
}

class ViewedIterable(private val value: String) : IterableView<String> {
    override fun iterator(): Iterator<String> = StringIterator(value)

    override fun label(): String = "view"
}

fun iteratorFrom(values: Iterable<Int>): Iterator<Int> = values.iterator()

fun iteratorThroughView(values: IterableView<String>): Iterator<String> = values.iterator()

fun sum(values: Iterable<Int>): Int {
    var result = 0
    for (value in values) result = result + value
    return result
}

fun main() {
    val values = CountingIterable(3)
    println(iteratorFrom(values).next())
    println(sum(values))
    println(iteratorThroughView(ViewedIterable("viewed")).next())
}
