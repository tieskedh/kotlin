// Companion shapes outside the supported model are rejected with their immediate owner: the
// enclosing class contains the companion field and .cctor, so it cannot survive a failure OF the
// companion (the Marker interface itself is supported since the interface model and survives).
// A companion with a supertype other than kotlin.Any fails the same constraint chain as any class.
interface Marker

class WithMarkedCompanion {
    companion object : Marker
}

fun main() {
    println("rejected")
}
