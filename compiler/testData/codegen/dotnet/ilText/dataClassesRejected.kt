// Unsupported generated-member representations reject the whole data class. Generic equality
// cannot use CLR's reified generic isinst without changing Kotlin/JVM semantics; arrays require
// content hash/string intrinsics; constructor defaults need a collision-safe constructor ABI;
// and nested/local metadata shapes remain outside this first slice. No partial data-class members
// may survive in the output.
data class Generic<T>(val value: T)

data class WithArray(val values: IntArray)

data class WithConstructorDefault(val value: Int = 1)

class Owner {
    data class Nested(val value: Int)
}

fun declaresLocal() {
    data class Local(val value: Int)
    println("unreachable " + Local(1))
}

fun main() {
    println("survives")
}
