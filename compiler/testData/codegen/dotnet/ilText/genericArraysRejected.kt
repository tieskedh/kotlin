package test.genericarrays.rejected

// A supported non-capturing initializer lambda is lowered independently before its unsupported
// Array(size, init) call is rejected. Its unreferenced callable class may therefore remain while
// the containing function is absent; no fallback array-construction IL is emitted.

open class Base

class Broken {
    fun unsupported(): Float = 1.0f
}

class PrimitiveElements(val values: Array<Int>)

class NullableElements<T>(val values: Array<T?>)

class EvictedElementField(val values: Array<Broken>)

fun evictedElement(values: Array<Broken>): Array<Broken> = values

fun primitiveElements(values: Array<Int>): Int = values[0]

fun nullablePrimitiveElements(values: Array<Int?>): Int? = values[0]

fun nested(values: Array<Array<String>>): Array<String> = values[0]

fun primitiveNested(values: Array<IntArray>): IntArray = values[0]

fun projected(values: Array<out Base>): Base = values[0]

fun contravariant(values: Array<in Base>) {
    values[0] = Base()
}

fun star(values: Array<*>): Any? = values[0]

fun <T> nullableTypeParameter(values: Array<T?>): T? = values[0]

fun initialized(size: Int): Array<String> = Array(size) { "x" }

fun spread(values: Array<String>): Array<String> = arrayOf(*values)

fun nullPrimitive(size: Int): Array<Int?> = arrayOfNulls(size)

fun emptyPrimitive(): Array<Int> = emptyArray()

fun iteratorAsStatement(values: Array<String>) {
    values.iterator()
}

fun main() {
}
