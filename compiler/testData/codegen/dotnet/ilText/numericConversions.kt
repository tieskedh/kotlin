package test

import kotlin.io.println

fun intToLong(x: Int): Long = x.toLong()

fun intToDouble(x: Int): Double = x.toDouble()

fun intToChar(x: Int): Char = x.toChar()

fun longToInt(x: Long): Int = x.toInt()

fun longToDouble(x: Long): Double = x.toDouble()

fun doubleToInt(x: Double): Int = x.toInt()

fun doubleToLong(x: Double): Long = x.toLong()

// The deprecated member `Char.toInt()` (a warning, not an error) is exercised deliberately as
// the second route to the Char -> Int conversion intrinsic; the primary route, the `Char.code`
// extension property (resolvable since it was added to the fake test stdlib), is covered by
// charOperations.kt.
@Suppress("DEPRECATION")
fun charToIntCode(c: Char): Int = c.toInt()

fun charToLong(c: Char): Long = c.toLong()

fun charToDouble(c: Char): Double = c.toDouble()

fun identity(x: Int): Int = x.toInt()

fun main() {
    println(intToLong(42))
    println(intToDouble(7))
    println(intToChar(66))
    // Wraps to the low 32 bits, like JVM l2i: 4294967296 + 5 -> 5.
    println(longToInt(4294967301L))
    println(longToDouble(4611686018427387904L))
    println(doubleToInt(12.9))
    println(doubleToInt(-12.9))
    println(doubleToLong(-12.9))
    println(charToIntCode('B'))
    println(charToLong('B'))
    println(charToDouble('B'))
    println(identity(-1))

    // Saturation cases (JVM d2i/d2l semantics): NaN -> 0, above MAX (incl. +Inf) -> MAX,
    // below MIN (incl. -Inf) -> MIN. Routed through vals so the runtime conversion sequence
    // is emitted instead of any potential compile-time folding.
    val nan = 0.0 / 0.0
    val big = 1e300
    val small = -1e300
    println(nan.toInt())
    println(nan.toLong())
    println(big.toInt())
    println(big.toLong())
    println(small.toInt())
    println(small.toLong())
    println(doubleToInt(nan))
    println(doubleToLong(big))
}
