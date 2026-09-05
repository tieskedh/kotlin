// A semantic body still executes on one exact generic-owner construction. Its generic helper may
// retain receiver-derived T only across output-only callback positions; captured or independent
// broad candidates stay in the semantic domain.

private fun <T> MutableIterator<T>.matches(candidate: Any?): Boolean = next() == candidate

// A is fixed by the exact receiver. B is deliberately independent even when the caller maps both
// method parameters to the same owner T; exactness of A must never be reused as evidence for B.
private fun <A, B> MutableIterator<A>.selectIndependent(value: B): B = value

private fun <T> MutableList<T>.removeMatching(predicate: (T) -> Boolean): Boolean {
    var changed = false
    var index = 0
    while (index < size) {
        if (predicate(this[index])) {
            removeAt(index)
            changed = true
        } else {
            index++
        }
    }
    return changed
}

private class ReceiverIterator<out T>(private val value: T) :
    MutableIterator<@UnsafeVariance T> {
    private var consumed: Boolean = false

    override fun hasNext(): Boolean = !consumed

    override fun next(): T {
        if (consumed) throw NoSuchElementException()
        consumed = true
        return value
    }

    override fun remove() {
        throw UnsupportedOperationException()
    }

    fun accepts(candidate: @UnsafeVariance T): Boolean = matches(candidate)
}

private class IndependentHelperOwner<out T>(private val exact: T) :
    MutableIterator<@UnsafeVariance T> {
    private var consumed: Boolean = false

    override fun hasNext(): Boolean = !consumed

    override fun next(): T {
        if (consumed) throw NoSuchElementException()
        consumed = true
        return exact
    }

    override fun remove() {
        throw UnsupportedOperationException()
    }

    fun passIndependent(value: @UnsafeVariance T): T =
        selectIndependent<T, T>(value)
}

// Repeat MutableList<T> so this user-module proof owns the natural Runtime interface edge even
// though the external AbstractMutableList fixture remains production-erased.
private class SingleMutableList<T>(private var value: T) : AbstractMutableList<T>(), MutableList<T> {
    private var present: Boolean = true

    override val size: Int
        get() = if (present) 1 else 0

    override fun get(index: Int): T {
        if (!present || index != 0) throw IndexOutOfBoundsException()
        return value
    }

    override fun add(index: Int, element: T) {
        if (present || index != 0) throw IndexOutOfBoundsException()
        value = element
        present = true
    }

    override fun removeAt(index: Int): T {
        val removed = get(index)
        present = false
        return removed
    }

    override fun set(index: Int, element: T): T {
        val previous = get(index)
        value = element
        return previous
    }

    override fun contains(element: T): Boolean = present && value == element

    override fun containsAll(elements: Collection<T>): Boolean {
        for (element in elements) if (!contains(element)) return false
        return true
    }

    override fun addAll(elements: Collection<T>): Boolean = addAll(size, elements)

    override fun addAll(index: Int, elements: Collection<T>): Boolean {
        var insertionIndex = index
        var changed = false
        for (element in elements) {
            add(insertionIndex++, element)
            changed = true
        }
        return changed
    }

    override fun removeAll(elements: Collection<T>): Boolean = removeMatching { it in elements }

    override fun retainAll(elements: Collection<T>): Boolean = removeMatching { it !in elements }
}

fun box(): String {
    if (!ReceiverIterator(42).accepts(42)) return "exact value helper"
    if (ReceiverIterator(42).accepts(43)) return "exact value miss"

    if (!ReceiverIterator("typed").accepts("typed")) return "exact reference helper"
    if (ReceiverIterator("typed").accepts("other")) return "exact reference miss"

    val exactIndependent = IndependentHelperOwner(41)
    if (exactIndependent.passIndependent(42) != 42) return "exact independent helper"
    val widenedIndependent: IndependentHelperOwner<Any?> = exactIndependent
    if (widenedIndependent !== exactIndependent) return "independent helper owner identity"
    val independentResult = try {
        widenedIndependent.passIndependent("semantic")
    } catch (_: ClassCastException) {
        return "independent helper narrowed B to exact A"
    }
    if (independentResult != "semantic") return "semantic independent helper"

    val ints = ReceiverIterator(42)
    val widened: ReceiverIterator<Any?> = ints
    if (widened !== ints) return "widened identity"
    if (!widened.accepts(42)) return "semantic input"
    val widenedMiss: ReceiverIterator<Any?> = ReceiverIterator(42)
    if (widenedMiss.accepts("other")) return "semantic input miss"

    val values = SingleMutableList(42)
    if (!values.removeAll(SingleMutableList(42)) || values.isNotEmpty()) {
        return "runtime exact receiver helper"
    }

    val retained = SingleMutableList("typed")
    if (retained.removeAll(SingleMutableList("other")) || retained[0] != "typed") {
        return "runtime exact receiver helper miss"
    }

    return "OK"
}
