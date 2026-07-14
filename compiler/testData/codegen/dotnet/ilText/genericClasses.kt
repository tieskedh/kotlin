// Generic top-level classes, stage 1 (probe series genprobe_s2/_s3/_s4): real CLR reified
// generics, the Roslyn shape — `.class ... 'demo.Box`1'<'T'>` with the CLS arity suffix INSIDE
// the quoted identifier (outside the quotes is an ilasm syntax error, genprobe_s2c) — fields
// typed `!0`, ctor params typed `!0`, methods taking/returning `!0`, properties typed `!0` with
// `.property` blocks whose accessor references use the BARE class name (no type-args list).
// Member-reference operands always carry an instantiation: the CLOSED one at external call
// sites (`newobj instance void class 'Box`1'<string>::.ctor(!0)`), the OPEN self-instantiation
// (`class 'Box`1'<!0>`) inside the class's own bodies. Multiple instantiations of one class
// coexist (true reification: `Box`1<int32>` stores a raw int32, zero box/unbox), a Nullable
// instantiation composes with the landed hybrid spellings, and instantiations nest
// (`Box<Box<String>>` in every operand position, genprobe_s3).

class Box<T>(private var value: T) {
    fun get(): T = value

    fun put(v: T) {
        value = v
    }

    val item: T
        get() = value
}

class Pair2<A, B>(val first: A, val second: B)

fun main() {
    val bs = Box<String>("first")
    println(bs.get())
    bs.put("second")
    println(bs.item)
    val bi = Box<Int>(41)
    bi.put(bi.get() + 1)
    println(bi.get())
    val bn = Box<Int?>(7)
    println(bn.get() ?: 0)
    val nested = Box<Box<String>>(Box<String>("inner"))
    println(nested.get().get())
    val p = Pair2<Int, String>(1, "one")
    println(p.first)
    println(p.second)
}
