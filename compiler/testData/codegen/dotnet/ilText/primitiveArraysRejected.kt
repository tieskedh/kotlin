package test.arrays.rejected

// Each declaration below resolves in the frontend and is then skipped with a feature-specific
// backend warning. None may silently acquire fallback IL. Callable lowering happens before the
// array-intrinsic gate, so a valid non-capturing initializer lambda can leave an unreferenced,
// independently valid callable class even though the function containing its rejected array
// construction is absent.
fun genericArray(size: Int): Array<Int> = Array(size) { it }

fun genericNullableArray(size: Int): Array<Int?> = Array(size) { null }

fun initialized(size: Int): IntArray = IntArray(size) { it }

fun unsupportedVarargs(vararg values: Byte): Int = values.size

fun unsupportedShortVarargs(vararg values: Short): Int = values.size

fun unsupportedFloatVarargs(vararg values: Float): Int = values.size

fun iteratorAsAny(values: IntArray): Any? = values.iterator()

fun unsupportedElements(size: Int): ByteArray = ByteArray(size)

fun unsupportedShortElements(size: Int): ShortArray = ShortArray(size)

fun unsupportedFloatElements(size: Int): FloatArray = FloatArray(size)

fun main() {
}
