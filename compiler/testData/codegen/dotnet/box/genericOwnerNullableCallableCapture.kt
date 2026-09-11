// DOTNET_GENERIC_OWNER_NULLABLE_CALLABLE_CAPTURE_PROBE
// MODULE: lib
// FILE: receiver.kt
package generic.owner.nullable.capture

class Plain<T>(private val stored: T) {
    fun maybe(useNull: Boolean): () -> T? = { if (useNull) null else stored }
    fun nullableInput(): (T?) -> Boolean = { it == null }
    fun accepts(): (T?) -> Boolean = { it == stored }
    fun snapshot(): () -> T = { stored }
    fun select(other: T?): (Boolean, Int) -> T? = { useStored, number ->
        if (number < 0) null else if (useStored) stored else other
    }
}

value class Id(val raw: Int)
fun nominalPlain(): Plain<Id> = Plain(Id(73))

// MODULE: main(lib)
// FILE: main.kt
package generic.owner.nullable.capture

fun box(): String {
    if (Plain(42).maybe(false)() != 42 || Plain(42).maybe(true)() != null) return "nullable result"
    if (!Plain(42).nullableInput()(null) || Plain(42).nullableInput()(73)) return "nullable input"
    if (!Plain(42).accepts()(42) || Plain(42).accepts()(73)) return "captured nullable input"
    if (Plain("text").maybe(false)() != "text" || Plain("text").maybe(true)() != null) return "reference result"
    if (Plain<Int?>(null).maybe(false)() != null || Plain<Int?>(42).maybe(false)() != 42) return "nullable substitution"
    if (!Plain<Int?>(null).accepts()(null) || Plain<Int?>(null).accepts()(42)) return "nullable comparison"
    val nominal = nominalPlain()
    if (nominal.maybe(false)()?.raw != 73 || nominal.maybe(true)() != null) return "nominal result"
    if (!nominal.accepts()(Id(73)) || nominal.accepts()(Id(42))) return "nominal input"
    val select = Plain(42).select(73)
    if (select(true, 0) != 42 || select(false, 0) != 73 || select(true, -1) != null) return "mixed captures"
    if (Plain("text").select(null)(false, 0) != null) return "mixed reference"
    if (nominal.select(Id(42))(false, 0)?.raw != 42) return "mixed nominal"
    val exact = Plain(42).snapshot()
    val wide: () -> Any? = exact
    if (exact !== wide || wide() != 42) return "callable identity"
    var selected: () -> Any? = Plain(42).maybe(false)
    if (selected() != 42) return "mutable int"
    selected = Plain("text").maybe(false)
    if (selected() != "text") return "mutable text"
    return "OK"
}
