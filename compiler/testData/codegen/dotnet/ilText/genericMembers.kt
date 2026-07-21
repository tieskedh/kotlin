// Generic member functions use the same real CLR method-generic model as top-level functions.
// A generic class or interface owner keeps its `!n` parameters independent from the member's
// `!!n` parameters. Calls instantiate both tokens without substituting the open member-ref
// signature, including inherited interface owners and instantiated generic base-class slots.

interface Marked {
    fun label(): String
}

class MarkedValue(private val text: String) : Marked {
    override fun label(): String = text
}

class MemberHost<C>(val captured: C) {
    fun <M> choose(context: C, value: M): M = value

    fun <M> capturedFrom(ignored: M): C = captured

    fun <M> relay(value: M): M = choose(captured, value)

    fun <M> wrap(value: M): MemberHost<M> = MemberHost(value)

    fun <M> read(other: MemberHost<M>): M = other.captured

    fun <M : Marked> labelOf(value: M): String = value.label()
}

class TypeParameterBoundMember<C> {
    fun <T : C> use(value: T): T = value
}

interface GenericPicker<C> {
    fun <M> pick(context: C, value: M): M
}

interface ChildPicker<C> : GenericPicker<C>

class Picker<C> : ChildPicker<C> {
    override fun <M> pick(context: C, value: M): M = value
}

interface BoundMember {
    fun <M : Marked> labelOf(value: M): String
}

class BoundMemberImpl : BoundMember {
    override fun <M : Marked> labelOf(value: M): String = value.label()
}

interface GenericIdentity {
    fun <M> identity(value: M): M
}

open class GenericProvider {
    open fun <M> identity(value: M): M = value
}

class InheritedIdentity : GenericProvider(), GenericIdentity

open class GenericBase<C> {
    open fun <M> select(context: C, value: M): M = value
}

class StringDerived : GenericBase<String>() {
    override fun <M> select(context: String, value: M): M = super.select(context, value)
}

class ArityOverloads {
    fun same(value: Int): Int = value

    fun <Unused> same(value: Int): Int = value
}

object GenericObject {
    fun <M> identity(value: M): M = value
}

class CompanionOwner {
    companion object {
        fun <M> identity(value: M): M = value
    }
}

class ExtensionHost {
    fun <M> String.keep(value: M): M = value

    fun run(): String = "receiver".keep("extension")
}

fun childPick(value: ChildPicker<String>): Int = value.pick("child", 9)

fun basePick(value: GenericBase<String>): Int = value.select("base", 41)

fun main() {
    val host = MemberHost("captured")
    println(host.choose("context", 7))
    println(host.capturedFrom(true))
    println(host.relay("relayed"))
    println(host.wrap(11).captured)
    println(host.read(MemberHost("read")))
    println(host.labelOf(MarkedValue("marked")))
    println(TypeParameterBoundMember<Marked>().use(MarkedValue("owner-bound")).label())
    println(childPick(Picker()))
    val intPicker: ChildPicker<Int> = Picker()
    println(intPicker.pick(1, "value-owner"))
    println(BoundMemberImpl().labelOf(MarkedValue("bound-interface")))
    val inherited: GenericIdentity = InheritedIdentity()
    println(inherited.identity("inherited-interface"))
    println(basePick(StringDerived()))
    println(StringDerived().select("direct", "derived"))
    val overloads = ArityOverloads()
    println(overloads.same(12))
    println(overloads.same<String>(13))
    println(GenericObject.identity("object"))
    println(CompanionOwner.identity("companion"))
    println(ExtensionHost().run())
}
