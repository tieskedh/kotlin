// Explicit primitive casts preserve boxed Kotlin type identity. They are never numeric
// conversions, and safe casts materialize the existing nullable-primitive carrier.

fun castBoolean(value: Any?): Boolean = value as Boolean
fun castByte(value: Any?): Byte = value as Byte
fun castShort(value: Any?): Short = value as Short
fun castInt(value: Any?): Int = value as Int
fun castLong(value: Any?): Long = value as Long
fun castFloat(value: Any?): Float = value as Float
fun castDouble(value: Any?): Double = value as Double
fun castChar(value: Any?): Char = value as Char

fun castNullableBoolean(value: Any?): Boolean? = value as Boolean?
fun castNullableByte(value: Any?): Byte? = value as Byte?
fun castNullableShort(value: Any?): Short? = value as Short?
fun castNullableInt(value: Any?): Int? = value as Int?
fun castNullableLong(value: Any?): Long? = value as Long?
fun castNullableFloat(value: Any?): Float? = value as Float?
fun castNullableDouble(value: Any?): Double? = value as Double?
fun castNullableChar(value: Any?): Char? = value as Char?

fun safeBoolean(value: Any?): Boolean? = value as? Boolean
fun safeByte(value: Any?): Byte? = value as? Byte
fun safeShort(value: Any?): Short? = value as? Short
fun safeInt(value: Any?): Int? = value as? Int
fun safeLong(value: Any?): Long? = value as? Long
fun safeFloat(value: Any?): Float? = value as? Float
fun safeDouble(value: Any?): Double? = value as? Double
fun safeChar(value: Any?): Char? = value as? Char
fun safeExplicitNullableInt(value: Any?): Int? = value as? Int?

fun checkedIntAsNullable(value: Any?): Int? = value as Int
fun checkedIntAsAny(value: Any?): Any? = value as Int
fun safeIntAsAny(value: Any?): Any? = value as? Int

var scalarCastEvaluationCount: Int = 0

fun countedScalarCastOperand(value: Any?): Any? {
    scalarCastEvaluationCount = scalarCastEvaluationCount + 1
    return value
}

fun throwsClassCast(block: () -> Unit): Boolean {
    try {
        block()
    } catch (_: ClassCastException) {
        return true
    }
    return false
}

