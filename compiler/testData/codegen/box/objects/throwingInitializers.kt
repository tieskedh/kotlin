// IGNORE_BACKEND: JS_IR, JS_IR_ES6, WASM_JS, WASM_WASI
// WITH_STDLIB
// FULL_JDK

class C {
    companion object {
        val never: Nothing = run { throw IllegalStateException("C.never") }
    }
}

open class Parent {
    companion object {
        val never: Nothing = run { throw IllegalStateException("Parent.never") }
    }
}

class Child : Parent() {
    companion object {
        val normal = 42
    }
}

object O {
    val never: Nothing = run { throw IllegalStateException("O.never") }
    fun foo() {}
}

class ThrowsErrorWithCompanion {
    companion object {
        val never: Nothing = run { throw Error("ThrowsErrorWithCompanion.never") }
    }
}

object ThrowsErrorObject {
    val never: Nothing = run { throw Error("ThrowsErrorObject.never") }
    fun foo() {}
}

fun box(): String {
    try {
        C()
        return "FAIL 1.1: should throw"
    } catch (e: Error) {
        val cause = e.cause
        if (cause !is IllegalStateException) return "FAIL 1.2: cause must be IllegalStateException, was ${cause?.let { it::class }}"
        if (cause.message != "C.never") return "FAIL 1.3: message must be 'C.never', was '${cause.message}'"
    }

    try {
        C()
        return "FAIL 2.1: should throw"
    } catch (e: Error) {
        if (JDK_MAJOR_VERSION < 17 || BACKEND_UNDER_TEST == "NATIVE") {
            if (e.cause != null) return "FAIL 2.2.1: cause must be null, got ${e.cause}"
        } else {
            val cause = e.cause as? Error
            if (cause == null) return "FAIL 2.2.2: cause must be ExceptionInInitializerError, was ${e.cause}"
            if (cause.cause != null) return "FAIL 2.2.3: cause.cause must be null, was ${cause.cause}"
            val expectedMessage = IllegalStateException("C.never").toString()
            if (cause.message?.contains(expectedMessage) != true) return "FAIL 2.2.4: cause.message must contain '$expectedMessage', was '${cause.message}'"
        }
        val expectedMessage = when (BACKEND_UNDER_TEST) {
            "NATIVE" -> "There was an error during file or class initialization"
            else -> "Could not initialize class C"
        }
        if (e.message != expectedMessage) return "FAIL 2.3: message must be '$expectedMessage', was '${e.message}'"
    }

    val childEIIE = try {
        Child()
        return "FAIL 3.1: should throw"
    } catch (e: Error) {
        val cause = e.cause
        if (cause !is IllegalStateException) return "FAIL 3.2: cause must be IllegalStateException, was ${cause?.let { it::class }}"
        if (cause.message != "Parent.never") return "FAIL 3.3: message must be 'Parent.never', was '${cause.message}'"
        e
    }

    try {
        Child()
        return "FAIL 4.1: should throw"
    } catch (e: Error) {
        if (JDK_MAJOR_VERSION < 17 || BACKEND_UNDER_TEST == "NATIVE") {
            if (e.cause != null) return "FAIL 4.2.1: cause must be null, got ${e.cause}"
        } else {
            val cause = e.cause as? Error
            if (cause == null) return "FAIL 4.2.2: cause must be ExceptionInInitializerError, was ${e.cause}"
            if (cause.cause != null) return "FAIL 4.2.3: cause.cause must be null, was ${cause.cause}"
            val expectedMessage = childEIIE.toString()
            if (cause.message?.contains(expectedMessage) != true) return "FAIL 4.2.4: cause.message must contain '$expectedMessage', was '${cause.message}'"
        }
        val expectedMessage = when (BACKEND_UNDER_TEST) {
            "NATIVE" -> "There was an error during file or class initialization"
            else -> "Could not initialize class Child"
        }
        if (e.message != expectedMessage) return "FAIL 4.3: message must be '$expectedMessage', was '${e.message}'"
    }

    try {
        Parent()
        return "FAIL 5.1: should throw"
    } catch (e: Throwable) {
        if (JDK_MAJOR_VERSION < 17 || BACKEND_UNDER_TEST == "NATIVE") {
            if (e.cause != null) return "FAIL 5.2.1: cause must be null, got ${e.cause}"
        } else {
            val cause = e.cause as? Error
            if (cause == null) return "FAIL 5.2.2: cause must be ExceptionInInitializerError, was ${e.cause}"
            if (cause.cause != null) return "FAIL 5.2.3: cause.cause must be null, was ${cause.cause}"
            val expectedMessage = IllegalStateException("Parent.never").toString()
            if (cause.message?.contains(expectedMessage) != true) return "FAIL 5.2.4: cause.message must contain '$expectedMessage', was '${cause.message}'"
        }
        val expectedMessage = when (BACKEND_UNDER_TEST) {
            "NATIVE" -> "There was an error during file or class initialization"
            else -> "Could not initialize class Parent"
        }
        if (e.message != expectedMessage) return "FAIL 5.3: message must be '$expectedMessage', was '${e.message}'"
    }

    try {
        O.foo()
        return "FAIL 6.1: should throw"
    } catch (e: Error) {
        val cause = e.cause
        if (cause !is IllegalStateException) return "FAIL 6.2: cause must be IllegalStateException, was ${cause?.let { it::class }}"
        if (cause.message != "O.never") return "FAIL 6.3: message must be 'O.never', was '${cause.message}'"
    }

    try {
        O.foo()
        return "FAIL 7.1: should throw"
    } catch (e: Error) {
        if (JDK_MAJOR_VERSION < 17 || BACKEND_UNDER_TEST == "NATIVE") {
            if (e.cause != null) return "FAIL 7.2.1: cause must be null, got ${e.cause}"
        } else {
            val cause = e.cause as? Error
            if (cause == null) return "FAIL 7.2.2: cause must be ExceptionInInitializerError, was ${e.cause}"
            if (cause.cause != null) return "FAIL 7.2.3: cause.cause must be null, was ${cause.cause}"
            val expectedMessage = IllegalStateException("O.never").toString()
            if (cause.message?.contains(expectedMessage) != true) return "FAIL 7.2.4: cause.message must contain '$expectedMessage', was '${cause.message}'"
        }
        val expectedMessage = when (BACKEND_UNDER_TEST) {
            "NATIVE" -> "There was an error during file or class initialization"
            else -> "Could not initialize class O"
        }
        if (e.message != expectedMessage) return "FAIL 7.3: message must be '$expectedMessage', was '${e.message}'"
    }

    try {
        ThrowsErrorWithCompanion()
        return "FAIL 8.1: should throw"
    } catch (e: Error) {
        if (e.cause != null) return "FAIL 8.2: cause must be null, got ${e.cause}"
        if (e.message != "ThrowsErrorWithCompanion.never") return "FAIL 8.3: message must be 'ThrowsErrorWithCompanion.never', was '${e.message}'"
    }

    try {
        ThrowsErrorWithCompanion()
        return "FAIL 9.1: should throw"
    } catch (e: Error) {
        if (JDK_MAJOR_VERSION < 17 || BACKEND_UNDER_TEST == "NATIVE") {
            if (e.cause != null) return "FAIL 9.2.1: cause must be null, got ${e.cause}"
        } else {
            val cause = e.cause as? Error
            if (cause == null) return "FAIL 9.2.2: cause must be Error, was ${e.cause}"
            if (cause.cause != null) return "FAIL 9.2.3: cause.cause must be null, was ${cause.cause}"
            val expectedMessage = Error("ThrowsErrorWithCompanion.never").toString()
            if (cause.message?.contains(expectedMessage) != true) return "FAIL 9.2.4: cause.message must contain '$expectedMessage', was '${cause.message}'"
        }
        val expectedMessage = when (BACKEND_UNDER_TEST) {
            "NATIVE" -> "There was an error during file or class initialization"
            else -> "Could not initialize class ThrowsErrorWithCompanion"
        }
        if (e.message != expectedMessage) return "FAIL 9.3: message must be '$expectedMessage', was '${e.message}'"
    }


    try {
        ThrowsErrorObject.foo()
        return "FAIL 10.1: should throw"
    } catch (e: Error) {
        if (e.cause != null) return "FAIL 10.2: cause must be null, got ${e.cause}"
        if (e.message != "ThrowsErrorObject.never") return "FAIL 10.3: message must be 'ThrowsErrorObject.never', was '${e.message}'"
    }

    try {
        ThrowsErrorObject.foo()
        return "FAIL 11.1: should throw"
    } catch (e: Error) {
        if (JDK_MAJOR_VERSION < 17 || BACKEND_UNDER_TEST == "NATIVE") {
            if (e.cause != null) return "FAIL 11.2.1: cause must be null, got ${e.cause}"
        } else {
            val cause = e.cause as? Error
            if (cause == null) return "FAIL 11.2.2: cause must be Error, was ${e.cause}"
            if (cause.cause != null) return "FAIL 11.2.3: cause.cause must be null, was ${cause.cause}"
            val expectedMessage = Error("ThrowsErrorObject.never").toString()
            if (cause.message?.contains(expectedMessage) != true) return "FAIL 11.2.4: cause.message must contain '$expectedMessage', was '${cause.message}'"
        }
        val expectedMessage = when (BACKEND_UNDER_TEST) {
            "NATIVE" -> "There was an error during file or class initialization"
            else -> "Could not initialize class ThrowsErrorObject"
        }
        if (e.message != expectedMessage) return "FAIL 11.3: message must be '$expectedMessage', was '${e.message}'"
    }

    return "OK"
}
