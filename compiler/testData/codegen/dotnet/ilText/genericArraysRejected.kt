package test.genericarrays.rejected

// Most declarations below remain outside the supported array model. A covariant view of an
// already allocated exact vector is represented by System.Array; it does not allocate a fallback
// array or weaken the invariant carrier used by the original value. Open copyOf is also supported:
// it allocates from the source vector's exact runtime component type and returns the same open
// Array<T> carrier. Open nullable element positions and writable projected arrays remain rejected.

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

// Reified substitution makes the initialized open array valid at Kotlin call sites. Its Array<T>
// physical remainder is representable and therefore emitted only as a non-public throwing stub.
inline fun <reified T> initializedOpen(size: Int, value: T): Array<T> = Array(size) { value }

fun <T> openCopy(values: Array<T>): Array<T> = values.copyOf()

fun <T> openResize(values: Array<T>, size: Int): Array<T?> = values.copyOf(size)

@Suppress("UNCHECKED_CAST")
fun <T> nullableLocalWrite(values: Array<T>) {
    val nullableValues = if (values.isEmpty()) values as Array<T?> else values as Array<T?>
    nullableValues[0] = null
}

fun <T> openCopyInto(source: Array<T>, destination: Array<T>): Array<T> =
    source.copyInto(destination)

fun main() {
}
