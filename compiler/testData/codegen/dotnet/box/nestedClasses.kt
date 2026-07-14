interface NestedLabel {
    fun label(): String
}

open class NestedBase(private val seed: Int) {
    open fun value(): Int = seed
}

class GenericOuter<T> {
    class Plain(private val number: Int) {
        fun value(): Int = number
    }

    class Generic<U>(private val item: U) {
        fun item(): U = item
    }

    class Middle {
        class Deep(private val text: String) {
            fun text(): String = text
        }
    }
}

open class VisibilityOuter(private val secret: Int) {
    private class Hidden(private val number: Int) {
        private fun twice(): Int = number + number

        fun doubled(): Int = twice()

        fun readOuter(outer: VisibilityOuter): Int = outer.secret
    }

    internal class InternalNested(val value: Int)

    protected class ProtectedNested(val value: Int)

    class PublicNested(val value: Int)

    fun hiddenValue(): Int = Hidden(21).doubled()

    fun hiddenReadsOuter(): Int = Hidden(0).readOuter(this)

    fun protectedValue(): Int = ProtectedNested(7).value
}

class HierarchyOuter {
    class Derived(seed: Int) : NestedBase(seed), NestedLabel {
        override fun value(): Int = super.value() + 1

        override fun label(): String = "nested"
    }
}

class ForwardOuter {
    class UsesLater(private val later: Later) {
        fun value(): Int = later.value
    }

    class Later(val value: Int)
}

class MixedOuter {
    class Nested(private val text: String) {
        fun text(): String = text
    }

    companion object {
        fun create(text: String): Nested = Nested(text)
    }
}

fun makePlain(value: Int): GenericOuter.Plain = GenericOuter.Plain(value)

fun readPlain(value: GenericOuter.Plain): Int = value.value()

fun box(): String {
    if (readPlain(makePlain(3)) != 3) return "fail 1: nested type in top-level signatures"
    if (GenericOuter.Generic("generic").item() != "generic") return "fail 2: generic nested class"
    if (GenericOuter.Generic(4).item() != 4) return "fail 3: reified nested instantiations"
    if (GenericOuter.Middle.Deep("deep").text() != "deep") return "fail 4: recursive nesting"

    val visibility = VisibilityOuter(9)
    if (visibility.hiddenValue() != 42) return "fail 5: enclosing to nested private access"
    if (visibility.hiddenReadsOuter() != 9) return "fail 6: nested to enclosing private access"
    if (VisibilityOuter.InternalNested(5).value != 5) return "fail 7: internal nested class"
    if (visibility.protectedValue() != 7) return "fail 8: protected nested class"
    if (VisibilityOuter.PublicNested(8).value != 8) return "fail 9: public nested class"

    val derived = HierarchyOuter.Derived(10)
    val base: NestedBase = derived
    val label: NestedLabel = derived
    if (base.value() != 11) return "fail 10: nested class inheritance dispatch"
    if (label.label() != "nested") return "fail 11: nested class interface dispatch"

    val forward = ForwardOuter.UsesLater(ForwardOuter.Later(12))
    if (forward.value() != 12) return "fail 12: forward nested reference"
    if (MixedOuter.create("companion").text() != "companion") {
        return "fail 13: companion alongside nested class"
    }
    return "OK"
}
