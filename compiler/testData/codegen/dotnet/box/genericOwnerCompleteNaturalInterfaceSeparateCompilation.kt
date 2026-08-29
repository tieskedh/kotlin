// DOTNET_GENERIC_OWNER_COMPLETE_NATURAL_INTERFACE_CSHARP_PROBE

// MODULE: lib
// FILE: contracts.kt

package generic.owner.complete.natural

/**
 * One logical covariant interface whose complete CLR contract requires physical invariance.
 * Both exact members must live on the one natural TypeDef; neither belongs to an exact sibling.
 */
public interface CompleteNaturalContract<out T> {
    public fun fetch(): T

    public fun accept(value: @UnsafeVariance T)
}

public class CompleteNaturalValue<T>(private val value: T) : CompleteNaturalContract<T> {
    private var acceptedCount: Int = 0

    public override fun fetch(): T = value

    public override fun accept(value: T) {
        if (value == this.value) acceptedCount += 1
    }

    public fun acceptedCount(): Int = acceptedCount
}

public class CompleteNaturalReader {
    public fun fetch(value: CompleteNaturalContract<Any?>): Any? = value.fetch()

    public fun accept(value: CompleteNaturalContract<Any?>, candidate: Any?) {
        value.accept(candidate)
    }

    public fun same(value: CompleteNaturalContract<Any?>, expected: Any?): Boolean =
        value === expected
}

public fun completeNaturalStringFactory(): CompleteNaturalContract<String> =
    CompleteNaturalValue("factory")

// MODULE: middle(lib)
// FILE: dependentContracts.kt

package generic.owner.complete.natural

/** A memberless logical-out child must inherit the parent's actual invariant CLR construction. */
public interface CompleteNaturalChild<out T> : CompleteNaturalContract<T>

/** A nested occurrence of an invariant physical construction also requires invariant CLR variance. */
public interface CompleteNaturalOuter<out T> {
    public fun nested(): CompleteNaturalContract<T>
}

// MODULE: main(lib, middle)
// FILE: main.kt

package generic.owner.complete.natural

private class DownstreamChild(private var value: String) : CompleteNaturalChild<String> {
    override fun fetch(): String = value

    override fun accept(value: String) {
        this.value = value
    }
}

private class DownstreamOuter(
    // Keep this transitive ABI-consumption proof on one statically exact implementation edge.
    // An interface-typed constructor input is a different representation problem: it can receive
    // a logically widened semantic value and therefore needs its own natural/semantic entry plan.
    private val value: DownstreamChild,
) : CompleteNaturalOuter<String> {
    override fun nested(): CompleteNaturalContract<String> = value
}

private fun downstreamWidenedFetch(value: CompleteNaturalContract<Any?>): Any? =
    value.fetch()

private fun downstreamInheritedWidenedFetch(value: CompleteNaturalChild<Any?>): Any? =
    value.fetch()

fun box(): String {
    val intImplementation = CompleteNaturalValue(41)
    val intExact: CompleteNaturalContract<Int> = intImplementation
    if (intExact.fetch() != 41) return "int exact fetch"
    intExact.accept(41)

    val intWidened: CompleteNaturalContract<Any?> = intExact
    val reader = CompleteNaturalReader()
    if (!reader.same(intWidened, intImplementation)) return "int widened identity"
    if (reader.fetch(intWidened) != 41) return "int widened fetch"
    if (downstreamWidenedFetch(intWidened) != 41) return "int downstream widened fetch"
    reader.accept(intWidened, 41)
    if (intImplementation.acceptedCount() != 2) return "int widened accept"

    val stringImplementation = CompleteNaturalValue("forty-one")
    val stringExact: CompleteNaturalContract<String> = stringImplementation
    if (stringExact.fetch() != "forty-one") return "string exact fetch"
    stringExact.accept("forty-one")

    val stringWidened: CompleteNaturalContract<Any?> = stringExact
    if (!reader.same(stringWidened, stringImplementation)) return "string widened identity"
    if (reader.fetch(stringWidened) != "forty-one") return "string widened fetch"
    if (downstreamWidenedFetch(stringWidened) != "forty-one") {
        return "string downstream widened fetch"
    }
    reader.accept(stringWidened, "forty-one")
    if (stringImplementation.acceptedCount() != 2) return "string widened accept"

    val factoryValue = completeNaturalStringFactory()
    if (factoryValue.fetch() != "factory") return "natural factory fetch"
    factoryValue.accept("factory")

    val downstreamChild = DownstreamChild("downstream")
    val downstreamOuter = DownstreamOuter(downstreamChild)
    downstreamChild.accept("changed-downstream")
    if (downstreamOuter.nested() !== downstreamChild) return "downstream identity"
    if (downstreamOuter.nested().fetch() != "changed-downstream") return "downstream fetch"
    val downstreamWidenedChild: CompleteNaturalChild<Any?> = downstreamChild
    if (downstreamInheritedWidenedFetch(downstreamWidenedChild) != "changed-downstream") {
        return "downstream inherited widened fetch"
    }
    return "OK"
}
