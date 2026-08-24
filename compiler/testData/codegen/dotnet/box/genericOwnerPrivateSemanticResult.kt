private abstract class PrivateIteratorScope<in T> {
    abstract fun install(iterator: Iterator<T>)
}

private class PrivateIterator<T> : Iterator<T> {
    private var available = true

    override fun hasNext(): Boolean = available

    override fun next(): T {
        available = false
        throw NoSuchElementException()
    }
}

// A private constructed-interface result intentionally has no capability-interface slot. Its
// caller is owner-independent, so an exact same-owner read must bind directly to the private
// semantic hook instead of passing through the checked typed wrapper or widening visibility.
private class PrivateIteratorOwner<T> : PrivateIteratorScope<T>() {
    private var iterator: Iterator<T>? = null

    override fun install(iterator: Iterator<T>) {
        this.iterator = iterator
    }

    fun hasNext(): Boolean = iterator!!.hasNext()
}

fun box(): String {
    val owner = PrivateIteratorOwner<Int>()
    owner.install(PrivateIterator())
    return if (owner.hasNext()) "OK" else "fail: private semantic-result hook"
}
