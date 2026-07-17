// Kotlin-owned generic interfaces have one non-generic canonical identity plus optional typed CLR
// capabilities on the same object. Kotlin ABI storage always uses the canonical view, including
// value/reference widening, projections, and stars. Declared capabilities retain legal CLR
// variance; unsafe members live on an invariant exact capability. Compiler-generated MethodImpl
// bridges bind both physical contracts to the source implementation.

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

class ItemConsumer : Consumer<Item> {
    override fun consume(value: Item) {
        println(value.name)
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
    override fun consume(value: Any) {
        println(value)
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
fun castProducer(value: Any): Producer<Int> = value as Producer<Int>

@Suppress("UNCHECKED_CAST")
fun safeProducer(value: Any?): Producer<String>? = value as? Producer<String>

fun isProducer(value: Any?): Boolean = value is Producer<*>

fun isNullableProducer(value: Any?): Boolean = value is Producer<*>?

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
    val originalMixed: Mixed<Any, Int, String> = MixedImplementation()
    val mixed: Mixed<Int, Any, String> = widenMixed(originalMixed)
    println(mixed === originalMixed)
    println(mixed.run(123, "ab"))
    println(mixed.acceptsOutput(1))
    val bounded: BoundedProducer<Item> = BoundedSpecial(Special("bounded"))
    println(bounded.bounded().name)
    val nestedValue = GenericProducer<Producer<Special>>(SpecialProducer(Special("nested")))
    println(nested(nestedValue).produce().produce().name)
    println(exactValue(IntProducer(9)).produce())
    println(widenValue(IntProducer(10)).produce())
    narrowValue(AnyConsumer()).consume(11)
    val refined: Producer<Any> = RefinedProducer()
    println(refined.produce())
    println(refined.value)
    println(widenFactory(IntFactory(IntProducer(12))).make().produce())
    val cell = widenCell(IntCell(13))
    println(cell.value)
    cell.value = 14
    val nullableInt = GenericProducer<Int?>(15)
    println(nullableValue(nullableInt).produce())
    println(nullableValue(GenericProducer<Int?>(null)).produce())
    println(widenOpen(GenericProducer(16)).produce())
    println(widenOpen(GenericProducer("open")).produce())
    val projectedChannel: Channel<out String> = channel
    val starredChannel: Channel<*> = channel
    println(projectedChannel.produce())
    println(starredChannel.value)
    val inherited: ResultSource<Item> = InheritedResultSource()
    println(inherited.result().name)
    println(widenNestedSink(IntNestedSink()).accepts(IntProducer(17)))
    val castOperand: Any = IntProducer(18)
    println(castProducer(castOperand).produce())
    println(safeProducer(castOperand) != null)
    println(safeProducer(Item("not a producer")) == null)
    println(isProducer(castOperand))
    println(isNullableProducer(null))
}
