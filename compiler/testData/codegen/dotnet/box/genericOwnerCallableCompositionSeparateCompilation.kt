// DOTNET_GENERIC_OWNER_CALLABLE_COMPOSITION_CSHARP_PROBE
// MODULE: lib
// FILE: contracts.kt

package generic.owner.callable.composition

/**
 * Name-independent proof that one callable may compose an owner-dependent input with a different
 * owner-dependent split-nullable output.
 */
public interface Lookup<K, out V> {
    public fun lookup(key: K): V?
}

public class LookupReader {
    public fun readExactInt(source: Lookup<Int, Int>, key: Int): Int? = source.lookup(key)

    public fun readWideInt(source: Lookup<Int, Any?>, key: Int): Any? = source.lookup(key)

    public fun readExactString(source: Lookup<Int, String>, key: Int): String? =
        source.lookup(key)

    public fun readNullableInt(source: Lookup<Int, Int?>, key: Int): Int? = source.lookup(key)
}

public fun widenIntLookup(source: Lookup<Int, Int>): Lookup<Int, Any?> = source

public fun widenStringLookup(source: Lookup<Int, String>): Lookup<Int, Any?> = source

// MODULE: middle(lib)
// FILE: implementations.kt

package generic.owner.callable.composition

private class IntLookup(
    private val expectedKey: Int,
    private val value: Int,
) : Lookup<Int, Int> {
    override fun lookup(key: Int): Int? = if (key == expectedKey) value else null
}

private class StringLookup(
    private val expectedKey: Int,
    private val value: String,
) : Lookup<Int, String> {
    override fun lookup(key: Int): String? = if (key == expectedKey) value else null
}

private class NullableIntLookup(
    private val expectedKey: Int,
    private val value: Int?,
) : Lookup<Int, Int?> {
    override fun lookup(key: Int): Int? = if (key == expectedKey) value else null
}

public fun intLookup(expectedKey: Int, value: Int): Lookup<Int, Int> =
    IntLookup(expectedKey, value)

public fun stringLookup(expectedKey: Int, value: String): Lookup<Int, String> =
    StringLookup(expectedKey, value)

public fun nullableIntLookup(expectedKey: Int, value: Int?): Lookup<Int, Int?> =
    NullableIntLookup(expectedKey, value)

// MODULE: main(middle)
// FILE: main.kt

package generic.owner.callable.composition

public fun downstreamExactIntRead(source: Lookup<Int, Int>, key: Int): Int? =
    source.lookup(key)

public fun downstreamWidenedRead(source: Lookup<Int, Any?>, key: Int): Any? =
    source.lookup(key)

public fun downstreamProjectedRead(source: Lookup<Int, *>, key: Int): Any? =
    source.lookup(key)

public fun downstreamSame(source: Lookup<Int, Any?>, expected: Any?): Boolean =
    source === expected

fun box(): String {
    val reader = LookupReader()

    val ints = intLookup(3, 37)
    if (reader.readExactInt(ints, 3) != 37) return "exact int hit"
    if (reader.readExactInt(ints, 4) != null) return "exact int miss"
    if (downstreamExactIntRead(ints, 3) != 37) return "downstream exact int hit"
    if (downstreamExactIntRead(ints, 4) != null) return "downstream exact int miss"
    val wideInts = widenIntLookup(ints)
    if (!downstreamSame(wideInts, ints)) return "widened int identity"
    if (reader.readWideInt(wideInts, 3) != 37) return "widened int hit"
    if (reader.readWideInt(wideInts, 4) != null) return "widened int miss"
    if (downstreamWidenedRead(wideInts, 3) != 37) return "downstream widened int hit"
    if (downstreamWidenedRead(wideInts, 4) != null) return "downstream widened int miss"
    if (downstreamProjectedRead(ints, 3) != 37) return "projected int hit"
    if (downstreamProjectedRead(ints, 4) != null) return "projected int miss"

    val strings = stringLookup(5, "typed")
    if (reader.readExactString(strings, 5) != "typed") return "exact string hit"
    if (reader.readExactString(strings, 6) != null) return "exact string miss"
    val wideStrings = widenStringLookup(strings)
    if (!downstreamSame(wideStrings, strings)) return "widened string identity"
    if (downstreamWidenedRead(wideStrings, 5) != "typed") return "widened string hit"
    if (downstreamWidenedRead(wideStrings, 6) != null) return "widened string miss"
    if (downstreamProjectedRead(strings, 5) != "typed") return "projected string hit"

    val nullableInts = nullableIntLookup(7, 41)
    if (reader.readNullableInt(nullableInts, 7) != 41) return "nullable int hit"
    if (reader.readNullableInt(nullableInts, 8) != null) return "nullable int miss"
    val storedNull = nullableIntLookup(9, null)
    if (reader.readNullableInt(storedNull, 9) != null) return "nullable int stored null"

    return "OK"
}
