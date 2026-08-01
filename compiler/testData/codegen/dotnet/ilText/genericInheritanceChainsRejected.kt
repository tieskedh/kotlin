// Invalid generic base instantiations and evicted bases reject the whole derived chain. A sibling
// using the same supported open base remains available.

open class RejectBase<T>(val value: T)

class SurvivingDerived<T>(value: T) : RejectBase<T>(value)

class NullableBaseArgument<T>(value: T?) : RejectBase<T?>(value)

class ExternalGenericArgument<T>(value: List<T>) : RejectBase<List<T>>(value)

class UnsupportedInputProjectionArgument<T>(value: Array<in T>) : RejectBase<Array<in T>>(value)

class UnsupportedArrayArgument<T>(value: Array<Int>) : RejectBase<Array<Int>>(value)

open class EvictedBase<T> {
    fun <U> unsupported(values: Array<U?>): Array<U?> = values
}

open class EvictedDerived<T> : EvictedBase<T>()

class EvictedLeaf<T> : EvictedDerived<T>()

class EvictedArgument {
    fun <T> unsupported(values: Array<T?>): Array<T?> = values
}

class EvictedArgumentDerived<T>(value: EvictedArgument) : RejectBase<EvictedArgument>(value)

fun main() {
    println(SurvivingDerived(23).value)
}
