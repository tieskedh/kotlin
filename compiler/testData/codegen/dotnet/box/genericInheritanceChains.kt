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

fun box(): String {
    val leaf = Leaf("left", 7)
    if (leaf.first() != "left") return "fail 1: inherited first"
    if (leaf.second() != 7) return "fail 2: permuted override"

    val base: Base<String, Int> = leaf
    if (base.second() != 7) return "fail 3: base-view virtual dispatch"
    if (base.source(true) != "leaf:mid:base") return "fail 4: generic virtual super chain"
    if (base.choose("context", 11L) != 11L) return "fail 5: generic method owner"
    base.putLeft("changed")
    base.putRight(8)
    if (leaf.first() != "changed" || leaf.second() != 8) return "fail 6: inherited mutation"

    val view: View<String> = leaf
    if (view.first() != "changed") return "fail 7: inherited interface implementation"
    if (!(leaf === base) || !(leaf === view)) return "fail 8: cross-view identity"

    if (baseView(leaf).second() != 8) return "fail 9: open base upcast"
    if (interfaceView(leaf).first() != "changed") return "fail 10: open interface upcast"
    if (choose(leaf, "open", "selected") != "selected") return "fail 11: open inherited call"

    val swapped = Leaf(13, "right")
    val swappedBase: Base<Int, String> = swapped
    if (swappedBase.first() != 13 || swappedBase.second() != "right") return "fail 12: swapped instantiation"

    val wrapped = Wrapped("nested")
    if (wrapped.first().value != "nested" || wrapped.second() != "nested") {
        return "fail 13: nested base argument"
    }
    val fixed = Fixed(17)
    if (fixed.first() != "fixed" || fixed.second() != 17) return "fail 14: fixed and open arguments"

    val arrayWrapped = ArrayWrapped(arrayOf("array"), "array")
    if (arrayWrapped.first()[0] != "array" || arrayWrapped.second() != "array") {
        return "fail 15: open generic-array base argument"
    }
    val nullableFixed = NullableFixed("nullable")
    if (nullableFixed.first() != null || nullableFixed.second() != "nullable") {
        return "fail 16: fixed nullable-value base argument"
    }

    val constrained = ConstrainedDerived(Token("constrained"))
    if (constrained.current().label() != "constrained") return "fail 17: constrained inherited return"
    if (constrained.label() != "constrained") return "fail 18: constrained inherited call"
    return "OK"
}
