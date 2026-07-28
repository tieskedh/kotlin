// LANGUAGE: +StrictEquals

private var equalityBodyCalls = 0

private open class EqualityBase(val value: Int) {
    override fun equals(@EqualityBound(EqualityBase::class) other: Any?): Boolean {
        equalityBodyCalls++
        return value == other.value
    }
}

private class EqualityChild(value: Int, val label: String) : EqualityBase(value) {
    override fun equals(@EqualityBound(EqualityChild::class) other: Any?): Boolean {
        equalityBodyCalls++
        return value == other.value && label == other.label
    }
}

fun box(): String {
    val base = EqualityBase(1)
    equalityBodyCalls = 0
    if (!base.equals(base)) return "FAIL: reference equality"
    if (equalityBodyCalls != 0) return "FAIL: reference guard called body $equalityBodyCalls times"

    if (base.equals("not an EqualityBase")) return "FAIL: wrong bound accepted"
    if (equalityBodyCalls != 0) return "FAIL: bound guard called body $equalityBodyCalls times"

    if (!base.equals(EqualityBase(1))) return "FAIL: equal base rejected"
    if (equalityBodyCalls != 1) return "FAIL: base body calls=$equalityBodyCalls"

    val child = EqualityChild(1, "a")
    if (!child.equals(EqualityChild(1, "a"))) return "FAIL: equal child rejected"
    if (child.equals(EqualityChild(1, "b"))) return "FAIL: unequal child accepted"
    if (child.equals(EqualityBase(1))) return "FAIL: child accepted its base"
    return "OK"
}
