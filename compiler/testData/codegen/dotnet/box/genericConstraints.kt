package test.constraints

private interface Left {
    fun left(value: Int): Int
}

private interface Right {
    val right: Int
}

private interface ParentMark {
    fun parentValue(): Int
}

private interface ChildMark : ParentMark

private open class Base(private val seed: Int) {
    open fun virtualValue(): Int = seed

    fun finalValue(): Int = seed + 1
}

private class Impl(seed: Int) : Base(seed), Left, Right, ChildMark {
    override val right: Int
        get() = 5

    override fun left(value: Int): Int = value + right

    override fun virtualValue(): Int = 30

    override fun parentValue(): Int = 11
}

private open class GenericParent<T>(private val item: T) {
    open fun inheritedVirtual(): T = item

    fun inheritedFinal(): T = item
}

private open class StringParent(value: String) : GenericParent<String>(value)

private class OverrideStringParent(value: String) : StringParent(value) {
    override fun inheritedVirtual(): String = "override"
}

private class Other : Left, Right {
    override val right: Int
        get() = 6

    override fun left(value: Int): Int = value * 2
}

private fun consumeLeft(value: Left): Int = value.left(2)

private fun <T> methodTotal(value: T): Int where T : Base, T : Left, T : Right =
    value.virtualValue() + value.finalValue() + value.left(value.right) + consumeLeft(value)

private fun <T : Left> interfaceOnly(value: T, add: Int): Int = value.left(add)

private fun <T : Base> finalOnly(value: T): Int = value.finalValue()

private fun <T : Left, U> keepSecond(value: T, other: U): U {
    value.left(0)
    return other
}

private fun <T : Left> forward(value: T): Int = interfaceOnly(value, 1)

private fun <T : ChildMark> transitiveInterface(value: T): Int = value.parentValue()

private fun <T : StringParent> inheritedGenericVirtual(value: T): String = value.inheritedVirtual()

private fun <T : StringParent> inheritedGenericFinal(value: T): String = value.inheritedFinal()

private fun <T : Left> protectedCall(value: T): Int = value.left(
    try {
        3
    } catch (e: Exception) {
        -1
    },
)

private fun <T : Left> widenBound(value: T): Left = value

private fun <T : Left> widenAny(value: T): Any = value

private fun <T, U> typeParameterBound(value: T): T where T : U = value

private class BoundBox<T>(val value: T) where T : Left, T : Right {
    fun total(): Int = value.left(value.right)
}

fun box(): String {
    val value = Impl(3)
    if (methodTotal(value) != 51) return "fail: method total"
    if (interfaceOnly(value, 4) != 9) return "fail: interface call"
    if (finalOnly(value) != 4) return "fail: final call"
    if (keepSecond(value, "kept") != "kept") return "fail: multiple parameters"
    if (forward(value) != 6) return "fail: constrained pass-through"
    if (transitiveInterface(value) != 11) return "fail: transitive interface"
    val inherited = OverrideStringParent("base")
    if (inheritedGenericVirtual(inherited) != "override") return "fail: inherited generic virtual"
    if (inheritedGenericFinal(inherited) != "base") return "fail: inherited generic final"
    if (protectedCall(value) != 8) return "fail: protected argument"

    val asLeft: Left = widenBound(value)
    if (asLeft !== value) return "fail: bound widening"
    val asAny: Any = widenAny(value)
    if (asAny !== value) return "fail: Any widening"
    if (typeParameterBound<Impl, Left>(value) !== value) {
        return "fail: type-parameter bound"
    }

    if (BoundBox(value).total() != 10) return "fail: class constraint"
    if (BoundBox(Other()).total() != 12) return "fail: second instantiation"

    return "OK"
}
