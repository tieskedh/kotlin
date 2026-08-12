// MODULE: lib
// FILE: lib.kt

package comparison.boundary

public class Entry(
    public val priority: Int,
    public val name: String,
)

public fun entryComparator(): Comparator<Entry> =
    compareBy<Entry> { it.priority }.thenBy { it.name }

public fun selectFirst(left: Entry, right: Entry, comparator: Comparator<in Entry>): Entry =
    minOf(left, right, comparator)

public inline fun ordered(
    entries: Iterable<Entry>,
    comparator: Comparator<in Entry>,
): Boolean = entries.isSortedWith(comparator)

// MODULE: main(lib)
// FILE: main.kt

import comparison.boundary.*

fun box(): String {
    val low = Entry(1, "z")
    val high = Entry(2, "a")
    val comparator = entryComparator()
    if (selectFirst(high, low, comparator) !== low) return "fail 1: producer comparator"
    if (!ordered(listOf(low, high), comparator)) return "fail 2: inline consumer"

    val local = Comparator<Entry> { left, right -> left.name.compareTo(right.name) }
    if (selectFirst(low, high, local) !== high) return "fail 3: consumer SAM"

    val anyComparator = Comparator<Any> { left, right -> left.toString().compareTo(right.toString()) }
    val widened: Comparator<in Entry> = anyComparator
    selectFirst(low, high, widened)

    return "OK"
}
