@file:Suppress("UNCHECKED_CAST")

// These are the concrete array shapes that the shared inliner must leave after replacing a
// reified type parameter. This test deliberately contains no reified declaration: it validates
// the target operations without weakening either public reified-support gate.

private class Payload<T>(private val value: T) {
    fun read(): T = value
}

private interface Token<T> {
    fun read(): T
}

private class TokenImpl<T>(private val value: T) : Token<T> {
    override fun read(): T = value
}

private var trace = ""

private fun <T> recorded(label: String, value: T): T {
    trace += label
    return value
}

private fun stringVararg(vararg values: String): Array<out String> = values

fun box(): String {
    trace = ""
    val references = arrayOf(recorded("a", "A"), recorded("b", "B"))
    if (trace != "ab" || references.size != 2 || references[0] != "A" || references[1] != "B") {
        return "fail 1: reference literal/evaluation order"
    }
    if (emptyArray<String>().size != 0) return "fail 2: empty reference"
    val nullReferences = arrayOfNulls<String>(2)
    if (nullReferences.size != 2 || nullReferences[0] != null || nullReferences[1] != null) {
        return "fail 3: zeroed reference allocation"
    }

    trace = ""
    val initializedReferences = Array(3) { index ->
        trace += index
        "v$index"
    }
    if (trace != "012" || initializedReferences[0] != "v0" || initializedReferences[2] != "v2") {
        return "fail 4: reference initializer"
    }

    val scalar = arrayOf(40, 2)
    if (scalar[0] + scalar[1] != 42 || emptyArray<Int>().size != 0) {
        return "fail 5: scalar carrier"
    }
    val nullableScalar = arrayOf<Int?>(40, null, 2)
    val zeroedNullableScalar = arrayOfNulls<Int>(2)
    if ((nullableScalar[0] ?: 0) + (nullableScalar[2] ?: 0) != 42 ||
        nullableScalar[1] != null || zeroedNullableScalar[0] != null
    ) return "fail 6: nullable scalar carrier"

    val anys = arrayOf<Any?>(1, "two", null)
    if (anys[0] != 1 || anys[1] != "two" || anys[2] != null) return "fail 7: Any? carrier"
    val sequences = arrayOf<CharSequence>("four", "two")
    if (sequences[0].length + sequences[1].length != 7) return "fail 8: classified CharSequence"

    val payloads = arrayOf(Payload("payload"))
    if (payloads[0].read() != "payload") return "fail 9: generic-class element"
    try {
        val wrongPayloadArguments = payloads as Any as Array<Payload<Int>>
        if (wrongPayloadArguments as Any !== payloads) return "fail 10: generic-class array identity"
        val impossible: Int = wrongPayloadArguments[0].read()
        impossible + 1
        return "fail 11: generic-class erased element barrier"
    } catch (_: ClassCastException) {
        // A CLR vector whose component is Payload<string> can expose the implementation-defined
        // failure of this throwing parameterized cast before the later element read.
    }
    val zeroedPayloads = arrayOfNulls<Payload<Int>>(1)
    if (zeroedPayloads[0] != null || emptyArray<Payload<Int>>().size != 0) {
        return "fail 12: generic-class empty/null allocation"
    }
    val initializedPayloads = Array(2) { Payload(it) }
    if (initializedPayloads[0].read() != 0 || initializedPayloads[1].read() != 1) {
        return "fail 13: generic-class initializer"
    }

    val tokens = arrayOf<Token<String>>(TokenImpl("token"))
    if (tokens[0].read() != "token") return "fail 14: split-interface element"
    val wrongTokenArguments = tokens as Any as Array<Token<Int>>
    if (wrongTokenArguments as Any !== tokens) return "fail 15: split-interface array identity"
    try {
        val impossible: Int = wrongTokenArguments[0].read()
        impossible + 1
        return "fail 16: split-interface erased element barrier"
    } catch (_: ClassCastException) {
    }

    val nested = Array<Array<Int>>(2) { outer -> arrayOf(outer, outer + 40) }
    if (nested[0][1] != 40 || nested[1][1] != 41) return "fail 17: exact nested vectors"
    val starElements = arrayOf<Array<*>>(arrayOf(42), arrayOf<String?>("x", null))
    if ((starElements[0] as Array<Int>)[0] != 42 ||
        (starElements[1] as Array<String?>)[1] != null
    ) return "fail 18: classified star-array elements"
    val initializedStars = Array<Array<*>>(2) { index ->
        if (index == 0) arrayOf(1) else arrayOf("two")
    }
    if ((initializedStars[0] as Array<Int>)[0] != 1 ||
        (initializedStars[1] as Array<String>)[0] != "two"
    ) return "fail 19: star-array initializer"

    val wrappers = arrayOf(intArrayOf(40, 2))
    if (wrappers[0][0] + wrappers[0][1] != 42) return "fail 20: primitive-array wrapper element"
    val failures = arrayOf<Throwable>(IllegalArgumentException("expected"))
    if (failures[0].message != "expected") return "fail 21: classified throwable element"

    trace = ""
    val spread = arrayOf("spread")
    val combined = stringVararg(recorded("l", "left"), *spread, recorded("r", "right"))
    if (trace != "lr" || combined.size != 3 || combined[0] != "left" ||
        combined[1] != "spread" || combined[2] != "right"
    ) return "fail 22: vararg/spread order"

    try {
        arrayOfNulls<Payload<String>>(-1)
        return "fail 23: negative size accepted"
    } catch (_: Exception) {
    }

    return "OK"
}
