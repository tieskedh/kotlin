// Kotlin-owned generic inheritance uses one non-generic CLR owner per declaration. `IntBox`
// physically extends `Box`; its constructor boxes Int for the erased base slot. A substituted
// narrow override receives a JVM-direction bridge for the erased base signature, so base-typed
// dispatch still reaches the most-derived body. Generic subclasses are erased in the same way.
// This class hierarchy composes with the separately selected split-interface ABI: `LabeledBox`
// extends erased `Box` and implements the ordinary `Labeled` interface without changing either
// identity model.
open class Box<T>(private var value: T) {
    fun get(): T = value

    open fun describe(): T = value
}

class IntBox(v: Int) : Box<Int>(v) {
    override fun describe(): Int = super.describe() + 1000
}

interface Labeled {
    fun label(): String
}

class LabeledBox(v: Int) : Box<Int>(v), Labeled {
    override fun label(): String = "labeled"
}

open class PlainBase {
    open fun name(): String = "base"
}

class GDerived<T>(val payload: T) : PlainBase() {
    override fun name(): String = "generic-derived"
}

fun main() {
    val ib = IntBox(42)
    println(ib.get())
    println(ib.describe())
    val asBase: Box<Int> = ib
    println(asBase.describe())
    val lb = LabeledBox(5)
    val asLabeled: Labeled = lb
    println(asLabeled.label())
    println(lb.get())
    val g = GDerived<String>("p")
    println(g.name())
    val asPlain: PlainBase = g
    println(asPlain.name())
    println(g.payload)
}
