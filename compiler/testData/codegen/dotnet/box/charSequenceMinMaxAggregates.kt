private class MinMaxAggregateCharSequence(private val value: String) : CharSequence {
    var lengthReads: Int = 0
    var getCalls: Int = 0

    override val length: Int
        get() {
            lengthReads++
            return value.length
        }

    override fun get(index: Int): Char {
        getCalls++
        return value[index]
    }

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
        value.subSequence(startIndex, endIndex)
}

private class CharSequenceAggregateKey(val value: Int, val id: String) : Comparable<CharSequenceAggregateKey> {
    override fun compareTo(other: CharSequenceAggregateKey): Int = value.compareTo(other.value)
}

private class CharSequenceAggregateFailure : RuntimeException()

fun box(): String {
    val text: CharSequence = "cab"
    if (text.min() != 'a' || text.max() != 'c') return "fail 1a: natural throwing"
    if (text.minOrNull() != 'a' || text.maxOrNull() != 'c') return "fail 1b: natural nullable"

    if (text.minBy { -it.code } != 'c' || text.maxBy { -it.code } != 'a') return "fail 2a: selector throwing"
    if (text.minByOrNull { it.code } != 'a' || text.maxByOrNull { it.code } != 'c') return "fail 2b: selector nullable"

    if (text.minOf { it.code } != 'a'.code || text.maxOf { it.code } != 'c'.code) return "fail 3a: generic result"
    if (text.minOfOrNull { it.code } != 'a'.code || text.maxOfOrNull { it.code } != 'c'.code) return "fail 3b: generic nullable result"
    if (text.minOf { it.code.toFloat() } != 'a'.code.toFloat()) return "fail 3c: Float min"
    if (text.maxOf { it.code.toFloat() } != 'c'.code.toFloat()) return "fail 3d: Float max"
    if (text.minOfOrNull { it.code.toFloat() } != 'a'.code.toFloat()) return "fail 3e: Float nullable min"
    if (text.maxOfOrNull { it.code.toFloat() } != 'c'.code.toFloat()) return "fail 3f: Float nullable max"
    if (text.minOf { it.code.toDouble() } != 'a'.code.toDouble()) return "fail 3g: Double min"
    if (text.maxOf { it.code.toDouble() } != 'c'.code.toDouble()) return "fail 3h: Double max"
    if (text.minOfOrNull { it.code.toDouble() } != 'a'.code.toDouble()) return "fail 3i: Double nullable min"
    if (text.maxOfOrNull { it.code.toDouble() } != 'c'.code.toDouble()) return "fail 3j: Double nullable max"

    val reverseCharComparator = Comparator<Char> { a, b -> b.compareTo(a) }
    if (text.minWith(reverseCharComparator) != 'c' || text.maxWith(reverseCharComparator) != 'a') return "fail 4a: comparator throwing"
    if (text.minWithOrNull(reverseCharComparator) != 'c' || text.maxWithOrNull(reverseCharComparator) != 'a') return "fail 4b: comparator nullable"
    val intComparator = Comparator<Int> { a, b -> a.compareTo(b) }
    if (text.minOfWith(intComparator) { -it.code } != -'c'.code) return "fail 4c: result comparator min"
    if (text.maxOfWith(intComparator) { -it.code } != -'a'.code) return "fail 4d: result comparator max"
    if (text.minOfWithOrNull(intComparator) { -it.code } != -'c'.code) return "fail 4e: result comparator nullable min"
    if (text.maxOfWithOrNull(intComparator) { -it.code } != -'a'.code) return "fail 4f: result comparator nullable max"

    var selectorCalls = 0
    val empty: CharSequence = ""
    try {
        empty.minBy { selectorCalls++; it.code }
        return "fail 5a: missing minBy failure"
    } catch (_: NoSuchElementException) {
    }
    if (empty.maxByOrNull { selectorCalls++; it.code } != null) return "fail 5b: maxByOrNull empty"
    try {
        empty.minOf { selectorCalls++; it.code }
        return "fail 5c: missing minOf failure"
    } catch (_: NoSuchElementException) {
    }
    if (empty.maxOfOrNull { selectorCalls++; it.code } != null) return "fail 5d: maxOfOrNull empty"
    if (empty.minOfWithOrNull(intComparator) { selectorCalls++; it.code } != null) return "fail 5e: minOfWithOrNull empty"
    if (selectorCalls != 0) return "fail 5f: empty selector evaluation"
    if (empty.minOrNull() != null || empty.maxWithOrNull(reverseCharComparator) != null) return "fail 5g: empty ordinary nullable"

    val singleton: CharSequence = "x"
    if (singleton.minBy { selectorCalls++; it.code } != 'x') return "fail 6a: singleton minBy"
    if (singleton.maxByOrNull { selectorCalls++; it.code } != 'x') return "fail 6b: singleton maxByOrNull"
    if (selectorCalls != 0) return "fail 6c: singleton selector elision"
    if (singleton.minOf { selectorCalls++; it.code } != 'x'.code) return "fail 6d: singleton minOf"
    if (singleton.maxOfWithOrNull(intComparator) { selectorCalls++; it.code } != 'x'.code) return "fail 6e: singleton maxOfWithOrNull"
    if (selectorCalls != 2) return "fail 6f: singleton result selector count"

    val firstKey = CharSequenceAggregateKey(1, "first")
    val secondKey = CharSequenceAggregateKey(1, "second")
    if (("ab" as CharSequence).minOf { if (it == 'a') firstKey else secondKey } !== firstKey) return "fail 7a: first result identity"
    if (("ab" as CharSequence).maxOfOrNull { if (it == 'a') firstKey else secondKey } !== firstKey) return "fail 7b: first nullable result identity"

    val nullableComparator = Comparator<String?> { a, b ->
        when {
            a === b -> 0
            a == null -> -1
            b == null -> 1
            else -> a.compareTo(b)
        }
    }
    if (("ab" as CharSequence).minOfWithOrNull(nullableComparator) { if (it == 'a') null else "b" } != null) {
        return "fail 7c: nullable comparator result"
    }

    val failure = CharSequenceAggregateFailure()
    var comparatorCalls = 0
    val failingComparator = Comparator<Char> { a, b ->
        comparatorCalls++
        if (comparatorCalls == 2) throw failure
        a.compareTo(b)
    }
    try {
        ("cba" as CharSequence).minWith(failingComparator)
        return "fail 8a: missing comparator failure"
    } catch (caught: CharSequenceAggregateFailure) {
        if (caught !== failure) return "fail 8b: comparator failure identity"
    }
    if (comparatorCalls != 2) return "fail 8c: comparator failure stopping"

    var failingSelectorCalls = 0
    var comparisonCalls = 0
    val countingComparator = Comparator<Int> { a, b -> comparisonCalls++; a.compareTo(b) }
    try {
        ("abc" as CharSequence).maxOfWith(countingComparator) {
            failingSelectorCalls++
            if (it == 'b') throw failure
            it.code
        }
        return "fail 8d: missing selector failure"
    } catch (caught: CharSequenceAggregateFailure) {
        if (caught !== failure) return "fail 8e: selector failure identity"
    }
    if (failingSelectorCalls != 2 || comparisonCalls != 0) return "fail 8f: selector failure stopping"

    if (!("ab" as CharSequence).maxOf { if (it == 'b') Float.NaN else 1.0f }.isNaN()) return "fail 9a: Float NaN"
    if (!("ab" as CharSequence).minOfOrNull { if (it == 'b') Double.NaN else 1.0 }!!.isNaN()) return "fail 9b: Double NaN"
    if (1.0 / ("ab" as CharSequence).minOf { if (it == 'a') 0.0 else -0.0 } != Double.NEGATIVE_INFINITY) return "fail 9c: Double min zero"
    if (1.0f / ("ab" as CharSequence).maxOfOrNull { if (it == 'a') -0.0f else 0.0f }!! != Float.POSITIVE_INFINITY) return "fail 9d: Float max zero"

    val custom = MinMaxAggregateCharSequence("cab")
    val customView: CharSequence = custom
    if (customView.min() != 'a' || customView.maxBy { it.code } != 'c') return "fail 10a: custom carrier"
    if (custom.getCalls != 6 || custom.lengthReads == 0) return "fail 10b: custom indexed dispatch"

    return "OK"
}
