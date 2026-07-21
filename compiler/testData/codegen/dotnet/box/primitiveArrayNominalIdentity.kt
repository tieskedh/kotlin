private fun kind(value: IntArray): Int = value.size

private fun kind(value: Array<Int>): Int = value.size + 10

private class ArrayPair(
    val specialized: IntArray,
    val generic: Array<Int>,
)

private fun firstNested(vararg values: IntArray): IntArray = values[0]

fun box(): String {
    val specialized = intArrayOf(1, 2)
    val generic = arrayOf(1, 2)

    if (kind(specialized) != 2) return "specialized overload"
    if (kind(generic) != 12) return "generic overload"

    val specializedIdentity: Any = specialized
    val specializedAlias: Any = specialized
    val genericIdentity: Any = generic
    if (specializedIdentity !== specializedAlias) return "wrapper identity"
    if (specializedIdentity === genericIdentity) return "nominal identity collapsed"

    specialized[0] = 41
    generic[0] = 42
    if (specialized[0] != 41 || generic[0] != 42) return "independent storage"

    val pair = ArrayPair(specialized, generic)
    if (pair.specialized !== specialized || pair.generic !== generic) return "field identity"

    val nullableSpecialized: IntArray? = specialized
    val nullableGeneric: Array<Int>? = generic
    if (nullableSpecialized !== specialized || nullableGeneric !== generic) return "nullable identity"

    val initialized = Array(3) { index -> index + 1 }
    if (initialized[0] != 1 || initialized[2] != 3) return "generic primitive initializer"

    val nestedPrimitive = arrayOf(specialized)
    if (nestedPrimitive[0] !== specialized) return "nested primitive wrapper"
    if (firstNested(*nestedPrimitive) !== specialized) return "nested primitive vararg"

    val nestedGeneric = Array(1) { arrayOf("nested") }
    if (nestedGeneric[0][0] != "nested") return "nested generic array"
    return "OK"
}
