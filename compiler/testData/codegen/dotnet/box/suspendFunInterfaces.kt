import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

private fun interface SuspendTransform {
    suspend fun transform(value: Int): Int
}

private fun <T> runSuspend(block: suspend () -> T): T {
    var result: Result<T>? = null
    block.startCoroutine(object : Continuation<T> {
        override val context = EmptyCoroutineContext

        override fun resumeWith(resumeResult: Result<T>) {
            result = resumeResult
        }
    })
    return result!!.getOrThrow()
}

fun box(): String {
    val transform = SuspendTransform { it + 1 }
    if (runSuspend { transform.transform(41) } != 42) return "fail 1: suspend forwarding"

    val function: suspend (Int) -> Int = { it + 2 }
    val first = SuspendTransform(function)
    val second = SuspendTransform(function)
    if (first === second || first != second) return "fail 2: suspend wrapper equality"
    if (runSuspend { first.transform(40) } != 42) return "fail 3: stored suspend function"

    return "OK"
}
