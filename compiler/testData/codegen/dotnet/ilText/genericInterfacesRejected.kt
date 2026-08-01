interface RejectProducer<out T> {
    fun produce(): T
}

interface UnsupportedInterfaceBound<T : List<String>> {
    fun value(): T
}

class EvictedArgument {
    fun <T> unsupported(values: Array<T?>): Array<T?> = values
}

interface EvictedArgumentView : RejectProducer<EvictedArgument>

class EvictedArgumentImplementation : EvictedArgumentView {
    override fun produce(): EvictedArgument = EvictedArgument()
}

class ReifiedBox<T>(val value: T)

// Kotlin generic-class casts are erased on mature targets, but this class currently has a
// reified CLR identity. Do not silently strengthen the check to ReifiedBox<string>.
@Suppress("UNCHECKED_CAST")
fun reifiedGenericCast(value: Any): ReifiedBox<String> = value as ReifiedBox<String>

// The legal star-projected test has the same erased-class requirement. In particular, neither
// ReifiedBox<object> nor any other closed GenericInstance is its Kotlin runtime identity.
fun reifiedGenericTest(value: Any): Boolean = value is ReifiedBox<*>
