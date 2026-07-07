// Top-level property initializers run in declaration order inside the facade's `.cctor`, before
// the first active use of the facade (box() is a static method of the same non-beforefieldinit
// facade, so the CLR runs the `.cctor` before its body) — Kotlin/JVM first-active-use parity.

var log = ""

fun mark(s: String, v: Int): Int {
    log = log + s
    return v
}

val a = mark("a", 1)
var b = mark("b", 2)

fun box(): String {
    if (log != "ab") return "FAIL init order: " + log
    if (a != 1) return "FAIL a: " + a
    if (b != 2) return "FAIL b: " + b
    b = 5
    if (b != 5) return "FAIL b write: " + b
    return "OK"
}
