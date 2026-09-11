// DOTNET_GENERIC_OWNER_INVARIANT_OPEN_NULLABLE_SAM_CSHARP_PROBE
// MODULE: lib
// FILE: contracts.kt

package generic.owner.invariant.nullable.sam

public fun interface PairInput<T> {
    public fun combine(first: T, second: T): String
}

public open class Token(public val text: String) {
    override fun toString(): String = text
}

public class DerivedToken(text: String) : Token(text)

public fun <Noise, T : Any> localNullableInput(noise: Noise): PairInput<T?> =
    PairInput { first, second -> "local:$noise:$first:$second" }

@Suppress("UNCHECKED_CAST")
public fun safeNullableIntInput(value: Any): PairInput<Int?>? = value as? PairInput<Int?>

@Suppress("UNCHECKED_CAST")
public fun safeIntInput(value: Any): PairInput<Int>? = value as? PairInput<Int>

@Suppress("UNCHECKED_CAST")
public fun safeNullableStringInput(value: Any): PairInput<String?>? = value as? PairInput<String?>

@Suppress("UNCHECKED_CAST")
public fun safeNullableAnyInput(value: Any): PairInput<Any?>? = value as? PairInput<Any?>

@Suppress("UNCHECKED_CAST")
public fun safeNullableTokenInput(value: Any): PairInput<Token?>? = value as? PairInput<Token?>

@Suppress("UNCHECKED_CAST")
public fun safeNullableDerivedInput(value: Any): PairInput<DerivedToken?>? = value as? PairInput<DerivedToken?>

@Suppress("UNCHECKED_CAST")
public fun checkedNullableIntInput(value: Any): PairInput<Int?> = value as PairInput<Int?>

@Suppress("UNCHECKED_CAST")
public fun checkedIntInputFails(value: Any): Boolean = try {
    value as PairInput<Int>
    false
} catch (_: ClassCastException) {
    true
}

@Suppress("UNCHECKED_CAST")
public fun checkedDerivedInputFails(value: Any): Boolean = try {
    value as PairInput<DerivedToken?>
    false
} catch (_: ClassCastException) {
    true
}

private inline fun <reified T> reifiedIs(value: Any?): Boolean = value is T

public fun isNullableIntInput(value: Any?): Boolean = reifiedIs<PairInput<Int?>>(value)

public fun isIntInput(value: Any?): Boolean = reifiedIs<PairInput<Int>>(value)

public fun isNullableDerivedInput(value: Any?): Boolean = reifiedIs<PairInput<DerivedToken?>>(value)

public fun isPairInput(value: Any?): Boolean = value is PairInput<*>

// MODULE: middle(lib)
// FILE: factories.kt

package generic.owner.invariant.nullable.sam

private val shared: (Any?, Any?) -> String = { first, second -> "shared:$first:$second" }

// Deliberately encounter the semantic plan before the natural plan in this file.
public fun <Noise, T : Any> externalNullableInput(noise: Noise): PairInput<T?> =
    PairInput { first, second -> "external:$noise:$first:$second" }

public fun <T : Any> sharedNullableInput(): PairInput<T?> = PairInput(shared)

public fun sharedClosedNullableIntInput(): PairInput<Int?> = PairInput(shared)

public fun sharedClosedStringInput(): PairInput<String> = PairInput(shared)

public fun tokenInput(): PairInput<Token?> = externalNullableInput<String, Token>("token")

public fun combineProjected(input: PairInput<in Int>, first: Int, second: Int): String =
    input.combine(first, second)

// MODULE: main(middle)
// FILE: main.kt

package generic.owner.invariant.nullable.sam

fun box(): String {
    val local = localNullableInput<String, Int>("noise")
    if (local.combine(null, 7) != "local:noise:null:7" ||
        local.combine(11, null) != "local:noise:11:null"
    ) return "local nullable input"

    val value = externalNullableInput<Long, Int>(13L)
    if (value.combine(null, null) != "external:13:null:null" ||
        value.combine(17, 19) != "external:13:17:19"
    ) return "external value input"

    val reference = externalNullableInput<Int, String>(23)
    if (reference.combine(null, "text") != "external:23:null:text" ||
        reference.combine("first", null) != "external:23:first:null"
    ) return "external reference input"

    val broad: Any = value
    val recovered = safeNullableIntInput(broad) ?: return "nullable recovery"
    if (recovered !== value || recovered.combine(null, 29) != "external:13:null:29") {
        return "nullable recovery identity or call"
    }
    if (checkedNullableIntInput(broad) !== value || !isNullableIntInput(broad) || !isPairInput(broad)) {
        return "checked recovery or classifier"
    }

    val projected: PairInput<in Int> = value
    val star: PairInput<*> = value
    if (projected !== value || star !== value ||
        projected.combine(31, 37) != "external:13:31:37" ||
        combineProjected(value, 41, 43) != "external:13:41:43"
    ) return "legal use-site projection"

    val token = tokenInput()
    val tokenProjection: PairInput<in DerivedToken?> = token
    if (tokenProjection !== token ||
        tokenProjection.combine(DerivedToken("derived"), null) != "external:token:derived:null"
    ) return "reference use-site projection"
    val recoveredToken = safeNullableTokenInput(token as Any) ?: return "token recovery"
    if (recoveredToken !== token || recoveredToken.combine(null, Token("base")) != "external:token:null:base") {
        return "token recovery call"
    }

    val semantic = sharedNullableInput<Int>()
    val natural = sharedClosedNullableIntInput()
    if (semantic != natural || natural != semantic || semantic.hashCode() != natural.hashCode()) {
        return "symmetric natural and semantic equality"
    }
    if (semantic.combine(null, 47) != "shared:null:47" || natural.combine(53, null) != "shared:53:null") {
        return "shared function forwarding"
    }
    val semanticArm = if (isNullableIntInput(broad)) semantic else natural
    val naturalArm = if (!isNullableIntInput(broad)) semantic else natural
    if (semanticArm !== semantic || naturalArm !== natural ||
        semanticArm.combine(null, 59) != "shared:null:59" || naturalArm.combine(61, null) != "shared:61:null"
    ) return "mixed result join"

    // The C# probe checks incompatible casts against the selected BK-1/erased epoch.
    return "OK"
}
