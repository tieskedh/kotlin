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

fun box(): String {
    if (intPlusLong(1, 2L) != 3L) return "fail 1: got " + intPlusLong(1, 2L)
    // Result exceeds Int range, so a wrong 32-bit promotion cannot fake it.
    if (longPlusInt(6000000000L, 1) != 6000000001L) return "fail 2: got " + longPlusInt(6000000000L, 1)
    if (intPlusLong(2147483647, 1L) != 2147483648L) return "fail 3: got " + intPlusLong(2147483647, 1L)
    if (intPlusDouble(1, 2.5) != 3.5) return "fail 4: got " + intPlusDouble(1, 2.5)
    if (doublePlusInt(2.5, 1) != 3.5) return "fail 5: got " + doublePlusInt(2.5, 1)
    if (longTimesDouble(4L, 2.5) != 10.0) return "fail 6: got " + longTimesDouble(4L, 2.5)
    if (doubleMinusLong(0.5, 2L) != -1.5) return "fail 7: got " + doubleMinusLong(0.5, 2L)
    // Integer division after promotion still truncates toward zero.
    if (intDivLong(-7, 2L) != -3L) return "fail 8: got " + intDivLong(-7, 2L)
    if (longRemInt(-7L, 2) != -1L) return "fail 9: got " + longRemInt(-7L, 2)
    // Division with a Double operand produces an exact Double result.
    if (doubleDivInt(-7.0, 2) != -3.5) return "fail 10: got " + doubleDivInt(-7.0, 2)
    if (doubleDivInt(7.0, 2) != 3.5) return "fail 11: got " + doubleDivInt(7.0, 2)
    // Kotlin Double rem is truncated (fmod): -7.0 % 2.5 == -7.0 - (-2 * 2.5) == -2.0.
    if (intRemDouble(-7, 2.5) != -2.0) return "fail 12: got " + intRemDouble(-7, 2.5)
    // Int promoted through Long, then the Long result promoted to Double.
    val mixed = 1 + 2L + 0.5
    if (mixed != 3.5) return "fail 13: got " + mixed
    // Comparisons on promoted results.
    val sum = intPlusLong(2147483647, 1L)
    if (sum < 2147483648L) return "fail 14: got " + sum
    if (sum > 2147483648L) return "fail 15: got " + sum
    val d = longTimesDouble(3L, 0.5)
    if (d <= 1.0) return "fail 16: got " + d
    if (d >= 2.0) return "fail 17: got " + d
    return "OK"
}
