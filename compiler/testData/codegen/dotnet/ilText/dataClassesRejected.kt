// Unsupported generated-member representations reject the whole data class. Generic equality
// cannot use CLR's reified generic isinst without changing Kotlin/JVM semantics; unsupported
// array shapes still obey their owning vector mapper; constructor defaults need a collision-safe
// constructor ABI; local closure-converted data classes remain outside this slice. No partial
// data-class members may survive in the output. Supported arrays and named nesting are covered
// separately.
data class Generic<T>(val value: T)

data class WithByteArray(val values: ByteArray)

data class WithPrimitiveGenericArray(val values: Array<Int>)

data class WithConstructorDefault(val value: Int = 1)

fun declaresLocal() {
    data class Local(val value: Int)
    println("unreachable " + Local(1))
}

fun main() {
    println("survives")
}
