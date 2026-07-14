// Kotlin named nested classes follow the JVM's static-nested model: they carry no outer
// instance and own only their own generic parameters. The CLR representation is a recursively
// nested metadata type, with independent arity suffixes and source visibility mapped to the
// four nested-type accessibility flags.

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

fun main() {
    println(readPlain(makePlain(3)))
    println(GenericOuter.Generic("generic").item())
    println(GenericOuter.Middle.Deep("deep").text())

    val visibility = VisibilityOuter(9)
    println(visibility.hiddenValue())
    println(visibility.hiddenReadsOuter())
    println(VisibilityOuter.InternalNested(5).value)
    println(visibility.protectedValue())
    println(VisibilityOuter.PublicNested(8).value)

    val derived = HierarchyOuter.Derived(10)
    val base: NestedBase = derived
    val label: NestedLabel = derived
    println(base.value())
    println(label.label())

    println(ForwardOuter.UsesLater(ForwardOuter.Later(12)).value())
    println(MixedOuter.create("companion").text())
}
