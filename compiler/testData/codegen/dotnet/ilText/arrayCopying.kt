class Item(val value: Int)

class GenericArrayFiller<T>(private val values: Array<T>) {
    fun fill(value: T) {
        values.fill(value)
    }
}

fun copyInts(values: IntArray): IntArray = values.copyOf()

fun resizeInts(values: IntArray, size: Int): IntArray = values.copyOf(size)

fun copyStrings(values: Array<String>): Array<String> = values.copyOf()

fun resizeStrings(values: Array<String>, size: Int): Array<String?> = values.copyOf(size)

fun resizeGenericInts(values: Array<Int>, size: Int): Array<Int?> = values.copyOf(size)

fun <T> resizeProjected(values: Array<out T?>, size: Int): Array<out T?> = values.copyOf(size)

fun firstAfterResizeProjectedAny(values: Array<out Any?>, size: Int): Any? {
    val copied = values.copyOf(size)
    return copied[0]
}

fun fillGenericInts(values: Array<Int>, value: Int, fromIndex: Int, toIndex: Int) {
    values.fill(value, fromIndex, toIndex)
}

fun fillNullableInts(values: Array<Int?>, value: Int?) {
    values.fill(value)
}

fun <T> fillOpen(values: Array<T>, value: T) {
    values.fill(value)
}

fun copyItems(values: Array<Item>): Array<Item> = values.copyOf()

fun copyIntsInto(
    source: IntArray,
    destination: IntArray,
    destinationOffset: Int,
    startIndex: Int,
    endIndex: Int,
): IntArray = source.copyInto(destination, destinationOffset, startIndex, endIndex)

fun copyIntsIntoDefaults(source: IntArray, destination: IntArray): IntArray =
    source.copyInto(destination)

fun copyStringsInto(source: Array<String>, destination: Array<String>): Array<String> =
    source.copyInto(destination)

fun main() {
    println(copyInts(intArrayOf(1, 2))[1])
}
