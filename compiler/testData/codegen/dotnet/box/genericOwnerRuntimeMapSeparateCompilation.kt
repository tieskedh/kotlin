// MODULE: lib
// FILE: mapApi.kt

// DOTNET_GENERIC_OWNER_RUNTIME_MAP_CSHARP_PROBE

package generic.owner.runtime.map

public fun mapSize(map: Map<String, Int>): Int = map.size

public fun mapIsEmpty(map: Map<String, Int>): Boolean = map.isEmpty()

public fun mapContainsKey(map: Map<String, Int>, key: String): Boolean =
    map.containsKey(key)

public fun mapContainsValue(map: Map<String, Int>, value: Int): Boolean =
    map.containsValue(value)

public fun mapGet(map: Map<String, Int>, key: String): Int? = map[key]

public fun widenedMapGet(map: Map<String, Any?>, key: String): Any? = map[key]

public fun starMapContainsKey(map: Map<*, *>, key: Any?): Boolean =
    map.containsKey(key)

public fun starMapContainsValue(map: Map<*, *>, value: Any?): Boolean =
    map.containsValue(value)

public fun starMapGet(map: Map<*, *>, key: Any?): Any? = map[key]

public fun mapKeys(map: Map<String, Int>): Set<String> = map.keys

public fun mapValues(map: Map<String, Int>): Collection<Int> = map.values

public fun mapEntries(map: Map<String, Int>): Set<Map.Entry<String, Int>> = map.entries

public fun sameMap(map: Map<*, *>, expected: Any?): Boolean = map === expected

public fun isMap(value: Any): Boolean = value is Map<*, *>

@Suppress("UNCHECKED_CAST")
public fun safeStringValueMap(value: Any): Map<String, String>? =
    value as? Map<String, String>

@Suppress("UNCHECKED_CAST")
public fun safeAnyKeyMap(value: Any): Map<Any?, Int>? =
    value as? Map<Any?, Int>

@Suppress("UNCHECKED_CAST")
public fun hardStringValueMapFails(value: Any): Boolean = try {
    value as Map<String, String>
    false
} catch (_: ClassCastException) {
    true
}

@Suppress("UNCHECKED_CAST")
public fun hardStringValueMapReceiverFails(value: Any): Boolean = try {
    (value as Map<String, String>)["seed"]
    false
} catch (_: ClassCastException) {
    true
}

@Suppress("UNCHECKED_CAST")
public fun hardAnyKeyMapFails(value: Any): Boolean = try {
    value as Map<Any?, Int>
    false
} catch (_: ClassCastException) {
    true
}

// MODULE: middle(lib)
// FILE: mapImplementation.kt

package generic.owner.runtime.map

private class SingleIterator<T>(private val value: T) : Iterator<T> {
    private var consumed: Boolean = false

    override fun hasNext(): Boolean = !consumed

    override fun next(): T {
        if (consumed) throw NoSuchElementException()
        consumed = true
        return value
    }
}

private class SingleCollection<T>(private val value: T) : Collection<T> {
    override val size: Int get() = 1

    override fun isEmpty(): Boolean = false

    override fun contains(element: T): Boolean = value == element

    override fun containsAll(elements: Collection<T>): Boolean {
        for (element in elements) {
            if (!contains(element)) return false
        }
        return true
    }

    override fun iterator(): Iterator<T> = SingleIterator(value)
}

private class SingleSet<T>(private val value: T) : Set<T> {
    override val size: Int get() = 1

    override fun isEmpty(): Boolean = false

    override fun contains(element: T): Boolean = value == element

    override fun containsAll(elements: Collection<T>): Boolean {
        for (element in elements) {
            if (!contains(element)) return false
        }
        return true
    }

    override fun iterator(): Iterator<T> = SingleIterator(value)
}

private class RuntimeEntry<K, V>(
    private val keyState: K,
    private val valueState: V,
) : Map.Entry<K, V> {
    override val key: K get() = keyState
    override val value: V get() = valueState
}

public class RuntimeMapValue<K, V>(
    private val keyState: K,
    private val valueState: V,
) : Map<K, V> {
    override val size: Int get() = 1

    override fun isEmpty(): Boolean = false

    override fun containsKey(key: K): Boolean = key == keyState

    override fun containsValue(value: V): Boolean = value == valueState

    override fun get(key: K): V? = if (containsKey(key)) valueState else null

    override val keys: Set<K> get() = SingleSet(keyState)

    override val values: Collection<V> get() = SingleCollection(valueState)

    override val entries: Set<Map.Entry<K, V>>
        get() = SingleSet(RuntimeEntry(keyState, valueState))
}

public fun stringIntMap(key: String, value: Int): RuntimeMapValue<String, Int> =
    RuntimeMapValue(key, value)

// MODULE: main(middle)
// FILE: main.kt

package generic.owner.runtime.map

private fun <T> only(elements: Iterable<T>): T {
    val iterator = elements.iterator()
    val value = iterator.next()
    if (iterator.hasNext()) throw IllegalStateException("expected one element")
    return value
}

fun box(): String {
    val map = stringIntMap("answer", 73)
    if (mapSize(map) != 1 || mapIsEmpty(map)) return "size"
    if (!mapContainsKey(map, "answer") || mapContainsKey(map, "missing")) {
        return "contains key"
    }
    if (!mapContainsValue(map, 73) || mapContainsValue(map, 74)) {
        return "contains value"
    }
    if (mapGet(map, "answer") != 73 || mapGet(map, "missing") != null) {
        return "nullable get"
    }

    val widened: Map<String, Any?> = map
    if (widenedMapGet(widened, "answer") != 73 ||
        widenedMapGet(widened, "missing") != null
    ) {
        return "widened get"
    }
    if (!starMapContainsKey(map, "answer") || starMapContainsKey(map, 73) ||
        !starMapContainsValue(map, 73) || starMapContainsValue(map, "wrong") ||
        starMapGet(map, "answer") != 73 || starMapGet(map, 73) != null
    ) {
        return "star routing"
    }

    if (only(mapKeys(map)) != "answer" || only(mapValues(map)) != 73) {
        return "views"
    }
    val entry = only(mapEntries(map))
    if (entry.key != "answer" || entry.value != 73 || !sameMap(map, map)) {
        return "entry"
    }
    if (!isMap(map)) return "classifier"
    return "OK"
}
