// CLR nested types do not inherit an enclosing type's generic slots. The DotNet lowering copies
// the immediate outer's complete parameter list after the inner's own parameters, remaps the
// inner subtree, and leaves FIR's existing `Inner<own, outer...>` use-site order unchanged.

open class GenericInnerPairBase<A, B>(private val first: A, private val second: B) {
    fun first(): A = first

    fun second(): B = second
}

class GenericInnerOuter<T>(private val seed: T) {
    inner class Plain {
        fun outer(): T = seed

        fun owns(outer: GenericInnerOuter<T>): Boolean = this@GenericInnerOuter === outer
    }

    inner class Own<U>(private val item: U) {
        fun item(): U = item

        fun outer(): T = seed
    }

    inner open class Base<U>(private val item: U) {
        fun item(): U = item

        open fun outer(): T = seed
    }

    inner class Derived<V>(item: V) : Base<V>(item) {
        override fun outer(): T = seed
    }

    inner class Pair<U>(item: U) : GenericInnerPairBase<U, T>(item, seed)

    inner class First<U>(private val middle: U) {
        inner class Second<V>(private val own: V) {
            fun root(): T = seed

            fun middle(): U = this@First.middle

            fun own(): V = own
        }
    }

    inner class ConstructorPaths {
        private val value: T

        constructor(value: T) {
            this.value = value
        }

        constructor() : this(seed)

        fun value(): T = value
    }

    fun plain(): Plain = Plain()

    fun <U> own(item: U): Own<U> = Own(item)
}

class DuplicateGenericInnerOuter<T> {
    inner class Duplicate<T>(private val item: T) {
        fun item(): T = item
    }
}

fun <T, U> makeGenericInnerOwn(outer: GenericInnerOuter<T>, item: U): GenericInnerOuter<T>.Own<U> =
    outer.Own(item)

fun main() {
    val outer = GenericInnerOuter("outer")
    val plain = outer.plain()
    val own = outer.own(7)
    val made = makeGenericInnerOwn(outer, 9)
    val base: GenericInnerOuter<String>.Base<Int> = outer.Derived(11)
    val pair: GenericInnerPairBase<Int, String> = outer.Pair(13)
    val second = outer.First("middle").Second(17)
    val constructorPath = outer.ConstructorPaths()
    val duplicate = DuplicateGenericInnerOuter<String>().Duplicate(19)

    println(plain.outer())
    println(plain.owns(outer))
    println(own.item())
    println(own.outer())
    println(made.item())
    println(base.item())
    println(base.outer())
    println(pair.first())
    println(pair.second())
    println(second.root())
    println(second.middle())
    println(second.own())
    println(constructorPath.value())
    println(duplicate.item())
}
