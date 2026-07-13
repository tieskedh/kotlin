// A base-class member satisfying an interface of a derived class is supported ONLY when the
// inherited member is virtual (ifaceprobe_s5a); a NON-virtual inherited member assembles
// cleanly but load-poisons the type — every use throws TypeLoadException at JIT time
// (ifaceprobe_s5b) — so the shape gate rejects `Combined` whole-class at compile time.
// `Able` and `Provider` themselves are fine and survive.
interface Able {
    fun f(): Int
}

open class Provider {
    fun f(): Int = 42
}

class Combined : Provider(), Able

fun main() {
    println("rejected")
}
