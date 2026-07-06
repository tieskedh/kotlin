// Reference identity (===/!==) on class instances: distinct instances with
// equal state are not identical; an aliased reference is.

class Point(val x: Int, var y: Int)

fun box(): String {
    val a = Point(1, 1)
    val b = Point(1, 1)
    val c = a
    if (!(a === c)) return "fail 1: a !== c"
    if (a === b) return "fail 2: a === b"
    if (!(a !== b)) return "fail 3: !(a !== b)"
    return "OK"
}
