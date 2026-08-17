private class CountingIterable<T>(private val values: Array<T>) : Iterable<T> {
    var iteratorCalls: Int = 0
    var nextCalls: Int = 0

    override fun iterator(): Iterator<T> {
        iteratorCalls++
        return CountingIterator(this, values)
    }

    private class CountingIterator<T>(
        private val owner: CountingIterable<T>,
        private val values: Array<T>,
    ) : Iterator<T> {
        private var index: Int = 0

        override fun hasNext(): Boolean = index < values.size

        override fun next(): T {
            owner.nextCalls++
            return values[index++]
        }
    }
}

private fun List<Int>.encoded(): Int {
    var result = 0
    for (value in this) result = result * 10 + value
    return result
}

fun box(): String {
    val randomAccess = arrayListOf(1, 2, 3, 4, 5)
    if (randomAccess !is RandomAccess) return "fail 1a: missing RandomAccess view"
    if (randomAccess.windowed(3, step = 2) != listOf(listOf(1, 2, 3), listOf(3, 4, 5))) {
        return "fail 1b: random-access snapshots"
    }
    if (randomAccess.windowed(3, step = 2, partialWindows = true) !=
        listOf(listOf(1, 2, 3), listOf(3, 4, 5), listOf(5))) {
        return "fail 1c: random-access partial windows"
    }
    if (randomAccess.windowed(2, step = 3, partialWindows = true) !=
        listOf(listOf(1, 2), listOf(4, 5))) {
        return "fail 1d: random-access gaps"
    }

    var randomAccessView: List<Int>? = null
    var reusedRandomAccessView = false
    val transformedRandomAccess = randomAccess.windowed(3, transform = { window ->
        if (randomAccessView === window) reusedRandomAccessView = true
        randomAccessView = window
        window.encoded()
    })
    if (transformedRandomAccess != listOf(123, 234, 345) || !reusedRandomAccessView) {
        return "fail 2: ephemeral RandomAccess transform view"
    }

    val counted = CountingIterable(arrayOf(1, 2, 3, 4, 5))
    val countedWindows = counted.windowed(3, step = 2, partialWindows = true)
    if (countedWindows != listOf(listOf(1, 2, 3), listOf(3, 4, 5), listOf(5))) {
        return "fail 3a: iterator snapshots"
    }
    if (counted.iteratorCalls != 1 || counted.nextCalls != 5) {
        return "fail 3b: iterator traversal ${counted.iteratorCalls}/${counted.nextCalls}"
    }

    val transformedCounted = CountingIterable(arrayOf(1, 2, 3, 4))
    var iteratorView: List<Int>? = null
    var reusedIteratorView = false
    val transformedIterator = transformedCounted.windowed(3, transform = { window ->
        if (iteratorView === window) reusedIteratorView = true
        iteratorView = window
        window.encoded()
    })
    if (transformedIterator != listOf(123, 234) || !reusedIteratorView) {
        return "fail 4a: ephemeral iterator transform view"
    }
    if (transformedCounted.iteratorCalls != 1 || transformedCounted.nextCalls != 4) {
        return "fail 4b: transformed iterator traversal"
    }

    if (emptyList<Int>().windowed(2).isNotEmpty()) return "fail 5a: empty windowed"
    if (emptyList<Int>().chunked(2).isNotEmpty()) return "fail 5b: empty chunked"
    if (counted.chunked(2) != listOf(listOf(1, 2), listOf(3, 4), listOf(5))) {
        return "fail 5c: chunked snapshots"
    }
    if (listOf(1, 2, 3, 4, 5).chunked(2) { it.encoded() } != listOf(12, 34, 5)) {
        return "fail 5d: transformed chunks"
    }

    val failure = IllegalStateException("window transform failure")
    val failing = CountingIterable(arrayOf(1, 2, 3, 4))
    var callbackCalls = 0
    try {
        failing.windowed(2, transform = { window ->
            callbackCalls++
            if (callbackCalls == 2) throw failure
            window.encoded()
        })
        return "fail 6a: missing callback failure"
    } catch (caught: IllegalStateException) {
        if (caught !== failure) return "fail 6b: callback failure identity"
    }
    if (callbackCalls != 2 || failing.nextCalls != 3) return "fail 6c: callback stopping point"

    try {
        listOf(1).windowed(0)
        return "fail 7a: missing invalid size failure"
    } catch (caught: IllegalArgumentException) {
        if (caught.message != "Both size 0 and step 1 must be greater than zero.") {
            return "fail 7b: invalid size message ${caught.message}"
        }
    }
    try {
        listOf(1).chunked(0)
        return "fail 7c: missing invalid chunk failure"
    } catch (caught: IllegalArgumentException) {
        if (caught.message != "size 0 must be greater than zero.") {
            return "fail 7d: invalid chunk message ${caught.message}"
        }
    }
    try {
        listOf(1).windowed(2, step = 0)
        return "fail 7e: missing invalid step failure"
    } catch (caught: IllegalArgumentException) {
        if (caught.message != "Both size 2 and step 0 must be greater than zero.") {
            return "fail 7f: invalid step message ${caught.message}"
        }
    }

    return "OK"
}
