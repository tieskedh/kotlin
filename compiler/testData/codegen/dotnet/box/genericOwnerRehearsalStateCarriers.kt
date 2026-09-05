// Rehearsal oracle for the one-state CLR-generic owner memory-model boundary.
// The ordinary field must become true !T storage, while the volatile sibling must use the
// same owner's single object-domain field so every closed value/reference construction is legal.
// DOTNET_GENERIC_OWNER_FOREIGN_OVERRIDE_PROBE
// DOTNET_GENERIC_OWNER_TARGET_INDEXED_STATE_PROVENANCE_PROBE

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

// Typed-write provenance is target-indexed, not one undifferentiated "generic" bit. Both
// parameters may independently survive an object-shaped private helper, but neither may be used
// as physical evidence for the other parameter or for a differently ordered nested construction.
public class RehearsalCarrierDualStore<K, V>(initialKey: K, initialValue: V) {
    private var key: K = initialKey
    private var value: V = initialValue

    @Suppress("UNCHECKED_CAST")
    private fun installKey(candidate: Any?) {
        key = candidate as K
    }

    @Suppress("UNCHECKED_CAST")
    private fun installValue(candidate: Any?) {
        value = candidate as V
    }

    fun writeKey(next: K) = installKey(next)

    fun writeValue(next: V) = installValue(next)

    fun readKey(): K = key

    fun readValue(): V = value
}

public class RehearsalCarrierCrossCastStore<K, V>(initial: K) {
    private var key: K = initial

    @Suppress("UNCHECKED_CAST")
    private fun installKey(candidate: Any?) {
        key = candidate as K
    }

    fun writeWrong(next: V) = installKey(next)

    fun read(): K = key
}

public class RehearsalCarrierSameJoinStore<T>(initial: T) {
    private var value: T = initial

    @Suppress("UNCHECKED_CAST")
    private fun install(candidate: Any?) {
        value = candidate as T
    }

    fun choose(first: T, second: T, useFirst: Boolean) {
        val candidate: Any? = if (useFirst) first else second
        install(candidate)
    }

    fun read(): T = value
}

public class RehearsalCarrierMixedJoinStore<K, V>(initial: K) {
    private var key: K = initial

    @Suppress("UNCHECKED_CAST")
    private fun install(candidate: Any?) {
        key = candidate as K
    }

    fun choose(key: K, value: V, useKey: Boolean) {
        val candidate: Any? = if (useKey) key else value
        install(candidate)
    }
}

public class RehearsalCarrierBroadCastStore<T>(initial: T) {
    private var value: T = initial

    @Suppress("UNCHECKED_CAST")
    fun install(candidate: Any?) {
        value = candidate as T
    }
}

public class RehearsalCarrierValueOperatorStore<T>(initial: T) {
    private var value: T = initial

    @Suppress("UNCHECKED_CAST")
    private fun install(candidate: Any?) {
        value = candidate as T
    }

    fun poison(candidate: T) = install(candidate is String)
}

public class RehearsalCarrierNestedOrderStore<K, V>(initial: Array<Array<K>>) {
    private var pair: Array<Array<K>> = initial

    @Suppress("UNCHECKED_CAST")
    private fun install(candidate: Any?) {
        pair = candidate as Array<Array<K>>
    }

    fun writeWrong(candidate: Array<Array<V>>) = install(candidate)
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

// ForLoopsLowering creates the Iterator hasNext()/next() calls after generic-owner family
// publication. The early capability contract must therefore preserve its universal Iterator
// superinterface without reconstructing one arbitrary C<T> construction from those later calls.
private class RehearsalLateRoutedIterator<out T>(private val value: T) : Iterator<T> {
    private var consumed: Boolean = false

    override fun hasNext(): Boolean = !consumed

