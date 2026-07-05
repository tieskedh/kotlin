package test

import kotlin.io.println

fun next(c: Char): Char = c + 1

fun previous(c: Char): Char = c - 1

fun distance(a: Char, b: Char): Int = a - b

fun incrementDecrement(start: Char): Char {
    var c = start
    c++
    c++
    c--
    return c
}

fun isDigit(c: Char): Boolean = c >= '0' && c <= '9'

fun compare(a: Char, b: Char): String {
    if (a < b) return "less"
    if (a > b) return "greater"
    return "equal"
}

fun main() {
    println(next('A'))
    println(previous('Z'))
    println(distance('z', 'a'))
    println(distance('a', 'z'))
    println(incrementDecrement('a'))
    println(isDigit('7'))
    println(isDigit('x'))
    println(compare('a', 'b'))
    println(compare('b', 'b'))
    println(compare('c', 'b'))
    val c = 'K'
    println(c)
    if (c == 'K') println("eq OK") else println("eq FAIL")
    // Code-unit wraparound: U+FFFF + 1 must be truncated back to a 16-bit code unit.
    // (`Char.code` is a stdlib extension property and does not resolve against the fake test
    // stdlib, so the distance from '\u0000' is used to surface the code unit as an Int.)
    println(('￿' + 1) - '\u0000')
    // Non-ASCII char constant (Cyrillic A, U+0410).
    println('А')
}
