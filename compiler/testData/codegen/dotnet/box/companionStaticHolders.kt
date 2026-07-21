// LANGUAGE: +CompanionBlocks +CompanionExtensions

class GenericHolderOwner<T> private constructor(val value: Int) {
    fun reveal(): Int = secret()

    companion {
        const val marker = 7

        val answer: Int
            get() = 42

        private fun secret(): Int = 11

        fun create(value: Int = 40): GenericHolderOwner<String> = GenericHolderOwner(value)

        fun <R> echo(value: R): R = value

        fun increment(value: Int): Int = value + 1
    }
}

interface GenericHolderInterface<T> {
    companion {
        const val marker = 9

        val answer: Int
            get() = 43

        fun <R> echo(value: R): R = value
    }
}

fun box(): String {
    val owner = GenericHolderOwner.create()
    if (owner.value != 40) return "FAIL: default=${owner.value}"
    if (owner.reveal() != 11) return "FAIL: private bridge"
    if (GenericHolderOwner.marker != 7) return "FAIL: class const"
    if (GenericHolderOwner.answer != 42) return "FAIL: class property"
    if (GenericHolderOwner.echo("OK") != "OK") return "FAIL: class generic method"
    if (GenericHolderInterface.marker != 9) return "FAIL: interface const"
    if (GenericHolderInterface.answer != 43) return "FAIL: interface property"
    if (GenericHolderInterface.echo(44) != 44) return "FAIL: interface generic method"

    val increment: (Int) -> Int = GenericHolderOwner::increment
    if (increment(44) != 45) return "FAIL: callable reference"
    return "OK"
}
