private class MapAggregateKey(val value: Int, val id: String) : Comparable<MapAggregateKey> {
    override fun compareTo(other: MapAggregateKey): Int = value.compareTo(other.value)
}

private class MapAggregateFailure : RuntimeException()

fun box(): String {
    val values = linkedMapOf("first" to 2, "second" to 1, "third" to 2)

    if (values.minBy { it.value }.key != "second") return "fail 1a: minBy"
    if (values.maxBy { it.value }.key != "first") return "fail 1b: maxBy first tie"
    if (values.minByOrNull { -it.value }?.key != "first") return "fail 1c: minByOrNull"
    if (values.maxByOrNull { -it.value }?.key != "second") return "fail 1d: maxByOrNull"

    if (values.minOf { it.value } != 1 || values.maxOf { it.value } != 2) return "fail 2a: generic result"
    if (values.minOfOrNull { it.value } != 1 || values.maxOfOrNull { it.value } != 2) {
        return "fail 2b: generic nullable result"
    }
    if (values.minOf { it.value.toFloat() } != 1.0f || values.maxOf { it.value.toFloat() } != 2.0f) {
        return "fail 2c: Float result"
    }
    if (values.minOfOrNull { it.value.toFloat() } != 1.0f || values.maxOfOrNull { it.value.toFloat() } != 2.0f) {
        return "fail 2d: nullable Float result"
    }
    if (values.minOf { it.value.toDouble() } != 1.0 || values.maxOf { it.value.toDouble() } != 2.0) {
        return "fail 2e: Double result"
    }
    if (values.minOfOrNull { it.value.toDouble() } != 1.0 || values.maxOfOrNull { it.value.toDouble() } != 2.0) {
        return "fail 2f: nullable Double result"
    }

    val entryComparator = Comparator<Map.Entry<String, Int>> { a, b -> a.value.compareTo(b.value) }
    if (values.minWith(entryComparator).key != "second") return "fail 3a: minWith"
    if (values.maxWith(entryComparator).key != "first") return "fail 3b: maxWith first tie"
    if (values.minWithOrNull(entryComparator)?.key != "second") return "fail 3c: minWithOrNull"
    if (values.maxWithOrNull(entryComparator)?.key != "first") return "fail 3d: maxWithOrNull"

    val intComparator = Comparator<Int> { a, b -> a.compareTo(b) }
    if (values.minOfWith(intComparator) { -it.value } != -2) return "fail 4a: minOfWith"
    if (values.maxOfWith(intComparator) { -it.value } != -1) return "fail 4b: maxOfWith"
    if (values.minOfWithOrNull(intComparator) { -it.value } != -2) return "fail 4c: minOfWithOrNull"
    if (values.maxOfWithOrNull(intComparator) { -it.value } != -1) return "fail 4d: maxOfWithOrNull"

    var selectorCalls = 0
    val empty = emptyMap<String, Int>()
    try {
        empty.minBy { selectorCalls++; it.value }
        return "fail 5a: missing minBy failure"
    } catch (_: NoSuchElementException) {
    }
    if (empty.maxByOrNull { selectorCalls++; it.value } != null) return "fail 5b: maxByOrNull empty"
    try {
        empty.minOf { selectorCalls++; it.value }
        return "fail 5c: missing minOf failure"
    } catch (_: NoSuchElementException) {
    }
    if (empty.maxOfOrNull { selectorCalls++; it.value } != null) return "fail 5d: maxOfOrNull empty"
    try {
        empty.minWith(entryComparator)
        return "fail 5e: missing minWith failure"
    } catch (_: NoSuchElementException) {
    }
    if (empty.maxWithOrNull(entryComparator) != null) return "fail 5f: maxWithOrNull empty"
    try {
        empty.minOfWith(intComparator) { selectorCalls++; it.value }
        return "fail 5g: missing minOfWith failure"
    } catch (_: NoSuchElementException) {
    }
    if (empty.maxOfWithOrNull(intComparator) { selectorCalls++; it.value } != null) {
        return "fail 5h: maxOfWithOrNull empty"
    }
    if (selectorCalls != 0) return "fail 5i: empty selector evaluation"

    val singleton = mapOf("only" to 7)
    if (singleton.minBy { selectorCalls++; it.value }.key != "only") return "fail 6a: singleton minBy"
    if (singleton.maxByOrNull { selectorCalls++; it.value }?.key != "only") return "fail 6b: singleton maxByOrNull"
    if (selectorCalls != 0) return "fail 6c: singleton element-selector elision"
    if (singleton.minOf { selectorCalls++; it.value } != 7) return "fail 6d: singleton minOf"
    if (singleton.maxOfWithOrNull(intComparator) { selectorCalls++; it.value } != 7) {
        return "fail 6e: singleton maxOfWithOrNull"
    }
    if (selectorCalls != 2) return "fail 6f: singleton result-selector count"

    val firstKey = MapAggregateKey(1, "first")
    val secondKey = MapAggregateKey(1, "second")
    if (values.minOf { if (it.key == "first") firstKey else secondKey } !== firstKey) {
        return "fail 7a: first generic result identity"
    }
    if (values.maxOfOrNull { if (it.key == "first") firstKey else secondKey } !== firstKey) {
        return "fail 7b: first nullable result identity"
    }

    val nullableComparator = Comparator<String?> { a, b ->
        when {
            a === b -> 0
            a == null -> -1
            b == null -> 1
            else -> a.compareTo(b)
        }
    }
    if (values.minOfWithOrNull(nullableComparator) { if (it.key == "first") null else it.key } != null) {
        return "fail 7c: nullable comparator result"
    }

    val failure = MapAggregateFailure()
    var comparatorCalls = 0
    val failingComparator = Comparator<Map.Entry<String, Int>> { a, b ->
        comparatorCalls++
        if (comparatorCalls == 2) throw failure
        a.value.compareTo(b.value)
    }
    try {
        values.minWith(failingComparator)
        return "fail 8a: missing comparator failure"
    } catch (caught: MapAggregateFailure) {
        if (caught !== failure) return "fail 8b: comparator failure identity"
    }
    if (comparatorCalls != 2) return "fail 8c: comparator failure stopping"

    var failingSelectorCalls = 0
    var comparisonCalls = 0
    val countingComparator = Comparator<Int> { a, b -> comparisonCalls++; a.compareTo(b) }
    try {
        values.maxOfWith(countingComparator) {
            failingSelectorCalls++
            if (it.key == "second") throw failure
            it.value
        }
        return "fail 8d: missing selector failure"
    } catch (caught: MapAggregateFailure) {
        if (caught !== failure) return "fail 8e: selector failure identity"
    }
    if (failingSelectorCalls != 2 || comparisonCalls != 0) return "fail 8f: selector failure stopping"

    if (!values.maxOf { if (it.key == "second") Float.NaN else 1.0f }.isNaN()) return "fail 9a: Float NaN"
    if (!values.minOfOrNull { if (it.key == "second") Double.NaN else 1.0 }!!.isNaN()) return "fail 9b: Double NaN"
    if (1.0 / values.minOf { if (it.key == "first") 0.0 else -0.0 } != Double.NEGATIVE_INFINITY) {
        return "fail 9c: Double min zero"
    }
    if (1.0f / values.maxOfOrNull { if (it.key == "first") -0.0f else 0.0f }!! != Float.POSITIVE_INFINITY) {
        return "fail 9d: Float max zero"
    }

    return "OK"
}
