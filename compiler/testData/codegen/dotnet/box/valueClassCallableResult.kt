// MODULE: lib
// FILE: library.kt
package values.callable

value class Id(val raw: Int)
value class Text(val raw: String)
value class Maybe(val raw: Int?)

fun supplier(): () -> Id = { Id(42) }
fun mapping(): (Int) -> Id = { Id(it) }
fun text(): () -> Text = { Text("OK") }
fun maybe(): () -> Maybe = { Maybe(null) }
fun absent(): () -> Id? = { null }
class Manual : () -> Id {
    override fun invoke(): Id = Id(51)
}
fun manual(): () -> Id = Manual()
fun readId(value: Any?): Int = (value as Id).raw
fun readText(value: Any?): String = (value as Text).raw
fun readMaybe(value: Any?): Int? = (value as Maybe).raw

interface Source<out T> { fun read(): T }
class Value<T>(private val stored: T) : Source<T> {
    override fun read(): T = stored
}
class Receiver<T>(private val source: Source<T>) {
    fun nominal(): (Source<T>) -> Id = { value -> Id(if (value === source) 42 else 7) }
    fun producer(): () -> Source<T> = { source }
}
class Plain<T>(private val stored: T) {
    fun choose(): (T, Boolean) -> T = { other, useStored -> if (useStored) stored else other }
}
fun input(): Source<Int> = Value(0)
fun nominalCallable(): (Source<Int>) -> Id = Receiver(input()).nominal()

// MODULE: main(lib)
// FILE: main.kt
package values.callable

fun box(): String {
    if (supplier()().raw != 42) return "supplier"
    if (mapping()(73).raw != 73) return "mapping"
    if (manual()().raw != 51) return "manual"
    if (text()().raw != "OK") return "reference underlying"
    if (maybe()().raw != null || absent()() != null) return "nullable underlying or outer"
    val number = Value(42)
    if (Receiver(number).nominal()(number).raw != 42) return "nominal value"
    val id = Value(Id(73))
    if (Receiver(id).producer()().read().raw != 73) return "producer value"
    if (Plain(Id(73)).choose()(Id(42), true).raw != 73) return "choose value"
    return "OK"
}
