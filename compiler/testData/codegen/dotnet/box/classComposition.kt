// Composition: a class-typed field, class-typed constructor parameter, and a
// member call chain through the composed instance.

class Inner(val v: Int)

class Outer(val inner: Inner) {
    fun get(): Int = inner.v
}

fun box(): String {
    val outer = Outer(Inner(42))
    if (outer.get() != 42) return "fail: " + outer.get()
    return "OK"
}
