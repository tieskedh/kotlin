private var stringEvaluations: Int = 0

private class TextItem(private val value: Int) {
    override fun toString(): String = "item:$value"
}

private fun stringArrayExpression(): Array<Any?> {
    stringEvaluations = stringEvaluations + 1
    return arrayOf<Any?>(intArrayOf(1, 2), TextItem(3))
}

private fun <T> genericContentString(array: Array<T>?): String = array.contentToString()

private fun <T> genericDeepString(array: Array<T>?): String = array.contentDeepToString()

fun box(): String {
    val nullAny: Array<Any?>? = null
    val nullInts: IntArray? = null
    if (nullAny.contentToString() != "null") return "fail 1: generic null"
    if (nullInts.contentToString() != "null") return "fail 2: primitive null"
    if (nullAny.contentDeepToString() != "null") return "fail 3: deep null"
    if (arrayOf<Any?>().contentToString() != "[]") return "fail 4: generic empty"
    if (intArrayOf().contentToString() != "[]") return "fail 5: primitive empty"

    if (intArrayOf(1, 10, 42).contentToString() != "[1, 10, 42]") return "fail 6: Int"
    if (longArrayOf(1L, 5L).contentToString() != "[1, 5]") return "fail 7: Long"
    if (booleanArrayOf(true, false).contentToString() != "[true, false]") return "fail 8: Boolean"
    if (charArrayOf('a', 'z').contentToString() != "[a, z]") return "fail 9: Char"

    val nan = 0.0 / 0.0
    val doubles = doubleArrayOf(0.0, -0.0, 1.0 / 0.0, -1.0 / 0.0, nan)
    if (doubles.contentToString() != "[0.0, -0.0, Infinity, -Infinity, NaN]") {
        return "fail 10: Double ${doubles.contentToString()}"
    }
    if (arrayOf<Any?>("a", 1, null, TextItem(2)).contentToString() != "[a, 1, null, item:2]") {
        return "fail 11: scalar text"
    }

    val nestedIdentity = intArrayOf(1, 2)
    val shallowNested = arrayOf<Any>(nestedIdentity).contentToString()
    if (shallowNested != "[${nestedIdentity.toString()}]") return "fail 12: shallow nested $shallowNested"

    val deep = arrayOf<Any?>(
        "root",
        intArrayOf(1, 2),
        longArrayOf(3L),
        booleanArrayOf(true, false),
        charArrayOf('x', 'y'),
        doubleArrayOf(0.0, -0.0, nan),
        arrayOf<Any?>("inner", null, TextItem(4)),
    )
    val expectedDeep = "[root, [1, 2], [3], [true, false], [x, y], [0.0, -0.0, NaN], [inner, null, item:4]]"
    if (deep.contentDeepToString() != expectedDeep) return "fail 13: deep ${deep.contentDeepToString()}"

    val b = arrayOfNulls<Any>(2)
    val a = arrayOf<Any>(b)
    b[0] = a
    b[1] = b
    if (a.contentDeepToString() != "[[[...], [...]]]") return "fail 14: cycle ${a.contentDeepToString()}"
    if (a.contentToString() == "") return "fail 15: shallow cycle"

    val shared = arrayOf<Any>(intArrayOf(7))
    val repeated = arrayOf<Any>(shared, shared)
    if (repeated.contentDeepToString() != "[[[7]], [[7]]]") {
        return "fail 16: repeated ${repeated.contentDeepToString()}"
    }

    if (genericContentString(arrayOf("x")) != "[x]") return "fail 17: open shallow"
    if (genericDeepString(arrayOf<Any>(intArrayOf(1, 2))) != "[[1, 2]]") return "fail 18: open deep"
    if (genericContentString<String>(null) != "null") return "fail 19: open shallow null"
    if (genericDeepString<String>(null) != "null") return "fail 20: open deep null"

    stringEvaluations = 0
    if (stringArrayExpression().contentDeepToString() != "[[1, 2], item:3]") return "fail 21: evaluation result"
    if (stringEvaluations != 1) return "fail 22: evaluation count $stringEvaluations"
    return "OK"
}
