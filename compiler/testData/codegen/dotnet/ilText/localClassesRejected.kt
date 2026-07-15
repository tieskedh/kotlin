// Mutable captures require SharedVariablesLowering. Their functions disappear independently;
// main and unrelated declarations survive.

fun mutableCaptureRejected(): Int {
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
