// DOTNET_GENERIC_OWNER_METHOD_GENERIC_SEALED_EMISSION_PROBE

// A method-generic producer keeps its method parameter independently on every physical
// MethodDef in the natural/semantic family. The declaration certificate and these executable
// calls deliberately share one hostile fixture: exact calls must construct the natural MethodSpec,
// while widened calls must construct the corresponding semantic-capability MethodSpec.

interface MethodGenericProducer<out T> {
    fun <R> produce(marker: R): T
}

private class MethodGenericFirstView<T>(private val value: T) : MethodGenericProducer<T> {
    override fun <R> produce(marker: R): T = value
}

private class MethodGenericSecondView<T>(private val value: T) : MethodGenericProducer<T> {
    override fun <R> produce(marker: R): T = value
}

private class MethodGenericConcreteIntView(
    private val value: Int,
) : MethodGenericProducer<Int> {
    override fun <R> produce(marker: R): Int = value
}

private object MethodGenericNothingView : MethodGenericProducer<Nothing> {
    override fun <R> produce(marker: R): Nothing =
        error("the Nothing producer must not be invoked by the identity probe")
}

// An explicit nullable owner parameter keeps this owner outside the ordinary reified class path.
// Its logical supertype alone cannot select one physical construction at a later MethodSpec call.
private class MethodGenericOpenNullableView<T>(
    private val value: T?,
) : MethodGenericProducer<T?> {
    override fun <R> produce(marker: R): T? = value
}

public fun methodGenericExactIntOwnerIntMarker(
    producer: MethodGenericProducer<Int>,
): Int = producer.produce(11)

public fun methodGenericExactIntOwnerStringMarker(
    producer: MethodGenericProducer<Int>,
): Int = producer.produce("exact-int-owner")

public fun methodGenericExactStringOwnerIntMarker(
    producer: MethodGenericProducer<String>,
): String = producer.produce(13)

public fun methodGenericExactStringOwnerStringMarker(
    producer: MethodGenericProducer<String>,
): String = producer.produce("exact-string-owner")

// A default-bearing source and its generated $default dispatcher are deliberately ineligible
// for classifier-input twins. Their existing default ABI remains the sole physical authority.
public fun <R> methodGenericDefaultedClassifierInput(
    producer: MethodGenericProducer<Int>,
    marker: R,
    suffix: String = "default",
): Int = producer.produce(marker)

@Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@kotlin.internal.InlineOnly
public inline fun methodGenericInlineOnlyClassifierInput(
    producer: MethodGenericProducer<Int>,
): Int = producer.produce(21)

// Open-nullable and nested-open arguments are not part of the bounded natural-input grammar.
// Their logical MethodSpec substitution must not manufacture a physical natural MethodDef slot.
public fun <T> methodGenericOpenNullableClassifierInput(
    producer: MethodGenericProducer<T?>,
): Any? = producer

public fun <T> methodGenericNestedOpenClassifierInput(
    producer: MethodGenericProducer<MethodGenericProducer<T>>,
): Any? = producer

// The same MethodDef is instantiated three ways below. Only the exact Producer<int> argument has
// a positively proven natural CLR view. Kotlin's value-type widening and Nothing subtype rules do
// not manufacture Producer<Number> or Producer<string>, so those calls must use this MethodDef's
// paired object-input MethodSpec without changing object identity.
public fun <T> methodGenericRetainProducer(
    producer: MethodGenericProducer<T>,
): Any? = producer

public fun <T> methodGenericForwardExactProducer(
    producer: MethodGenericProducer<T>,
): Any? = methodGenericRetainProducer<T>(producer)

public fun methodGenericForwardClosedNestedProducer(
    producer: MethodGenericProducer<MethodGenericProducer<Int>>,
): Any? = methodGenericRetainProducer<MethodGenericProducer<Int>>(producer)

private fun methodGenericWidenedIntMarker(
    producer: MethodGenericProducer<Any?>,
): Any? = producer.produce(17)

private fun methodGenericWidenedStringMarker(
    producer: MethodGenericProducer<Any?>,
): Any? = producer.produce("widened")

private fun <R> methodGenericWidenedForward(
    producer: MethodGenericProducer<Any?>,
    marker: R,
): Any? = producer.produce(marker)

fun box(): String {
    val first: MethodGenericProducer<Int> = MethodGenericFirstView(41)
    val widenedFirst: MethodGenericProducer<Any?> = first
    if (widenedFirst !== first) return "fail: first widened identity"
    if (methodGenericExactIntOwnerIntMarker(first) != 41 ||
        methodGenericExactIntOwnerStringMarker(first) != 41 ||
        methodGenericDefaultedClassifierInput(first, 15) != 41 ||
        methodGenericInlineOnlyClassifierInput(first) != 41 ||
        methodGenericWidenedIntMarker(widenedFirst) != 41 ||
        methodGenericWidenedStringMarker(widenedFirst) != 41 ||
        methodGenericWidenedForward(widenedFirst, 19) != 41 ||
        methodGenericWidenedForward(widenedFirst, "open-first") != 41
    ) {
        return "fail: first method-generic routes"
    }

    val second: MethodGenericProducer<String> = MethodGenericSecondView("second")
    val widenedSecond: MethodGenericProducer<Any?> = second
    if (widenedSecond !== second) return "fail: second widened identity"
    if (methodGenericExactStringOwnerIntMarker(second) != "second" ||
        methodGenericExactStringOwnerStringMarker(second) != "second" ||
        methodGenericWidenedIntMarker(widenedSecond) != "second" ||
        methodGenericWidenedStringMarker(widenedSecond) != "second" ||
        methodGenericWidenedForward(widenedSecond, 23) != "second" ||
        methodGenericWidenedForward(widenedSecond, "open-second") != "second"
    ) {
        return "fail: second method-generic routes"
    }

    val concreteInt = MethodGenericConcreteIntView(29)
    if (methodGenericRetainProducer<Int>(concreteInt) !== concreteInt) {
        return "fail: exact producer did not use its natural view"
    }
    if (methodGenericForwardExactProducer<Int>(concreteInt) !== concreteInt) {
        return "fail: exact MethodDef parameter did not retain its natural view"
    }
    if (methodGenericRetainProducer<Number>(concreteInt) !== concreteInt) {
        return "fail: concrete classifier hid a value-type producer"
    }
    if (methodGenericRetainProducer<String>(MethodGenericNothingView) !==
        MethodGenericNothingView
    ) {
        return "fail: Nothing fabricated a CLR string producer"
    }
    val openNullable = MethodGenericOpenNullableView<String>("nullable")
    if (methodGenericRetainProducer<Any?>(openNullable) !== openNullable) {
        return "fail: erased owner ancestry fabricated a natural producer"
    }
    return "OK"
}
