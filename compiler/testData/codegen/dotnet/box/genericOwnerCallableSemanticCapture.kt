// DOTNET_GENERIC_OWNER_CALLABLE_SEMANTIC_CAPTURE_PROBE
// MODULE: lib
// FILE: receiver.kt
package generic.owner.callable.capture

interface Source<out T> { fun read(): T }
class Value<T>(private val stored: T) : Source<T> {
    override fun read(): T = stored
}

class Receiver<T>(private val source: Source<T>) {
    fun consumer(): (Source<T>) -> Int = { value -> if (value === source) 42 else 7 }
    fun producer(): () -> Source<T> = { source }
    fun mixed(): (Source<T>, Int) -> Long = { value, number ->
        if (value === source) number.toLong() else -1L
    }
    fun nominal(): (Source<T>) -> Id = { value -> Id(if (value === source) 42 else 7) }
}

class Plain<T>(private val stored: T) {
    fun choose(): (T, Boolean) -> T = { other, useStored -> if (useStored) stored else other }
}

value class Id(val raw: Int)

fun <T> nullableProducer(): () -> T? = { null }
fun <T> nullableConsumer(): (T?) -> Boolean = { it == null }

// MODULE: main(lib)
// FILE: main.kt
package generic.owner.callable.capture

fun box(): String {
    val number = Value(42)
    val receiver = Receiver(number)
    val consume = receiver.consumer()
    if (consume(number) != 42 || consume(Value(42)) != 7) return "consumer"
    val produce = receiver.producer()
    if (produce() !== number) return "producer identity"
    val widened: () -> Source<Any?> = produce
    if (widened !== produce || widened().read() != 42) return "widening"
    val text = Value("text")
    if (Receiver(text).consumer()(text) != 42) return "reference"
    if (receiver.mixed()(number, 73) != 73L) return "mixed"
    if (receiver.nominal()(number).raw != 42) return "nominal"
    val nullable = Value<Int?>(null)
    if (Receiver(nullable).producer()().read() != null) return "nullable payload"
    val id = Value(Id(73))
    if (Receiver(id).producer()().read().raw != 73) return "nominal payload"
    var selected: () -> Source<Any?> = produce
    if (selected().read() != 42) return "joined number"
    selected = Receiver(text).producer()
    if (selected().read() != "text") return "joined text"
    val choose = Plain(42).choose()
    if (choose(73, true) != 42 || choose(73, false) != 73) return "typed capture"
    if (Plain("stored").choose()("other", true) != "stored") return "typed reference"
    if (Plain<Int?>(null).choose()(42, true) != null) return "typed nullable"
    if (Plain(Id(73)).choose()(Id(42), true).raw != 73) return "typed nominal"
    if (nullableProducer<Int>()() != null) return "open nullable result"
    if (!nullableConsumer<Int>()(null) || nullableConsumer<Int>()(73)) return "open nullable input"
    return "OK"
}
