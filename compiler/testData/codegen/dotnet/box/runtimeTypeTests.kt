// Ordinary runtime tests preserve Kotlin classifier identity. The CLR instruction is only the
// physical realization for exact carriers; it must not introduce numeric conversion or closed
// generic-argument identity.

open class RuntimeBase(val label: String)

class RuntimeLeaf(label: String) : RuntimeBase(label), RuntimeMarker {
    override fun marker(): String = "marker:$label"
}

class RuntimeUnrelated

interface RuntimeMarker {
    fun marker(): String
}

fun isAny(value: Any?): Boolean = value is Any
fun isNullableAny(value: Any?): Boolean = value is Any?
fun isString(value: Any?): Boolean = value is String
fun isNullableString(value: Any?): Boolean = value is String?
fun isBase(value: Any?): Boolean = value is RuntimeBase
fun isMarker(value: Any?): Boolean = value is RuntimeMarker
fun isNotMarker(value: Any?): Boolean = value !is RuntimeMarker
fun isNullableMarker(value: Any?): Boolean = value is RuntimeMarker?

fun isBoolean(value: Any?): Boolean = value is Boolean
fun isByte(value: Any?): Boolean = value is Byte
fun isShort(value: Any?): Boolean = value is Short
fun isInt(value: Any?): Boolean = value is Int
fun isLong(value: Any?): Boolean = value is Long
fun isFloat(value: Any?): Boolean = value is Float
fun isDouble(value: Any?): Boolean = value is Double
fun isChar(value: Any?): Boolean = value is Char

fun isNullableBoolean(value: Any?): Boolean = value is Boolean?
fun isNullableByte(value: Any?): Boolean = value is Byte?
fun isNullableShort(value: Any?): Boolean = value is Short?
fun isNullableInt(value: Any?): Boolean = value is Int?
fun isNullableLong(value: Any?): Boolean = value is Long?
fun isNullableFloat(value: Any?): Boolean = value is Float?
fun isNullableDouble(value: Any?): Boolean = value is Double?
fun isNullableChar(value: Any?): Boolean = value is Char?

fun isBooleanArray(value: Any?): Boolean = value is BooleanArray
fun isIntArray(value: Any?): Boolean = value is IntArray
fun isLongArray(value: Any?): Boolean = value is LongArray
fun isDoubleArray(value: Any?): Boolean = value is DoubleArray
fun isCharArray(value: Any?): Boolean = value is CharArray

fun smartcastBase(value: Any?): String =
    if (value is RuntimeBase) value.label else "none"

fun smartcastMarker(value: Any?): String =
    if (value is RuntimeMarker) value.marker() else "none"

fun smartcastIntArray(value: Any?): Int =
    if (value is IntArray) value.size + value[0] else -1

var runtimeTypeTestEvaluations: Int = 0

fun countedRuntimeTypeTestOperand(value: Any?): Any? {
    runtimeTypeTestEvaluations = runtimeTypeTestEvaluations + 1
    return value
}

