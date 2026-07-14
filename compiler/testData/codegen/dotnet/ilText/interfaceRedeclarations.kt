// Abstract interface redeclarations introduce real additional CLR interface slots. One exact-
// signature class member fills the original and every redeclared slot, including properties,
// generic methods, composed generic owners, repeated redeclarations, and diamonds.

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

fun main() {
    val impl = ViewImpl("item", 1)
    val base: BaseView<String> = impl
    val redeclared: RedeclaredView<String> = impl
    val leaf: LeafView<String> = impl
    println(base.value())
    println(redeclared.value())
    println(leaf.value())
    println(base.label)
    println(redeclared.label)
    println(leaf.label)
    println(base.echo("base-echo"))
    println(redeclared.echo(7))
    println(leaf.echo("leaf-echo"))
    base.count = 2
    println(redeclared.count)
    redeclared.count = 3
    println(leaf.count)

    val pair = PairImpl()
    val pairBase: PairView<String, Int> = pair
    val swapped: Swapped<Int, String> = pair
    println(pairBase.first())
    println(pairBase.second())
    println(swapped.first())
    println(swapped.second())

    val node = Node()
    val root: Root = node
    val left: Left = node
    val right: Right = node
    val diamond: Diamond = node
    println(root.id())
    println(left.id())
    println(right.id())
    println(diamond.id())
    val action: RedeclaredAction = ActionDerived()
    println(action.act())
}
