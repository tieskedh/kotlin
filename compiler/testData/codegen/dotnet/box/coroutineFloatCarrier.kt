// WITH_STDLIB
// WITH_COROUTINES

import helpers.EmptyContinuation
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.coroutines.resume
import kotlin.coroutines.startCoroutine

private var suspendedFloat: Continuation<Float>? = null

private suspend fun nextFloat(): Float = suspendCoroutineUninterceptedOrReturn { continuation ->
    suspendedFloat = continuation
    COROUTINE_SUSPENDED
}

private suspend fun addAfterSuspension(first: Float): Float {
    val live = first
    return live + nextFloat()
}

fun box(): String {
    var result = 0.0f
    suspend {
        result = addAfterSuspension(1.25f)
    }.startCoroutine(EmptyContinuation)

    if (result != 0.0f) return "completed before resume: $result"
    val continuation = suspendedFloat ?: return "continuation was not captured"
    continuation.resume(2.5f)

    return if (result == 3.75f) "OK" else "wrong result: $result"
}
