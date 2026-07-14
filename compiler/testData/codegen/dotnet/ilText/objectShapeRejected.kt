// Every object shape here is outside the supported model and is skipped with a warning. A data
// object needs the same Any model as a data class; an anonymous object expression is a local class,
// so the function using it is skipped. Named objects nested in non-generic plain classes are
// supported by the recursive nested-singleton model and are covered separately.
data object D

fun localObject(): Int {
    val o = object { val x = 1 }
    return o.x
}

fun main() {
    println("rejected")
}
