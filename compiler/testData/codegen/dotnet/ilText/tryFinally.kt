// Catch-less 'try'/'finally' is a single CLR region: '.try { ... leave }' followed by
// 'finally { ... endfinally }', with the CLR running the finally automatically on the 'leave'
// out (and on the exceptional path) — no JVM-style finally inlining/duplication. 'guarded' is
// the statement form; 'measure' uses 'try' as an expression, draining the branch value into the
// synthetic '<try>' result local inside the region and reloading it at the join label after the
// whole construct.
package test

import kotlin.io.println

fun guarded(flag: Boolean): Int {
    var state = 0
    try {
        if (flag) throw IllegalStateException("boom")
        state = 1
    } finally {
        state = state + 10
    }
    return state
}

fun measure(a: Int, b: Int): Int {
    val r = try {
        a / b
    } finally {
        println("done")
    }
    return r
}

fun main() {
    println(guarded(false))
    println(measure(84, 2))
}
