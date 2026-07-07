// Kotlin 'try'/'catch'/'finally' nests the try/catch construct inside an outer
// '.try { } finally { }': a CLR '.try' may carry either catch handlers or one finally, never
// both (combining them assembles but throws InvalidProgramException at runtime,
// probe-verified). The returns inside the protected regions drain into the synthetic '<return>'
// local and 'leave' in one hop across both regions to the return-join epilogue after the outer
// construct; the CLR runs the finally automatically on the way out, on the normal and the
// caught path alike.
package test

import kotlin.io.println

fun compute(flag: Boolean): Int {
    try {
        if (flag) throw IllegalArgumentException("bad")
        return 7
    } catch (e: IllegalArgumentException) {
        return -1
    } finally {
        println("cleanup")
    }
}

fun main() {
    println(compute(false))
    println(compute(true))
}
