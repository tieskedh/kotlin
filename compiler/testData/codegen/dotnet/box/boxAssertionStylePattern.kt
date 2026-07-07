// The assertion-style box harness pattern enabled by the exception model: helper assertions
// throw on failure with a labeled message, and box() reports the first failing label through a
// single 'try'-expression with a catch-everything handler — the shape new box tests may use for
// failure reporting (existing tests keep their explicit if/return style). The failure message
// concatenates 'e.message' (a String?) directly, going through the backend's nullable-string
// coalescing (a null message would render as "null"); '?:' is not used — elvis desugars to a
// block in value position, which is outside the supported expression subset.

fun check(condition: Boolean, label: String) {
    if (!condition) throw IllegalStateException(label)
}

fun box(): String {
    return try {
        check(1 + 1 == 2, "arithmetic")
        check("a" + "b" == "ab", "concat")
        check(5 / 5 == 1, "division")
        "OK"
    } catch (e: Throwable) {
        "FAIL: " + e.message
    }
}
