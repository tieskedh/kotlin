package test

import kotlin.io.println

fun nestedArithmetic(a: Double, b: Double, c: Double): Double =
    a + b * c - (a - b) / c

fun divRem(a: Double, b: Double): Double = a / b + a % b

fun negate(x: Double): Double = -x + (+x)

fun incrementDecrement(start: Double): Double {
    var counter = start
    counter++
    counter++
    counter--
    return counter
}

// Comparisons as if-conditions. The final NaN fallthrough is reachable exactly because the
// float64 comparison scheme keeps every ordered comparison false for unordered operands.
fun compare(a: Double, b: Double): String {
    if (a < b) return "less"
    if (a > b) return "greater"
    if (a == b) return "equal"
    return "unordered"
}

fun describe(x: Double): String = when {
    x <= -1.0 -> "negative"
    x >= 1.0 -> "positive"
    else -> "small"
}

fun main() {
    println(nestedArithmetic(2.0, 3.0, 4.0))
    println(divRem(-7.5, 2.0))
    println(negate(5.0))
    println(incrementDecrement(10.0))
    println(compare(1.0, 2.0))
    println(compare(3.0, 3.0))
    println(compare(5.0, 4.0))
    println(describe(-5.0))
    println(describe(0.5))
    println(describe(7.0))

    // Comparisons stored into Boolean vals: locks the ieee754equals routing and the value-producing
    // (non-branch) form of the NaN-correct comparison scheme.
    val nan = 0.0 / 0.0
    val less = nan < 1.0
    val lessOrEqual = nan <= 1.0
    val greater = nan > 1.0
    val greaterOrEqual = nan >= 1.0
    val equal = nan == nan
    println(less)
    println(lessOrEqual)
    println(greater)
    println(greaterOrEqual)
    println(equal)
    println(compare(nan, 1.0))
    if (nan == nan) println("NaN == NaN") else println("NaN != NaN")
    val negativeZeroEqualsZero = -0.0 == 0.0
    println(negativeZeroEqualsZero)
}
