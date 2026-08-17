// MODULE: lib
// FILE: lib.kt

package generic.owner.oracle

public interface HostileContract<T> {
    public fun read(): T

    public fun write(next: T): T

    public fun accepts(candidate: Any?): Boolean
}

public open class HostileCell<T>(initial: T) : AbstractMutableCollection<T>(), HostileContract<T> {
    private val values = ArrayList<T>()

    init {
        values.add(initial)
    }

    override val size: Int
        get() = values.size

    override fun iterator(): MutableIterator<T> = values.iterator()

    override fun add(element: T): Boolean = values.add(element)

    override fun read(): T = values[0]

    override fun write(next: T): T {
        val previous = values[0]
        values[0] = next
        return previous
    }

    override fun accepts(candidate: Any?): Boolean {
        for (value in values) {
            if (value == candidate) return true
        }
        return false
    }

    public open fun <R : T> relative(value: R): R = value

    public open fun readOr(default: T = read()): T = default
}

public open class HostileMid<T>(initial: T) : HostileCell<T>(initial)

public class LibraryIntLeaf(initial: Int) : HostileMid<Int>(initial) {
    override fun read(): Int = super.read() + 100
}

// This open TypeDef edge is deliberately producer-owned so both producer and consumer subclasses
// exercise the metadata-fixed D<T> : C<T?> problem across the binary boundary.
public open class HostileNullableDerived<T>(initial: T?) : HostileCell<T?>(initial) {
    override fun read(): T? = super.read()

    override fun write(next: T?): T? = super.write(next)

    public fun readDirectFromBase(): T? = super.read()
}

public class LibraryNullableIntLeaf(initial: Int?) : HostileNullableDerived<Int>(initial) {
    override fun read(): Int? = super.read()?.plus(1000)
}

public open class HostileUnsafeProducer<out T>(private val expected: T) {
    public open fun probe(candidate: @UnsafeVariance T): String =
        if (candidate == expected) "match" else "candidate:$candidate"
}

public open class HostileMixed<in I, out O> {
    public open fun describe(input: I, candidate: @UnsafeVariance O): String = "$input:$candidate"
}

public open class HostileUnsafeStore<out T>(initial: T) {
    private var stored: T = initial

    public open var exposed: @UnsafeVariance T
        get() = stored
        set(value) {
            installUnchecked(value)
        }

    public constructor(initial: T, marker: Int) : this(initial)

    @Suppress("UNCHECKED_CAST")
    private fun installUnchecked(candidate: Any?) {
        stored = candidate as T
    }

    public open fun writeUnsafe(next: @UnsafeVariance T) {
        installUnchecked(next)
    }

    public open fun read(): T = stored

    public open fun echo(values: Array<out @UnsafeVariance T>): Array<out T> = values

    // The typed overloads remain distinct while both semantic hook parameters erase to object.
    // Separate consumers must bind the producer-recorded family names, never reconstruct them.
    public open fun collide(value: HostileTypedStore<@UnsafeVariance T>): String =
        "typed:${value.read()}"

    public open fun collide(value: HostileAbstractPropertyStorage<@UnsafeVariance T>): String =
        "abstract:${value.exposed}"

    public open fun <R> relay(values: Array<R>): Array<R> = values

    public open fun label(prefix: String = "default"): String = prefix
}

public abstract class HostileAbstractProperty<out T> {
    public abstract var exposed: @UnsafeVariance T
}

public class HostileAbstractPropertyStorage<T>(initial: T) : HostileAbstractProperty<T>() {
    private var stored: T = initial

    public override var exposed: T
        get() = stored
        set(value) {
            stored = value
        }
}

public open class HostileTypedStore<T>(initial: T) {
    private var stored: T = initial

    @Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
    @kotlin.concurrent.Volatile
    private var published: T = initial

    @Suppress("UNCHECKED_CAST")
    private fun installBoxed(candidate: Any?) {
        stored = candidate as T
    }

    public open fun write(next: T) {
        installBoxed(next)
    }

    public open fun read(): T = stored

    public open fun publish(next: T) {
        published = next
    }

    public open fun observe(): T = published
}

public fun readInvariantTypedStore(store: HostileTypedStore<String>): String = store.read()

public fun readStarTypedStore(store: HostileTypedStore<*>): Any? = store.read()

public fun labelStarUnsafeStore(store: HostileUnsafeStore<*>): String = store.label()

public class TypedStoreRouteHolder(
    public val exact: HostileTypedStore<String>,
    public val star: HostileTypedStore<*>,
)

public fun readMergedTypedStore(
    useStar: Boolean,
    exact: HostileTypedStore<String>,
    star: HostileTypedStore<*>,
): Any? = (if (useStar) star else exact).read()

public fun readExactTypedStoreField(holder: TypedStoreRouteHolder): String = holder.exact.read()

public fun readStarTypedStoreField(holder: TypedStoreRouteHolder): Any? = holder.star.read()

