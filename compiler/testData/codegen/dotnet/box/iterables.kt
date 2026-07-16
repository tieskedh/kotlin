private class CountingIterator(private val limit: Int) : Iterator<Int> {
    private var value = 0

    override fun hasNext(): Boolean = value < limit

    override fun next(): Int {
        if (!hasNext()) throw NoSuchElementException()
        return value++
    }
}

private class CountingIterable(private val limit: Int) : Iterable<Int> {
    override fun iterator(): Iterator<Int> = CountingIterator(limit)
}

private class SingleIterator<T>(private val value: T) : Iterator<T> {
    private var available = true

    override fun hasNext(): Boolean = available

    override fun next(): T {
        if (!available) throw NoSuchElementException()
        available = false
        return value
    }
}

private open class BaseIterable<T>(private val value: T) : Iterable<T> {
    override fun iterator(): Iterator<T> = SingleIterator(value)
}

private class DerivedIterable : BaseIterable<String>("derived")

private abstract class DeferredIterable<T> : Iterable<T>

private class DeferredStringIterable(private val value: String) : DeferredIterable<String>() {
    override fun iterator(): Iterator<String> = SingleIterator(value)
}

private interface IterableView<out T> : Iterable<T> {
    fun label(): String
}

private class ViewedIterable(private val value: String) : IterableView<String> {
    override fun iterator(): Iterator<String> = SingleIterator(value)

    override fun label(): String = "view"
}

private class IntViewedIterable : IterableView<Int> {
    override fun iterator(): Iterator<Int> = CountingIterator(1)

    override fun label(): String = "int view"
}

private fun sum(values: Iterable<Int>): Int {
    var result = 0
    for (value in values) result = result + value
    return result
}

private fun <T> openArrayView(values: Array<T>): Iterable<T> = values.asIterable()

private fun <T> firstFrom(values: Iterable<T>): T = values.first()

private fun <T> lastFrom(values: Iterable<T>): T = values.last()

fun box(): String {
    val counting = CountingIterable(4)
    val widened: Iterable<Any> = counting
    if (widened !== counting) return "fail 1: covariance identity"
    if (sum(counting) != 6) return "fail 2: iterable for loop"
    if (widened.iterator().next() != 0) return "fail 3: widened primitive result"
    if (BaseIterable("generic").iterator().next() != "generic") return "fail 4: generic iterable"
    if (DerivedIterable().iterator().next() != "derived") return "fail 5: inherited bridge"
    if (DeferredStringIterable("deferred").iterator().next() != "deferred") {
        return "fail 6: deferred bridge"
    }

    val view: IterableView<String> = ViewedIterable("subinterface")
    if (view.label() != "view" || view.iterator().next() != "subinterface") {
        return "fail 7: iterable subinterface"
    }
    val erasedView: Iterable<Any> = view
    if (erasedView !== view) return "fail 8: subinterface identity"
    if (erasedView.iterator().next() != "subinterface") return "fail 9: erased subinterface call"

    val intView: IterableView<Int> = IntViewedIterable()
    val erasedIntView: Iterable<Any> = intView
    if (erasedIntView !== intView) return "fail 10: primitive subinterface identity"
    if (erasedIntView.iterator().next() != 0) return "fail 11: primitive subinterface result"

    val words = arrayOf("before", "second")
    val wordView: Iterable<String> = words.asIterable()
    val widenedWordView: Iterable<Any> = wordView
    if (widenedWordView !== wordView) return "fail 12: array view covariance identity"
    words[0] = "after"
    val firstWords = wordView.iterator()
    val secondWords = wordView.iterator()
    if (firstWords.next() != "after" || firstWords.next() != "second") {
        return "fail 13: reference array view"
    }
    if (secondWords.next() != "after") return "fail 14: independent array view iterators"

    val numbers = intArrayOf(2, 3, 4)
    val numberView = numbers.asIterable()
    numbers[0] = 1
    if (sum(numberView) != 8) return "fail 15: primitive array view"
    if (openArrayView(arrayOf("open")).iterator().next() != "open") {
        return "fail 16: open array view"
    }
    if (emptyArray<String>().asIterable().iterator().hasNext()) return "fail 17: empty array view"
    if (arrayOf<String?>(null).asIterable().iterator().next() != null) {
        return "fail 18: nullable-element array view"
    }
    if (firstFrom(CountingIterable(1)) != 0) return "fail 19: stdlib first user iterable"
    val widenedFirst: Iterable<Any> = CountingIterable(1)
    if (widenedFirst.first() != 0) return "fail 20: stdlib first widened primitive"
    if (firstFrom(arrayOf("stdlib").asIterable()) != "stdlib") {
        return "fail 21: stdlib first array view"
    }
    if (arrayOf<String?>(null).asIterable().first() != null) {
        return "fail 22: stdlib first nullable element"
    }
    val nullableNumber: Int? = firstFrom(BaseIterable<Int?>(7))
    if (nullableNumber != 7) return "fail 23: stdlib first nullable primitive"
    try {
        emptyArray<String>().asIterable().first()
        return "fail 24: stdlib first empty did not throw"
    } catch (failure: NoSuchElementException) {
        if (failure.message != "Collection is empty.") {
            return "fail 25: stdlib first message: ${failure.message}"
        }
    }
    if (lastFrom(CountingIterable(4)) != 3) return "fail 26: stdlib last user iterable"
    val widenedLast: Iterable<Any> = CountingIterable(3)
    if (widenedLast.last() != 2) return "fail 27: stdlib last widened primitive"
    if (lastFrom(arrayOf("first", "last").asIterable()) != "last") {
        return "fail 28: stdlib last array view"
    }
    val nullableLast: Int? = lastFrom(BaseIterable<Int?>(9))
    if (nullableLast != 9) return "fail 29: stdlib last nullable primitive"
    try {
        emptyArray<String>().asIterable().last()
        return "fail 30: stdlib last empty did not throw"
    } catch (failure: NoSuchElementException) {
        if (failure.message != "Collection is empty.") {
            return "fail 31: stdlib last message: ${failure.message}"
        }
    }
    return "OK"
}
