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

fun isGenericInnerOwn(value: Any?): Boolean = value is GenericInnerOuter<*>.Own<*>

fun box(): String {
    val outer = GenericInnerOuter("outer")
    val other = GenericInnerOuter("other")
    val plain = outer.plain()
    if (plain.outer() != "outer" || !plain.owns(outer) || plain.owns(other)) {
        return "fail 1: copied outer slot and identity"
    }

    val own = outer.own(7)
    if (own.item() != 7 || own.outer() != "outer") return "fail 2: own plus outer ordering"
    val ownAny: Any = own
    if (!isGenericInnerOwn(ownAny) || ownAny !is GenericInnerOuter<*>.Own<*>) {
        return "fail 2a: projected inner erased identity"
    }
    val projectedOwn = ownAny as GenericInnerOuter<*>.Own<*>
    if (projectedOwn !== own || projectedOwn.item() != 7 || projectedOwn.outer() != "outer") {
        return "fail 2b: projected inner cast identity and members"
    }

    val made = makeGenericInnerOwn(outer, 9)
    if (made.item() != 9 || made.outer() != "outer") return "fail 3: generic factory call site"

    val base: GenericInnerOuter<String>.Base<Int> = outer.Derived(11)
    if (base.item() != 11 || base.outer() != "outer") return "fail 4: inner generic inheritance"

    val pair: GenericInnerPairBase<Int, String> = outer.Pair(13)
    if (pair.first() != 13 || pair.second() != "outer") return "fail 5: generic base substitution"

    val second = outer.First("middle").Second(17)
    if (second.root() != "outer" || second.middle() != "middle" || second.own() != 17) {
        return "fail 6: multi-level copied slots"
    }

    if (outer.ConstructorPaths().value() != "outer") return "fail 7: delegated secondary constructor"

    val duplicate = DuplicateGenericInnerOuter<String>().Duplicate(19)
    if (duplicate.item() != 19) return "fail 8: duplicate metadata parameter names"
    return "OK"
}
