private class IteratorItem(val value: Int)

private class IteratorGenericItem<T>(val value: T)

private fun <T> first(iterator: Iterator<T>): T = iterator.next()

private fun isExhaustedTwice(iterator: Iterator<Any>): Boolean {
    try {
        iterator.next()
        return false
    } catch (_: NoSuchElementException) {
    }
    if (iterator.hasNext()) return false
    try {
        iterator.next()
        return false
    } catch (_: NoSuchElementException) {
    }
    return !iterator.hasNext()
}

fun box(): String {
    val ints = intArrayOf(3, 4).iterator()
    if (!ints.hasNext() || ints.nextInt() != 3 || ints.next() != 4 || ints.hasNext()) {
        return "fail 1: IntIterator"
    }
    val exhaustedInts: Iterator<Any> = ints
    if (!isExhaustedTwice(exhaustedInts)) return "fail 2: exhaustion"

    val longs = longArrayOf(5L).iterator()
    val doubles = doubleArrayOf(1.5).iterator()
    val booleans = booleanArrayOf(true).iterator()
    val chars = charArrayOf('Z').iterator()
    if (longs.nextLong() != 5L || doubles.nextDouble() != 1.5 ||
        !booleans.nextBoolean() || chars.nextChar() != 'Z'
    ) return "fail 3: primitive families"

    val strings = arrayOf("left", "right")
    val stringIterator = strings.iterator()
    strings[0] = "changed"
    if (!stringIterator.hasNext() || stringIterator.next() != "changed" || stringIterator.next() != "right") {
        return "fail 4: reference mutation visibility"
    }

    val nullableIterator = arrayOf<String?>(null, "value").iterator()
    if (nullableIterator.next() != null || nullableIterator.next() != "value") {
        return "fail 5: nullable reference"
    }

    val item = IteratorItem(9)
    val itemIterator = arrayOf(item).iterator()
    if (itemIterator.next() !== item) return "fail 6: user class identity"
    val genericItem = IteratorGenericItem("payload")
    val genericItemIterator = arrayOf(genericItem).iterator()
    if (genericItemIterator.next() !== genericItem || genericItem.value != "payload") {
        return "fail 7: generic class identity"
    }

    val primitiveIterator = intArrayOf(11).iterator()
    val asInt: Iterator<Int> = primitiveIterator
    val asAny: Iterator<Any> = asInt
    if (asAny !== primitiveIterator || asAny.next() != 11) return "fail 8: primitive covariance"

    val referenceIterator: Iterator<String> = arrayOf("same").iterator()
    val referenceAsAny: Iterator<Any> = referenceIterator
    if (referenceAsAny !== referenceIterator || referenceAsAny.next() != "same") {
        return "fail 9: reference covariance"
    }

    if (first(intArrayOf(17).iterator()) != 17) return "fail 10: generic primitive consumer"
    if (first(arrayOf("generic").iterator()) != "generic") return "fail 11: generic reference consumer"

    val firstIndependent = intArrayOf(1, 2).iterator()
    val secondIndependent = intArrayOf(1, 2).iterator()
    if (firstIndependent === secondIndependent || firstIndependent.nextInt() != 1 ||
        firstIndependent.nextInt() != 2 || secondIndependent.nextInt() != 1
    ) return "fail 12: independent state"

    val empty: Iterator<Any> = emptyArray<String>().iterator()
    if (empty.hasNext() || !isExhaustedTwice(empty)) return "fail 13: empty"

    val visibleIterator = charArrayOf('A').iterator()
    val opaque: Any = visibleIterator
    if (opaque !== visibleIterator) return "fail 14: Any storage identity"

    var loopSum = 0
    for (value in intArrayOf(2, 3)) {
        loopSum = loopSum + value
    }
    if (loopSum != 5) return "fail 15: direct for loop"
    return "OK"
}
