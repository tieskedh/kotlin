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

public fun <Noise, T> localOpenSink(noise: Noise): Sink<T> =
    Sink { _: T -> "local:$noise" }

// MODULE: middle(lib)
// FILE: factories.kt

package generic.owner.sam.wrapper

private val shared: (Any?) -> String = { value -> "shared:$value" }

public fun sharedAnySink(): Sink<Any?> = Sink(shared)

public fun <Noise, T> externalOpenSink(noise: Noise): Sink<T> =
    Sink { _: T -> "external:$noise" }

public fun sharedStringSink(): Sink<String> = Sink(shared)

public fun sharedIntSink(): Sink<Int> = Sink(shared)

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
    return "OK"
}
