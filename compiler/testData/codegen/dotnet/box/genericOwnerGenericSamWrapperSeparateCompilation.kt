// DOTNET_GENERIC_OWNER_GENERIC_SAM_WRAPPER_CSHARP_PROBE
// MODULE: lib
// FILE: contracts.kt

package generic.owner.sam.wrapper

public fun interface Sink<in T> {
    public fun accept(value: T): String
}

public fun interface OtherSink {
    public fun accept(value: Any?): String
}

public open class MarkerValue(public val text: String) {
    override fun toString(): String = text
}

public class DerivedMarkerValue(text: String) : MarkerValue(text)

public fun <Noise, T> localOpenSink(noise: Noise): Sink<T> =
    Sink { _: T -> "local:$noise" }

public fun <Noise, T : Any> localOpenNullableSink(noise: Noise): Sink<T?> =
    Sink { value: T? -> "local-nullable:$noise:$value" }

@Suppress("UNCHECKED_CAST")
public fun safeNullableIntSink(value: Any): Sink<Int?>? = value as? Sink<Int?>

@Suppress("UNCHECKED_CAST")
public fun safeNonNullIntSink(value: Any): Sink<Int>? = value as? Sink<Int>

@Suppress("UNCHECKED_CAST")
public fun safeNullableStringSink(value: Any): Sink<String?>? = value as? Sink<String?>

@Suppress("UNCHECKED_CAST")
public fun safeNullableAnySink(value: Any): Sink<Any?>? = value as? Sink<Any?>

@Suppress("UNCHECKED_CAST")
public fun checkedNullableIntSink(value: Any): Sink<Int?> = value as Sink<Int?>

@Suppress("UNCHECKED_CAST")
public fun checkedNullableStringSinkFails(value: Any): Boolean = try {
    value as Sink<String?>
    false
} catch (_: ClassCastException) {
    true
}

private inline fun <reified T> reifiedIs(value: Any?): Boolean = value is T

public fun isNullableIntSink(value: Any?): Boolean = reifiedIs<Sink<Int?>>(value)

public fun isNullableStringSink(value: Any?): Boolean = reifiedIs<Sink<String?>>(value)

public fun isSinkClassifier(value: Any?): Boolean = value is Sink<*>

// MODULE: middle(lib)
// FILE: factories.kt

package generic.owner.sam.wrapper

private val shared: (Any?) -> String = { value -> "shared:$value" }

public fun sharedAnySink(): Sink<Any?> = Sink(shared)

public fun <Noise, T> externalOpenSink(noise: Noise): Sink<T> =
    Sink { _: T -> "external:$noise" }

public fun <Noise, T : Any> externalOpenNullableSink(noise: Noise): Sink<T?> =
    Sink { value: T? -> "external-nullable:$noise:$value" }

public fun sharedStringSink(): Sink<String> = Sink(shared)

public fun sharedIntSink(): Sink<Int> = Sink(shared)

public fun sharedNullableIntSink(): Sink<Int?> = Sink(shared)

public fun <T : Any> sharedOpenNullableSink(): Sink<T?> = Sink(shared)

public fun sharedOtherSink(): OtherSink = OtherSink(shared)

// MODULE: main(middle)
// FILE: main.kt

package generic.owner.sam.wrapper

