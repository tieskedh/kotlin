class Inner(val v: Int)

class Outer(val inner: Inner) {
    fun v(): Int = inner.v
}

fun make(i: Inner): Outer = Outer(i)

fun main() {
    val o = make(Inner(42))
    println(o.v())
}
