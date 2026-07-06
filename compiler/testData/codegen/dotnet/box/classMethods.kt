// Instance method dispatch (call instance) with state mutation across calls:
// the method reads and writes fields of its own receiver.

class Acc(val base: Int) {
    var total: Int = 0

    fun add(n: Int): Int {
        total = total + n + base
        return total
    }
}

fun box(): String {
    val a = Acc(1)
    a.add(10)
    a.add(20)
    if (a.total != 32) return "fail: " + a.total
    return "OK"
}
