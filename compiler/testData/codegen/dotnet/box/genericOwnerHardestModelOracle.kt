// This is the legal-semantics oracle for the CLR-generic owner architecture spike.
// It deliberately combines the shapes that broke or complicated the removed typed-primary model.
// A future physical C<T> implementation must keep every assertion without wrappers or copied state.

private interface HostileContract<T> {
    fun read(): T

    fun write(next: T): T

    fun accepts(candidate: Any?): Boolean
}

private open class HostileCell<T>(initial: T) : AbstractMutableCollection<T>(), HostileContract<T> {
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

    open fun <R : T> relative(value: R): R = value

    open fun readOr(default: T = read()): T = default
}

private open class HostileMid<T>(initial: T) : HostileCell<T>(initial) {
    override fun accepts(candidate: Any?): Boolean = super.accepts(candidate)
}

private class HostileIntLeaf(initial: Int) : HostileMid<Int>(initial) {
    override fun read(): Int = super.read() + 100
}

private class HostileStringLeaf(initial: String) : HostileMid<String>(initial) {
    override fun write(next: String): String = super.write("$next!")
}

// This is the metadata-fixed inheritance case. A future physical D<T> cannot change this
// C<T?> base edge according to whether a closed T is a CLR value or reference type.
private open class HostileNullableDerived<T>(initial: T?) : HostileCell<T?>(initial) {
    override fun read(): T? = super.read()

    override fun write(next: T?): T? = super.write(next)

    fun readDirectFromBase(): T? = super.read()
}

private class HostileNullableIntLeaf(initial: Int?) : HostileNullableDerived<Int>(initial) {
    override fun read(): Int? = super.read()?.plus(1000)
}

// Unlike the fixed Collection candidate barriers, an arbitrary @UnsafeVariance body can have
// meaningful behavior for an incompatible widened candidate and must not be replaced by false.
private open class HostileUnsafeProducer<out T>(private val expected: T) {
    open fun probe(candidate: @UnsafeVariance T): String =
        if (candidate == expected) "match" else "candidate:$candidate"
}

private open class HostileMixed<in I, out O> {
    open fun describe(input: I, candidate: @UnsafeVariance O): String = "$input:$candidate"
}

private open class HostileUnsafeStore<out T>(initial: T) {
    private var stored: T = initial

    open var exposed: @UnsafeVariance T
        get() = stored
        set(value) {
            installUnchecked(value)
        }

    constructor(initial: T, marker: Int) : this(initial)

    @Suppress("UNCHECKED_CAST")
    private fun installUnchecked(candidate: Any?) {
        stored = candidate as T
    }

    open fun writeUnsafe(next: @UnsafeVariance T) {
        installUnchecked(next)
    }

    open fun read(): T = stored

    open fun echo(values: Array<out @UnsafeVariance T>): Array<out T> = values

    open fun <R> relay(values: Array<R>): Array<R> = values

    open fun label(prefix: String = "default"): String = prefix
}

// A cast-shaped helper is not automatically unsafe. This producer graph accepts only exact T at
// its public boundary, widens the local alias to Any?, and narrows it again before the write. The
// physical provenance proof must follow the value through that chain instead of trusting or
// rejecting the final logical `as T` in isolation.
private open class HostileTypedStore<T>(initial: T) {
    private var stored: T = initial

    @Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
    @kotlin.concurrent.Volatile
    private var published: T = initial

    @Suppress("UNCHECKED_CAST")
    private fun installBoxed(candidate: Any?) {
        stored = candidate as T
    }

    open fun write(next: T) {
        installBoxed(next)
    }

    open fun read(): T = stored

    open fun publish(next: T) {
        published = next
    }

    open fun observe(): T = published
}

private fun readInvariantTypedStore(store: HostileTypedStore<String>): String = store.read()

private fun readStarTypedStore(store: HostileTypedStore<*>): Any? = store.read()

private fun labelStarUnsafeStore(store: HostileUnsafeStore<*>): String = store.label()

private class TypedStoreRouteHolder(
    val exact: HostileTypedStore<String>,
    val star: HostileTypedStore<*>,
)

