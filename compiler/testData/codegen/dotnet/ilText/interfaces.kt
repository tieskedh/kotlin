// Real CLR interface types (probe series ifaceprobe): a Kotlin interface is `.class interface
// public abstract auto ansi` with NO `extends` line; its abstract members are `newslot abstract
// virtual` methods with empty bodies (`specialname` for accessors, bound by ordinary .property
// blocks). An implementing class appends `implements` after its `extends` line; an implicit
// implementation of only interface members is `newslot virtual` — even in a final class whose
// member is not `final override` (the stated deviation from Roslyn, which seals it) — a Kotlin
// `final override` of only interface members is `newslot virtual final` (Both.tag, and
// `specialname newslot virtual final` for OpenNamed.flag's accessors: the Roslyn
// implicit-implementation shape), and a derived override of an open implementation reuses
// the CLASS slot (`virtual`, no newslot) — CLR interface mapping follows the class vtable.
// Call sites through interface-typed values are `callvirt` with the operand naming the
// DECLARING interface; class->interface upcasts are free reference widenings in parameter,
// return, local and field positions.
interface Named {
    fun describe(): String
    val id: Int
    var flag: Boolean
}

interface Tagged {
    fun tag(): String
}

open class Base(val prefix: String)

class Both(prefix: String, override val id: Int) : Base(prefix), Named, Tagged {
    override var flag: Boolean = false
    override fun describe(): String = prefix + id
    final override fun tag(): String = "tag"
}

open class OpenNamed : Named {
    override val id: Int get() = 1
    final override var flag: Boolean = true
    override fun describe(): String = "open"
}

class SubNamed : OpenNamed() {
    override fun describe(): String = "sub"
}

fun show(n: Named): String = n.describe()

fun raise(n: Named): Named {
    n.flag = true
    return n
}

fun main() {
    val b = Both("p", 5)
    println(show(b))
    println(b.tag())
    println(raise(b).flag)
    val n: Named = SubNamed()
    println(n.describe())
    println(n.id)
    println(show(OpenNamed()))
}
