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
    return "OK"
}
