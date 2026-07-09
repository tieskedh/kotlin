// Every object shape here is outside the supported model; each is skipped whole with a warning
// and only the file facade is emitted — never a silent partial class. A named object nested in
// a class rejects the enclosing class whole (the same gate that rejects nested classes); a data
// object needs the same Any model as a data class; an anonymous object expression is a local
// class, so the function using it is skipped.
data object D

class Outer {
    object Nested
}

fun localObject(): Int {
    val o = object { val x = 1 }
    return o.x
}

fun main() {
    println("rejected")
}
