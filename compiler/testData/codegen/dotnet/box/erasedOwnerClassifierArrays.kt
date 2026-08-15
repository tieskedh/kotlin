private open class StableNode<T>(val value: T)

private class StableChild<T>(value: T) : StableNode<T>(value)

private class StableNodeArray<T> {
    private val nodes = arrayOfNulls<StableNode<T>>(2)

    fun roundTrip(first: T, second: T): String {
        nodes[0] = StableNode(first)
        nodes[1] = StableChild(second)
        return "${nodes[0]!!.value}:${nodes[1]!!.value}:${nodes.size}"
    }
}

fun box(): String {
    if (StableNodeArray<Int>().roundTrip(1, 2) != "1:2:2") return "fail 1: value substitution"
    if (StableNodeArray<String>().roundTrip("a", "b") != "a:b:2") return "fail 2: reference substitution"
    return "OK"
}
