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

fun main() {
    val owner = GenericHolderOwner.create()
    println(owner.value)
    println(owner.reveal())
    println(GenericHolderOwner.marker)
    println(GenericHolderOwner.answer)
    println(GenericHolderOwner.echo("OK"))
    println(GenericHolderInterface.marker)
    println(GenericHolderInterface.answer)
    println(GenericHolderInterface.echo(44))
}
