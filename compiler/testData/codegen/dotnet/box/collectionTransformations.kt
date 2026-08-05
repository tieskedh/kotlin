private class CountingIterable<T>(private val values: List<T>) : Iterable<T> {
    var iteratorCalls: Int = 0
    var hasNextCalls: Int = 0
    var nextCalls: Int = 0

    override fun iterator(): Iterator<T> {
        iteratorCalls++
        return object : Iterator<T> {
            private var index: Int = 0

            override fun hasNext(): Boolean {
                hasNextCalls++
                return index < values.size
            }

            override fun next(): T {
                nextCalls++
                if (index >= values.size) throw NoSuchElementException()
                return values[index++]
            }
        }
    }
}

private class DestinationFailure : Error("destination")

private class FailingDestination<T>(private val failure: DestinationFailure) : AbstractMutableCollection<T>() {
    override val size: Int get() = 0

    override fun iterator(): MutableIterator<T> = mutableListOf<T>().iterator()

    override fun add(element: T): Boolean = throw failure
}

private fun earlyMap(): Int {
    listOf(1, 2, 3).map { value ->
        if (value == 2) return 42
        value * 10
    }
    return -1
}

private fun earlyFilter(): String {
    listOf("a", "stop", "c").filter { value ->
        if (value == "stop") return "escaped"
        true
    }
    return "failed"
}

