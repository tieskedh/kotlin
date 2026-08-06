package test.genericarrays.rejected

// Every declaration below except valueProjection remains outside the supported array model. A
// covariant view of an already allocated exact vector is now represented by System.Array; it does
// not allocate a fallback array or weaken the invariant carrier used by the original value.

open class Base

class Broken {
    fun <T> unsupported(values: Array<T?>): Array<T?> = values
}

class NullableElements<T>(val values: Array<T?>)

class EvictedElementField(val values: Array<Broken>)

fun evictedElement(values: Array<Broken>): Array<Broken> = values

fun contravariant(values: Array<in Base>) {
    values[0] = Base()
}

fun valueProjection(values: Array<Int>): Array<out Any> = values

fun <T> nullableTypeParameter(values: Array<T?>): T? = values[0]

inline fun <reified T> initializedOpen(size: Int, value: T): Array<T> = Array(size) { value }

fun <T> openCopy(values: Array<T>): Array<T> = values.copyOf()

fun <T> openResize(values: Array<T>, size: Int): Array<T?> = values.copyOf(size)

fun <T> openCopyInto(source: Array<T>, destination: Array<T>): Array<T> =
    source.copyInto(destination)

fun main() {
}
