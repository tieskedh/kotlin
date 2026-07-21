// The default .NET ilText profile is portable. Kotlin interface bodies therefore move to marked
// <DefaultImpls> helpers, while the CLR interface slots remain abstract. This golden pins ordinary
// bodies, a default-argument dispatcher, an overriding class, inheritance, and a property accessor;
// net10 DIM shape and cross-profile promotion are covered by runtime/integration tests.
interface WithBody {
    fun f(): Int = 1

    fun withDefault(value: Int = 2): Int = value
}

interface SubOfBody : WithBody

class ImplOfBody : WithBody {
    override fun f(): Int = 2
}

interface WithAccessorBody {
    val ok: Boolean get() = true
}

interface GenericBody<out T> {
    fun seed(): T

    fun value(): T = seed()

    fun <R : @UnsafeVariance T> echo(value: R): R = value

    fun same(value: @UnsafeVariance T): Boolean = seed() == value
}

class GenericBodyImpl(private val current: Int) : GenericBody<Int> {
    override fun seed(): Int = current
}


interface InvariantGenericBody<T> {
    fun <R : T> echo(value: R): R = value
}

class InvariantGenericBodyImpl : InvariantGenericBody<Int>

fun main() {
    println("rejected")
}
