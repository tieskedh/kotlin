private class AnyValue {
    var peer: Any? = null

    override fun equals(other: Any?): Boolean = other === peer

    override fun hashCode(): Int = 73

    override fun toString(): String = "override"

    fun parentString(): String = super.toString()
}

private class Plain

private interface Left

private interface Right

private class Both : Left, Right

private fun same(left: Any?, right: Any?): Boolean = left == right

private fun mixedNullable(value: Int?, other: Any?): Boolean = value == other

private fun anyHash(value: Any): Int = value.hashCode()

private fun <T> genericSame(left: T, right: T): Boolean = left == right

private fun <T> genericRender(value: T): String = "$value"

private fun <T> genericString(value: T): String = value.toString()

fun box(): String {
    val first = AnyValue()
    val second = AnyValue()
    first.peer = second
    second.peer = first

    if (first === second) return "fail 1: distinct identity"
    if (first != second || second != first) return "fail 2: virtual equals"

    val any: Any = first
    if (!any.equals(second)) return "fail 3: explicit equals"
    if (any.hashCode() != 73) return "fail 4: virtual hashCode"
    if (any.toString() != "override") return "fail 5: virtual toString"
    if (first.parentString() == "override") return "fail 6: super toString dispatched virtually"

    val plain = Plain()
    if (plain != plain || plain == Plain()) return "fail 7: default Object equals"

    val nullAny: Any? = null
    if (!same(nullAny, nullAny)) return "fail 8: null equality"
    if ("$nullAny" != "null") return "fail 9: null string"
    if ("$any" != "override") return "fail 10: virtual string conversion"

    val boxedInt: Any? = 42
    if (!mixedNullable(42, boxedInt)) return "fail 11: nullable-to-Any equality"
    if (mixedNullable(null, boxedInt)) return "fail 12: null-to-boxed equality"

    if (!genericSame(42, 42)) return "fail 13: generic value equality"
    if (!genericSame("same", "same")) return "fail 14: generic reference equality"
    val missing: String? = null
    if (!genericSame(missing, missing)) return "fail 15: generic null equality"
    if (genericRender(missing) != "null") return "fail 16: generic null string"
    if (genericRender(first) != "override") return "fail 17: generic virtual string"
    if (genericString(missing) != "null") return "fail 18: generic member string"

    val negativeZero: Any = -0.0
    val positiveZero: Any = 0.0
    if (same(negativeZero, positiveZero)) return "fail 19: boxed signed-zero equality"
    if (anyHash(negativeZero) == anyHash(positiveZero)) return "fail 20: boxed signed-zero hash"
    val zero = 0.0
    val nanA: Any = zero / zero
    val nanB: Any = zero / zero
    if (!same(nanA, nanB)) return "fail 21: boxed NaN equality"
    if (anyHash(nanA) != anyHash(nanB)) return "fail 22: boxed NaN hash"
    if (negativeZero.toString() != "-0.0") return "fail 23: boxed Double string"
    val trueValue: Any = true
    val falseValue: Any = false
    if (trueValue.toString() != "true") return "fail 24: boxed Boolean string"
    if (anyHash(trueValue) != 1231 || anyHash(falseValue) != 1237) {
        return "fail 25: boxed Boolean hash"
    }
    if ((-0.0).equals(0.0)) return "fail 26: direct Double signed-zero equality"
    val directNanA = zero / zero
    val directNanB = zero / zero
    if (!directNanA.equals(directNanB)) return "fail 27: direct Double NaN equality"
    if ((-0.0).hashCode() == 0.0.hashCode()) return "fail 28: direct Double hash"
    if (true.hashCode() != 1231 || false.hashCode() != 1237) return "fail 29: direct Boolean hash"
    val charValue: Any = 'a'
    if (anyHash(charValue) != 97) return "fail 30: boxed Char hash"
    if ('Z'.hashCode() != 90) return "fail 31: direct Char hash"

    val both = Both()
    val left: Left = both
    val right: Right = both
    if (left !== right) return "fail 32: unrelated interface identity"

    return "OK"
}
