// DOTNET_GENERIC_OWNER_CANONICAL_STATE_CSHARP_PROBE
// MODULE: lib
// FILE: owners.kt

package generic.owner.canonical.state

interface Input<out T> {
    fun read(): T
}

// This declaration still has a semantic constructor boundary. Its physical
// classifier, not its logical type arguments, owns the stored reference.
class LibraryData<K, V>(private val input: Input<V>) {
    fun label(): String = "data"
    fun peek(): V = input.read()
}

open class Parent<T> {
    open fun describe(): String = "parent"
}

open class Observer<T>(private var source: LibraryData<T, *>, private var value: T) : Parent<T>() {
    override fun describe(): String = source.label()
    fun read(): T = value
    fun write(next: T) { value = next }
    fun replaceSource(next: LibraryData<T, *>) { source = next }
    fun sourceIdentity(): Any = source
}

fun describeInt(parent: Parent<Int>): String = parent.describe()
fun describeString(parent: Parent<String>): String = parent.describe()

// MODULE: middle(lib)
// FILE: inherited.kt

package generic.owner.canonical.state

open class InheritedObserver<T>(source: LibraryData<T, *>, initial: T) : Observer<T>(source, initial)

fun inheritedRead(value: Observer<Int>): Int = value.read()

// MODULE: main(lib, middle)
// FILE: main.kt

package generic.owner.canonical.state

fun box(): String {
    val input = object : Input<String> {
        override fun read(): String = "input"
    }
    val data = LibraryData<Int, String>(input)
    val ints = Observer(data, 42)
    if (ints.read() != 42 || describeInt(ints) != "data") return "value owner"
    ints.write(43)
    if (ints.read() != 43 || ints.sourceIdentity() !== data) return "value identity"
    val numberInput = object : Input<Int> {
        override fun read(): Int = 17
    }
    val replacement = LibraryData<Int, Int>(numberInput)
    ints.replaceSource(replacement)
    if (ints.sourceIdentity() !== replacement || ints.read() != 43) return "canonical replacement"

    val textData = LibraryData<String, String>(input)
    val strings = Observer(textData, "before")
    strings.write("after")
    if (strings.read() != "after" || describeString(strings) != "data") return "reference owner"
    if (strings.sourceIdentity() !== textData || textData.peek() != "input") return "reference identity"
    val inherited = InheritedObserver(data, 81)
    if (inheritedRead(inherited) != 81 || inherited.sourceIdentity() !== data) return "separate inheritance"
    return "OK"
}