fun box(): String {
    val booleanValue: Any = true
    val byteValue: Any = (-5).toByte()
    val shortValue: Any = 1200.toShort()
    val intValue: Any = 123456
    val longValue: Any = 9876543210L
    val floatValue: Any = 1.25f
    val doubleValue: Any = 2.5
    val charValue: Any = 'K'

    if (!castBoolean(booleanValue)) return "fail 1: checked Boolean"
    if (castByte(byteValue) != (-5).toByte()) return "fail 2: checked Byte"
    if (castShort(shortValue) != 1200.toShort()) return "fail 3: checked Short"
    if (castInt(intValue) != 123456) return "fail 4: checked Int"
    if (castLong(longValue) != 9876543210L) return "fail 5: checked Long"
    if (castFloat(floatValue) != 1.25f) return "fail 6: checked Float"
    if (castDouble(doubleValue) != 2.5) return "fail 7: checked Double"
    if (castChar(charValue) != 'K') return "fail 8: checked Char"

    if (castNullableBoolean(booleanValue) != true) return "fail 9: nullable Boolean"
    if (castNullableByte(byteValue) != (-5).toByte()) return "fail 10: nullable Byte"
    if (castNullableShort(shortValue) != 1200.toShort()) return "fail 11: nullable Short"
    if (castNullableInt(intValue) != 123456) return "fail 12: nullable Int"
    if (castNullableLong(longValue) != 9876543210L) return "fail 13: nullable Long"
    if (castNullableFloat(floatValue) != 1.25f) return "fail 14: nullable Float"
    if (castNullableDouble(doubleValue) != 2.5) return "fail 15: nullable Double"
    if (castNullableChar(charValue) != 'K') return "fail 16: nullable Char"

    if (safeBoolean(booleanValue) != true) return "fail 17: safe Boolean"
    if (safeByte(byteValue) != (-5).toByte()) return "fail 18: safe Byte"
    if (safeShort(shortValue) != 1200.toShort()) return "fail 19: safe Short"
    if (safeInt(intValue) != 123456) return "fail 20: safe Int"
    if (safeLong(longValue) != 9876543210L) return "fail 21: safe Long"
    if (safeFloat(floatValue) != 1.25f) return "fail 22: safe Float"
    if (safeDouble(doubleValue) != 2.5) return "fail 23: safe Double"
    if (safeChar(charValue) != 'K') return "fail 24: safe Char"
    if (safeExplicitNullableInt(intValue) != 123456) return "fail 25: explicit nullable safe Int"

    if (safeBoolean(intValue) != null) return "fail 26: Int became Boolean"
    if (safeByte(shortValue) != null) return "fail 27: Short became Byte"
    if (safeShort(byteValue) != null) return "fail 28: Byte became Short"
    if (safeInt(longValue) != null) return "fail 29: Long became Int"
    if (safeLong(intValue) != null) return "fail 30: Int became Long"
    if (safeFloat(doubleValue) != null) return "fail 31: Double became Float"
    if (safeDouble(floatValue) != null) return "fail 32: Float became Double"
    if (safeChar(intValue) != null) return "fail 33: Int became Char"

    if (!throwsClassCast { castBoolean(intValue) }) return "fail 34: checked Boolean mismatch"
    if (!throwsClassCast { castByte(shortValue) }) return "fail 35: checked Byte mismatch"
    if (!throwsClassCast { castShort(byteValue) }) return "fail 36: checked Short mismatch"
    if (!throwsClassCast { castInt(longValue) }) return "fail 37: checked Int mismatch"
    if (!throwsClassCast { castLong(intValue) }) return "fail 38: checked Long mismatch"
    if (!throwsClassCast { castFloat(doubleValue) }) return "fail 39: checked Float mismatch"
    if (!throwsClassCast { castDouble(floatValue) }) return "fail 40: checked Double mismatch"
    if (!throwsClassCast { castChar(intValue) }) return "fail 41: checked Char mismatch"
    if (!throwsClassCast { castNullableInt(longValue) }) return "fail 42: nullable mismatch"

    if (castNullableBoolean(null) != null ||
        castNullableByte(null) != null ||
        castNullableShort(null) != null ||
        castNullableInt(null) != null ||
        castNullableLong(null) != null ||
        castNullableFloat(null) != null ||
        castNullableDouble(null) != null ||
        castNullableChar(null) != null
    ) {
        return "fail 43: checked nullable null"
    }
    if (safeInt(null) != null || safeExplicitNullableInt(null) != null) {
        return "fail 44: safe null"
    }
    try {
        castInt(null)
        return "fail 45: checked non-null accepted null"
    } catch (_: NullPointerException) {
    }

    if (checkedIntAsNullable(intValue) != 123456) return "fail 46: outer nullable widening"
    if (checkedIntAsAny(intValue) != 123456) return "fail 47: outer checked boxing"
    if (safeIntAsAny(intValue) != 123456) return "fail 48: outer safe boxing"
    if (safeIntAsAny(longValue) != null) return "fail 49: outer safe failure"

    scalarCastEvaluationCount = 0
    if (countedScalarCastOperand(intValue) as? Int != 123456 || scalarCastEvaluationCount != 1) {
        return "fail 50: safe cast evaluated operand more than once"
    }
    scalarCastEvaluationCount = 0
    if (countedScalarCastOperand(intValue) as Int != 123456 || scalarCastEvaluationCount != 1) {
        return "fail 51: checked cast evaluated operand more than once"
    }

    return "OK"
}
