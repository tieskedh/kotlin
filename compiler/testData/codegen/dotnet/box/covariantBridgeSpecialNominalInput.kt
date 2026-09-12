// MODULE: lib
// FILE: base.kt
package covariant.bridge.nominal.input

value class Id(val raw: Int)

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

// MODULE: middle(lib)
// FILE: derived.kt
package covariant.bridge.nominal.input

class NominalKeys : Base<Id, String>(Id(1), "nominal") {
    var calls: Int = 0
    override fun get(key: Id): String? { calls++; return super.get(key) }
}

// MODULE: main(lib, middle)
// FILE: main.kt
package covariant.bridge.nominal.input

fun box(): String {
    val nominal = NominalKeys()
    if (candidate(nominal, 1) != null || candidate(nominal, null) != null || nominal.calls != 0) return "nominal barrier"
    if (candidate(nominal, Id(1)) != "nominal" || nominal.calls != 1) return "nominal match"
    if (candidate(nominal, Id(2)) != null || nominal.calls != 2) return "nominal miss"
    return "OK"
}
