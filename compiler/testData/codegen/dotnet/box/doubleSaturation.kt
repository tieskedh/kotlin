// Executable twin of the numericConversions.kt ilText golden saturation cases: Double -> Int/Long
// conversions must follow JVM d2i/d2l semantics (NaN -> 0, out-of-range and infinities saturate
// to MIN/MAX, fractions truncate toward zero) instead of raw CLR conv.i4/conv.i8 behavior.

fun box(): String {
    val zero = 0.0
    val nan = zero / zero
    val positiveInfinity = 1.0 / zero
    val negativeInfinity = -1.0 / zero
    val intMax = 2147483647
    val intMin = -2147483647 - 1
    val longMax = 9223372036854775807L
    val longMin = -9223372036854775807L - 1L

    if (nan.toInt() != 0) return "fail 1: got " + nan.toInt()
    if (nan.toLong() != 0L) return "fail 2: got " + nan.toLong()
    if (positiveInfinity.toInt() != intMax) return "fail 3: got " + positiveInfinity.toInt()
    if (negativeInfinity.toInt() != intMin) return "fail 4: got " + negativeInfinity.toInt()
    if (positiveInfinity.toLong() != longMax) return "fail 5: got " + positiveInfinity.toLong()
    if (negativeInfinity.toLong() != longMin) return "fail 6: got " + negativeInfinity.toLong()

    // Finite but far outside the Int range: saturates for toInt(), exact for toLong()
    // (1e18 is exactly representable as a Double and fits in Long).
    val big = 1e18
    val small = -1e18
    if (big.toInt() != intMax) return "fail 7: got " + big.toInt()
    if (small.toInt() != intMin) return "fail 8: got " + small.toInt()
    if (big.toLong() != 1000000000000000000L) return "fail 9: got " + big.toLong()
    if (small.toLong() != -1000000000000000000L) return "fail 10: got " + small.toLong()

    // In-range fractions truncate toward zero.
    val positiveFraction = 2.9
    val negativeFraction = -2.9
    if (positiveFraction.toInt() != 2) return "fail 11: got " + positiveFraction.toInt()
    if (negativeFraction.toInt() != -2) return "fail 12: got " + negativeFraction.toInt()

    return "OK"
}
