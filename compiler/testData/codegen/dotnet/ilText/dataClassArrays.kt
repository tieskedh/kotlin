// fir2ir keeps array equality as ordinary reference equality, but routes generated data-class
// hashCode/toString members through the same synthetic content intrinsics used by the JVM backend.
// Pin both a primitive vector and a nullable-reference vector flowing into runtime-owned helpers.
data class ArrayRecord(
    val numbers: IntArray,
    val labels: Array<String?>,
)

fun main() {
    val value = ArrayRecord(intArrayOf(1, 2), arrayOf("a", null))
    println(value)
    println(value.hashCode())
    println(value == ArrayRecord(intArrayOf(1, 2), arrayOf("a", null)))
}
