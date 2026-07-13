// The hybrid representation makes Int? and Int DISTINCT IL types while String? and String stay
// the SAME `string` — three gate interactions pinned here:
// - `Narrowed`, whose `override fun value(): Int` covariantly narrows `Producer.value(): Int?`
//   (legal Kotlin), maps to a DIFFERENT IL return (int32 vs Nullable<int32>) and is evicted
//   whole-class by the covariant-return gate (ECMA-335 slot matching includes the return type);
// - `Overloads.f(Int)` / `f(Int?)` map to two DISTINCT legal IL methods — no identity clash;
// - `StringClash.g(String)` / `g(String?)` still collide on one IL identity `g(string)` and the
//   class stays evicted (reference nullability erases in IL, unchanged).
open class Producer {
    open fun value(): Int? = null
}

class Narrowed : Producer() {
    override fun value(): Int = 1
}

class Overloads {
    fun f(x: Int): Int = x
    fun f(x: Int?): Int = x ?: -1
}

class StringClash {
    fun g(x: String): String = x
    fun g(x: String?): String = x ?: ""
}

fun main() {
    println(Producer().value() ?: -5)
    val o = Overloads()
    println(o.f(2))
    println(o.f(null))
}
