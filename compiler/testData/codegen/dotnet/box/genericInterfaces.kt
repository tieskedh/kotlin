open class Item(val name: String)

class Special(name: String) : Item(name)

interface Producer<out T> {
    fun produce(): T
    val value: T
}

interface Consumer<in T> {
    fun consume(value: T)
}

interface Channel<T> : Producer<T>, Consumer<T>

interface PairView<A, B> {
    fun first(): A
    fun second(): B
}

interface Mixed<in I, out O, X> {
    fun run(input: I, state: X): O
    fun acceptsOutput(value: @UnsafeVariance O): Boolean
}

interface Swapped<A, B> : PairView<B, A>

interface BoundedProducer<out T : Item> {
    fun bounded(): T
}

class GenericChannel<T>(private var current: T) : Channel<T> {
    override fun produce(): T = current
    override val value: T get() = current
    override fun consume(value: T) {
        current = value
    }
}

class GenericProducer<T>(private val current: T) : Producer<T> {
    override fun produce(): T = current
    override val value: T get() = current
}

class SpecialProducer(private val current: Special) : Producer<Special> {
    override fun produce(): Special = current
    override val value: Special get() = current
}

class RecordingConsumer : Consumer<Item> {
    var seen: String = ""

    override fun consume(value: Item) {
        seen = value.name
    }
}

class SwappedPair<A, B>(private val a: A, private val b: B) : Swapped<A, B> {
    override fun first(): B = b
    override fun second(): A = a
}

class MixedImplementation : Mixed<Any, Int, String> {
    override fun run(input: Any, state: String): Int = 5
    override fun acceptsOutput(value: Int): Boolean = value > 0
}

class BoundedSpecial(private val current: Special) : BoundedProducer<Special> {
    override fun bounded(): Special = current
}

class IntProducer(private val current: Int) : Producer<Int> {
    override fun produce(): Int = current
    override val value: Int get() = current
}

class RefinedProducer : Producer<Any> {
    override fun produce(): String = "refined"
    override val value: String get() = "refined property"
}

class AnyConsumer : Consumer<Any> {
    var seen: Any? = null

    override fun consume(value: Any) {
        seen = value
    }
}

interface NestedFactory<out T> {
    fun make(): Producer<T>
}

class IntFactory(private val producer: Producer<Int>) : NestedFactory<Int> {
    override fun make(): Producer<Int> = producer
}

interface NestedSink<out T> {
    fun accepts(value: Producer<@UnsafeVariance T>): Boolean
}

class IntNestedSink : NestedSink<Int> {
    override fun accepts(value: Producer<Int>): Boolean = value.produce() == 17
}

interface VariantCell<out T> {
    var value: @UnsafeVariance T
}

class IntCell(override var value: Int) : VariantCell<Int>

interface ResultSource<out T> {
    fun result(): T
}

class Payload<T>(val value: T)

interface PayloadFactory<T> {
    fun makePayload(): Payload<T>
}

class IntPayloadFactory : PayloadFactory<Int> {
    override fun makePayload(): Payload<Int> = Payload(19)
}

open class InheritedSource {
    open fun result(): Special = Special("inherited")
}

class InheritedResultSource : InheritedSource(), ResultSource<Item>

fun widen(value: Producer<Special>): Producer<Item> = value

fun widenImplementation(value: SpecialProducer): Producer<Item> = value

fun narrow(value: Consumer<Item>): Consumer<Special> = value

fun widenMixed(value: Mixed<Any, Int, String>): Mixed<Int, Any, String> = value

fun nested(
    value: Producer<Producer<Special>>,
): Producer<Producer<Item>> = value

fun nullableReference(value: Producer<String?>): Producer<Any?> = value

fun nullableValue(value: Producer<Int?>): Producer<Any?> = value

fun <T> widenOpen(value: Producer<T>): Producer<Any?> = value

fun exactValue(value: Producer<Int>): Producer<Int> = value

fun widenValue(value: Producer<Int>): Producer<Any> = value

fun narrowValue(value: Consumer<Any>): Consumer<Int> = value

fun widenFactory(value: NestedFactory<Int>): NestedFactory<Any> = value

fun widenNestedSink(value: NestedSink<Int>): NestedSink<Any> = value

fun widenCell(value: VariantCell<Int>): VariantCell<Any> = value

@Suppress("UNCHECKED_CAST")
fun castIntProducer(value: Any): Producer<Int> = value as Producer<Int>

@Suppress("UNCHECKED_CAST")
fun castAnyProducer(value: Any): Producer<Any> = value as Producer<Any>

fun castStarProducer(value: Any): Producer<*> = value as Producer<*>

