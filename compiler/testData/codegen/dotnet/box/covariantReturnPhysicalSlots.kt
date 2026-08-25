// TARGET_BACKEND: DOTNET

private class PhysicalSingleIterator<T>(private val value: T) : Iterator<T> {
    private var consumed = false

    override fun hasNext(): Boolean = !consumed

    override fun next(): T {
        if (consumed) throw NoSuchElementException()
        consumed = true
        return value
    }
}

// FunctionN's exact Runtime capability is genuinely generic, but its canonical nested
// generic-owner result uses object so exact and semantic implementations share one construction.
// Keep the generated InvokeExact naturally typed and widen only its private Runtime MethodImpl.
private fun <T> nestedResultFactory(value: T): () -> Iterator<T> = {
    PhysicalSingleIterator(value)
}

// A nullable open parameter has one declaration-stable object return on the CLR. Substituting a
// concrete argument in the leaf must not retroactively narrow that inherited MethodDef; the leaf
// keeps its natural typed return and receives one private MethodImpl adapter.
private abstract class OpenNullableReturnBase<E> {
    protected abstract fun readNullable(): E?

    fun readThroughBase(): E? = readNullable()
}

private class OpenNullableReturnLeaf(
    private val value: String,
) : OpenNullableReturnBase<String>() {
    override fun readNullable(): String? = value
}

fun box(): String {
    val nested = nestedResultFactory("nested")()
    if (!nested.hasNext() || nested.next() != "nested" || nested.hasNext()) {
        return "fail: nested generic callable result"
    }

    val nullable = OpenNullableReturnLeaf("nullable")
    if (nullable.readThroughBase() != "nullable") return "fail: open nullable return"

    return "OK"
}
