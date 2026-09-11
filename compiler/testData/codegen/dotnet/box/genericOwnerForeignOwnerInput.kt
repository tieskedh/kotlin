// DOTNET_GENERIC_OWNER_FOREIGN_OWNER_INPUT_PROBE
// MODULE: lib
// FILE: inputs.kt
package generic.owner.foreign.input

interface Producer<out T> {
    fun produce(): T
}

interface Lookup<K, out V> {
    fun find(key: K): V?
}

class IntProducer(private val value: Int) : Producer<Int> {
    override fun produce(): Int = value
}

fun nested(lookup: Lookup<Producer<Any?>, Int>, key: Producer<Any?>): Int? = lookup.find(key)
fun exact(lookup: Lookup<Int, Int>, key: Int): Int? = lookup.find(key)
fun same(key: Producer<Any?>, other: Any): Boolean = key === other

// MODULE: main(lib)
// FILE: main.kt
package generic.owner.foreign.input

fun box(): String {
    val key = IntProducer(53)
    val wide: Producer<Any?> = key
    val lookup = object : Lookup<Producer<Any?>, Int> {
        override fun find(key: Producer<Any?>): Int? = key.produce() as Int
    }
    if (!same(wide, key) || nested(lookup, wide) != 53) return "nested Kotlin input"
    return "OK"
}
