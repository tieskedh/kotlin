// Executing twin of the forLoops.kt ilText golden: iteration counts and sums for `a..b`
// ranges (only Int.rangeTo(Int) headers are lowered by DotNetForLoopLowering; until/downTo/step
// are deliberately not used), nesting, break/continue, and the Int.MAX_VALUE boundary that a
// naive `i <= last` lowering would turn into an infinite loop via wraparound.

fun sum(from: Int, to: Int): Int {
    var total = 0
    for (i in from..to) {
        total = total + i
    }
    return total
}

fun count(from: Int, to: Int): Int {
    var n = 0
    for (i in from..to) {
        n = n + 1
    }
    return n
}

fun nested(): Int {
    var c = 0
    for (i in 1..3) {
        for (j in 1..i) {
            c = c + 1
        }
    }
    return c
}

fun breakContinue(limit: Int): Int {
    var acc = 0
    for (i in 0..100) {
        if (i == 3) continue
        if (i > limit) break
        acc = acc + i
    }
    return acc
}

// A range ending exactly at Int.MAX_VALUE (2147483645 = MAX_VALUE - 2): a naive
// `while (i <= last)` lowering increments past MAX_VALUE after the last iteration, wraps to
// MIN_VALUE and never terminates; the correct lowering exits on `i == last` first.
fun maxValueBoundary(): Int {
    var n = 0
    for (i in 2147483645..2147483647) {
        n = n + 1
    }
    return n
}

fun box(): String {
    // Basic sums and counts over a..b.
    if (sum(1, 4) != 10) return "fail 1: got " + sum(1, 4)
    if (count(1, 4) != 4) return "fail 2: got " + count(1, 4)
    if (sum(-2, 2) != 0) return "fail 3: got " + sum(-2, 2)
    if (sum(0, 10) != 55) return "fail 4: got " + sum(0, 10)

    // a..b with a == b iterates exactly once.
    if (count(3, 3) != 1) return "fail 5: got " + count(3, 3)
    if (sum(3, 3) != 3) return "fail 6: got " + sum(3, 3)

    // a..b with a > b is empty: executes zero times.
    if (count(5, 1) != 0) return "fail 7: got " + count(5, 1)
    if (sum(5, 1) != 0) return "fail 8: got " + sum(5, 1)
    if (count(1, -1) != 0) return "fail 9: got " + count(1, -1)

    // Nested loops with inner bound depending on the outer variable: 1 + 2 + 3 iterations.
    if (nested() != 6) return "fail 10: got " + nested()

    // continue skips i == 3, break stops after i > 7: 0+1+2+4+5+6+7 = 25.
    if (breakContinue(7) != 25) return "fail 11: got " + breakContinue(7)
    // With limit -1 the break condition already holds at i == 0, so nothing accumulates.
    if (breakContinue(-1) != 0) return "fail 12: got " + breakContinue(-1)

    // 2147483645..2147483647 has exactly 3 values; wrapping lowerings loop forever here
    // (the harness kills the process on timeout, so a regression fails instead of hanging).
    if (maxValueBoundary() != 3) return "fail 13: got " + maxValueBoundary()

    return "OK"
}
