open class RejectTop

class RejectBottom : RejectTop()

interface RejectProducer<out T> {
    fun produce(): T
}

interface RejectConsumer<in T> {
    fun consume(value: T)
}

interface RejectInvariant<T> {
    fun value(): T
}

// CLR variance conversions do not apply when either differing argument is a value type.
fun valueCovariance(value: RejectProducer<Int>): RejectProducer<Any> = value

fun valueContravariance(value: RejectConsumer<Any>): RejectConsumer<Int> = value

fun nullableValueCovariance(value: RejectProducer<Int?>): RejectProducer<Any?> = value

// An unconstrained CLR type parameter may be instantiated with a value type, so the conversion
// cannot be emitted safely even though Kotlin's erased generic model accepts it.
fun <T> openCovariance(value: RejectProducer<T>): RejectProducer<Any?> = value

// ECMA-335 has declaration-site variance only; Kotlin use-site projections and stars are never
// erased or silently treated as invariant.
fun projected(value: RejectInvariant<out String>): RejectInvariant<out String> = value

fun starred(value: RejectInvariant<*>): Any? = value

interface NullableSlot<T> {
    fun nullable(): T?
}

fun nullableSlotUse(value: NullableSlot<String>): NullableSlot<String> = value

class NullableSlotImplementation : NullableSlot<String> {
    override fun nullable(): String? = null
}

interface UnsupportedInterfaceBound<T : List<String>> {
    fun value(): T
}

// Kotlin permits covariant return refinement, but implicit CLR interface mapping requires the
// return type after generic substitution to match the slot exactly.
class CovariantOverride : RejectProducer<RejectTop> {
    override fun produce(): RejectBottom = RejectBottom()
}

open class InheritedProvider {
    open fun produce(): RejectBottom = RejectBottom()
}

class CovariantInherited : InheritedProvider(), RejectProducer<RejectTop>

class EvictedArgument(val unsupported: Float)

interface EvictedArgumentView : RejectProducer<EvictedArgument>

class EvictedArgumentImplementation : EvictedArgumentView {
    override fun produce(): EvictedArgument = EvictedArgument(1.0f)
}