fun box(): String {
    val leaf = RuntimeLeaf("leaf")
    val unrelated = RuntimeUnrelated()
    val leafAsAny: Any = leaf
    val unrelatedAsAny: Any = unrelated
    val nullValue: Any? = null

    if (!isAny(leafAsAny) || isAny(nullValue) || !isNullableAny(nullValue)) {
        return "fail 1: Any nullability"
    }
    if (!isString("text") || isString(leafAsAny) || !isNullableString(nullValue)) {
        return "fail 2: String identity"
    }
    if (!isBase(leafAsAny) || !isMarker(leafAsAny) || isMarker(unrelatedAsAny)) {
        return "fail 3: class/interface identity"
    }
    if (isNotMarker(leafAsAny) || !isNotMarker(unrelatedAsAny) || !isNotMarker(nullValue)) {
        return "fail 4: negative interface test"
    }
    if (!isNullableMarker(leafAsAny) || !isNullableMarker(nullValue) || isNullableMarker(unrelatedAsAny)) {
        return "fail 5: nullable interface test"
    }
    if (smartcastBase(leafAsAny) != "leaf" || smartcastBase(unrelatedAsAny) != "none") {
        return "fail 6: class smartcast"
    }
    if (smartcastMarker(leafAsAny) != "marker:leaf" || smartcastMarker(unrelatedAsAny) != "none") {
        return "fail 7: interface smartcast"
    }

    val booleanValue: Any = true
    val byteValue: Any = (-7).toByte()
    val shortValue: Any = 1200.toShort()
    val intValue: Any = 123456
    val longValue: Any = 9876543210L
    val floatValue: Any = 1.25f
    val doubleValue: Any = 2.5
    val charValue: Any = 'K'

    if (!isBoolean(booleanValue) || !isByte(byteValue) || !isShort(shortValue) || !isInt(intValue) ||
        !isLong(longValue) || !isFloat(floatValue) || !isDouble(doubleValue) || !isChar(charValue)
    ) {
        return "fail 8: scalar positive matrix"
    }
    if (isBoolean(intValue) || isByte(shortValue) || isShort(byteValue) || isInt(longValue) ||
        isLong(intValue) || isFloat(doubleValue) || isDouble(floatValue) || isChar(intValue)
    ) {
        return "fail 9: scalar identities collapsed"
    }
    if (!isNullableBoolean(booleanValue) || !isNullableByte(byteValue) ||
        !isNullableShort(shortValue) || !isNullableInt(intValue) || !isNullableLong(longValue) ||
        !isNullableFloat(floatValue) || !isNullableDouble(doubleValue) || !isNullableChar(charValue)
    ) {
        return "fail 10: nullable scalar positive matrix"
    }
    if (!isNullableBoolean(nullValue) || !isNullableByte(nullValue) ||
        !isNullableShort(nullValue) || !isNullableInt(nullValue) || !isNullableLong(nullValue) ||
        !isNullableFloat(nullValue) || !isNullableDouble(nullValue) || !isNullableChar(nullValue)
    ) {
        return "fail 11: nullable scalar null matrix"
    }
    if (isNullableBoolean(intValue) || isNullableByte(shortValue) || isNullableShort(byteValue) ||
        isNullableInt(longValue) || isNullableLong(intValue) || isNullableFloat(doubleValue) ||
        isNullableDouble(floatValue) || isNullableChar(intValue)
    ) {
        return "fail 12: nullable scalar identities collapsed"
    }

    val booleans: Any = booleanArrayOf(true)
    val ints: Any = intArrayOf(41)
    val longs: Any = longArrayOf(42L)
    val doubles: Any = doubleArrayOf(4.25)
    val chars: Any = charArrayOf('Z')
    if (!isBooleanArray(booleans) || !isIntArray(ints) || !isLongArray(longs) ||
        !isDoubleArray(doubles) || !isCharArray(chars)
    ) {
        return "fail 13: primitive-array positive matrix"
    }
    if (isBooleanArray(ints) || isIntArray(longs) || isLongArray(doubles) ||
        isDoubleArray(chars) || isCharArray(booleans)
    ) {
        return "fail 14: primitive-array identities collapsed"
    }
    if (smartcastIntArray(ints) != 42 || smartcastIntArray(longs) != -1) {
        return "fail 15: primitive-array smartcast"
    }

    if (nullValue !is Nothing? || leafAsAny is Nothing?) return "fail 16: Nothing? identity"

    runtimeTypeTestEvaluations = 0
    if (countedRuntimeTypeTestOperand(leafAsAny) !is RuntimeMarker || runtimeTypeTestEvaluations != 1) {
        return "fail 17: positive test evaluated operand more than once"
    }
    runtimeTypeTestEvaluations = 0
    if (countedRuntimeTypeTestOperand(unrelatedAsAny) is RuntimeMarker || runtimeTypeTestEvaluations != 1) {
        return "fail 18: failed test evaluated operand more than once"
    }
    runtimeTypeTestEvaluations = 0
    if (countedRuntimeTypeTestOperand(nullValue) !is RuntimeMarker? || runtimeTypeTestEvaluations != 1) {
        return "fail 19: nullable test evaluated operand more than once"
    }

    return "OK"
}
