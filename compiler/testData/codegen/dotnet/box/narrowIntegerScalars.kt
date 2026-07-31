// Kotlin Byte/Short remain distinct in signatures, generics, nullable values and boxing even
// though ECMA-335 evaluates int8/int16 values on its int32 stack. Arithmetic and conversions
// follow Common/JVM semantics; the physical carriers are System.SByte and System.Int16.

const val BYTE_CONST: Byte = -8
const val SHORT_CONST: Short = -300

class NarrowBox<T>(private var value: T) {
    fun get(): T = value
    fun put(next: T) { value = next }
}

interface NarrowSource<T> {
    fun value(): T
}

class ByteSource(private val stored: Byte) : NarrowSource<Byte> {
    override fun value(): Byte = stored
}

class NarrowState(var byteValue: Byte, var shortValue: Short)

fun <T> narrowIdentity(value: T): T = value

fun overload(value: Byte): String = "byte:" + value
fun overload(value: Short): String = "short:" + value
fun overload(value: Int): String = "int:" + value

fun byteInc(start: Byte): Byte {
    var value = start
    value++
    return value
}

fun shortDec(start: Short): Short {
    var value = start
    value--
    return value
}

fun nullableByte(value: Byte?): Byte = value ?: 11
fun nullableShort(value: Short?): Short = value ?: 12

fun box(): String {
    val minByte: Byte = -128
    val maxByte: Byte = 127
    val minShort: Short = -32768
    val maxShort: Short = 32767

    if (BYTE_CONST != (-8).toByte()) return "fail 1: byte const"
    if (SHORT_CONST != (-300).toShort()) return "fail 2: short const"
    if (overload((-7).toByte()) != "byte:-7") return "fail 3: byte overload"
    if (overload((-700).toShort()) != "short:-700") return "fail 4: short overload"
    if (overload(-700) != "int:-700") return "fail 5: int overload"

    if (minByte + maxByte != -1) return "fail 6: byte arithmetic"
    if (minShort + maxShort != -1) return "fail 7: short arithmetic"
    if (minByte * 2 != -256) return "fail 8: mixed arithmetic"
    if (-minByte != 128 || +minShort != -32768) return "fail 9: unary promotion"
    if (byteInc(maxByte) != minByte) return "fail 10: byte wrap"
    if (shortDec(minShort) != maxShort) return "fail 11: short wrap"
    if (minByte >= maxByte || maxShort <= minShort) return "fail 12: comparison"

    if (130.toByte() != (-126).toByte()) return "fail 13: int to byte"
    if (65535.toShort() != (-1).toShort()) return "fail 14: int to short"
    if (0x1_0000_0081L.toByte() != (-127).toByte()) return "fail 15: long to byte"
    // Floating-to-narrow conversions are deliberately a Common deprecation error: spell the
    // authoritative two-step semantics explicitly, as the diagnostic requires.
    if (130.9.toInt().toByte() != (-126).toByte()) return "fail 16: double to byte"
    if ((0.0 / 0.0).toInt().toByte() != 0.toByte()) return "fail 17: NaN to byte"
    if ((1.0 / 0.0).toInt().toByte() != (-1).toByte()) return "fail 18: +Inf to byte"
    if ((-1.0 / 0.0).toInt().toShort() != 0.toShort()) return "fail 19: -Inf to short"
    if (1.0e100.toInt().toShort() != (-1).toShort()) return "fail 20: high double to short"
    if ((-1.0e100).toInt().toShort() != 0.toShort()) return "fail 21: low double to short"
    if ((-7).toByte().toLong() != -7L) return "fail 22: byte sign extension"
    if ((-700).toShort().toDouble() != -700.0) return "fail 23: short to double"

    if (nullableByte(null) != 11.toByte()) return "fail 24: nullable byte empty"
    if (nullableByte((-9).toByte()) != (-9).toByte()) return "fail 25: nullable byte value"
    if (nullableShort(null) != 12.toShort()) return "fail 26: nullable short empty"
    if (nullableShort((-900).toShort()) != (-900).toShort()) return "fail 27: nullable short value"

    val byteAny: Any = (-7).toByte()
    val shortAny: Any = (-700).toShort()
    if (byteAny !is Byte || byteAny != (-7).toByte()) return "fail 28: byte box"
    if (shortAny !is Short || shortAny != (-700).toShort()) return "fail 29: short box"
    if (byteAny.hashCode() != -7 || shortAny.hashCode() != -700) return "fail 30: hash"
    if ("" + byteAny != "-7" || "" + shortAny != "-700") return "fail 31: boxed string"

    val byteBox = NarrowBox((-1).toByte())
    byteBox.put(2.toByte())
    if (byteBox.get() != 2.toByte()) return "fail 32: generic byte"
    val shortBox = NarrowBox((-2).toShort())
    if (shortBox.get() != (-2).toShort()) return "fail 33: generic short"
    if (narrowIdentity((-3).toByte()) != (-3).toByte()) return "fail 34: generic function"
    val source: NarrowSource<Byte> = ByteSource((-4).toByte())
    if (source.value() != (-4).toByte()) return "fail 35: generic interface"

    val bytes = arrayOf<Byte>(minByte, 0, maxByte)
    bytes[1] = (-5).toByte()
    if (bytes[0] != minByte || bytes[1] != (-5).toByte() || bytes[2] != maxByte) {
        return "fail 36: byte vector"
    }
    val shorts = arrayOf<Short>(minShort, 0, maxShort)
    shorts[1] = (-500).toShort()
    if (shorts[0] != minShort || shorts[1] != (-500).toShort() || shorts[2] != maxShort) {
        return "fail 37: short vector"
    }

    if ((-7).toByte() + 10L != 3L || (-700).toShort() + 700.5 != 0.5) {
        return "fail 38: mixed-width arithmetic"
    }
    if (7.toByte() / 2.toShort() != 3 || 7.toByte() % 2.toShort() != 1) {
        return "fail 39: narrow div/rem"
    }
    if (!((-7).toByte() < 0) || !((-700).toShort() < (-7).toByte())) {
        return "fail 40: mixed-width comparison"
    }
    val state = NarrowState((-10).toByte(), (-1000).toShort())
    state.byteValue = (state.byteValue + 1).toByte()
    state.shortValue = (state.shortValue - 1).toShort()
    if (state.byteValue != (-9).toByte() || state.shortValue != (-1001).toShort()) {
        return "fail 41: narrow properties"
    }

    return "OK"
}
