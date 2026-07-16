private var trace: String = ""

private data class Item(val value: Int)

private fun leftExpression(): IntArray {
    trace = trace + "left;"
    return intArrayOf(1, 2)
}

private fun rightExpression(): IntArray {
    trace = trace + "right;"
    return intArrayOf(1, 2)
}

private fun <T> genericContentEquals(left: Array<T>?, right: Array<T>?): Boolean =
    left contentEquals right

fun box(): String {
    val nullInts: IntArray? = null
    if (!(nullInts contentEquals null)) return "fail 1: null/null"
    if (nullInts contentEquals intArrayOf()) return "fail 2: null/array"
    if (intArrayOf() contentEquals nullInts) return "fail 3: array/null"

    if (!(intArrayOf() contentEquals intArrayOf())) return "fail 4: empty"
    if (!(intArrayOf(1, 2) contentEquals intArrayOf(1, 2))) return "fail 5: Int equal"
    if (intArrayOf(1, 2) contentEquals intArrayOf(1, 3)) return "fail 6: Int element"
    if (intArrayOf(1) contentEquals intArrayOf(1, 0)) return "fail 7: Int length"
    if (!(longArrayOf(1L, 2L) contentEquals longArrayOf(1L, 2L))) return "fail 8: Long"
    if (!(booleanArrayOf(true, false) contentEquals booleanArrayOf(true, false))) return "fail 9: Boolean"
    if (!(charArrayOf('A', 'z') contentEquals charArrayOf('A', 'z'))) return "fail 10: Char"

    val nan = 0.0 / 0.0
    if (!(doubleArrayOf(nan) contentEquals doubleArrayOf(nan))) return "fail 11: NaN"
    if (doubleArrayOf(-0.0) contentEquals doubleArrayOf(0.0)) return "fail 12: signed zero"
    if (!(doubleArrayOf(1.5, -2.0) contentEquals doubleArrayOf(1.5, -2.0))) return "fail 13: Double"

    val nullableStrings = arrayOf<String?>("a", null)
    if (!(nullableStrings contentEquals arrayOf<String?>("a", null))) return "fail 14: nullable refs"
    if (nullableStrings contentEquals arrayOf<String?>("a", "x")) return "fail 15: ref element"
    if (!(arrayOf(Item(1)) contentEquals arrayOf(Item(1)))) return "fail 16: Kotlin equals"
    if (arrayOf(Item(1)) contentEquals arrayOf(Item(2))) return "fail 17: Kotlin inequality"

    val inner = intArrayOf(4)
    if (!(arrayOf<Any>(inner) contentEquals arrayOf<Any>(inner))) return "fail 18: nested identity"
    if (arrayOf<Any>(intArrayOf(4)) contentEquals arrayOf<Any>(intArrayOf(4))) {
        return "fail 19: shallow nested comparison"
    }

    val strings: Array<String> = arrayOf("left", "right")
    val anys: Array<Any> = arrayOf("left", "right")
    if (!(strings contentEquals anys)) return "fail 20: projected arrays"
    if (!genericContentEquals(arrayOf("x", "y"), arrayOf("x", "y"))) return "fail 21: open generic"
    val nullStrings: Array<String>? = null
    if (!genericContentEquals(nullStrings, nullStrings)) return "fail 22: open generic null"

    trace = ""
    if (!(leftExpression() contentEquals rightExpression())) return "fail 23: evaluated result"
    if (trace != "left;right;") return "fail 24: evaluation $trace"

    val mutableLeft = intArrayOf(1, 2)
    val mutableRight = intArrayOf(1, 2)
    mutableRight[1] = 3
    if (mutableLeft contentEquals mutableRight) return "fail 25: mutation"
    return "OK"
}
