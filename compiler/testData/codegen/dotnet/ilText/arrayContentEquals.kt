fun sameInts(left: IntArray?, right: IntArray?): Boolean = left contentEquals right

fun sameDoubles(left: DoubleArray?, right: DoubleArray?): Boolean = left contentEquals right

fun sameStrings(left: Array<String>?, right: Array<String>?): Boolean = left contentEquals right

fun <T> sameGeneric(left: Array<T>?, right: Array<T>?): Boolean = left contentEquals right

fun main() {
    println(sameInts(intArrayOf(1, 2), intArrayOf(1, 2)))
}
