// Object initialization is lazy and runs exactly once, at the first active use, in declaration
// order (property initializers and init blocks interleaved) — Kotlin/JVM first-active-use
// parity, pinned end-to-end: reading `log` initializes only the file facade, not the object
// (the object's .cctor must not run early); the bare `A` statement is the first active use.

var log = ""

fun mark(s: String, v: Int): Int {
    log = log + s
    return v
}

object A {
    val a = mark("a", 1)

    init {
        log = log + "i"
    }

    val b = mark("b", 2)
}

fun box(): String {
    if (log != "") return "FAIL eager init: " + log
    A
    if (log != "aib") return "FAIL init order: " + log
    if (A.a != 1) return "FAIL a: " + A.a
    if (A.b != 2) return "FAIL b: " + A.b
    if (log != "aib") return "FAIL second access appended: " + log
    return "OK"
}
