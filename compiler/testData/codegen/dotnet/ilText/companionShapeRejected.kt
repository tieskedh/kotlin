// Companion shapes outside the supported model are rejected whole-family: the enclosing class and
// its companion are linked availableClasses entries, so a failing companion takes the enclosing
// class down with it (and vice versa) and only the file facade is emitted — never a partial family
// (the Marker interface itself is supported since the interface model and survives alone).
// A companion with a supertype other than kotlin.Any fails the same constraint chain as any class;
// a declaration inside the companion fails because ordinary nested classes are supported only
// inside plain classes; a named object nested in an object stays rejected.
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
