package test.genericarrays

private open class Base(private val seed: Int) {
    open fun value(): Int = seed
}

private class Derived(seed: Int) : Base(seed) {
    override fun value(): Int = 30
}

private class Box<T>(val value: T)

private class ArrayBox<T>(val values: Array<T>) {
    fun first(): T = values[0]

    fun replace(value: T) {
        values[0] = value
    }
}

private fun <T> first(values: Array<T>): T = values[0]

private fun <T> replace(values: Array<T>, value: T) {
    values[0] = value
}

private fun <T : Base> bounded(values: Array<T>): Int = values[0].value()

private fun protectedGet(values: Array<String>): String = values[
    try {
        0
    } catch (e: Exception) {
        1
    },
]

private fun protectedSet(values: Array<String>, value: String) {
    values[
        try {
            0
        } catch (e: Exception) {
            1
        },
    ] = try {
        value
    } catch (e: Exception) {
        "fallback"
    }
}

private fun <T> last(values: Array<T>): T {
    var result = values[0]
    for (value in values) {
        result = value
    }
    return result
}

fun box(): String {
    val strings = arrayOf(
        "a",
        try {
            "b"
        } catch (e: Exception) {
            "fallback"
        },
    )
    if (strings.size != 2 || strings[0] != "a" || strings[1] != "b") return "fail: literal"
    if (first(strings) != "a") return "fail: generic get"
    replace(strings, "c")
    if (strings[0] != "c") return "fail: generic set"
    if (protectedGet(strings) != "c") return "fail: protected get"
    protectedSet(strings, "d")
    if (strings[0] != "d") return "fail: protected set"
    if (last(strings) != "b") return "fail: generic loop"

    val empty = emptyArray<String>()
    if (empty.size != 0) return "fail: empty"
    val nullable = arrayOfNulls<String>(2)
    if (nullable.size != 2 || nullable[0] != null || nullable[1] != null) return "fail: null allocation"
    nullable[1] = "set"
    if (nullable[1] != "set") return "fail: nullable element set"
    val nullableLiteral = arrayOf<String?>("value", null)
    if (nullableLiteral[0] != "value" || nullableLiteral[1] != null) return "fail: nullable literal"

    val derived = arrayOf(Derived(1))
    if (bounded(derived) != 30) return "fail: constrained element"
    val bases = arrayOf<Base>(Base(4), Derived(2))
    if (bases[0].value() != 4 || bases[1].value() != 30) return "fail: class elements"

    val boxes = arrayOf(Box("boxed"))
    if (boxes[0].value != "boxed") return "fail: generic-instance elements"
    val holder = ArrayBox(strings)
    if (holder.first() != "d") return "fail: generic class get"
    holder.replace("holder")
    if (strings[0] != "holder") return "fail: generic class set"

    val marker = "marker"
    val anyValues = arrayOf<Any>(1, marker)
    if (anyValues.size != 2 || anyValues[1] !== marker) return "fail: Any elements"
    if (strings != strings || strings == arrayOf("holder", "b")) return "fail: equality identity"
    if (strings !== strings || strings === arrayOf("holder", "b")) return "fail: reference identity"
    val absent: Array<String>? = null
    if (absent != null) return "fail: nullable outer"

    var negativeCaught = false
    try {
        arrayOfNulls<String>(-1)
    } catch (e: Exception) {
        negativeCaught = true
    }
    if (!negativeCaught) return "fail: negative size"

    return "OK"
}
