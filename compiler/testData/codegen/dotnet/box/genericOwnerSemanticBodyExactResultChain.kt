// A semantic body may accept a broad candidate while values produced exclusively from its exact
// current receiver retain their natural CLR constructions through locals and nested calls.

private class SingleMutableEntry<K, V>(
    override val key: K,
    private var valueState: V,
) : MutableMap.MutableEntry<K, V> {
    override val value: V get() = valueState

    override fun setValue(newValue: V): V {
        val previous = valueState
        valueState = newValue
        return previous
    }
}

private class SingleMutableIterator<T>(private val value: T) : MutableIterator<T> {
    private var consumed: Boolean = false

    override fun hasNext(): Boolean = !consumed

    override fun next(): T {
        if (consumed) throw NoSuchElementException()
        consumed = true
        return value
    }

    override fun remove() {
        if (!consumed) throw IllegalStateException("next was not called")
    }
}

private abstract class EntryOwner<out K, out V> {
    protected abstract fun entryIterator():
        MutableIterator<MutableMap.MutableEntry<@UnsafeVariance K, @UnsafeVariance V>>

    fun remove(candidate: @UnsafeVariance K): V? {
        val iterator = entryIterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (candidate == entry.key) {
                val value = entry.value
                iterator.remove()
                return value
            }
        }
        return null
    }
}

private class SingleEntryOwner<K, V>(key: K, value: V) : EntryOwner<K, V>() {
    private val entry = SingleMutableEntry(key, value)

    override fun entryIterator(): MutableIterator<MutableMap.MutableEntry<K, V>> =
        SingleMutableIterator(entry)
}

fun box(): String {
    val valueOwner = SingleEntryOwner(42, "typed")
    val widenedValue: EntryOwner<Any?, Any?> = valueOwner
    if (widenedValue !== valueOwner) return "value identity"
    if (widenedValue.remove("wrong") != null) return "broad value miss"

    val matchingValue: EntryOwner<Any?, Any?> = SingleEntryOwner(42, "typed")
    if (matchingValue.remove(42) != "typed") return "exact value result chain"

    val referenceOwner = SingleEntryOwner("key", 73)
    val widenedReference: EntryOwner<Any?, Any?> = referenceOwner
    if (widenedReference !== referenceOwner) return "reference identity"
    if (widenedReference.remove("key") != 73) return "exact reference result chain"

    return "OK"
}
