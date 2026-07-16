private var sourceEvaluations = 0
private var elementOrder = 0

private fun source(): IntArray {
    sourceEvaluations = sourceEvaluations + 1
    return intArrayOf(1, 2, 3, 9, 10)
}

private fun marked(value: Int): Int {
    elementOrder = elementOrder * 10 + value
    return value
}

private class Holder(
    val ints: IntArray,
    var chars: CharArray?,
)

private class Crate<T>(val value: T)

private fun loopSum(): Int {
    var sum = 0
    for (value in source()) {
        if (value == 2) continue
        if (value == 9) break
        sum = sum + value
    }
    return sum
}

private fun nestedLabelled(): Int {
    var result = 0
    outer@ for (left in intArrayOf(1, 2, 3)) {
        for (right in intArrayOf(4, 5)) {
            if (left == 2 && right == 4) break@outer
            result = result + left * right
        }
    }
    return result
}

private fun primitiveLoopTotals(): String {
    var longTotal = 0L
    for (value in longArrayOf(2L, 3L)) longTotal = longTotal + value

    var doubleTotal = 0.0
    for (value in doubleArrayOf(1.25, 2.75)) doubleTotal = doubleTotal + value

    var trueCount = 0
    for (value in booleanArrayOf(true, false, true)) {
        if (value) trueCount = trueCount + 1
    }

    var charTotal = 0
    for (value in charArrayOf('A', 'B')) charTotal = charTotal + value.code

    return "$longTotal:$doubleTotal:$trueCount:$charTotal"
}

private fun protectedArrayExpressions(): Int {
    val values = intArrayOf(
        try {
            4
        } catch (e: Exception) {
            -1
        },
    )
    values[
        try {
            0
        } catch (e: Exception) {
            -1
        }
    ] = try {
        5
    } catch (e: Exception) {
        -1
    }
    return values[
        try {
            0
        } catch (e: Exception) {
            -1
        }
    ]
}

private fun negativeLengthCatch(): String = try {
    IntArray(-1)
    "not-thrown"
} catch (e: ArithmeticException) {
    "wrong-arithmetic"
} catch (e: IllegalArgumentException) {
    "wrong-argument"
} catch (e: IllegalStateException) {
    "wrong-state"
} catch (e: Exception) {
    if (e.message == null) "caught" else "wrong-message:${e.message}"
}

fun box(): String {
    val empty = IntArray(0)
    if (empty.size != 0) return "fail: empty size"
    if (intArrayOf().size != 0) return "fail: literal empty size"

    val ints = IntArray(3)
    val longs = LongArray(2)
    val doubles = DoubleArray(2)
    val booleans = BooleanArray(2)
    val chars = CharArray(2)
    if (ints[0] != 0 || longs[0] != 0L || doubles[0] != 0.0 || booleans[0] || chars[0] != '\u0000') {
        return "fail: defaults"
    }

    ints[1] = 37
    longs[1] = 5000000000L
    doubles[1] = 1.5
    booleans[1] = true
    chars[1] = '\u20AC'
    if (ints[1] != 37) return "fail: int store"
    if (longs[1] != 5000000000L) return "fail: long store"
    if (doubles[1] != 1.5) return "fail: double store"
    if (!booleans[1]) return "fail: boolean store"
    if (chars[1] != '\u20AC') return "fail: char store"

    val literals = intArrayOf(marked(1), marked(2), marked(3))
    if (elementOrder != 123 || literals[2] != 3) return "fail: literal order"
    if (longArrayOf(4L, -5L)[1] != -5L) return "fail: long literal"
    if (doubleArrayOf(1.5, -0.0)[0] != 1.5) return "fail: double literal"
    if (!booleanArrayOf(false, true)[1]) return "fail: boolean literal"
    if (charArrayOf('A', '\u20AC')[1] != '\u20AC') return "fail: char literal"

    val holder = Holder(ints, chars)
    if (holder.ints !== ints || holder.chars !== chars) return "fail: fields"
    holder.chars = null
    if (holder.chars !== null) return "fail: nullable field"

    val nullable: IntArray? = ints
    if (nullable!! !== ints) return "fail: not-null"
    val any: Any = ints
    if (any !== ints) return "fail: Any widening"
    val alias = ints
    if (alias != ints || ints == IntArray(3)) return "fail: array equality"
    if (Crate(ints).value !== ints) return "fail: generic storage"

    if (loopSum() != 4 || sourceEvaluations != 1) return "fail: loop/evaluation"
    if (nestedLabelled() != 9) return "fail: nested labelled loop"
    if (primitiveLoopTotals() != "5:4.0:2:131") return "fail: primitive loops"
    if (protectedArrayExpressions() != 5) return "fail: protected expressions"

    val bounds = try {
        ints[ints.size]
        "not-thrown"
    } catch (e: IndexOutOfBoundsException) {
        "caught"
    }
    if (bounds != "caught") return "fail: bounds $bounds"
    if (negativeLengthCatch() != "caught") return "fail: negative ${negativeLengthCatch()}"

    return "OK"
}
