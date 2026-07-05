package test

import kotlin.io.println

fun intPlusLong(a: Int, b: Long): Long = a + b

fun longPlusInt(a: Long, b: Int): Long = a + b

fun intPlusDouble(a: Int, b: Double): Double = a + b

fun doublePlusInt(a: Double, b: Int): Double = a + b

fun longTimesDouble(a: Long, b: Double): Double = a * b

fun doubleMinusLong(a: Double, b: Long): Double = a - b

fun intDivLong(a: Int, b: Long): Long = a / b

fun longRemInt(a: Long, b: Int): Long = a % b

fun doubleDivInt(a: Double, b: Int): Double = a / b

fun intRemDouble(a: Int, b: Double): Double = a % b

fun main() {
    println(intPlusLong(1, 2L))
    println(longPlusInt(6000000000L, 1))
    println(intPlusDouble(1, 2.5))
    println(doublePlusInt(2.5, 1))
    println(longTimesDouble(4L, 2.5))
    println(doubleMinusLong(0.5, 2L))
    println(intDivLong(-7, 2L))
    println(longRemInt(-7L, 2))
    println(doubleDivInt(-7.0, 2))
    println(intRemDouble(-7, 2.5))
    val mixed = 1 + 2L + 0.5
    println(mixed)
}
