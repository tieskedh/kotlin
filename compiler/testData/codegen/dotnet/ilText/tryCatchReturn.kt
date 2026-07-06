// Non-local exits crossing protected regions: 'ret' inside a '.try' or handler assembles but
// throws InvalidProgramException at runtime, so 'attempt' drains each return value into the
// synthetic '<return>' local and leaves to the shared 'IL_returnJoin' epilogue, which reloads
// it and returns. 'sumSkipping' freezes the loop shapes: 'continue' is a backward 'leave' to
// the while condition label and 'break' a forward 'leave' to the loop end label (a 'leave' may
// target any label of an enclosing scope, probe-verified).
package test

import kotlin.io.println

fun attempt(flag: Boolean): Int {
    try {
        if (flag) {
            throw IllegalStateException("boom")
        }
        return 1
    } catch (e: Throwable) {
        return -1
    }
}

fun sumSkipping(): Int {
    var sum = 0
    var i = 0
    while (i < 10) {
        try {
            if (i == 3) {
                i = i + 1
                continue
            }
            if (i == 7) break
            sum = sum + i
        } catch (e: Throwable) {
            sum = -1
        }
        i = i + 1
    }
    return sum
}

fun main() {
    println(attempt(false))
    println(attempt(true))
    println(sumSkipping())
}
