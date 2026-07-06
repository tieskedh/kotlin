// Multiple catch clauses are emitted in source order and the CLR matches them strictly
// first-to-last (probe-verified): an IllegalArgumentException lands in the first, specific
// handler; an IllegalStateException falls through to the general Throwable handler; nothing
// thrown takes the normal path. Also exercises 'return try {...}' — a try expression whose
// reloaded result feeds the return.

fun classify(mode: Int): Int {
    return try {
        if (mode == 1) throw IllegalArgumentException("a")
        if (mode == 2) throw IllegalStateException("s")
        0
    } catch (e: IllegalArgumentException) {
        1
    } catch (e: Throwable) {
        2
    }
}

fun box(): String {
    if (classify(1) != 1) return "FAIL 1"
    if (classify(2) != 2) return "FAIL 2"
    if (classify(0) != 0) return "FAIL 3"
    return "OK"
}
