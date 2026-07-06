// Executing twin of the charOperations.kt ilText golden: Char.code, Char arithmetic,
// Int.toChar()/Char.code round-trips, code-unit wraparound, comparisons, equality and
// Char-in-string concatenation, verified against Kotlin/JVM semantics.

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

fun box(): String {
    if ('A'.code != 65) return "fail 1: got " + 'A'.code
    if ('a'.code != 97) return "fail 2: got " + 'a'.code
    if ('0'.code != 48) return "fail 3: got " + '0'.code

    if (next('A') != 'B') return "fail 4: got " + next('A')
    if (previous('Z') != 'Y') return "fail 5: got " + previous('Z')
    if (distance('z', 'a') != 25) return "fail 6: got " + distance('z', 'a')
    if (distance('a', 'z') != -25) return "fail 7: got " + distance('a', 'z')
    if (incrementDecrement('a') != 'b') return "fail 8: got " + incrementDecrement('a')

    // Int.toChar / Char.code round-trips.
    if (66.toChar() != 'B') return "fail 9: got " + 66.toChar()
    if (('z'.code + 1).toChar() != '{') return "fail 10: got " + ('z'.code + 1).toChar()
    if (97.toChar().code != 97) return "fail 11: got " + 97.toChar().code

    // Code-unit wraparound: U+FFFF + 1 truncates back to a 16-bit code unit (NUL).
    if (('\uFFFF' + 1).code != 0) return "fail 12: got " + ('\uFFFF' + 1).code

    // Comparisons and equality.
    if (!isDigit('7')) return "fail 13"
    if (isDigit('x')) return "fail 14"
    if (compare('a', 'b') != "less") return "fail 15: got " + compare('a', 'b')
    if (compare('b', 'b') != "equal") return "fail 16: got " + compare('b', 'b')
    if (compare('c', 'b') != "greater") return "fail 17: got " + compare('c', 'b')
    val c = 'K'
    if (c != 'K') return "fail 18: got " + c
    val newline = '\n'
    if (!(newline < ' ')) return "fail 19"
    if (distance('a', '\u0000') != 97) return "fail 20: got " + distance('a', '\u0000')

    // Char in string concatenation: constant and runtime values render identically.
    if ("" + 'K' != "K") return "fail 21: got " + ("" + 'K')
    if ("c = " + c != "c = K") return "fail 22: got " + ("c = " + c)
    if ("q=" + '\'' + "end" != "q='end") return "fail 23: got " + ("q=" + '\'' + "end")
    if ("" + next(c) != "L") return "fail 24: got " + ("" + next(c))

    return "OK"
}
