package test

import kotlin.io.println

fun divide(a: Int, b: Int): Int = a / b

fun remainder(a: Int, b: Int): Int = a % b

fun divideByConstMinusOne(a: Int): Int = a / -1

fun remainderByConstMinusOne(a: Int): Int = a % -1

fun divideByConstTwo(a: Int): Int = a / 2

fun main() {
    val min = -2147483647 - 1
    println(divide(min, -1))
    println(remainder(min, -1))
    println(divideByConstMinusOne(min))
    println(remainderByConstMinusOne(min))
    println(divideByConstTwo(-7))
    println(divide(-7, 2))
    println(remainder(-7, 2))
    println(divide(7, -1))
    println(remainder(7, -1))
}
