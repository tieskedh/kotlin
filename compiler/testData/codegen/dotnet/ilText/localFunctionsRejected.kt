// Shared mutable captures, lambdas, and function references remain outside the explicit named
// local-function slice. Their containing functions disappear independently; unrelated metadata
// and the entry point survive.

fun mutableLocalFunctionCaptureRejected(): Int {
    var value = 1

    fun local(): Int = value

    value = 2
    return local()
}

fun lambdaMixtureRejected(): Int {
    fun local(): Int = 2
    val lambda = { 3 }

    return local() + lambda()
}

fun functionReferenceMixtureRejected(): Int {
    fun local(): Int = 4
    val reference = ::local

    return reference()
}

class SurvivingLocalFunctionSibling {
    fun value(): Int = 29
}

fun main() {
    println(SurvivingLocalFunctionSibling().value())
}