public fun returnInvariantTypedStore(store: HostileTypedStore<String>): HostileTypedStore<String> = store

public fun readReturnedTypedStore(store: HostileTypedStore<String>): String =
    returnInvariantTypedStore(store).read()

public open class HostileUnsafeMid<T>(initial: T) : HostileUnsafeStore<T>(initial) {
    override fun writeUnsafe(next: T) {
        super.writeUnsafe(next)
    }

    override fun read(): T = super.read()

    override fun echo(values: Array<out T>): Array<out T> = super.echo(values)
}

public fun <T> openNullableCell(value: T?): HostileCell<T?> = HostileCell(value)

public fun widenedContains(cell: HostileCell<Int>, candidate: Any?): Boolean {
    val widened: Collection<Any?> = cell
    return widened.contains(candidate)
}

// MODULE: main(lib)
// FILE: main.kt

import generic.owner.oracle.*

public open class ConsumerUnsafeLeaf<T>(initial: T) : HostileUnsafeMid<T>(initial) {
    override fun writeUnsafe(next: T) {
        super.writeUnsafe(next)
    }

    override fun read(): T = super.read()

    override fun echo(values: Array<out T>): Array<out T> = super.echo(values)

    override fun label(prefix: String): String = "consumer:${super.label(prefix)}"
}

private class ConsumerStringLeaf(initial: String) : HostileMid<String>(initial) {
    override fun write(next: String): String = super.write("$next!")
}

private class ConsumerNullableStringLeaf(initial: String?) : HostileNullableDerived<String>(initial) {
    override fun write(next: String?): String? =
        super.write(if (next == null) null else "$next!")
}

private fun fail(message: String): String = "fail: $message"

private fun consumerLabelStarUnsafeStore(store: HostileUnsafeStore<*>): String = store.label()

