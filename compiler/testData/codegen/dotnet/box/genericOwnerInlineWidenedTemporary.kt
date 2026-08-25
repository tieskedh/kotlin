// Kotlin inference may choose Any? for the covariant receiver of an inline helper even when the
// call originates on an exact generic owner. The inliner's immutable argument temporaries must
// retain that owner's natural CLR construction instead of fabricating Producer<object> or
// degrading the whole typed member body to the semantic carrier.

private interface InlineProducer<out T> {
    fun produce(): T
}

private inline fun <T> InlineProducer<T>.indexOfFirst(
    predicate: (T) -> Boolean,
): Int = if (predicate(produce())) 0 else -1

private class InlineSelfView<T>(private val value: T) : InlineProducer<T> {
    override fun produce(): T = value

    fun indexOf(element: T): Int = indexOfFirst { it == element }
}

fun box(): String {
    val ints = InlineSelfView(42)
    if (ints.indexOf(42) != 0 || ints.indexOf(43) != -1) return "value self-view"

    val strings = InlineSelfView("inline")
    if (strings.indexOf("inline") != 0 || strings.indexOf("other") != -1) {
        return "reference self-view"
    }

    // A source-declared wide variable remains a semantic Kotlin view. In particular, its
    // physical carrier must not be pinned to the first exact value by the temporary fast path.
    var widened: InlineProducer<Any?> = ints
    if (widened !== ints || widened.produce() != 42) return "value widened view"
    widened = strings
    if (widened !== strings || widened.produce() != "inline") return "reference widened view"

    return "OK"
}
