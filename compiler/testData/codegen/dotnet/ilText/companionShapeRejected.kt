// Companion shapes outside the supported model are rejected with their immediate owner: the
// enclosing class contains the companion field and .cctor, so it cannot survive a failed
// companion (the Marker interface itself is supported since the interface model and survives).
// A companion with a supertype other than kotlin.Any fails the same constraint chain as any class;
// a declaration inside the companion fails because ordinary nested classes are supported only
// inside plain classes. O is independent of its rejected named child and survives as an object.
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
