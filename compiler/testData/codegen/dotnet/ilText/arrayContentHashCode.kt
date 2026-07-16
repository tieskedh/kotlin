fun shallowAnyHash(value: Array<Any?>?): Int = value.contentHashCode()

fun shallowIntHash(value: IntArray?): Int = value.contentHashCode()

fun deepAnyHash(value: Array<Any?>?): Int = value.contentDeepHashCode()

fun <T> genericHash(value: Array<T>?): Int = value.contentDeepHashCode()

fun main() {
    println(deepAnyHash(arrayOf<Any?>(intArrayOf(1, 2))))
}
