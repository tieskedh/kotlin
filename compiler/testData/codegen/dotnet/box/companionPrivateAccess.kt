// The companion visibility rule end-to-end: a Kotlin-private companion member used from
// enclosing-class code must work at runtime — the CLR grants NO enclosing->nested private access
// (objprobe_s7b), so such members are emitted with IL 'assembly' visibility (objprobe_s7c) — and
// the reverse direction needs nothing: companion code reaching a Kotlin-private member of the
// enclosing class (here C's private constructor, newobj'd from the companion factory) relies on
// the nested->enclosing private access the CLR does grant (objprobe_s7a). The rule is uniform
// over member kinds, so the enclosing class exercises every slice across the boundary: the
// private METHOD stamp() (via stamped()), and the private PROPERTY stamps through both of its
// 'assembly' accessors — readStamps() calls the getter, resetStamps() the setter. Emitting the
// accessors as IL 'private' would throw MethodAccessException on those calls.

class C private constructor(val tag: Int) {
    fun stamped(): Int = stamp()

    fun readStamps(): Int = stamps

    fun resetStamps() {
        stamps = 0
    }

    companion object {
        private var stamps = 0

        private fun stamp(): Int {
            stamps = stamps + 1
            return stamps
        }

        fun create(tag: Int): C = C(tag)

        fun total(): Int = stamps
    }
}

fun box(): String {
    val c = C.create(41)
    if (c.tag != 41) return "FAIL tag: " + c.tag
    if (c.stamped() != 1) return "FAIL first stamp: " + C.total()
    if (c.stamped() != 2) return "FAIL second stamp: " + C.total()
    if (C.total() != 2) return "FAIL total: " + C.total()
    if (c.readStamps() != 2) return "FAIL private getter from enclosing class: " + c.readStamps()
    c.resetStamps()
    if (C.total() != 0) return "FAIL private setter from enclosing class: " + C.total()
    return "OK"
}
