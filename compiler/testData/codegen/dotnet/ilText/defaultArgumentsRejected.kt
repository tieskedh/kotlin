// Masked defaults cover ordinary top-level and class-member functions only. Constructor calls
// retain the existing omitted-argument rejection until a collision-safe marker ABI exists, and
// interface defaults retain the abstract Framework-compatible interface instead of introducing
// a body-bearing interface method. The two callers below disappear; their declarations survive.
class ConstructorDefault(val value: Int = 1)

interface InterfaceDefault {
    fun value(number: Int = 2): Int
}

class InterfaceDefaultImpl : InterfaceDefault {
    override fun value(number: Int): Int = number
}

fun omittedConstructor(): Int = ConstructorDefault().value

fun omittedInterface(value: InterfaceDefault): Int = value.value()

fun main() {
    println(ConstructorDefault(7).value + InterfaceDefaultImpl().value(8))
}
