// fir2ir appends the synthetic noWhenBranchMatchedException call to exhaustive `when`
// expressions without a source `else`. The DotNet intrinsic emits an inline parameterless
// System.Exception throw (JVM intrinsic-registry precedent; deliberate
// cross-target deviation from Roslyn's modern-only SwitchExpressionException, whenprobe_s1).
// Pin value and statement positions, nullable-Boolean composition, and the absence of a false
// IllegalStateException catch edge.
package test

import kotlin.io.println

fun describe(flag: Boolean): String = when (flag) {
    true -> "true"
    false -> "false"
}

fun describeNullable(flag: Boolean?): String = when (flag) {
    true -> "true"
    false -> "false"
    null -> "null"
}

fun printFlag(flag: Boolean) {
    when (flag) {
        true -> println("true")
        false -> println("false")
    }
}

fun caught(flag: Boolean): String = try {
    when (flag) {
        true -> "true"
        false -> "false"
    }
} catch (e: IllegalStateException) {
    "wrong"
} catch (e: Exception) {
    "caught"
}

fun join(left: String, right: String): String = left + right

fun secondArgument(flag: Boolean): String = join(
    "value:",
    when (flag) {
        true -> "true"
        false -> "false"
    },
)

fun <T> choose(flag: Boolean, first: T, second: T): T = when (flag) {
    true -> first
    false -> second
}

fun main() {
    println(describe(true))
    println(describeNullable(null))
    printFlag(false)
    println(caught(true))
    println(secondArgument(false))
    println(choose(true, "first", "second"))
}
