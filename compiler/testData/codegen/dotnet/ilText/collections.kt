// Collection adds one invariant exact capability to the canonical + covariant-declared split:
// contains(E) cannot live on a CLR-covariant interface, while the canonical ContainsErased(object)
// slot must retain Kotlin's wrong-shaped-input-false collection contract.

private class OneIterator<T>(
    private val value: T,
    private var available: Boolean,
) : Iterator<T> {
    override fun hasNext(): Boolean = available

    override fun next(): T {
        if (!available) throw NoSuchElementException()
        available = false
        return value
    }
}

class OneCollection<T>(
    private val value: T,
    private val present: Boolean,
) : Collection<T> {
    override val size: Int get() = if (present) 1 else 0

    override fun isEmpty(): Boolean = !present

    override fun contains(element: T): Boolean = present && element == value

    override fun iterator(): Iterator<T> = OneIterator(value, present)

    override fun containsAll(elements: Collection<T>): Boolean {
        val iterator = elements.iterator()
        while (iterator.hasNext()) {
            if (!contains(iterator.next())) return false
        }
        return true
    }
}

fun exactContains(collection: Collection<Int>): Boolean = collection.contains(7)

fun wideContains(collection: Collection<Any?>): Boolean = collection.contains(7)

fun exactFirst(collection: Collection<Int>): Int = collection.iterator().next()

fun wideFirst(collection: Collection<Any?>): Any? = collection.iterator().next()

fun exactContainsAll(collection: Collection<Int>, elements: Collection<Int>): Boolean =
    collection.containsAll(elements)

fun <T> openCalls(collection: Collection<T>, value: T): Boolean =
    collection.size == 1 &&
            !collection.isEmpty() &&
            collection.contains(value) &&
            collection.iterator().next() == value

fun widen(collection: Collection<Int>): Collection<Any?> = collection

fun main() {
    val collection: Collection<Int> = OneCollection(7, true)
    println(exactContains(collection))
    println(wideContains(widen(collection)))
    println(exactFirst(collection))
    println(wideFirst(widen(collection)))
    println(exactContainsAll(collection, OneCollection(7, true)))
    println(openCalls(collection, 7))
    println(widen(collection) === collection)
}
