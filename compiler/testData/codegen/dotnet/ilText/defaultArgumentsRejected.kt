// Reference nullability erases on the CLR. The original constructors therefore collide before
// their default stubs matter, and the constructor-identity gate rejects the class whole. The
// runtime marker keeps an otherwise valid default stub distinct from user-declared constructors.
class ErasedConstructorClash {
    constructor(value: String = "")
    constructor(value: String?)
}

fun main() {
}