    override fun next(): T {
        consumed = true
        return value
    }
}

private fun rehearsalLateRoutedLoop(iterator: RehearsalLateRoutedIterator<Any?>): Any? {
    for (value in iterator) return value
    return null
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

// The final pass must apply the same stable routing rule to a copied generic-class call, not only
// to reified interface members. The unboxed value keeps the class capability as its one carrier.
private value class RehearsalLateRoutedOwnerValue(
    val store: RehearsalForeignOverrideStore<Any?>,
) {
    init {
        check(store.read() == 57)
    }

    fun read(): Any? = store.read()
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

private var rehearsalDefaultConsumerObserved: Any? = null
private var rehearsalDefaultConsumerOverrideObserved: Any? = null

// A Kotlin default must retain one logical body across the portable helper and the .NET 10 DIM.
// The narrowed Int view cannot name the existing I<object> construction on the CLR, so its call
// must cross the same semantic capability without copying the body or changing object identity.
public interface RehearsalDefaultConsumer<in T> {
    public fun consumeDefault(value: T) {
        rehearsalDefaultConsumerObserved = value
    }
}

// Declaration-invariant generic owners have no legal sibling widening. Exact constructions and
// open method substitutions must therefore remain ordinary CLR I<T>; only a star read needs the
// classifier/semantic operation boundary.
public interface RehearsalInvariantProducer<T> {
    public fun produceInvariant(): T
}

// The first broader invariant family owns both directions on one natural CLR I<T>. Exact and
// open calls must stay typed; output/input projections select semantic operations independently.
public interface RehearsalInvariantCell<T> {
    public fun readCell(): T

    public fun writeCell(value: T)
}

public interface RehearsalInvariantPropertyCell<T> {
    public var propertyCellValue: T
}

public interface RehearsalInvariantPropertyCellChild<T> :
    RehearsalInvariantPropertyCell<T> {
    public var childPropertyCellValue: T
}

public interface RehearsalInvariantPropertyConsumerChild<T> :
    RehearsalInvariantPropertyCell<T> {
    public fun consumePropertyCellValue(value: T)
}

public interface RehearsalInvariantPropertyConsumerGrandchild<T> :
    RehearsalInvariantPropertyConsumerChild<T> {
    public fun consumeSecondaryPropertyCellValue(value: T)
}

// Property admission is deliberately exact. Read-only state, open-nullable state, and a property
// mixed with an unrelated member remain on the erased production ABI until each broader family
// has its own proof.
public interface RehearsalInvariantReadOnlyProperty<T> {
    public val readOnlyPropertyValue: T
}

public interface RehearsalInvariantNullablePropertyCell<T> {
    public var nullablePropertyCellValue: T?
}

public interface RehearsalInvariantPropertyCellWithMember<T> {
    public var propertyCellWithMemberValue: T

    public fun touchPropertyCell()
}

// Exact overload identity is proven for bounded producer-only families. The mixed invariant
// producer/consumer composition remains erased until its projected, foreign, and separate-
// compilation routes have their own proof.
public interface RehearsalInvariantOverloaded<T> {
    public fun exchange(): T

    public fun exchange(value: T)
}

public interface RehearsalInvariantNullableCell<T> {
    public fun readNullableCell(): T?

    public fun writeNullableCell(value: T)
}

private class RehearsalProducerValue<T>(private val value: T) : RehearsalProducer<T> {
    override fun produce(): T = value
}

// This owner is intentionally non-generic in the candidate CLR surface: its potentially
// reparameterized nested constructor/result need object, while its ordinary scalar T result still
// requires normal checked recovery and its fixed Boolean result must remain bool.
private class RehearsalLocalErasedNestedResult<T>(
    private val nestedValue: RehearsalProducer<T>,
    private val scalarValue: T,
) {
    fun nestedResult(): RehearsalProducer<T> = nestedValue

    fun scalarResult(): T = scalarValue

    fun fixedResult(): Boolean = true
}

// InlineClassDeclarationLowering deep-copies this init body after generic-owner publication. The
// final router must classify both its generated static parameter and copied produce() call from
// stable declaration/value provenance rather than the source IrCall identity.
private value class RehearsalLateRoutedValue(
    val producer: RehearsalProducer<Any?>,
) {
    init {
        check(producer.produce() == 41)
    }

    fun read(): Any? = producer.produce()
}

private class RehearsalConsumerValue<T>(initial: T) : RehearsalConsumer<T> {
    private var value: T = initial

    override fun consume(value: T) {
        this.value = value
    }

    fun read(): T = value
}

private class RehearsalDefaultConsumerValue : RehearsalDefaultConsumer<Any?>

private class RehearsalDefaultConsumerOverrideValue : RehearsalDefaultConsumer<Any?> {
    override fun consumeDefault(value: Any?) {
        rehearsalDefaultConsumerOverrideObserved = value
    }

    fun consumeInterfaceDefault(value: Any?) {
        super<RehearsalDefaultConsumer>.consumeDefault(value)
    }
}

private class RehearsalInvariantProducerValue<T>(private val value: T) :
    RehearsalInvariantProducer<T> {
    override fun produceInvariant(): T = value
}

private class RehearsalInvariantCellValue<T>(private var value: T) :
    RehearsalInvariantCell<T> {
    override fun readCell(): T = value

    override fun writeCell(value: T) {
        this.value = value
    }
}

private class RehearsalInvariantPropertyCellValue<T>(
    override var propertyCellValue: T,
) : RehearsalInvariantPropertyCell<T>

private class RehearsalInvariantPropertyCellChildValue<T>(
    override var propertyCellValue: T,
    override var childPropertyCellValue: T,
) : RehearsalInvariantPropertyCellChild<T>

private class RehearsalInvariantPropertyConsumerChildValue<T>(
    override var propertyCellValue: T,
) : RehearsalInvariantPropertyConsumerChild<T> {
    override fun consumePropertyCellValue(value: T) {
        propertyCellValue = value
    }
}

private class RehearsalInvariantPropertyConsumerGrandchildValue<T>(
    override var propertyCellValue: T,
) : RehearsalInvariantPropertyConsumerGrandchild<T> {
    override fun consumePropertyCellValue(value: T) {
        propertyCellValue = value
    }

    override fun consumeSecondaryPropertyCellValue(value: T) {
        propertyCellValue = value
    }
}

private fun rehearsalBroadProduce(producer: RehearsalProducer<Any?>): Any? = producer.produce()

public fun rehearsalStarInvariantProduce(producer: RehearsalInvariantProducer<*>): Any? =
    producer.produceInvariant()

public fun rehearsalProjectedInvariantProduce(
    producer: RehearsalInvariantProducer<out Any?>,
): Any? = producer.produceInvariant()

public fun rehearsalStarInvariantCellRead(cell: RehearsalInvariantCell<*>): Any? =
    cell.readCell()

public fun rehearsalProjectedInvariantCellRead(
    cell: RehearsalInvariantCell<out Any?>,
): Any? = cell.readCell()

public fun rehearsalProjectedInvariantCellWrite(
    cell: RehearsalInvariantCell<in String>,
    value: String,
) {
    cell.writeCell(value)
}

public fun rehearsalProjectedInvariantCellWriteResult(
    cell: RehearsalInvariantCell<in String>,
    value: String,
): Any? = cell.writeCell(value)

public fun <T> rehearsalOpenInvariantCellIdentity(
    cell: RehearsalInvariantCell<T>,
): RehearsalInvariantCell<T> = cell

public fun rehearsalStarInvariantPropertyCellRead(
    cell: RehearsalInvariantPropertyCell<*>,
): Any? = cell.propertyCellValue

public fun rehearsalProjectedInvariantPropertyCellRead(
    cell: RehearsalInvariantPropertyCell<out Any?>,
): Any? = cell.propertyCellValue

public fun rehearsalProjectedInvariantPropertyCellWrite(
    cell: RehearsalInvariantPropertyCell<in String>,
    value: String,
) {
    cell.propertyCellValue = value
}

public fun <T> rehearsalOpenInvariantPropertyCellIdentity(
    cell: RehearsalInvariantPropertyCell<T>,
): RehearsalInvariantPropertyCell<T> = cell

public fun rehearsalProjectedInvariantPropertyCellChildRead(
    cell: RehearsalInvariantPropertyCellChild<out Any?>,
): Any? = cell.childPropertyCellValue

public fun rehearsalProjectedInvariantPropertyCellChildWrite(
    cell: RehearsalInvariantPropertyCellChild<in String>,
    value: String,
) {
    cell.childPropertyCellValue = value
}

public fun <T> rehearsalOpenInvariantPropertyCellChildIdentity(
    cell: RehearsalInvariantPropertyCellChild<T>,
): RehearsalInvariantPropertyCellChild<T> = cell

public fun rehearsalProjectedInvariantPropertyConsumerChildWrite(
    cell: RehearsalInvariantPropertyConsumerChild<in String>,
    value: String,
) {
    cell.consumePropertyCellValue(value)
}

public fun <T> rehearsalOpenInvariantPropertyConsumerChildIdentity(
    cell: RehearsalInvariantPropertyConsumerChild<T>,
): RehearsalInvariantPropertyConsumerChild<T> = cell

public fun rehearsalProjectedInvariantPropertyConsumerGrandchildWrite(
    cell: RehearsalInvariantPropertyConsumerGrandchild<in String>,
    value: String,
) {
    cell.consumeSecondaryPropertyCellValue(value)
}

public fun <T> rehearsalOpenInvariantPropertyConsumerGrandchildIdentity(
    cell: RehearsalInvariantPropertyConsumerGrandchild<T>,
): RehearsalInvariantPropertyConsumerGrandchild<T> = cell

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

public fun <T> rehearsalOpenInvariantCellBoxIdentity(
    box: RehearsalNestedBox<RehearsalInvariantCell<T>>,
): RehearsalNestedBox<RehearsalInvariantCell<T>> = box

public fun rehearsalProjectedInvariantCellBox(
    cell: RehearsalInvariantCell<out Any?>,
): RehearsalNestedBox<RehearsalInvariantCell<out Any?>> = RehearsalNestedBox(cell)

public fun <T> rehearsalOpenInvariantPropertyCellBoxIdentity(
    box: RehearsalNestedBox<RehearsalInvariantPropertyCell<T>>,
): RehearsalNestedBox<RehearsalInvariantPropertyCell<T>> = box

public fun rehearsalProjectedInvariantPropertyCellBox(
    cell: RehearsalInvariantPropertyCell<out Any?>,
): RehearsalNestedBox<RehearsalInvariantPropertyCell<out Any?>> = RehearsalNestedBox(cell)

public fun <T> rehearsalOpenInvariantPropertyCellChildBoxIdentity(
    box: RehearsalNestedBox<RehearsalInvariantPropertyCellChild<T>>,
): RehearsalNestedBox<RehearsalInvariantPropertyCellChild<T>> = box

public fun rehearsalProjectedInvariantPropertyCellChildBox(
    cell: RehearsalInvariantPropertyCellChild<out Any?>,
): RehearsalNestedBox<RehearsalInvariantPropertyCellChild<out Any?>> = RehearsalNestedBox(cell)

fun box(): String {
    val dualCarrier = RehearsalCarrierDualStore("key", 1)
    dualCarrier.writeKey("next-key")
    dualCarrier.writeValue(2)
    if (dualCarrier.readKey() != "next-key" || dualCarrier.readValue() != 2) {
        return "fail: target-indexed dual carrier"
    }
    val sameJoinCarrier = RehearsalCarrierSameJoinStore("initial")
    sameJoinCarrier.choose("left", "right", useFirst = false)
    if (sameJoinCarrier.read() != "right") return "fail: same-target carrier join"
    val crossCarrier = RehearsalCarrierCrossCastStore<String, String>("initial")
    crossCarrier.writeWrong("cross")
    if (crossCarrier.read() != "cross") return "fail: erased cross-carrier semantics"
    val mixedJoinCarrier = RehearsalCarrierMixedJoinStore<String, String>("initial")
    mixedJoinCarrier.choose("key", "value", useKey = true)
    val broadCarrier = RehearsalCarrierBroadCastStore<Any?>(null)
    broadCarrier.install("broad")
    RehearsalCarrierValueOperatorStore<Any?>(null).poison("value")
    RehearsalCarrierNestedOrderStore<String, Int>(arrayOf(arrayOf("key")))

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

    val lateRoutedIterator = RehearsalLateRoutedIterator(43)
    val widenedLateRoutedIterator: RehearsalLateRoutedIterator<Any?> = lateRoutedIterator
    if (rehearsalLateRoutedLoop(widenedLateRoutedIterator) != 43) {
        return "fail: late-routed widened iterator"
    }

    val customGetter = RehearsalCustomGetter("custom")
    if (customGetter.read() != "custom" || customGetter.readCount() != 1) return "fail: custom getter"

    val lateRoutedOwnerSource = RehearsalForeignOverrideStore(57)
    val lateRoutedOwnerView: RehearsalForeignOverrideStore<Any?> = lateRoutedOwnerSource
    val lateRoutedOwnerValue = RehearsalLateRoutedOwnerValue(lateRoutedOwnerView)
    if (lateRoutedOwnerValue.read() != 57) return "fail: late-routed generic owner"

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
    val localExactResultControl = RehearsalLocalErasedNestedResult(intProducer, 43)
    if (localExactResultControl.scalarResult() + 1 != 44 ||
        !localExactResultControl.fixedResult()
    ) {
        return "fail: local erased scalar/fixed result"
    }
    val localBroadNestedResult = try {
        RehearsalLocalErasedNestedResult<Any?>(broadProducer, "broad")
    } catch (_: ClassCastException) {
        return "fail: local semantic nested constructor materialized a CLR construction"
    }
    try {
        if (localBroadNestedResult.nestedResult() !== intProducer) {
            return "fail: local semantic nested direct result identity"
        }
    } catch (_: ClassCastException) {
        return "fail: local semantic nested identity materialized a CLR construction"
    }
    try {
        if (localBroadNestedResult.nestedResult().produce() != 41 ||
            !localBroadNestedResult.fixedResult()
        ) {
            return "fail: local semantic nested member use"
        }
    } catch (_: ClassCastException) {
        return "fail: local semantic nested member materialized a CLR construction"
    }
    val lateRoutedValue = RehearsalLateRoutedValue(broadProducer)
    if (lateRoutedValue.read() != 41) return "fail: late-routed value-class producer"

    val stringProducer: RehearsalProducer<String> = RehearsalProducerValue("typed")
    if (stringProducer.produce() != "typed") return "fail: exact reference producer"

    val exactDefaultConsumer: RehearsalDefaultConsumer<Any?> =
        RehearsalDefaultConsumerValue()
    exactDefaultConsumer.consumeDefault("default-reference")
    if (rehearsalDefaultConsumerObserved != "default-reference") {
        return "fail: exact default consumer"
    }
    val narrowedDefaultConsumer: RehearsalDefaultConsumer<Int> = exactDefaultConsumer
    narrowedDefaultConsumer.consumeDefault(61)
    if (rehearsalDefaultConsumerObserved != 61 ||
        narrowedDefaultConsumer !== exactDefaultConsumer
    ) {
        return "fail: narrowed default consumer"
    }
    val overridingDefaultConsumerValue = RehearsalDefaultConsumerOverrideValue()
    val overridingDefaultConsumer: RehearsalDefaultConsumer<Any?> =
        overridingDefaultConsumerValue
    overridingDefaultConsumer.consumeDefault("default-override-reference")
    val narrowedOverridingDefaultConsumer: RehearsalDefaultConsumer<Int> =
        overridingDefaultConsumer
    narrowedOverridingDefaultConsumer.consumeDefault(62)
    if (rehearsalDefaultConsumerOverrideObserved != 62 ||
        narrowedOverridingDefaultConsumer !== overridingDefaultConsumer
    ) {
        return "fail: narrowed overriding default consumer"
    }
    overridingDefaultConsumerValue.consumeInterfaceDefault("qualified-default")
    if (rehearsalDefaultConsumerObserved != "qualified-default" ||
        rehearsalDefaultConsumerOverrideObserved != 62
    ) {
        return "fail: qualified interface default"
    }

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

    val invariantCell: RehearsalInvariantCell<String> = RehearsalInvariantCellValue("cell")
    invariantCell.writeCell("exact-cell")
    if (invariantCell.readCell() != "exact-cell") return "fail: exact invariant cell"
    val projectedOutputCell: RehearsalInvariantCell<out Any?> = invariantCell
    if (rehearsalProjectedInvariantCellRead(projectedOutputCell) != "exact-cell" ||
        rehearsalStarInvariantCellRead(projectedOutputCell) != "exact-cell" ||
        projectedOutputCell !== invariantCell
    ) {
        return "fail: projected invariant cell read"
    }
    val projectedInputCell: RehearsalInvariantCell<in String> = invariantCell
    rehearsalProjectedInvariantCellWrite(projectedInputCell, "projected-cell")
    val projectedWriteResult: Any? =
        rehearsalProjectedInvariantCellWriteResult(projectedInputCell, "projected-cell-result")
    if (projectedWriteResult !== Unit ||
        invariantCell.readCell() != "projected-cell-result" ||
        projectedInputCell !== invariantCell
    ) {
        return "fail: projected invariant cell write"
    }
    val broadInvariantCell: RehearsalInvariantCell<Any?> =
        RehearsalInvariantCellValue("broad-cell")
    val projectedBroadInputCell: RehearsalInvariantCell<in String> = broadInvariantCell
    rehearsalProjectedInvariantCellWrite(projectedBroadInputCell, "broad-projected-cell")
    if (broadInvariantCell.readCell() != "broad-projected-cell" ||
        projectedBroadInputCell !== broadInvariantCell
    ) {
        return "fail: broad projected invariant cell write"
    }
    if (rehearsalOpenInvariantCellIdentity(invariantCell) !== invariantCell) {
        return "fail: open invariant cell identity"
    }
    val invariantCellBox = RehearsalNestedBox(invariantCell)
    if (rehearsalOpenInvariantCellBoxIdentity(invariantCellBox) !== invariantCellBox) {
        return "fail: open invariant cell box identity"
    }
    val projectedInvariantCellBox = rehearsalProjectedInvariantCellBox(projectedOutputCell)
    if (projectedInvariantCellBox.read() !== invariantCell) {
        return "fail: projected invariant cell box identity"
    }
    val intInvariantCell: RehearsalInvariantCell<Int> = RehearsalInvariantCellValue(59)
    projectedInvariantCellBox.write(intInvariantCell)
    if (projectedInvariantCellBox.read() !== intInvariantCell ||
        rehearsalProjectedInvariantCellRead(projectedInvariantCellBox.read()) != 59
    ) {
        return "fail: projected invariant cell box mutation"
    }

    val invariantPropertyCell: RehearsalInvariantPropertyCell<String> =
        RehearsalInvariantPropertyCellValue("property-cell")
    invariantPropertyCell.propertyCellValue = "exact-property-cell"
    if (invariantPropertyCell.propertyCellValue != "exact-property-cell") {
        return "fail: exact invariant property cell"
    }
    val projectedOutputPropertyCell: RehearsalInvariantPropertyCell<out Any?> =
        invariantPropertyCell
    if (rehearsalProjectedInvariantPropertyCellRead(projectedOutputPropertyCell) !=
        "exact-property-cell" ||
        rehearsalStarInvariantPropertyCellRead(projectedOutputPropertyCell) !=
        "exact-property-cell" ||
        projectedOutputPropertyCell !== invariantPropertyCell
    ) {
        return "fail: projected invariant property cell read"
    }
    val projectedInputPropertyCell: RehearsalInvariantPropertyCell<in String> =
        invariantPropertyCell
    rehearsalProjectedInvariantPropertyCellWrite(
        projectedInputPropertyCell,
        "projected-property-cell",
    )
    if (invariantPropertyCell.propertyCellValue != "projected-property-cell" ||
        projectedInputPropertyCell !== invariantPropertyCell
    ) {
        return "fail: projected invariant property cell write"
    }
    val broadInvariantPropertyCell: RehearsalInvariantPropertyCell<Any?> =
        RehearsalInvariantPropertyCellValue("broad-property-cell")
    val projectedBroadInputPropertyCell: RehearsalInvariantPropertyCell<in String> =
        broadInvariantPropertyCell
    rehearsalProjectedInvariantPropertyCellWrite(
        projectedBroadInputPropertyCell,
        "broad-projected-property-cell",
    )
    if (broadInvariantPropertyCell.propertyCellValue != "broad-projected-property-cell" ||
        projectedBroadInputPropertyCell !== broadInvariantPropertyCell
    ) {
        return "fail: broad projected invariant property cell write"
    }
    if (rehearsalOpenInvariantPropertyCellIdentity(invariantPropertyCell) !==
        invariantPropertyCell
    ) {
        return "fail: open invariant property cell identity"
    }
    val invariantPropertyCellBox = RehearsalNestedBox(invariantPropertyCell)
    if (rehearsalOpenInvariantPropertyCellBoxIdentity(invariantPropertyCellBox) !==
        invariantPropertyCellBox
    ) {
        return "fail: open invariant property cell box identity"
    }
    val projectedInvariantPropertyCellBox =
        rehearsalProjectedInvariantPropertyCellBox(projectedOutputPropertyCell)
    if (projectedInvariantPropertyCellBox.read() !== invariantPropertyCell) {
        return "fail: projected invariant property cell box identity"
    }
    val intInvariantPropertyCell: RehearsalInvariantPropertyCell<Int> =
        RehearsalInvariantPropertyCellValue(67)
    projectedInvariantPropertyCellBox.write(intInvariantPropertyCell)
    if (projectedInvariantPropertyCellBox.read() !== intInvariantPropertyCell ||
        rehearsalProjectedInvariantPropertyCellRead(
            projectedInvariantPropertyCellBox.read()
        ) != 67
    ) {
        return "fail: projected invariant property cell box mutation"
    }

    val invariantPropertyCellChild: RehearsalInvariantPropertyCellChild<String> =
        RehearsalInvariantPropertyCellChildValue("property-parent", "property-child")
    invariantPropertyCellChild.propertyCellValue = "exact-property-parent"
    invariantPropertyCellChild.childPropertyCellValue = "exact-property-child"
    val projectedOutputPropertyCellChild:
            RehearsalInvariantPropertyCellChild<out Any?> = invariantPropertyCellChild
    if (rehearsalProjectedInvariantPropertyCellRead(projectedOutputPropertyCellChild) !=
        "exact-property-parent" ||
        rehearsalProjectedInvariantPropertyCellChildRead(projectedOutputPropertyCellChild) !=
        "exact-property-child"
    ) {
        return "fail: projected invariant property child read"
    }
    val projectedInputPropertyCellChild:
            RehearsalInvariantPropertyCellChild<in String> = invariantPropertyCellChild
    rehearsalProjectedInvariantPropertyCellWrite(
        projectedInputPropertyCellChild,
        "projected-property-parent",
    )
    rehearsalProjectedInvariantPropertyCellChildWrite(
        projectedInputPropertyCellChild,
        "projected-property-child",
    )
    if (invariantPropertyCellChild.propertyCellValue != "projected-property-parent" ||
        invariantPropertyCellChild.childPropertyCellValue != "projected-property-child" ||
        rehearsalOpenInvariantPropertyCellChildIdentity(invariantPropertyCellChild) !==
        invariantPropertyCellChild
    ) {
        return "fail: projected invariant property child write"
    }
    val broadInvariantPropertyCellChild: RehearsalInvariantPropertyCellChild<Any?> =
        RehearsalInvariantPropertyCellChildValue("broad-property-parent", "broad-property-child")
    val projectedBroadInputPropertyCellChild:
            RehearsalInvariantPropertyCellChild<in String> = broadInvariantPropertyCellChild
    rehearsalProjectedInvariantPropertyCellWrite(
        projectedBroadInputPropertyCellChild,
        "broad-projected-property-parent",
    )
    rehearsalProjectedInvariantPropertyCellChildWrite(
        projectedBroadInputPropertyCellChild,
        "broad-projected-property-child",
    )
    if (broadInvariantPropertyCellChild.propertyCellValue !=
        "broad-projected-property-parent" ||
        broadInvariantPropertyCellChild.childPropertyCellValue !=
        "broad-projected-property-child"
    ) {
        return "fail: broad projected invariant property child write"
    }
    val invariantPropertyConsumerChild: RehearsalInvariantPropertyConsumerChild<String> =
        RehearsalInvariantPropertyConsumerChildValue("property-consumer")
    invariantPropertyConsumerChild.consumePropertyCellValue("exact-property-consumer")
    val projectedInputPropertyConsumerChild:
            RehearsalInvariantPropertyConsumerChild<in String> =
        invariantPropertyConsumerChild
    rehearsalProjectedInvariantPropertyConsumerChildWrite(
        projectedInputPropertyConsumerChild,
        "projected-property-consumer",
    )
    if (invariantPropertyConsumerChild.propertyCellValue !=
        "projected-property-consumer" ||
        rehearsalOpenInvariantPropertyConsumerChildIdentity(
            invariantPropertyConsumerChild
        ) !== invariantPropertyConsumerChild
    ) {
        return "fail: projected invariant property consumer child write"
    }
    val broadInvariantPropertyConsumerChild:
            RehearsalInvariantPropertyConsumerChild<Any?> =
        RehearsalInvariantPropertyConsumerChildValue("broad-property-consumer")
    rehearsalProjectedInvariantPropertyConsumerChildWrite(
        broadInvariantPropertyConsumerChild,
        "broad-projected-property-consumer",
    )
    if (broadInvariantPropertyConsumerChild.propertyCellValue !=
        "broad-projected-property-consumer"
    ) {
        return "fail: broad projected invariant property consumer child write"
    }
    val invariantPropertyConsumerGrandchild:
            RehearsalInvariantPropertyConsumerGrandchild<String> =
        RehearsalInvariantPropertyConsumerGrandchildValue("property-consumer-grandchild")
    invariantPropertyConsumerGrandchild.consumePropertyCellValue(
        "exact-property-consumer-child"
    )
    val projectedInputPropertyConsumerGrandchild:
            RehearsalInvariantPropertyConsumerGrandchild<in String> =
        invariantPropertyConsumerGrandchild
    rehearsalProjectedInvariantPropertyConsumerGrandchildWrite(
        projectedInputPropertyConsumerGrandchild,
        "projected-property-consumer-grandchild",
    )
    if (invariantPropertyConsumerGrandchild.propertyCellValue !=
        "projected-property-consumer-grandchild" ||
        rehearsalOpenInvariantPropertyConsumerGrandchildIdentity(
            invariantPropertyConsumerGrandchild
        ) !== invariantPropertyConsumerGrandchild
    ) {
        return "fail: projected invariant property consumer grandchild write"
    }
    val invariantPropertyCellChildBox = RehearsalNestedBox(invariantPropertyCellChild)
    if (rehearsalOpenInvariantPropertyCellChildBoxIdentity(invariantPropertyCellChildBox) !==
        invariantPropertyCellChildBox
    ) {
        return "fail: open invariant property child box identity"
    }
    val projectedInvariantPropertyCellChildBox =
        rehearsalProjectedInvariantPropertyCellChildBox(projectedOutputPropertyCellChild)
    val intInvariantPropertyCellChild: RehearsalInvariantPropertyCellChild<Int> =
        RehearsalInvariantPropertyCellChildValue(73, 79)
    projectedInvariantPropertyCellChildBox.write(intInvariantPropertyCellChild)
    if (projectedInvariantPropertyCellChildBox.read() !== intInvariantPropertyCellChild ||
        rehearsalProjectedInvariantPropertyCellRead(
            projectedInvariantPropertyCellChildBox.read()
        ) != 73 ||
        rehearsalProjectedInvariantPropertyCellChildRead(
            projectedInvariantPropertyCellChildBox.read()
        ) != 79
    ) {
        return "fail: projected invariant property child box mutation"
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
