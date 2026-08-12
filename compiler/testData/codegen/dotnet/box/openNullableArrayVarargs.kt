private var evaluationTrace: String = ""
private var previousVararg: Any? = null

private fun <T> marked(label: String, value: T?): T? {
    evaluationTrace += label
    return value
}

private fun markedIntSpread(label: String, value: Array<Int?>): Array<Int?> {
    evaluationTrace += label
    return value
}

private fun <T> nullableSnapshot(vararg values: T?): List<T?> = values.asList()

private fun <T> receivesFreshVararg(vararg values: T?): Boolean {
    val current: Any = values
    val isFresh = current !== previousVararg
    previousVararg = current
    return isFresh
}

fun box(): String {
    val nullableInts = arrayOf<Int?>(1, null, 2, null, 3)
    if (nullableInts.filterNotNull() != listOf(1, 2, 3)) {
        return "fail 1: nullable value array ${nullableInts.filterNotNull()}"
    }
    val nullableStrings = arrayOf<String?>("a", null, "b")
    if (nullableStrings.filterNotNull() != listOf("a", "b")) {
        return "fail 2: nullable reference array ${nullableStrings.filterNotNull()}"
    }

    val widened: Array<out Any?> = nullableInts
    if (widened.filterNotNull() != listOf(1, 2, 3)) {
        return "fail 3: widened projected array ${widened.filterNotNull()}"
    }
    val destination = arrayListOf<Number>(0)
    val returned = nullableInts.filterNotNullTo(destination)
    if (returned !== destination || destination != listOf<Number>(0, 1, 2, 3)) {
        return "fail 4: destination identity $destination"
    }

    val intSet = setOfNotNull(1, null, 2, 1, null)
    if (intSet.toString() != "[1, 2]") return "fail 5: value vararg $intSet"
    val stringSet = setOfNotNull("a", null, "b", "a")
    if (stringSet.toString() != "[a, b]") return "fail 6: reference vararg $stringSet"
    if (setOfNotNull(*emptyArray<String?>()).isNotEmpty()) return "fail 7: empty spread"

    evaluationTrace = ""
    val snapshot = nullableSnapshot<Int>(
        marked<Int>("a", 1),
        *markedIntSpread("b", arrayOf(null, 2)),
        marked<Int>("c", 3),
    )
    if (evaluationTrace != "abc" || snapshot != listOf(1, null, 2, 3)) {
        return "fail 8: evaluation $evaluationTrace $snapshot"
    }

    previousVararg = null
    if (!receivesFreshVararg<String>() || !receivesFreshVararg<String>()) {
        return "fail 9: omitted varargs were reused"
    }
    val source = arrayOf<String?>("x", null)
    nullableSnapshot(*source)
    if (source[0] != "x" || source[1] != null) return "fail 10: spread source mutation"

    return "OK"
}
