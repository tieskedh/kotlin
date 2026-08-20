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

public interface RehearsalConsumer<in T> {
    public fun consume(value: T)
}

// Declaration-invariant generic owners have no legal sibling widening. Exact constructions and
// open method substitutions must therefore remain ordinary CLR I<T>; only a star read needs the
// classifier/semantic operation boundary.
public interface RehearsalInvariantProducer<T> {
    public fun produceInvariant(): T
}

private class RehearsalProducerValue<T>(private val value: T) : RehearsalProducer<T> {
    override fun produce(): T = value
}

private class RehearsalConsumerValue<T>(initial: T) : RehearsalConsumer<T> {
    private var value: T = initial

    override fun consume(value: T) {
        this.value = value
    }

    fun read(): T = value
}

private class RehearsalInvariantProducerValue<T>(private val value: T) :
    RehearsalInvariantProducer<T> {
    override fun produceInvariant(): T = value
}

private fun rehearsalBroadProduce(producer: RehearsalProducer<Any?>): Any? = producer.produce()

public fun rehearsalStarInvariantProduce(producer: RehearsalInvariantProducer<*>): Any? =
    producer.produceInvariant()

public fun rehearsalProjectedInvariantProduce(
    producer: RehearsalInvariantProducer<out Any?>,
): Any? = producer.produceInvariant()

// The owner itself always retains one !T field. A logical construction whose argument can carry
// a CLR-unnameable semantic producer view substitutes object for this construction only; exact
// scalar, reference, and nested-producer constructions retain their natural CLR argument.
public class RehearsalNestedBox<T>(initial: T) {
    private var value: T = initial

    public fun read(): T = value

    public fun write(value: T) {
        this.value = value
    }
}

public open class RehearsalNestedAnimal(public val label: String)

public class RehearsalNestedCat(label: String) : RehearsalNestedAnimal(label)

public fun rehearsalBroadProducerBox(
    producer: RehearsalProducer<Any?>,
): RehearsalNestedBox<RehearsalProducer<Any?>> = RehearsalNestedBox(producer)

public fun rehearsalExactStringProducerBox(
    producer: RehearsalProducer<String>,
): RehearsalNestedBox<RehearsalProducer<String>> = RehearsalNestedBox(producer)

public fun rehearsalNumberProducerBox(
    producer: RehearsalProducer<Number>,
): RehearsalNestedBox<RehearsalProducer<Number>> = RehearsalNestedBox(producer)

public fun rehearsalComparableProducerBox(
    producer: RehearsalProducer<Comparable<Int>>,
): RehearsalNestedBox<RehearsalProducer<Comparable<Int>>> =
    RehearsalNestedBox(producer)

public fun rehearsalAnimalProducerBox(
    producer: RehearsalProducer<RehearsalNestedAnimal>,
): RehearsalNestedBox<RehearsalProducer<RehearsalNestedAnimal>> =
    RehearsalNestedBox(producer)

public fun rehearsalIntConsumerBox(
    consumer: RehearsalConsumer<Int>,
): RehearsalNestedBox<RehearsalConsumer<Int>> = RehearsalNestedBox(consumer)

public fun rehearsalCatConsumerBox(
    consumer: RehearsalConsumer<RehearsalNestedCat>,
): RehearsalNestedBox<RehearsalConsumer<RehearsalNestedCat>> =
    RehearsalNestedBox(consumer)

// One generic MethodDef must choose a construction which remains truthful for every later T
// substitution. Its direct producer/consumer input is still the natural CLR I<T> entry, while
// the enclosing invariant box cannot promise that the nested variant view always has that one
// physical construction.
public fun <T> rehearsalOpenProducerBox(
    producer: RehearsalProducer<T>,
): RehearsalNestedBox<RehearsalProducer<T>> = RehearsalNestedBox(producer)

public fun <T> rehearsalOpenConsumerBox(
    consumer: RehearsalConsumer<T>,
): RehearsalNestedBox<RehearsalConsumer<T>> = RehearsalNestedBox(consumer)

public fun <T> rehearsalOpenProducerBoxIdentity(
    box: RehearsalNestedBox<RehearsalProducer<T>>,
): RehearsalNestedBox<RehearsalProducer<T>> = box

public fun <T> rehearsalOpenConsumerBoxIdentity(
    box: RehearsalNestedBox<RehearsalConsumer<T>>,
): RehearsalNestedBox<RehearsalConsumer<T>> = box

public fun <T> rehearsalStableOpenNestedBoxIdentity(
    box: RehearsalNestedBox<RehearsalNestedBox<T>>,
): RehearsalNestedBox<RehearsalNestedBox<T>> = box

public fun <T> rehearsalOpenInvariantProducerBoxIdentity(
    box: RehearsalNestedBox<RehearsalInvariantProducer<T>>,
): RehearsalNestedBox<RehearsalInvariantProducer<T>> = box

