// Mutable captures use the runtime shared cell. A non-capturing lambda composes with an
// explicit named local function. An inferred `::local` variable now keeps its KFunction view and
// can invoke the lifted target. Unrelated metadata and the entry point survive.

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

fun functionReferenceMixtureSupported(): Int {
    fun local(): Int = 4
    val reference = ::local

    return if (reference.name == "local") reference() else -1
}

class SurvivingLocalFunctionSibling {
    fun value(): Int = 29
}

fun main() {
    println(lambdaMixtureSupported())
    println(functionReferenceMixtureSupported())
    println(SurvivingLocalFunctionSibling().value())
}
