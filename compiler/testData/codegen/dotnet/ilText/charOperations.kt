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
    println(('\uFFFF' + 1).code)
    // Non-ASCII char constant (Cyrillic A, U+0410).
    println('\u0410')

    // Escaped char literals: NUL as a comparison operand, newline below the space code unit.
    println(distance('a', '\u0000'))
    val newline = '\n'
    println(newline < ' ')

    // Int.toChar / Char.code round-trip (Int.toChar is `conv.u2`; Char.code reinterprets the
    // int32-shaped stack value with no instruction).
    println('A'.code)
    println(66.toChar())
    println(('z'.code + 1).toChar())
    println(97.toChar().code)

    // Concatenation: constant chars (a quote and a newline) fold into the IL string literal;
    // a Char variable goes through the runtime Char::ToString(char) path.
    println("q=" + '\'' + "end")
    println("nl" + '\n' + "done")
    println("c = " + c)
    println("mix=" + '"' + newline + "end")
}
