// Runtime pin for exact Kotlin-created Error identity inside the classified CLR exception
// universe. Error uses System.Exception as its physical carrier but must not classify as Kotlin
// Exception; the catch filter retains the logical hierarchy without replacing the object.
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
    } catch (_: Throwable) {
        false
    }
    if (exceptionCatch) return "Error classified as Exception"

    val runtime = RuntimeException("runtime")
    val exactRuntime = try {
        throw runtime
    } catch (failure: RuntimeException) {
        failure
    }
    if (exactRuntime !== runtime) return "RuntimeException identity"

    val mappedChildCaught = try {
        throw IllegalStateException("mapped")
    } catch (_: RuntimeException) {
        true
    }
    if (!mappedChildCaught) return "mapped child escaped RuntimeException"

    val plain = Exception("plain")
    val plainAsThrowable: Throwable = plain
    val runtimeAsThrowable: Throwable = runtime
    val errorAsThrowable: Throwable = explicit
    if (plainAsThrowable is RuntimeException) return "plain Exception classified as RuntimeException"
    if (runtimeAsThrowable !is Exception) return "RuntimeException not classified as Exception"
    if (errorAsThrowable is Exception) return "Error type test classified as Exception"
    return "OK"
}
