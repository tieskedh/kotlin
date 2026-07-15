// Mutable captures require SharedVariablesLowering, and explicit local functions remain outside
// the local-declaration slice. Their functions disappear independently; main and unrelated
// declarations survive.

fun mutableCaptureRejected(): Int {
    var value = 1

    class Local {
        fun value(): Int = value
    }

    value = 2
    return Local().value()
}

fun localFunctionMixtureRejected(): Int {
    fun localValue(): Int = 3

    class Local {
        fun value(): Int = 4
    }

    return localValue() + Local().value()
}

class SurvivingLocalSibling {
    fun value(): Int = 17
}

fun main() {
    println(SurvivingLocalSibling().value())
}
