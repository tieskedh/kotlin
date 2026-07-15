// Kotlin-created Error values use exact Kotlin.Runtime identity below System.Exception. The CLR
// has no faithful fatal-error root, so this pins only exact construction/catch, Throwable storage,
// all four mature constructors, nullable message/cause behavior, and the already-accepted
// Throwable/Exception physical collapse. Foreign CLR fatal exceptions stay distinct by decision.
package test

fun asThrowable(message: String?): Throwable = Error(message)

fun exactCatch(message: String?): String? = try {
    throw Error(message)
} catch (failure: Error) {
    failure.message
}

fun throwableCatch(): String = try {
    throw Error()
} catch (_: Throwable) {
    "throwable"
}

fun collapsedExceptionCatch(): String = try {
    throw Error()
} catch (_: Exception) {
    "exception"
}

fun withCause(cause: Throwable?): Error = Error("message", cause)

fun fromCause(cause: Throwable?): Error = Error(cause)

fun hasNoDefaultState(): Boolean {
    val failure = Error()
    return failure.message == null && failure.cause == null
}
