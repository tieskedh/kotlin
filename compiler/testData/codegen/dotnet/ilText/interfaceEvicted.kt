// Eviction cascades from an interface exactly like from a base class: `Bad` fails the member
// pre-pass (a `FloatArray` parameter has no IL mapping), `SubBad` — whose extended interface no
// longer exists — is evicted at render with a reason carrying Bad's reason, `Impl` fails on its
// own unmappable override, and `User` falls with the type-mapper cascade (its field type names
// an evicted class). No `implements` line may ever name an interface that was removed from the
// module; only the file facade survives.
interface Bad {
    fun f(x: FloatArray): FloatArray
}

interface SubBad : Bad

class Impl : SubBad {
    override fun f(x: FloatArray): FloatArray = x
}

class User {
    val held: Impl? = null
}

fun main() {
    println("evicted")
}
