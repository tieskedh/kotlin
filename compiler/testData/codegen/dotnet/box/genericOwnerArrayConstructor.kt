// DOTNET_GENERIC_OWNER_ARRAY_CONSTRUCTOR_PROBE
// MODULE: lib
// FILE: source.kt
package generic.owner.array.constructor

interface Read<out T> { fun read(): T }
interface Build<out T> { fun make(): Read<T> }

class Cursor<T>(private val values: Array<T>) : Read<T> {
    override fun read(): T = values[0]
}

class Source<T>(private val values: Array<T>) : Build<T> {
    override fun make(): Read<T> = Cursor(values)

    fun makeWith(candidate: Any?): Read<T> {
        if (candidate === values) return Cursor(values)
        val exact = values
        return Cursor(exact)
    }
}

value class Id(val raw: Int)
fun nominalSource(): Source<Id> = Source(arrayOf(Id(19)))
fun readNominal(source: Source<Id>): Int = source.make().read().raw
fun readIntSource(source: Source<Int>): Any? {
    val widened: Build<Any?> = source
    return widened.make().read()
}

// MODULE: main(lib)
// FILE: main.kt
package generic.owner.array.constructor

private class Item(val value: Int)

fun box(): String {
    val values = arrayOf(3, 5)
    val source = Source(values)
    val direct = source.make()
    val widened: Build<Any?> = source
    val broad = widened.make()
    if (direct.read() != 3 || broad.read() != 3) return "initial"
    values[0] = 7
    if (direct.read() != 7 || broad.read() != 7) return "shared vector"
    if (widened !== source) return "receiver identity"
    val star: Build<*> = source
    if (star.make().read() != 7) return "star receiver"
    val item = Item(11)
    if (Source(arrayOf(item)).make().read() !== item) return "reference identity"
    val nullable: Build<Any?> = Source(arrayOf<Int?>(null))
    if (nullable.make().read() != null) return "nullable element"
    if (source.makeWith(values).read() != 7 || source.makeWith(arrayOf("unrelated")).read() != 7) return "independent input"
    val nominal = nominalSource()
    val widenedNominal: Build<Any?> = nominal
    if (readNominal(nominal) != 19 || (widenedNominal.make().read() as Id).raw != 19) return "nominal element"
    val nested = arrayOf(arrayOf(23))
    val nestedSource: Build<Any?> = Source(nested)
    if (nestedSource.make().read() !== nested[0]) return "nested vector"
    val projected = arrayOf<Array<out Any?>>(arrayOf(29), arrayOf("projected"))
    val projectedSource = Source(projected)
    if (projectedSource.make().read() !== projected[0]) return "projected element identity"
    var selected: Build<*> = source
    if (selected.make().read() != 7) return "first construction"
    selected = Source(arrayOf("different"))
    if (selected.make().read() != "different") return "mutable construction"
    val joined: Build<*> = if (values[0] == 7) Source(arrayOf("joined")) else source
    if (joined.make().read() != "joined") return "joined constructions"
    return "OK"
}
