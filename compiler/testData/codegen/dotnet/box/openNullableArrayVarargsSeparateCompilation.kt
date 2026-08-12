// MODULE: lib
// FILE: lib.kt

package nullable.boundary

public fun <T> nullableSnapshot(vararg values: T?): List<T?> = values.asList()

public fun <T : Any> nonNullSet(vararg values: T?): Set<T> = setOfNotNull(*values)

public fun <T : Any> filtered(values: Array<out T?>): List<T> = values.filterNotNull()

public fun <T : Any, C : MutableCollection<in T>> filteredTo(
    values: Array<out T?>,
    destination: C,
): C = values.filterNotNullTo(destination)

// MODULE: main(lib)
// FILE: main.kt

import nullable.boundary.*

fun box(): String {
    if (nullableSnapshot(1, null, 2) != listOf(1, null, 2)) {
        return "fail 1: value consumer"
    }
    if (nullableSnapshot("a", null, "b") != listOf("a", null, "b")) {
        return "fail 2: reference consumer"
    }

    val ints = arrayOf<Int?>(1, null, 2, 1)
    if (nullableSnapshot(*ints) != listOf(1, null, 2, 1)) return "fail 3: value spread"
    if (nonNullSet(*ints).toString() != "[1, 2]") return "fail 4: forwarded value spread"
    if (filtered(ints) != listOf(1, 2, 1)) return "fail 5: projected value array"

    val strings = arrayOf<String?>("a", null, "b")
    if (nonNullSet(*strings).toString() != "[a, b]") return "fail 6: forwarded reference spread"
    if (filtered(strings) != listOf("a", "b")) return "fail 7: projected reference array"

    val destination = arrayListOf<Any>("seed")
    if (filteredTo(ints, destination) !== destination || destination != listOf<Any>("seed", 1, 2, 1)) {
        return "fail 8: projected destination $destination"
    }
    if (nullableSnapshot<String>().isNotEmpty()) return "fail 9: omitted vararg"

    return "OK"
}
