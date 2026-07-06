// Runtime construction of a top-level final class with property parameters:
// newobj + primary-constructor field stores, then instance property reads.

class Point(val x: Int, var y: Int)

fun box(): String {
    val p = Point(3, 4)
    if (p.x != 3 || p.y != 4) return "fail: " + p.x + "," + p.y
    return "OK"
}
