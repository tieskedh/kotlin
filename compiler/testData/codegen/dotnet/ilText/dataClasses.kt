// Non-generic data classes reuse System.Object's Equals/GetHashCode/ToString slots. The
// frontend-generated bodies use a CLR type test plus checked downcast, the Any runtime helpers,
// ordinary property fields, and the existing string-concatenation lowering. componentN and copy
// remain ordinary instance methods; this first shape calls copy with every argument explicitly.
data class Point(val x: Int, val label: String?, val rank: Int?)

fun same(left: Point, right: Any?): Boolean = left == right

fun duplicate(value: Point): Point = value.copy(x = 9, rank = null)

fun main() {
    val value = Point(7, "p", 3)
    println(value)
    println(value.hashCode())
    println(same(value, Point(7, "p", 3)))
    println(value.component1())
    println(value.component2())
    println(value.component3())
    println(duplicate(value))
}
