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

/** A logically invariant input has one exact natural CLR construction. */
public interface CompleteInvariantContract<T> {
    public fun fetch(): T

    public fun accept(value: T)
}

public class CompleteInvariantValue<T>(private var value: T) : CompleteInvariantContract<T> {
    public override fun fetch(): T = value

    public override fun accept(value: T) {
        this.value = value
    }
}

/** The non-generic semantic owner cannot spell this otherwise invariant I<!T> result. */
public open class CompleteInvariantResultOwner<T>(
    private val value: CompleteInvariantContract<T>,
) {
    public open fun select(): CompleteInvariantContract<T> = value
}

public fun completeInvariantStarResultIdentity(
    owner: CompleteInvariantResultOwner<*>,
): Any? = owner.select()

public fun completeInvariantStarResultValue(
    owner: CompleteInvariantResultOwner<*>,
): Any? = owner.select().fetch()

private object CompleteNaturalBottomResult : CompleteNaturalContract<Nothing> {
    public override fun fetch(): Nothing =
        throw IllegalStateException("bottom result has no value")

    public override fun accept(value: Nothing) = Unit
}

public fun completeNaturalBottomResultIdentity(): Any? = CompleteNaturalBottomResult

/** Ordinary C# overrides only the natural typed members; Kotlin's widened route stays compiler ABI. */
public open class CompleteNaturalResultStore<out T> {
    public open fun select(
        label: String = "kotlin",
        index: Int = 2,
    ): CompleteNaturalContract<T> = CompleteNaturalBottomResult

    public open val selected: CompleteNaturalContract<T>
        get() = CompleteNaturalBottomResult
}

/** Recording the erased semantic `.ctor` is not, by itself, proof that this owner may reopen. */
public class CompleteBlockedSemanticConstructor<T>(
    @Suppress("UNUSED_PARAMETER") ignored: CompleteNaturalContract<T>,
) {
    public fun marker(): Int = 1
}

/** An invariant generic-class shell must not hide a nested semantic interface carrier. */
public class CompleteNestedCarrierBox<X>(public val value: X)

/** Detached prototypes and live mapping must share one carrier before this owner can reopen. */
public class CompleteBlockedNestedCarrier<T> {
    public fun echo(
        value: CompleteNestedCarrierBox<CompleteNaturalContract<T>>,
    ): CompleteNestedCarrierBox<CompleteNaturalContract<T>> = value
}

/** A copied body must keep direct-super dispatch to its producer-owned semantic hook. */
public class CompleteClonedDirectSuperResult : CompleteNaturalResultStore<Any?>() {
    public fun <T> selectFromSuper(trigger: CompleteNaturalContract<T>): Any? {
        if (trigger.fetch() == null) throw IllegalStateException("missing trigger")
        return super.select("kotlin", 2)
    }
}

/** An open owner-relative input still lacks a complete ordinary-C# semantic-result bridge. */
public open class CompleteUnsupportedOwnerInputResult<T> {
    public open fun select(value: T): CompleteNaturalContract<T> =
        throw IllegalStateException("negative proof")
}

/** A non-generic capability cannot prove the natural MethodSpec constraint R : T. */
public open class CompleteUnsupportedOwnerRelativeMethodResult<out T> {
    public open fun <R : @UnsafeVariance T> select(value: R): CompleteNaturalContract<T> =
        throw IllegalStateException("owner-relative method negative proof")
}

/** A fixed semantic-interface input is owner-independent, but not a fixed physical leaf. */
public open class FixedSemanticInputResult<out T> {
    public open fun select(
        value: CompleteNaturalContract<Any?>,
    ): CompleteNaturalContract<T> = throw IllegalStateException("fixed semantic-input negative")
}

/** An open object-domain interface input cannot yet forward to an ordinary C# typed override. */
public open class CompleteUnsupportedSemanticInputOnly<U> {
    public open fun inspect(value: CompleteNaturalContract<Any?>): Boolean =
        value.fetch() != null
}

