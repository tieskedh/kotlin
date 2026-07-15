// Shared mutable captures and explicit local functions remain outside anonymous-object closure
// conversion. Unsupported base classes reject only the containing function; unrelated metadata
// and the entry point survive.

interface RejectedAnonymousValue {
    fun value(): Int
}

fun mutableAnonymousCaptureRejected(): Int {
    var value = 1
    val source: RejectedAnonymousValue = object : RejectedAnonymousValue {
        override fun value(): Int = value
    }
    value = 2
    return source.value()
}

fun localFunctionAnonymousMixtureRejected(): Int {
    fun localValue(): Int = 3
    val source: RejectedAnonymousValue = object : RejectedAnonymousValue {
        override fun value(): Int = 4
    }
    return localValue() + source.value()
}

fun exceptionAnonymousRejected(): Int {
    val failure = object : IllegalStateException("bad") {}
    return if (failure.message == "bad") 1 else 0
}

class SurvivingAnonymousSibling {
    fun value(): Int = 23
}

fun main() {
    println(SurvivingAnonymousSibling().value())
}