fun box(): String {
    val ints = HostileCell(1)
    ints.add(2)
    val widened: Collection<Any?> = ints
    if (!widened.containsAll(listOf<Any?>(1, 2)) ||
        widened.containsAll(listOf<Any?>(1, "wrong")) ||
        widened.containsAll(listOf<Any?>(1, null)) ||
        widenedContains(ints, "wrong")
    ) {
        return fail("cross-library widened candidates")
    }

    val output: HostileCell<out Any?> = ints
    val star: HostileCell<*> = ints
    if (output.read() != 1 || star !== ints || star.accepts("wrong")) {
        return fail("cross-library projections")
    }

    val anyCell = HostileCell<Any?>("seed")
    val input: HostileCell<in Int> = anyCell
    if (input.write(7) != "seed" || anyCell.read() != 7) {
        return fail("cross-library input projection")
    }

    val nullableInt = openNullableCell<Int>(null)
    val nullableString = openNullableCell<String>(null)
    nullableInt.write(8)
    nullableString.write("text")
    if (nullableInt.read() != 8 || nullableString.read() != "text") {
        return fail("cross-library open nullable constructions")
    }

    val libraryNullableLeaf = LibraryNullableIntLeaf(null)
    val libraryNullableBase: HostileCell<Int?> = libraryNullableLeaf
    if (libraryNullableBase.write(5) != null ||
        libraryNullableBase.read() != 1005 ||
        libraryNullableLeaf.readDirectFromBase() != 5
    ) {
        return fail("producer metadata-fixed nullable inheritance")
    }

    val consumerNullableLeaf = ConsumerNullableStringLeaf(null)
    val consumerNullableBase: HostileCell<String?> = consumerNullableLeaf
    if (consumerNullableBase.write("derived") != null ||
        consumerNullableBase.read() != "derived!" ||
        consumerNullableLeaf.readDirectFromBase() != "derived!"
    ) {
        return fail("consumer metadata-fixed nullable inheritance")
    }

    val unsafeProducer: HostileUnsafeProducer<Any?> = HostileUnsafeProducer(1)
    if (unsafeProducer.probe(1) != "match" ||
        unsafeProducer.probe("wrong") != "candidate:wrong" ||
        unsafeProducer.probe(null) != "candidate:null"
    ) {
        return fail("cross-library general widened semantic body")
    }
    val exactMixed: HostileMixed<Number, Int> = HostileMixed()
    val widenedMixed: HostileMixed<Int, Any?> = exactMixed
    if (widenedMixed.describe(7, "wrong") != "7:wrong") {
        return fail("cross-library mixed strict and broad input domains")
    }

    val exactUnsafeStore = HostileUnsafeStore(1)
    val widenedUnsafeStore: HostileUnsafeStore<Any?> = exactUnsafeStore
    widenedUnsafeStore.writeUnsafe("wrong")
    if (widenedUnsafeStore.read() != "wrong") {
        return fail("cross-library widened semantic state read")
    }
    try {
        val impossible = exactUnsafeStore.read() + 1
        return fail("cross-library exact use accepted incompatible state: $impossible")
    } catch (_: ClassCastException) {
        // The legal widened write remains producer-erased and the exact consumer fails on use.
    }
    widenedUnsafeStore.writeUnsafe(2)
    if (exactUnsafeStore.read() != 2) {
        return fail("cross-library semantic state recovery")
    }
    widenedUnsafeStore.exposed = "property-widened"
    if (widenedUnsafeStore.exposed != "property-widened") {
        return fail("cross-library widened property semantic state")
    }
    if (runCatching { exactUnsafeStore.exposed }.exceptionOrNull() !is ClassCastException) {
        return fail("cross-library widened property delayed typed failure")
    }
    widenedUnsafeStore.exposed = 2
    val exactAbstractProperty = HostileAbstractPropertyStorage(17)
    val widenedAbstractProperty: HostileAbstractProperty<Any?> = exactAbstractProperty
    widenedAbstractProperty.exposed = "abstract-property-widened"
    if (widenedAbstractProperty.exposed != "abstract-property-widened") {
        return fail("cross-library abstract widened property semantic state")
    }
    if (runCatching { exactAbstractProperty.exposed }.exceptionOrNull() !is ClassCastException) {
        return fail("cross-library abstract widened property delayed typed failure")
    }
    widenedAbstractProperty.exposed = 18
    if (exactAbstractProperty.exposed != 18) {
        return fail("cross-library abstract widened property recovery")
    }
    val exactNested = arrayOf(2, 3)
    val semanticNested = arrayOf<Any?>("nested", null)
    if (exactUnsafeStore.echo(exactNested) !== exactNested ||
        widenedUnsafeStore.echo(semanticNested) !== semanticNested
    ) {
        return fail("cross-library nested typed and semantic array carriers")
    }
    if (exactUnsafeStore.collide(HostileTypedStore(3)) != "typed:3" ||
        exactUnsafeStore.collide(HostileAbstractPropertyStorage(4)) != "abstract:4" ||
        widenedUnsafeStore.collide(HostileTypedStore("wide")) != "typed:wide" ||
        widenedUnsafeStore.collide(HostileAbstractPropertyStorage("semantic")) != "abstract:semantic"
    ) {
        return fail("cross-library overloaded broad semantic families")
    }
    val methodNested = arrayOf("method")
    if (exactUnsafeStore.relay(methodNested) !== methodNested) {
        return fail("cross-library nested method-generic array carrier")
    }

    val typedStore = HostileTypedStore("before")
    typedStore.write("after")
    typedStore.publish("published")
    val typedStoreRoutes = TypedStoreRouteHolder(typedStore, typedStore)
    if (typedStore.read() != "after" || typedStore.observe() != "published" ||
        readInvariantTypedStore(typedStore) != "after" ||
        readStarTypedStore(typedStore) != "after" ||
        readMergedTypedStore(false, typedStore, typedStore) != "after" ||
        readMergedTypedStore(true, typedStore, typedStore) != "after" ||
        readExactTypedStoreField(typedStoreRoutes) != "after" ||
        readStarTypedStoreField(typedStoreRoutes) != "after" ||
        readReturnedTypedStore(typedStore) != "after" ||
        labelStarUnsafeStore(exactUnsafeStore) != "default" ||
        consumerLabelStarUnsafeStore(exactUnsafeStore) != "default"
    ) {
        return fail("cross-library typed write provenance through boxed helper")
    }
    val consumerUnsafeLeaf = ConsumerUnsafeLeaf("derived")
    consumerUnsafeLeaf.writeUnsafe("changed")
    if (consumerUnsafeLeaf.read() != "changed") {
        return fail("cross-library generic subclass family")
    }
    if (consumerUnsafeLeaf.label() != "consumer:default" ||
        consumerUnsafeLeaf.label("exact") != "consumer:exact"
    ) {
        return fail("cross-library generic owner default helper family")
    }

    val libraryLeaf = LibraryIntLeaf(10)
    val libraryBase: HostileCell<Int> = libraryLeaf
    if (libraryBase.read() != 110) return fail("producer override dispatch")

    val consumerLeaf = ConsumerStringLeaf("before")
    val consumerBase: HostileCell<String> = consumerLeaf
    val consumerContract: HostileContract<String> = consumerLeaf
    if (consumerBase.write("after") != "before" ||
        consumerBase.read() != "after!" ||
        consumerContract.read() != "after!" ||
        consumerBase !== consumerContract
    ) {
        return fail("consumer override dispatch and identity")
    }

    if (!HostileCell::class.isInstance(libraryLeaf) ||
        !HostileCell::class.isInstance(consumerLeaf) ||
        !HostileCell::class.isInstance(libraryNullableLeaf) ||
        !HostileNullableDerived::class.isInstance(consumerNullableLeaf) ||
        HostileCell::class.isInstance("wrong")
    ) {
        return fail("cross-library classifier normalization")
    }

    return "OK"
}
