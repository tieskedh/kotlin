package test.genericarrays.rejected

// Every declaration below remains outside the supported invariant concrete-array model. No
// fallback array-construction IL may be emitted.

open class Base

class Broken {
    fun unsupported(): Float = 1.0f
}

class NullableElements<T>(val values: Array<T?>)

class EvictedElementField(val values: Array<Broken>)

fun evictedElement(values: Array<Broken>): Array<Broken> = values

fun nullablePrimitiveElements(values: Array<Int?>): Int? = values[0]

fun projected(values: Array<out Base>): Base = values[0]

fun contravariant(values: Array<in Base>) {
    values[0] = Base()
}

fun star(values: Array<*>): Any? = values[0]

fun <T> nullableTypeParameter(values: Array<T?>): T? = values[0]

fun nullablePrimitiveVarargs(vararg values: Int?): Int? = values[0]

fun nullPrimitive(size: Int): Array<Int?> = arrayOfNulls(size)

inline fun <reified T> initializedOpen(size: Int, value: T): Array<T> = Array(size) { value }

fun <T> openCopy(values: Array<T>): Array<T> = values.copyOf()

fun <T> openResize(values: Array<T>, size: Int): Array<T?> = values.copyOf(size)

fun <T> openCopyInto(source: Array<T>, destination: Array<T>): Array<T> =
    source.copyInto(destination)

class CustomIntIterator : IntIterator() {
    override fun hasNext(): Boolean = false
    override fun nextInt(): Int = 0
}

fun main() {
}
