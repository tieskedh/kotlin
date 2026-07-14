// Generic top-level functions on the real CoreCLR (stage-1 generics, probe series genprobe):
// identity/pair-style plumbing through every mapped type-arg kind — string, int32, int64,
// float64, bool, char, Int? (the Nullable hybrid: both the wrapped and the empty flavor
// round-trip through !!0), a user class, a nested generic-class instantiation
// (`id<Box<String>>`) — plus multiple type parameters, a generic function calling another
// generic function passing T through (`<!!0>` at the inner call site), and a generic class
// instantiated with the METHOD type parameter (`wrap` returning `class 'Box`1'<!!0>`,
// genprobe_s9).

class Marker(val tag: Int)

class Box<T>(val v: T)

fun <T> id(x: T): T = x

fun <T> wrap(x: T): Box<T> = Box<T>(x)

fun <A, B> pairSecond(a: A, b: B): B = b

fun <T> chain(x: T): T = id<T>(x)

fun box(): String {
    if (id("s") != "s") return "fail 1: id(String)"
    if (id(42) != 42) return "fail 2: id(Int)"
    if (id(10L) != 10L) return "fail 3: id(Long)"
    if (id(1.5) != 1.5) return "fail 4: id(Double)"
    if (!id(true)) return "fail 5: id(Boolean)"
    if (id('x') != 'x') return "fail 6: id(Char)"
    val some: Int? = id<Int?>(7)
    if (some != 7) return "fail 7: id(Int?) some"
    val none: Int? = id<Int?>(null)
    if (none != null) return "fail 8: id(Int?) none"
    val m = id(Marker(9))
    if (m.tag != 9) return "fail 9: id(Marker)"
    if (pairSecond("a", 5) != 5) return "fail 10: pairSecond"
    if (pairSecond(5, "b") != "b") return "fail 11: pairSecond swapped"
    if (chain("c") != "c") return "fail 12: chain(String)"
    if (chain(3) != 3) return "fail 13: chain(Int)"
    val chainedNone: Int? = chain<Int?>(null)
    if (chainedNone != null) return "fail 14: chain(Int?) none"
    if (wrap(7).v != 7) return "fail 15: wrap(Int) — Box<!!0> composition"
    if (wrap("w").v != "w") return "fail 16: wrap(String) — Box<!!0> composition"
    val nested: Box<String> = id<Box<String>>(Box<String>("n"))
    if (nested.v != "n") return "fail 17: id(Box<String>) nested instantiation type-arg"
    return "OK"
}
