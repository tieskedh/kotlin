// A plain top-level object end-to-end: reading a val, writing and reading a var, calling a
// member function, and reference identity (===) through a function returning the object.

object Counter {
    val base = 10
    var count = 0

    fun next(): Int {
        count = count + 1
        return base + count
    }
}

fun grab(): Counter = Counter

fun box(): String {
    if (Counter.base != 10) return "FAIL base: " + Counter.base
    if (Counter.next() != 11) return "FAIL next: " + Counter.count
    Counter.count = 5
    if (Counter.count != 5) return "FAIL count write: " + Counter.count
    if (!(grab() === Counter)) return "FAIL identity"
    return "OK"
}
