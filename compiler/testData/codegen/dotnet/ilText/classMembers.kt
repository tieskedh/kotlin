class Point(val x: Int, var y: Int) {
    fun translate(dx: Int): Int = x + dx
}

fun main() {
    val p = Point(1, 2)
    println(p.translate(3))
    p.y = 5
    println(p.y)
}