fun castProjectedProducer(value: Any): Producer<out Any> = value as Producer<out Any>

@Suppress("UNCHECKED_CAST")
fun safeStringProducer(value: Any?): Producer<String>? = value as? Producer<String>

fun safeStarProducer(value: Any?): Producer<*>? = value as? Producer<*>

fun nullableProducerCast(value: Any?): Producer<*>? = value as Producer<*>?

fun requiredProducerCast(value: Any?): Producer<*> = value as Producer<*>

fun isProducer(value: Any?): Boolean = value is Producer<*>

fun isNotProducer(value: Any?): Boolean = value !is Producer<*>

fun isNullableProducer(value: Any?): Boolean = value is Producer<*>?

fun isNotNullableProducer(value: Any?): Boolean = value !is Producer<*>?

@Suppress("UNCHECKED_CAST")
fun castMixed(value: Any): Mixed<Int, Any, String> = value as Mixed<Int, Any, String>

var castEvaluationCount: Int = 0

fun countedCastOperand(value: Any?): Any? {
    castEvaluationCount = castEvaluationCount + 1
    return value
}

fun box(): String {
    val direct: Producer<Item> = SpecialProducer(Special("direct"))
    if (direct.produce().name != "direct") return "fail 1: direct covariance"

    val throughFunction = widen(SpecialProducer(Special("function")))
    if (throughFunction.value.name != "function") return "fail 2: function covariance"

    val throughImplementation = widenImplementation(SpecialProducer(Special("implementation")))
    if (throughImplementation.produce().name != "implementation") return "fail 3: class covariance"

    val recording = RecordingConsumer()
    val narrowed: Consumer<Special> = narrow(recording)
    narrowed.consume(Special("contravariant"))
    if (recording.seen != "contravariant") return "fail 4: contravariance"

    val channelImpl = GenericChannel("first")
    val channel: Channel<String> = channelImpl
    if (channel.produce() != "first" || channel.value != "first") return "fail 5: generic channel read"
    channel.consume("second")
    if (channelImpl.produce() != "second") return "fail 6: generic channel write"

    val intChannel: Channel<Int> = GenericChannel(1)
    intChannel.consume(2)
    if (intChannel.produce() != 2) return "fail 7: open value implementation"

    val transitiveProducer: Producer<Item> = GenericChannel(Special("transitive out"))
    if (transitiveProducer.produce().name != "transitive out") return "fail 8: transitive covariance"

    val transitiveChannel = GenericChannel<Item>(Item("before"))
    val transitiveConsumer: Consumer<Special> = transitiveChannel
    transitiveConsumer.consume(Special("transitive in"))
    if (transitiveChannel.produce().name != "transitive in") return "fail 9: transitive contravariance"

    val pair: PairView<Int, String> = SwappedPair("left", 7)
    if (pair.first() != 7 || pair.second() != "left") return "fail 10: permuted interface owner"

    val originalMixed: Mixed<Any, Int, String> = MixedImplementation()
    val mixed: Mixed<Int, Any, String> = widenMixed(originalMixed)
    if (mixed !== originalMixed) return "fail 10a: mixed variance changed identity"
    if (mixed.run(123, "ab") != 5) return "fail 10b: mixed declared member"
    if (!mixed.acceptsOutput(1)) return "fail 10c: mixed exact member"

    val bounded: BoundedProducer<Item> = BoundedSpecial(Special("bounded"))
    if (bounded.bounded().name != "bounded") return "fail 11: bounded covariance"

    val nestedValue = GenericProducer<Producer<Special>>(SpecialProducer(Special("nested")))
    val nestedResult = nested(nestedValue).produce().produce()
    if (nestedResult.name != "nested") return "fail 12: nested covariance"

    val nullable: Producer<Any?> = nullableReference(GenericProducer<String?>(null))
    if (nullable.produce() != null) return "fail 13: nullable reference covariance"

    if (exactValue(IntProducer(9)).produce() != 9) return "fail 14: exact value instantiation"

    val intIdentity: Producer<Int> = IntProducer(10)
    val anyIdentity: Producer<Any> = intIdentity
    if (intIdentity !== anyIdentity) return "fail 15: widening changed identity"
    if (widenValue(intIdentity).produce() != 10) return "fail 16: primitive result widening"

    val anyConsumer = AnyConsumer()
    narrowValue(anyConsumer).consume(11)
    if (anyConsumer.seen != 11) return "fail 17: primitive argument narrowing"

    val refined: Producer<Any> = RefinedProducer()
    if (refined.produce() != "refined") return "fail 18: covariant return bridge"
    if (refined.value != "refined property") return "fail 19: covariant property bridge"

    val nestedFactory: NestedFactory<Any> = widenFactory(IntFactory(IntProducer(12)))
    if (nestedFactory.make().produce() != 12) return "fail 20: nested canonical carrier"

    val cellImpl = IntCell(13)
    val cell: VariantCell<Any> = widenCell(cellImpl)
    if (cell.value != 13) return "fail 21: unsafe property getter"
    cell.value = 14
    if (cellImpl.value != 14) return "fail 22: unsafe property setter"

    val projected: Producer<out Any> = intIdentity
    val starred: Producer<*> = intIdentity
    if (projected.produce() != 10) return "fail 23: projected identity"
    if (starred.produce() != 10) return "fail 24: star-projected identity"

    val nullableInt = GenericProducer<Int?>(15)
    val nullableAny = nullableValue(nullableInt)
    if (nullableInt !== nullableAny) return "fail 25: nullable value widening changed identity"
    if (nullableAny.produce() != 15) return "fail 26: nullable value widening"
    if (nullableValue(GenericProducer<Int?>(null)).produce() != null) return "fail 27: nullable value null"

    val openInt = GenericProducer(16)
    val openIntAny = widenOpen(openInt)
    if (openInt !== openIntAny || openIntAny.produce() != 16) return "fail 28: open value widening"
    val openString = GenericProducer("open")
    val openStringAny = widenOpen(openString)
    if (openString !== openStringAny || openStringAny.produce() != "open") return "fail 29: open reference widening"

    val projectedChannel: Channel<out String> = channelImpl
    val starredChannel: Channel<*> = channelImpl
    if (projectedChannel !== channelImpl || projectedChannel.produce() != "second") {
        return "fail 30: invariant projected identity"
    }
    if (starredChannel !== channelImpl || starredChannel.value != "second") {
        return "fail 31: invariant star identity"
    }

    val inherited: ResultSource<Item> = InheritedResultSource()
    if (inherited.result().name != "inherited") return "fail 32: inherited covariant bridge"
    if (!widenNestedSink(IntNestedSink()).accepts(IntProducer(17))) {
        return "fail 33: nested canonical placement"
    }

    val castOperand: Any = intIdentity
    val castExact = castIntProducer(castOperand)
    if (castExact !== intIdentity || castExact.produce() != 10) {
        return "fail 34: exact hard cast"
    }
    val castWide = castAnyProducer(castOperand)
    if (castWide !== intIdentity || castWide.produce() != 10) {
        return "fail 35: widened hard cast"
    }
    if (castStarProducer(castOperand) !== intIdentity || castProjectedProducer(castOperand) !== intIdentity) {
        return "fail 36: projected hard cast identity"
    }

    val safeMismatchedArguments = safeStringProducer(castOperand)
    val safeMismatchedIdentity: Any? = safeMismatchedArguments
    if (safeMismatchedIdentity !== intIdentity) return "fail 37: safe cast inspected logical arguments"
    if (safeStarProducer(Item("not a producer")) != null) return "fail 38: safe cast mismatch"
    if (safeStarProducer(null) != null) return "fail 39: safe cast null"
    if (nullableProducerCast(null) != null) return "fail 40: nullable hard cast null"

    try {
        castStarProducer(Item("not a producer"))
        return "fail 41: hard cast mismatch did not throw"
    } catch (_: ClassCastException) {
    }
    try {
        requiredProducerCast(null)
        return "fail 42: non-null hard cast accepted null"
    } catch (_: NullPointerException) {
    }

    if (!isProducer(castOperand) || isProducer(Item("not a producer")) || isProducer(null)) {
        return "fail 43: erased type test"
    }
    if (isNotProducer(castOperand) || !isNotProducer(Item("not a producer")) || !isNotProducer(null)) {
        return "fail 44: erased negative type test"
    }
    if (!isNullableProducer(castOperand) || !isNullableProducer(null) || isNotNullableProducer(null)) {
        return "fail 45: nullable erased type test"
    }

    val castMixed = castMixed(originalMixed)
    if (castMixed !== originalMixed || castMixed.run(1, "state") != 5 || !castMixed.acceptsOutput(1)) {
        return "fail 46: mixed variance cast"
    }

    castEvaluationCount = 0
    if (safeStarProducer(countedCastOperand(castOperand)) !== intIdentity || castEvaluationCount != 1) {
        return "fail 47: safe cast evaluated operand more than once"
    }
    val payloadFactory: PayloadFactory<Int> = IntPayloadFactory()
    if (payloadFactory.makePayload().value != 19) {
        return "fail 48: erased generic-class carrier nested in generic-interface result"
    }
    return "OK"
}
