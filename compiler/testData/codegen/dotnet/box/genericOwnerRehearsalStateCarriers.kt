// Rehearsal oracle for the one-state CLR-generic owner memory-model boundary.
// The ordinary field must become true !T storage, while the volatile sibling must use the
// same owner's single object-domain field so every closed value/reference construction is legal.
// DOTNET_GENERIC_OWNER_FOREIGN_OVERRIDE_PROBE

private class RehearsalStateCarriers<T>(initial: T) {
    private var typed: T = initial

    @Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
    @kotlin.concurrent.Volatile
    private var published: T = initial

    fun writeTyped(value: T) {
        typed = value
    }

    fun readTyped(): T = typed

    fun publish(value: T) {
        published = value
    }

    fun observe(): T = published
}

// A private default accessor over producer-proven state must retain the exact CLR carrier even
// when it is read from an initializer. Initializers have an exact newly-constructed `this`; a
// general semantic-hook rewrite must not degrade that stronger proof to the owner capability.
private class RehearsalTypedSource<out T>(private val value: T) {
    fun read(): T = value
}

private class RehearsalTypedInitializer<out T>(private val source: RehearsalTypedSource<T>) {
    private val observed: T = source.read()

    fun read(): T = observed
}

// Having a typed backing field is deliberately insufficient for a custom getter: it owns
// independent Kotlin behavior and therefore must remain subject to ordinary member routing.
private class RehearsalCustomGetter<T>(initial: T) {
    private var stored: T = initial
    private var reads: Int = 0
    private val observed: T
        get() {
            reads++
            return stored
        }

    fun read(): T = observed

    fun readCount(): Int = reads
}

// A foreign subclass must only override this natural typed entry. Kotlin calls through a widened
// view must still observe that override; the protected semantic hook is compiler ABI and cannot be
// an additional obligation imposed on C# source.
public open class RehearsalForeignOverrideStore<out T>(initial: T) {
    private var value: T = initial

    public open fun read(): T = value

    public fun write(value: @UnsafeVariance T) {
        this.value = value
    }
}

public open class RehearsalKotlinOverrideStore<out T>(initial: T) :
    RehearsalForeignOverrideStore<T>(initial) {
    public override fun read(): T = super.read()
}

public fun rehearsalWidenedRead(store: RehearsalForeignOverrideStore<Any?>): Any? = store.read()

// The first generic-interface reopening tranche is structural rather than library- or name-
// specific: one covariant no-input producer becomes a natural CLR I<T> plus a non-generic
// declaration-semantic capability for Kotlin views which cannot name one CLR construction.
public interface RehearsalProducer<out T> {
    public fun produce(): T
}

private class RehearsalProducerValue<T>(private val value: T) : RehearsalProducer<T> {
    override fun produce(): T = value
}

private fun rehearsalBroadProduce(producer: RehearsalProducer<Any?>): Any? = producer.produce()

fun box(): String {
    val ints = RehearsalStateCarriers(1)
    ints.writeTyped(2)
    ints.publish(3)
    if (ints.readTyped() != 2 || ints.observe() != 3) return "fail: value state"

    val strings = RehearsalStateCarriers("a")
    strings.writeTyped("b")
    strings.publish("c")
    if (strings.readTyped() != "b" || strings.observe() != "c") return "fail: reference state"

    val typedInitializer = RehearsalTypedInitializer(RehearsalTypedSource(42))
    if (typedInitializer.read() != 42) return "fail: typed initializer"

    val customGetter = RehearsalCustomGetter("custom")
    if (customGetter.read() != "custom" || customGetter.readCount() != 1) return "fail: custom getter"

    val exactStore = RehearsalForeignOverrideStore(11)
    val widenedStore: RehearsalForeignOverrideStore<Any?> = exactStore
    widenedStore.write("semantic")
    if (rehearsalWidenedRead(widenedStore) != "semantic") return "fail: raw widened read"
    try {
        exactStore.read() + 1
        return "fail: typed incompatible read"
    } catch (_: ClassCastException) {
        // The exact typed use is the real checked boundary.
    }
    widenedStore.write(19)
    if (exactStore.read() != 19) return "fail: compatible recovery"

    val intProducer: RehearsalProducer<Int> = RehearsalProducerValue(41)
    if (intProducer.produce() + 1 != 42) return "fail: exact value producer"
    val broadProducer: RehearsalProducer<Any?> = intProducer
    if (rehearsalBroadProduce(broadProducer) != 41) return "fail: broad value producer"
    if (broadProducer !== intProducer) return "fail: producer identity"

    val stringProducer: RehearsalProducer<String> = RehearsalProducerValue("typed")
    if (stringProducer.produce() != "typed") return "fail: exact reference producer"

    return "OK"
}
