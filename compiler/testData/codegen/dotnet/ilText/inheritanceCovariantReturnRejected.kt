// Covariant-return overrides are rejected whole-class: ECMA-335 implicit slot matching
// includes the RETURN type, so the override — `virtual` without `newslot` — would land in a
// fresh slot and base-typed `callvirt` would silently run the BASE implementation
// (probe-verified). The comparison runs on MAPPED types in the member pre-pass, so `SameIl`,
// whose `String?`-to-`String` covariance maps to the same IL `string`, keeps the base slot
// and stays compiled, while the method and accessor covariance over user classes are each
// evicted whole-class.
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

fun main() {
    println(Base(1).self().tag)
    println(SameIl(2).text())
}
