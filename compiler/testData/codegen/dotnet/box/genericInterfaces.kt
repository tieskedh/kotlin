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

class BoundedSpecial(private val current: Special) : BoundedProducer<Special> {
    override fun bounded(): Special = current
}

class IntProducer(private val current: Int) : Producer<Int> {
    override fun produce(): Int = current
    override val value: Int get() = current
}

fun widen(value: Producer<Special>): Producer<Item> = value

fun widenImplementation(value: SpecialProducer): Producer<Item> = value

fun narrow(value: Consumer<Item>): Consumer<Special> = value

fun nested(
    value: Producer<Producer<Special>>,
): Producer<Producer<Item>> = value

fun nullableReference(value: Producer<String?>): Producer<Any?> = value

fun exactValue(value: Producer<Int>): Producer<Int> = value

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

    val bounded: BoundedProducer<Item> = BoundedSpecial(Special("bounded"))
    if (bounded.bounded().name != "bounded") return "fail 11: bounded covariance"

    val nestedValue = GenericProducer<Producer<Special>>(SpecialProducer(Special("nested")))
    val nestedResult = nested(nestedValue).produce().produce()
    if (nestedResult.name != "nested") return "fail 12: nested covariance"

    val nullable: Producer<Any?> = nullableReference(GenericProducer<String?>(null))
    if (nullable.produce() != null) return "fail 13: nullable reference covariance"

    if (exactValue(IntProducer(9)).produce() != 9) return "fail 14: exact value instantiation"
    return "OK"
}
