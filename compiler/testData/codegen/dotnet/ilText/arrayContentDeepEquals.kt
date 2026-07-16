fun deepAny(left: Array<Any?>?, right: Array<Any?>?): Boolean = left contentDeepEquals right

fun deepStrings(left: Array<String>?, right: Array<String>?): Boolean = left contentDeepEquals right

fun <T> deepGeneric(left: Array<T>?, right: Array<T>?): Boolean = left contentDeepEquals right

fun main() {
    println(deepAny(arrayOf<Any?>(intArrayOf(1)), arrayOf<Any?>(intArrayOf(1))))
}
