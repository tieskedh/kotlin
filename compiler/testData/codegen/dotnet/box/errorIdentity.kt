// Runtime pin for exact Kotlin-created Error identity. CLR fatal exceptions remain foreign; this
// test covers the Kotlin-owned constructors, exact/root catches, identity, and the established
// Throwable/Exception collapse only.
fun box(): String {
    val empty = Error()
    if (empty.message != null) return "default message: ${empty.message}"
    if (empty.cause != null) return "default cause"

    val cause = IllegalStateException("cause")
    val explicit = Error("message", cause)
    if (explicit.message != "message") return "explicit message: ${explicit.message}"
    if (explicit.cause !== cause) return "explicit cause"

    val caused = Error(cause)
    if (caused.message != cause.toString()) return "cause message: ${caused.message}"
    if (caused.cause !== cause) return "cause identity"

    val exact = try {
        throw Error("exact")
    } catch (failure: Error) {
        failure
    }
    if (exact.message != "exact") return "exact catch"

    val root: Throwable = exact
    if (root !== exact) return "root identity"

    val exceptionCatch = try {
        throw Error()
    } catch (_: Exception) {
        true
    }
    if (!exceptionCatch) return "collapsed exception catch"
    return "OK"
}
