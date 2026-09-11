// DOTNET_GENERIC_OWNER_PROJECTED_ALLOCATION_PROBE
// MODULE: lib
// FILE: capture.kt
package generic.owner.projected.callable

interface Source<out T> { fun iterator(): Iterator<T> }
interface PredicateSource<out T> { fun accept(candidate: @UnsafeVariance T): Boolean }

private class Single<T>(private val value: T) : Iterator<T> {
    override fun hasNext(): Boolean = true
    override fun next(): T = value
}

private var last: Any? = null
fun lastPredicate(): Any? = last
value class Id(val raw: Int)

private fun <T> evaluate(value: T, predicate: (T) -> Boolean): Boolean {
    last = predicate
    return predicate(value)
}

fun <T> capture(values: Array<out T>): Source<T> = object : Source<T> {
    override fun iterator(): Iterator<T> {
        if (!evaluate(values[0]) { candidate -> candidate == values[0] }) {
            throw IllegalStateException("predicate")
        }
        return Single(values[0])
    }
}

fun <T> predicate(values: Array<out T>): PredicateSource<T> = object : PredicateSource<T> {
    override fun accept(candidate: @UnsafeVariance T): Boolean =
        evaluate(candidate) { value -> value == values[0] }
}

// MODULE: main(lib)
// FILE: main.kt
package generic.owner.projected.callable

fun box(): String {
    val values = arrayOf(42)
    val source = capture(values)
    if (source.iterator().next() != 42) return "value"
    val widened: Source<Any?> = source
    if (widened !== source || widened.iterator().next() != 42) return "widened"
    values[0] = 73
    if (source.iterator().next() != 73 || widened.iterator().next() != 73) return "mutation"
    if (capture(arrayOf("text")).iterator().next() != "text") return "reference"
    if (capture(arrayOf<Int?>(null)).iterator().next() != null) return "nullable"
    if (capture(arrayOf(Id(19))).iterator().next().raw != 19) return "nominal"
    val exact = predicate(values)
    val broad: PredicateSource<Any?> = exact
    if (broad !== exact || broad.accept("not an Int") || !broad.accept(73)) return "broad input"
    var selected: PredicateSource<Any?> = broad
    if (selected.accept("wrong")) return "first mutable view"
    selected = predicate(arrayOf("text"))
    if (!selected.accept("text") || selected.accept(73)) return "mutable view"
    return "OK"
}
