package test

import kotlin.io.println

fun nestedArithmetic(a: Long, b: Long, c: Long): Long =
    a + b * c - (a - b) / c

fun divRem(a: Long, b: Long): Long =
    a / b * 1000L + a % b

fun negate(x: Long): Long = -x + (+x)

fun incrementDecrement(start: Long): Long {
    var counter = start
    counter++
    counter++
    counter--
    return counter
}

fun compare(a: Long, b: Long): String {
    if (a < b) return "less"
    if (a > b) return "greater"
    return "equal"
}

fun describe(x: Long): String = when {
    x <= -1L -> "negative"
    x >= 1L -> "positive"
    else -> "zero"
}

fun main() {
    // 2 + 3 * 4 - (2 - 3) / 4 = 2 + 12 - 0 = 14 (-1 / 4 truncates to 0)
    if (nestedArithmetic(2L, 3L, 4L) == 14L) println("arithmetic OK") else println("arithmetic FAIL")
    // -7 / 2 = -3 and -7 % 2 = -1 (IL div/rem truncate toward zero like Kotlin)
    if (divRem(-7L, 2L) == -3001L) println("divRem OK") else println("divRem FAIL")
    if (negate(5L) == 0L) println("negate OK") else println("negate FAIL")
    if (incrementDecrement(10L) == 11L) println("incDec OK") else println("incDec FAIL")
    println(compare(1L, 2L))
    println(compare(3L, 3L))
    println(compare(5L, 4L))
    println(describe(-5L))
    println(describe(0L))
    println(describe(7L))
    // Larger than any int32 so a wrong 32-bit emission cannot fake the result.
    val sum = 6000000000L * 7L
    println(sum)
}