fun box(): String {
    val supplied = listOf(2, 4, 6).iterator()
    var iteratorTrace = ""
    for (value in supplied) iteratorTrace += value.toString()
    if (iteratorTrace != "246") return "fail 1: Iterator.iterator $iteratorTrace"

    val source = CountingIterable(listOf(3, 5, 7))
    val constructed: Iterable<Int> = Iterable { source.iterator() }
    if (constructed.toList().toString() != "[3, 5, 7]" || source.iteratorCalls != 1) {
        return "fail 2: Iterable factory"
    }

    val indexed = listOf("a", "b", "c").withIndex().toList()
    if (indexed.size != 3 || indexed[0].index != 0 || indexed[0].value != "a" ||
        indexed[2].index != 2 || indexed[2].value != "c"
    ) {
        return "fail 3: Iterable.withIndex $indexed"
    }
    val indexedIterator = listOf(9, 8).iterator().withIndex()
    val indexedFirst = indexedIterator.next()
    val indexedSecond = indexedIterator.next()
    if (indexedFirst.index != 0 || indexedFirst.value != 9 ||
        indexedSecond.index != 1 || indexedSecond.value != 8 || indexedIterator.hasNext()
    ) {
        return "fail 4: Iterator.withIndex"
    }

    val flattened = listOf(listOf(1, 2), emptyList(), listOf(3)).flatten()
    if (flattened.toString() != "[1, 2, 3]") return "fail 5: flatten $flattened"
    val unzipped = listOf(1 to "one", 2 to "two").unzip()
    if (unzipped.first.toString() != "[1, 2]" || unzipped.second.toString() != "[one, two]") {
        return "fail 6: unzip $unzipped"
    }
    val pair = Pair(4, "four")
    val triple = Triple(5, "five", 5.0)
    if (pair.copy(first = 6).toString() != "(6, four)" || Pair(4, 5).toList().toString() != "[4, 5]" ||
        triple.copy(second = "FIVE").toString() != "(5, FIVE, 5.0)"
    ) {
        return "fail 7: tuple data classes"
    }

    var defaultCalls = 0
    val elementSource = CountingIterable(listOf("x", "y", "z"))
    if (elementSource.elementAt(1) != "y") return "fail 8: elementAt"
    if (elementSource.elementAtOrElse(-1) { index -> defaultCalls++; "n$index" } != "n-1") {
        return "fail 9: elementAtOrElse negative"
    }
    if (elementSource.elementAtOrElse(9) { index -> defaultCalls++; "n$index" } != "n9" || defaultCalls != 2) {
        return "fail 10: elementAtOrElse missing"
    }
    if (elementSource.elementAtOrNull(9) != null || listOf(10, 11).getOrElse(3) { 12 } != 12) {
        return "fail 11: optional indexed access"
    }

    val values = listOf<Int?>(null, 1, 2, null, 3, 4)
    if (values.filterNotNull().toString() != "[1, 2, 3, 4]") return "fail 12: filterNotNull"
    if (values.filter { it == null }.size != 2) return "fail 13: nullable filter"
    if (listOf(1, 2, 3, 4).filterIndexed { index, value -> index == 0 || value == 4 }.toString() != "[1, 4]") {
        return "fail 14: filterIndexed"
    }
    if (listOf(1, 2, 3, 4).filterNot { it % 2 == 0 }.toString() != "[1, 3]") {
        return "fail 15: filterNot"
    }
    val filteredDestination = arrayListOf(0)
    val filteredIdentity = listOf(1, 2, 3).filterTo(filteredDestination) { it >= 2 }
    if (filteredIdentity !== filteredDestination || filteredDestination.toString() != "[0, 2, 3]") {
        return "fail 16: filterTo identity"
    }

    val six = listOf(1, 2, 3, 4, 5, 6)
    if (six.drop(2).toString() != "[3, 4, 5, 6]" || six.take(2).toString() != "[1, 2]") {
        return "fail 17: drop/take"
    }
    if (six.dropLast(2).toString() != "[1, 2, 3, 4]" || six.takeLast(2).toString() != "[5, 6]") {
        return "fail 18: dropLast/takeLast"
    }
    if (six.dropWhile { it < 3 }.toString() != "[3, 4, 5, 6]" ||
        six.takeWhile { it < 4 }.toString() != "[1, 2, 3]" ||
        six.dropLastWhile { it > 4 }.toString() != "[1, 2, 3, 4]" ||
        six.takeLastWhile { it > 4 }.toString() != "[5, 6]"
    ) {
        return "fail 19: predicate drop/take"
    }
    try {
        six.take(-1)
        return "fail 20: negative take"
    } catch (_: IllegalArgumentException) {
    }

    if (six.map { it * 2 }.toString() != "[2, 4, 6, 8, 10, 12]") return "fail 21: map"
    if (six.mapIndexed { index, value -> index + value }.toString() != "[1, 3, 5, 7, 9, 11]") {
        return "fail 22: mapIndexed"
    }
    if (values.mapNotNull { it }.toString() != "[1, 2, 3, 4]" ||
        values.mapIndexedNotNull { index, value -> if (value == null) null else index + value }.toString() != "[2, 4, 7, 9]"
    ) {
        return "fail 23: not-null mapping"
    }
    if (listOf(1, 3).flatMap { listOf(it, -it) }.toString() != "[1, -1, 3, -3]" ||
        listOf(2, 4).flatMapIndexed { index, value -> listOf(index, value) }.toString() != "[0, 2, 1, 4]"
    ) {
        return "fail 24: flatMap"
    }
    val mapDestination = arrayListOf("seed")
    if (listOf(1, 2).mapTo(mapDestination) { "v$it" } !== mapDestination ||
        mapDestination.toString() != "[seed, v1, v2]"
    ) {
        return "fail 25: mapTo identity"
    }

    val snapshotSource = arrayListOf("a", "b")
    val readSnapshot = snapshotSource.toList()
    val mutableSnapshot = snapshotSource.toMutableList()
    snapshotSource[0] = "changed"
    if (readSnapshot.toString() != "[a, b]" || mutableSnapshot.toString() != "[a, b]") {
        return "fail 26: snapshot aliasing"
    }
    val collectionDestination = arrayListOf("start")
    if (listOf("x", "y").toCollection(collectionDestination) !== collectionDestination ||
        collectionDestination.toString() != "[start, x, y]"
    ) {
        return "fail 27: toCollection"
    }

    val booleans = listOf(true, false).toBooleanArray()
    val bytes: List<Byte> = listOf(1, -2)
    val shorts: List<Short> = listOf(3, -4)
    val ints = listOf(5, -6).toIntArray()
    val longs = listOf(7L, -8L).toLongArray()
    val floats = listOf(1.5f, -2.5f).toFloatArray()
    val doubles = listOf(3.5, -4.5).toDoubleArray()
    val chars = listOf('a', 'z').toCharArray()
    if (booleans.size != 2 || !booleans[0] || booleans[1] ||
        bytes.toByteArray()[1] != (-2).toByte() || shorts.toShortArray()[0] != 3.toShort() ||
        ints[0] != 5 || ints[1] != -6 || longs[1] != -8L ||
        floats[0] != 1.5f || doubles[1] != -4.5 || chars[1] != 'z'
    ) {
        return "fail 28: primitive snapshots"
    }

    if (listOf(1, 2, 3).runningFold(10) { acc, value -> acc + value }.toString() != "[10, 11, 13, 16]") {
        return "fail 29: runningFold"
    }
    if (listOf(2, 3).runningFoldIndexed(1) { index, acc, value -> acc + index + value }.toString() != "[1, 3, 7]") {
        return "fail 30: runningFoldIndexed"
    }
    if (listOf(2, 3, 4).runningReduce { acc, value -> acc * value }.toString() != "[2, 6, 24]" ||
        listOf(2, 3, 4).runningReduceIndexed { index, acc, value -> acc + index * value }.toString() != "[2, 5, 13]"
    ) {
        return "fail 31: runningReduce"
    }
    if (emptyList<Int>().runningReduce { acc, value -> acc + value }.isNotEmpty() ||
        listOf(1, 2).scan(0) { acc, value -> acc + value }.toString() != "[0, 1, 3]" ||
        listOf(1, 2).scanIndexed(0) { index, acc, value -> acc + index + value }.toString() != "[0, 1, 4]"
    ) {
        return "fail 32: empty running/scan aliases"
    }

    val reversible = arrayListOf<Int?>(1, null, 3, 4)
    reversible.reverse()
    if (reversible.toString() != "[4, 3, null, 1]" ||
        reversible.reversed().toString() != "[1, null, 3, 4]"
    ) {
        return "fail 33: reverse/reversed"
    }

    val partitioned = six.partition { it % 2 == 0 }
    if (partitioned.first.toString() != "[2, 4, 6]" || partitioned.second.toString() != "[1, 3, 5]") {
        return "fail 34: partition"
    }
    if ((listOf(1, 2) + 3).toString() != "[1, 2, 3]" ||
        (listOf(1) + arrayOf(2, 3)).toString() != "[1, 2, 3]" ||
        (listOf(1) + CountingIterable(listOf(2, 3))).toString() != "[1, 2, 3]"
    ) {
        return "fail 35: plus"
    }
    if ((listOf(1, 2, 1) - 1).toString() != "[2, 1]" ||
        (listOf(1, 2, 3, 2) - listOf(2, 3)).toString() != "[1]"
    ) {
        return "fail 36: minus"
    }

    val zipped = listOf(1, 2, 3).zip(listOf("a", "b"))
    if (zipped.toString() != "[(1, a), (2, b)]" ||
        listOf(1, 2).zip(arrayOf(10, 20, 30)) { left, right -> left + right }.toString() != "[11, 22]" ||
        listOf(2, 4, 8).zipWithNext().toString() != "[(2, 4), (4, 8)]" ||
        listOf(2, 4, 8).zipWithNext { left, right -> right - left }.toString() != "[2, 4]"
    ) {
        return "fail 37: zip"
    }

    val shortCircuit = CountingIterable(listOf(1, 2, 3, 4))
    if (shortCircuit.take(2).toString() != "[1, 2]" || shortCircuit.nextCalls != 2) {
        return "fail 38: take short-circuit ${shortCircuit.nextCalls}"
    }
    val destinationFailure = DestinationFailure()
    try {
        listOf(1, 2).mapTo(FailingDestination(destinationFailure)) { it * 2 }
        return "fail 39: destination failure"
    } catch (failure: DestinationFailure) {
        if (failure !== destinationFailure) return "fail 40: destination failure identity"
    }

    val mutable = arrayListOf(1, 2, 3)
    mutable += 4
    mutable += arrayOf(5, 6)
    mutable += CountingIterable(listOf(7, 8))
    mutable -= 2
    mutable -= arrayOf(5, 8)
    if (mutable.toString() != "[1, 3, 4, 6, 7]") return "fail 41: plus/minus assign $mutable"
    if (!mutable.removeAll(CountingIterable(listOf(3, 7))) || mutable.toString() != "[1, 4, 6]") {
        return "fail 42: removeAll Iterable"
    }
    if (!mutable.retainAll(arrayOf(1, 6)) || mutable.toString() != "[1, 6]") {
        return "fail 43: retainAll Array"
    }
    if (mutable.removeFirst() != 1 || mutable.removeLastOrNull() != 6 || mutable.removeFirstOrNull() != null) {
        return "fail 44: remove first/last"
    }

    val array = arrayOf<Any?>("x", 3, null)
    if (!array.contains(3) || array.indexOf(null) != 2 || array.indexOf("missing") != -1) {
        return "fail 45: generic array search"
    }
    if (earlyMap() != 42 || earlyFilter() != "escaped") return "fail 46: non-local returns"

    return "OK"
}
