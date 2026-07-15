// Shared mutable captures now compose with anonymous-object closure conversion. Unsupported base
// classes still reject only the containing function; unrelated metadata and the entry point
// survive.

interface RejectedAnonymousValue {
    fun value(): Int
}

fun mutableAnonymousCaptureSupported(): Int {
    var value = 1
    val source: RejectedAnonymousValue = object : RejectedAnonymousValue {
        override fun value(): Int = value
    }
    value = 2
    return source.value()
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
