// Executable twin of the doubleToString.kt ilText golden: the '<KotlinIl>'::DoubleToString
// runtime helper must render Kotlin/JVM Double.toString shapes on real CoreCLR, which natively
// renders -0.0 as "-0", NaN as "NaN" but infinities as the unicode symbol, and uses a different
// decimal/E-notation switch than Kotlin's [1e-3, 1e7) window.

fun render(d: Double): String = "" + d

fun box(): String {
    val zero = 0.0
    val nan = zero / zero
    val positiveInfinity = 1.0 / zero
    val negativeInfinity = -1.0 / zero

    if (render(nan) != "NaN") return "fail 1: got " + nan
    if (render(positiveInfinity) != "Infinity") return "fail 2: got " + positiveInfinity
    if (render(negativeInfinity) != "-Infinity") return "fail 3: got " + negativeInfinity

    // The signature CoreCLR divergence: raw .NET formatting renders -0.0 as "-0".
    val negativeZero = -zero
    if (render(negativeZero) != "-0.0") return "fail 4: got " + negativeZero
    if (render(zero) != "0.0") return "fail 5: got " + zero

    val one = 1.0
    if (render(one) != "1.0") return "fail 6: got " + one

    // Shortest-roundtrip rendering.
    val tenth = 0.1
    if (render(tenth) != "0.1") return "fail 7: got " + tenth

    // Kotlin's decimal-notation window is [1e-3, 1e7); both boundaries and both sides.
    val tenMillion = 1.0E7
    if (render(tenMillion) != "1.0E7") return "fail 8: got " + tenMillion
    val belowTenMillion = 1234567.0
    if (render(belowTenMillion) != "1234567.0") return "fail 9: got " + belowTenMillion
    val thousandth = 1.0E-3
    if (render(thousandth) != "0.001") return "fail 10: got " + thousandth
    val belowThousandth = 1.0E-4
    if (render(belowThousandth) != "1.0E-4") return "fail 11: got " + belowThousandth

    // Multi-digit mantissa above the 1e7 boundary (the case the ilText golden calls out).
    val mantissa = 1.2345678E7
    if (render(mantissa) != "1.2345678E7") return "fail 12: got " + mantissa
    val smallDecimal = 1.5E-3
    if (render(smallDecimal) != "0.0015") return "fail 13: got " + smallDecimal
    val big = 1.0E20
    if (render(big) != "1.0E20") return "fail 14: got " + big

    // Explicit toString() must route through the same helper as concatenation.
    val two = 2.0
    if (two.toString() != "2.0") return "fail 15: got " + two.toString()

    return "OK"
}
