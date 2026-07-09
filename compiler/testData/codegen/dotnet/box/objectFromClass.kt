// An object used from a class method, passed as a parameter and returned from functions;
// the singleton identity is preserved across every path.

object Registry {
    var value = 1

    fun bump(): Int {
        value = value + 1
        return value
    }
}

class Client {
    fun read(): Int = Registry.value
    fun same(r: Registry): Boolean = r === Registry
}

fun give(): Registry = Registry

fun take(r: Registry): Int = r.bump()

fun box(): String {
    val c = Client()
    if (c.read() != 1) return "FAIL read: " + c.read()
    if (take(give()) != 2) return "FAIL take: " + Registry.value
    if (!c.same(give())) return "FAIL identity"
    return "OK"
}
