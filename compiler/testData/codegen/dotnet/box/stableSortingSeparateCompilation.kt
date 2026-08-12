// MODULE: lib
// FILE: lib.kt

package sorting.boundary

public data class Entry(
    public val priority: Int,
    public val name: String,
)

public fun sortedEntries(entries: Iterable<Entry>): List<Entry> =
    entries.sortedWith(compareBy<Entry> { it.priority }.thenBy { it.name })

public fun sortEntries(entries: MutableList<Entry>, comparator: Comparator<in Entry>) {
    entries.sortWith(comparator)
}

public fun sortedArray(entries: Array<Entry>): Array<Entry> {
    entries.sortWith(compareBy { it.priority })
    return entries
}

public inline fun sortedNames(entries: Iterable<Entry>): List<String> =
    entries.sortedBy { it.priority }.map { it.name }

// MODULE: main(lib)
// FILE: main.kt

import sorting.boundary.*

fun box(): String {
    val lowB = Entry(1, "b")
    val high = Entry(2, "a")
    val lowA = Entry(1, "a")

    val producerSnapshot = sortedEntries(listOf(high, lowB, lowA))
    if (producerSnapshot != listOf(lowA, lowB, high)) return "fail 1: producer snapshot $producerSnapshot"

    val consumerComparator = Comparator<Entry> { left, right -> right.priority.compareTo(left.priority) }
    val mutable = mutableListOf(lowB, high, lowA)
    sortEntries(mutable, consumerComparator)
    if (mutable != listOf(high, lowB, lowA)) return "fail 2: consumer comparator $mutable"

    val array = sortedArray(arrayOf(high, lowB, lowA))
    if (array.asList() != listOf(lowB, lowA, high)) return "fail 3: producer array stability"

    if (sortedNames(listOf(high, lowB, lowA)) != listOf("b", "a", "a")) {
        return "fail 4: cross-library inline"
    }

    val anyComparator = Comparator<Any> { left, right -> left.toString().compareTo(right.toString()) }
    val widened: Comparator<in Entry> = anyComparator
    sortEntries(mutable, widened)

    return "OK"
}
