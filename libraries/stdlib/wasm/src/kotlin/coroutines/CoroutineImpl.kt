/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin.coroutines

import kotlin.internal.UsedFromCompilerGeneratedCode

@SinceKotlin("1.3")
@UsedFromCompilerGeneratedCode
internal abstract class CoroutineImpl<T, R>(protected val resultContinuation: Continuation<R>?) : Continuation<T> {
    protected var state = 0
    protected var exceptionState = 0

    @UsedFromCompilerGeneratedCode
    internal var result: Any? = null
    @UsedFromCompilerGeneratedCode
    internal var exception: Throwable? = null

    protected open val _context: CoroutineContext? = resultContinuation?.context
    public override val context: CoroutineContext get() = _context!!

    protected var intercepted_: Continuation<T>? = null

    public fun intercepted(): Continuation<T> = intercepted_
        ?: (context[ContinuationInterceptor]?.interceptContinuation(this) ?: this)
            .also { intercepted_ = it }

    protected fun releaseIntercepted() {
        val intercepted = intercepted_
        if (intercepted != null && intercepted !== this) {
            context[ContinuationInterceptor]!!.releaseInterceptedContinuation(intercepted)
        }
        this.intercepted_ = CompletedContinuation // just in case
    }
}

internal object CompletedContinuation : Continuation<Any?> {
    override val context: CoroutineContext
        get() = error("This continuation is already complete")

    override fun resumeWith(result: Result<Any?>) {
        error("This continuation is already complete")
    }

    override fun toString(): String = "This continuation is already complete"
}
