/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin.coroutines

import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.internal.UsedFromCompilerGeneratedCode

/** Continuation base consumed by the explicit ordinary-IR .NET coroutine state machine. */
@SinceKotlin("1.3")
@PublishedApi
@UsedFromCompilerGeneratedCode
internal abstract class DotNetCoroutineImpl(
    protected val resultContinuation: Continuation<Any?>?,
) : Continuation<Any?> {
    protected var state: Int = 0
    protected var exceptionState: Int = 0
    protected var result: Any? = null
    protected var exception: Throwable? = null

    private val _context: CoroutineContext? = resultContinuation?.context
    private var _intercepted: Continuation<Any?>? = null

    final override val context: CoroutineContext
        get() = _context!!

    final override fun resumeWith(result: Result<Any?>) {
        var current = this
        var currentResult: Any? = result.getOrNull()
        var currentException: Throwable? = result.exceptionOrNull()

        while (true) {
            if (currentException == null) {
                current.result = currentResult
                current.exception = null
            } else {
                current.state = current.exceptionState
                current.exception = currentException
            }

            try {
                val outcome = current.doResume()
                if (outcome === COROUTINE_SUSPENDED) return
                currentResult = outcome
                currentException = null
            } catch (failure: Throwable) {
                currentResult = null
                currentException = failure
            }

            current.releaseIntercepted()
            val completion = current.resultContinuation!!
            if (completion is DotNetCoroutineImpl) {
                current = completion
            } else {
                completion.resumeWith(
                    if (currentException == null) Result.success(currentResult)
                    else Result.failure(currentException),
                )
                return
            }
        }
    }

    protected abstract fun doResume(): Any?

    internal fun intercepted(): Continuation<Any?> =
        _intercepted
            ?: (context[ContinuationInterceptor]?.interceptContinuation(this) ?: this)
                .also { _intercepted = it }

    protected fun releaseIntercepted() {
        val intercepted = _intercepted
        if (intercepted != null && intercepted !== this) {
            context[ContinuationInterceptor]!!.releaseInterceptedContinuation(intercepted)
        }
        _intercepted = CompletedDotNetContinuation
    }

    protected open fun create(completion: Continuation<*>): Continuation<Unit> {
        throw UnsupportedOperationException("create(Continuation) has not been overridden")
    }

    protected open fun create(value: Any?, completion: Continuation<*>): Continuation<Unit> {
        throw UnsupportedOperationException("create(Any?;Continuation) has not been overridden")
    }
}

private object CompletedDotNetContinuation : Continuation<Any?> {
    override val context: CoroutineContext
        get() = error("This continuation is already complete")

    override fun resumeWith(result: Result<Any?>) {
        error("This continuation is already complete")
    }

    override fun toString(): String = "This continuation is already complete"
}
