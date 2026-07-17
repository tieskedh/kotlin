interface RejectProducer<out T> {
    fun produce(): T
}

// Open T? does not yet have one uniform CLR slot: a value instantiation needs Nullable<T>, while
// a reference instantiation uses the reference directly.
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

class EvictedArgument(val unsupported: Float)

interface EvictedArgumentView : RejectProducer<EvictedArgument>

class EvictedArgumentImplementation : EvictedArgumentView {
    override fun produce(): EvictedArgument = EvictedArgument(1.0f)
}

class ReifiedBox<T>(val value: T)

// Kotlin generic-class casts are erased on mature targets, but this class currently has a
// reified CLR identity. Do not silently strengthen the check to ReifiedBox<string>.
@Suppress("UNCHECKED_CAST")
fun reifiedGenericCast(value: Any): ReifiedBox<String> = value as ReifiedBox<String>
