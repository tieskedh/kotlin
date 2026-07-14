package test.genericarrays

open class Base(private val seed: Int) {
    open fun value(): Int = seed
}

class Derived(seed: Int) : Base(seed) {
    override fun value(): Int = 30
}

class Box<T>(val value: T)

class ArrayBox<T>(val values: Array<T>) {
    fun first(): T = values[0]

    fun replace(value: T) {
        values[0] = value
    }
}

fun strings(values: Array<String>): String = values[0]

fun nullableOuter(values: Array<String>?): Array<String>? = values

fun <T> first(values: Array<T>): T = values[0]

fun <T> replace(values: Array<T>, value: T) {
    values[0] = value
}

fun <T : Base> bounded(values: Array<T>): Int = values[0].value()

fun stringLiteral(): Array<String> = arrayOf(
    "a",
    try {
        "b"
    } catch (e: Exception) {
        "fallback"
    },
)

fun nullableLiteral(): Array<String?> = arrayOf<String?>("a", null)

fun emptyStrings(): Array<String> = emptyArray()

fun nullableStrings(size: Int): Array<String?> = arrayOfNulls(size)

fun baseLiteral(): Array<Base> = arrayOf<Base>(Base(1), Derived(2))

fun boxes(): Array<Box<String>> = arrayOf(Box("boxed"))

fun anyValues(marker: String): Array<Any> = arrayOf<Any>(1, marker)

fun protectedGet(values: Array<String>): String = values[
    try {
        0
    } catch (e: Exception) {
        1
    },
]

fun protectedSet(values: Array<String>, value: String) {
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

fun <T> last(values: Array<T>): T {
    var result = values[0]
    for (value in values) {
        result = value
    }
    return result
}

fun sameArray(left: Array<String>, right: Array<String>): Boolean = left == right

fun nullArray(values: Array<String>?): Boolean = values == null

fun main(args: Array<String>) {
    val values = stringLiteral()
    strings(values)
    nullableOuter(values)
    first(values)
    replace(values, "c")
    bounded(arrayOf(Derived(1)))
    nullableLiteral()
    emptyStrings()
    nullableStrings(2)
    baseLiteral()
    boxes()
    anyValues("marker")
    protectedGet(values)
    protectedSet(values, "d")
    last(values)
    val holder = ArrayBox(values)
    holder.first()
    holder.replace("e")
    sameArray(values, values)
    nullArray(null)
    args.size
}
