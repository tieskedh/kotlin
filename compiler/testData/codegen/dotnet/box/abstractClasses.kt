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

fun box(): String {
    val leaf = Leaf("stored", 3)
    if (leaf.current() != "stored") return "fail 1: abstract method"
    if (leaf.label != "label") return "fail 2: abstract property"
    if (leaf.template() != "middle:label:leaf:3") return "fail 3: abstract template dispatch"
    if (leaf.finalValue() != "stored") return "fail 4: concrete-to-abstract call"
    if (leaf.echo(11) != 11) return "fail 5: abstract generic method"
    if (callRoot(leaf) != "leaf:9") return "fail 6: re-abstracted base slot"
    if (callNamed(leaf) != "stored") return "fail 7: abstract interface slot"

    val abstractView: AbstractBase<String> = leaf
    if (abstractView.render(4) != "leaf:4") return "fail 8: abstract-class view dispatch"
    if (abstractView.echo("abstract") != "abstract") return "fail 9a: abstract generic dispatch"
    if (abstractView.mutable != 3) return "fail 9b: abstract mutable getter"
    abstractView.mutable = 6
    if (leaf.mutable != 6) return "fail 9c: abstract mutable setter"
    if (!(leaf === abstractView)) return "fail 9: abstract upcast identity"

    val deferred: Deferred = DeferredLeaf("deferred")
    if (deferred.deferred() != "deferred:done") return "fail 10: fake abstract interface obligation"

    val choice: Choice = Chosen(5)
    if (choice.choose() != "chosen") return "fail 11: sealed abstract dispatch"
    if (choice.describe() != "chosen:5") return "fail 12: open member on sealed owner"
    val genericChoice: GenericChoice<String> = StringChoice("generic-sealed")
    if (genericChoice.currentChoice() != "generic-sealed") return "fail 12a: generic sealed dispatch"

    val constrained = ConcreteConstrained(Token("marked"))
    if (constrained.current().mark() != "marked") return "fail 13: constrained abstract return"
    if (constrained.marker() != "marked") return "fail 14: constrained abstract call"

    val factory: FactoryBase = FactoryBase.create("factory")
    if (factory.product() != "factory") return "fail 15: abstract companion factory"
    if (leaf.seed != 3 || leaf.stored != "stored") return "fail 16: abstract constructor state"
    return "OK"
}
