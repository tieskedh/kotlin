// Companion shapes outside the supported model are rejected with their immediate owner: the
// enclosing class contains the companion field and .cctor, so it cannot survive a failure OF the
// companion (the Marker interface itself is supported since the interface model and survives).
// A companion with a supertype other than kotlin.Any fails the same constraint chain as any class;
// a rejected declaration inside an otherwise valid companion is instead its own metadata subtree,
// so WithNestedInCompanion survives without Inner. O likewise survives without its rejected child.
interface Marker

class WithMarkedCompanion {
    companion object : Marker
}

class WithNestedInCompanion {
    companion object {
        object Inner
    }
}

object O {
    object Nested
}

fun main() {
    println("rejected")
}
