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

class BottomSource(private val failure: Throwable) : Source<Nothing> {
    private var calls: Int = 0

    override fun value(): Nothing {
        calls++
        throw failure
    }

    fun callCount(): Int = calls
}

class NullableBottomSource : Source<Nothing?> {
    private var calls: Int = 0

    override fun value(): Nothing? {
        calls++
        return null
    }

    fun callCount(): Int = calls
}

// Ordinary, separately compiled forwarding and storage, without inlining or a
// helper which reduces every tested call site to the same star-projected route.
fun forwardSource(source: Source<Any?>): Any? = source.value()

fun keepStringSource(source: Source<String>): Source<String> = source
fun <T> keepSource(source: Source<T>): Source<T> = source

fun chooseStringSource(source: Source<String>, useSource: Boolean): Source<String> {
    if (useSource) return source
    return StringSource()
}

class ValueBox<T>(private val value: T) {
    fun read(): T = value
}

fun <T> readBox(box: ValueBox<T>): T = box.read()

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

class ExactStringField {
    private val source: Source<String> = StringSource()
    fun read(): Source<String> = source
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

// Keep identity assertions from teaching FIR a narrower receiver type before
// the operation whose declared interface view this test intends to exercise.
private fun sameReceiver(actual: Any, expected: Any): Boolean = actual === expected

fun checkBroadStoredResults(): String {
    // Source<Nothing> is a legal Source<Int>, not evidence of a physical Source<int>.
    val failure = IllegalStateException("bottom")
    val bottom = BottomSource(failure)
    val intView: Source<Int> = bottom
    val stringView: Source<String> = bottom
    if (!sameReceiver(intView, bottom) || !sameReceiver(stringView, bottom) || bottom.callCount() != 0) return "bottom widening"
    try {
        intView.value()
        return "bottom int operation returned"
    } catch (actual: Throwable) {
        if (actual !== failure || bottom.callCount() != 1) return "bottom int dispatch"
    }
    try {
        stringView.value()
        return "bottom string operation returned"
    } catch (actual: Throwable) {
        if (actual !== failure || bottom.callCount() != 2) return "bottom string dispatch"
    }

    val stored = BroadStoredResult(intView).read()
    if (!sameReceiver(stored, bottom) || bottom.callCount() != 2) return "bottom view was narrowed"
    try {
        forwardSource(stored)
        return "bottom forwarded operation returned"
    } catch (actual: Throwable) {
        if (actual !== failure || bottom.callCount() != 3) return "bottom forwarded dispatch"
    }

    val opaque: Any = stringView
    val star = opaque as Source<*>
    if (!sameReceiver(opaque, bottom) || !sameReceiver(star, bottom) || bottom.callCount() != 3) return "bottom star recovery"
    try {
        star.value()
        return "bottom star operation returned"
    } catch (actual: Throwable) {
        if (actual !== failure || bottom.callCount() != 4) return "bottom star dispatch"
    }

    // This proves ordinary generic object transport, not that an existing
    // ValueBox<Source<object>> field can contain every Kotlin-widened value.
    val recovered: Any = readBox(ValueBox<Any>(opaque))
    if (!sameReceiver(recovered, bottom) || bottom.callCount() != 4) return "bottom generic transport"
    try {
        (recovered as Source<*>).value()
        return "bottom generic operation returned"
    } catch (actual: Throwable) {
        if (actual !== failure || bottom.callCount() != 5) return "bottom generic dispatch"
    }

    val forwardedString = keepStringSource(stringView)
    if (!sameReceiver(forwardedString, bottom) || bottom.callCount() != 5) return "bottom interface forwarding"
    try {
        forwardedString.value()
        return "bottom interface forwarding returned"
    } catch (actual: Throwable) {
        if (actual !== failure || bottom.callCount() != 6) return "bottom interface forwarding dispatch"
    }
    val genericString = keepSource<String>(stringView)
    if (!sameReceiver(genericString, bottom) || bottom.callCount() != 6) return "bottom method-generic forwarding"
    try {
        genericString.value()
        return "bottom method-generic forwarding returned"
    } catch (actual: Throwable) {
        if (actual !== failure || bottom.callCount() != 7) return "bottom method-generic forwarding dispatch"
    }
    if (!sameReceiver(chooseStringSource(stringView, true), bottom) ||
        chooseStringSource(stringView, false).value() != "different construction" ||
        bottom.callCount() != 7) return "mixed return paths"

    val nullableBottom = NullableBottomSource()
    val nullableStringView: Source<String?> = nullableBottom
    if (!sameReceiver(nullableStringView, nullableBottom) || nullableBottom.callCount() != 0) return "nullable bottom widening"
    if (nullableStringView.value() != null || nullableBottom.callCount() != 1) return "nullable bottom dispatch"

    val exactStringField = ExactStringField()
    val exactString = exactStringField.read()
    if (!sameReceiver(exactStringField.read(), exactString) ||
        exactString.value() != "different construction") return "exact string state"

    val ints = IntSource()
    val strings = StringSource()
    val wideInt: Source<Any?> = ints
    val wideString: Source<Any?> = strings
    if (!sameReceiver(keepStringSource(strings), strings) ||
        !sameReceiver(keepSource<Int>(ints), ints) || keepSource<Int>(ints).value() != 73) return "exact interface forwarding"
    if (!sameReceiver(wideInt, ints) || !sameReceiver(wideString, strings) ||
        wideInt.value() != 73 || wideString.value() != "different construction") return "returning widened dispatch"
    if (forwardSource(BroadStoredResult(ints).read()) != 73 ||
        forwardSource(wideString) != "different construction") return "returning forwarded dispatch"
    val opaqueInt: Any = wideInt
    val opaqueString: Any = wideString
    if ((opaqueInt as Source<*>).value() != 73 ||
        (opaqueString as Source<*>).value() != "different construction") return "returning star dispatch"
    val recoveredInt: Any = readBox(ValueBox<Any>(opaqueInt))
    val recoveredString: Any = readBox(ValueBox<Any>(opaqueString))
    if (!sameReceiver(recoveredInt, ints) || !sameReceiver(recoveredString, strings) ||
        (recoveredInt as Source<*>).value() != 73 ||
        (recoveredString as Source<*>).value() != "different construction") return "returning generic transport"

    val mutable = MutableStoredResult()
    mutable.replace(ints)
    if (mutable.read() !== ints || mutable.read().value() != 73) return "mutable int view"
    mutable.replace(strings)
    if (mutable.read() !== strings || mutable.read().value() != "different construction") return "mutable string view"
    return "OK"
}

fun box(): String = checkBroadStoredResults()
