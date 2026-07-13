// Companion shapes outside the supported model are rejected whole-PAIR: the enclosing class and
// its companion are linked availableClasses entries, so a failing companion takes the enclosing
// class down with it (and vice versa) and only the file facade is emitted — never a partial pair
// (the Marker interface itself is supported since the interface model and survives alone).
// A companion with a supertype other than kotlin.Any fails the same constraint chain as any class;
// a nested class inside the companion fails the same nested gate as anywhere else; a named object
// nested in an object stays rejected (only the companion is a supported nested shape).
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
