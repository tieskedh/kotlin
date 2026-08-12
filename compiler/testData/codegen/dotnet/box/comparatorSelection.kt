private class Item(
    val key: Int,
    val name: String,
)

fun box(): String {
    val ascending = Comparator<Item> { left, right -> left.key.compareTo(right.key) }
    val first = Item(1, "first")
    val tied = Item(1, "tied")
    val last = Item(3, "last")

    if (ascending.compare(first, last) >= 0) return "fail 1: direct SAM"
    if (minOf(first, tied, ascending) !== first) return "fail 2: min tie"
    if (maxOf(first, tied, ascending) !== first) return "fail 3: max tie"
    if (minOf(last, tied, first, last, comparator = ascending) !== tied) {
        return "fail 4: vararg min"
    }
    if (maxOf(first, tied, last, first, comparator = ascending) !== last) {
        return "fail 5: vararg max"
    }

    val widenedSource = Comparator<Any> { left, right -> left.toString().compareTo(right.toString()) }
    val widened: Comparator<in String> = widenedSource
    if (widened.compare("a", "b") >= 0) return "fail 6: use-site variance"

    var selectorCalls = 0
    val chained = compareBy<Item> {
        selectorCalls++
        it.key
    }.thenByDescending {
        selectorCalls++
        it.name
    }
    if (chained.compare(Item(1, "a"), Item(1, "b")) <= 0) return "fail 7: chain"
    if (selectorCalls != 4) return "fail 8: chain calls $selectorCalls"

    selectorCalls = 0
    val selectedComparison = compareValuesBy(
        Item(1, "a"),
        Item(2, "b"),
        {
            selectorCalls++
            0
        },
        {
            selectorCalls++
            it.key
        },
        {
            selectorCalls++
            it.name
        },
    )
    if (selectedComparison >= 0) return "fail 9: compareValuesBy"
    if (selectorCalls != 4) return "fail 10: selector short circuit $selectorCalls"

    val nullableAscending = nullsFirst(naturalOrder<String>())
    if (nullableAscending.compare(null, "a") >= 0) return "fail 11: nullsFirst"
    if (nullsLast(naturalOrder<String>()).compare(null, "a") <= 0) return "fail 12: nullsLast"
    if (naturalOrder<Double>().compare(Double.NaN, 1.0) <= 0) return "fail 13: NaN total order"
    if (naturalOrder<Double>().compare(-0.0, 0.0) >= 0) return "fail 14: signed zero total order"
    if (reverseOrder<String>().compare("a", "b") <= 0) return "fail 15: reverse order"

    val values = listOf(last, tied, first)
    if (values.minWith(ascending) !== tied) return "fail 16: minWith first tie"
    if (values.maxWith(ascending) !== last) return "fail 17: maxWith"
    if (emptyList<Item>().minWithOrNull(ascending) != null) return "fail 18: minWithOrNull"
    if (emptyList<Item>().maxWithOrNull(ascending) != null) return "fail 19: maxWithOrNull"
    try {
        emptyList<Item>().minWith(ascending)
        return "fail 20: empty minWith"
    } catch (_: NoSuchElementException) {
    }

    selectorCalls = 0
    val selectedMin = values.minOfWith(naturalOrder<Int>()) {
        selectorCalls++
        it.key
    }
    if (selectedMin != 1 || selectorCalls != 3) return "fail 21: minOfWith $selectedMin/$selectorCalls"
    selectorCalls = 0
    val selectedMax = values.maxOfWithOrNull(naturalOrder<Int>()) {
        selectorCalls++
        it.key
    }
    if (selectedMax != 3 || selectorCalls != 3) return "fail 22: maxOfWithOrNull $selectedMax/$selectorCalls"

    var comparisons = 0
    val counting = Comparator<Int> { left, right ->
        comparisons++
        left.compareTo(right)
    }
    if (!listOf(1, 2, 2, 3).isSortedWith(counting)) return "fail 23: sorted"
    if (comparisons != 3) return "fail 24: sorted comparisons $comparisons"
    comparisons = 0
    if (listOf(3, 2, 100).isSortedWith(counting)) return "fail 25: unsorted"
    if (comparisons != 1) return "fail 26: sorted short circuit $comparisons"
    if (!listOf(1, 2, 3).isSorted()) return "fail 27: natural sorted"
    if (!listOf(3, 2, 1).isSortedDescending()) return "fail 28: descending sorted"
    if (!values.isSortedByDescending { it.key }) return "fail 29: sortedByDescending"

    val failure = IllegalStateException("comparator failure")
    val throwing = Comparator<Int> { _, _ -> throw failure }
    try {
        listOf(1, 2, 3).isSortedWith(throwing)
        return "fail 30: comparator failure returned"
    } catch (caught: IllegalStateException) {
        if (caught !== failure) return "fail 31: comparator failure identity"
    }

    return "OK"
}
