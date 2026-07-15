fun intIterator(values: IntArray): IntIterator = values.iterator()

fun nextInt(values: IntIterator): Int = values.nextInt()

fun nextBoxed(values: Iterator<Int>): Int = values.next()

fun <T> nextGeneric(values: Iterator<T>): T = values.next()

fun stringIterator(values: Array<String>): Iterator<String> = values.iterator()

fun hasNext(values: Iterator<String>): Boolean = values.hasNext()

fun iteratorAsAny(values: IntArray): Any = values.iterator()

fun nextOrMinusOne(values: Iterator<Int>): Int = try {
    values.next()
} catch (_: NoSuchElementException) {
    -1
}

fun main() {
    println(nextInt(intArrayOf(7).iterator()))
}
