// Generics × inheritance on the real CoreCLR (stage-1 generics, probes genprobe_s5/_s8):
// a non-generic class extending an INSTANTIATED generic base — inherited state and members
// through the derived receiver, virtual dispatch through a base-typed view (the substituted
// override spelling lands in the base slot; the open `!0` spelling would silently run the BASE
// body, the probe-verified poison shape this backend never emits), super-call chains, inherited
// mutation through the base view, and reference identity across the derived/instantiated-base
// views — plus a generic class extending a plain base with dispatch through the base type, and
// the gate-allowed COMBINED flavor: a non-generic class extending an instantiated generic base
// AND implementing an interface (inherited generic-base state, interface-view dispatch,
// instantiated-base-view dispatch and mutation, identity across all three views — interface
// mapping mistakes assemble clean and fail only at JIT time, so this dispatch shape needs its
// own runtime pin).

open class Box<T>(private var value: T) {
    fun get(): T = value

    fun put(v: T) {
        value = v
    }

    open fun describe(): T = value

    open fun tag(): String = "box"
}

class IntBox(v: Int) : Box<Int>(v) {
    override fun describe(): Int = super.describe() + 1000

    override fun tag(): String = "intbox"
}

class StrBox(s: String) : Box<String>(s) {
    override fun describe(): String = "str:" + super.describe()
}

interface Labeled {
    fun label(): String
}

class LabeledBox(v: Int) : Box<Int>(v), Labeled {
    override fun label(): String = "labeled"

    override fun describe(): Int = super.describe() + 5000
}

open class PlainBase {
    open fun name(): String = "base"
}

class GDerived<T>(private val payload: T) : PlainBase() {
    override fun name(): String = "generic-derived"

    fun payload(): T = payload
}

fun box(): String {
    val ib = IntBox(42)
    if (ib.get() != 42) return "fail 1: inherited get through derived receiver"
    if (ib.describe() != 1042) return "fail 2: override + super chain"
    val asBase: Box<Int> = ib
    if (asBase.describe() != 1042) return "fail 3: dispatch through instantiated-base view"
    if (asBase.tag() != "intbox") return "fail 4: string override through base view"
    asBase.put(1)
    if (ib.get() != 1) return "fail 5: inherited mutation through base view"
    if (ib.describe() != 1001) return "fail 6: override sees mutated base state"
    if (!(ib === asBase)) return "fail 7: identity across derived/base views"
    val sb = StrBox("x")
    if (sb.describe() != "str:x") return "fail 8: Box<String> flavor"
    if (sb.tag() != "box") return "fail 9: inherited non-overridden member"
    val g = GDerived("payload")
    if (g.payload() != "payload") return "fail 10: generic derived state"
    val asPlain: PlainBase = g
    if (asPlain.name() != "generic-derived") return "fail 11: dispatch through plain base"
    val lb = LabeledBox(3)
    if (lb.get() != 3) return "fail 12: inherited generic-base state with interface"
    val asLabeled: Labeled = lb
    if (asLabeled.label() != "labeled") return "fail 13: interface-view dispatch on generic-base class"
    val lbAsBase: Box<Int> = lb
    if (lbAsBase.describe() != 5003) return "fail 14: instantiated-base-view dispatch with interface"
    lbAsBase.put(4)
    if (lb.get() != 4) return "fail 15: inherited mutation through base view with interface"
    if (lbAsBase.describe() != 5004) return "fail 16: override sees mutated state with interface"
    if (!(lb === asLabeled)) return "fail 17: identity across derived/interface views"
    if (!(lb === lbAsBase)) return "fail 18: identity across derived/instantiated-base views"
    return "OK"
}
