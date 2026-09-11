// MODULE: lib
// FILE: iterator.kt
package array.iterator.opaque.result

fun starIterator(values: Array<*>): Iterator<*> = values.iterator()
fun opaqueIterator(values: Array<*>): Any = values.iterator()
fun exactOpaqueIterator(values: Array<String>): Any = values.iterator()
fun primitiveOpaqueIterator(values: IntArray): Any = values.iterator()
fun <T> projectedIterator(values: Array<out T>): Iterator<*> = values.iterator()
fun starIterable(values: Array<*>): Iterable<*> = values.asIterable()
fun opaqueIterable(values: Array<String>): Any = values.asIterable()
fun primitiveOpaqueIterable(values: IntArray): Any = values.asIterable()

// MODULE: main(lib)
// FILE: main.kt
package array.iterator.opaque.result

private class Item(val value: Int)

private fun exhaust(iterator: Iterator<*>): Boolean {
    if (iterator.hasNext()) return false
    try {
        iterator.next()
        return false
    } catch (_: NoSuchElementException) {
        return !iterator.hasNext()
    }
}

fun box(): String {
    val values = arrayOf(3, 5)
    val iterator = starIterator(values)
    values[0] = 7
    if (!iterator.hasNext() || iterator.next() != 7 || iterator.next() != 5 || !exhaust(iterator)) return "value mutation"
    val nullable = starIterator(arrayOf<Int?>(null, 11))
    if (nullable.next() != null || nullable.next() != 11 || !exhaust(nullable)) return "nullable value"
    val item = Item(13)
    val opaque = opaqueIterator(arrayOf(item))
    val recovered = opaque as Iterator<*>
    if (recovered !== opaque || recovered.next() !== item || !exhaust(recovered)) return "reference identity"
    val exact = exactOpaqueIterator(arrayOf("exact")) as Iterator<*>
    if (exact.next() != "exact" || !exhaust(exact)) return "exact vector"
    val primitive = primitiveOpaqueIterator(intArrayOf(17)) as Iterator<*>
    if (primitive.next() != 17 || !exhaust(primitive)) return "primitive wrapper"
    val projected = projectedIterator(arrayOf(item))
    if (projected.next() !== item || !exhaust(projected)) return "projected vector"
    if (!exhaust(starIterator(emptyArray<Int>()))) return "empty"
    if (starIterator(values) === starIterator(values)) return "independent iterators"
    val iterable = starIterable(values)
    values[0] = 19
    if (iterable.iterator().next() != 19) return "iterable mutation"
    val referenceIterable = opaqueIterable(arrayOf("iterable")) as Iterable<*>
    if (referenceIterable.iterator().next() != "iterable") return "reference iterable"
    val primitiveIterable = primitiveOpaqueIterable(intArrayOf(23)) as Iterable<*>
    if (primitiveIterable.iterator().next() != 23) return "primitive iterable"
    if (starIterable(emptyArray<Int>()).iterator().hasNext()) return "empty iterable"
    return "OK"
}
