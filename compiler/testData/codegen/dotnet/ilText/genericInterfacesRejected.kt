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
