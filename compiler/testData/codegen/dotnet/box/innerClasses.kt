interface InnerValue {
    fun value(): Int
}

class InnerOuter(private val seed: Int) {
    private val offset: Int = 3

    inner open class Base(private val delta: Int) : InnerValue {
        private val initialized: Int = seed + delta

        override fun value(): Int = initialized

        fun owns(outer: InnerOuter): Boolean = this@InnerOuter === outer
    }

    inner class Derived(delta: Int) : Base(delta) {
        override fun value(): Int = super.value() + offset
    }

    inner class Generic<T>(private val item: T) {
        fun item(): T = item

        fun outerSeed(): Int = seed
    }

    inner class First(private val first: Int) {
        inner class Second(private val second: Int) {
            fun total(): Int = seed + first + second
        }
    }

    inner class UsesLater(private val later: Later) {
        fun value(): Int = seed + later.value
    }

    inner class Later(val value: Int)

    inner class ConstructorPaths {
        private val value: Int

        constructor(value: Int) {
            this.value = seed + value
        }

        constructor() : this(offset)

        fun value(): Int = value
    }

    private inner class Hidden {
        fun value(): Int = seed + offset
    }

    fun hiddenValue(): Int = Hidden().value()
}

fun box(): String {
    val outer = InnerOuter(10)

    val base: InnerValue = outer.Base(2)
    if (base.value() != 12) return "fail 1: outer capture in initializer"

    val derivedAsBase: InnerOuter.Base = outer.Derived(4)
    if (derivedAsBase.value() != 17) return "fail 2: inner inheritance and dispatch"
    if (!derivedAsBase.owns(outer)) return "fail 3: explicit outer this"

    val generic = outer.Generic("generic")
    if (generic.item() != "generic" || generic.outerSeed() != 10) {
        return "fail 4: generic inner class"
    }

    if (outer.First(5).Second(7).total() != 22) return "fail 5: multi-level outer chain"
    if (outer.UsesLater(outer.Later(9)).value() != 19) return "fail 6: forward inner reference"
    if (outer.hiddenValue() != 13) return "fail 7: private inner class"
    if (outer.ConstructorPaths(5).value() != 15) return "fail 8: secondary constructor"
    if (outer.ConstructorPaths().value() != 13) return "fail 9: delegated secondary constructor"

    val other = InnerOuter(20)
    if (other.Base(2).value() != 22) return "fail 10: per-instance outer capture"
    return "OK"
}
