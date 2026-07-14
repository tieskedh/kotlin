package test.arrays.rejected

// Each declaration below resolves in the frontend and is then skipped with a feature-specific
// backend warning. None may silently acquire fallback IL.
fun genericArray(size: Int): Array<Int> = Array(size) { it }

fun genericNullableArray(size: Int): Array<Int?> = Array(size) { null }

fun initialized(size: Int): IntArray = IntArray(size) { it }

fun spread(values: IntArray): IntArray = intArrayOf(*values)

fun iteratorAsAny(values: IntArray): Any? = values.iterator()

fun unsupportedElements(size: Int): ByteArray = ByteArray(size)

fun unsupportedShortElements(size: Int): ShortArray = ShortArray(size)

fun unsupportedFloatElements(size: Int): FloatArray = FloatArray(size)

fun main() {
}
