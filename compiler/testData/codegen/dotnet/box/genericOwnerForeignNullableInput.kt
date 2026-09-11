// DOTNET_GENERIC_OWNER_FOREIGN_NULLABLE_INPUT_PROBE
// MODULE: lib
// FILE: lookup.kt
package generic.owner.foreign.nullable.input

interface NullableLookup<K, out V> {
    fun find(key: K?, missing: Boolean): V?
}

value class Id(val raw: Int)
fun id(): Any = Id(53)
fun readId(value: Any): Int = (value as Id).raw

open class Store<K, out V>(private val anchor: K, initial: V) : NullableLookup<K, V> {
    private var value: V = initial

    override fun find(key: K?, missing: Boolean): V? =
        if (missing || key != anchor) null else value

    fun anchor(): K = anchor
    fun replace(next: @UnsafeVariance V) { value = next }
}

fun exact(store: Store<Int, Int>, key: Int?, missing: Boolean): Int? = store.find(key, missing)
fun wide(store: Store<Int, Any?>, key: Int?, missing: Boolean): Any? = store.find(key, missing)
fun throughInterface(store: NullableLookup<Int, Any?>, key: Int?, missing: Boolean): Any? = store.find(key, missing)
fun same(store: Store<Int, Any?>, other: Any): Boolean = store === other

open class Reader<K, out V>(private val anchor: K, initial: V, private val alternative: V) {
    private var value: V = initial

    open fun read(key: K?, alternate: Boolean): V =
        if (key == anchor && !alternate) value else alternative

    open fun readArray(keys: Array<out K>, alternate: Boolean): V =
        if (keys.size != 0 && !alternate) value else alternative

    fun anchor(): K = anchor
    fun replace(next: @UnsafeVariance V) { value = next }
}

fun readExact(reader: Reader<Int, Int>, key: Int?, alternate: Boolean): Int = reader.read(key, alternate)
fun readWide(reader: Reader<Int, Any?>, key: Int?, alternate: Boolean): Any? = reader.read(key, alternate)
fun readStar(reader: Reader<*, *>): Any? = reader.read(null, false)
fun sameReader(reader: Reader<Int, Any?>, other: Any): Boolean = reader === other
fun readArrayExact(reader: Reader<Int, Int>, keys: Array<Int>, alternate: Boolean): Int = reader.readArray(keys, alternate)
fun readArrayWide(reader: Reader<Int, Any?>, keys: Array<Int>, alternate: Boolean): Any? = reader.readArray(keys, alternate)

class Cell<T>(val value: T)

open class NamedInput<out V>(initial: V) {
    private var value: V = initial
    open fun read(key: Cell<Int>): V = value
    fun replace(next: @UnsafeVariance V) { value = next }
}

open class UnequalInput<K, out V>(initial: V) {
    private var value: V = initial
    open fun read(key: K): V = value
    fun replace(next: @UnsafeVariance V) { value = next }
}

open class BroadInput<out K, out V>(initial: V) {
    private var value: V = initial
    open fun read(key: @UnsafeVariance K?): V = value
    fun replace(next: @UnsafeVariance V) { value = next }
}

// MODULE: middle(lib)
// FILE: middle.kt
package generic.owner.foreign.nullable.input

open class Middle<K, V>(anchor: K, initial: V) : Store<K, V>(anchor, initial)

open class KotlinOverride(initial: Int) : Store<Int, Int>(7, initial) {
    override fun find(key: Int?, missing: Boolean): Int? =
        if (key == null) 97 else super.find(key, missing)
}

open class MiddleReader<K, V>(anchor: K, initial: V, alternative: V) : Reader<K, V>(anchor, initial, alternative)

open class KotlinReader(initial: Int) : Reader<Int, Int>(7, initial, 59) {
    override fun read(key: Int?, alternate: Boolean): Int =
        if (key == null) 97 else super.read(key, alternate)

    fun parent(key: Int?, alternate: Boolean): Int = super.read(key, alternate)
}

// MODULE: main(lib, middle)
// FILE: main.kt
package generic.owner.foreign.nullable.input

fun box(): String {
    val reader = Reader(7, 41, 43)
    if (readExact(reader, 7, false) != 41 || readExact(reader, null, false) != 43 || readExact(reader, 7, true) != 43) return "reader exact"
    val broadReader: Reader<Int, Any?> = reader
    broadReader.replace("wide")
    if (readWide(broadReader, 7, false) != "wide" || readWide(broadReader, 7, true) != 43 || !sameReader(broadReader, reader)) return "reader wide"
    if (readArrayWide(broadReader, arrayOf(7), false) != "wide" || readArrayWide(broadReader, arrayOf(), false) != 43 ||
        readArrayExact(Reader(7, 47, 53), arrayOf(7), false) != 47) return "reader arrays"
    val overriddenReader = KotlinReader(47)
    if (readWide(overriddenReader, null, false) != 97 || overriddenReader.parent(null, false) != 59 || readWide(overriddenReader, 7, false) != 47) return "reader override"
    val stringReader = MiddleReader("key", "value", "alternative")
    if (stringReader.read("key", false) != "value" || readStar(stringReader) != "alternative") return "reader reference"
    val nullReader = Reader<Int?, Int?>(null, null, 53)
    if (nullReader.read(null, false) != null || nullReader.read(null, true) != 53) return "reader nullable"
    val nominalReader = Reader(Id(53), Id(59), Id(61))
    if (nominalReader.read(Id(53), false).raw != 59 || nominalReader.read(null, false).raw != 61) return "reader nominal"
    if (UnequalInput<Int, Int>(67).read(7) != 67) return "unequal input"
    val broadInput: BroadInput<Any?, Any?> = BroadInput<Int, Int>(71)
    if (broadInput.read("candidate") != 71) return "broad input"
    if (NamedInput(73).read(Cell(7)) != 73) return "unbound named input"
    val base = Store(7, 41)
    if (exact(base, 7, false) != 41 || exact(base, null, false) != null || exact(base, 7, true) != null) return "exact"
    val widened: Store<Int, Any?> = base
    if (!same(widened, base) || throughInterface(widened, 7, false) != 41) return "identity"
    widened.replace("wide")
    if (wide(widened, 7, false) != "wide" || throughInterface(widened, 7, false) != "wide") return "state"
    val child = KotlinOverride(43)
    if (wide(child, null, false) != 97 || wide(child, 7, false) != 43) return "override"
    val reference = Middle("key", "value")
    if (reference.find("key", false) != "value" || reference.find(null, false) != null) return "reference"
    val nullableKey = Store<Int?, Int>(null, 47)
    if (nullableKey.find(null, false) != 47 || nullableKey.find(7, false) != null) return "nullable key"
    val nullableResult = Store<Int, Int?>(7, null)
    if (nullableResult.find(7, false) != null || nullableResult.find(7, true) != null) return "nullable result"
    val nominal = Store(Id(53), Id(59))
    if (nominal.find(Id(53), false)?.raw != 59 || nominal.find(null, false) != null) return "nominal"
    return "OK"
}
