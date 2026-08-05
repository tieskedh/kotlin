private class CollisionKey(val id: Int) {
    override fun hashCode(): Int = 7
    override fun equals(other: Any?): Boolean = other is CollisionKey && other.id == id
    override fun toString(): String = "k$id"
}

private class MapCallbackFailure : RuntimeException("map callback")

private fun nonLocalAssociateExit(): String {
    listOf(1, 2, 3).associate { element ->
        if (element == 2) return "OK"
        element to element
    }
    return "fail: associate non-local return"
}

fun box(): String {
    val map = HashMap<Any?, Any?>(1)
    if (map.put(null, "null") != null || map[null] != "null") return "fail 1: null key"
    if (map.put("nullable", null) != null || !map.containsKey("nullable")) return "fail 2: null value"

    var index = 0
    while (index < 80) {
        map[CollisionKey(index)] = index
        index++
    }
    index = 0
    while (index < 80) {
        if (map[CollisionKey(index)] != index) return "fail 3: collision or resize at $index"
        index++
    }

    if (map.put(CollisionKey(10), 100) != 10 || map[CollisionKey(10)] != 100) {
        return "fail 4: replace"
    }
    if (!map.keys.remove(CollisionKey(11)) || map.containsKey(CollisionKey(11))) {
        return "fail 5: key view"
    }
    if (!map.values.remove(12) || map.containsKey(CollisionKey(12))) {
        return "fail 6: value view"
    }

    val entry = map.entries.iterator().next()
    val oldValue = entry.value
    if (entry.setValue("changed") !== oldValue || map[entry.key] != "changed") {
        return "fail 7: mutable entry"
    }

    val iterator = map.keys.iterator()
    iterator.next()
    map["structural"] = 1
    try {
        iterator.next()
        return "fail 8: concurrent modification"
    } catch (_: ConcurrentModificationException) {
    }

    val set = HashSet<Any?>(1)
    if (!set.add(null) || set.add(null)) return "fail 9: nullable set"
    index = 0
    while (index < 80) {
        if (!set.add(CollisionKey(index))) return "fail 10: set add $index"
        index++
    }
    index = 0
    while (index < 80) {
        if (!set.contains(CollisionKey(index))) return "fail 11: set lookup $index"
        index++
    }
    if (!set.remove(CollisionKey(40)) || set.contains(CollisionKey(40))) return "fail 12: set remove"

    val linkedMap = LinkedHashMap<Int, String>()
    linkedMap[2] = "b"
    linkedMap[1] = "a"
    linkedMap[2] = "B"
    if (linkedMap.keys.toString() != "[2, 1]") return "fail 13: linked map order"

    val linkedSet = LinkedHashSet<Int>()
    linkedSet.add(2)
    linkedSet.add(1)
    linkedSet.add(2)
    if (linkedSet.toString() != "[2, 1]") return "fail 14: linked set order"

    val erased: Any = map
    if (erased !is Map<*, *> || erased !is MutableMap<*, *>) return "fail 15: erased map identity"
    val erasedSet: Any = set
    if (erasedSet !is Set<*> || erasedSet !is MutableSet<*>) return "fail 16: erased set identity"

    val emptyMap = emptyMap<String, Int>()
    if (emptyMap.isNotEmpty() || !emptyMap.isEmpty() || emptyMap["missing"] != null) {
        return "fail 17: empty map"
    }
    val mapFactory = mapOf("a" to 1, "b" to 2, "a" to 3)
    if (mapFactory.size != 2 || mapFactory["a"] != 3 || mapFactory.keys.toString() != "[a, b]") {
        return "fail 18: mapOf"
    }
    val mutableMapFactory = mutableMapOf("a" to 1)
    mutableMapFactory["b"] = 2
    if (mutableMapFactory.toString() != "{a=1, b=2}") return "fail 19: mutableMapOf"
    val hashMapFactory = hashMapOf("a" to 1, "b" to 2)
    if (hashMapFactory["b"] != 2) return "fail 20: hashMapOf"
    val linkedMapFactory = linkedMapOf("b" to 2, "a" to 1)
    if (linkedMapFactory.keys.toString() != "[b, a]") return "fail 21: linkedMapOf"

    val builtMap = buildMap<String, Int>(1) {
        put("a", 1)
        this["b"] = 2
    }
    if (builtMap.toString() != "{a=1, b=2}") return "fail 22: buildMap"
    try {
        (builtMap as MutableMap<String, Int>)["c"] = 3
        return "fail 23: built map mutation"
    } catch (_: UnsupportedOperationException) {
    }

    val pairDestination = linkedMapOf<String, Int>()
    arrayOf("a" to 1, "b" to 2).toMap(pairDestination)
    listOf("c" to 3).toMap(pairDestination)
    if (pairDestination.toString() != "{a=1, b=2, c=3}") return "fail 24: toMap destination"
    var entryTrace = ""
    for ((key, value) in pairDestination) entryTrace += "$key$value"
    if (entryTrace != "a1b2c3") return "fail 25: map iterator and components"
    if ((null as Map<String, Int>?).orEmpty().isNotEmpty()) return "fail 26: map orEmpty"
    if (emptyMap<String, Int>().ifEmpty { mapOf("fallback" to 4) }["fallback"] != 4) {
        return "fail 27: map ifEmpty"
    }

    val emptySet = emptySet<String>()
    if (emptySet.isNotEmpty() || emptySet.contains("missing")) return "fail 28: empty set"
    val setFactory = setOf("a", "b", "a")
    if (setFactory.toString() != "[a, b]") return "fail 29: setOf"
    val mutableSetFactory = mutableSetOf("a", "b", "a")
    mutableSetFactory.add("c")
    if (mutableSetFactory.toString() != "[a, b, c]") return "fail 30: mutableSetOf"
    if (hashSetOf("a", "b").size != 2) return "fail 31: hashSetOf"
    if (linkedSetOf("b", "a").toString() != "[b, a]") return "fail 32: linkedSetOf"
    if (setOfNotNull<String>(null).isNotEmpty() || setOfNotNull("x").toString() != "[x]") {
        return "fail 33: setOfNotNull"
    }
    val builtSet = buildSet<String>(1) {
        add("a")
        add("b")
        add("a")
    }
    if (builtSet.toString() != "[a, b]") return "fail 34: buildSet"
    try {
        (builtSet as MutableSet<String>).add("c")
        return "fail 35: built set mutation"
    } catch (_: UnsupportedOperationException) {
    }
    if (arrayOf("a", "b", "a").toSet().toString() != "[a, b]") return "fail 36: array toSet"
    if (listOf("a", "b", "a").toHashSet().size != 2) return "fail 37: iterable toHashSet"
    if (listOf("a", "b", "a").toMutableSet().toString() != "[a, b]") {
        return "fail 38: iterable toMutableSet"
    }
    if ((null as Set<String>?).orEmpty().isNotEmpty()) return "fail 39: set orEmpty"

    val associated = listOf("a", "bb", "c").associate { it to it.length }
    if (associated.toString() != "{a=1, bb=2, c=1}") return "fail 40: associate"
    val associatedBy = arrayOf("a", "bb", "cc").associateBy { it.length }
    if (associatedBy.toString() != "{1=a, 2=cc}") return "fail 41: associateBy"
    val associatedWith = listOf("a", "bb").associateWith { it.length }
    if (associatedWith.toString() != "{a=1, bb=2}") return "fail 42: associateWith"
    val grouped = listOf("a", "bb", "c", "dd").groupBy { it.length }
    if (grouped.toString() != "{1=[a, c], 2=[bb, dd]}") return "fail 43: groupBy"

    val distinct = listOf("a", "bb", "c", "dd", "eee").distinctBy { it.length }
    if (distinct.toString() != "[a, bb, eee]") return "fail 44: distinctBy"
    if (listOf(1, 2, 2, 3, 1).distinct().toString() != "[1, 2, 3]") {
        return "fail 45: distinct"
    }
    if (listOf(1, 2).union(listOf(2, 3)).toString() != "[1, 2, 3]") return "fail 46: union"
    if (listOf(1, 2, 3).intersect(listOf(3, 1)).toString() != "[1, 3]") {
        return "fail 47: intersect"
    }
    if (listOf(1, 2, 3).subtract(listOf(2)).toString() != "[1, 3]") return "fail 48: subtract"

    val transformed = linkedMapOf("a" to 1, "b" to 2)
    if (transformed.mapValues { it.value * 10 }.toString() != "{a=10, b=20}") {
        return "fail 49: mapValues"
    }
    if (transformed.mapKeys { it.value }.toString() != "{1=1, 2=2}") return "fail 50: mapKeys"
    if (transformed.filterKeys { it == "b" }.toString() != "{b=2}") return "fail 51: filterKeys"
    if (transformed.filterValues { it == 1 }.toString() != "{a=1}") return "fail 52: filterValues"
    if (transformed.filter { it.value > 1 }.toString() != "{b=2}") return "fail 53: filter"
    if (transformed.filterNot { it.key == "a" }.toString() != "{b=2}") return "fail 54: filterNot"

    val extendedMap = transformed + ("c" to 3) + mapOf("a" to 4)
    if (extendedMap.toString() != "{a=4, b=2, c=3}") return "fail 55: map plus"
    if ((extendedMap - listOf("a", "missing")).toString() != "{b=2, c=3}") {
        return "fail 56: map minus"
    }
    var defaultCalls = 0
    val getOrPutMap = mutableMapOf<String, String?>("nullable" to null)
    if (getOrPutMap.getOrPut("present") { defaultCalls++; "value" } != "value") {
        return "fail 57: getOrPut missing"
    }
    if (getOrPutMap.getOrPut("present") { defaultCalls++; "wrong" } != "value" || defaultCalls != 1) {
        return "fail 58: getOrPut present"
    }
    if (getOrPutMap.getOrPut("nullable") { defaultCalls++; "replacement" } != "replacement") {
        return "fail 59: getOrPut null"
    }
    if (emptyMap<String, String>().getOrElse("missing") { "fallback" } != "fallback") {
        return "fail 60: getOrElse"
    }
    val callbackFailure = MapCallbackFailure()
    try {
        listOf(1, 2, 3).associate { if (it == 2) throw callbackFailure else it to it }
        return "fail 61: callback returned"
    } catch (failure: MapCallbackFailure) {
        if (failure !== callbackFailure) return "fail 62: callback identity"
    }
    if (nonLocalAssociateExit() != "OK") return "fail 63: non-local associate"

    return "OK"
}
