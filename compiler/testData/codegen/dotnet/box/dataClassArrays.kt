// TARGET_BACKEND: DOTNET

private data class Ints(val values: IntArray)
private data class Longs(val values: LongArray)
private data class Doubles(val values: DoubleArray)
private data class Booleans(val values: BooleanArray)
private data class Chars(val values: CharArray)
private data class Labels(val values: Array<String?>)
private data class NullableInts(val values: IntArray?)

fun box(): String {
    val ints = intArrayOf(1, 2, 3)
    val first = Ints(ints)
    val alias = Ints(ints)
    val sameContent = Ints(intArrayOf(1, 2, 3))

    // Kotlin/JVM data-class semantics deliberately keep array equality referential even though
    // generated hashCode and toString inspect the elements.
    if (first != alias || first == sameContent) return "fail 1: identity equality"
    if (first.hashCode() != 30817 || sameContent.hashCode() != 30817) return "fail 2: int hash"
    if (first.toString() != "Ints(values=[1, 2, 3])") return "fail 3: int string"

    if (Longs(longArrayOf(0L, 4294967296L)).hashCode() != 962) return "fail 4: long hash"
    if (Booleans(booleanArrayOf(true, false)).hashCode() != 40359) return "fail 5: boolean hash"
    if (Chars(charArrayOf('a', 'Z')).hashCode() != 4058) return "fail 6: char hash"

    val zero = 0.0
    val doubles = Doubles(doubleArrayOf(zero / zero, -zero))
    if (doubles.hashCode() != -16251967) return "fail 7: double hash " + doubles.hashCode()
    if (doubles.toString() != "Doubles(values=[NaN, -0.0])") return "fail 8: double string " + doubles

    val labels = Labels(arrayOf("x", null, "y"))
    if (labels.toString() != "Labels(values=[x, null, y])") return "fail 9: reference string " + labels

    val absent = NullableInts(null)
    if (absent.hashCode() != 0 || absent.toString() != "NullableInts(values=null)") {
        return "fail 10: null array " + absent
    }
    val empty = Ints(intArrayOf())
    if (empty.hashCode() != 1 || empty.toString() != "Ints(values=[])") return "fail 11: empty array"

    return "OK"
}
