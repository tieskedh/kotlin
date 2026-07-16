private var hashEvaluations: Int = 0

private data class HashItem(val value: Int) {
    override fun hashCode(): Int = value
}

private fun hashArrayExpression(): Array<Any?> {
    hashEvaluations = hashEvaluations + 1
    return arrayOf<Any?>(intArrayOf(1, 2), HashItem(3))
}

private fun <T> genericContentHash(array: Array<T>?): Int = array.contentHashCode()

private fun <T> genericDeepHash(array: Array<T>?): Int = array.contentDeepHashCode()

fun box(): String {
    val nullAny: Array<Any?>? = null
    val nullInts: IntArray? = null
    if (nullAny.contentHashCode() != 0) return "fail 1: generic null"
    if (nullInts.contentHashCode() != 0) return "fail 2: primitive null"
    if (nullAny.contentDeepHashCode() != 0) return "fail 3: deep null"
    if (arrayOf<Any?>().contentHashCode() != 1) return "fail 4: generic empty"
    if (intArrayOf().contentHashCode() != 1) return "fail 5: primitive empty"

    if (intArrayOf(1, 2).contentHashCode() != 994) return "fail 6: Int"
    if (longArrayOf(1L, 2L).contentHashCode() != 994) return "fail 7: Long"
    if (doubleArrayOf(0.0).contentHashCode() != 31) return "fail 8: positive zero"
    if (doubleArrayOf(-0.0).contentHashCode() != Int.MIN_VALUE + 31) return "fail 9: negative zero"
    if (booleanArrayOf(true, false).contentHashCode() != 40359) return "fail 10: Boolean"
    if (charArrayOf('x').contentHashCode() != 151) return "fail 11: Char"

    val nan = 0.0 / 0.0
    if (doubleArrayOf(nan).contentHashCode() != 2146959391) return "fail 12: NaN"
    if (arrayOf<Any?>(HashItem(2), null).contentHashCode() != 1023) return "fail 13: scalar hash"

    val nested = arrayOf<Any?>(null, HashItem(2), arrayOf(HashItem(3)))
    if (nested.contentDeepHashCode() != 29887) return "fail 14: recursive reference"
    val primitiveNested = arrayOf<Any>(intArrayOf(1, 2), intArrayOf(3, 4))
    if (primitiveNested.contentDeepHashCode() != 32833) return "fail 15: recursive primitive"

    val referenceLeft = arrayOf<Any>(arrayOf("x", "y"))
    val referenceRight = arrayOf<Any>(arrayOf<Any>("x", "y"))
    if (!(referenceLeft contentDeepEquals referenceRight)) return "fail 16: setup equality"
    if (referenceLeft.contentDeepHashCode() != referenceRight.contentDeepHashCode()) {
        return "fail 17: deep equality/hash invariant"
    }

    val shallowLeft = arrayOf<Any?>("x", HashItem(4), null)
    val shallowRight = arrayOf<Any?>("x", HashItem(4), null)
    if (!(shallowLeft contentEquals shallowRight)) return "fail 18: shallow setup equality"
    if (shallowLeft.contentHashCode() != shallowRight.contentHashCode()) {
        return "fail 19: shallow equality/hash invariant"
    }

    if (genericContentHash(arrayOf("x")) != 31 + "x".hashCode()) return "fail 20: open shallow"
    if (genericDeepHash(arrayOf<Any>(intArrayOf(1, 2))) != 31 + 994) return "fail 21: open deep"
    if (genericContentHash<String>(null) != 0) return "fail 22: open shallow null"
    if (genericDeepHash<String>(null) != 0) return "fail 23: open deep null"

    hashEvaluations = 0
    val evaluatedHash = hashArrayExpression().contentDeepHashCode()
    if (evaluatedHash != (31 + 994) * 31 + 3) return "fail 24: evaluated result"
    if (hashEvaluations != 1) return "fail 25: evaluation count $hashEvaluations"
    return "OK"
}
