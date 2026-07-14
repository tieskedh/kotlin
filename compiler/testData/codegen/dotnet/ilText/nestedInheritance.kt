// Named nested classes use the same modality and inheritance model as top-level classes.
// Their CLR base tokens may name forward siblings, metadata parents, deeper family members,
// independently generic nested classes, or nested classes in another top-level family.

interface NestedValue {
    fun value(): Int
}

class ForwardFamily {
    class Derived(seed: Int) : Base(seed) {
        override fun value(): Int = super.value() + 1
    }

    open class Base(private val seed: Int) : NestedValue {
        override fun value(): Int = seed
    }
}

class AbstractFamily {
    abstract class Base : NestedValue

    class Concrete : Base() {
        override fun value(): Int = 7
    }

    sealed class Choice {
        abstract fun code(): Int
    }

    class Chosen : Choice() {
        override fun code(): Int = 8
    }
}

class GenericFamily<Unused> {
    open class Base<T>(private val item: T) {
        open fun item(): T = item
    }

    class Derived<T>(item: T) : Base<T>(item) {
        override fun item(): T = super.item()
    }
}

class DepthFamily {
    open class Parent(private val stored: Int) {
        open fun value(): Int = stored

        class Child(stored: Int) : Parent(stored)
    }

    class Middle {
        open class Base(private val stored: Int) {
            open fun value(): Int = stored
        }
    }

    class CrossDepth(stored: Int) : Middle.Base(stored)
}

class SharedFamily {
    open class Base(private val stored: Int) {
        open fun value(): Int = stored
    }
}

class OtherFamily {
    class Derived(stored: Int) : SharedFamily.Base(stored)
}

class TopLevelDerived(stored: Int) : SharedFamily.Base(stored)

fun readForward(value: ForwardFamily.Base): Int = value.value()

fun readNestedValue(value: NestedValue): Int = value.value()

fun <T> readGeneric(value: GenericFamily.Base<T>): T = value.item()

fun readParent(value: DepthFamily.Parent): Int = value.value()

fun readDeep(value: DepthFamily.Middle.Base): Int = value.value()

fun readShared(value: SharedFamily.Base): Int = value.value()

fun main() {
    println(readForward(ForwardFamily.Derived(4)))
    println(readNestedValue(AbstractFamily.Concrete()))
    println(AbstractFamily.Chosen().code())
    println(readGeneric(GenericFamily.Derived("generic")))
    println(readParent(DepthFamily.Parent.Child(9)))
    println(readDeep(DepthFamily.CrossDepth(10)))
    println(readShared(TopLevelDerived(11)))
    println(readShared(OtherFamily.Derived(12)))
}
