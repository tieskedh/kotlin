// Null-aware structural `==` over the Nullable<T> representation, no boxing (JVM precedent:
// the Intrinsics.areEqual specializations; IL shape: Roslyn's lifted equality, probe-verified
// including the (none, some(0)) corner — boxprobe_s5): `T? == T?` compares
// GetValueOrDefault values ANDed with the HasValue flags; the mixed `T? == T` / `T == T?`
// shapes AND the value comparison with the nullable side's HasValue; `T? == null` is a negated
// HasValue. `Double? == Double?` arrives through the ieee754equals symbol and gets the same
// shape — `ceq` on the extracted float64 values IS the IEEE semantics of the JVM's
// areEqual(Double, Double) specialization.
fun eqNN(a: Int?, b: Int?): Boolean = a == b

fun eqNP(a: Int?, b: Int): Boolean = a == b

fun eqPN(a: Int, b: Int?): Boolean = a == b

fun eqNull(a: Int?): Boolean = a == null

fun neNull(a: Int?): Boolean = null != a

fun eqLong(a: Long?, b: Long?): Boolean = a == b

fun eqDouble(a: Double?, b: Double?): Boolean = a == b

fun eqBool(a: Boolean?, b: Boolean): Boolean = a == b

fun eqChar(a: Char?, b: Char?): Boolean = a == b

fun main() {
    println(eqNN(1, 1))
    println(eqNN(null, 0))
    println(eqNP(5, 5))
    println(eqPN(5, null))
    println(eqNull(null))
    println(neNull(3))
    println(eqLong(7L, 7L))
    println(eqDouble(2.5, 2.5))
    println(eqBool(null, false))
    println(eqChar('a', 'a'))
}
