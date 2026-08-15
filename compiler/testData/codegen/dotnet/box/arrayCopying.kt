private var trace: String = ""

private class Item(val value: Int)

private class GenericItem<T>(val value: T)

private class GenericArrayFiller<T>(private val values: Array<T>) {
    fun fill(element: T) {
        values.fill(element)
    }
}

private class GenericNullableArrayCopier<T>(private val values: Array<T?>) {
    fun shiftRight(): Array<T?> =
        values.copyInto(values, destinationOffset = 1, startIndex = 0, endIndex = values.size - 1)
}

private fun sourceExpression(): IntArray {
    trace = trace + "source;"
    return intArrayOf(4, 5, 6)
}

private fun destinationExpression(): IntArray {
    trace = trace + "destination;"
    return IntArray(4)
}

private fun recordedInt(label: String, value: Int): Int {
    trace = trace + label + ";"
    return value
}

private fun recordedGenericInts(): Array<Int> {
    trace = trace + "fillArray;"
    return arrayOf(1, 2, 3, 4)
}

private fun <T> fillOpen(values: Array<T>, element: T) {
    values.fill(element)
}

private fun <T> copyOpen(source: Array<T>, destination: Array<T>): Array<T> =
    source.copyInto(destination)

private fun scalarFillsAreCorrect(): Boolean {
    val bytes = arrayOf(0.toByte(), 0.toByte())
    val shorts = arrayOf(0.toShort(), 0.toShort())
    val longs = arrayOf(0L, 0L)
    val floats = arrayOf(0.0f, 0.0f)
    val doubles = arrayOf(0.0, 0.0)
    val booleans = arrayOf(false, false)
    val chars = arrayOf('a', 'a')
    bytes.fill(1.toByte())
    shorts.fill(2.toShort())
    longs.fill(3L)
    floats.fill(4.5f)
    doubles.fill(5.5)
    booleans.fill(true)
    chars.fill('K')
    return bytes[0] == 1.toByte() && bytes[1] == 1.toByte() &&
            shorts[0] == 2.toShort() && shorts[1] == 2.toShort() &&
            longs[0] == 3L && longs[1] == 3L &&
            floats[0] == 4.5f && floats[1] == 4.5f &&
            doubles[0] == 5.5 && doubles[1] == 5.5 &&
            booleans[0] && booleans[1] && chars[0] == 'K' && chars[1] == 'K'
}

private fun <T> resizeProjected(values: Array<out T?>, size: Int): Array<out T?> =
    values.copyOf(size)

private fun <T> padProjected(values: Array<out T?>): Array<out T?> =
    resizeProjected(values, values.size + 1)

private fun projectedAnyCopyIsCorrect(): Boolean {
    val values: Array<out Any?> = arrayOf(9)
    val padded = values.copyOf(2)
    return padded[0] == 9 && padded[1] == null
}

private fun openProjectedCopyIsCorrect(): Boolean {
    val paddedValue = padProjected<Int>(arrayOf(10))
    val paddedAny = padProjected<Any?>(arrayOf(11))
    val paddedReference = padProjected<String>(arrayOf("value"))
    val paddedNullableValue = padProjected<Int?>(arrayOf<Int?>(12))
    val truncatedValue = resizeProjected<Int>(arrayOf(13, 14), 1)
    @Suppress("UNCHECKED_CAST")
    val exactReference = paddedReference as Array<String?>
    @Suppress("UNCHECKED_CAST")
    val exactNullableValue = paddedNullableValue as Array<Int?>
    @Suppress("UNCHECKED_CAST")
    val exactTruncatedValue = truncatedValue as Array<Int>
    return paddedValue[0] == 10 && paddedValue[1] == null &&
            paddedAny[0] == 11 && paddedAny[1] == null &&
            exactReference[0] == "value" && exactReference[1] == null &&
            exactNullableValue[0] == 12 && exactNullableValue[1] == null &&
            exactTruncatedValue.size == 1 && exactTruncatedValue[0] == 13
}

private fun copyIntoFailureCategory(
    destinationSize: Int,
    destinationOffset: Int,
    startIndex: Int,
    endIndex: Int,
): String = try {
    intArrayOf(1, 2).copyInto(IntArray(destinationSize), destinationOffset, startIndex, endIndex)
    "not-thrown"
} catch (_: IllegalArgumentException) {
    "argument"
} catch (_: IndexOutOfBoundsException) {
    "index"
} catch (_: Exception) {
    "other"
}

