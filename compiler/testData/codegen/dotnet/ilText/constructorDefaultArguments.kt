// Constructor defaults follow the common/JVM mask algorithm, with a runtime-owned marker after
// the masks. The marker keeps the synthetic primary constructor distinct from the real two-Int
// secondary constructor whose trailing parameter would otherwise look exactly like a mask.
class Defaulted(val value: Int = 7) {
    constructor(value: Int, mask: Int) : this(value + mask * 100)
}

fun defaultValue(): Int = Defaulted().value

fun explicitValue(): Int = Defaulted(2).value

fun collisionValue(): Int = Defaulted(2, 3).value

fun main() {
    println(defaultValue())
    println(explicitValue())
    println(collisionValue())
}
