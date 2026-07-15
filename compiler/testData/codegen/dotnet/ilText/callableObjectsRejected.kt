// Logical function type arguments erase from CLR signatures. As on the JVM, declarations that
// differ only in those arguments collide after erasure and must be rejected before IL emission.
// Captures, bound references, suspend callables, arity above 2, and inferred KFunction storage
// also remain outside this first candidate slice; unrelated declarations survive independently.

fun consume(function: () -> Int): Int = function()

fun consume(function: () -> String): String = function()

fun identityValue(value: Int): Int = value

fun capturing(value: Int): () -> Int = { value }

fun bound(value: Int): () -> Int = value::inc

fun arityThree(): (Int, Int, Int) -> Int = { first, second, third -> first + second + third }

fun suspendCallable(): suspend () -> Int = { 42 }

fun reflected() = ::identityValue

fun survivor(): String = "OK"
