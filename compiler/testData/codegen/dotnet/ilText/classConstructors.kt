class Point(val x: Int, var y: Int) {
    val tag: Int = 7

    init {
        println("init")
    }

    constructor(x: Int) : this(x, 0)
}

fun main() {
    val p = Point(1, 2)
    Point(5)
}
