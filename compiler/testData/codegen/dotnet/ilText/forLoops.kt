package test

import kotlin.io.println

fun sum(from: Int, to: Int): Int {
    var total = 0
    for (i in from..to) {
        total = total + i
    }
    return total
}

fun nested(): Int {
    var count = 0
    for (i in 1..3) {
        for (j in 1..i) {
            count = count + 1
        }
    }
    return count
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

// A range ending exactly at Int.MAX_VALUE: a naive `while (index <= last)` would increment past
// MAX_VALUE after the final iteration, wrap to MIN_VALUE and loop forever. The lowered
// guard-then-do-while form exits on `i == last` before the wrapped value is ever compared.
fun maxValueBoundary(): Int {
    var iterations = 0
    for (i in 2147483646..2147483647) {
        iterations = iterations + 1
    }
    return iterations
}

// This loop shape is NOT lowered (only Int.rangeTo(Int) is; this is Long.rangeTo(Long)): the
// function must be skipped by the emitter with its regular unsupported-function warning — it
// keeps its iterator-based desugaring whose LongIterator local has no IL mapping. main must not
// call it, or main itself would be skipped.
fun unsupportedLongRange(): Long {
    var lastSeen = 0L
    for (i in 1L..3L) {
        lastSeen = i
    }
    return lastSeen
}

fun main() {
    for (i in 0..3) println(i)
    println(sum(1, 4))
    println(sum(5, 1)) // empty range: executes zero times
    println(nested())
    println(breakContinue(7))
    println(maxValueBoundary())
}
