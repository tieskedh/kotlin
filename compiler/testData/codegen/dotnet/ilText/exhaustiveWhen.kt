// Kotlin 2.5 fir2ir passes the subject of an exhaustive `when` without a source `else` to the
// Common-authored throwNoWhenBranchMatchedException helper. Pin the ordinary helper call and
// message-producing body, value and statement positions, nullable-Boolean composition, and the
// absence of a false IllegalStateException catch edge.
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
