// FILE: provide.kt
package delegated.failures

import kotlin.reflect.KProperty

object ProvideFailure {
    val throwable = Exception("provide")
}

private operator fun String.provideDelegate(receiver: Any?, property: KProperty<*>): String {
    throw ProvideFailure.throwable
}

private operator fun String.getValue(receiver: Any?, property: KProperty<*>): String = this

val broken: String by "unreachable"

// FILE: get.kt
package delegated.failures

import kotlin.reflect.KProperty

object GetFailure {
    val throwable = Exception("get")
}

private class ThrowingGetter {
    operator fun getValue(receiver: Any?, property: KProperty<*>): String {
        throw GetFailure.throwable
    }
}

val unreadable: String by ThrowingGetter()

// FILE: set.kt
package delegated.failures

import kotlin.reflect.KProperty

object SetFailure {
    val throwable = Exception("set")
}

private class ThrowingSetter {
    operator fun getValue(receiver: Any?, property: KProperty<*>): String = "initial"

    operator fun setValue(receiver: Any?, property: KProperty<*>, value: String) {
        throw SetFailure.throwable
    }
}

var unwritable: String by ThrowingSetter()

// FILE: main.kt
import delegated.failures.GetFailure
import delegated.failures.ProvideFailure
import delegated.failures.SetFailure
import delegated.failures.broken
import delegated.failures.unreadable
import delegated.failures.unwritable

private fun expectSame(expected: Exception, action: () -> Unit): String? {
    try {
        action()
        return "no exception"
    } catch (actual: Exception) {
        if (actual !== expected) return "wrong exception: ${actual.message}"
    }
    return null
}

fun box(): String {
    @Suppress("INVISIBLE_REFERENCE")
    try {
        broken
        return "provide first: no exception"
    } catch (failure: ExceptionInInitializerError) {
        if (failure.message != null) return "provide first message: ${failure.message}"
        if (failure.cause !== ProvideFailure.throwable) return "provide first cause"
    }

    @Suppress("INVISIBLE_REFERENCE")
    try {
        broken
        return "provide second: no exception"
    } catch (failure: NoClassDefFoundError) {
        if (failure.message != "Could not initialize file") {
            return "provide second message: ${failure.message}"
        }
    }

    expectSame(GetFailure.throwable) { unreadable }?.let { return "get: $it" }
    expectSame(SetFailure.throwable) { unwritable = "changed" }?.let { return "set: $it" }
    return "OK"
}
