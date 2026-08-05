private class NullableBox<T>(var value: T?)

private interface NullableSource<T> {
    fun nullable(): T?
}

private class NullableSourceImpl<T>(private val stored: T?) : NullableSource<T> {
    override fun nullable(): T? = stored
}

private fun <T> echo(value: T?): T? = value

private fun <T> forward(value: T?): T? = echo(value)

private fun <T> throughLocal(value: T): T? {
    val local: T? = value
    return local
}

private fun <T> sameNullable(first: T?, second: T?): Boolean = first == second

private fun <T> requireValue(value: T?): T = value!!

private fun <T : Any> nullGuarded(value: T?): T =
    if (value != null) value else throw IllegalArgumentException("missing")

private fun <T : String> echoStringBound(value: T?): T? = value

private fun <T : String> requireStringBound(value: T?): T = value!!

fun box(): String {
    if (echo<Int>(null) != null) return "fail 1: primitive null"
    if (echo(41) != 41) return "fail 2: primitive value"
    if (forward<Int>(42) != 42) return "fail 3: primitive forwarding"
    if (requireValue<Int>(43) != 43) return "fail 4: primitive recovery"
    if (nullGuarded<Int>(48) != 48) return "fail 4c: primitive null-guard recovery"
    if (throughLocal(46) != 46) return "fail 4a: primitive local"
    if (!sameNullable<Int>(47, 47) || sameNullable<Int>(47, null)) {
        return "fail 4b: primitive equality"
    }

    if (echo<String>(null) != null) return "fail 5: reference null"
    if (echo("reference") != "reference") return "fail 6: reference value"
    if (requireValue("required") != "required") return "fail 7: reference recovery"
    if (nullGuarded("guarded") != "guarded") return "fail 7f: reference null-guard recovery"
    if (throughLocal("local") != "local") return "fail 7a: reference local"
    if (!sameNullable<String>(null, null) || sameNullable("left", "right")) {
        return "fail 7b: reference equality"
    }
    if (echoStringBound<String>(null) != null) return "fail 7c: string-bound null"
    if (echoStringBound("bounded") != "bounded") return "fail 7d: string-bound value"
    if (requireStringBound("required-bound") != "required-bound") {
        return "fail 7e: string-bound recovery"
    }

    val primitiveBox = NullableBox<Int>(null)
    if (primitiveBox.value != null) return "fail 8: primitive field null"
    primitiveBox.value = 44
    if (primitiveBox.value != 44) return "fail 9: primitive field value"

    val referenceBox = NullableBox<String>(null)
    referenceBox.value = "field"
    if (referenceBox.value != "field") return "fail 10: reference field"

    val primitiveSource: NullableSource<Int> = NullableSourceImpl(45)
    if (primitiveSource.nullable() != 45) return "fail 11: primitive interface slot"
    val referenceSource: NullableSource<String> = NullableSourceImpl(null)
    if (referenceSource.nullable() != null) return "fail 12: reference interface slot"

    var threw = false
    try {
        requireValue<Int>(null)
    } catch (_: NullPointerException) {
        threw = true
    }
    if (!threw) return "fail 13: null check"

    threw = false
    try {
        requireStringBound<String>(null)
    } catch (_: NullPointerException) {
        threw = true
    }
    if (!threw) return "fail 14: string-bound null check"

    try {
        nullGuarded<String>(null)
        return "fail 15: null guard did not throw"
    } catch (failure: IllegalArgumentException) {
        if (failure.message != "missing") return "fail 16: null guard failure identity"
    }

    return "OK"
}
