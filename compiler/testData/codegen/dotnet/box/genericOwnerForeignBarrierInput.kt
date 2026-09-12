// DOTNET_GENERIC_OWNER_FOREIGN_BARRIER_INPUT_PROBE
// MODULE: lib
// FILE: store.kt
package generic.owner.foreign.barrier

interface TypedLookup<K, out V> { operator fun get(key: K): V? }

open class Store<K, out V>(private val key: K, initial: V) : Map<K, V>, TypedLookup<K, V> {
    private var value: V = initial

    override val size: Int get() = 1
    override fun isEmpty(): Boolean = false
    override fun containsKey(key: K): Boolean = this.key == key
    override fun containsValue(value: @UnsafeVariance V): Boolean = this.value == value
    override fun get(key: K): V? = if (containsKey(key)) value else null

    // These operations are not used by this dispatch fixture.
    override val keys: Set<K> get() = throw UnsupportedOperationException()
    override val values: Collection<V> get() = throw UnsupportedOperationException()
    override val entries: Set<Map.Entry<K, V>> get() = throw UnsupportedOperationException()

    fun replace(next: @UnsafeVariance V) { value = next }
    fun anchor(): K = key
}

fun exact(store: Store<Int, Int>, key: Int): Int? = store[key]
fun wide(store: Store<Int, Any?>, key: Int): Any? = store[key]
fun star(store: Map<*, *>, key: Any?): Any? = store[key]
fun same(store: Map<*, *>, other: Any): Boolean = store === other
fun throughTyped(store: TypedLookup<Int, Int>, key: Int): Int? = store[key]
fun throughWide(store: TypedLookup<Int, Any?>, key: Int): Any? = store[key]

// Only the existing Runtime slot: its physical object result must not become split.
open class ObjectResult<K, out V>(private val key: K, initial: V) : Map<K, V> {
    private var value: V = initial
    override val size: Int get() = 1
    override fun isEmpty(): Boolean = false
    override fun containsKey(key: K): Boolean = this.key == key
    override fun containsValue(value: @UnsafeVariance V): Boolean = this.value == value
    override fun get(key: K): V? = if (containsKey(key)) value else null
    override val keys: Set<K> get() = throw UnsupportedOperationException()
    override val values: Collection<V> get() = throw UnsupportedOperationException()
    override val entries: Set<Map.Entry<K, V>> get() = throw UnsupportedOperationException()
    fun replace(next: @UnsafeVariance V) { value = next }
}

value class Id(val raw: Int)
fun id(value: Int): Any = Id(value)

open class Strict<K, out V>(initial: V) {
    private var value: V = initial
    open fun find(key: K): V? = value
    fun replace(next: @UnsafeVariance V) { value = next }
}

// MODULE: middle(lib)
// FILE: middle.kt
package generic.owner.foreign.barrier

open class Middle<K, V>(key: K, initial: V) : Store<K, V>(key, initial)

open class KotlinFixed(initial: Int) : Store<Int, Int>(1, initial) {
    override fun get(key: Int): Int? = super.get(key)
    fun parent(key: Int): Int? = super.get(key)
}

open class KotlinGeneric<K, V>(key: K, initial: V) : Store<K, V>(key, initial) {
    override fun get(key: K): V? = super.get(key)
    fun parent(key: K): V? = super.get(key)
}

open class NonNullKey<K : Any, V>(key: K, initial: V) : Store<K, V>(key, initial) {
    override fun get(key: K): V? = super.get(key)
}

// MODULE: main(lib, middle)
// FILE: main.kt
package generic.owner.foreign.barrier

fun box(): String {
    val store = Store(1, 41)
    if (exact(store, 1) != 41 || exact(store, 2) != null || store.anchor() != 1 ||
        throughTyped(store, 1) != 41 || throughTyped(store, 2) != null) return "exact"
    val broad: Store<Int, Any?> = store
    if (!same(broad, store) || star(broad, 1) != 41) return "identity"
    if (star(broad, "wrong") != null || star(broad, null) != null) return "barrier"
    broad.replace("widened")
    if (wide(broad, 1) != "widened" || star(broad, 1) != "widened" ||
        throughWide(broad, 1) != "widened") return "semantic state"
    val fixed = KotlinFixed(43)
    if (star(fixed, 1) != 43 || star(fixed, "wrong") != null || fixed.parent(2) != null) return "fixed override"
    val generic = KotlinGeneric(1, 47)
    if (star(generic, 1) != 47 || star(generic, null) != null || generic.parent(1) != 47) return "generic override"
    val nonNull = NonNullKey("key", 53)
    if (star(nonNull, "key") != 53 || star(nonNull, null) != null) return "non-null bound"
    val objectResult = ObjectResult(1, 59)
    val objectWide: ObjectResult<Int, Any?> = objectResult
    objectWide.replace("object layout")
    if (star(objectWide, 1) != "object layout" || star(objectWide, "wrong") != null ||
        !same(objectWide, objectResult)) return "object layout"
    return "OK"
}
