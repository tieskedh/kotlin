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

fun <T> iterableFromOpenArray(values: Array<T>): Iterable<T> = values.asIterable()

fun <T> firstFrom(values: Iterable<T>): T = values.first()

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
    val words = arrayOf("before")
    val wordView = words.asIterable()
    words[0] = "after"
    println(wordView.iterator().next())
    println(sum(intArrayOf(1, 2, 3).asIterable()))
    println(iterableFromOpenArray(arrayOf("open")).iterator().next())
    println(emptyArray<String>().asIterable().iterator().hasNext())
    println(firstFrom(CountingIterable(1)))
    println(arrayOf("stdlib").asIterable().first())
}
