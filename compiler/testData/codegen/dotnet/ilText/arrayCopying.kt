class Item(val value: Int)

fun copyInts(values: IntArray): IntArray = values.copyOf()

fun resizeInts(values: IntArray, size: Int): IntArray = values.copyOf(size)

fun copyStrings(values: Array<String>): Array<String> = values.copyOf()

fun resizeStrings(values: Array<String>, size: Int): Array<String?> = values.copyOf(size)

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
