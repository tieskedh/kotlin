// Fixed Function0/1/2 objects on CoreCLR: optional exact invocation with erased fallback,
// function-typed plumbing, direct top-level references, Unit materialization, singleton reuse,
// Kotlin variance across both reference and primitive slots, open generic type arguments, the
// Function marker, extension receivers, explicit implementations, nullable callable storage,
// immutable/mutable captures, and bound references. KFunction reflection, suspend callables, and
// delegate adapters remain separate.

private var unitCalls: Int = 0
private var callableEvaluationOrder: String = ""

private class Doubler : (Int) -> Int {
    override fun invoke(value: Int): Int = value + value
}

private class Offset(private val delta: Int) {
    fun apply(value: Int): Int = value + delta
}

fun increment(value: Int): Int = value + 1

fun mark(): Unit {
    unitCalls = unitCalls + 1
}

fun cached(): () -> Int = { 7 }

fun captured(value: Int): () -> Int = { value }

fun capturedPair(left: Int, right: Int): () -> Int = { left + right }

fun mutableCounter(start: Int): () -> Int {
    var value = start
    return {
        value = value + 1
        value
    }
}

fun boundIncrement(value: Int): () -> Int = value::inc

fun boundOffset(delta: Int): (Int) -> Int = Offset(delta)::apply

fun <T> genericMutable(initial: T, replacement: T): () -> T {
    var value = initial
    return {
        value = replacement
        value
    }
}

fun call0(function: () -> Int): Int = function()

fun call1(function: (Int) -> Int, value: Int): Int = function(value)

fun call2(function: (Int, Int) -> Int, left: Int, right: Int): Int = function(left, right)

fun <T> applyExact(value: T, function: (T) -> T): T = function(value)

fun <T> preserve(function: () -> T): () -> T = function

fun orderedCallable(): (Int) -> Int {
    callableEvaluationOrder = callableEvaluationOrder + "R"
    return { value ->
        callableEvaluationOrder = callableEvaluationOrder + "I"
        value + 1
    }
}

fun orderedArgument(): Int {
    callableEvaluationOrder = callableEvaluationOrder + "A"
    return 41
}

fun acceptAny(value: Any): Int = 42

fun box(): String {
    val zero: () -> Int = { 40 }
    val one: (Int) -> Int = { value -> value + 2 }
    val two: (Int, Int) -> Int = { left, right -> left + right }
    if (call0(zero) != 40) return "fail 1: Function0"
    if (call1(one, 40) != 42) return "fail 2: Function1"
    if (call2(two, 20, 22) != 42) return "fail 3: Function2"

    val reference: (Int) -> Int = ::increment
    if (reference(41) != 42) return "fail 4: direct reference"

    val unit: () -> Unit = ::mark
    unit()
    if (unitCalls != 1) return "fail 5: Unit result"

    if (cached() !== cached()) return "fail 6: singleton reuse"

    val broad: (Any) -> String = { "variance" }
    val narrow: (String) -> Any = broad
    if (broad !== narrow) return "fail 7: reference variance"
    if (narrow("ignored") != "variance") return "fail 39: reference variance invocation"

    val primitive: () -> Int = { 42 }
    val widened: () -> Any = primitive
    if (primitive !== widened) return "fail 8: primitive result variance identity"
    if (acceptAny(widened()) != 42) return "fail 9: primitive result variance invocation"

    val acceptsAny: (Any) -> String = { "contravariance" }
    val acceptsInt: (Int) -> Any = acceptsAny
    if (acceptsAny !== acceptsInt) return "fail 10: primitive parameter variance identity"
    if (acceptAny(acceptsInt(1)) != 42) return "fail 11: primitive parameter variance invocation"

    val text: (String) -> String = { value -> value }
    if (text("reference cast") != "reference cast") return "fail 12: reference argument/result cast"

    val nullablePresent: () -> Int? = { 42 }
    val nullableAbsent: () -> Int? = { null }
    if (nullablePresent() != 42) return "fail 13: nullable primitive present"
    if (nullableAbsent() != null) return "fail 14: nullable primitive absent"

    val boolean: (Boolean) -> Boolean = { value -> !value }
    if (!boolean(false)) return "fail 15: Boolean bridge"

    val preserved = preserve(primitive)
    if (preserved !== primitive) return "fail 16: open generic identity"
    if (preserved() != 42) return "fail 17: open generic invocation"

    if (applyExact(41) { value -> value + 1 } != 42) return "fail 18: generic Int"
    if (applyExact("generic") { value -> value } != "generic") return "fail 19: generic String"

    val marker: Function<Int> = primitive
    val starMarker: Function<*> = marker
    if (marker !== primitive || starMarker !== primitive) return "fail 20: Function marker identity"

    val extension: String.() -> String = { this }
    if (extension("extension receiver") != "extension receiver") return "fail 21: extension receiver"

    val implemented: (Int) -> Int = Doubler()
    if (implemented(21) != 42) return "fail 22: explicit Function implementation"

    val nullableCallable: (() -> Int)? = null
    if (nullableCallable != null) return "fail 23: nullable callable"

    val immutable = captured(42)
    if (immutable() != 42) return "fail 24: immutable primitive capture"
    if (captured(42) === captured(42)) return "fail 25: capturing callable was cached"
    val immutableWidened: () -> Any = immutable
    if (immutable !== immutableWidened) return "fail 26: capture variance identity"
    if (capturedPair(20, 22)() != 42) return "fail 27: multiple captures"

    val capturedTextValue = "captured reference"
    val capturedText: () -> String = { capturedTextValue }
    if (capturedText() != capturedTextValue) return "fail 28: reference capture"

    val counter = mutableCounter(40)
    if (counter() != 41 || counter() != 42) return "fail 29: mutable capture"

    var shared = 1
    val readShared: () -> Int = { shared }
    val writeShared: (Int) -> Unit = { value -> shared = value }
    shared = 20
    if (readShared() != 20) return "fail 30: outer write did not reach shared cell"
    writeShared(42)
    if (readShared() != 42 || shared != 42) return "fail 31: closures do not share one cell"

    var nullableCell: Int? = null
    val setNullable: () -> Int? = {
        nullableCell = 42
        nullableCell
    }
    if (setNullable() != 42 || nullableCell != 42) return "fail 32: nullable primitive cell"

    if (genericMutable(1, 42)() != 42) return "fail 33: open generic primitive cell"
    if (genericMutable("before", "after")() != "after") return "fail 34: open generic reference cell"

    val boundPrimitive = boundIncrement(41)
    if (boundPrimitive() != 42) return "fail 35: bound primitive receiver"
    val boundWidened: () -> Any = boundPrimitive
    if (boundPrimitive !== boundWidened) return "fail 36: bound reference variance identity"
    if (boundIncrement(1) === boundIncrement(1)) return "fail 37: bound reference was cached"
    if (boundOffset(2)(40) != 42) return "fail 38: bound class receiver"

    callableEvaluationOrder = ""
    if (orderedCallable()(orderedArgument()) != 42 || callableEvaluationOrder != "RAI") {
        return "fail 40: callable evaluation order"
    }
    return "OK"
}