/** Result policy belongs to this open family even after a child fixes T to Any?. */
public open class CompleteInheritedResultBase<out T> {
    public open fun select(): CompleteNaturalContract<T> =
        throw IllegalStateException("inherited result base")
}

private val completeFixedDirectResultValue = CompleteNaturalValue(41)

/** A fixed variant result can carry another CLR construction without owner state or mentioning U. */
public class CompleteFixedDirectResult<U> {
    public fun select(): CompleteNaturalContract<Any?> = completeFixedDirectResultValue
}

/** A final family must accept both semantic Kotlin values and ordinary natural C# values. */
public class CompleteFinalFixedSemanticInputResult<U> {
    public fun select(
        value: CompleteNaturalContract<Any?>,
    ): CompleteNaturalContract<Any?> = value
}

/** A final family keeps its broad interface input in the object domain, including via `$default`. */
public class CompleteFinalSemanticInputOnly<U> {
    /** Natural C# and widened Kotlin entries each execute their own correctly typed body. */
    public fun matchesPaired(
        value: CompleteNaturalContract<Any?>,
        expected: Any?,
    ): Boolean = value === expected && value.fetch() != null

    public fun matches(
        value: CompleteNaturalContract<Any?>,
        expected: Any?,
        marker: Int = 73,
    ): Boolean = marker == 73 && value === expected && value.fetch() != null
}

private var completeSemanticPropertySink: Any? = null

/** A computed property composes semantic interface input/output without generic-owner state. */
public class CompleteFinalSemanticInterfaceProperty<U> {
    public var selected: CompleteNaturalContract<Any?>
        get() = throw IllegalStateException("write-only hostile property")
        set(value) {
            completeSemanticPropertySink = value
        }

    public fun containsIdentity(value: Any?): Boolean = completeSemanticPropertySink === value
}

public fun completeFinalSemanticInputDefault(
    owner: CompleteFinalSemanticInputOnly<String>,
    value: CompleteNaturalContract<Any?>,
    expected: Any?,
): Boolean = owner.matches(value, expected)

/** Ordinary C# can supply only the natural I<int>; Kotlin owns widening and default dispatch. */
public fun completeFinalSemanticInputDefaultFromInt(
    owner: CompleteFinalSemanticInputOnly<String>,
    value: CompleteNaturalContract<Int>,
): Boolean {
    val widened: CompleteNaturalContract<Any?> = value
    return owner.matches(widened, value)
}

/** A semantic result must not contaminate an unrelated exact invariant input. */
public class CompleteFinalInvariantInputResult<U>(
    private val value: CompleteNaturalValue<Int>,
) {
    public fun select(
        input: CompleteInvariantContract<Int>,
    ): CompleteNaturalContract<Any?> {
        if (input.fetch() != 71) throw IllegalStateException("exact invariant input")
        return value
    }
}

public fun completeFinalInvariantInputResult(
    owner: CompleteFinalInvariantInputResult<String>,
    input: CompleteInvariantContract<Int>,
): Any? = owner.select(input)

/** A broad semantic argument must not erase an unrelated exact result carrier. */
public class CompleteSemanticInputExactResult<U>(
    private val value: CompleteInvariantContract<Int>,
) {
    public fun select(
        candidate: CompleteNaturalContract<Any?>,
    ): CompleteInvariantContract<Int> {
        if (candidate.fetch() == null) throw IllegalStateException("semantic candidate")
        return value
    }
}

public fun completeSemanticInputExactResult(
    owner: CompleteSemanticInputExactResult<String>,
    candidate: CompleteNaturalContract<Any?>,
): Any? = owner.select(candidate)

public fun completeWidenedParameterizedResult(
    store: CompleteNaturalResultStore<Any?>,
): Any? = store.select("kotlin", 2).fetch()

public fun completeWidenedParameterizedResultIdentity(
    store: CompleteNaturalResultStore<Any?>,
): Any? = store.select("kotlin", 2)

public fun completeWidenedDefaultResult(
    store: CompleteNaturalResultStore<Any?>,
): Any? = store.select().fetch()

public fun completeWidenedDefaultResultIdentity(
    store: CompleteNaturalResultStore<Any?>,
): Any? = store.select()

