// DOTNET_GENERIC_OWNER_FOREIGN_OVERRIDE_SEPARATE_PROBE

// MODULE: lib
// FILE: lib.kt

public open class RehearsalSeparateStore<out T>(initial: T) {
    private var value: T = initial

    public open fun read(): T = value

    public fun write(value: @UnsafeVariance T) {
        this.value = value
    }
}

public class RehearsalSeparateReader {
    public fun read(store: RehearsalSeparateStore<Any?>): Any? = store.read()
}

public interface RehearsalSeparateProducer<out T> {
    public fun produce(): T
}

public class RehearsalSeparateProducerReader {
    public fun read(producer: RehearsalSeparateProducer<Any?>): Any? = producer.produce()

    public fun same(producer: RehearsalSeparateProducer<Any?>, expected: Any?): Boolean =
        producer === expected
}

// MODULE: middle(lib)
// FILE: middle.kt

public open class RehearsalSeparateKotlinOverrideStore<T>(initial: T) :
    RehearsalSeparateStore<T>(initial) {
    public override fun read(): T = super.read()
}

public interface RehearsalSeparateChildProducer<out T> : RehearsalSeparateProducer<T>

public class RehearsalSeparateProducerValue<T>(private val value: T) :
    RehearsalSeparateProducer<T> {
    public override fun produce(): T = value
}

public class RehearsalSeparateChildProducerValue<T>(private val value: T) :
    RehearsalSeparateChildProducer<T> {
    public override fun produce(): T = value
}

// MODULE: main(middle)
// FILE: main.kt

fun box(): String {
    val store = RehearsalSeparateKotlinOverrideStore("kotlin-middle")
    if (RehearsalSeparateReader().read(store) != "kotlin-middle") {
        return "fail: separate Kotlin override"
    }

    val exact = RehearsalSeparateKotlinOverrideStore(11)
    val widened: RehearsalSeparateStore<Any?> = exact
    widened.write("semantic")
    if (RehearsalSeparateReader().read(widened) != "semantic") {
        return "fail: separate raw widened read"
    }
    try {
        exact.read() + 1
        return "fail: separate typed incompatible read"
    } catch (_: ClassCastException) {
        // Only this actual typed use is a checked boundary.
    }
    widened.write(19)
    if (exact.read() != 19) return "fail: separate compatible recovery"

    val exactProducer: RehearsalSeparateProducer<Int> = RehearsalSeparateProducerValue(31)
    if (exactProducer.produce() != 31) return "fail: separate exact producer"
    val broadProducer: RehearsalSeparateProducer<Any?> = exactProducer
    if (RehearsalSeparateProducerReader().read(broadProducer) != 31) {
        return "fail: separate broad producer"
    }
    if (broadProducer !== exactProducer) return "fail: separate producer identity"

    val exactChild: RehearsalSeparateChildProducer<Int> =
        RehearsalSeparateChildProducerValue(47)
    if (exactChild.produce() != 47) return "fail: separate exact child producer"
    val broadChild: RehearsalSeparateChildProducer<Any?> = exactChild
    if (RehearsalSeparateProducerReader().read(broadChild) != 47) {
        return "fail: separate broad child producer"
    }
    if (broadChild !== exactChild) return "fail: separate child producer identity"

    return "OK"
}
