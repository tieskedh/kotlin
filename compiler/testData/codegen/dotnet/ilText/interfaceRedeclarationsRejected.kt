// A redeclaration whose mapped return type differs from its inherited interface slot is rejected
// whole-interface; one implicit class member cannot fill both CLR signatures. Same-IL-type
// nullability covariance remains supported.

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
    val value: SameIlBase = SameIlImpl()
    println(value.text())
    println(value.name)
}
