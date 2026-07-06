class Box(val v: Int)

fun main() {
    val a = Box(1)
    val b = Box(1)
    val c = a
    println(a === c)
    println(a === b)
    println(a !== b)
}