fun box(): String {
    val anySink = sharedAnySink()
    val stringSink = sharedStringSink()
    if (anySink.accept("value") != "shared:value") return "any call"
    if (stringSink.accept("text") != "shared:text") return "string call"
    if (anySink != stringSink || stringSink != anySink) return "symmetric equality"
    if (anySink.hashCode() != stringSink.hashCode()) return "equal hash"

    val closedIntSink = sharedIntSink()
    if (anySink != closedIntSink || closedIntSink != anySink) return "value-type equality"
    if (anySink.hashCode() != closedIntSink.hashCode()) return "value-type equal hash"
    if (closedIntSink.accept(13) != "shared:13") return "closed value-type call"

    val otherSink = sharedOtherSink()
    if (anySink.equals(otherSink) || otherSink.equals(anySink)) return "classifier equality"

    val intView: Sink<Int> = anySink
    val starView: Sink<*> = anySink
    if (intView !== anySink || starView !== anySink) return "variant view identity"
    if (intView.accept(7) != "shared:7") return "value-type variant call"

    if (localOpenSink<Int, String>(3).accept("ignored") != "local:3") {
        return "local open MethodDef binder"
    }
    if (externalOpenSink<Long, String>(5L).accept("ignored") != "external:5") {
        return "external open MethodDef binder"
    }
    if (externalOpenSink<String, Int>("noise").accept(11) != "external:noise") {
        return "external value-type construction"
    }
    val localNullable = localOpenNullableSink<Int, String>(3)
    if (localNullable.accept(null) != "local-nullable:3:null" ||
        localNullable.accept("text") != "local-nullable:3:text"
    ) {
        return "local open nullable construction"
    }
    val externalNullableReference = externalOpenNullableSink<Long, String>(5L)
    if (externalNullableReference.accept(null) != "external-nullable:5:null" ||
        externalNullableReference.accept("text") != "external-nullable:5:text"
    ) {
        return "external open nullable reference construction"
    }
    val externalNullableValue = externalOpenNullableSink<String, Int>("noise")
    if (externalNullableValue.accept(null) != "external-nullable:noise:null" ||
        externalNullableValue.accept(11) != "external-nullable:noise:11"
    ) {
        return "external open nullable value construction"
    }
    val nullableValueAsAny: Any = externalNullableValue
    val recoveredNullable = safeNullableIntSink(nullableValueAsAny)
        ?: return "open nullable safe recovery"
    if (recoveredNullable !== nullableValueAsAny ||
        recoveredNullable.accept(null) != "external-nullable:noise:null" ||
        recoveredNullable.accept(23) != "external-nullable:noise:23"
    ) {
        return "open nullable safe recovery call"
    }
    val recoveredNonNull = safeNonNullIntSink(nullableValueAsAny)
        ?: return "open nullable contravariant recovery"
    if (recoveredNonNull !== nullableValueAsAny ||
        recoveredNonNull.accept(29) != "external-nullable:noise:29"
    ) {
        return "open nullable contravariant recovery call"
    }
    val checkedNullable = checkedNullableIntSink(nullableValueAsAny)
    if (checkedNullable !== nullableValueAsAny ||
        checkedNullable.accept(31) != "external-nullable:noise:31"
    ) {
        return "open nullable checked recovery"
    }
    // The C# probe checks incompatible casts against the selected BK-1/erased epoch.
    if (!isNullableIntSink(nullableValueAsAny) || !isSinkClassifier(nullableValueAsAny)) {
        return "open nullable shared runtime predicate"
    }
    val nonNullIntView: Sink<Int> = externalNullableValue
    val semanticStarView: Sink<*> = externalNullableValue
    if (nonNullIntView !== externalNullableValue || semanticStarView !== externalNullableValue ||
        nonNullIntView.accept(37) != "external-nullable:noise:37"
    ) {
        return "open nullable ordinary contravariance"
    }

    val baseNullable = externalOpenNullableSink<String, MarkerValue>("base")
    val derivedView: Sink<DerivedMarkerValue?> = baseNullable
    if (derivedView !== baseNullable ||
        derivedView.accept(DerivedMarkerValue("derived")) != "external-nullable:base:derived"
    ) {
        return "open nullable reference contravariance"
    }
    @Suppress("UNCHECKED_CAST")
    val recoveredDerived = (baseNullable as Any) as? Sink<DerivedMarkerValue?>
        ?: return "open nullable reference recovery"
    if (recoveredDerived !== baseNullable ||
        recoveredDerived.accept(null) != "external-nullable:base:null"
    ) {
        return "open nullable reference recovery call"
    }
    val closedNullableValue = sharedNullableIntSink()
    if (closedNullableValue.accept(null) != "shared:null" ||
        closedNullableValue.accept(17) != "shared:17"
    ) {
        return "closed nullable value construction"
    }
    val sharedSemanticValue = sharedOpenNullableSink<Int>()
    if (sharedSemanticValue != closedNullableValue || closedNullableValue != sharedSemanticValue ||
        sharedSemanticValue.hashCode() != closedNullableValue.hashCode() ||
        sharedSemanticValue.accept(null) != "shared:null" || sharedSemanticValue.accept(41) != "shared:41"
    ) {
        return "natural and semantic wrapper equality"
    }
    val semanticArm = if (isNullableIntSink(nullableValueAsAny)) externalNullableValue else closedNullableValue
    val naturalArm = if (!isNullableIntSink(nullableValueAsAny)) externalNullableValue else closedNullableValue
    if (semanticArm !== externalNullableValue || naturalArm !== closedNullableValue ||
        semanticArm.accept(null) != "external-nullable:noise:null" || naturalArm.accept(43) != "shared:43"
    ) {
        return "semantic and natural result join"
    }
    return "OK"
}
