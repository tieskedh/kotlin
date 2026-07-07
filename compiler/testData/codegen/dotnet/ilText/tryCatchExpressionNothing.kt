// A 'try' expression none of whose branches reaches its join: both the try branch and the
// catch handler throw, so no 'leave' ever targets the join label and nothing is drained into
// the synthetic '<try>' result local. The join label and its reload are skipped (the construct
// is Nothing-like; only a phantom value keeps the stack tracker balanced for the dead consumer
// instructions), so the SAME function can still open the later protected region — previously
// the unreferenced join resurrected the throw's phantom stack depth and crashed the backend at
// the second '.try'.
package test

import kotlin.io.println

fun allBranchesThrow(c: Boolean): Int {
    if (c) {
        val x: Int = try {
            throw IllegalStateException("a")
        } catch (e: Exception) {
            throw IllegalStateException("b")
        }
    }
    try {
        println("later")
    } catch (e: Exception) {
        return -1
    }
    return 0
}

fun main() {
    println(allBranchesThrow(false))
}
