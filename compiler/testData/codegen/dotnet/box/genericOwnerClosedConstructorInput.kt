// DOTNET_GENERIC_OWNER_CLOSED_CONSTRUCTOR_PROBE
// MODULE: lib
// FILE: owners.kt

package generic.owner.closed.constructor

interface Source<out E> { fun read(): E }
interface Sink<in E> { fun write(value: E) }

open class ClosedInput<T>(private var value: T, sink: Sink<String>) {
    constructor(sink: Sink<String>, initial: T) : this(initial, sink)
    init { sink.write("from source") }
    fun read(): T = value
    fun write(next: T) { value = next }
}

open class RefBase
class RefLeaf : RefBase()

class ReferenceInput<T>(private val value: T, sink: Sink<RefLeaf>, leaf: RefLeaf) {
    init { sink.write(leaf) }
    fun read(): T = value
}

class NullableInput<T>(private val value: T, sink: Sink<String?>) {
    init { sink.write(null) }
    fun read(): T = value
}

// Covariant closed references still admit bottom views the CLR cannot name.
class ClosedOutputInput<T>(source: Source<String>)
class NullableOutputInput<T>(source: Source<String?>)
// None of these boundaries may be made natural from the class's unrelated T.
class BroadInput<T>(source: Source<Any?>) {
    init { if (source.read() != 7) throw IllegalStateException("value widening") }
}
class ValueInput<T>(sink: Sink<Int>)
class NullableValueInput<T>(sink: Sink<Int?>)
class OpenInput<T>(sink: Sink<T>)
class StarInput<T>(sink: Sink<*>)
interface Cell<E> { fun read(): E; fun write(value: E) }
class ProjectedInput<T>(cell: Cell<out String>)
interface LogicalIn<in E> { fun read(): @UnsafeVariance E }
class InvariantPhysicalInput<T>(source: LogicalIn<String>)
class NestedOutputInput<T>(sink: Sink<Source<String>>)

// MODULE: middle(lib)
// FILE: inherited.kt

package generic.owner.closed.constructor

open class InheritedInput<T>(value: T, sink: Sink<String>) : ClosedInput<T>(value, sink)
// Uses producer-recorded interface variance rather than an early local plan.
class ExternalInput<T>(private val value: T, sink: Sink<String>) {
    init { sink.write("external") }
    fun read(): T = value
}

// MODULE: main(lib, middle)
// FILE: main.kt

package generic.owner.closed.constructor

fun box(): String {
    var observed: Any? = ""
    val sink = object : Sink<Any?> { override fun write(value: Any?) { observed = value } }
    val value = ClosedInput(41, sink)
    value.write(42)
    if (value.read() != 42 || observed != "from source") return "closed constructor"
    if (ClosedInput(sink, "text").read() != "text") return "this delegation"
    if (InheritedInput(43, sink).read() != 43) return "base delegation"
    if (ExternalInput(44, sink).read() != 44 || observed != "external") return "external interface"
    val leaf = RefLeaf()
    var captured: RefBase? = null
    val refSink = object : Sink<RefBase> { override fun write(value: RefBase) { captured = value } }
    if (ReferenceInput(45, refSink, leaf).read() != 45 || captured !== leaf) return "reference identity"
    if (NullableInput(46, sink).read() != 46 || observed != null) return "nullable reference"
    val number = object : Source<Int> { override fun read(): Int = 7 }
    BroadInput<String>(number)
    // The other broad constructors above are exclusion/metadata tests, not
    // admitted separate-compilation routes. BroadInput has an authenticated L seal.
    return "OK"
}
