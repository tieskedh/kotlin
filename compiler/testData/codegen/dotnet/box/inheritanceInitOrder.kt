// Initialization order across the inheritance chain: the derived constructor chains to the
// base constructor FIRST, so base `init` blocks and field initializers run before the derived
// ones (Kotlin/JVM parity; the CLR runs the chained `.ctor` call exactly where it is emitted —
// probe-verified, inheritprobe_s1: the body code after the chain executes after the base body).
var log: String = ""

open class Base {
    init {
        log = log + "B"
    }
}

class Derived : Base() {
    init {
        log = log + "D"
    }
}

fun box(): String {
    if (log != "") return "fail: log written before any construction"
    Derived()
    if (log != "BD") return "fail: base init must run before derived init, got: " + log
    Base()
    if (log != "BDB") return "fail: base-only construction, got: " + log
    Derived()
    if (log != "BDBBD") return "fail: second derived construction, got: " + log
    return "OK"
}
