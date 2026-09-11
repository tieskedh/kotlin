// DOTNET_GENERIC_OWNER_SEMANTIC_ITERATOR_RESULT_PROBE
// MODULE: lib
// FILE: api.kt
package generic.owner.iterator.result

interface Atom<out T> { fun sample(): T }
class ValueAtom<T>(private val stored: T) : Atom<T> {
    override fun sample(): T = stored
}
class One<T>(private val stored: T) : Iterator<T> {
    override fun hasNext(): Boolean = true
    override fun next(): T = stored
}
interface InvariantAtom<T> { fun sample(): T }
class InvariantValue<T>(private val stored: T) : InvariantAtom<T> {
    override fun sample(): T = stored
}
abstract class InvariantSource<out T> {
    abstract fun iterator(): Iterator<InvariantAtom<@UnsafeVariance T>>
}
class KotlinInvariantSource<T>(value: T) : InvariantSource<T>() {
    val item = InvariantValue(value)
    override fun iterator(): Iterator<InvariantAtom<T>> = One(item)
}
interface Duplex<out T> {
    fun sample(): T
    fun <R> independent(value: R): R
}
class DuplexValue<T>(private val stored: T) : Duplex<T> {
    override fun sample(): T = stored
    override fun <R> independent(value: R): R = value
}
value class Id(val raw: Int)

fun read(iterator: Iterator<Atom<Any?>>, expected: Any): Any? {
    val item = iterator.next()
    if (item !== expected) return "identity changed"
    return item.sample()
}
fun readChain(iterator: Iterator<Iterator<Atom<Any?>>>, expected: Any): Any? =
    read(iterator.next(), expected)
fun readExact(iterator: Iterator<Atom<String>>): String = iterator.next().sample()
fun readInvariant(source: InvariantSource<Any?>, expected: Any): Any? {
    val item = source.iterator().next()
    if (item !== expected) return "invariant identity changed"
    return item.sample()
}
fun independent(source: Duplex<Any?>, item: Atom<String>): String {
    val result = source.independent(item)
    if (result !== item) return "independent identity changed"
    return result.sample()
}

// MODULE: main(lib)
// FILE: main.kt
package generic.owner.iterator.result

fun box(): String {
    val number = ValueAtom(42)
    val text = ValueAtom("text")
    var iterator: Iterator<Atom<Any?>> = One(number)
    if (read(iterator, number) != 42) return "number"
    iterator = One(text)
    if (read(iterator, text) != "text") return "mutable local"
    if (readChain(One(One(number)), number) != 42) return "chain"
    val nullable = ValueAtom<Int?>(null)
    if (read(One(nullable), nullable) != null) return "nullable"
    val id = ValueAtom(Id(73))
    if ((read(One(id), id) as Id).raw != 73) return "value class"
    if (readExact(One(text)) != "text") return "exact"
    val invariant = KotlinInvariantSource(42)
    if (readInvariant(invariant, invariant.item) != 42) return "invariant"
    if (independent(DuplexValue(42), text) != "text") return "method parameter"
    return "OK"
}
