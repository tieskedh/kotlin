// Immutable local aliases retain enough IR provenance to try the generated callable object's
// original ExactFunctionN shape after a call-site-shaped probe fails. Parameter boundaries and
// explicit user implementations still require the erased FunctionN fallback.

private class UserDoubler : (Int) -> Int {
    override fun invoke(value: Int): Int = value + value
}

private fun increment(value: Int): Int = value + 1

private fun invokeAcrossBoundary(callable: (Int) -> Any, value: Int): Any = callable(value)

private fun invokeMutable(
    initial: (Int) -> Any,
    replacement: (Int) -> Any,
    value: Int,
): Any {
    var callable = initial
    callable = replacement
    return callable(value)
}

fun main() {
    val zero: () -> Int = { 42 }
    val widenedZero: () -> Any = zero
    println(widenedZero())

    val lambda: (Int) -> Int = { it + 1 }
    lambda(0)
    val widenedLambda: (Int) -> Any = lambda
    println(widenedLambda(41))
    val widenedAgain: (Int) -> Any = widenedLambda
    println(widenedAgain(41))
    val nullableResult: (Int) -> Int? = lambda
    println(nullableResult(41))

    val sum: (Int, Int) -> Int = { left, right -> left + right }
    val widenedSum: (Int, Int) -> Any = sum
    println(widenedSum(20, 22))

    val reference: (Int) -> Int = ::increment
    val widenedReference: (Int) -> Any = reference
    println(widenedReference(41))

    val acceptsAny: (Any) -> Int = { 42 }
    val acceptsInt: (Int) -> Int = acceptsAny
    println(acceptsInt(0))

    val user: (Int) -> Int = UserDoubler()
    val widenedUser: (Int) -> Any = user
    println(widenedUser(21))

    println(invokeAcrossBoundary(widenedLambda, 41))
    println(invokeMutable(widenedLambda, widenedUser, 21))
}
