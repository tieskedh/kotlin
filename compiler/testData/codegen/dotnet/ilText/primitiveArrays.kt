package test.arrays

private var sequence = 0

private fun marked(value: Int): Int {
    sequence = sequence * 10 + value
    return value
}

val seeded: IntArray = intArrayOf(marked(1), marked(2))

class Holder(
    val ints: IntArray,
    var chars: CharArray?,
)

class Crate<T>(val value: T)

fun allocate(size: Int): IntArray = IntArray(size)

fun ints(): IntArray = intArrayOf(1, 2, 3)

fun emptyInts(): IntArray = intArrayOf()

fun longs(): LongArray = longArrayOf(4L, -5L)

fun doubles(): DoubleArray = doubleArrayOf(1.5, -0.0)

fun booleans(): BooleanArray = booleanArrayOf(true, false)

fun chars(): CharArray = charArrayOf('A', '\u20AC')

fun intAccess(values: IntArray, index: Int, value: Int): Int {
    values[index] = value
    return values[index]
}

fun longAccess(values: LongArray, index: Int, value: Long): Long {
    values[index] = value
    return values[index]
}

fun doubleAccess(values: DoubleArray, index: Int, value: Double): Double {
    values[index] = value
    return values[index]
}

fun booleanAccess(values: BooleanArray, index: Int, value: Boolean): Boolean {
    values[index] = value
    return values[index]
}

fun charAccess(values: CharArray, index: Int, value: Char): Char {
    values[index] = value
    return values[index]
}

fun totalSize(
    ints: IntArray,
    longs: LongArray,
    doubles: DoubleArray,
    booleans: BooleanArray,
    chars: CharArray,
): Int = ints.size + longs.size + doubles.size + booleans.size + chars.size

fun sum(values: IntArray): Int {
    var result = 0
    for (value in values) {
        if (value == 2) continue
        if (value == 9) break
        result = result + value
    }
    return result
}

fun isNull(values: IntArray?): Boolean = values === null

fun sameArray(left: IntArray, right: IntArray): Boolean = left == right

fun wrap(values: IntArray): Crate<IntArray> = Crate(values)

fun main() {
}
