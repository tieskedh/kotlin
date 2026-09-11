// DOTNET_GENERIC_OWNER_ABSTRACT_FOREIGN_OUTPUT_PROBE
// MODULE: lib
// FILE: owners.kt

package generic.owner.abstract.output

interface Source<out T> {
    fun read(): T
}

class ValueSource<T>(private val value: T) : Source<T> {
    override fun read(): T = value
}

abstract class Owner<out T> {
    abstract fun source(): Source<T>
    abstract fun choose(first: Boolean): Source<T>
    abstract val current: Source<T>
}

open class KotlinOwner<T>(private val value: T) : Owner<T>() {
    override fun source(): Source<T> = ValueSource(value)
    override fun choose(first: Boolean): Source<T> = source()
    override val current: Source<T> get() = source()
}

abstract class BroadProperty<out T> {
    abstract var value: @UnsafeVariance T
}

abstract class BroadInput<out T> {
    abstract fun transform(value: Source<@UnsafeVariance T>): Source<T>
}

interface Maybe<out T> {
    fun read(): T?
}

abstract class NestedSplit<T> : Maybe<Any?> {
    // A fixed base construction is independently representable even while this owner is
    // erased. The inherited nullable payload layout, not a missing class binder, must deny it.
    abstract override fun read(): Source<Any?>?
}

value class Id(val raw: Int)

fun readWide(owner: Owner<Any?>): Any? = owner.source().read()
fun chooseWide(owner: Owner<Any?>): Any? = owner.choose(true).read()
fun currentWide(owner: Owner<Any?>): Any? = owner.current.read()
fun sourceIdentity(owner: Owner<Any?>): Any = owner.source()
fun sameOwner(owner: Owner<Any?>, other: Any): Boolean = owner === other

// MODULE: middle(lib)
// FILE: inherited.kt

package generic.owner.abstract.output

abstract class InheritedOwner<T> : Owner<T>()

abstract class ReabstractOwner<T>(value: T) : KotlinOwner<T>(value) {
    abstract override fun source(): Source<T>
    abstract override fun choose(first: Boolean): Source<T>
    abstract override val current: Source<T>
}

open class MiddleOwner<T>(value: T) : KotlinOwner<T>(value)

class ConcreteOwner<T>(private val value: T) : InheritedOwner<T>() {
    override fun source(): Source<T> = ValueSource(value)
    override fun choose(first: Boolean): Source<T> = source()
    override val current: Source<T> get() = source()
}

class BroadOwner<D> : Owner<Any?>() {
    // Deliberately produce another construction without adding a fixed semantic field:
    // that distinct state grammar remains an explicit rehearsal admission blocker.
    override fun source(): Source<Any?> = ValueSource(53)
    override fun choose(first: Boolean): Source<Any?> = ValueSource(53)
    override val current: Source<Any?> get() = ValueSource(53)
}

// MODULE: main(lib, middle)
// FILE: main.kt

package generic.owner.abstract.output

fun box(): String {
    val ints = KotlinOwner(41)
    if (readWide(ints) != 41 || chooseWide(ints) != 41 || currentWide(ints) != 41) return "value"
    val strings = MiddleOwner("middle")
    if (readWide(strings) != "middle" || chooseWide(strings) != "middle") return "inherited"
    val concrete = ConcreteOwner(43)
    if (currentWide(concrete) != 43) return "abstract inheritance"
    val broad: Owner<Any?> = ints
    if (broad !== ints) return "identity"
    val nestedBroad = BroadOwner<String>()
    if (readWide(nestedBroad) != 53 || chooseWide(nestedBroad) != 53 || currentWide(nestedBroad) != 53) return "nested widening"
    val nullable = KotlinOwner<Int?>(null)
    if (readWide(nullable) != null || chooseWide(nullable) != null) return "nullable"
    val id = KotlinOwner(Id(59))
    if ((readWide(id) as Id).raw != 59) return "value class"
    return "OK"
}
