// Real CLR inheritance end-to-end: polymorphic dispatch through base-typed values (callvirt),
// a super call running the base implementation, a final override still dispatching virtually,
// constructor chaining with arguments, inherited state and methods on a derived receiver, and
// reference upcasts across the whole three-level chain in local/parameter positions.
open class Base(val tag: Int) {
    var state: Int = tag * 10

    open fun describe(): String = "Base:" + tag
    open val label: String get() = "L:Base"

    fun bump(): Int {
        state = state + 1
        return state
    }
}

open class Mid(tag: Int) : Base(tag) {
    override fun describe(): String = "Mid[" + super.describe() + "]"
}

class Leaf(tag: Int, val extra: Int) : Mid(tag) {
    override fun describe(): String = "Leaf[" + super.describe() + "]:" + extra
    final override val label: String get() = "L:Leaf"
}

fun render(b: Base): String = b.describe()

fun box(): String {
    val leaf = Leaf(3, 7)
    if (leaf.tag != 3) return "fail: inherited val through two levels"
    if (leaf.extra != 7) return "fail: own val"
    if (leaf.state != 30) return "fail: base field state after chained construction"
    if (leaf.bump() != 31) return "fail: inherited final method on derived receiver"
    if (leaf.state != 31) return "fail: state after bump"
    if (leaf.describe() != "Leaf[Mid[Base:3]]:7") return "fail: super chain through two overrides"
    if (leaf.label != "L:Leaf") return "fail: final override accessor on derived receiver"

    val asBase: Base = leaf
    if (asBase.describe() != "Leaf[Mid[Base:3]]:7") return "fail: virtual dispatch through base-typed local"
    if (asBase.label != "L:Leaf") return "fail: virtual accessor dispatch through base-typed local"
    if (asBase.bump() != 32) return "fail: final method through base-typed local"

    if (render(leaf) != "Leaf[Mid[Base:3]]:7") return "fail: two-level upcast in parameter position"
    if (render(Mid(4)) != "Mid[Base:4]") return "fail: mid dispatch"
    if (render(Base(9)) != "Base:9") return "fail: base instance dispatch"
    if (Base(2).label != "L:Base") return "fail: base accessor"

    // Reference identity across base/derived static types: the operands widen to the ancestor
    // and compare with a type-agnostic reference ceq.
    if (!(asBase === leaf)) return "fail: identity between base-typed and derived-typed views"
    if (leaf === Mid(5)) return "fail: distinct instances identical across the chain"
    return "OK"
}
