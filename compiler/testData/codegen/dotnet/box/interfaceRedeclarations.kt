interface BaseView<T> {
    fun value(): T

    val label: String

    var count: Int

    fun <M> echo(value: M): M
}

interface RedeclaredView<T> : BaseView<T> {
    override fun value(): T

    override val label: String

    override var count: Int

    override fun <M> echo(value: M): M
}

interface LeafView<T> : RedeclaredView<T> {
    override fun value(): T
}

class ViewImpl(private val item: String, initialCount: Int) : LeafView<String> {
    override fun value(): String = item

    override val label: String get() = "label:" + item

    override var count: Int = initialCount

    override fun <M> echo(value: M): M = value
}

interface PairView<A, B> {
    fun first(): A

    fun second(): B
}

interface Swapped<X, Y> : PairView<Y, X> {
    override fun first(): Y

    override fun second(): X
}

class PairImpl : Swapped<Int, String> {
    override fun first(): String = "first"

    override fun second(): Int = 2
}

interface Root {
    fun id(): String
}

interface Left : Root {
    override fun id(): String
}

interface Right : Root {
    override fun id(): String
}

interface Diamond : Left, Right {
    override fun id(): String
}

class Node : Diamond {
    override fun id(): String = "node"
}

interface Action {
    fun act(): String
}

interface RedeclaredAction : Action {
    override fun act(): String
}

open class ActionBase : Action {
    override fun act(): String = "inherited"
}

class ActionDerived : ActionBase(), RedeclaredAction

fun box(): String {
    val impl = ViewImpl("item", 1)
    val base: BaseView<String> = impl
    val redeclared: RedeclaredView<String> = impl
    val leaf: LeafView<String> = impl
    if (base.value() != "item") return "fail 1: base function slot"
    if (redeclared.value() != "item") return "fail 2: redeclared function slot"
    if (leaf.value() != "item") return "fail 3: repeated function slot"
    if (base.label != "label:item") return "fail 4: base property slot"
    if (redeclared.label != "label:item") return "fail 5: redeclared property slot"
    if (leaf.label != "label:item") return "fail 6: inherited redeclared property"
    if (base.echo("base") != "base") return "fail 7: base generic method"
    if (redeclared.echo(7) != 7) return "fail 8: redeclared generic method"
    if (leaf.echo("leaf") != "leaf") return "fail 9: inherited generic redeclaration"
    base.count = 2
    if (redeclared.count != 2) return "fail 10: base setter to redeclared getter"
    redeclared.count = 3
    if (leaf.count != 3 || impl.count != 3) return "fail 11: redeclared mutable property"

    val pair = PairImpl()
    val pairBase: PairView<String, Int> = pair
    val swapped: Swapped<Int, String> = pair
    if (pairBase.first() != "first" || pairBase.second() != 2) return "fail 12: composed base slots"
    if (swapped.first() != "first" || swapped.second() != 2) return "fail 13: composed redeclared slots"

    val node = Node()
    val root: Root = node
    val left: Left = node
    val right: Right = node
    val diamond: Diamond = node
    if (root.id() != "node") return "fail 14: root diamond slot"
    if (left.id() != "node") return "fail 15: left redeclared slot"
    if (right.id() != "node") return "fail 16: right redeclared slot"
    if (diamond.id() != "node") return "fail 17: merged redeclared slot"
    val action: RedeclaredAction = ActionDerived()
    if (action.act() != "inherited") return "fail 17a: inherited implementation of redeclared slot"
    if (!(base === redeclared) || !(left === right)) return "fail 18: cross-slot identity"
    return "OK"
}
