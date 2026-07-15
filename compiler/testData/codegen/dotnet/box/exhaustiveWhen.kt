// Exhaustive Boolean/Boolean? `when` without a source `else` compiles through the registered
// noWhenBranchMatchedException intrinsic. Well-typed Kotlin values cover every reachable arm;
// whenprobe_s2 separately forces the fallthrough with a noncanonical CLR bool and runtime-pins
// its exact Kotlin.NoWhenBranchMatchedException identity/catchability on both CLR runtimes.
private fun describe(flag: Boolean): String = when (flag) {
    true -> "true"
    false -> "false"
}

private fun describeNullable(flag: Boolean?): String = when (flag) {
    true -> "true"
    false -> "false"
    null -> "null"
}

private fun statementValue(flag: Boolean): Int {
    var result = 0
    when (flag) {
        true -> result = 1
        false -> result = 2
    }
    return result
}

private fun caught(flag: Boolean): String = try {
    describe(flag)
} catch (e: IllegalStateException) {
    "wrong"
} catch (e: Exception) {
    "caught"
}

private fun join(left: String, right: String): String = left + right

private fun secondArgument(flag: Boolean): String = join(
    "value:",
    when (flag) {
        true -> "true"
        false -> "false"
    },
)

private fun <T> choose(flag: Boolean, first: T, second: T): T = when (flag) {
    true -> first
    false -> second
}

fun box(): String {
    if (describe(true) != "true") return "fail: true arm"
    if (describe(false) != "false") return "fail: false arm"
    if (describeNullable(true) != "true") return "fail: nullable true arm"
    if (describeNullable(false) != "false") return "fail: nullable false arm"
    if (describeNullable(null) != "null") return "fail: nullable null arm"
    if (statementValue(true) != 1) return "fail: statement true arm"
    if (statementValue(false) != 2) return "fail: statement false arm"
    if (caught(true) != "true") return "fail: caught true arm"
    if (caught(false) != "false") return "fail: caught false arm"
    if (secondArgument(true) != "value:true") return "fail: second-argument true arm"
    if (secondArgument(false) != "value:false") return "fail: second-argument false arm"
    if (choose(true, "first", "second") != "first") return "fail: generic true arm"
    if (choose(false, 1, 2) != 2) return "fail: generic false arm"
    return "OK"
}
