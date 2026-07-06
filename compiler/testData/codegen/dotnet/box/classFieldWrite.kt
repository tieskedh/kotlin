// Mutable property writes through the setter (stfld via set_y) and
// read-modify-write on the same instance.

class Point(val x: Int, var y: Int)

fun box(): String {
    val p = Point(1, 2)
    p.y = 7
    p.y = p.y + 35
    if (p.y != 42) return "fail: " + p.y
    return "OK"
}
