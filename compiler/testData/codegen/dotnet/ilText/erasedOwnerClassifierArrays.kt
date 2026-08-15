package test.erasedownerclassifierarrays

open class StableNode<T>(val value: T)

class StableChild<T>(value: T) : StableNode<T>(value)

class StableNodeArray<T> {
    val nodes = arrayOfNulls<StableNode<T>>(2)

    fun set(index: Int, value: T) {
        nodes[index] = StableChild(value)
    }
}

fun main() {
}
