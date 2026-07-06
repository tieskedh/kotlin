fun intToLong(x: Int): Long = x.toLong()

fun intToDouble(x: Int): Double = x.toDouble()

fun intToChar(x: Int): Char = x.toChar()

fun longToInt(x: Long): Int = x.toInt()

fun longToDouble(x: Long): Double = x.toDouble()

fun doubleToInt(x: Double): Int = x.toInt()

fun doubleToLong(x: Double): Long = x.toLong()

fun charToLong(c: Char): Long = c.toLong()

fun charToDouble(c: Char): Double = c.toDouble()

fun box(): String {
    // Exact widenings.
    if (intToLong(42) != 42L) return "fail 1: got " + intToLong(42)
    if (intToLong(-2147483647 - 1) != -2147483648L) return "fail 2: got " + intToLong(-2147483647 - 1)
    if (intToDouble(7) != 7.0) return "fail 3: got " + intToDouble(7)
    if (intToDouble(2147483647) != 2147483647.0) return "fail 4: got " + intToDouble(2147483647)
    // Long -> Int keeps the low 32 bits (JVM l2i): 2^32 + 5 -> 5, -(2^32) + 5 -> 5.
    if (longToInt(4294967301L) != 5) return "fail 5: got " + longToInt(4294967301L)
    if (longToInt(-4294967291L) != 5) return "fail 6: got " + longToInt(-4294967291L)
    if (longToInt(-1L) != -1) return "fail 7: got " + longToInt(-1L)
    // 2^62 is exactly representable as a Double.
    if (longToDouble(4611686018427387904L) != 4611686018427387904.0) return "fail 8: got " + longToDouble(4611686018427387904L)
    // Double -> integer truncates toward zero.
    if (doubleToInt(12.9) != 12) return "fail 9: got " + doubleToInt(12.9)
    if (doubleToInt(-12.9) != -12) return "fail 10: got " + doubleToInt(-12.9)
    if (doubleToLong(-12.9) != -12L) return "fail 11: got " + doubleToLong(-12.9)
    // Saturation (JVM d2i/d2l semantics): NaN -> 0, out-of-range clamps to MAX/MIN.
    // Routed through vals so the runtime conversion sequence is emitted.
    val nan = 0.0 / 0.0
    val big = 1e300
    val small = -1e300
    if (nan.toInt() != 0) return "fail 12: got " + nan.toInt()
    if (nan.toLong() != 0L) return "fail 13: got " + nan.toLong()
    if (big.toInt() != 2147483647) return "fail 14: got " + big.toInt()
    if (small.toInt() != -2147483647 - 1) return "fail 15: got " + small.toInt()
    if (big.toLong() != 9223372036854775807L) return "fail 16: got " + big.toLong()
    if (small.toLong() != -9223372036854775807L - 1L) return "fail 17: got " + small.toLong()
    // Int -> Char keeps the low 16 bits (conv.u2); Char.code reads the code unit back.
    if (intToChar(66) != 'B') return "fail 18: got " + intToChar(66)
    if (intToChar(66).code != 66) return "fail 19: got " + intToChar(66).code
    if (intToChar(-1).code != 65535) return "fail 20: got " + intToChar(-1).code
    if (intToChar(65536 + 65).code != 65) return "fail 21: got " + intToChar(65536 + 65).code
    // Char widenings go through the code unit value.
    if (charToLong('B') != 66L) return "fail 22: got " + charToLong('B')
    if (charToDouble('B') != 66.0) return "fail 23: got " + charToDouble('B')
    return "OK"
}
