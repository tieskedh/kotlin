// Generic top-level functions, stage 1 (probe series genprobe_s1/_s4/_s8/_s9): real CLR generic
// methods, the Roslyn shape — `.method ... !!0 'id'<'T'>(!!0 'x')` with the formal list between
// the name and the parameters — and call sites carrying the instantiation on the method token
// (`call !!0 '...'::'id'<string>(!!0)`: the signature slots stay OPEN per CLR member-ref rules,
// only the `<...>` list is substituted). Every mapped type-arg kind composes through the
// existing type mapper: string, int32, `valuetype [mscorlib]System.Nullable`1<int32>` (the
// hybrid model — CLR reification means the instantiation really carries the value type), a user
// class, a NESTED generic-class instantiation (`id<Box<String>>` — the type-arg list carries
// `class 'Box`1'<string>`), and `!!0` itself at a generic→generic pass-through call site
// (chain). A generic class also composes with a METHOD type parameter as its argument
// (genprobe_s9): `wrap` declares `class 'Box`1'<!!0>` as its return and `newobj`s
// `class 'Box`1'<!!0>::.ctor(!0)` inside the generic method's own body.

class Marker(val tag: Int)

class Box<T>(val v: T)

fun <T> id(x: T): T = x

fun <T> wrap(x: T): Box<T> = Box<T>(x)

fun <A, B> second(a: A, b: B): B = b

fun <T> chain(x: T): T = id<T>(x)

fun main() {
    val s: String = id<String>("hello")
    val i: Int = id<Int>(1)
    val n: Int? = id<Int?>(2)
    val m: Marker = id<Marker>(Marker(3))
    val b: Boolean = second<Int, Boolean>(1, true)
    val c: String = chain<String>("chained")
    val w: Box<Int> = wrap<Int>(7)
    val nested: Box<String> = id<Box<String>>(Box<String>("nested"))
    println(s)
    println(i)
    println(n ?: 0)
    println(m.tag)
    println(b)
    println(c)
    println(w.v)
    println(nested.v)
}
