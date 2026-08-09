// WITH_STDLIB
// WITH_COROUTINES

import helpers.*
import kotlin.coroutines.*
import kotlin.coroutines.intrinsics.*

private var parked: Continuation<String>? = null

private suspend fun pause(): String = suspendCoroutineUninterceptedOrReturn { continuation ->
    parked = continuation
    COROUTINE_SUSPENDED
}

private fun builder(block: suspend () -> Unit) {
    block.startCoroutine(EmptyContinuation)
}

private suspend fun invoke21(
    callable: suspend (
        Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int,
        Int, Int, Int, Int, Int, Int, Int, Int, Int, String,
    ) -> String,
): String = callable(
    1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11,
    12, 13, 14, 15, 16, 17, 18, 19, 20, "O",
)

fun box(): String {
    var result = "not started"
    builder {
        result = invoke21 {
                p1, _, _, _, _, _, _, _, _, _, _,
                _, _, _, _, _, _, _, _, p20, text,
            -> "$text${p1 + p20}:${pause()}"
        }
    }
    if (result != "not started") return "fail 1: suspension did not park"
    val continuation = parked ?: return "fail 2: continuation was not captured"
    parked = null
    continuation.resume("K")
    if (result != "O21:K") return "fail 3: $result"
    if (parked != null) return "fail 4: continuation leaked"
    return "OK"
}
