// MODULE: lib
// FILE: mutableEntryApi.kt

// DOTNET_GENERIC_OWNER_RUNTIME_MUTABLE_MAP_ENTRY_CSHARP_PROBE

package generic.owner.runtime.mutable.map.entry

public interface PairSource<out K, out V> {
    public val first: K
    public val second: V
}

public interface MutablePair<K, V> : PairSource<K, V> {
    public fun replaceSecond(value: V): V
}

public fun pairFirst(pair: MutablePair<*, *>): Any? = pair.first

public fun pairSecond(pair: MutablePair<*, *>): Any? = pair.second

public fun replacePair(
    pair: MutablePair<*, in String>,
    value: String,
): Any? = pair.replaceSecond(value)

public fun samePair(pair: MutablePair<*, *>, expected: Any?): Boolean = pair === expected

public fun entryKey(entry: MutableMap.MutableEntry<*, *>): Any? = entry.key

public fun entryValue(entry: MutableMap.MutableEntry<*, *>): Any? = entry.value

public fun replaceEntry(
    entry: MutableMap.MutableEntry<*, in String>,
    value: String,
): Any? = entry.setValue(value)

public fun replaceIntEntry(
    entry: MutableMap.MutableEntry<*, in Int>,
    value: Int,
): Any? = entry.setValue(value)

public fun sameEntry(
    entry: MutableMap.MutableEntry<*, *>,
    expected: Any?,
): Boolean = entry === expected

// MODULE: middle(lib)
// FILE: mutableEntryImplementation.kt

package generic.owner.runtime.mutable.map.entry

public class MutablePairValue<K, V>(
    private val firstState: K,
    private var secondState: V,
) : MutablePair<K, V> {
    override val first: K get() = firstState
    override val second: V get() = secondState

    override fun replaceSecond(value: V): V {
        val previous = secondState
        secondState = value
        return previous
    }
}

public class RuntimeMutableEntryValue<K, V>(
    private val keyState: K,
    private var valueState: V,
) : MutableMap.MutableEntry<K, V> {
    override val key: K get() = keyState
    override val value: V get() = valueState

    override fun setValue(newValue: V): V {
        val previous = valueState
        valueState = newValue
        return previous
    }
}

public fun intStringPair(first: Int, second: String): MutablePairValue<Int, String> =
    MutablePairValue(first, second)

public fun intStringEntry(key: Int, value: String): RuntimeMutableEntryValue<Int, String> =
    RuntimeMutableEntryValue(key, value)

public fun stringIntEntry(key: String, value: Int): RuntimeMutableEntryValue<String, Int> =
    RuntimeMutableEntryValue(key, value)

// MODULE: main(middle)
// FILE: main.kt

package generic.owner.runtime.mutable.map.entry

fun box(): String {
    val pair = intStringPair(61, "pair")
    if (pairFirst(pair) != 61 || pairSecond(pair) != "pair" || !samePair(pair, pair)) {
        return "pair read"
    }
    if (replacePair(pair, "pair next") != "pair" || pair.second != "pair next") {
        return "pair replace"
    }

    val entry = intStringEntry(62, "entry")
    if (entryKey(entry) != 62 || entryValue(entry) != "entry" || !sameEntry(entry, entry)) {
        return "entry read"
    }
    if (replaceEntry(entry, "entry next") != "entry" || entry.value != "entry next") {
        return "entry replace"
    }

    val intEntry = stringIntEntry("key", 63)
    if (replaceIntEntry(intEntry, 64) != 63 || intEntry.value != 64) {
        return "value replace"
    }
    return "OK"
}