private fun negativeCopySizeCategory(): String = try {
    intArrayOf(1).copyOf(-1)
    "not-thrown"
} catch (_: ArithmeticException) {
    "arithmetic"
} catch (_: IllegalArgumentException) {
    "argument"
} catch (_: IndexOutOfBoundsException) {
    "index"
} catch (_: Exception) {
    "exception"
}

fun box(): String {
    val original = intArrayOf(1, 2, 3)
    val copied = original.copyOf()
    if (copied === original || copied.size != 3 || copied[0] != 1 || copied[2] != 3) {
        return "fail 1: Int copyOf"
    }
    original[0] = 9
    if (copied[0] != 1) return "fail 2: independent copy"

    val truncated = intArrayOf(1, 2, 3).copyOf(2)
    val padded = intArrayOf(1, 2).copyOf(4)
    if (truncated.size != 2 || truncated[1] != 2) return "fail 3: truncate"
    if (padded.size != 4 || padded[0] != 1 || padded[1] != 2 || padded[2] != 0 || padded[3] != 0) {
        return "fail 4: pad"
    }
    val empty = IntArray(0)
    if (empty.copyOf() === empty) return "fail 5: empty alias"

    val longs = longArrayOf(7L, 8L).copyOf(3)
    val doubles = doubleArrayOf(1.5, 2.5).copyOf()
    val booleans = booleanArrayOf(true).copyOf(2)
    val chars = charArrayOf('A', 'B').copyOf()
    if (longs[0] != 7L || longs[1] != 8L || longs[2] != 0L) return "fail 6: Long"
    if (doubles[0] != 1.5 || doubles[1] != 2.5) return "fail 7: Double"
    if (!booleans[0] || booleans[1]) return "fail 8: Boolean"
    if (chars[0] != 'A' || chars[1] != 'B') return "fail 9: Char"

    if (longArrayOf(3L).copyInto(LongArray(1))[0] != 3L) return "fail 10: Long copyInto"
    if (doubleArrayOf(3.5).copyInto(DoubleArray(1))[0] != 3.5) return "fail 11: Double copyInto"
    if (!booleanArrayOf(true).copyInto(BooleanArray(1))[0]) return "fail 12: Boolean copyInto"
    if (charArrayOf('Z').copyInto(CharArray(1))[0] != 'Z') return "fail 13: Char copyInto"

    val destination = IntArray(3)
    val returned = intArrayOf(5, 6, 7).copyInto(destination)
    if (returned !== destination || destination[0] != 5 || destination[1] != 6 || destination[2] != 7) {
        return "fail 14: default copyInto"
    }
    val partial = IntArray(4)
    intArrayOf(3, 4, 5).copyInto(partial, destinationOffset = 1, startIndex = 1)
    if (partial[0] != 0 || partial[1] != 4 || partial[2] != 5 || partial[3] != 0) {
        return "fail 15: partial defaults"
    }

    val overlapRight = intArrayOf(1, 2, 3, 4, 5)
    overlapRight.copyInto(overlapRight, destinationOffset = 1, startIndex = 0, endIndex = 4)
    if (overlapRight[0] != 1 || overlapRight[1] != 1 || overlapRight[2] != 2 ||
        overlapRight[3] != 3 || overlapRight[4] != 4
    ) return "fail 16: overlap right"
    val overlapLeft = intArrayOf(1, 2, 3, 4, 5)
    overlapLeft.copyInto(overlapLeft, destinationOffset = 0, startIndex = 1, endIndex = 5)
    if (overlapLeft[0] != 2 || overlapLeft[1] != 3 || overlapLeft[2] != 4 ||
        overlapLeft[3] != 5 || overlapLeft[4] != 5
    ) return "fail 17: overlap left"

    val strings = arrayOf("a", "b").copyOf()
    val nullable = arrayOf<String?>(null, "x").copyOf()
    val items = arrayOf(Item(9)).copyOf()
    val genericItems = arrayOf(GenericItem("value")).copyOf()
    val paddedStrings = arrayOf("first").copyOf(3)
    val truncatedItems = arrayOf(Item(1), Item(2)).copyOf(1)
    if (strings[0] != "a" || strings[1] != "b") return "fail 18: String copyOf"
    if (nullable[0] != null || nullable[1] != "x") return "fail 19: nullable reference copyOf"
    if (items[0].value != 9) return "fail 20: user class copyOf"
    if (genericItems[0].value != "value") return "fail 21: generic class copyOf"
    if (paddedStrings.size != 3 || paddedStrings[0] != "first" ||
        paddedStrings[1] != null || paddedStrings[2] != null
    ) return "fail 21a: padded String copyOf"
    if (truncatedItems.size != 1 || truncatedItems[0]?.value != 1) return "fail 21b: truncated Item copyOf"

    val anyDestination: Array<Any> = arrayOf("old", "old")
    val stringSource: Array<String> = arrayOf("left", "right")
    val anyResult = stringSource.copyInto(anyDestination)
    if (anyResult !== anyDestination || anyDestination[0] != "left" || anyDestination[1] != "right") {
        return "fail 22: projected source copyInto"
    }

    trace = ""
    val evaluated = sourceExpression().copyInto(
        destinationExpression(),
        destinationOffset = recordedInt("offset", 1),
        startIndex = recordedInt("start", 1),
        endIndex = recordedInt("end", 3),
    )
    if (trace != "source;destination;offset;start;end;") return "fail 23: evaluation $trace"
    if (evaluated[0] != 0 || evaluated[1] != 5 || evaluated[2] != 6 || evaluated[3] != 0) {
        return "fail 24: evaluated result"
    }

    trace = ""
    val namedOrder = sourceExpression().copyInto(
        destination = destinationExpression(),
        endIndex = recordedInt("end", 3),
        startIndex = recordedInt("start", 1),
        destinationOffset = recordedInt("offset", 1),
    )
    if (trace != "source;destination;end;start;offset;") return "fail 25: named evaluation $trace"
    if (namedOrder[1] != 5 || namedOrder[2] != 6) return "fail 26: named result"

    trace = ""
    val evaluatedCopy = sourceExpression().copyOf(recordedInt("size", 2))
    if (trace != "source;size;" || evaluatedCopy[0] != 4 || evaluatedCopy[1] != 5) {
        return "fail 27: copyOf evaluation $trace"
    }

    if (copyIntoFailureCategory(1, 2, 0, 0) != "index") return "fail 28: destination offset"
    if (copyIntoFailureCategory(1, 0, 1, 0) != "index") return "fail 29: reversed source"
    if (copyIntoFailureCategory(2, 0, -1, 1) != "index") return "fail 30: negative source"
    if (copyIntoFailureCategory(2, 0, 0, 3) != "index") return "fail 31: source end"
    if (copyIntoFailureCategory(2, -1, 0, 1) != "index") return "fail 32: negative destination"
    if (copyIntoFailureCategory(1, 0, 0, 2) != "index") return "fail 33: destination range"
    val endEmpty = intArrayOf(1, 2).copyInto(IntArray(2), destinationOffset = 2, startIndex = 2, endIndex = 2)
    if (endEmpty[0] != 0 || endEmpty[1] != 0) return "fail 34: empty end copy"
    if (negativeCopySizeCategory() != "exception") return "fail 35: negative ${negativeCopySizeCategory()}"

    val openIntDestination = arrayOf(0, 0, 0)
    if (copyOpen(arrayOf(1, 2, 3), openIntDestination) !== openIntDestination ||
        openIntDestination.contentToString() != "[1, 2, 3]"
    ) return "fail 35a: open value copyInto"
    val openStringDestination = arrayOf("", "")
    if (copyOpen(arrayOf("left", "right"), openStringDestination) !== openStringDestination ||
        openStringDestination.contentToString() != "[left, right]"
    ) return "fail 35b: open reference copyInto"
    val ownerValues = arrayOf<Int?>(1, null, 3, 4)
    if (GenericNullableArrayCopier(ownerValues).shiftRight() !== ownerValues ||
        ownerValues.contentToString() != "[1, 1, null, 3]"
    ) return "fail 35c: erased owner nullable copyInto"

    val genericInts = arrayOf(1, 2, 3, 4)
    genericInts.fill(9, fromIndex = 1, toIndex = 3)
    if (genericInts.contentToString() != "[1, 9, 9, 4]") return "fail 36: generic value fill"
    genericInts.fill(0, fromIndex = 2, toIndex = 2)
    if (genericInts.contentToString() != "[1, 9, 9, 4]") return "fail 36a: empty generic fill"
    trace = ""
    val evaluatedFill = recordedGenericInts()
    evaluatedFill.fill(
        recordedInt("fillElement", 8),
        fromIndex = recordedInt("fillFrom", 1),
        toIndex = recordedInt("fillTo", 3),
    )
    if (trace != "fillArray;fillElement;fillFrom;fillTo;" ||
        evaluatedFill.contentToString() != "[1, 8, 8, 4]"
    ) return "fail 36b: generic fill evaluation $trace"
    fillOpen(genericInts, 6)
    if (genericInts.contentToString() != "[6, 6, 6, 6]") return "fail 36c: open value fill"
    val openStrings = arrayOf("a", "b")
    fillOpen(openStrings, "open")
    if (openStrings.contentToString() != "[open, open]") return "fail 36d: open reference fill"
    val openNullableInts = arrayOf<Int?>(1, null)
    fillOpen(openNullableInts, null)
    if (openNullableInts.contentToString() != "[null, null]") return "fail 36e: open nullable fill"
    val erasedOwnerInts = arrayOf(1, 2, 3)
    GenericArrayFiller(erasedOwnerInts).fill(7)
    if (erasedOwnerInts.contentToString() != "[7, 7, 7]") return "fail 36f: erased owner fill"
    val nullableInts = arrayOf<Int?>(1, null, 3)
    nullableInts.fill(5, fromIndex = 1)
    if (nullableInts.contentToString() != "[1, 5, 5]") return "fail 36g: nullable value fill"
    nullableInts.fill(null, fromIndex = 2)
    if (nullableInts.contentToString() != "[1, 5, null]") return "fail 36h: nullable null fill"
    if (!scalarFillsAreCorrect()) return "fail 36i: scalar fill family"
    val nullableStrings = arrayOf<String?>("a", "b", "c")
    nullableStrings.fill(null, fromIndex = 1)
    if (nullableStrings.contentToString() != "[a, null, null]") return "fail 37: nullable fill"
    try {
        genericInts.fill(0, fromIndex = 3, toIndex = 2)
        return "fail 38: missing fill bounds failure"
    } catch (_: IllegalArgumentException) {
    }
    try {
        genericInts.fill(0, fromIndex = -1, toIndex = 2)
        return "fail 39: missing fill lower-bound failure"
    } catch (_: IndexOutOfBoundsException) {
    }
    try {
        genericInts.fill(0, fromIndex = 0, toIndex = 5)
        return "fail 40: missing fill upper-bound failure"
    } catch (_: IndexOutOfBoundsException) {
    }
    val paddedGenericInts: Array<Int?> = arrayOf(7).copyOf(3)
    if (paddedGenericInts.size != 3 || paddedGenericInts[0] != 7 ||
        paddedGenericInts[1] != null || paddedGenericInts[2] != null
    ) return "fail 41: nullable value padding"
    val paddedBytes: Array<Byte?> = arrayOf(1.toByte()).copyOf(2)
    val paddedShorts: Array<Short?> = arrayOf(2.toShort()).copyOf(2)
    val paddedLongs: Array<Long?> = arrayOf(3L).copyOf(2)
    val paddedFloats: Array<Float?> = arrayOf(4.5f).copyOf(2)
    val paddedDoubles: Array<Double?> = arrayOf(5.5).copyOf(2)
    val paddedBooleans: Array<Boolean?> = arrayOf(true).copyOf(2)
    val paddedChars: Array<Char?> = arrayOf('K').copyOf(2)
    if (paddedBytes[0] != 1.toByte() || paddedBytes[1] != null ||
        paddedShorts[0] != 2.toShort() || paddedShorts[1] != null ||
        paddedLongs[0] != 3L || paddedLongs[1] != null ||
        paddedFloats[0] != 4.5f || paddedFloats[1] != null ||
        paddedDoubles[0] != 5.5 || paddedDoubles[1] != null ||
        paddedBooleans[0] != true || paddedBooleans[1] != null ||
        paddedChars[0] != 'K' || paddedChars[1] != null
    ) return "fail 42: nullable scalar padding"
    val projectedInts: Array<out Int> = arrayOf(8)
    val paddedProjectedInts = projectedInts.copyOf(2)
    if (paddedProjectedInts[0] != 8 || paddedProjectedInts[1] != null) {
        return "fail 43: projected nullable value padding"
    }
    if (!projectedAnyCopyIsCorrect()) return "fail 44: captured projected value padding"
    if (!openProjectedCopyIsCorrect()) return "fail 45: open projected nullable value padding"
    return "OK"
}
