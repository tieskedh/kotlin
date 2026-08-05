interface RejectProducer<out T> {
    fun produce(): T
}

// A constructed Kotlin-owned interface bound is the supported control: its CLR constraint is
// the one erased List owner while KLIB retains List<String>.
interface ErasedInterfaceBound<T : List<String>> {
    fun value(): T
}

class EvictedArgument {
    fun <T> unsupported(values: Array<T?>): Array<T?> = values
}

interface EvictedArgumentView : RejectProducer<EvictedArgument>

class EvictedArgumentImplementation : EvictedArgumentView {
    override fun produce(): EvictedArgument = EvictedArgument()
}
