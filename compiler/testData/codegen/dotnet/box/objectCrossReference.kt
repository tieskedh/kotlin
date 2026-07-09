// An acyclic object-to-object reference: touching A first runs A's .cctor, whose initializer
// reads B.y — the CLR fully initializes B before B.y is read, so A.x sees the final value and
// the marks land in dependency order ("b" before "a"). (The true A<->B cycle stays a
// probe-documented delta shared with the JVM and is deliberately not box-tested.)

var log = ""

fun mark(s: String, v: Int): Int {
    log = log + s
    return v
}

object B {
    val y = mark("b", 10)
}

object A {
    val x = mark("a", B.y + 1)
}

fun box(): String {
    if (log != "") return "FAIL eager init: " + log
    if (A.x != 11) return "FAIL x: " + A.x
    if (log != "ba") return "FAIL init order: " + log
    if (B.y != 10) return "FAIL y: " + B.y
    return "OK"
}
