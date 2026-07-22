// A mapped covariant return uses one exact-return new slot plus a private final MethodImpl
// adapter for every wider base slot. The adapter dispatches virtually to the exact method and
// never owns a copy of its body. Reference nullability covariance that maps to the same CLR
// carrier reuses the ordinary base slot and needs no adapter.
open class Base(val tag: Int) {
    open fun self(): Base = this
    open val partner: Base get() = this
    open fun text(): String? = null
}

class CovariantMethod(tag: Int) : Base(tag) {
    override fun self(): CovariantMethod = this
}

class CovariantAccessor(tag: Int) : Base(tag) {
    override val partner: CovariantAccessor get() = this
}

class SameIl(tag: Int) : Base(tag) {
    override fun text(): String = "leaf-text"
}

abstract class AbstractCovariant(tag: Int) : Base(tag) {
    abstract override fun self(): AbstractCovariant
}

class ConcreteCovariant(tag: Int) : AbstractCovariant(tag) {
    override fun self(): ConcreteCovariant = this
}

open class StringBoundBase {
    open fun <T : String> bounded(value: T): String = value
}

class StringBoundDerived : StringBoundBase() {
    override fun <T : String> bounded(value: T): T = value
}

fun main() {
    println(Base(1).self().tag)
    println(SameIl(2).text())
    val concreteAsBase: Base = ConcreteCovariant(3)
    println(concreteAsBase.self().tag)
    val stringBoundAsBase: StringBoundBase = StringBoundDerived()
    println(stringBoundAsBase.bounded("bound"))
}
