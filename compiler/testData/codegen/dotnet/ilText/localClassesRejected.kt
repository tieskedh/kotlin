// Mutable captures now use the runtime shared cell and survive. This file retains the historical
// rejection-boundary sibling to pin that newly supported shape independently.

fun mutableCaptureSupported(): Int {
    var value = 1

    class Local {
        fun value(): Int = value
    }

    value = 2
    return Local().value()
}

class SurvivingLocalSibling {
    fun value(): Int = 17
}

fun main() {
    println(SurvivingLocalSibling().value())
}
