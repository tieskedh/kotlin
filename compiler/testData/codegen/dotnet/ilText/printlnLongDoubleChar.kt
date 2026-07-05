package test

import kotlin.io.println

fun main() {
    // Long constants and values: Console.WriteLine(int64).
    println(42L)
    println(-9223372036854775807L - 1L)
    val l = 40L
    println(l + 2L)

    // Double: must NOT use the culture-sensitive Console.WriteLine(float64); constants and
    // dynamic values both go through the invariant-culture string rendering.
    println(1.5)
    println(-0.0)
    println(1e300)
    val d = 2.5
    println(d)
    println(d * 2.0)

    // Char constants and values: Console.WriteLine(char).
    println('A')
    val c = 'K'
    println(c)
    println(c + 1)

    // String concatenation renders the values through the same toString paths.
    println("l = " + l)
    println("d = " + d)
    println("c = " + c)
}
