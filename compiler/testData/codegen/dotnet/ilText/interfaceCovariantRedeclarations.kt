// A covariant abstract interface redeclaration owns a distinct exact CLR slot. A body-owning
// class receives a private MethodImpl adapter for each wider inherited interface slot; the
// abstract interface itself contains no forwarding body. Same-carrier reference nullability
// covariance remains an ordinary pair of implicit slots and needs no adapter.

open class ReturnTop(val label: String)

class ReturnBottom(label: String) : ReturnTop(label)

interface CovariantBase {
    fun create(): ReturnTop

    val item: ReturnTop
}

interface CovariantMethod : CovariantBase {
    override fun create(): ReturnBottom
}

interface CovariantProperty : CovariantBase {
    override val item: ReturnBottom
}

class CovariantMethodImpl : CovariantMethod {
    override fun create(): ReturnBottom = ReturnBottom("method")

    override val item: ReturnTop get() = ReturnTop("base-item")
}

class CovariantPropertyImpl : CovariantProperty {
    override fun create(): ReturnTop = ReturnTop("base-method")

    override val item: ReturnBottom get() = ReturnBottom("property")
}

interface SameIlBase {
    fun text(): String?

    val name: String?
}

interface SameIlRedeclared : SameIlBase {
    override fun text(): String

    override val name: String
}

class SameIlImpl : SameIlRedeclared {
    override fun text(): String = "same-il"

    override val name: String get() = "same-name"
}

fun main() {
    val method: CovariantBase = CovariantMethodImpl()
    println(method.create().label)
    val property: CovariantBase = CovariantPropertyImpl()
    println(property.item.label)
    val value: SameIlBase = SameIlImpl()
    println(value.text())
    println(value.name)
}
