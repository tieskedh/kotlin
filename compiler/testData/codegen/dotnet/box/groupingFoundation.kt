private fun groupingNonLocalReturn(): String {
    listOf(1).groupingBy { it }.aggregate<Int, Int, Int> { _, _, _, _ -> return "OK" }
    return "fail: non-local return did not run"
}

fun box(): String {
    var selectorCalls = 0
    val iterableGrouping = listOf("a", "bb", "c").groupingBy {
        selectorCalls++
        it.length
    }
    if (selectorCalls != 0) return "fail 1: eager grouping"
    if (iterableGrouping.eachCount().toString() != "{1=2, 2=1}" || selectorCalls != 3) {
        return "fail 2: iterable/count"
    }
    if (iterableGrouping.eachCount().toString() != "{1=2, 2=1}" || selectorCalls != 6) {
        return "fail 3: repeated traversal"
    }
    if (emptyList<Int>().groupingBy { it }.eachCount().isNotEmpty()) return "fail 3a: empty"
    if (listOf<String?>(null).groupingBy { it }.eachCount().toString() != "{null=1}") {
        return "fail 3b: singleton nullable key"
    }

    val mutableSource = arrayListOf(1)
    val liveGrouping = mutableSource.groupingBy { it % 2 }
    mutableSource.add(3)
    if (liveGrouping.eachCount().toString() != "{1=2}") return "fail 3c: source mutation visibility"

    if (sequenceOf(1, 2, 3).groupingBy { it % 2 }.eachCount().toString() != "{1=2, 0=1}") {
        return "fail 4: sequence factory"
    }
    if (arrayOf("a", "bb", "cc").groupingBy { it.length }.eachCount().toString() != "{1=1, 2=2}") {
        return "fail 5: array factory"
    }
    if ("abca".groupingBy { it }.eachCount().toString() != "{a=2, b=1, c=1}") {
        return "fail 6: CharSequence factory"
    }

    val trace = arrayListOf<String>()
    val nullable = listOf<String?>(null, null, "x").groupingBy { if (it == null) 0 else 1 }.aggregate {
            key, accumulator: String?, element, first ->
        trace.add("$key:$first:${accumulator == null}")
        if (element == null) accumulator else (accumulator ?: "") + element
    }
    if (nullable.toString() != "{0=null, 1=x}") return "fail 7: nullable accumulator"
    if (trace.toString() != "[0:true:true, 0:false:true, 1:true:true]") return "fail 8: first flag"

    val folded = listOf("a", "bb", "c").groupingBy { it.length }.fold("") { acc, element -> acc + element }
    if (folded.toString() != "{1=ac, 2=bb}") return "fail 9: constant fold"
    val selected = listOf("a", "bb", "c").groupingBy { it.length }.fold(
        { key, _ -> "$key:" },
        { _, acc, element -> acc + element },
    )
    if (selected.toString() != "{1=1:ac, 2=2:bb}") return "fail 10: selector fold"

    val reduced = listOf("a", "bb", "c").groupingBy { it.length }.reduce { _, acc, element -> acc + element }
    if (reduced.toString() != "{1=ac, 2=bb}") return "fail 11: reduce"

    val destination = mutableMapOf(1 to 10)
    val counted = listOf("a", "b", "cc").groupingBy { it.length }.eachCountTo(destination)
    if (counted !== destination || destination.toString() != "{1=12, 2=1}") return "fail 12: destination"

    val covariant: Grouping<Int, Any?> = listOf(1, 2).groupingBy { "key" }
    if (covariant.eachCount().toString() != "{key=2}") return "fail 13: key covariance"

    var failedCalls = 0
    val failure = IllegalStateException("grouping failure")
    try {
        listOf(1, 2, 3).groupingBy {
            failedCalls++
            if (it == 2) throw failure
            it
        }.eachCount()
        return "fail 14: missing failure"
    } catch (caught: IllegalStateException) {
        if (caught !== failure || failedCalls != 2) return "fail 15: failure identity/timing"
    }

    var iteratorKeyCalls = 0
    val iteratorFailure = IllegalStateException("iterator failure")
    val hostile = object : Grouping<Int, Int> {
        override fun sourceIterator(): Iterator<Int> = object : Iterator<Int> {
            private var index = 0
            override fun hasNext(): Boolean = index < 2
            override fun next(): Int {
                if (index++ == 1) throw iteratorFailure
                return 7
            }
        }

        override fun keyOf(element: Int): Int {
            iteratorKeyCalls++
            return element
        }
    }
    try {
        hostile.eachCount()
        return "fail 16: missing iterator failure"
    } catch (caught: IllegalStateException) {
        if (caught !== iteratorFailure || iteratorKeyCalls != 1) return "fail 17: iterator failure timing"
    }

    if (groupingNonLocalReturn() != "OK") return "fail 18: non-local return"

    return "OK"
}
