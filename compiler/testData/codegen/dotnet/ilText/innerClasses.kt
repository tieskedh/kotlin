// Kotlin inner classes use the common/JVM explicit-outer representation on CLR: a private
// `this$0` field, a leading outer-instance constructor argument, and field-chain rewrites for
// outer `this` reads. The immediate outer must be non-generic; an inner class may still own its
// own independent generic parameters.

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

fun main() {
    val outer = InnerOuter(10)
    val base: InnerValue = outer.Base(2)
    val derivedAsBase: InnerOuter.Base = outer.Derived(4)
    val generic = outer.Generic("generic")

    println(base.value())
    println(derivedAsBase.value())
    println(derivedAsBase.owns(outer))
    println(generic.item())
    println(generic.outerSeed())
    println(outer.First(5).Second(7).total())
    println(outer.UsesLater(outer.Later(9)).value())
    println(outer.hiddenValue())
    println(outer.ConstructorPaths(5).value())
    println(outer.ConstructorPaths().value())
    println(InnerOuter(20).Base(2).value())
}
