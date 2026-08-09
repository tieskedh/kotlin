/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin.coroutines

import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.CoroutineSingletons.RESUMED
import kotlin.coroutines.intrinsics.CoroutineSingletons.UNDECIDED

@PublishedApi
@SinceKotlin("1.3")
internal actual class SafeContinuation<in T> internal actual constructor(
    private val delegate: Continuation<T>,
    initialResult: Any?,
) : Continuation<T> {
    @PublishedApi
    internal actual constructor(delegate: Continuation<T>) : this(delegate, UNDECIDED)

    actual override val context: CoroutineContext
        get() = delegate.context

    @kotlin.concurrent.Volatile
    private var result: Any? = initialResult

    /** Replaced by an `Interlocked.CompareExchange` CIL intrinsic. */
    private fun compareAndSetResult(expected: Any?, update: Any?): Boolean =
        error("Implemented as a Kotlin/.NET compiler intrinsic")

    actual override fun resumeWith(result: Result<T>) {
        while (true) {
            val current = this.result
            when {
                current === UNDECIDED -> if (compareAndSetResult(UNDECIDED, result.value)) return
                current === COROUTINE_SUSPENDED -> if (compareAndSetResult(COROUTINE_SUSPENDED, RESUMED)) {
                    delegate.resumeWith(result)
                    return
                }
                else -> throw IllegalStateException("Already resumed")
            }
        }
    }

    @PublishedApi
    internal actual fun getOrThrow(): Any? {
        var current = result
        if (current === UNDECIDED) {
            if (compareAndSetResult(UNDECIDED, COROUTINE_SUSPENDED)) return COROUTINE_SUSPENDED
            current = result
        }
        return when {
            current === RESUMED -> COROUTINE_SUSPENDED
            current is Result.Failure -> throw current.exception
            else -> current
        }
    }
}
