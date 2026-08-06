package test

fun materializedIntRange(first: Int, last: Int): IntRange = first..last

fun steppedIntProgression(first: Int, last: Int): IntProgression = first..last step 2

fun primitiveArrayIterator(values: IntArray): IntIterator = values.iterator()

fun primitiveRangeIterator(first: Int, last: Int): IntIterator = (first..last).iterator()

fun closedStart(range: ClosedRange<Int>): Int = range.start

fun openEnd(range: OpenEndRange<Int>): Int = range.endExclusive

fun intLoop(first: Int, last: Int): Int {
    var total = 0
    for (value in first..last) total += value
    return total
}

fun longLoop(first: Long, last: Long): Long {
    var total = 0L
    for (value in first..last) total += value
    return total
}

fun charLoop(first: Char, last: Char): Int {
    var total = 0
    for (value in first..last) total += value.code
    return total
}

fun main() {
    println(materializedIntRange(1, 3))
    println(steppedIntProgression(1, 5))
    println(primitiveArrayIterator(intArrayOf(7)).nextInt())
    println(primitiveRangeIterator(8, 8).nextInt())
    println(closedStart(1..3))
    println(openEnd(1..<3))
    println(intLoop(1, 3))
    println(longLoop(1L, 3L))
    println(charLoop('a', 'c'))
}
