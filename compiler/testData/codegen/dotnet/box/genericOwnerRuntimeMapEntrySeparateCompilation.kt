// MODULE: lib
// FILE: entryApi.kt

// DOTNET_GENERIC_OWNER_RUNTIME_MAP_ENTRY_CSHARP_PROBE

package generic.owner.runtime.map.entry

public interface PairSource<out K, out V> {
    public val first: K
    public val second: V
}

public interface RepeatedPairSource<out K, out V> {
    public val first: K
    public val repeated: K
}

public interface ExtraPairSource<out K, out V> {
    public val first: K
    public val second: V
    public fun isReady(): Boolean
}

public fun pairFirst(pair: PairSource<Any?, Any?>): Any? = pair.first

public fun pairSecond(pair: PairSource<Any?, Any?>): Any? = pair.second

public fun starPairFirst(pair: PairSource<*, *>): Any? = pair.first

public fun samePair(pair: PairSource<*, *>, expected: Any?): Boolean = pair === expected

public fun entryKey(entry: Map.Entry<Any?, Any?>): Any? = entry.key

public fun entryValue(entry: Map.Entry<Any?, Any?>): Any? = entry.value

public fun starEntryKey(entry: Map.Entry<*, *>): Any? = entry.key

public fun sameEntry(entry: Map.Entry<*, *>, expected: Any?): Boolean = entry === expected

public fun isEntry(value: Any): Boolean = value is Map.Entry<*, *>

public fun isNotEntry(value: Any): Boolean = value !is Map.Entry<*, *>

@Suppress("UNCHECKED_CAST")
public fun safeStringEntry(value: Any): Map.Entry<String, String>? =
    value as? Map.Entry<String, String>

@Suppress("UNCHECKED_CAST")
public fun broadEntry(value: Any): Map.Entry<Any?, Any?> =
    value as Map.Entry<Any?, Any?>

@Suppress("UNCHECKED_CAST")
public fun hardStringEntryFails(value: Any): Boolean = try {
    value as Map.Entry<String, String>
    false
} catch (_: ClassCastException) {
    true
}

// MODULE: middle(lib)
// FILE: entryImplementation.kt

package generic.owner.runtime.map.entry

public class PairValue<K, V>(
    private val firstState: K,
    private val secondState: V,
) : PairSource<K, V> {
    override val first: K get() = firstState
    override val second: V get() = secondState
}

public class RuntimeEntryValue<K, V>(
    private val keyState: K,
    private val valueState: V,
) : Map.Entry<K, V> {
    override val key: K get() = keyState
    override val value: V get() = valueState
}

public fun intStringPair(first: Int, second: String): PairValue<Int, String> =
    PairValue(first, second)

public fun stringPair(first: String, second: String): PairValue<String, String> =
    PairValue(first, second)

public fun intStringEntry(key: Int, value: String): RuntimeEntryValue<Int, String> =
    RuntimeEntryValue(key, value)

public fun stringEntry(key: String, value: String): RuntimeEntryValue<String, String> =
    RuntimeEntryValue(key, value)

// MODULE: main(middle)
// FILE: main.kt

package generic.owner.runtime.map.entry

fun box(): String {
    val valuePair: PairSource<Any?, Any?> = intStringPair(57, "pair")
    if (pairFirst(valuePair) != 57 || pairSecond(valuePair) != "pair") {
        return "value pair"
    }
    if (starPairFirst(valuePair) != 57 || !samePair(valuePair, valuePair)) {
        return "star pair"
    }

    val referencePair: PairSource<Any?, Any?> = stringPair("first", "second")
    if (pairFirst(referencePair) != "first" || pairSecond(referencePair) != "second") {
        return "reference pair"
    }

    val valueEntry: Map.Entry<Any?, Any?> = intStringEntry(58, "entry")
    if (entryKey(valueEntry) != 58 || entryValue(valueEntry) != "entry") {
        return "value entry"
    }
    if (starEntryKey(valueEntry) != 58 || !sameEntry(valueEntry, valueEntry)) {
        return "star entry"
    }
    if (!isEntry(valueEntry) || isNotEntry(valueEntry) || isEntry(valuePair)) {
        return "entry classifier"
    }

    val referenceEntry: Map.Entry<Any?, Any?> = stringEntry("key", "value")
    if (entryKey(referenceEntry) != "key" || entryValue(referenceEntry) != "value") {
        return "reference entry"
    }
    return "OK"
}
