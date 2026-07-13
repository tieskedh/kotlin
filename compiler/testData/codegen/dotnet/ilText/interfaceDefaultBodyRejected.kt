// Interface members with bodies are rejected at the shape gate, whole-interface — a
// backend-scope decision, NOT a platform one: CoreCLR supports Default Interface Methods
// (probe-verified, ifaceprobe_s8), but this backend has no DIM model yet. The rejection
// cascades at render to every implementing class and every sub-interface (their `implements`
// lines are re-resolved from the live class map each round, with chained reasons), so only the
// file facade survives here.
interface WithBody {
    fun f(): Int = 1
}

interface SubOfBody : WithBody

class ImplOfBody : WithBody {
    override fun f(): Int = 2
}

interface WithAccessorBody {
    val ok: Boolean get() = true
}

fun main() {
    println("rejected")
}
