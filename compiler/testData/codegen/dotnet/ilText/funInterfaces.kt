fun interface IntTransform {
    fun transform(value: Int): Int
}

fun interface GenericTransform<T> {
    fun transform(value: T): T
}

fun createTransform(function: (Int) -> Int): IntTransform = IntTransform(function)

fun createGenericTransform(): GenericTransform<String> = GenericTransform { it }

fun main() {
    println(createTransform { it + 1 }.transform(41))
    println(createGenericTransform().transform("OK"))
}
