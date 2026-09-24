// DOTNET_FOREIGN_KOTLIN_INTERFACE_RESULT_PROBE
// MODULE: lib
// FILE: source.kt
package foreign.result.kotlin

interface Source<out T> {
    fun value(): T
}

class IntSource : Source<Int> {
    override fun value(): Int = 73
}

class StringSource : Source<String> {
    override fun value(): String = "different construction"
}

class BottomSource : Source<Nothing> {
    override fun value(): Nothing = throw IllegalStateException("bottom")
}

// MODULE: main(lib)
// FILE: main.kt
package foreign.result.kotlin

fun primitive(factory: ForeignReturn.PrimitiveFactory): Int = factory.read()
fun native(factory: ForeignReturn.NativeFactory): Int = factory.read().value()
fun nativeString(source: ForeignReturn.NativeProducer<String>): String = source.read()
fun nativeObject(source: ForeignReturn.NativeProducer<Any>): Any = source.read()
fun nativeInt(source: ForeignReturn.NativeProducer<Int>): Int = source.read()

fun nativeWidened(source: ForeignReturn.NativeProducer<String>): Any {
    val wide: ForeignReturn.NativeProducer<Any> = source
    return wide.read()
}

fun nativeAfterObject(source: ForeignReturn.NativeProducer<String>): Any {
    val opaque: Any = source
    return (opaque as ForeignReturn.NativeProducer<Any>).read()
}

fun nativeCheckedObject(value: Any): Any = (value as ForeignReturn.NativeProducer<Any>).read()

fun nativeSafeWidened(source: ForeignReturn.NativeProducer<String>?): ForeignReturn.NativeProducer<Any>? =
    source as? ForeignReturn.NativeProducer<Any>

fun nativeSafeExactInt(source: ForeignReturn.NativeProducer<Int>?): ForeignReturn.NativeProducer<Int>? =
    source as? ForeignReturn.NativeProducer<Int>

fun nativeSafeFromProvider(provider: ForeignReturn.NativeProducerProvider): ForeignReturn.NativeProducer<Any>? =
    provider.get() as? ForeignReturn.NativeProducer<Any>

fun nativeSameReceiver(source: ForeignReturn.NativeProducer<String>, expected: Any): Boolean {
    val wide: ForeignReturn.NativeProducer<Any> = source
    val opaque: Any = wide
    return source === expected && wide === expected && opaque === expected
}

fun nativeSameFailure(source: ForeignReturn.NativeProducer<Int>, expected: Any): Boolean {
    try {
        source.read()
    } catch (failure: Throwable) {
        return failure === expected
    }
    return false
}

fun kotlinSource(source: Source<Int>): Int = source.value()
fun owned(factory: ForeignReturn.KotlinFactory): Any? = factory.read().value()
fun identity(factory: ForeignReturn.KotlinFactory, source: Any): Boolean = factory.read() === source
fun genericInt(factory: ForeignReturn.GenericFactory<Int>): Any? = factory.read().value()
fun genericString(factory: ForeignReturn.GenericFactory<String>): Any? = factory.read().value()
fun nested(factory: ForeignReturn.NestedFactory): Any? = (factory.read().value() as Source<*>).value()

class FreshFactory : ForeignReturn.KotlinFactory {
    override fun read(): Source<Int> = IntSource()
}

class NominalFreshFactory : ForeignReturn.KotlinFactory {
    override fun read(): IntSource = IntSource()
}

class ExactFieldFactory(private val source: IntSource) : ForeignReturn.KotlinFactory {
    override fun read(): Source<Int> = source
}

class FreshInterfaceFieldFactory : ForeignReturn.KotlinFactory {
    private val source: Source<Int> = IntSource()
    override fun read(): Source<Int> = source
}

class RelayFieldFactory : ForeignReturn.KotlinFactory {
    private val source: Source<Int> = IntSource()
    private var calls: Int = 0

    // A broad input cannot contaminate this unrelated exact result. This is an ordinary,
    // effectful helper, not a trivial getter or a parameterless producer recognizer.
    private fun relay(candidate: Source<Any?>): Source<Int> {
        calls++
        if (candidate === source) throw IllegalStateException("unexpected candidate")
        return source
    }

    override fun read(): Source<Int> = relay(StringSource())
    fun callCount(): Int = calls
}

open class OpenFieldFactory : ForeignReturn.KotlinFactory {
    private val source: Source<Int> = IntSource()
    override fun read(): Source<Int> = source
}

class BroadStoredResult(private val source: Source<Int>) {
    private fun relay(): Source<Int> = source
    fun read(): Source<Int> = relay()
}

class MutableStoredResult {
    private var source: Source<Any?> = IntSource()
    fun replace(value: Source<Any?>) { source = value }
    private fun relay(): Source<Any?> = source
    fun read(): Source<Any?> = relay()
}

fun freshFactoryValue(): Int = FreshFactory().read().value()
fun nominalFactoryValue(): Int = NominalFreshFactory().read().value()
fun exactFieldFactoryValue(source: IntSource): Int = ExactFieldFactory(source).read().value()
fun freshInterfaceFieldValue(): Int = FreshInterfaceFieldFactory().read().value()
fun openFieldValue(factory: OpenFieldFactory): Int = factory.read().value()

fun checkBroadStoredResults(): String {
    // Source<Nothing> is a legal Source<Int>, not evidence of a physical Source<int>.
    val bottom = BottomSource()
    if (BroadStoredResult(bottom).read() !== bottom) return "bottom view was narrowed"
    val ints = IntSource()
    val strings = StringSource()
    val mutable = MutableStoredResult()
    mutable.replace(ints)
    if (mutable.read() !== ints || mutable.read().value() != 73) return "mutable int view"
    mutable.replace(strings)
    if (mutable.read() !== strings || mutable.read().value() != "different construction") return "mutable string view"
    return "OK"
}

fun box(): String = checkBroadStoredResults()
