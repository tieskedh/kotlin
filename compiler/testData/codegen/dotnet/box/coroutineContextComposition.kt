// WITH_STDLIB
// WITH_COROUTINES

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.Continuation
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.createCoroutine
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.coroutines.resume
import kotlin.coroutines.startCoroutine

private class FirstElement(val value: Int) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<FirstElement>
}

private class SecondElement(val value: String) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<SecondElement>
}

private class RecordingInterceptor :
    AbstractCoroutineContextElement(ContinuationInterceptor),
    ContinuationInterceptor {
    var interceptions = 0
    var releases = 0

    override fun <T> interceptContinuation(continuation: Continuation<T>): Continuation<T> {
        interceptions++
        return object : Continuation<T> {
            override val context: CoroutineContext = continuation.context

            override fun resumeWith(result: Result<T>) {
                continuation.resumeWith(result)
            }
        }
    }

    override fun releaseInterceptedContinuation(continuation: Continuation<*>) {
        releases++
    }
}

private var suspended: Continuation<Unit>? = null

private suspend fun observeContext(expected: CoroutineContext): String {
    if (coroutineContext !== expected) return "wrong context before suspension"
    if (coroutineContext[FirstElement]?.value != 3) return "wrong first element before suspension"
    if (coroutineContext[SecondElement]?.value != "two") return "wrong second element before suspension"

    suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
        suspended = continuation
        COROUTINE_SUSPENDED
    }

    if (coroutineContext !== expected) return "wrong context after suspension"
    if (coroutineContext[FirstElement]?.value != 3) return "wrong first element after suspension"
    if (coroutineContext[SecondElement]?.value != "two") return "wrong second element after suspension"
    return "OK"
}

private class ContextReceiver(private val expected: CoroutineContext) {
    fun operation(): suspend ContextReceiver.() -> String = { observeContext(expected) }
}

fun box(): String {
    val first = FirstElement(1)
    val second = SecondElement("two")
    val interceptor = RecordingInterceptor()

    val initial = EmptyCoroutineContext + first + interceptor + second
    if (initial[FirstElement] !== first) return "initial first lookup failed"
    if (initial[SecondElement] !== second) return "initial second lookup failed"
    if (initial[ContinuationInterceptor] !== interceptor) return "initial interceptor lookup failed"

    val replacement = FirstElement(3)
    val context = initial + replacement
    if (context[FirstElement] !== replacement) return "replacement failed"
    if (context.minusKey(FirstElement)[FirstElement] != null) return "minusKey failed"

    val order = context.fold("") { result, element ->
        result + when (element) {
            second -> "second;"
            replacement -> "first;"
            interceptor -> "interceptor;"
            else -> "unknown;"
        }
    }
    if (order != "second;first;interceptor;") return "wrong fold order: $order"

    var result: String? = null
    suspend { observeContext(context) }.startCoroutine(object : Continuation<String> {
        override val context: CoroutineContext = context

        override fun resumeWith(outcome: Result<String>) {
            result = outcome.getOrThrow()
        }
    })

    if (result != null) return "did not suspend"
    val continuation = suspended ?: return "continuation was not captured"
    if (continuation.context !== context) return "captured continuation lost context"
    continuation.resume(Unit)

    if (result != "OK") return result ?: "completion was not resumed"
    if (interceptor.interceptions != 1) return "wrong start interception count: ${interceptor.interceptions}"
    if (interceptor.releases != 1) return "wrong start release count: ${interceptor.releases}"

    suspended = null
    result = null
    val receiver = ContextReceiver(context)
    val created = receiver.operation().createCoroutine(receiver, object : Continuation<String> {
        override val context: CoroutineContext = context

        override fun resumeWith(outcome: Result<String>) {
            result = outcome.getOrThrow()
        }
    })
    created.resume(Unit)

    if (result != null) return "receiver coroutine did not suspend"
    val receiverContinuation = suspended ?: return "receiver continuation was not captured"
    if (receiverContinuation.context !== context) return "receiver continuation lost context"
    receiverContinuation.resume(Unit)

    if (result != "OK") return result ?: "receiver completion was not resumed"
    if (interceptor.interceptions != 2) return "wrong receiver interception count: ${interceptor.interceptions}"
    if (interceptor.releases != 2) return "wrong receiver release count: ${interceptor.releases}"
    return "OK"
}
