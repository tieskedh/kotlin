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

private interface MixedCapturedProducer<out T> {
    fun read(): T
}

private class MixedCapturedProducerValue<T>(private val value: T) : MixedCapturedProducer<T> {
    override fun read(): T = value
}

private interface MixedCapturedView<out T> {
    fun read(): T
    fun ownerValue(): T
    fun capturedProducer(): MixedCapturedProducer<T>
}

// The generated object needs the exact current-owner T for its TypeSpec and owner capture, while
// producer is a separate semantic capture: at T = Any? it may still physically be Producer<Int>.
private class MixedCaptureOwner<out T>(private val value: T) {
    private fun current(): T = value

    fun capture(
        producer: MixedCapturedProducer<@UnsafeVariance T>,
    ): MixedCapturedView<T> = object : MixedCapturedView<T> {
        override fun read(): T = producer.read()
        override fun ownerValue(): T = this@MixedCaptureOwner.current()
        override fun capturedProducer(): MixedCapturedProducer<T> = producer
    }
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

    val mixedOwner = MixedCaptureOwner<Any?>("owner")
    val physicalProducer: MixedCapturedProducer<Int> = MixedCapturedProducerValue(79)
    val widenedProducer: MixedCapturedProducer<Any?> = physicalProducer
    val mixedView = try {
        mixedOwner.capture(widenedProducer)
    } catch (_: ClassCastException) {
        return "mixed capture narrowed constructor input"
    }
    if (mixedView.ownerValue() != "owner" || mixedView.read() != 79) {
        return "mixed capture values"
    }
    if (mixedView.capturedProducer() !== physicalProducer ||
        mixedView.capturedProducer() !== widenedProducer
    ) {
        return "mixed capture producer identity"
    }

    return "OK"
}
