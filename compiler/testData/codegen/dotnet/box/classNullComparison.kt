// `==`/`!=` against the null literal on class-typed values is a reference check that never
// calls equals, in both operand orders. Comparisons live in helper functions so `box` never
// re-reads a checked value (smart-cast IMPLICIT_CASTs are not supported by this backend yet).

class Node(val value: Int)

fun nodeOrNull(present: Boolean): Node? = if (present) Node(42) else null

fun eqNull(n: Node?): Boolean = n == null
fun nullEq(n: Node?): Boolean = null == n
fun neqNull(n: Node?): Boolean = n != null
fun nullNeq(n: Node?): Boolean = null != n

fun box(): String {
    val present = nodeOrNull(true)
    val absent = nodeOrNull(false)
    if (eqNull(present)) return "fail 1: present == null"
    if (!nullEq(absent)) return "fail 2: !(null == absent)"
    if (neqNull(absent)) return "fail 3: absent != null"
    if (!nullNeq(present)) return "fail 4: !(null != present)"
    return "OK"
}
