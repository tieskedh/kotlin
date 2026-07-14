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

interface Action {
    fun act(): String
}

class ActionImpl(private val text: String) : Action {
    override fun act(): String = text
}

class VariableForwarder(var delegate: Action) : Action by delegate

class BoundedForwarder<D : Action>(delegate: D) : Action by delegate

open class BaseAction : Action {
    override fun act(): String = "base"
}

class InheritedForwarder(delegate: Action) : BaseAction(), Action by delegate

var delegationInitializationTrace = ""

fun recordSeed(label: String, value: Int): Int {
    delegationInitializationTrace = delegationInitializationTrace + label
    return value
}

fun recordDelegate(label: String, value: Action): Action {
    delegationInitializationTrace = delegationInitializationTrace + label
    return value
}

open class DelegationOrderBase(value: Int) {
    init {
        delegationInitializationTrace = delegationInitializationTrace + "base;"
    }
}

class OrderedForwarder(seed: Int) :
    DelegationOrderBase(recordSeed("arg;", seed)),
    Action by recordDelegate("delegate;", ActionImpl("ordered")) {
    val member = recordSeed("member;", seed)

    init {
        recordSeed("init;", seed)
    }
}

fun box(): String {
    val implementation = CounterImpl(1, "shared")
    val plain = PlainCounter(implementation)
    val property = PropertyCounter(implementation)
    if (plain.add(2) != 3) return "fail 1: plain method forwarding"
    property.value = 9
    if (plain.value != 9) return "fail 2: mutable property forwarding"
    if (property.label != "shared") return "fail 3: val-parameter forwarding"
    if (plain.echo("plain") != "plain") return "fail 4: generic method forwarding"
    if (CustomCounter(implementation).add(5) != 105) return "fail 5: explicit override wins"

    val expression = ExpressionCounter(4)
    if (expression.add(3) != 7 || expression.label != "expression") {
        return "fail 6: expression delegate initialization"
    }

    val generic: Source<String> = GenericSource(StringSource("generic"))
    if (generic.current() != "generic") return "fail 7: generic interface forwarding"
    if (generic.choose(12) != 12) return "fail 8: generic owner and method forwarding"

    val redeclared = RedeclaredForwarder(LeafIdImpl())
    val root: RootId = redeclared
    if (root.id() != "leaf") return "fail 9: inherited interface slot"
    if (redeclared.id() != "leaf") return "fail 10: redeclared interface slot"

    val multi = MultiForwarder(CounterImpl(20, "multi"), NamedImpl())
    if (multi.add(2) != 22) return "fail 11: first delegate"
    if (multi.name() != "named") return "fail 12: second delegate"

    val original = ActionImpl("original")
    val variable = VariableForwarder(original)
    variable.delegate = ActionImpl("replacement")
    if (variable.act() != "original") return "fail 13: delegation captures initial var value"
    if (BoundedForwarder(original).act() != "original") return "fail 14: bounded delegate receiver"

    val inherited = InheritedForwarder(original)
    val base: BaseAction = inherited
    val action: Action = inherited
    if (base.act() != "original") return "fail 15: delegated override through base view"
    if (action.act() != "original") return "fail 16: delegated override through interface view"

    delegationInitializationTrace = ""
    val ordered = OrderedForwarder(7)
    if (delegationInitializationTrace != "arg;base;delegate;member;init;") {
        return "fail 17: initialization order: " + delegationInitializationTrace
    }
    if (ordered.act() != "ordered" || ordered.act() != "ordered") {
        return "fail 18: expression delegate reuse"
    }
    if (delegationInitializationTrace != "arg;base;delegate;member;init;") {
        return "fail 19: delegate expression evaluated more than once"
    }
    if (ordered.member != 7) return "fail 20: member initialization"
    return "OK"
}
