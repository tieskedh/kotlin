// kotlin.Any/Any? as STORAGE maps to CLR `object` (params, returns, locals, fields —
// probe-verified positions, nullprobe_s8). Widenings to it: reference types for free,
// plain primitives via `box <boxed T>`, and Nullable<T> via `box Nullable<T>`, which the CLR
// COLLAPSES to boxed-T-or-null (Roslyn's model, probe-verified boxprobe_s3/nullprobe_s8) — so
// `x == null` on the boxed result is a plain reference test. `===` on Any?/reference operands
// is the type-agnostic reference `ceq`; string templates of nullable primitives render through
// a HasValue branch selecting "null" or the existing per-type rendering (boxprobe_s7).
val emptySlot: Any? = null

class Box(var payload: Any?)

fun stash(value: Any?): Any? = value

fun wrapNullable(x: Int?): Any? = x

fun wrapPlain(x: Int): Any? = x

fun same(a: Any?, b: Any?): Boolean = a === b

fun isNull(a: Any?): Boolean = a == null

fun template(x: Int?, d: Double?): String = "x=$x d=$d"

fun main() {
    println(isNull(emptySlot))
    println(isNull(wrapNullable(null)))
    println(isNull(wrapNullable(1)))
    println(isNull(wrapPlain(2)))
    val b = Box("p")
    println(isNull(b.payload))
    b.payload = 5
    println(isNull(b.payload))
    val s: Any? = stash("token")
    println(same(s, s))
    println(same(s, null))
    println(template(3, null))
    println(template(null, 2.5))
}
