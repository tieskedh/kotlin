// Non-capturing callable objects use Kotlin-owned, arity-erased interfaces in Kotlin.Runtime.
// Invoke boxes its logical arguments/results through object slots, Unit uses its singleton there,
// and one static field caches each source callable expression. The common Function marker,
// extension receivers, explicit implementations, and nullable callable storage share that ABI.

private class Doubler : (Int) -> Int {
    override fun invoke(value: Int): Int = value + value
}

fun increment(value: Int): Int = value + 1

fun mark(): Unit {
    println("unit")
}

fun cached(): () -> Int = { 7 }

fun <T> preserve(function: () -> T): () -> T = function

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

    val nullableCallable: (() -> Int)? = null
    println(nullableCallable == null)
}
