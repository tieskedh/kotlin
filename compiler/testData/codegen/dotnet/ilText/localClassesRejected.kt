// Mutable captures require SharedVariablesLowering, and anonymous objects/local functions remain
// outside the first named-local-class slice. Their functions disappear independently; main and
// unrelated declarations survive.

fun mutableCaptureRejected(): Int {
    var value = 1

    class Local {
        fun value(): Int = value
    }

    value = 2
    return Local().value()
}

fun anonymousObjectRejected(): Int {
    val value = object {
        fun value(): Int = 2
    }
    return value.value()
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
