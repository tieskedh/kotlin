// A named nested data class follows Kotlin/JVM static-nested semantics: it captures neither an
// outer instance nor the generic outer's type parameter. Its generated equality test/downcast
// therefore names the independently non-generic CLR metadata type, and copy defaults use the
// same instance helper as a top-level data class.
class GenericDataOwner<T> {
    data class Entry(val number: Int, val label: String?)
}

fun sameNested(left: GenericDataOwner.Entry, right: Any?): Boolean = left == right

fun copyNested(value: GenericDataOwner.Entry): GenericDataOwner.Entry = value.copy(number = 9)

fun main() {
    val value = GenericDataOwner.Entry(7, "nested")
    println(value)
    println(sameNested(value, GenericDataOwner.Entry(7, "nested")))
    println(value.component1())
    println(value.component2())
    println(copyNested(value))
}
