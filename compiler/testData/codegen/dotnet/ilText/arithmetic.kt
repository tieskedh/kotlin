package test

import kotlin.io.println

fun nestedArithmetic(a: Int, b: Int, c: Int): Int =
    a + b * c - (a - b) / c

fun divRem(a: Int, b: Int): Int =
    a / b * 1000 + a % b

fun negate(x: Int): Int = -x + (+x)

fun incrementDecrement(start: Int): Int {
    var counter = start
    counter++
    counter++
    counter--
    return counter
}

fun compare(a: Int, b: Int): String {
    if (a < b) return "less"
    if (a > b) return "greater"
    return "equal"
}

fun describe(x: Int): String = when {
    x <= -1 -> "negative"
    x >= 1 -> "positive"
    else -> "zero"
}

fun main() {
    // 2 + 3 * 4 - (2 - 3) / 4 = 2 + 12 - 0 = 14 (-1 / 4 truncates to 0)
    if (nestedArithmetic(2, 3, 4) == 14) println("arithmetic OK") else println("arithmetic FAIL")
    // -7 / 2 = -3 and -7 % 2 = -1 (IL div/rem truncate toward zero like Kotlin)
    if (divRem(-7, 2) == -3001) println("divRem OK") else println("divRem FAIL")
    if (negate(5) == 0) println("negate OK") else println("negate FAIL")
    if (incrementDecrement(10) == 11) println("incDec OK") else println("incDec FAIL")
    println(compare(1, 2))
    println(compare(3, 3))
    println(compare(5, 4))
    println(describe(-5))
    println(describe(0))
    println(describe(7))
    val sum = 6 * 7
    if (sum == 42) println("6 * 7 = " + "42") else println("6 * 7 = " + "?")
}
