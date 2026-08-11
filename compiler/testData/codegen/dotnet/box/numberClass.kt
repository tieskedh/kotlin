// MODULE: lib
// FILE: lib.kt

package numbers

class TaggedNumber(private val value: Int) : Number() {
    override fun toByte(): Byte = value.toByte()
    override fun toShort(): Short = value.toShort()
    override fun toInt(): Int = value
    override fun toLong(): Long = value.toLong()
    override fun toFloat(): Float = value.toFloat()
    override fun toDouble(): Double = value.toDouble()
}

@Suppress("DEPRECATION_ERROR", "OVERRIDE_DEPRECATION")
class CharacterNumber(private val value: Int) : Number() {
    override fun toByte(): Byte = value.toByte()
    override fun toShort(): Short = value.toShort()
    override fun toInt(): Int = value
    override fun toLong(): Long = value.toLong()
    override fun toFloat(): Float = value.toFloat()
    override fun toDouble(): Double = value.toDouble()
    override fun toChar(): Char = 'Z'
}

@Suppress("DEPRECATION_ERROR", "OVERRIDE_DEPRECATION")
class SuperCharacterNumber(private val value: Int) : Number() {
    override fun toByte(): Byte = value.toByte()
    override fun toShort(): Short = value.toShort()
    override fun toInt(): Int = value
    override fun toLong(): Long = value.toLong()
    override fun toFloat(): Float = value.toFloat()
    override fun toDouble(): Double = value.toDouble()
    override fun toChar(): Char = super.toChar() + 1
}

fun convert(number: Number): String =
    "${number.toByte()}:${number.toShort()}:${number.toInt()}:${number.toLong()}:${number.toFloat()}:${number.toDouble()}"

fun <T : Number> genericToLong(number: T): Long = number.toLong()

fun retain(number: Number): Number = number

fun isNumber(value: Any?): Boolean = value is Number

fun safeNumber(value: Any?): Number? = value as? Number

fun checkedNumber(value: Any?): Number = value as Number

@Suppress("DEPRECATION_ERROR")
fun deprecatedCharacter(number: Number): Char = number.toChar()

// MODULE: main(lib)
// FILE: main.kt

import numbers.*

private fun fail(message: String): String = "fail: $message"

private class NotNumber

fun box(): String {
    val builtIns: Array<Number> = arrayOf(
        1.toByte(),
        2.toShort(),
        3,
        4L,
        5.5f,
        6.5,
    )
    val expected = arrayOf(
        "1:1:1:1:1.0:1.0",
        "2:2:2:2:2.0:2.0",
        "3:3:3:3:3.0:3.0",
        "4:4:4:4:4.0:4.0",
        "5:5:5:5:5.5:5.5",
        "6:6:6:6:6.5:6.5",
    )
    for (index in builtIns.indices) {
        if (convert(builtIns[index]) != expected[index]) return fail("built-in conversion $index")
    }

    val tagged = TaggedNumber(23)
    val widened: Number = tagged
    if (tagged.toInt() != 23 || tagged.toDouble() != 23.0) return fail("direct custom conversions")
    if (convert(widened) != "23:23:23:23:23.0:23.0") return fail("custom virtual conversions")
    if (genericToLong(tagged) != 23L || genericToLong(42) != 42L) return fail("generic Number bound")
    if (retain(widened) !== tagged) return fail("custom identity")

    val boxed: Any = 41
    val checkedBuiltIn = checkedNumber(boxed)
    if ((checkedBuiltIn as Any) !== boxed || checkedBuiltIn.toInt() != 41) return fail("built-in checked cast identity")
    if (checkedNumber(tagged) !== tagged) return fail("custom checked cast identity")
    if (safeNumber(boxed)?.toInt() != 41 || safeNumber(tagged) !== tagged) return fail("safe cast success")

    val rejected: Array<Any?> = arrayOf(null, true, '7', "7", NotNumber())
    for (value in rejected) {
        if (isNumber(value)) return fail("false Number admission: $value")
        if (safeNumber(value) != null) return fail("false safe cast: $value")
    }
    try {
        checkedNumber("not a number")
        return fail("invalid checked cast succeeded")
    } catch (_: ClassCastException) {
    }
    try {
        checkedNumber(null)
        return fail("null checked cast succeeded")
    } catch (_: NullPointerException) {
    }

    val numberValues: Array<Any> = arrayOf(1.toByte(), 2.toShort(), 3, 4L, 5.0f, 6.0, tagged)
    for (value in numberValues) {
        if (!isNumber(value)) return fail("Number type test: $value")
        if (!Number::class.isInstance(value)) return fail("Number KClass classifier: $value")
    }
    for (value in rejected) {
        if (Number::class.isInstance(value)) return fail("false Number KClass admission: $value")
    }
    if (null is Number || null !is Number?) return fail("nullable Number type tests")

    if (Float.NaN.let(::retain).toInt() != 0) return fail("Float NaN toInt")
    if (Double.NaN.let(::retain).toLong() != 0L) return fail("Double NaN toLong")
    if (Float.POSITIVE_INFINITY.let(::retain).toInt() != Int.MAX_VALUE) return fail("Float positive saturation")
    if (Double.NEGATIVE_INFINITY.let(::retain).toLong() != Long.MIN_VALUE) return fail("Double negative saturation")

    @Suppress("DEPRECATION_ERROR")
    if (deprecatedCharacter(TaggedNumber(65)) != 'A') return fail("inherited Number.toChar")
    @Suppress("DEPRECATION_ERROR")
    if (deprecatedCharacter(CharacterNumber(65)) != 'Z') return fail("overridden Number.toChar")
    @Suppress("DEPRECATION_ERROR")
    if (CharacterNumber(65).toChar() != 'Z') return fail("direct overridden toChar")
    @Suppress("DEPRECATION_ERROR")
    if (deprecatedCharacter(SuperCharacterNumber(65)) != 'B') return fail("Number.toChar super call")

    return "OK"
}
