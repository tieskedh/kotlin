// `x == null` / `null != x` on a class-typed operand is a pure reference check (Kotlin never
// calls `equals` for a null-literal comparison), compiled to `ldnull`/`ceq` like `===`.
class Handle(val id: Int)

fun describe(h: Handle?): String = if (h == null) "missing" else "present"

fun main() {
    println(describe(null))
    println(describe(Handle(1)))
    val h: Handle? = Handle(2)
    println(h != null)
    println(null == h)
}
