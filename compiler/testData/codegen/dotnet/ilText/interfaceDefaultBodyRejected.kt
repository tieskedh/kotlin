// Interface members with bodies are rejected at the shape gate, whole-interface — a
// runtime-floor decision: modern CoreCLR supports Default Interface Methods (ifaceprobe_s8 and
// dimprobe_s1), but .NET Framework 4.8 ILAsm rejects a non-static interface method body. The
// rejection cascades at render to every implementing class and every sub-interface (their
// `implements`
// lines are re-resolved from the live class map each round, with chained reasons), so only the
// file facade survives here.
interface WithBody {
    fun f(): Int = 1

    fun withDefault(value: Int = 2): Int = value
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