private fun readMergedTypedStore(
    useStar: Boolean,
    exact: HostileTypedStore<String>,
    star: HostileTypedStore<*>,
): Any? = (if (useStar) star else exact).read()

private fun readExactTypedStoreField(holder: TypedStoreRouteHolder): String = holder.exact.read()

private fun readStarTypedStoreField(holder: TypedStoreRouteHolder): Any? = holder.star.read()

private fun returnInvariantTypedStore(store: HostileTypedStore<String>): HostileTypedStore<String> = store

private fun readReturnedTypedStore(store: HostileTypedStore<String>): String =
    returnInvariantTypedStore(store).read()

private open class HostileUnsafeDerived<T>(initial: T) : HostileUnsafeStore<T>(initial) {
    override fun writeUnsafe(next: T) {
        super.writeUnsafe(next)
    }

    override fun read(): T = super.read()
}

private class HostileEnvelope<T>(val cell: HostileCell<T>)

private fun <T> openCell(value: T): HostileCell<T> = HostileCell(value)

private fun <T> openNullableCell(value: T?): HostileCell<T?> = HostileCell(value)

private fun fail(message: String): String = "fail: $message"

fun box(): String {
    val ints = HostileCell(1)
    ints.add(2)
    if (ints.toString() != "[1, 2]") return fail("mutable generic state")

    // This legal covariant collection view exposed the old typed-primary bridge bug: a String
    // candidate must be inspected and rejected, not narrowed to the physical Int argument first.
    val widened: Collection<Any?> = ints
    if (!widened.contains(1) || widened.contains("wrong") || widened.contains(null)) {
        return fail("widened candidate barrier")
    }
    if (!widened.containsAll(listOf<Any?>(1, 2)) ||
        widened.containsAll(listOf<Any?>(1, "wrong")) ||
        widened.containsAll(listOf<Any?>(1, null)) ||
        !widened.containsAll(emptyList<Any?>())
    ) {
        return fail("widened nested candidate barrier")
    }

    val output: HostileCell<out Any?> = ints
    if (output.read() != 1) return fail("output projection")
    if (ints.relative(3) != 3) return fail("owner-relative method bound")
    val star: HostileCell<*> = ints
    if (star !== ints || star.read() != 1 || !star.accepts(2) || star.accepts("wrong")) {
        return fail("star identity and calls")
    }

    val anyCell = HostileCell<Any?>("seed")
    val input: HostileCell<in Int> = anyCell
    if (input.write(7) != "seed" || anyCell.read() != 7) return fail("input projection state")

    val nullableInt = openNullableCell<Int>(null)
    if (nullableInt.read() != null || nullableInt.write(8) != null || nullableInt.read() != 8) {
        return fail("open nullable value construction")
    }
    val nullableString = openNullableCell<String>(null)
    if (nullableString.write("text") != null || nullableString.read() != "text") {
        return fail("open nullable reference construction")
    }

    val broadPropertyOwner = HostileUnsafeStore(11)
    val broadPropertyView: HostileUnsafeStore<Any?> = broadPropertyOwner
    broadPropertyView.exposed = "property-widened"
    if (broadPropertyView.exposed != "property-widened") {
        return fail("widened property semantic state")
    }
    if (runCatching { broadPropertyOwner.exposed }.exceptionOrNull() !is ClassCastException) {
        return fail("widened property delayed typed failure")
    }

    val nullableDerivedInt = HostileNullableIntLeaf(null)
    val nullableDerivedIntBase: HostileCell<Int?> = nullableDerivedInt
    if (nullableDerivedIntBase.write(5) != null ||
        nullableDerivedIntBase.read() != 1005 ||
        nullableDerivedInt.readDirectFromBase() != 5
    ) {
        return fail("metadata-fixed nullable value inheritance")
    }
    val nullableDerivedString = HostileNullableDerived<String>(null)
    val nullableDerivedStringBase: HostileCell<String?> = nullableDerivedString
    if (nullableDerivedStringBase.write("derived") != null ||
        nullableDerivedStringBase.read() != "derived" ||
        nullableDerivedString.readDirectFromBase() != "derived"
    ) {
        return fail("metadata-fixed nullable reference inheritance")
    }

    val unsafeProducer: HostileUnsafeProducer<Any?> = HostileUnsafeProducer(1)
    if (unsafeProducer.probe(1) != "match" ||
        unsafeProducer.probe("wrong") != "candidate:wrong" ||
        unsafeProducer.probe(null) != "candidate:null"
    ) {
        return fail("general widened semantic body")
    }
    val exactMixed: HostileMixed<Number, Int> = HostileMixed()
    val widenedMixed: HostileMixed<Int, Any?> = exactMixed
    if (widenedMixed.describe(7, "wrong") != "7:wrong") {
        return fail("mixed strict and broad input domains")
    }

    val exactUnsafeStore = HostileUnsafeStore(1)
    val widenedUnsafeStore: HostileUnsafeStore<Any?> = exactUnsafeStore
    widenedUnsafeStore.writeUnsafe("wrong")
    if (widenedUnsafeStore.read() != "wrong") {
        return fail("widened semantic state read")
    }
    try {
        val impossible = exactUnsafeStore.read() + 1
        return fail("exact use accepted widened incompatible state: $impossible")
    } catch (_: ClassCastException) {
        // Kotlin/JVM-style erasure accepts the legal widened write and fails at this exact use.
    }
    widenedUnsafeStore.writeUnsafe(2)
    if (exactUnsafeStore.read() != 2) {
        return fail("semantic state recovery")
    }
    val exactNested = arrayOf(2, 3)
    val semanticNested = arrayOf<Any?>("nested", null)
    if (exactUnsafeStore.echo(exactNested) !== exactNested ||
        widenedUnsafeStore.echo(semanticNested) !== semanticNested
    ) {
        return fail("nested typed and semantic array carriers")
    }
    val methodNested = arrayOf("method")
    if (exactUnsafeStore.relay(methodNested) !== methodNested) {
        return fail("nested method-generic array carrier")
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
        labelStarUnsafeStore(exactUnsafeStore) != "default"
    ) {
        return fail("typed write provenance through boxed helper")
    }
    val unsafeDerived = HostileUnsafeDerived("derived")
    unsafeDerived.writeUnsafe("changed")
    if (unsafeDerived.read() != "changed") {
        return fail("generic subclass semantic family")
    }
    if (unsafeDerived.label() != "default" || unsafeDerived.label("exact") != "exact") {
        return fail("generic owner default helper family")
    }

    val contract: HostileContract<Int> = ints
    if (contract.read() != 1 || contract.write(9) != 1 || !contract.accepts(2)) {
        return fail("generic interface capability")
    }
    if (ints.read() != 9) return fail("interface and owner state identity")

    val intLeaf = HostileIntLeaf(10)
    val intBase: HostileCell<Int> = intLeaf
    val intContract: HostileContract<Int> = intLeaf
    if (intBase.read() != 110 || intContract.read() != 110 || intBase !== intContract) {
        return fail("multi-level value override dispatch")
    }
    val stringLeaf = HostileStringLeaf("before")
    val stringBase: HostileCell<String> = stringLeaf
    if (stringBase.write("after") != "before" || stringBase.read() != "after!") {
        return fail("multi-level reference override state")
    }

    val envelope = HostileEnvelope(openCell("nested"))
    if (envelope.cell.readOr() != "nested" || envelope.cell.readOr("default") != "default") {
        return fail("nested owner and defaults")
    }
    val cells = arrayOf<HostileCell<*>>(ints, stringLeaf, nullableInt)
    if (cells.size != 3 || cells[0].read() != 9 || cells[1].read() != "after!" || cells[2].read() != 8) {
        return fail("generic-owner array and star reads")
    }

    if (!HostileCell::class.isInstance(ints) ||
        !HostileCell::class.isInstance(intLeaf) ||
        !HostileCell::class.isInstance(nullableDerivedInt) ||
        !HostileNullableDerived::class.isInstance(nullableDerivedInt) ||
        HostileCell::class.isInstance("wrong") ||
        HostileCell::class == HostileMid::class
    ) {
        return fail("classifier normalization")
    }

    return "OK"
}
