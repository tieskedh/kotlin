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

    fun <Unused> same(value: Int): Int = value + 1
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

fun box(): String {
    val host = MemberHost("captured")
    if (host.choose("context", 7) != 7) return "fail 1: class and method parameters"
    if (host.capturedFrom(true) != "captured") return "fail 2: class return"
    if (host.relay("relayed") != "relayed") return "fail 3: method pass-through"
    if (host.wrap(11).captured != 11) return "fail 4: generic return owner"
    if (host.read(MemberHost("read")) != "read") return "fail 5: generic argument owner"
    if (host.labelOf(MarkedValue("marked")) != "marked") return "fail 6: constrained member"
    if (TypeParameterBoundMember<Marked>().use(MarkedValue("owner-bound")).label() != "owner-bound") {
        return "fail 6b: owner-relative method constraint"
    }

    val some: Int? = host.choose<Int?>("nullable", 8)
    if (some != 8) return "fail 7: nullable value method argument"
    val none: Int? = host.choose<Int?>("nullable", null)
    if (none != null) return "fail 8: empty nullable method argument"

    val child: ChildPicker<String> = Picker()
    if (child.pick("child", 9) != 9) return "fail 9: inherited generic interface member"
    val intOwner: ChildPicker<Int> = Picker()
    if (intOwner.pick(1, "value-owner") != "value-owner") return "fail 10: value owner"

    val bound: BoundMember = BoundMemberImpl()
    if (bound.labelOf(MarkedValue("bound")) != "bound") return "fail 11: constrained interface member"

    val inherited: GenericIdentity = InheritedIdentity()
    if (inherited.identity("inherited") != "inherited") return "fail 12: inherited interface implementation"

    val derived = StringDerived()
    val base: GenericBase<String> = derived
    if (base.select("base", 41) != 41) return "fail 13: base-view generic dispatch"
    if (derived.select("direct", "derived") != "derived") return "fail 14: generic super call"

    val overloads = ArityOverloads()
    if (overloads.same(12) != 12) return "fail 15: non-generic arity overload"
    if (overloads.same<String>(12) != 13) return "fail 16: generic arity overload"
    if (GenericObject.identity("object") != "object") return "fail 17: object member"
    if (CompanionOwner.identity("companion") != "companion") return "fail 18: companion member"
    if (ExtensionHost().run() != "extension") return "fail 19: member extension"
    return "OK"
}
