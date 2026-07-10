// Eviction cascades down the inheritance chain: `Bad` fails the member pre-pass (a `Float`
// parameter has no IL mapping), so `Mid` — whose base no longer exists — is evicted at render
// with a reason carrying Bad's reason, and `Leaf` follows in the next fixpoint round with a
// reason carrying Mid's. Only the file facade is emitted; no `extends` line may ever name a
// class that was removed from the module.
open class Bad {
    fun f(x: Float): Float = x
}

open class Mid(val x: Int) : Bad()

class Leaf : Mid(1)

fun main() {
    println("cascade")
}
