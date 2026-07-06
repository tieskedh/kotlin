// A property with a custom getter body (no backing field): the accessor is a
// real instance method computing from another property.

class Sq(val n: Int) {
    val sq: Int
        get() = n * n
}

fun box(): String {
    val s = Sq(6)
    if (s.sq != 36) return "fail: " + s.sq
    return "OK"
}
