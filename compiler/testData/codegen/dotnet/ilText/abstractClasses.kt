// Abstract and sealed Kotlin classes map to ordinary CLR abstract classes. New abstract members
// use fresh slots, abstract overrides reuse class slots, abstract interface obligations may be
// explicit or remain fake until a concrete subclass, and open/concrete members keep normal
// virtual and constructor behavior across generic and non-generic chains.

interface Named<T> {
    fun name(): T
}

open class Root(val seed: Int) {
    open fun render(value: Int): String = "root:" + value
}

abstract class AbstractBase<T>(val stored: T, seed: Int) : Root(seed), Named<T> {
    abstract fun current(): T

    abstract val label: String

    abstract var mutable: Int

    abstract override fun render(value: Int): String

    abstract override fun name(): T

    open fun template(): String = label + ":" + render(seed)

    fun finalValue(): T = current()

    abstract fun <M> echo(value: M): M
}

abstract class Middle<T>(stored: T, seed: Int) : AbstractBase<T>(stored, seed) {
    override fun template(): String = "middle:" + super.template()
}

class Leaf(stored: String, seed: Int) : Middle<String>(stored, seed) {
    override fun current(): String = stored

    override val label: String get() = "label"

    override var mutable: Int = seed

    override fun render(value: Int): String = "leaf:" + value

    override fun name(): String = stored

    override fun <M> echo(value: M): M = value
}

interface Deferred {
    fun deferred(): String
}

abstract class DeferredBase(val prefix: String) : Deferred

class DeferredLeaf(prefix: String) : DeferredBase(prefix) {
    override fun deferred(): String = prefix + ":done"
}

sealed class Choice(val code: Int) {
    abstract fun choose(): String

    open fun describe(): String = choose() + ":" + code
}

class Chosen(code: Int) : Choice(code) {
    override fun choose(): String = "chosen"
}

sealed class GenericChoice<T>(val value: T) {
    abstract fun currentChoice(): T
}

class StringChoice(value: String) : GenericChoice<String>(value) {
    override fun currentChoice(): String = value
}

interface Marked {
    fun mark(): String
}

class Token(private val text: String) : Marked {
    override fun mark(): String = text
}

abstract class Constrained<T : Marked>(val stored: T) {
    abstract fun current(): T

    fun marker(): String = current().mark()
}

class ConcreteConstrained<T : Marked>(stored: T) : Constrained<T>(stored) {
    override fun current(): T = stored
}

abstract class FactoryBase {
    abstract fun product(): String

    companion object {
        fun create(value: String): FactoryBase = FactoryLeaf(value)
    }
}

class FactoryLeaf(private val value: String) : FactoryBase() {
    override fun product(): String = value
}

fun callRoot(value: Root): String = value.render(9)

fun callNamed(value: Named<String>): String = value.name()

fun main() {
    val leaf = Leaf("stored", 3)
    println(leaf.current())
    println(leaf.label)
    println(leaf.mutable)
    println(leaf.template())
    println(leaf.finalValue())
    println(leaf.echo(11))
    val abstractView: AbstractBase<String> = leaf
    println(abstractView.echo("abstract"))
    abstractView.mutable = 6
    println(leaf.mutable)
    println(callRoot(leaf))
    println(callNamed(leaf))
    println(DeferredLeaf("deferred").deferred())
    val choice: Choice = Chosen(5)
    println(choice.describe())
    val genericChoice: GenericChoice<String> = StringChoice("generic-sealed")
    println(genericChoice.currentChoice())
    val constrained = ConcreteConstrained(Token("marked"))
    println(constrained.current().mark())
    println(constrained.marker())
    println(FactoryBase.create("factory").product())
}
