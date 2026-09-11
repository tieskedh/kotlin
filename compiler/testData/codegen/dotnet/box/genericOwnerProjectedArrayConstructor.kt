// DOTNET_GENERIC_OWNER_PROJECTED_ARRAY_STATE_PROBE
// MODULE: lib
// FILE: view.kt
package generic.owner.projected.array

interface Read<out T> {
    fun read(): T
}
abstract class Base<T> : Read<T>

private class Window<T>(private var values: Array<out T>) : Base<T>() {
    override fun read(): T = values[0]
    fun sameArray(expected: Any?): Boolean = values === expected
    fun replace(next: Array<out T>) { values = next }
}

// An unguarded raw System.Array entry is not part of this private-owner admission.
class PublicWindow<T>(private val values: Array<out T>) {
    fun read(): T = values[0]
}

private fun <T> projected(values: Array<out T>): Base<T> = Window(values)
fun <T> exact(values: Array<T>): Base<T> = projected(values)
fun widenedInts(values: Array<Int>): Base<Any?> = projected<Any?>(values)
value class Id(val raw: Int)
fun nominal(): Base<Id> = exact(arrayOf(Id(19)))
fun nominalValue(value: Read<Id>): Int = value.read().raw

fun changing(): Boolean {
    val ints = arrayOf(13)
    val texts = arrayOf("replacement")
    val window = Window<Any?>(ints)
    val reader: Read<Any?> = window
    window.replace(texts)
    if (reader !== window || reader.read() != "replacement" || !window.sameArray(texts)) return false
    window.replace(ints)
    ints[0] = 17
    return reader.read() == 17 && window.sameArray(ints)
}

// MODULE: main(lib)
// FILE: main.kt
package generic.owner.projected.array

fun box(): String {
    val values = arrayOf(7)
    val reader = exact(values)
    if (reader.read() != 7) return "exact"
    val materialized = widenedInts(values)
    if (materialized.read() != 7) return "widened"
    val viaFactory = exact(values)
    values[0] = 11
    if (reader.read() != 11 || materialized.read() != 11 || viaFactory.read() != 11) return "mutation"
    val star: Read<*> = reader
    if (star !== reader || star.read() != 11) return "star"
    if (exact(arrayOf<Int?>(null)).read() != null) return "nullable"
    if (exact(arrayOf("text")).read() != "text") return "reference"
    if (!changing()) return "replacement"
    if (nominalValue(nominal()) != 19) return "nominal"
    return "OK"
}
