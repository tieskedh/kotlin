private var deepTrace: String = ""

private data class DeepItem(val value: Int)

private fun deepLeftExpression(): Array<Any?> {
    deepTrace = deepTrace + "left;"
    return arrayOf<Any?>(intArrayOf(1, 2), "x")
}

private fun deepRightExpression(): Array<Any?> {
    deepTrace = deepTrace + "right;"
    return arrayOf<Any?>(intArrayOf(1, 2), "x")
}

private fun <T> genericDeepEquals(left: Array<T>?, right: Array<T>?): Boolean =
    left contentDeepEquals right

fun box(): String {
    val nullArray: Array<Any?>? = null
    if (!(nullArray contentDeepEquals null)) return "fail 1: null/null"
    if (nullArray contentDeepEquals arrayOf<Any?>()) return "fail 2: null/array"
    if (arrayOf<Any?>() contentDeepEquals nullArray) return "fail 3: array/null"

    val nestedLeft = arrayOf<Any?>("a", null, intArrayOf(2), arrayOf<Any?>("inner", DeepItem(3)))
    val nestedRight = arrayOf<Any?>("a", null, intArrayOf(2), arrayOf<Any?>("inner", DeepItem(3)))
    if (nestedLeft contentEquals nestedRight) return "fail 4: shallow identity"
    if (!(nestedLeft contentDeepEquals nestedRight)) return "fail 5: nested equality"
    nestedRight[2] = intArrayOf(3)
    if (nestedLeft contentDeepEquals nestedRight) return "fail 6: nested mutation"

    val primitivesLeft = arrayOf<Any>(
        intArrayOf(1),
        longArrayOf(2L),
        doubleArrayOf(3.0),
        booleanArrayOf(true),
        charArrayOf('x'),
    )
    val primitivesRight = arrayOf<Any>(
        intArrayOf(1),
        longArrayOf(2L),
        doubleArrayOf(3.0),
        booleanArrayOf(true),
        charArrayOf('x'),
    )
    if (!(primitivesLeft contentDeepEquals primitivesRight)) return "fail 7: primitive families"
    primitivesRight[1] = intArrayOf(2)
    if (primitivesLeft contentDeepEquals primitivesRight) return "fail 8: primitive kind"

    val nan = 0.0 / 0.0
    if (!(arrayOf<Any>(doubleArrayOf(nan)) contentDeepEquals arrayOf<Any>(doubleArrayOf(nan)))) {
        return "fail 9: nested NaN"
    }
    if (arrayOf<Any>(doubleArrayOf(-0.0)) contentDeepEquals arrayOf<Any>(doubleArrayOf(0.0))) {
        return "fail 10: nested signed zero"
    }

    val referenceLeft = arrayOf<Any>(arrayOf("x", "y"))
    val referenceRight = arrayOf<Any>(arrayOf<Any>("x", "y"))
    if (!(referenceLeft contentDeepEquals referenceRight)) return "fail 11: reference vector kinds"
    if (!(arrayOf(DeepItem(4)) contentDeepEquals arrayOf(DeepItem(4)))) return "fail 12: scalar equals"
    if (arrayOf(DeepItem(4)) contentDeepEquals arrayOf(DeepItem(5))) return "fail 13: scalar inequality"

    val strings: Array<String> = arrayOf("left", "right")
    val anys: Array<Any> = arrayOf("left", "right")
    if (!(strings contentDeepEquals anys)) return "fail 14: projected arrays"
    if (!genericDeepEquals(arrayOf("x"), arrayOf("x"))) return "fail 15: open generic"
    val nullStrings: Array<String>? = null
    if (!genericDeepEquals(nullStrings, nullStrings)) return "fail 16: open generic null"

    val self = arrayOfNulls<Any>(1)
    self[0] = self
    if (!(self contentDeepEquals self)) return "fail 17: same cyclic identity"

    deepTrace = ""
    if (!(deepLeftExpression() contentDeepEquals deepRightExpression())) return "fail 18: evaluated result"
    if (deepTrace != "left;right;") return "fail 19: evaluation $deepTrace"
    return "OK"
}
