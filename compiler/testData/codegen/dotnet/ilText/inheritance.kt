// Real CLR inheritance (probe series inheritprobe): an open base class loses `sealed` and its
// open members get `newslot virtual`; the derived class `extends` the base, chains its
// constructor with arguments, overrides with `virtual` (no newslot) — `virtual final` for a
// final override — and calls `super` with a plain non-virtual `call`. Call sites dispatch with
// `callvirt` on virtual callees (including through base-typed values and on final overrides)
// and keep the plain `call` for final members, including inherited ones resolved through fake
// overrides to the declaring class's method token.
open class Base(val tag: Int) {
    open fun describe(): String = "Base:" + tag
    open val label: String get() = "base-label"
    fun fixed(): Int = tag + 1
}

class Derived(tag: Int, val extra: Int) : Base(tag) {
    override fun describe(): String = "Derived[" + super.describe() + "]:" + extra
    final override val label: String get() = "derived-label"
}

fun takeBase(b: Base): String = b.describe()

fun giveBase(flag: Boolean): Base = if (flag) Derived(1, 2) else Base(3)

fun main() {
    val b: Base = Derived(4, 5)
    println(b.describe())
    println(b.label)
    println(b.fixed())
    val d = Derived(6, 7)
    println(d.describe())
    println(d.fixed())
    println(takeBase(d))
    println(giveBase(true).describe())
}
