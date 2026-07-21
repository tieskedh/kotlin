package test.constraints

interface Left {
    fun left(value: Int): Int
}

interface Right {
    val right: Int
}

interface ParentMark {
    fun parentValue(): Int
}

interface ChildMark : ParentMark

open class Base(private val seed: Int) {
    open fun virtualValue(): Int = seed

    fun finalValue(): Int = seed + 1
}

class Impl(seed: Int) : Base(seed), Left, Right, ChildMark {
    override val right: Int
        get() = 5

    override fun left(value: Int): Int = value + right

    override fun virtualValue(): Int = 30

    override fun parentValue(): Int = 11
}

open class GenericParent<T>(private val item: T) {
    open fun inheritedVirtual(): T = item

    fun inheritedFinal(): T = item
}

open class StringParent(value: String) : GenericParent<String>(value)

class OverrideStringParent(value: String) : StringParent(value) {
    override fun inheritedVirtual(): String = "override"
}

fun consumeLeft(value: Left): Int = value.left(2)

fun <T> methodTotal(value: T): Int where T : Base, T : Left, T : Right =
    value.virtualValue() + value.finalValue() + value.left(value.right) + consumeLeft(value)

fun <T : Left> interfaceOnly(value: T, add: Int): Int = value.left(add)

fun <T : Base> finalOnly(value: T): Int = value.finalValue()

fun <T : Left, U> keepSecond(value: T, other: U): U {
    value.left(0)
    return other
}

fun <T : Left> forward(value: T): Int = interfaceOnly(value, 1)

fun <T : ChildMark> transitiveInterface(value: T): Int = value.parentValue()

fun <T : StringParent> inheritedGenericVirtual(value: T): String = value.inheritedVirtual()

fun <T : StringParent> inheritedGenericFinal(value: T): String = value.inheritedFinal()

fun <T : Left> protectedCall(value: T): Int = value.left(
    try {
        3
    } catch (e: Exception) {
        -1
    },
)

fun <T : Left> widenBound(value: T): Left = value

fun <T : Left> widenAny(value: T): Any = value

fun <T, U> typeParameterBound(value: T): T where T : U = value

class BoundBox<T>(val value: T) where T : Left, T : Right {
    fun total(): Int = value.left(value.right)
}

fun main() {
    val value = Impl(3)
    methodTotal(value)
    interfaceOnly(value, 4)
    finalOnly(value)
    keepSecond(value, "kept")
    forward(value)
    transitiveInterface(value)
    val inherited = OverrideStringParent("base")
    inheritedGenericVirtual(inherited)
    inheritedGenericFinal(inherited)
    protectedCall(value)
    widenBound(value)
    widenAny(value)
    typeParameterBound<Impl, Left>(value)
    BoundBox(value).total()
}
