// IGNORE_BACKEND: JS_IR, JS_IR_ES6, WASM_JS, WASM_WASI
// WITH_STDLIB
// FULL_JDK

// FILE: lib.kt
val x: String = computeX()

fun computeX(): String = throw IllegalStateException("1")

val y: String = computeY()

fun computeY(): String = "2"

// FILE: main.kt
fun box() : String {
    try {
        x
        return "FAIL 1.1"
    } catch(t: Error) {
        val cause = t.cause
        if (cause !is IllegalStateException) return "FAIL 1.2: cause must be IllegalStateException, was ${cause?.let { it::class }}"
        if (cause.message != "1") return "FAIL 1.3: message must be '1', was '${cause.message}'"
    }
    try {
        y
        return "FAIL 2.1"
    } catch(t: Error) {
        if (JDK_MAJOR_VERSION < 17 || BACKEND_UNDER_TEST == "NATIVE") {
            if (t.cause != null) return "FAIL 2.2.1: cause must be null, got ${t.cause}"
        } else {
            val cause = t.cause as? Error
            if (cause == null) return "FAIL 2.2.2: cause must be ExceptionInInitializerError, was ${t.cause}"
            if (cause.cause != null) return "FAIL 2.2.3: cause.cause must be null, was ${cause.cause}"
            val expectedMessage = IllegalStateException("1").toString()
            if (cause.message?.contains(expectedMessage) != true) return "FAIL 2.2.4: cause.message must contain '$expectedMessage', was '${cause.message}'"
        }
        val expectedMessage = when (BACKEND_UNDER_TEST) {
            "JVM_IR" -> "Could not initialize class LibKt"
            "NATIVE" -> "There was an error during file or class initialization"
            else -> "Could not initialize file"
        }
        if (t.message != expectedMessage) return "FAIL 2.3: message must be '$expectedMessage', was '${t.message}'"
    }
    return "OK"
}
