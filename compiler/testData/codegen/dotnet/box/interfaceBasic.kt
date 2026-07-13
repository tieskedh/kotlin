// Real CLR interface types end-to-end: polymorphic dispatch through interface-typed parameters
// and locals (callvirt naming the interface), abstract property access through the interface,
// multiple interfaces on one class combined with a base class, a final override implementing an
// interface member, a derived override of an open implementation picked up by interface
// dispatch (ifaceprobe_s4), an inherited VIRTUAL base member satisfying a derived class's
// interface (ifaceprobe_s5a), interface-typed fields/returns, and reference identity across
// interface-typed and class-typed views of one object. (Identity between SIBLING views lives in
// box/interfaceHierarchy.kt: two FINAL sibling classes are frontend-rejected operands —
// EQUALITY_NOT_APPLICABLE, empty intersection — so the sibling widening arm is only reachable
// through sibling INTERFACE types.)
interface Shape {
    fun area(): Int
    val name: String
}

interface Movable {
    fun move(dx: Int): Int
}

class Rect(val w: Int, val h: Int) : Shape {
    override fun area(): Int = w * h
    override val name: String get() = "rect"
}

open class Circle(val r: Int) : Shape, Movable {
    var x: Int = 0
    override fun area(): Int = 3 * r * r
    final override val name: String get() = "circle"
    override fun move(dx: Int): Int {
        x = x + dx
        return x
    }
}

class BigCircle(r: Int) : Circle(r) {
    override fun area(): Int = 4 * r * r
}

open class Grower {
    open fun move(dx: Int): Int = dx * 10
}

class GrowingThing : Grower(), Movable

class Holder(val shape: Shape)

fun describe(s: Shape): String = s.name + ":" + s.area()

fun moveIt(m: Movable): Int = m.move(5)

fun maybeShape(flag: Boolean): Shape? = if (flag) Rect(1, 1) else null

fun box(): String {
    val r = Rect(3, 4)
    val c = Circle(2)
    if (describe(r) != "rect:12") return "fail: dispatch through interface parameter (rect)"
    if (describe(c) != "circle:12") return "fail: dispatch through interface parameter (circle)"
    val s: Shape = r
    if (s.area() != 12) return "fail: dispatch through interface-typed local"
    if (s.name != "rect") return "fail: property access through interface-typed local"
    if (moveIt(c) != 5) return "fail: second interface on one class"
    if (moveIt(c) != 10) return "fail: state mutation through interface dispatch"
    val big: Circle = BigCircle(1)
    val bigAsShape: Shape = big
    if (bigAsShape.area() != 4) return "fail: derived override via interface dispatch"
    if (bigAsShape.name != "circle") return "fail: final override via interface dispatch"
    if (moveIt(GrowingThing()) != 50) return "fail: inherited virtual member satisfying a derived interface"
    if (Holder(c).shape.area() != 12) return "fail: interface-typed field/constructor/return chain"
    if (s === c) return "fail: distinct instances identical across interface views"
    if (maybeShape(false) != null) return "fail: null interface-typed value"
    if (maybeShape(true) == null) return "fail: non-null interface-typed value"
    // Deliberately last: a POSITIVE identity check smartcasts `s` down to Rect afterwards, and
    // the resulting IMPLICIT_CAST downcast is (loudly) outside the supported type-operator set.
    if (!(s === r)) return "fail: identity between interface-typed and class-typed views"
    return "OK"
}
