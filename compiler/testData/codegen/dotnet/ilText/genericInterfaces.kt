// Generic interfaces use real CLR reified generics. Declaration-site `out`/`in` maps to the
// `+`/`-` generic-parameter metadata flags, while invariant parameters stay unmarked. Direct
// interface edges retain their full open or closed instantiation in `implements`, including
// generic-interface inheritance and permuted parameters. Reference-only variant conversions are
// instruction-free; exact value-type instantiations remain valid and invariant at conversion
// sites.

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

class ItemConsumer : Consumer<Item> {
    override fun consume(value: Item) {
        println(value.name)
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

fun main() {
    val producer: Producer<Item> = SpecialProducer(Special("special"))
    println(producer.produce().name)
    val consumer: Consumer<Special> = ItemConsumer()
    consumer.consume(Special("consumed"))
    val channel: Channel<String> = GenericChannel("channel")
    println(channel.produce())
    channel.consume("changed")
    println(channel.value)
    val pair: PairView<Int, String> = SwappedPair("left", 7)
    println(pair.first())
    println(pair.second())
    val bounded: BoundedProducer<Item> = BoundedSpecial(Special("bounded"))
    println(bounded.bounded().name)
    val nestedValue = GenericProducer<Producer<Special>>(SpecialProducer(Special("nested")))
    println(nested(nestedValue).produce().produce().name)
    println(exactValue(IntProducer(9)).produce())
}
