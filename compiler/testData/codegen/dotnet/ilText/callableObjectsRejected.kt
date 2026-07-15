// Logical function type arguments erase from CLR signatures. As on the JVM, declarations that
// differ only in those arguments collide after erasure and must be rejected before IL emission.
// Suspend callables and arity above 2 remain outside this candidate slice; unrelated declarations
// survive independently.

fun consume(function: () -> Int): Int = function()

fun consume(function: () -> String): String = function()

fun identityValue(value: Int): Int = value

fun arityThree(): (Int, Int, Int) -> Int = { first, second, third -> first + second + third }

fun suspendCallable(): suspend () -> Int = { 42 }

fun survivor(): String = "OK"
