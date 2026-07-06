// A thrown built-in exception is caught by its own Kotlin type and carries its message across
// the CLR exception table: IllegalStateException maps to System.InvalidOperationException on
// both the throw and catch sides.

fun box(): String {
    try {
        throw IllegalStateException("boom")
    } catch (e: IllegalStateException) {
        if (e.message == "boom") return "OK"
    }
    return "FAIL"
}
