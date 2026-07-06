// 'try' used as an expression: 'leave' discards the evaluation stack (ECMA-335), so the branch
// values cannot cross the region boundary on the stack — each branch drains its value into the
// synthetic '<try>' result local and the join label reloads it. 'safeDiv' stores the reloaded
// value into a local; 'safeDivExpr' is the expression-body form where the reloaded value feeds
// 'ret' directly. Kotlin's ArithmeticException maps to System.ArithmeticException, which the
// CLR's DivideByZeroException IS-A, so the division guard's faulting 'div' is catchable.
package test

import kotlin.io.println

fun safeDiv(a: Int, b: Int): Int {
    val x = try {
        a / b
    } catch (e: ArithmeticException) {
        -1
    }
    return x
}

fun safeDivExpr(a: Int, b: Int): Int = try {
    a / b
} catch (e: ArithmeticException) {
    -1
}

fun main() {
    println(safeDiv(84, 2))
    println(safeDivExpr(84, 0))
}
