// DOTNET_GENERIC_OWNER_PHYSICAL_VALUE_PLACEMENT_COMPILER_ALIAS_PROBE

// Kotlin inference may choose Any? for the covariant receiver of an inline helper even when the
// call originates on an exact generic owner. The inliner's immutable argument temporaries must
// retain that owner's natural CLR construction instead of fabricating Producer<object> or
// degrading the whole typed member body to the semantic carrier.

interface InlineProducer<out T> {
    fun produce(): T
}

private inline fun <T> InlineProducer<T>.indexOfFirst(
    predicate: (T) -> Boolean,
): Int = if (predicate(produce())) 0 else -1

private class InlineSelfView<T>(private val value: T) : InlineProducer<T> {
    override fun produce(): T = value

    fun indexOf(element: T): Int = indexOfFirst { it == element }

    fun sourceAliasMatches(element: T): Boolean {
        val sourceNaturalAlias: InlineProducer<T> = this
        return sourceNaturalAlias.produce() == element
    }

    fun wideAliasMatches(element: T): Boolean {
        val sourceWideAlias: InlineProducer<Any?> = this
        return sourceWideAlias.produce() == element
    }

    fun nullableAliasMatches(element: T): Boolean {
        val sourceNullableAlias: InlineProducer<T?> = this
        return sourceNullableAlias.produce() == element
    }
}

fun box(): String {
    val ints = InlineSelfView(42)
    if (ints.indexOf(42) != 0 || ints.indexOf(43) != -1) return "value self-view"
    if (!ints.sourceAliasMatches(42) || ints.sourceAliasMatches(43)) return "value source alias"
    if (!ints.wideAliasMatches(42) || ints.wideAliasMatches(43)) return "value wide alias"
    if (!ints.nullableAliasMatches(42) || ints.nullableAliasMatches(43)) return "value nullable alias"

    val strings = InlineSelfView("inline")
    if (strings.indexOf("inline") != 0 || strings.indexOf("other") != -1) {
        return "reference self-view"
    }
    if (!strings.sourceAliasMatches("inline") || strings.sourceAliasMatches("other")) {
        return "reference source alias"
    }
    if (!strings.wideAliasMatches("inline") || strings.wideAliasMatches("other")) {
        return "reference wide alias"
    }
    if (!strings.nullableAliasMatches("inline") || strings.nullableAliasMatches("other")) {
        return "reference nullable alias"
    }

    // A source-declared wide variable remains a semantic Kotlin view. In particular, its
    // physical carrier must not be pinned to the first exact value by the temporary fast path.
    var widened: InlineProducer<Any?> = ints
    if (widened !== ints || widened.produce() != 42) return "value widened view"
    widened = strings
    if (widened !== strings || widened.produce() != "inline") return "reference widened view"

    return "OK"
}
