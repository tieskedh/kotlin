// Executing twin of the branches.kt/loops.kt/equality.kt ilText goldens: && / || short-circuit
// evaluation, subjectless when {} branch selection, and if/else chains. Short-circuiting is
// observed through integer division by zero: on the CLR a reached `10 / x` with x == 0 throws
// and kills the process (non-zero exit), so a wrong evaluation order fails the test by itself.

fun safeAnd(x: Int): Boolean = x != 0 && 10 / x > 1

fun safeOr(x: Int): Boolean = x == 0 || 10 / x > 1

fun bothSides(a: Int, b: Int): Boolean = (a != 0 && 100 / a > b) || (b != 0 && 100 / b > a)

fun classify(x: Int): String = when {
    x < 0 -> "negative"
    x == 0 -> "zero"
    x < 10 -> "small"
    else -> "large"
}

fun grade(score: Int): String {
    if (score >= 90) return "A"
    if (score >= 80) return "B"
    if (score >= 70) return "C"
    return "F"
}

fun chooseString(flag: Boolean): String =
    if (flag) "yes" else "no"

fun box(): String {
    // && must not evaluate its right side when the left is false: 10 / 0 would fault.
    if (safeAnd(0)) return "fail 1"
    // && evaluates the right side when the left is true.
    if (!safeAnd(5)) return "fail 2"
    if (safeAnd(10)) return "fail 3"
    if (safeAnd(-5)) return "fail 4"

    // || must not evaluate its right side when the left is true: 10 / 0 would fault.
    if (!safeOr(0)) return "fail 5"
    // || evaluates the right side when the left is false.
    if (!safeOr(2)) return "fail 6"
    if (safeOr(20)) return "fail 7"

    // Combined && inside ||: the first disjunct is false without faulting (a == 0 guards the
    // division), the second is true.
    if (!bothSides(0, 1)) return "fail 8"
    if (bothSides(0, 0)) return "fail 9"
    if (!bothSides(1, 0)) return "fail 10"

    // Negation of a short-circuited result.
    if (!(!safeAnd(0))) return "fail 11"

    // Subjectless when {}: first matching branch wins.
    if (classify(-7) != "negative") return "fail 12: got " + classify(-7)
    if (classify(0) != "zero") return "fail 13: got " + classify(0)
    if (classify(3) != "small") return "fail 14: got " + classify(3)
    if (classify(42) != "large") return "fail 15: got " + classify(42)

    // if/else chains returning distinct strings.
    if (grade(95) != "A") return "fail 16: got " + grade(95)
    if (grade(80) != "B") return "fail 17: got " + grade(80)
    if (grade(79) != "C") return "fail 18: got " + grade(79)
    if (grade(12) != "F") return "fail 19: got " + grade(12)
    if (chooseString(true) != "yes") return "fail 20: got " + chooseString(true)
    if (chooseString(false) != "no") return "fail 21: got " + chooseString(false)

    return "OK"
}
