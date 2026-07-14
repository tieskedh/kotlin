interface Counter {
    fun add(delta: Int): Int

    val label: String

    var value: Int

    fun <M> echo(item: M): M
}

class CounterImpl(initial: Int, override val label: String) : Counter {
    override var value: Int = initial

    override fun add(delta: Int): Int {
        value += delta
        return value
    }

    override fun <M> echo(item: M): M = item
}

class PlainCounter(delegate: Counter) : Counter by delegate

class PropertyCounter(private val delegate: Counter) : Counter by delegate

class CustomCounter(delegate: Counter) : Counter by delegate {
    override fun add(delta: Int): Int = 100 + delta
}

class ExpressionCounter(initial: Int) : Counter by CounterImpl(initial, "expression")

interface Source<T> {
    fun current(): T

    fun <M> choose(item: M): M
}

class StringSource(private val item: String) : Source<String> {
    override fun current(): String = item

    override fun <M> choose(item: M): M = item
}

class GenericSource<T>(delegate: Source<T>) : Source<T> by delegate

interface RootId {
    fun id(): String
}

interface LeafId : RootId {
    override fun id(): String
}

class LeafIdImpl : LeafId {
    override fun id(): String = "leaf"
}

class RedeclaredForwarder(delegate: LeafId) : LeafId by delegate

interface Named {
    fun name(): String
}

class NamedImpl : Named {
    override fun name(): String = "named"
}

class MultiForwarder(counter: Counter, named: Named) : Counter by counter, Named by named

fun main() {
    val implementation = CounterImpl(1, "shared")
    val plain = PlainCounter(implementation)
    val property = PropertyCounter(implementation)
    println(plain.add(2))
    property.value = 9
    println(plain.value)
    println(property.label)
    println(plain.echo("plain"))
    println(CustomCounter(implementation).add(5))
    println(ExpressionCounter(4).add(3))

    val generic: Source<String> = GenericSource(StringSource("generic"))
    println(generic.current())
    println(generic.choose(12))

    val redeclared = RedeclaredForwarder(LeafIdImpl())
    val root: RootId = redeclared
    println(root.id())
    println(redeclared.id())

    val multi = MultiForwarder(CounterImpl(20, "multi"), NamedImpl())
    println(multi.add(2))
    println(multi.name())
}