public fun rehearsalProjectedInvariantProducerBox(
    producer: RehearsalInvariantProducer<out Any?>,
): RehearsalNestedBox<RehearsalInvariantProducer<out Any?>> =
    RehearsalNestedBox(producer)

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

    val invariantInt: RehearsalInvariantProducer<Int> = RehearsalInvariantProducerValue(47)
    if (invariantInt.produceInvariant() + 1 != 48 ||
        rehearsalStarInvariantProduce(invariantInt) != 47
    ) {
        return "fail: invariant value producer"
    }
    val invariantString: RehearsalInvariantProducer<String> =
        RehearsalInvariantProducerValue("invariant")
    if (invariantString.produceInvariant() != "invariant" ||
        rehearsalStarInvariantProduce(invariantString) != "invariant"
    ) {
        return "fail: invariant reference producer"
    }
    val projectedInvariant: RehearsalInvariantProducer<out Any?> = invariantString
    if (rehearsalProjectedInvariantProduce(projectedInvariant) != "invariant" ||
        projectedInvariant !== invariantString
    ) {
        return "fail: projected invariant producer"
    }
    val projectedInvariantBox = rehearsalProjectedInvariantProducerBox(projectedInvariant)
    if (projectedInvariantBox.read() !== invariantString ||
        projectedInvariantBox.read().produceInvariant() != "invariant"
    ) {
        return "fail: projected invariant producer box"
    }
    projectedInvariantBox.write(invariantInt)
    if (projectedInvariantBox.read() !== invariantInt ||
        projectedInvariantBox.read().produceInvariant() != 47
    ) {
        return "fail: projected invariant producer box write"
    }

    val intBox = RehearsalNestedBox(51)
    intBox.write(53)
    if (intBox.read() != 53) return "fail: exact value box"
    val stringBox = RehearsalNestedBox("nested")
    stringBox.write("typed-nested")
    if (stringBox.read() != "typed-nested") return "fail: exact reference box"
    val stableOpenNestedBox = RehearsalNestedBox(stringBox)
    val stableOpenNestedBoxIdentity =
        rehearsalStableOpenNestedBoxIdentity(stableOpenNestedBox)
    if (stableOpenNestedBoxIdentity !== stableOpenNestedBox ||
        stableOpenNestedBoxIdentity.read() !== stringBox ||
        stableOpenNestedBoxIdentity.read().read() != "typed-nested"
    ) {
        return "fail: stable open nested box identity"
    }
    val invariantBox = RehearsalNestedBox(invariantString)
    val invariantBoxIdentity = rehearsalOpenInvariantProducerBoxIdentity(invariantBox)
    if (invariantBoxIdentity !== invariantBox ||
        invariantBoxIdentity.read() !== invariantString ||
        invariantBoxIdentity.read().produceInvariant() != "invariant"
    ) {
        return "fail: invariant open nested box identity"
    }

    val exactProducerBox = rehearsalExactStringProducerBox(stringProducer)
    if (exactProducerBox.read() !== stringProducer ||
        exactProducerBox.read().produce() != "typed"
    ) {
        return "fail: exact nested producer box"
    }

    val broadProducerBox = rehearsalBroadProducerBox(broadProducer)
    if (broadProducerBox.read() !== intProducer ||
        rehearsalBroadProduce(broadProducerBox.read()) != 41
    ) {
        return "fail: broad nested producer box"
    }
    broadProducerBox.write(stringProducer)
    if (broadProducerBox.read() !== stringProducer ||
        rehearsalBroadProduce(broadProducerBox.read()) != "typed"
    ) {
        return "fail: broad nested producer box write"
    }

    val numberProducer: RehearsalProducer<Number> = intProducer
    val numberProducerBox = rehearsalNumberProducerBox(numberProducer)
    if (numberProducerBox.read() !== intProducer ||
        rehearsalBroadProduce(numberProducerBox.read()) != 41
    ) {
        return "fail: number nested producer box"
    }
    val comparableProducer: RehearsalProducer<Comparable<Int>> = intProducer
    val comparableProducerBox = rehearsalComparableProducerBox(comparableProducer)
    if (comparableProducerBox.read() !== intProducer ||
        rehearsalBroadProduce(comparableProducerBox.read()) != 41
    ) {
        return "fail: comparable nested producer box"
    }

    val catProducer: RehearsalProducer<RehearsalNestedCat> =
        RehearsalProducerValue(RehearsalNestedCat("cat"))
    val animalProducer: RehearsalProducer<RehearsalNestedAnimal> = catProducer
    val animalProducerBox = rehearsalAnimalProducerBox(animalProducer)
    if (animalProducerBox.read() !== catProducer ||
        animalProducerBox.read().produce().label != "cat"
    ) {
        return "fail: reference-only nested producer box"
    }

    val openIntProducerBox = rehearsalOpenProducerBox(intProducer)
    if (openIntProducerBox.read() !== intProducer ||
        openIntProducerBox.read().produce() != 41
    ) {
        return "fail: open value nested producer box"
    }
    val openStringProducerBox = rehearsalOpenProducerBox(stringProducer)
    if (openStringProducerBox.read() !== stringProducer ||
        openStringProducerBox.read().produce() != "typed"
    ) {
        return "fail: open reference nested producer box"
    }
    val openBroadProducerBox = rehearsalOpenProducerBox(broadProducer)
    if (openBroadProducerBox.read() !== intProducer ||
        rehearsalBroadProduce(openBroadProducerBox.read()) != 41 ||
        rehearsalOpenProducerBox(broadProducer).read().produce() != 41
    ) {
        return "fail: open broad nested producer box"
    }
    val exactProducerBoxIdentity = rehearsalOpenProducerBoxIdentity(exactProducerBox)
    if (exactProducerBoxIdentity !== exactProducerBox ||
        exactProducerBoxIdentity.read().produce() != "typed"
    ) {
        return "fail: open exact producer box identity"
    }
    val identityStringProducer: RehearsalProducer<String> =
        RehearsalProducerValue("identity-write")
    exactProducerBoxIdentity.write(identityStringProducer)
    if (exactProducerBox.read() !== identityStringProducer ||
        exactProducerBox.read().produce() != "identity-write"
    ) {
        return "fail: open exact producer box identity write"
    }
    val broadProducerBoxIdentity = rehearsalOpenProducerBoxIdentity(broadProducerBox)
    if (broadProducerBoxIdentity !== broadProducerBox ||
        rehearsalBroadProduce(broadProducerBoxIdentity.read()) != "typed"
    ) {
        return "fail: open broad producer box identity"
    }
    broadProducerBoxIdentity.write(broadProducer)
    if (broadProducerBox.read() !== intProducer ||
        rehearsalBroadProduce(broadProducerBox.read()) != 41
    ) {
        return "fail: open broad producer box identity write"
    }

    val anyConsumerValue = RehearsalConsumerValue<Any?>("initial")
    val anyConsumer: RehearsalConsumer<Any?> = anyConsumerValue
    val intConsumer: RehearsalConsumer<Int> = anyConsumer
    val intConsumerBox = rehearsalIntConsumerBox(intConsumer)
    if (intConsumerBox.read() !== anyConsumer) {
        return "fail: value-type nested consumer identity"
    }
    intConsumerBox.read().consume(71)
    if (anyConsumerValue.read() != 71) return "fail: value-type nested consumer dispatch"

    val animalConsumerValue = RehearsalConsumerValue<RehearsalNestedAnimal>(
        RehearsalNestedAnimal("initial-animal"),
    )
    val animalConsumer: RehearsalConsumer<RehearsalNestedAnimal> = animalConsumerValue
    val catConsumer: RehearsalConsumer<RehearsalNestedCat> = animalConsumer
    val catConsumerBox = rehearsalCatConsumerBox(catConsumer)
    if (catConsumerBox.read() !== animalConsumer) {
        return "fail: reference-only nested consumer identity"
    }
    catConsumerBox.read().consume(RehearsalNestedCat("consumed-cat"))
    if (animalConsumerValue.read().label != "consumed-cat") {
        return "fail: reference-only nested consumer dispatch"
    }

    val openIntConsumerBox = rehearsalOpenConsumerBox(intConsumer)
    if (openIntConsumerBox.read() !== anyConsumer) {
        return "fail: open value nested consumer identity"
    }
    openIntConsumerBox.read().consume(73)
    if (anyConsumerValue.read() != 73) return "fail: open value nested consumer dispatch"
    rehearsalOpenConsumerBox(intConsumer).read().consume(74)
    if (anyConsumerValue.read() != 74) return "fail: direct open nested consumer dispatch"
    val openCatConsumerBox = rehearsalOpenConsumerBox(catConsumer)
    if (openCatConsumerBox.read() !== animalConsumer) {
        return "fail: open reference nested consumer identity"
    }
    openCatConsumerBox.read().consume(RehearsalNestedCat("open-consumed-cat"))
    if (animalConsumerValue.read().label != "open-consumed-cat") {
        return "fail: open reference nested consumer dispatch"
    }
    val intConsumerBoxIdentity = rehearsalOpenConsumerBoxIdentity(intConsumerBox)
    if (intConsumerBoxIdentity !== intConsumerBox) {
        return "fail: open value consumer box identity"
    }
    intConsumerBoxIdentity.read().consume(75)
    if (anyConsumerValue.read() != 75) return "fail: open value consumer box identity dispatch"
    val catConsumerBoxIdentity = rehearsalOpenConsumerBoxIdentity(catConsumerBox)
    if (catConsumerBoxIdentity !== catConsumerBox) {
        return "fail: open reference consumer box identity"
    }
    catConsumerBoxIdentity.read().consume(RehearsalNestedCat("identity-consumed-cat"))
    if (animalConsumerValue.read().label != "identity-consumed-cat") {
        return "fail: open reference consumer box identity dispatch"
    }

    return "OK"
}
