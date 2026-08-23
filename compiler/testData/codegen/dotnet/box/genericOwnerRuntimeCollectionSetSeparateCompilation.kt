// MODULE: lib
// FILE: collectionSetApi.kt

// DOTNET_GENERIC_OWNER_RUNTIME_COLLECTION_SET_CSHARP_PROBE

package generic.owner.runtime.collectionset

public fun exactCollectionContains(collection: Collection<String>, value: String): Boolean =
    collection.contains(value)

public fun widenedCollectionContains(collection: Collection<Any?>, value: Any?): Boolean =
    collection.contains(value)

public fun widenedCollectionContainsAll(
    collection: Collection<Any?>,
    elements: Collection<Any?>,
): Boolean = collection.containsAll(elements)

public fun <T> exactCollectionIterator(collection: Collection<T>): Iterator<T> =
    collection.iterator()

public fun sameCollection(collection: Collection<Any?>, expected: Any?): Boolean =
    collection === expected

public fun exactSetContains(set: Set<String>, value: String): Boolean =
    set.contains(value)

public fun widenedSetContains(set: Set<Any?>, value: Any?): Boolean =
    set.contains(value)

public fun widenedSetContainsAll(set: Set<Any?>, elements: Collection<Any?>): Boolean =
    set.containsAll(elements)

public fun <T> exactSetIterator(set: Set<T>): Iterator<T> = set.iterator()

public fun sameSet(set: Set<Any?>, expected: Any?): Boolean = set === expected

// MODULE: middle(lib)
// FILE: collectionSetImplementation.kt

package generic.owner.runtime.collectionset

private class PairIterator<T>(
    private val first: T,
    private val second: T,
) : Iterator<T> {
    private var index: Int = 0

    override fun hasNext(): Boolean = index < 2

    override fun next(): T = when (index++) {
        0 -> first
        1 -> second
        else -> throw NoSuchElementException()
    }
}

public class RuntimeCollectionValue<T>(
    private val first: T,
    private val second: T,
) : Collection<T> {
    override val size: Int
        get() = 2

    override fun isEmpty(): Boolean = false

    override fun contains(element: T): Boolean = first == element || second == element

    override fun containsAll(elements: Collection<T>): Boolean {
        for (element in elements) {
            if (!contains(element)) return false
        }
        return true
    }

    override fun iterator(): Iterator<T> = PairIterator(first, second)
}

public class RuntimeSetValue<T>(
    private val first: T,
    private val second: T,
) : Set<T> {
    override val size: Int
        get() = 2

    override fun isEmpty(): Boolean = false

    override fun contains(element: T): Boolean = first == element || second == element

    override fun containsAll(elements: Collection<T>): Boolean {
        for (element in elements) {
            if (!contains(element)) return false
        }
        return true
    }

    override fun iterator(): Iterator<T> = PairIterator(first, second)
}

public fun stringCollection(first: String, second: String): Collection<String> =
    RuntimeCollectionValue(first, second)

public fun intCollection(first: Int, second: Int): Collection<Int> =
    RuntimeCollectionValue(first, second)

public fun widenCollection(value: Collection<Int>): Collection<Any?> = value

public fun stringSet(first: String, second: String): Set<String> = RuntimeSetValue(first, second)

public fun intSet(first: Int, second: Int): Set<Int> = RuntimeSetValue(first, second)

public fun widenSet(value: Set<Int>): Set<Any?> = value

// MODULE: main(middle)
// FILE: main.kt

package generic.owner.runtime.collectionset

fun box(): String {
    val strings = stringCollection("alpha", "beta")
    if (!exactCollectionContains(strings, "beta")) return "exact collection input"
    if (exactCollectionIterator(strings).next() != "alpha") return "exact collection iterator"

    val ints = intCollection(11, 13)
    val wideInts = widenCollection(ints)
    if (!sameCollection(wideInts, ints)) return "collection identity"
    if (!widenedCollectionContains(wideInts, 13)) return "collection widened match"
    if (widenedCollectionContains(wideInts, "13")) return "collection widened mismatch"
    if (!widenedCollectionContainsAll(wideInts, widenCollection(intCollection(11, 13)))) {
        return "collection containsAll match"
    }
    if (widenedCollectionContainsAll(wideInts, stringCollection("11", "13"))) {
        return "collection containsAll mismatch"
    }

    val stringSet = stringSet("left", "right")
    if (!exactSetContains(stringSet, "right")) return "exact set input"
    if (exactSetIterator(stringSet).next() != "left") return "exact set iterator"

    val intsSet = intSet(17, 19)
    val wideSet = widenSet(intsSet)
    if (!sameSet(wideSet, intsSet)) return "set identity"
    if (!widenedSetContains(wideSet, 19)) return "set widened match"
    if (widenedSetContains(wideSet, "19")) return "set widened mismatch"
    if (!widenedSetContainsAll(wideSet, widenCollection(intCollection(17, 19)))) {
        return "set containsAll match"
    }
    if (widenedSetContainsAll(wideSet, stringCollection("17", "19"))) {
        return "set containsAll mismatch"
    }
    return "OK"
}
