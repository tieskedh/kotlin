// MODULE: lib
// FILE: base.kt
package covariant.bridge.input

open class Base<K, out V>(private val key: K, private val value: V) : Map<K, V> {
    override val size: Int get() = 1
    override fun isEmpty(): Boolean = false
    override fun containsKey(key: K): Boolean = this.key == key
    override fun containsValue(value: @UnsafeVariance V): Boolean = this.value == value
    override fun get(key: K): V? = if (containsKey(key)) value else null
    override val keys: Set<K> get() = throw UnsupportedOperationException()
    override val values: Collection<V> get() = throw UnsupportedOperationException()
    override val entries: Set<Map.Entry<K, V>> get() = throw UnsupportedOperationException()
}

fun candidate(map: Map<*, *>, key: Any?): Any? = map[key]
fun candidateKey(map: Map<*, *>, key: Any?): Boolean = map.containsKey(key)

open class IndexBase<T>(private val value: T) : AbstractList<T>() {
    override val size: Int get() = 1
    override fun get(index: Int): T = if (index == 0) value else throw IndexOutOfBoundsException()
    override fun indexOf(element: T): Int = if (element == value) 0 else -1
}

fun candidateIndex(list: List<*>, value: Any?): Int = list.indexOf(value)

open class Strict<T> { open fun accept(value: T): String = "base" }

// MODULE: middle(lib)
// FILE: derived.kt
package covariant.bridge.input

open class IntKeys : Base<Int, Int>(1, 41) {
    var calls: Int = 0
    var keyCalls: Int = 0
    override fun get(key: Int): Int? { calls++; return super.get(key) }
    override fun containsKey(key: Int): Boolean { keyCalls++; return super.containsKey(key) }
}

class IntLeaf : IntKeys() {
    override fun get(key: Int): Int? { calls++; return if (key == 1) 43 else null }
}

class NullableKeys : Base<Int?, String>(null, "nullable") {
    var calls: Int = 0
    override fun get(key: Int?): String? { calls++; return super.get(key) }
}

class StringKeys : Base<String, Int>("key", 47) {
    var calls: Int = 0
    override fun get(key: String): Int? { calls++; return super.get(key) }
}

class IntIndex : IndexBase<Int>(1) {
    var calls: Int = 0
    override fun indexOf(element: Int): Int { calls++; return super.indexOf(element) }
}

class StrictString : Strict<String>() {
    var calls: Int = 0
    override fun accept(value: String): String { calls++; return value }
}

// MODULE: main(lib, middle)
// FILE: main.kt
package covariant.bridge.input

fun box(): String {
    val map = IntKeys()
    if (candidate(map, "wrong") != null || candidate(map, null) != null || map.calls != 0) return "barrier"
    if (candidateKey(map, "wrong") || candidateKey(map, null) || map.keyCalls != 0) return "false barrier"
    if (candidate(map, 1) != 41 || map.calls != 1) return "match"
    if (candidate(map, 2) != null || map.calls != 2) return "miss"
    val leaf = IntLeaf()
    if (candidate(leaf, "wrong") != null || candidate(leaf, null) != null || leaf.calls != 0) return "leaf barrier"
    if (candidate(leaf, 1) != 43 || leaf.calls != 1) return "leaf virtual"
    val nullable = NullableKeys()
    if (candidate(nullable, "wrong") != null || nullable.calls != 0) return "nullable barrier"
    if (candidate(nullable, null) != "nullable" || nullable.calls != 1) return "nullable match"
    if (candidate(nullable, 1) != null || nullable.calls != 2) return "nullable miss"
    val strings = StringKeys()
    if (candidate(strings, 1) != null || candidate(strings, null) != null || strings.calls != 0) return "reference barrier"
    if (candidate(strings, "key") != 47 || strings.calls != 1) return "reference match"
    val indexed = IntIndex()
    if (candidateIndex(indexed, "wrong") != -1 || candidateIndex(indexed, null) != -1 || indexed.calls != 0) return "index barrier"
    if (candidateIndex(indexed, 1) != 0 || indexed.calls != 1) return "index match"
    val strict = StrictString()
    try {
        @Suppress("UNCHECKED_CAST")
        val unsafe = strict as Strict<Any?>
        unsafe.accept(1)
        return "missing strict cast"
    } catch (expected: ClassCastException) { }
    if (strict.calls != 0 || strict.accept("exact") != "exact" || strict.calls != 1) return "strict body"
    return "OK"
}
