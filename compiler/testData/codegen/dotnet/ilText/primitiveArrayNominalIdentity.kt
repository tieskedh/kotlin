fun kind(value: IntArray): Int = value.size

fun kind(value: Array<Int>): Int = value.size + 10

fun specialized(): IntArray = intArrayOf(1, 2)

fun generic(): Array<Int> = arrayOf(1, 2)

fun main() {
    println("primitive-array-nominal-identity")
}
