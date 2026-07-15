// Non-capturing callable objects use Kotlin-owned, arity-erased interfaces in Kotlin.Runtime.
// Generated non-Unit callables also expose an optional typed capability on the same object;
// guarded calls use it when the static shape matches and otherwise use erased Invoke. Unit uses
// its singleton at the erased boundary, and one static field caches each source expression. The
// common Function marker, explicit implementations, and nullable storage share the identity ABI.

private class Doubler : (Int) -> Int {
    override fun invoke(value: Int): Int = value + value
}

fun increment(value: Int): Int = value + 1

fun mark(): Unit {
    println("unit")
}

fun cached(): () -> Int = { 7 }

fun <T> preserve(function: () -> T): () -> T = function

fun callOne(function: (Int) -> Int, value: Int): Int = function(value)

fun <T> callGeneric(function: (T) -> T, value: T): T = function(value)

fun main() {
    val zero: () -> Int = { 40 }
    val one: (Int) -> Int = { value -> value + 2 }
    val two: (Int, Int) -> Int = { left, right -> left + right }
    val reference: (Int) -> Int = ::increment
    val unit: () -> Unit = ::mark

    println(zero())
    println(one(40))
    println(two(20, 22))
    println(reference(41))
    unit()
    println(cached() === cached())

    val broad: (Any) -> String = { "variance" }
    val narrow: (String) -> Any = broad
    println(broad === narrow)
    println(narrow("reference variance"))

    val primitive: () -> Int = { 42 }
    val widened: () -> Any = primitive
    println(primitive === widened)

    val text: (String) -> String = { value -> value }
    println(text("reference cast"))

    val nullablePresent: () -> Int? = { 42 }
    val nullableAbsent: () -> Int? = { null }
    println(nullablePresent() ?: -1)
    println(nullableAbsent() == null)

    val boolean: (Boolean) -> Boolean = { value -> !value }
    println(boolean(false))

    val preserved = preserve(primitive)
    println(primitive === preserved)

    val marker: Function<Int> = primitive
    val starMarker: Function<*> = marker
    println(marker === primitive)
    println(starMarker === primitive)

    val extension: String.() -> String = { this }
    println(extension("extension receiver"))

    val implemented: (Int) -> Int = Doubler()
    println(implemented(21))
    println(callOne(one, 40))
    println(callGeneric({ value: String -> value }, "generic exact"))

    val nullableCallable: (() -> Int)? = null
    println(nullableCallable == null)
}
