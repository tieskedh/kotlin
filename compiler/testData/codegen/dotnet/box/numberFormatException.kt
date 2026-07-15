// Runtime pin for the exact Kotlin-owned NumberFormatException. Its CLR parent is the mapped
// System.ArgumentException so the supported Kotlin parent edge works without catch filters; the
// exact class owns Kotlin's nullable default-message behavior.
fun box(): String {
    val empty = NumberFormatException()
    if (empty.message != null) return "default message: ${empty.message}"
    if (empty.cause != null) return "default cause"

    val exact = try {
        throw NumberFormatException("exact")
    } catch (failure: NumberFormatException) {
        failure
    }
    if (exact.message != "exact") return "exact message: ${exact.message}"

    val parent: IllegalArgumentException = exact
    if (parent !== exact) return "parent identity"
    if (parent.message != "exact") return "parent message: ${parent.message}"

    val parentCatch = try {
        throw NumberFormatException()
    } catch (_: IllegalArgumentException) {
        true
    }
    if (!parentCatch) return "parent catch"

    val root: Throwable = exact
    if (root !== exact) return "root identity"
    return "OK"
}
