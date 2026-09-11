// DOTNET_GENERIC_OWNER_FOREIGN_SPLIT_RESULT_PROBE
// MODULE: lib
// FILE: lookup.kt
package generic.owner.foreign.split

interface NullableSource<out V> {
    fun find(missing: Boolean): V?
}

value class Id(val raw: Int)
fun id(): Any = Id(53)
fun readId(value: Any): Int = (value as Id).raw

open class Store<K, out V>(private val anchor: K, initial: V) : NullableSource<V> {
    private var value: V = initial

    override fun find(missing: Boolean): V? = if (missing) null else value

    fun anchor(): K = anchor

    fun replace(next: @UnsafeVariance V) { value = next }
}

fun exact(store: Store<Int, Int>, missing: Boolean): Int? = store.find(missing)
fun wide(store: Store<Int, Any?>, missing: Boolean): Any? = store.find(missing)
fun throughInterface(store: NullableSource<Any?>, missing: Boolean): Any? = store.find(missing)
fun same(store: Store<Int, Any?>, other: Any): Boolean = store === other
fun readAny(store: Store<*, *>, missing: Boolean): Any? = store.find(missing)

// MODULE: middle(lib)
// FILE: middle.kt
package generic.owner.foreign.split

open class Middle<K, V>(anchor: K, initial: V) : Store<K, V>(anchor, initial)

open class KotlinOverride(anchor: Int, initial: Int) : Store<Int, Int>(anchor, initial) {
    override fun find(missing: Boolean): Int? = if (missing) 97 else super.find(false)
    fun parent(missing: Boolean): Int? = super.find(missing)
}

// MODULE: main(lib, middle)
// FILE: main.kt
package generic.owner.foreign.split

fun box(): String {
    val base = Store(1, 41)
    if (exact(base, false) != 41 || exact(base, true) != null || base.anchor() != 1) return "exact base"
    val widened: Store<Int, Any?> = base
    if (!same(widened, base) || throughInterface(widened, false) != 41) return "identity"
    widened.replace("widened")
    if (wide(widened, false) != "widened" || throughInterface(widened, false) != "widened") return "semantic state"
    val child = KotlinOverride(1, 43)
    if (wide(child, true) != 97 || wide(child, false) != 43) return "override or super"
    val inherited = Middle("key", "value")
    if (inherited.find(false) != "value" || inherited.find(true) != null || inherited.anchor() != "key") return "reference"
    val nullableKey = Store<Int?, Int>(null, 47)
    if (nullableKey.find(false) != 47 || nullableKey.anchor() != null) return "nullable key"
    val nullableResult = Store<Int, Int?>(1, null)
    if (nullableResult.find(false) != null || nullableResult.find(true) != null) return "nullable payload"
    val nominal = Store(Id(1), Id(53))
    if (nominal.find(false)?.raw != 53 || nominal.find(true) != null || nominal.anchor().raw != 1) return "nominal key or payload"
    return "OK"
}