public fun completeWidenedPropertyResult(
    store: CompleteNaturalResultStore<Any?>,
): Any? = store.selected.fetch()

public fun completeWidenedPropertyResultIdentity(
    store: CompleteNaturalResultStore<Any?>,
): Any? = store.selected

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

public class CompleteWidenedOuter<T> {
    public fun nested(value: CompleteNaturalContract<T>): CompleteNaturalContract<T> = value
}

/** U is unrelated to the fixed Base<Any?> result family inherited from the producer. */
public open class CompleteFixedInheritedResult<U>(
    private val value: CompleteNaturalValue<Int>,
) : CompleteInheritedResultBase<Any?>() {
    public final override fun select(): CompleteNaturalContract<Any?> = value
}

/** The local H member, not this fixed source signature, owns the semantic-result obligation. */
public class CompleteFixedOuterResult<U>(
    private val value: CompleteNaturalValue<Int>,
) : CompleteNaturalOuter<Any?> {
    public override fun nested(): CompleteNaturalContract<Any?> = value
}

/** The semantic classifier-input twin copies this external masked-default call. */
public fun <T> completeMiddleSemanticInputDefault(
    owner: CompleteFinalSemanticInputOnly<String>,
    value: CompleteNaturalContract<T>,
): Boolean {
    val widened: CompleteNaturalContract<Any?> = value
    return owner.matches(widened, value)
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

/** Same fixed implementation, but its H contract is producer-recorded in `middle`. */
private class DownstreamFixedOuter<U>(
    private val value: CompleteNaturalValue<Int>,
) : CompleteNaturalOuter<Any?> {
    override fun nested(): CompleteNaturalContract<Any?> = value
}

private fun downstreamWidenedFetch(value: CompleteNaturalContract<Any?>): Any? =
    value.fetch()

private fun downstreamInheritedWidenedFetch(value: CompleteNaturalChild<Any?>): Any? =
    value.fetch()

private fun sameIdentity(first: Any?, second: Any?): Boolean = first === second

private fun validateFixedDirectResult(): Boolean {
    val owner = CompleteFixedDirectResult<String>()
    val first = owner.select()
    val second = owner.select()
    return sameIdentity(first, second) && first.fetch() == 41
}

private fun validateFixedOuterResult(
    intImplementation: CompleteNaturalValue<Int>,
): Boolean {
    val owner = CompleteFixedOuterResult<String>(intImplementation)
    val view: CompleteNaturalOuter<Any?> = owner
    val result = view.nested()
    return sameIdentity(result, intImplementation) && result.fetch() == 41
}

private fun validateDownstreamFixedOuterResult(
    intImplementation: CompleteNaturalValue<Int>,
): Boolean {
    val owner = DownstreamFixedOuter<String>(intImplementation)
    val view: CompleteNaturalOuter<Any?> = owner
    val result = view.nested()
    return sameIdentity(result, intImplementation) && result.fetch() == 41
}

fun box(): String {
    val intImplementation = CompleteNaturalValue(41)
    val intExact: CompleteNaturalContract<Int> = intImplementation
    val invariantImplementation = CompleteInvariantValue(71)
    val invariantOwnerExact = CompleteInvariantResultOwner(invariantImplementation)
    val invariantOwnerStar: CompleteInvariantResultOwner<*> = invariantOwnerExact
    if (invariantOwnerStar.select() !== invariantImplementation ||
        completeInvariantStarResultIdentity(invariantOwnerStar) !== invariantImplementation ||
        completeInvariantStarResultValue(invariantOwnerStar) != 71
    ) {
        return "invariant semantic result"
    }

    // A local/open owner has no externally subclassable C# ABI. Its owner-dependent input makes
    // the foreign-override probe shape deliberately unsupported, but that must not block or crash
    // same-compilation generic-owner materialization.
    open class LocalUnsupportedForeignResult<T> {
        open fun select(candidate: T): CompleteNaturalContract<T> =
            CompleteNaturalValue(candidate)
    }
    val localResult = LocalUnsupportedForeignResult<Int>().select(41)
    if (localResult.fetch() != 41) return "local unsupported foreign result"

    if (intExact.fetch() != 41) return "int exact fetch"
    intExact.accept(41)

    val intWidened: CompleteNaturalContract<Any?> = intExact
    if (CompleteBlockedSemanticConstructor<Any?>(intWidened).marker() != 1) {
        return "external semantic constructor MethodDef"
    }
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

    val widenedContract: CompleteNaturalContract<Any?> = intExact
    val widenedOuter = CompleteWidenedOuter<Any?>()
    val widenedNested = widenedOuter.nested(widenedContract)
    if (widenedNested !== intImplementation) return "nested widened identity"
    if (widenedNested.fetch() != 41) return "nested widened fetch"

    val fixedInheritedOwner = CompleteFixedInheritedResult<String>(intImplementation)
    val fixedInheritedBase: CompleteInheritedResultBase<Any?> = fixedInheritedOwner
    val fixedInheritedResult = fixedInheritedBase.select()
    if (fixedInheritedResult !== intImplementation) return "fixed inherited result identity"
    if (fixedInheritedResult.fetch() != 41) return "fixed inherited result fetch"

    val defaultResultStoreExact = CompleteNaturalResultStore<Int>()
    val defaultResultStoreWide: CompleteNaturalResultStore<Any?> = defaultResultStoreExact
    val defaultResult = defaultResultStoreWide.select()
    val bottomResult = completeNaturalBottomResultIdentity()
    if (defaultResult !== bottomResult) {
        return "default semantic result"
    }
    if (completeWidenedDefaultResultIdentity(defaultResultStoreWide) !== bottomResult) {
        return "default semantic result helper"
    }

    val fixedSemanticInput: CompleteNaturalContract<Any?> = intExact
    val fixedSemanticInputResult =
        CompleteFinalFixedSemanticInputResult<String>().select(fixedSemanticInput)
    if (fixedSemanticInputResult !== intImplementation) {
        return "final fixed semantic input identity"
    }
    if (fixedSemanticInputResult.fetch() != 41) return "final fixed semantic input fetch"

    val semanticInputOnlyOwner = CompleteFinalSemanticInputOnly<String>()
    if (!semanticInputOnlyOwner.matchesPaired(intWidened, intImplementation)) {
        return "final paired semantic input route"
    }
    if (!semanticInputOnlyOwner.matches(intWidened, intImplementation, 73)) {
        return "final semantic input explicit route"
    }
    if (!semanticInputOnlyOwner.matches(intWidened, intImplementation)) {
        return "final semantic input default helper"
    }
    if (!completeMiddleSemanticInputDefault<Any?>(semanticInputOnlyOwner, intWidened)) {
        return "middle cloned semantic input default helper"
    }

    val semanticPropertyOwner = CompleteFinalSemanticInterfaceProperty<String>()
    semanticPropertyOwner.selected = intWidened
    if (!semanticPropertyOwner.containsIdentity(intImplementation)) {
        return "semantic interface property identity"
    }

    val middleDirectSuperResult = CompleteClonedDirectSuperResult()
        .selectFromSuper<Any?>(intWidened)
    if (middleDirectSuperResult !== bottomResult) {
        return "middle cloned direct-super identity"
    }

    val finalInvariantInputOwner = CompleteFinalInvariantInputResult<String>(intImplementation)
    if (completeFinalInvariantInputResult(
            finalInvariantInputOwner,
            invariantImplementation,
        ) !== intImplementation
    ) {
        return "final exact invariant input semantic route"
    }

    val semanticInputExactResultOwner =
        CompleteSemanticInputExactResult<String>(invariantImplementation)
    if (completeSemanticInputExactResult(
            semanticInputExactResultOwner,
            intWidened,
        ) !== invariantImplementation
    ) {
        return "semantic input contaminated exact result"
    }

    if (!validateFixedDirectResult()) return "fixed direct result"
    if (!validateFixedOuterResult(intImplementation)) return "fixed outer result"
    if (!validateDownstreamFixedOuterResult(intImplementation)) {
        return "downstream fixed outer result"
    }

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
