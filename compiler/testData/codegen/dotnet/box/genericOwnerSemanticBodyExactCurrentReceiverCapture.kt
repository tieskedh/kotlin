// A semantic result cache may store an object whose generated generic constructor captures only
// the exact current owner. The construction retains that carrier without narrowing broad input.

abstract class CapturingOwner<out K, out V> {
    private var cached: Iterator<K>? = null

    protected abstract fun readCurrent(): K

    protected abstract fun readMarker(): V

    fun replace(producer: Iterator<@UnsafeVariance K>) {
        cached = producer
    }

    fun producer(): Iterator<K> {
        val current = cached
        if (current != null) return current
        cached = object : Iterator<K> {
            override fun hasNext(): Boolean = true
            override fun next(): K = this@CapturingOwner.readCurrent()
        }
        return cached!!
    }
}

private class FixedCapturingOwner<K, V>(private val key: K, private val marker: V) :
    CapturingOwner<K, V>() {
    override fun readCurrent(): K = key

    override fun readMarker(): V = marker
}

private class FixedIterator<T>(private val value: T) : Iterator<T> {
    override fun hasNext(): Boolean = true
    override fun next(): T = value
}

fun box(): String {
    val valueOwner = FixedCapturingOwner(42, "marker")
    val widenedValue: CapturingOwner<Any?, Any?> = valueOwner
    if (widenedValue !== valueOwner) return "value owner identity"
    val valueProducer = widenedValue.producer()
    if (valueProducer.next() != 42) return "value result"
    if (widenedValue.producer() !== valueProducer) return "value cache identity"

    val referenceOwner = FixedCapturingOwner("typed", 73)
    val widenedReference: CapturingOwner<Any?, Any?> = referenceOwner
    if (widenedReference !== referenceOwner) return "reference owner identity"
    val referenceProducer = widenedReference.producer()
    if (referenceProducer.next() != "typed") return "reference result"
    if (widenedReference.producer() !== referenceProducer) return "reference cache identity"

    val replacedOwner: CapturingOwner<Any?, Any?> = FixedCapturingOwner(42, "marker")
    val replacement = FixedIterator("broad")
    replacedOwner.replace(replacement)
    if (replacedOwner.producer() !== replacement) return "broad cache identity"
    if (replacedOwner.producer().next() != "broad") return "broad cache result"

    return "OK"
}
