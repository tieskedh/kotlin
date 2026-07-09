// Companion initialization is tied to the ENCLOSING class: it is lazy, runs exactly once at the
// enclosing class's first active use — here constructing C(), which the CLR precedes with C's
// .cctor creating the companion (objprobe_s8 end-to-end) — and runs the companion's property
// initializers and init blocks interleaved in declaration order (they stay on the companion
// instance, executed by its constructor invoked from C's .cctor). Reading `log` first initializes
// only the file facade, never C or its companion.

var log = ""

fun mark(s: String, v: Int): Int {
    log = log + s
    return v
}

class C {
    val instanceState = mark("c", 3)

    companion object {
        val a = mark("a", 1)

        init {
            log = log + "i"
        }

        val b = mark("b", 2)
    }
}

fun box(): String {
    if (log != "") return "FAIL eager init: " + log
    val c = C()
    if (log != "aibc") return "FAIL init order: " + log
    if (C.a != 1) return "FAIL a: " + C.a
    if (C.b != 2) return "FAIL b: " + C.b
    if (c.instanceState != 3) return "FAIL instance state: " + c.instanceState
    C()
    if (log != "aibcc") return "FAIL companion re-init: " + log
    return "OK"
}
