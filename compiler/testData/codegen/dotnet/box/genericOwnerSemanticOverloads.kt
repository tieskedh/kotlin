// DOTNET_GENERIC_OWNER_SEMANTIC_OVERLOAD_PROBE
// MODULE: lib
// FILE: renderer.kt

package generic.owner.semantic.overloads

interface Source<out E> { fun read(): E }

// The semantic constructor keeps this owner canonical. Its methods must still
// preserve logical overload identity when Source<T> becomes an object carrier.
class Renderer<T>(private val source: Source<T>) {
    private fun render(value: Source<T> = source): String = "source"
    private fun render(value: Any?): String = "other"
    fun result(): String = render(source) + ":" + render(null)
    fun defaultResult(): String = render()
    fun publicEntry(value: Source<T>): String = render(value)
}

class Reordered<T>(private val source: Source<T>) {
    private fun render(value: Any?): String = "other"
    private fun render(value: String): String = "string"
    private fun render(value: Source<T>): String = "source"
    fun result(): String = render(source) + ":" + render(null) + ":" + render("text")
}

class Single<T>(private val source: Source<T>) {
    private fun render(value: Source<T>): String = "source"
    fun result(): String = render(source)
}

class StarRenderer(private val source: Source<*>) {
    private fun render(value: Source<*>): String = "star"
    private fun render(value: Any?): String = "other"
    fun result(): String = render(source) + ":" + render(null)
}

class ExactRenderer {
    private fun render(value: Source<String>): String = value.read()
    private fun render(value: Any?): String = "other"
    fun result(value: Source<String>): String = render(value) + ":" + render(null)
}

class IteratorRenderer<T>(private val source: Iterator<T>) {
    private fun render(value: Iterator<T>): String = if (value.hasNext()) "iterator" else "empty"
    private fun render(value: Any?): String = "other"
    fun result(): String = render(source) + ":" + render(null)
}

class EntryRenderer<K, V>(private val source: Map.Entry<K, V>) {
    private fun render(value: Map.Entry<K, V>): String = "entry"
    private fun render(value: Any?): String = "other"
    fun result(): String = render(source) + ":" + render(null)
}

// MODULE: main(lib)
// FILE: main.kt

package generic.owner.semantic.overloads

fun box(): String {
    val source = object : Source<Int> { override fun read(): Int = 7 }
    if (Renderer(source).result() != "source:other") return "overloads"
    if (Renderer(source).defaultResult() != "source") return "default argument"
    if (Renderer(source).publicEntry(source) != "source") return "public entry"
    if (Reordered(source).result() != "source:other:string") return "reordered"
    if (Single(source).result() != "source") return "single"
    if (StarRenderer(source).result() != "star:other") return "star"
    val text = object : Source<String> { override fun read(): String = "text" }
    if (ExactRenderer().result(text) != "text:other") return "exact"
    val iterator = object : Iterator<Int> {
        override fun hasNext(): Boolean = true
        override fun next(): Int = 7
    }
    if (IteratorRenderer(iterator).result() != "iterator:other") return "runtime iterator"
    val entry = object : Map.Entry<Int, String> {
        override val key: Int get() = 7
        override val value: String get() = "text"
    }
    if (EntryRenderer(entry).result() != "entry:other") return "runtime entry"
    return "OK"
}
