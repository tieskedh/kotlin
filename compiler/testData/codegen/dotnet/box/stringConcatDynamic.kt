// Executing twin of the stringConcatDynamic.kt / stringConcatenation.kt / doubleToString.kt
// ilText goldens: concatenation of runtime (non-constant) Int/Long/Double/Char/Boolean/String
// values, string templates, and explicit toString(), with the exact strings Kotlin/JVM produces
// (including the Kotlin-parity Double rendering: "-0.0", "NaN", "Infinity", E-notation).

fun describe(count: Int, enabled: Boolean): String = "count: " + count + ", enabled: " + enabled

fun concatInt(prefix: String, value: Int): String = prefix + value

fun concatLong(value: Long): String = "l = " + value

fun concatDouble(value: Double): String = "d = " + value

fun concatChar(value: Char): String = "c = " + value

fun stringPlus(s: String): String = "O" + s

fun box(): String {
    val x = 12
    val ready = x > 10

    // Runtime Int.
    if (concatInt("v=", x + 30) != "v=42") return "fail 1: got " + concatInt("v=", x + 30)
    if (concatInt("neg=", 0 - x) != "neg=-12") return "fail 2: got " + concatInt("neg=", 0 - x)
    if (describe(x - 10, x == 12) != "count: 2, enabled: true") return "fail 3: got " + describe(x - 10, x == 12)

    // Runtime Long.
    val l = 40L
    if (concatLong(l + 2L) != "l = 42") return "fail 4: got " + concatLong(l + 2L)
    if (concatLong(0L - l) != "l = -40") return "fail 5: got " + concatLong(0L - l)

    // Runtime Double: Kotlin rendering keeps the trailing ".0" and the sign of -0.0.
    val d = 2.5
    if (concatDouble(d) != "d = 2.5") return "fail 6: got " + concatDouble(d)
    if (concatDouble(d * 2.0) != "d = 5.0") return "fail 7: got " + concatDouble(d * 2.0)
    val zero = 0.0
    if (concatDouble(-zero) != "d = -0.0") return "fail 8: got " + concatDouble(-zero)
    if (concatDouble(zero / zero) != "d = NaN") return "fail 9: got " + concatDouble(zero / zero)
    if (concatDouble(1.0 / zero) != "d = Infinity") return "fail 10: got " + concatDouble(1.0 / zero)
    if (concatDouble(-1.0 / zero) != "d = -Infinity") return "fail 11: got " + concatDouble(-1.0 / zero)
    // Value in the JVM-scientific/.NET-decimal notation gap: must render Kotlin-style.
    if ("v = " + 1.2345678E7 != "v = 1.2345678E7") return "fail 12: got " + ("v = " + 1.2345678E7)

    // Runtime Char.
    val c = 'K'
    if (concatChar(c) != "c = K") return "fail 13: got " + concatChar(c)
    if (concatChar(c + 1) != "c = L") return "fail 14: got " + concatChar(c + 1)

    // Boolean concatenation.
    if ("" + (1 < 2) != "true") return "fail 15: got " + ("" + (1 < 2))
    if ("ready=" + ready != "ready=true") return "fail 16: got " + ("ready=" + ready)
    if ("" + !ready != "false") return "fail 17: got " + ("" + !ready)

    // Runtime String operand.
    if (stringPlus("K") != "OK") return "fail 18: got " + stringPlus("K")

    // Mixed chain with a Char in the middle: left-associative String.plus at every step.
    val chained = "v=" + (x + 30) + '/' + ready
    if (chained != "v=42/true") return "fail 19: got " + chained

    // String template desugars to the same concatenation chain.
    val message = "x=$x and ready=$ready"
    if (message != "x=12 and ready=true") return "fail 20: got " + message

    // Explicit toString().
    if (x.toString() != "12") return "fail 21: got " + x.toString()
    if (ready.toString() != "true") return "fail 22: got " + ready.toString()
    if (d.toString() != "2.5") return "fail 23: got " + d.toString()

    return "OK"
}
