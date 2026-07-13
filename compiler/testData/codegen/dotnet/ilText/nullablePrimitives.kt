// Concrete nullable primitives use the HYBRID representation (Roslyn precedent: C# `int?`):
// `valuetype [mscorlib]System.Nullable`1<T>` in every exact typed position — parameters,
// returns, locals, instance fields, static facade fields, constructor parameters (spellings
// probe-verified, boxprobe_s1). `T -> T?` is a `newobj Nullable<T>::.ctor(!0)` wrap; the null
// literal is an `initobj` through an addressed synthetic local; null tests and value
// extraction go through `get_HasValue`/`GetValueOrDefault` on a spilled home address (the
// mandatory-spill rule, boxprobe_s2). Safe calls and elvis are the frontend's block+when
// shape; `!!` is a HasValue branch + mapped-NPE throw on Nullable<T>, and on reference types
// the `dup`/`brtrue` past `newobj System.NullReferenceException::.ctor()` + `throw` spelling
// (boxprobe_s4, message-less like Intrinsics.checkNotNull); a `T? != null` smartcast unwraps
// with the same checked extraction (JVM precedent: CHECKCAST + intValue, NPE on null).
var lastSeen: Int? = null

class Holder(val limit: Int?, var current: Long?) {
    fun advance(step: Long?): Long {
        val next: Long? = step
        return next ?: 1L
    }
}

fun pick(flag: Boolean?, value: Double?): Double = if (flag ?: false) value ?: 0.0 else -1.0

fun firstOr(c: Char?): Char = c ?: '?'

fun added(x: Int?): Int {
    if (x != null) {
        return x + 1
    }
    return -1
}

fun force(x: Int?): Int = x!!

fun forceRef(s: String?): String = s!!

fun main() {
    lastSeen = 5
    println(lastSeen ?: -1)
    val h = Holder(3, null)
    println(h.advance(10L))
    println(h.limit ?: 0)
    h.current = 20L
    println(h.current ?: 0L)
    val maybeHolder: Holder? = h
    maybeHolder?.advance(null)
    println(pick(true, 2.5))
    println(firstOr(null))
    println(added(41))
    println(force(7))
    println(forceRef("r"))
}
