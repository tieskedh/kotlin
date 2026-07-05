package test

import kotlin.io.println

fun divide(a: Long, b: Long): Long = a / b

fun remainder(a: Long, b: Long): Long = a % b

fun divideByConstMinusOne(a: Long): Long = a / -1L

fun remainderByConstMinusOne(a: Long): Long = a % -1L

fun divideByConstTwo(a: Long): Long = a / 2L

fun main() {
    val min = -9223372036854775807L - 1L
    println(divide(min, -1L))
    println(remainder(min, -1L))
    println(divideByConstMinusOne(min))
    println(remainderByConstMinusOne(min))
    println(divideByConstTwo(-7L))
    println(divide(-7L, 2L))
    println(remainder(-7L, 2L))
    println(divide(7L, -1L))
    println(remainder(7L, -1L))
}
