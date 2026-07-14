// Generics × inheritance, stage 1 (probe series genprobe_s5/_s7/_s8):
// - a NON-generic class extending an INSTANTIATED generic base: `extends class 'Box`1'<int32>`,
//   base-ctor chain `call instance void class 'Box`1'<int32>::.ctor(!0)`, inherited members and
//   the super-call named through the instantiated DECLARING base, and the override spelled with
//   the SUBSTITUTED type (`int32` where the base slot says `!0`) — the spelling that reuses the
//   base virtual slot (genprobe_s5; the open `!0` spelling in a non-generic derived class is
//   the probe-verified silent-mis-dispatch poison shape this backend never emits, and parameter
//   substitution matches the slot too, genprobe_s8);
// - a GENERIC class extending a plain non-generic base: plain `extends`, plain base-ctor chain,
//   ordinary slot-reusing override (genprobe_s5);
// - the instantiated-base flavor COMPOSES with the interface model: a non-generic class may
//   extend an instantiated generic base AND implement interfaces — `extends class 'Box`1'<int32>`
//   followed by the ordinary `implements` line, the interface implementation in the fresh-slot
//   spelling (`newslot virtual` — Kotlin modality: an override not marked `final` stays open),
//   interface-typed dispatch via `callvirt` on the interface token (`LabeledBox`; runtime-pinned
//   by box/genericInheritance.kt).
// Generic-extends-generic stays rejected (see genericRejected.kt).

open class Box<T>(private var value: T) {
    fun get(): T = value

    open fun describe(): T = value
}

class IntBox(v: Int) : Box<Int>(v) {
    override fun describe(): Int = super.describe() + 1000
}

interface Labeled {
    fun label(): String
}

class LabeledBox(v: Int) : Box<Int>(v), Labeled {
    override fun label(): String = "labeled"
}

open class PlainBase {
    open fun name(): String = "base"
}

class GDerived<T>(val payload: T) : PlainBase() {
    override fun name(): String = "generic-derived"
}

fun main() {
    val ib = IntBox(42)
    println(ib.get())
    println(ib.describe())
    val asBase: Box<Int> = ib
    println(asBase.describe())
    val lb = LabeledBox(5)
    val asLabeled: Labeled = lb
    println(asLabeled.label())
    println(lb.get())
    val g = GDerived<String>("p")
    println(g.name())
    val asPlain: PlainBase = g
    println(asPlain.name())
    println(g.payload)
}
