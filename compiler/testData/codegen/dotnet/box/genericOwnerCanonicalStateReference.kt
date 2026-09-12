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

// A self-reference does not need a completed state plan to identify its own TypeDef binder.
class RecursiveState<T>(private val value: T, private val previous: RecursiveState<T>?) {
    fun read(): T = value
    fun previous(): RecursiveState<T>? = previous
}

value class StateId(val raw: Int)

class ReorderedRecursive<K, V>(private val previous: ReorderedRecursive<V, K>?) {
    fun previous(): ReorderedRecursive<V, K>? = previous
}

class BroadRecursive<T>(private val value: T, previous: BroadRecursive<T>?) {
    private var previous: BroadRecursive<T>? = previous

    @Suppress("UNCHECKED_CAST")
    fun install(candidate: Any?) { previous = candidate as BroadRecursive<T>? }
    fun same(candidate: Any?): Boolean = previous === candidate
    fun read(): T = value
}

// Covariance admits a logically matching argument which is not the same CLR construction.
// The conditional invariant-self proof must not accidentally admit this owner too.
class CovariantRecursive<out T>(private val value: T, private val previous: CovariantRecursive<T>?) {
    fun read(): T = value
    fun previous(): CovariantRecursive<T>? = previous
}

// MODULE: middle(lib)
// FILE: inherited.kt

package generic.owner.canonical.state

open class InheritedObserver<T>(source: LibraryData<T, *>, initial: T) : Observer<T>(source, initial)

fun inheritedRead(value: Observer<Int>): Int = value.read()

// MODULE: main(lib, middle)
// FILE: main.kt

package generic.owner.canonical.state

fun box(): String {
    val first = RecursiveState(31, null)
    val second = RecursiveState(37, first)
    if (second.previous() !== first || first.previous() != null || second.read() != 37) return "recursive state"
    val text = RecursiveState("text", null)
    if (text.read() != "text") return "recursive reference"
    val absent = RecursiveState<Int?>(null, null)
    val present = RecursiveState<Int?>(41, absent)
    if (present.read() != 41 || present.previous() !== absent || absent.read() != null) return "recursive nullable"
    if (RecursiveState(StateId(43), null).read().raw != 43) return "recursive value class"
    val reverse = ReorderedRecursive<String, Int>(null)
    val forward = ReorderedRecursive<Int, String>(reverse)
    if (forward.previous() !== reverse) return "reordered recursive binder"
    val broadFirst = BroadRecursive(47, null)
    val broadSecond = BroadRecursive(53, broadFirst)
    broadSecond.install(null)
    if (!broadSecond.same(null) || broadSecond.read() != 53) return "broad recursive null"
    broadSecond.install(broadFirst)
    if (!broadSecond.same(broadFirst)) return "broad recursive identity"
    val covariantInt = CovariantRecursive(59, null)
    val covariantWide = CovariantRecursive<Any?>("wide", covariantInt)
    if (covariantWide.previous() !== covariantInt || covariantWide.previous()!!.read() != 59) return "covariant self view"
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
