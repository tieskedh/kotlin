// Mutable captures now use the runtime shared cell. A non-capturing lambda composes with an
// explicit named local function. An inferred `::local` variable still has KFunction type and
// remains rejected until the reflection ABI exists; its independently valid generated Function0
// class and lifted target can remain unreferenced. Unrelated metadata and the entry point survive.

fun mutableLocalFunctionCaptureSupported(): Int {
    var value = 1

    fun local(): Int = value

    value = 2
    return local()
}

fun lambdaMixtureSupported(): Int {
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
    println(lambdaMixtureSupported())
    println(SurvivingLocalFunctionSibling().value())
}
