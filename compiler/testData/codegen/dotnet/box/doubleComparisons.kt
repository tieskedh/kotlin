// Executable twin of the doubleArithmetic.kt ilText golden comparison cases: float64 comparisons
// must be NaN-correct (every ordered comparison is false for unordered operands) and IEEE-equal
// for 0.0 == -0.0, both in branch form and in the Boolean-value-producing form.

fun compare(a: Double, b: Double): String {
    if (a < b) return "less"
    if (a > b) return "greater"
    if (a == b) return "equal"
    return "unordered"
}

fun box(): String {
    val zero = 0.0
    val nan = zero / zero

    // NaN equality, branch form and value form.
    if (nan == nan) return "fail 1: NaN == NaN"
    val selfEqual = nan == nan
    if (selfEqual) return "fail 2: NaN == NaN as value"
    val notEqual = nan != nan
    if (!notEqual) return "fail 3: NaN != NaN is false"

    // Every ordered comparison with NaN is false.
    val less = nan < 1.0
    if (less) return "fail 4: NaN < 1.0"
    val lessOrEqual = nan <= 1.0
    if (lessOrEqual) return "fail 5: NaN <= 1.0"
    val greater = nan > 1.0
    if (greater) return "fail 6: NaN > 1.0"
    val greaterOrEqual = nan >= 1.0
    if (greaterOrEqual) return "fail 7: NaN >= 1.0"
    if (compare(nan, 1.0) != "unordered") return "fail 8: got " + compare(nan, 1.0)

    // IEEE: -0.0 == 0.0 under the == operator.
    val negativeZero = -zero
    if (negativeZero != zero) return "fail 9: -0.0 != 0.0"
    val negativeZeroEqualsZero = negativeZero == zero
    if (!negativeZeroEqualsZero) return "fail 10: -0.0 == 0.0 as value is false"

    // Ordinary ordered comparisons through the branching compare helper.
    if (compare(1.0, 2.0) != "less") return "fail 11: got " + compare(1.0, 2.0)
    if (compare(3.0, 3.0) != "equal") return "fail 12: got " + compare(3.0, 3.0)
    if (compare(5.0, 4.0) != "greater") return "fail 13: got " + compare(5.0, 4.0)

    // Ordinary ordered comparisons in value form.
    val a = 1.5
    val b = 2.5
    val aLessB = a < b
    if (!aLessB) return "fail 14: 1.5 < 2.5 is false"
    val aLessOrEqualA = a <= a
    if (!aLessOrEqualA) return "fail 15: 1.5 <= 1.5 is false"
    val bGreaterA = b > a
    if (!bGreaterA) return "fail 16: 2.5 > 1.5 is false"
    val bGreaterOrEqualB = b >= b
    if (!bGreaterOrEqualB) return "fail 17: 2.5 >= 2.5 is false"
    if (a >= b) return "fail 18: 1.5 >= 2.5"

    return "OK"
}
