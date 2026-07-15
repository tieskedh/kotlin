// KFunction is an orthogonal reflection view on the same erased FunctionN callable object.
// KCallable.name is the first metadata member; invocation retains the FunctionN identity while
// using the same optional exact capability and erased fallback as ordinary function values.

import kotlin.reflect.KFunction1

private class BoundValue(private val value: Int) {
    fun read(): Int = value
}

fun incrementReference(value: Int): Int = value + 1

fun labelReference(ignored: Any): String = "label"

fun reflectedReference() = ::incrementReference

fun referenceName(reference: KFunction1<Int, Int>): String = reference.name

fun callReference(reference: KFunction1<Int, Int>, value: Int): Int = reference(value)

fun main() {
    val reference = reflectedReference()
    println(referenceName(reference))
    println(callReference(reference, 41))

    val callable: (Int) -> Int = reference
    println(reference === callable)
    println(reflectedReference() === reflectedReference())

    val narrow: KFunction1<Any, String> = ::labelReference
    val wide: KFunction1<Int, Any> = narrow
    println(narrow === wide)

    val bound = BoundValue(42)::read
    println(bound.name)
    println(bound())
    println(bound !== BoundValue(42)::read)
}
