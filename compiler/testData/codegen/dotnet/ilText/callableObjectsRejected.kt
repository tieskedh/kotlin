// Logical function type arguments erase from CLR signatures. As on the JVM, declarations that
// differ only in those arguments collide after erasure and must be rejected before IL emission.
// Arity above 3 remains outside this candidate slice. Suspend callables now compose with the
// coroutine lowering and survive independently alongside the rejected erased overload pair.

fun consume(function: () -> Int): Int = function()

fun consume(function: () -> String): String = function()

fun identityValue(value: Int): Int = value

fun arityFour(): (Int, Int, Int, Int) -> Int = { first, second, third, fourth ->
    first + second + third + fourth
}

fun suspendCallable(): suspend () -> Int = { 42 }

fun survivor(): String = "OK"
