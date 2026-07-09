// A companion object end-to-end: reading a val, writing and reading a var, calling a member
// function — from inside the enclosing class by bare name, from a static context (a top-level
// function), and from outside via both C.Companion.x and the C.x shorthand; state mutated through
// one access path is visible through every other (all paths load the same singleton field on C),
// and reference identity (===) holds through a function round trip.

class C {
    fun viaInstance(): Int = base + count

    companion object {
        val base = 10
        var count = 0

        fun next(): Int {
            count = count + 1
            return base + count
        }
    }
}

fun grab(): C.Companion = C.Companion

fun box(): String {
    if (C.Companion.base != 10) return "FAIL base: " + C.Companion.base
    if (C.base != 10) return "FAIL shorthand base: " + C.base
    if (C.next() != 11) return "FAIL next: " + C.count
    C.count = 5
    if (C.Companion.count != 5) return "FAIL count write: " + C.Companion.count
    if (C().viaInstance() != 15) return "FAIL instance access: " + C().viaInstance()
    if (!(grab() === C.Companion)) return "FAIL identity"
    return "OK"
}
