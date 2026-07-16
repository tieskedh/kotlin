fun shallowAnyString(value: Array<Any?>?): String = value.contentToString()

fun shallowIntString(value: IntArray?): String = value.contentToString()

fun deepAnyString(value: Array<Any?>?): String = value.contentDeepToString()

fun <T> genericString(value: Array<T>?): String = value.contentDeepToString()

fun main() {
    println(deepAnyString(arrayOf<Any?>(intArrayOf(1, 2))))
}
