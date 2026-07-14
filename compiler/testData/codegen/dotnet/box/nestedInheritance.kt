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

fun box(): String {
    if (readForward(ForwardFamily.Derived(4)) != 5) return "fail 1: forward nested base"
    if (readNestedValue(AbstractFamily.Concrete()) != 7) return "fail 2: abstract nested base"
    if (AbstractFamily.Chosen().code() != 8) return "fail 3: sealed nested base"
    if (readGeneric(GenericFamily.Derived("generic")) != "generic") {
        return "fail 4: generic nested inheritance"
    }
    if (readParent(DepthFamily.Parent.Child(9)) != 9) return "fail 5: metadata parent base"
    if (readDeep(DepthFamily.CrossDepth(10)) != 10) return "fail 6: cross-depth nested base"
    if (readShared(TopLevelDerived(11)) != 11) return "fail 7: top-level to nested base"
    if (readShared(OtherFamily.Derived(12)) != 12) return "fail 8: cross-family nested base"
    return "OK"
}
