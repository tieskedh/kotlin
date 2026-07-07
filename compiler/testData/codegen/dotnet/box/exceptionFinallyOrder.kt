// Execution order around 'finally' on the real CoreCLR: the normal path runs
// try-finally-after, the caught path try-catch-finally-after (the CLR runs the finally
// automatically on the 'leave' out of the region), and a 'return' from inside a 'try' both
// preserves its already-evaluated value across the finally and observably runs the finally's
// side effect before the caller sees the value.

class Recorder {
    var log: String = ""

    fun add(part: String) {
        log = log + part
    }
}

fun normalPath(r: Recorder) {
    try {
        r.add("t,")
    } finally {
        r.add("f,")
    }
    r.add("a")
}

fun caughtPath(r: Recorder) {
    try {
        r.add("t,")
        throw IllegalStateException("x")
    } catch (e: IllegalStateException) {
        r.add("c,")
    } finally {
        r.add("f,")
    }
    r.add("a")
}

fun returnAcross(r: Recorder): Int {
    try {
        return 7
    } finally {
        r.add("rf")
    }
}

fun box(): String {
    val r1 = Recorder()
    normalPath(r1)
    if (r1.log != "t,f,a") return "FAIL normal: " + r1.log
    val r2 = Recorder()
    caughtPath(r2)
    if (r2.log != "t,c,f,a") return "FAIL caught: " + r2.log
    val r3 = Recorder()
    if (returnAcross(r3) != 7) return "FAIL return value"
    if (r3.log != "rf") return "FAIL return finally: " + r3.log
    return "OK"
}
