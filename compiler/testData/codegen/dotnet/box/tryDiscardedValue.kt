// A non-Unit `try` discarded in statement position whose branch types LUB to `Any` — the
// routine shape of an Int-typed try branch next to a Unit-typed catch branch ending in an
// assignment. `Any` maps to CLR `object` under the hybrid nullability model, and a discarded
// try must NOT switch to value emission just because its type maps: the catch branch's
// trailing assignment is a statement that value emission cannot handle (regression pin for
// emitDiscardedTry's statement-form arm).
fun give(): Int = 41

fun boom(): Int = throw IllegalStateException("boom")

fun box(): String {
    var caught = false
    try {
        give()
    } catch (e: Exception) {
        caught = true
    }
    if (caught) return "fail: catch ran without a throw"
    try {
        boom()
    } catch (e: Exception) {
        caught = true
    }
    if (!caught) return "fail: catch did not run"
    var sum = 0
    try {
        sum = give() + 1
        give()
    } catch (e: Exception) {
        sum = -1
    }
    if (sum != 42) return "fail: sum was $sum"
    return "OK"
}
