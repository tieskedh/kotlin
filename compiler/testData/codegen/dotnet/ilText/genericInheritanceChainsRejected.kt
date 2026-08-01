// Invalid generic base instantiations and evicted bases reject the whole derived chain. A sibling
// using the same supported open base remains available.

open class RejectBase<T>(val value: T)

class SurvivingDerived<T>(value: T) : RejectBase<T>(value)

class NullableBaseArgument<T>(value: T?) : RejectBase<T?>(value)

class ExternalGenericArgument<T>(value: List<T>) : RejectBase<List<T>>(value)

class UnsupportedNullablePrimitiveArgument<T>(value: Array<Int?>) : RejectBase<Array<Int?>>(value)

class UnsupportedArrayArgument<T>(value: Array<Int>) : RejectBase<Array<Int>>(value)

open class EvictedBase<T>(val unsupported: Array<Int?>)

open class EvictedDerived<T>(unsupported: Array<Int?>) : EvictedBase<T>(unsupported)

class EvictedLeaf<T>(unsupported: Array<Int?>) : EvictedDerived<T>(unsupported)

class EvictedArgument(val unsupported: Array<Int?>)

class EvictedArgumentDerived<T>(value: EvictedArgument) : RejectBase<EvictedArgument>(value)

fun main() {
    println(SurvivingDerived(23).value)
}
