// OPT_IN: kotlin.contracts.ExperimentalContracts

import kotlin.contracts.*

private class Holder(var value: String)

private open class CustomMessageError : Error("ignored") {
    override val message: String?
        get() = "custom"
}

private class DerivedCustomMessageError : CustomMessageError()

private fun isString(value: Any?): Boolean {
    contract { returns(true) implies (value is String) }
    return value is String
}

private fun stringLength(value: Any?): Int =
    if (isString(value)) value.length else -1

private fun nonLocalRun(): String {
    run { return "OK" }
}

private fun testTodoFunctions(): String? {
    try {
        TODO()
        return "FAIL TODO did not throw"
    } catch (failure: NotImplementedError) {
        if (failure.message != "An operation is not implemented." || failure.cause != null) {
            return "FAIL TODO message"
        }
    }
    try {
        TODO("reason")
        return "FAIL TODO reason did not throw"
    } catch (failure: NotImplementedError) {
        if (failure.message != "An operation is not implemented: reason") return "FAIL TODO reason message"
    }
    return null
}

fun box(): String {
    val assigned: Int
    var calls = 0
    val runResult = run {
        calls++
        assigned = 7
        "run"
    }
    if (assigned != 7 || runResult != "run" || calls != 1) return "FAIL run"

    val holder = Holder("A")
    var receiverRunCalls = 0
    val receiverRunResult = holder.run {
        receiverRunCalls++
        if (this !== holder) "wrong receiver" else value + "R"
    }
    if (receiverRunResult != "AR" || receiverRunCalls != 1) return "FAIL receiver run"
    if (holder.apply { value += "P" } !== holder || holder.value != "AP") return "FAIL apply"
    if (holder.also { it.value += "L" } !== holder || holder.value != "APL") return "FAIL also"
    if (holder.let { it.value + "Y" } != "APLY") return "FAIL let"
    if (with(holder) { value + "W" } != "APLW") return "FAIL with"

    var predicateCalls = 0
    if (holder.takeIf { predicateCalls++; it.value == "APL" } !== holder || predicateCalls != 1) {
        return "FAIL takeIf"
    }
    if (holder.takeIf { predicateCalls++; false } != null || predicateCalls != 2) return "FAIL takeIf null"
    if (holder.takeUnless { predicateCalls++; it.value == "missing" } !== holder || predicateCalls != 3) {
        return "FAIL takeUnless"
    }
    if (holder.takeUnless { predicateCalls++; true } != null || predicateCalls != 4) return "FAIL takeUnless null"

    if (stringLength("four") != 4 || stringLength(null) != -1) return "FAIL smart cast"
    if (nonLocalRun() != "OK") return "FAIL non-local return"
    testTodoFunctions()?.let { return it }
    if (DerivedCustomMessageError().message != "custom") return "FAIL custom Throwable message"

    if (buildString { append('O'); append('K') } != "OK") return "FAIL buildString"
    if (buildString(2) { append("OK") } != "OK") return "FAIL buildString capacity"
    try {
        buildString(-1) { append("unreachable") }
        return "FAIL negative buildString capacity"
    } catch (_: IllegalArgumentException) {
    }

    val kinds = InvocationKind.entries
    if (kinds.size != 4) return "FAIL invocation kind size"
    if (kinds[0] !== InvocationKind.AT_MOST_ONCE) return "FAIL at most once"
    if (kinds[1] !== InvocationKind.AT_LEAST_ONCE) return "FAIL at least once"
    if (kinds[2] !== InvocationKind.EXACTLY_ONCE) return "FAIL exactly once"
    if (kinds[3] !== InvocationKind.UNKNOWN) return "FAIL unknown"

    return "OK"
}
