// Interface-extends-interface end-to-end (ifaceprobe_s6/_s9): an inherited interface member
// called through a sub-interface-typed receiver (the callvirt operand must name the DECLARING
// interface), interface->super-interface reference widening, a diamond (two sub-interfaces of
// one root implemented by one class, whose single member fills every same-signature interface
// slot), and identity across the interface views — including between SIBLING interface types
// (Left vs Right share only Root, so the equality operands widen to the first common supertype
// of the left operand's supertype walk; both the same-object true case and the
// distinct-objects false case).
interface Root {
    fun id(): String
}

interface Left : Root {
    fun l(): String
}

interface Right : Root {
    fun r(): String
}

class Node : Left, Right {
    override fun id(): String = "node"
    override fun l(): String = "L"
    override fun r(): String = "R"
}

class Simple : Root {
    override fun id(): String = "simple"
}

fun viaRoot(x: Root): String = x.id()

fun viaLeft(x: Left): String = x.id() + x.l()

fun viaRight(x: Right): String = x.id() + x.r()

fun box(): String {
    val n = Node()
    if (viaRoot(n) != "node") return "fail: transitive upcast to the root interface"
    if (viaLeft(n) != "nodeL") return "fail: inherited member through a sub-interface receiver (left)"
    if (viaRight(n) != "nodeR") return "fail: inherited member through a sub-interface receiver (right)"
    val asLeft: Left = n
    val asRoot: Root = asLeft
    if (asRoot.id() != "node") return "fail: interface-to-super-interface widening"
    if (viaRoot(Simple()) != "simple") return "fail: direct root implementer"
    if (!(asRoot === n)) return "fail: identity across interface views"
    val asRight: Right = n
    if (!(asLeft === asRight)) return "fail: one object across sibling interface views"
    val otherRight: Right = Node()
    if (asLeft === otherRight) return "fail: distinct objects across sibling interface views"
    return "OK"
}
