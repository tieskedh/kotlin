// Exact Kotlin Float closure over the CLR float32/System.Single carrier.

const val FLOAT_CONST: Float = -1.25f

class FloatCell(var value: Float)

class FloatBox<T>(private var value: T) {
    fun get(): T = value
    fun put(next: T) {
        value = next
    }
}

interface FloatSource<T> {
    fun value(): T
}

class ExactFloatSource(private val stored: Float) : FloatSource<Float> {
    override fun value(): Float = stored
}

fun scalarOverload(value: Float): String = "float:" + value
fun scalarOverload(value: Double): String = "double:" + value

fun addFloat(left: Float, right: Float): Float = left + right
fun addLongFloat(left: Long, right: Float): Float = left + right
fun addFloatDouble(left: Float, right: Double): Double = left + right
fun remainder(left: Float, right: Float): Float = left % right
fun nullableFloat(value: Float?): Float = value ?: 12.5f
fun eraseFloat(value: Any): Any = value
fun objectEquals(left: Any?, right: Any?): Boolean = left == right

fun box(): String {
    if (FLOAT_CONST != -1.25f) return "fail 1: const"
    if (scalarOverload(0.5f) != "float:0.5") return "fail 2: float overload"
    if (scalarOverload(0.5) != "double:0.5") return "fail 3: double overload"

    if (addFloat(1.25f, 2.5f) != 3.75f) return "fail 4: arithmetic"
    if (addLongFloat(16_777_216L, 1f) != 16_777_216f) return "fail 5: long promotion"
    if (addFloatDouble(0.5f, 0.25) != 0.75) return "fail 6: double promotion"
    if (remainder(-7f, 2.5f) != -2f) return "fail 7: remainder"
    if (16_777_216f + 1f != 16_777_216f) return "fail 8: float32 rounding"
    if (16_777_217.0.toFloat() != 16_777_216f) return "fail 9: double to float"
    if (0.1f.toDouble() != 0.10000000149011612) return "fail 10: float to double"

    val zero = 0f
    val negativeZero = -zero
    val nan = zero / zero
    val otherNan = zero / zero
    val positiveInfinity = 1f / zero
    val negativeInfinity = -1f / zero
    if (nan == nan) return "fail 11: IEEE NaN equality"
    if (negativeZero != zero) return "fail 12: IEEE zero equality"
    if (nan < zero || nan <= zero || nan > zero || nan >= zero) return "fail 13: unordered"

    if (!nan.equals(otherNan)) return "fail 14: boxed NaN equality"
    if (negativeZero.equals(zero)) return "fail 15: boxed signed-zero equality"
    if (nan.hashCode() != otherNan.hashCode()) return "fail 16: NaN hash"
    if (negativeZero.hashCode() == zero.hashCode()) return "fail 17: signed-zero hash"
    if (negativeZero.compareTo(zero) >= 0) return "fail 18: signed-zero total order"
    if (nan.compareTo(positiveInfinity) <= 0) return "fail 19: NaN total order"
    if (nan.compareTo(otherNan) != 0) return "fail 20: canonical NaN order"
    if (1.5f.compareTo(1) <= 0 || 1.compareTo(1.5f) >= 0) return "fail 21: mixed compareTo"

    if (nan.toInt() != 0) return "fail 22: NaN to Int"
    if (positiveInfinity.toInt() != Int.MAX_VALUE) return "fail 23: +Inf to Int"
    if (negativeInfinity.toInt() != Int.MIN_VALUE) return "fail 24: -Inf to Int"
    if (Float.MAX_VALUE.toLong() != Long.MAX_VALUE) return "fail 25: max to Long"
    if ((-Float.MAX_VALUE).toLong() != Long.MIN_VALUE) return "fail 26: min to Long"
    if (2147483520f.toInt() != 2147483520) return "fail 27: in-range truncation"
    if (12.75f.toInt() != 12 || (-12.75f).toLong() != -12L) return "fail 28: truncate"

    if (negativeZero.toString() != "-0.0") return "fail 29: negative zero string"
    if (zero.toString() != "0.0") return "fail 30: zero string"
    if (nan.toString() != "NaN") return "fail 31: NaN string"
    if (positiveInfinity.toString() != "Infinity") return "fail 32: infinity string"
    if (negativeInfinity.toString() != "-Infinity") return "fail 33: negative infinity string"
    if (0.1f.toString() != "0.1") return "fail 34: compact string"
    if (Float.MIN_VALUE.toString() != "1.401298E-45") return "fail 35: minimum string"
    if (Float.MAX_VALUE.toString() != "3.40282347E38") return "fail 36: maximum string"
    if (1.0e7f.toString() != "1.0E7") return "fail 37: upper notation boundary"
    if (1.0e-3f.toString() != "0.001") return "fail 38: lower notation boundary"

    if (nullableFloat(null) != 12.5f || nullableFloat(-2.5f) != -2.5f) {
        return "fail 39: nullable"
    }
    val cell = FloatCell(1.5f)
    cell.value = -2.25f
    if (cell.value != -2.25f) return "fail 40: property"
    val generic = FloatBox(3.5f)
    generic.put(-4.5f)
    if (generic.get() != -4.5f) return "fail 41: generic class"
    val source: FloatSource<Float> = ExactFloatSource(5.5f)
    if (source.value() != 5.5f) return "fail 42: generic interface"
    val values = arrayOf(1.25f, -2.5f)
    values[0] = 3.75f
    if (values[0] != 3.75f || values[1] != -2.5f) return "fail 43: generic array"

    // Route through an opaque Any parameter so frontend data flow cannot recover Float and
    // choose primitive IEEE equality; this exercises the actual boxed-object boundary.
    val floatAny: Any = eraseFloat(-0.0f)
    if (floatAny !is Float) return "fail 44: boxed type"
    if (objectEquals(floatAny, eraseFloat(0.0f))) return "fail 45: object-boundary signed zero"
    val nanAny: Any = eraseFloat(nan)
    if (!objectEquals(nanAny, eraseFloat(otherNan))) return "fail 46: object-boundary NaN"
    if (nanAny.toString() != "NaN") return "fail 47: object-boundary string"
    if (nanAny.hashCode() != nan.hashCode()) return "fail 48: object-boundary hash"

    return "OK"
}
