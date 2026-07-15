package test.arrays.rejected

// Each declaration below resolves in the frontend and is then skipped with a feature-specific
// backend warning. None may silently acquire fallback IL.
fun genericArray(size: Int): Array<Int> = Array(size) { it }

fun genericNullableArray(size: Int): Array<Int?> = Array(size) { null }

fun unsupportedVarargs(vararg values: Byte): Int = values.size

fun unsupportedShortVarargs(vararg values: Short): Int = values.size

fun unsupportedFloatVarargs(vararg values: Float): Int = values.size

fun unsupportedElements(size: Int): ByteArray = ByteArray(size)

fun unsupportedShortElements(size: Int): ShortArray = ShortArray(size)

fun unsupportedFloatElements(size: Int): FloatArray = FloatArray(size)

fun unsupportedInitializedElements(size: Int): ByteArray = ByteArray(size) { it.toByte() }

fun main() {
}
