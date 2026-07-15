// Interface defaults retain the abstract Framework-compatible interface instead of introducing
// a body-bearing interface method. The omitted caller below disappears while its declarations
// survive. Constructor defaults are covered by their dedicated positive tests.
interface InterfaceDefault {
    fun value(number: Int = 2): Int
}

class InterfaceDefaultImpl : InterfaceDefault {
    override fun value(number: Int): Int = number
}

// Reference nullability erases on the CLR. The original constructors therefore collide before
// their default stubs matter, and the constructor-identity gate rejects the class whole. The
// runtime marker keeps an otherwise valid default stub distinct from user-declared constructors.
class ErasedConstructorClash {
    constructor(value: String = "")
    constructor(value: String?)
}

fun omittedInterface(value: InterfaceDefault): Int = value.value()

fun main() {
    println(InterfaceDefaultImpl().value(8))
}
