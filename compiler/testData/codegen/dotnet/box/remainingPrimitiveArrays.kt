// ByteArray, ShortArray, and FloatArray use the same Kotlin-owned wrapper contract as the first
// five signed Common primitive arrays. Their CLR vectors are private storage and explicit C#
// projections, never Kotlin declaration identity.

private var initializerTrace: String = ""

private fun byteTotal(vararg values: Byte): Int {
    var result = 0
    for (value in values) result = result + value
    return result
}

private fun shortTotal(vararg values: Short): Int {
    var result = 0
    for (value in values) result = result + value
    return result
}

private fun floatTotal(vararg values: Float): Float {
    var result = 0.0f
    for (value in values) result = result + value
    return result
}

private class RemainingArrays<T>(
    val bytes: ByteArray,
    val shorts: ShortArray,
    val floats: FloatArray,
    val payload: T,
)

fun box(): String {
    val bytes = ByteArray(3)
    val shorts = ShortArray(3)
    val floats = FloatArray(3)
    if (bytes[0] != 0.toByte() || shorts[0] != 0.toShort() || floats[0] != 0.0f) {
        return "fail 1: defaults"
    }

    bytes[0] = (-128).toByte()
    bytes[1] = 127.toByte()
    shorts[0] = (-32768).toShort()
    shorts[1] = 32767.toShort()
    floats[0] = -1.5f
    floats[1] = 2.25f
    if (bytes[0] != (-128).toByte() || bytes[1] != 127.toByte()) return "fail 2: Byte get/set"
    if (shorts[0] != (-32768).toShort() || shorts[1] != 32767.toShort()) return "fail 3: Short get/set"
    if (floats[0] != -1.5f || floats[1] != 2.25f) return "fail 4: Float get/set"

    initializerTrace = ""
    val initializedBytes = ByteArray(3) { index ->
        initializerTrace = initializerTrace + index
        (index - 1).toByte()
    }
    val initializedShorts = ShortArray(2) { index -> (index * 1000 - 500).toShort() }
    val initializedFloats = FloatArray(2) { index -> if (index == 0) 1.25f else 2.75f }
    if (initializerTrace != "012" || initializedBytes[0] != (-1).toByte() || initializedBytes[2] != 1.toByte()) {
        return "fail 5: Byte initializer order"
    }
    if (initializedShorts[0] != (-500).toShort() || initializedShorts[1] != 500.toShort()) {
        return "fail 6: Short initializer"
    }
    if (initializedFloats[0] != 1.25f || initializedFloats[1] != 2.75f) {
        return "fail 7: Float initializer"
    }

    val byteLiteral = byteArrayOf((-2).toByte(), 3.toByte())
    val shortLiteral = shortArrayOf((-200).toShort(), 300.toShort())
    val floatLiteral = floatArrayOf(-2.5f, 3.75f)
    if (byteTotal(*byteLiteral, 4.toByte()) != 5) return "fail 8: Byte vararg/spread"
    if (shortTotal((-100).toShort(), *shortLiteral) != 0) return "fail 9: Short vararg/spread"
    if (floatTotal(*floatLiteral, 0.75f) != 2.0f) return "fail 10: Float vararg/spread"

    var byteLoop = 0
    for (value in byteLiteral) byteLoop = byteLoop + value
    var shortLoop = 0
    for (value in shortLiteral) shortLoop = shortLoop + value
    var floatLoop = 0.0f
    for (value in floatLiteral) floatLoop = floatLoop + value
    if (byteLoop != 1 || shortLoop != 100 || floatLoop != 1.25f) return "fail 11: direct loops"

    val byteIterator = byteArrayOf(5.toByte(), 6.toByte()).iterator()
    val shortIterator = shortArrayOf(700.toShort()).iterator()
    val floatIterator = floatArrayOf(1.5f).iterator()
    if (byteIterator.nextByte() != 5.toByte() || byteIterator.next() != 6.toByte() || byteIterator.hasNext()) {
        return "fail 12: ByteIterator"
    }
    if (shortIterator.nextShort() != 700.toShort() || shortIterator.hasNext()) return "fail 13: ShortIterator"
    if (floatIterator.nextFloat() != 1.5f || floatIterator.hasNext()) return "fail 14: FloatIterator"

    val byteCopy = byteLiteral.copyOf(3)
    val shortDestination = ShortArray(3)
    val shortResult = shortLiteral.copyInto(shortDestination, destinationOffset = 1)
    val floatCopy = floatLiteral.copyOf()
    if (byteCopy[0] != (-2).toByte() || byteCopy[1] != 3.toByte() || byteCopy[2] != 0.toByte()) {
        return "fail 15: Byte copyOf"
    }
    if (shortResult !== shortDestination || shortDestination[1] != (-200).toShort() ||
        shortDestination[2] != 300.toShort()
    ) return "fail 16: Short copyInto"
    if (floatCopy === floatLiteral || floatCopy[0] != -2.5f || floatCopy[1] != 3.75f) {
        return "fail 17: Float copyOf"
    }

    if (!(byteArrayOf(1, 2) contentEquals byteArrayOf(1, 2))) return "fail 18: Byte contentEquals"
    if (!(shortArrayOf(1, 2) contentEquals shortArrayOf(1, 2))) return "fail 19: Short contentEquals"
    val floatNaN = 0.0f / 0.0f
    if (!(floatArrayOf(floatNaN) contentEquals floatArrayOf(floatNaN))) return "fail 20: Float NaN"
    if (floatArrayOf(-0.0f) contentEquals floatArrayOf(0.0f)) return "fail 21: Float signed zero"
    if (byteArrayOf(1, 2).contentHashCode() != 994 || shortArrayOf(1, 2).contentHashCode() != 994) {
        return "fail 22: narrow contentHashCode"
    }
    if (floatArrayOf(1.5f, -2.0f).contentToString() != "[1.5, -2.0]") {
        return "fail 23: Float contentToString"
    }

    val byteAsAny: Any = byteLiteral
    val shortAsAny: Any = shortLiteral
    val floatAsAny: Any = floatLiteral
    if (byteAsAny !is ByteArray || byteAsAny is ShortArray || shortAsAny !is ShortArray ||
        shortAsAny is FloatArray || floatAsAny !is FloatArray || floatAsAny is ByteArray
    ) return "fail 24: exact runtime identity"
    if (byteAsAny as ByteArray !== byteLiteral || shortAsAny as? ByteArray != null ||
        floatAsAny as FloatArray !== floatLiteral
    ) return "fail 25: exact casts"

    val genericBytes: Any = arrayOf((-2).toByte(), 3.toByte())
    val genericShorts: Any = arrayOf((-200).toShort(), 300.toShort())
    val genericFloats: Any = arrayOf(-2.5f, 3.75f)
    if (genericBytes is ByteArray || genericShorts is ShortArray || genericFloats is FloatArray) {
        return "fail 26: generic/specialized identity collapsed"
    }

    val holder = RemainingArrays(byteLiteral, shortLiteral, floatLiteral, "payload")
    if (holder.bytes !== byteLiteral || holder.shorts !== shortLiteral || holder.floats !== floatLiteral ||
        holder.payload != "payload"
    ) return "fail 27: fields/generic storage"

    try {
        bytes[bytes.size]
        return "fail 28: bounds did not throw"
    } catch (_: IndexOutOfBoundsException) {
    }
    return "OK"
}
