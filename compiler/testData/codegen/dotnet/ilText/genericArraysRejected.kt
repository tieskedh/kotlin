package test.genericarrays.rejected

// Every declaration below remains outside the supported invariant concrete-array model. No
// fallback array-construction IL may be emitted.

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

fun nullablePrimitiveVarargs(vararg values: Int?): Int? = values[0]

fun nestedVarargs(vararg values: IntArray): IntArray = values[0]

fun nullPrimitive(size: Int): Array<Int?> = arrayOfNulls(size)

fun emptyPrimitive(): Array<Int> = emptyArray()

inline fun <reified T> initializedOpen(size: Int, value: T): Array<T> = Array(size) { value }

fun initializedNested(size: Int): Array<Array<String>> = Array(size) { arrayOf("x") }

fun iteratorAsStatement(values: Array<String>) {
    values.iterator()
}

fun main() {
}
