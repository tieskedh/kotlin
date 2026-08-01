// Unsupported generated-member representations reject the whole data class. Array shapes still
// obey their owning vector mapper, and no partial data-class members may survive in the output.
// Supported generic classes, arrays, constructor defaults, named nesting, and local closure-
// converted data classes are covered separately.

data class WithNullablePrimitiveArray(val values: Array<Int?>)

data class WithPrimitiveGenericArray(val values: Array<Int>)

fun main() {
    println("survives")
}
