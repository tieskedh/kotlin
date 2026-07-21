interface BaseDefault {
    fun value(): String = "base"
}

interface DerivedDefault : BaseDefault {
    override fun value(): String = "derived"

    fun exactBase(): String = super<BaseDefault>.value()
}

class InheritedDefault : DerivedDefault

class ClassOverride : DerivedDefault {
    override fun value(): String = "class"
}

interface ReabstractedDefault : BaseDefault {
    override fun value(): String
}

class ReabstractedImplementation : ReabstractedDefault {
    override fun value(): String = "reabstracted"
}

interface LeftDefault {
    fun side(): String = "left"
}

interface RightDefault {
    fun side(): String = "right"
}

class ResolvedConflict : LeftDefault, RightDefault {
    override fun side(): String = super<LeftDefault>.side() + ":" + super<RightDefault>.side()
}

private var propertyState: String = ""

interface PropertyDefault {
    val label: String
        get() = "property"

    var observed: String
        get() = propertyState
        set(value) {
            propertyState = value
        }
}

class PropertyDefaultInherited : PropertyDefault

interface DefaultArgumentDefault {
    fun combine(first: String = "O", second: String = "K"): String = first + second
}

class DefaultArgumentInherited : DefaultArgumentDefault

class DefaultArgumentOverride : DefaultArgumentDefault {
    override fun combine(first: String, second: String): String = "override:" + first + ":" + second
}

class DefaultContainer {
    interface NestedDefault {
        fun nested(): String = "nested"
    }

    class NestedInherited : NestedDefault
}

interface GenericDefault<out T> {
    fun seed(): T

    fun value(): T = seed()

    fun <R : @UnsafeVariance T> echo(value: R): R = value

    fun same(value: @UnsafeVariance T): Boolean = seed() == value

    val propertyValue: T
        get() = seed()
}

interface DerivedGenericDefault<T> : GenericDefault<T> {
    override fun value(): T = super<GenericDefault>.value()
}

class IntGenericDefault(private val current: Int) : GenericDefault<Int> {
    override fun seed(): Int = current
}

interface ClosedGenericOverride : GenericDefault<Int> {
    override fun seed(): Int = 40

    override fun value(): Int = 41
}

class ClosedGenericOverrideImplementation : ClosedGenericOverride

class DerivedStringGenericDefault(private val current: String) : DerivedGenericDefault<String> {
    override fun seed(): String = current
}

interface InvariantGenericDefault<T> {
    fun <R : T> echo(value: R): R = value
}

class InvariantIntGenericDefault : InvariantGenericDefault<Int>

fun box(): String {
    val inherited = InheritedDefault()
    if (inherited.value() != "derived") return "direct derived default"
    val inheritedAsBase: BaseDefault = inherited
    if (inheritedAsBase.value() != "derived") return "base-typed virtual dispatch"
    if (inherited.exactBase() != "base") return "derived interface super"

    val overridden = ClassOverride()
    if (overridden.value() != "class") return "direct class override"
    val overriddenAsDerived: DerivedDefault = overridden
    if (overriddenAsDerived.value() != "class") return "interface-typed class override"
    if (overridden.exactBase() != "base") return "inherited exact super helper"

    val reabstracted = ReabstractedImplementation()
    if (reabstracted.value() != "reabstracted") return "direct reabstracted implementation"
    val reabstractedAsBase: BaseDefault = reabstracted
    if (reabstractedAsBase.value() != "reabstracted") return "base-typed reabstracted dispatch"
    val reabstractedAsInterface: ReabstractedDefault = reabstracted
    if (reabstractedAsInterface.value() != "reabstracted") return "reabstracted interface dispatch"

    val conflict = ResolvedConflict()
    if (conflict.side() != "left:right") return "qualified conflict resolution"
    val conflictAsLeft: LeftDefault = conflict
    val conflictAsRight: RightDefault = conflict
    if (conflictAsLeft.side() != "left:right") return "left virtual dispatch"
    if (conflictAsRight.side() != "left:right") return "right virtual dispatch"

    val propertyDefault: PropertyDefault = PropertyDefaultInherited()
    if (propertyDefault.label != "property") return "default property getter"
    propertyDefault.observed = "setter"
    if (propertyState != "setter") return "default property setter"
    if (propertyDefault.observed != "setter") return "stateful default property getter"

    val defaultArguments: DefaultArgumentDefault = DefaultArgumentInherited()
    if (defaultArguments.combine() != "OK") return "interface default arguments"
    if (defaultArguments.combine("A") != "AK") return "first interface default argument"
    if (defaultArguments.combine(second = "B") != "OB") return "named interface default argument"

    val overriddenDefaults: DefaultArgumentDefault = DefaultArgumentOverride()
    if (overriddenDefaults.combine() != "override:O:K") return "default arguments bypassed class override"

    val nestedDefault: DefaultContainer.NestedDefault = DefaultContainer.NestedInherited()
    if (nestedDefault.nested() != "nested") return "nested interface default"

    val intGeneric: GenericDefault<Int> = IntGenericDefault(37)
    if (intGeneric.value() != 37) return "generic typed default"
    if (intGeneric.echo(38) != 38) return "generic method default"
    if (!intGeneric.same(37)) return "generic exact-view default"
    if (intGeneric.propertyValue != 37) return "generic property default"
    val widenedGeneric: GenericDefault<Any> = intGeneric
    if (widenedGeneric.value() != 37) return "generic erased result adapter"
    if (!widenedGeneric.same(37)) return "generic erased argument adapter"
    if (widenedGeneric.echo("wide") != "wide") return "generic widened method constraint adapter"

    val closedOverride: ClosedGenericOverride = ClosedGenericOverrideImplementation()
    if (closedOverride.value() != 41) return "closed generic interface override"
    val closedAsGeneric: GenericDefault<Int> = closedOverride
    if (closedAsGeneric.value() != 41) return "closed generic typed override dispatch"
    val closedAsWidened: GenericDefault<Any> = closedOverride
    if (closedAsWidened.value() != 41) return "closed generic widened override dispatch"

    val derivedGeneric: GenericDefault<String> = DerivedStringGenericDefault("derived generic")
    if (derivedGeneric.value() != "derived generic") return "generic qualified-super helper"

    val invariant: InvariantGenericDefault<Int> = InvariantIntGenericDefault()
    if (invariant.echo(39) != 39) return "invariant generic method constraint"
    return "OK"
}
