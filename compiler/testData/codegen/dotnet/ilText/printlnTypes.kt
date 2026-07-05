package test

import kotlin.io.println

fun sum(a: Int, b: Int): Int = a + b

fun main() {
    println(42)
    println(-1)
    val n = 40
    println(n + 2)
    println(sum(19, 23))
    println(true)
    println(false)
    val flag = n > 10
    println(flag)
    println(n < 10)
    println(!flag)
    println(null)
}
