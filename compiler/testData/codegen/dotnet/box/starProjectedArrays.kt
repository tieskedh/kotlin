@file:Suppress("UNCHECKED_CAST")

private class StarHolder(val values: Array<*>)

private fun starSize(values: Array<*>): Int = values.size

private fun starFirst(values: Array<*>): Any? = values[0]

private fun starIdentity(values: Array<*>): Array<*> = values

private fun starLoop(values: Array<*>): String {
    var result = ""
    for (value in values) result += value ?: "null"
    return result
}

private fun isStar(value: Any?): Boolean = value is Array<*>

private fun isNullableStar(value: Any?): Boolean = value is Array<*>?

private fun starSmartcast(value: Any?): Any? =
    if (value is Array<*>) value[0] else "not-array"

private var starEvaluations: Int = 0

private fun countedStar(value: Any?): Any? {
    starEvaluations++
    return value
}

fun box(): String {
    val strings = arrayOf("a", "b")
    val ints = arrayOf(40, 2)
    val nullableInts = arrayOf<Int?>(1, null, 3)
    val nested = arrayOf(ints, arrayOf(7))
    val empty = emptyArray<String>()

    if (starSize(strings) != 2 || starFirst(strings) != "a") return "fail 1: references"
    if (starSize(ints) != 2 || starFirst(ints) != 40) return "fail 2: value vector"
    if (starSize(nullableInts) != 3 || starFirst(nullableInts) != 1) {
        return "fail 3: nullable-value vector"
    }
    val nullableStar: Array<*> = nullableInts
    if (nullableStar[1] != null) return "fail 4: erased nullable read"
    if (starSize(empty) != 0) return "fail 5: empty vector"

    val holder = StarHolder(ints)
    if (holder.values !== ints || starIdentity(holder.values) !== ints) return "fail 6: identity"
    ints[0] = 41
    if (holder.values[0] != 41) return "fail 7: mutation alias"

    if (starLoop(arrayOf<Any?>("x", null, 3)) != "xnull3") return "fail 8: indexed loop"
    val iterator: Iterator<Any?> = nullableInts.iterator()
    if (iterator.next() != 1 || iterator.next() != null || iterator.next() != 3 || iterator.hasNext()) {
        return "fail 9: explicit iterator"
    }
    val iterable: Iterable<Any?> = ints.asIterable()
    val iterableIterator = iterable.iterator()
    if (iterableIterator.next() != 41 || iterableIterator.next() != 2 || iterableIterator.hasNext()) {
        return "fail 10: asIterable"
    }

    val nestedStar: Array<*> = nested
    if (nestedStar[0] !== ints || (nestedStar[1] as Array<Int>)[0] != 7) {
        return "fail 11: nested vectors"
    }

    val stringsAsAny: Any = strings
    val intsAsAny: Any = ints
    val nullableIntsAsAny: Any = nullableInts
    val primitiveAsAny: Any = intArrayOf(1, 2)
    if (!isStar(stringsAsAny) || !isStar(intsAsAny) || !isStar(nullableIntsAsAny)) {
        return "fail 12: RTTI positive matrix"
    }
    if (isStar(primitiveAsAny) || isStar("not-array") || isStar(null)) {
        return "fail 13: RTTI negative matrix"
    }
    if (!isNullableStar(null) || !isNullableStar(intsAsAny) || isNullableStar(primitiveAsAny)) {
        return "fail 14: nullable RTTI"
    }
    if (starSmartcast(intsAsAny) != 41 || starSmartcast(primitiveAsAny) != "not-array") {
        return "fail 15: smartcast use"
    }

    val checked = intsAsAny as Array<*>
    if (checked !== ints || (checked as Array<Int>)[1] != 2) return "fail 16: checked cast"
    if ((stringsAsAny as? Array<*>) !== strings) return "fail 17: safe cast success"
    if (primitiveAsAny as? Array<*> != null || "not-array" as? Array<*> != null) {
        return "fail 18: safe cast failure"
    }
    var checkedFailure = false
    try {
        val impossible = primitiveAsAny as Array<*>
        if (impossible.size >= 0) return "fail 19: checked cast unexpectedly succeeded"
    } catch (_: ClassCastException) {
        checkedFailure = true
    }
    if (!checkedFailure) return "fail 19: checked cast failure"

    starEvaluations = 0
    if (countedStar(intsAsAny) !is Array<*> || starEvaluations != 1) {
        return "fail 20: positive single evaluation"
    }
    starEvaluations = 0
    if (countedStar(primitiveAsAny) is Array<*> || starEvaluations != 1) {
        return "fail 21: negative single evaluation"
    }
    starEvaluations = 0
    if (countedStar(null) !is Array<*>? || starEvaluations != 1) {
        return "fail 22: nullable single evaluation"
    }

    return "OK"
}
