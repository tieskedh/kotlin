fun runtimeValue(): Throwable = RuntimeException("runtime")

fun catchMappedRuntime(): String = try {
    throw IllegalStateException("mapped")
} catch (failure: RuntimeException) {
    "runtime"
}

fun distinguishError(): String = try {
    throw Error("fatal")
} catch (failure: Exception) {
    "exception"
} catch (failure: Error) {
    "error"
}

fun main() {
    println("classified-exceptions")
}
