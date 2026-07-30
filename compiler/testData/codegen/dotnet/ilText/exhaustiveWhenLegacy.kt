// LANGUAGE: -NoWhenBranchMatchedExceptionWithMessage

// Older language modes retain fir2ir's parameterless noWhenBranchMatchedException builtin.
// Keep this fallback separate from the Kotlin 2.5 subject-aware stdlib-helper path.
package test

import kotlin.io.println

fun describeLegacy(flag: Boolean): String = when (flag) {
    true -> "true"
    false -> "false"
}

fun main() {
    println(describeLegacy(true))
}
