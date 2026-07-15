// Captured values and bound receivers are private generated-class layout, not callable ABI
// alternatives. Every class below still implements only the arity-erased Kotlin.FunctionN
// identity; mutable locals share one runtime-internal generic cell.

private class Offset(private val delta: Int) {
    fun apply(value: Int): Int = value + delta
}

fun immutableCapture(value: Int): () -> Int = { value }

fun mutableCapture(start: Int): () -> Int {
    var value = start
    return {
        value = value + 1
        value
    }
}

fun unitMutableCapture(start: Int): (Int) -> Unit {
    var value = start
    return { next -> value = next }
}

fun <T> genericMutableCapture(initial: T, replacement: T): () -> T {
    var value = initial
    return {
        value = replacement
        value
    }
}

fun boundPrimitive(value: Int): () -> Int = value::inc

fun boundMember(delta: Int): (Int) -> Int = Offset(delta)::apply

fun main() {
    println(immutableCapture(42)())
    println(mutableCapture(41)())
    unitMutableCapture(0)(42)
    println(genericMutableCapture(0, 42)())
    println(boundPrimitive(41)())
    println(boundMember(2)(40))
}
