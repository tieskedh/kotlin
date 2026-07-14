// Generic-to-generic class inheritance retains full open base instantiations. Constructor calls,
// inherited member owners, virtual slots, generic methods, interface views, and open upcasts all
// compose through permuted and nested arguments without erasure.

interface View<T> {
    fun first(): T
}

open class Base<A, B>(private var left: A, private var right: B) {
    open fun first(): A = left

    open fun second(): B = right

    fun putLeft(value: A) {
        left = value
    }

    fun putRight(value: B) {
        right = value
    }

    open fun <M> choose(context: A, value: M): M = value

    open fun <M> source(value: M): String = "base"
}

open class Mid<X, Y>(x: X, y: Y) : Base<Y, X>(y, x), View<Y> {
    override fun second(): X = super.second()

    override fun <M> choose(context: Y, value: M): M = super.choose(context, value)

    override fun <M> source(value: M): String = "mid:" + super.source(value)
}

class Leaf<P, Q>(p: P, q: Q) : Mid<Q, P>(q, p) {
    override fun second(): Q = super.second()

    override fun <M> source(value: M): String = "leaf:" + super.source(value)
}

class Holder<T>(val value: T)

class Wrapped<T>(value: T) : Base<Holder<T>, T>(Holder(value), value)

class Fixed<T>(value: T) : Base<String, T>("fixed", value)

class ArrayWrapped<T>(values: Array<T>, value: T) : Base<Array<T>, T>(values, value)

class NullableFixed<T>(value: T) : Base<Int?, T>(null, value)

interface Marked {
    fun label(): String
}

class Token(private val text: String) : Marked {
    override fun label(): String = text
}

open class ConstrainedBase<T : Marked>(private val value: T) {
    fun current(): T = value

    fun label(): String = value.label()
}

class ConstrainedDerived<T : Marked>(value: T) : ConstrainedBase<T>(value)

fun <A, B> baseView(value: Leaf<A, B>): Base<A, B> = value

fun <A, B> interfaceView(value: Leaf<A, B>): View<A> = value

fun <A, B, M> choose(value: Leaf<A, B>, context: A, selected: M): M =
    value.choose(context, selected)

fun main() {
    val leaf = Leaf("left", 7)
    println(leaf.first())
    println(leaf.second())
    val base: Base<String, Int> = leaf
    println(base.source(true))
    println(base.choose("context", 11L))
    base.putLeft("changed")
    println(leaf.first())
    val view: View<String> = leaf
    println(view.first())
    println(baseView(leaf).second())
    println(interfaceView(leaf).first())
    println(choose(leaf, "open", "selected"))
    val swapped: Base<Int, String> = Leaf(13, "right")
    println(swapped.first())
    println(swapped.second())
    val wrapped = Wrapped("nested")
    println(wrapped.first().value)
    println(wrapped.second())
    val fixed = Fixed(17)
    println(fixed.first())
    println(fixed.second())
    val arrayWrapped = ArrayWrapped(arrayOf("array"), "array")
    println(arrayWrapped.first()[0])
    println(arrayWrapped.second())
    val nullableFixed = NullableFixed("nullable")
    println(nullableFixed.first())
    println(nullableFixed.second())
    val constrained = ConstrainedDerived(Token("constrained"))
    println(constrained.current().label())
    println(constrained.label())
}
