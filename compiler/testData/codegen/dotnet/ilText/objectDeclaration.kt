// A plain top-level object: a public static initonly INSTANCE field of the object's own type,
// a private .ctor (no other .NET code can mint a second instance), a .cctor doing newobj/stsfld
// (so beforefieldinit is dropped — first-active-use parity), and every use site loading the
// singleton with a bare ldsfld before the plain instance call.
object Counter {
    val base = 10
    var count = 0

    fun next(): Int {
        count = count + 1
        return base + count
    }
}

fun main() {
    println(Counter.next())
    println(Counter.count)
}
