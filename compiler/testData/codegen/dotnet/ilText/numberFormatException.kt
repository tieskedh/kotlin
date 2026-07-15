// NumberFormatException has an exact Kotlin.Runtime identity whose physical parent is the mapped
// System.ArgumentException. This preserves exact catches, the Kotlin IllegalArgumentException
// value/catch edge, Throwable widening, nullable default-message behavior, and virtual message
// dispatch without pretending that foreign System.FormatException has the same identity.
package test

fun asArgument(message: String?): IllegalArgumentException = NumberFormatException(message)

fun messageThroughParent(message: String?): String? = asArgument(message).message

fun exactCatch(message: String?): String? = try {
    throw NumberFormatException(message)
} catch (failure: NumberFormatException) {
    failure.message
}

fun parentCatch(): String = try {
    throw NumberFormatException()
} catch (_: IllegalArgumentException) {
    "argument"
}

fun asThrowable(): Throwable = NumberFormatException("root")

fun hasNoDefaultState(): Boolean {
    val failure = NumberFormatException()
    return failure.message == null && failure.cause == null
}
