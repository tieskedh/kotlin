// Generic top-level classes on the real CoreCLR (stage-1 generics, probe series genprobe):
// Box/Pair with mutable state, multiple instantiations of ONE class coexisting with different
// arguments (true reification — `Box`1<int32>` really stores a raw int32), the Int? hybrid
// instantiation (wrapped and empty flavors through `!0`-typed state), nested instantiation
// (`Box<Box<Int>>` drilled through two `get` hops), generic member dispatch through
// instantiated receivers, `.property` accessors typed `!0`, and a member body instantiating
// its OWN class with permuted type parameters (`Pair2<B, A>` — `class 'Pair2`2'<!1, !0>`).

class Box<T>(private var value: T) {
    fun get(): T = value

    fun put(v: T) {
        value = v
    }

    val item: T
        get() = value
}

class Pair2<A, B>(val first: A, val second: B) {
    fun swap(): Pair2<B, A> = Pair2<B, A>(second, first)
}

fun box(): String {
    val bs = Box("hello")
    if (bs.get() != "hello") return "fail 1: Box<String> get"
    bs.put("world")
    if (bs.item != "world") return "fail 2: Box<String> put/item"
    val bi = Box(1)
    bi.put(bi.get() + 41)
    if (bi.get() != 42) return "fail 3: Box<Int> arithmetic"
    if (bs.get() != "world") return "fail 4: instantiations coexist"
    val bn = Box<Int?>(null)
    if (bn.get() != null) return "fail 5: Box<Int?> empty"
    bn.put(8)
    val eight: Int? = bn.get()
    if (eight != 8) return "fail 6: Box<Int?> wrapped"
    val nested = Box(Box(5))
    if (nested.get().get() != 5) return "fail 7: Box<Box<Int>> get"
    nested.get().put(6)
    if (nested.get().get() != 6) return "fail 8: Box<Box<Int>> put"
    val p = Pair2(1, "one")
    if (p.first != 1) return "fail 9: Pair2 first"
    if (p.second != "one") return "fail 10: Pair2 second"
    val s = p.swap()
    if (s.first != "one") return "fail 11: swap first"
    if (s.second != 1) return "fail 12: swap second"
    return "OK"
}
