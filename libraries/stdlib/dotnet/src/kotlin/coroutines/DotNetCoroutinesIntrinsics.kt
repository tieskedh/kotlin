/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:Suppress("UNCHECKED_CAST", "INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package kotlin.coroutines.intrinsics

import kotlin.coroutines.Continuation
import kotlin.coroutines.DotNetCoroutineImpl
import kotlin.internal.InlineOnly

@InlineOnly
public actual inline fun <T> (suspend () -> T).startCoroutineUninterceptedOrReturn(
    completion: Continuation<T>,
): Any? {
    // JS uses the same wrapper (KT-55869): a direct suspend callable reference has no lambda
    // state-machine object of its own, but suspendCoroutineUninterceptedOrReturn must still see
    // an interceptable continuation carrying completion.context.
    val wrappedCompletion = if (completion is DotNetCoroutineImpl) {
        completion
    } else {
        createSimpleCoroutineForSuspendFunction(completion)
    }
    return (this as Function1<Continuation<T>, Any?>).invoke(wrappedCompletion)
}

@InlineOnly
public actual inline fun <R, T> (suspend R.() -> T).startCoroutineUninterceptedOrReturn(
    receiver: R,
    completion: Continuation<T>,
): Any? {
    val wrappedCompletion = if (completion is DotNetCoroutineImpl) {
        completion
    } else {
        createSimpleCoroutineForSuspendFunction(completion)
    }
    return (this as Function2<R, Continuation<T>, Any?>).invoke(receiver, wrappedCompletion)
}

@InlineOnly
internal actual inline fun <R, P, T> (suspend R.(P) -> T).startCoroutineUninterceptedOrReturn(
    receiver: R,
    param: P,
    completion: Continuation<T>,
): Any? {
    val wrappedCompletion = if (completion is DotNetCoroutineImpl) {
        completion
    } else {
        createSimpleCoroutineForSuspendFunction(completion)
    }
    return (this as Function3<R, P, Continuation<T>, Any?>).invoke(receiver, param, wrappedCompletion)
}

@PublishedApi
internal fun <T> createSimpleCoroutineForSuspendFunction(
    completion: Continuation<T>,
): Continuation<T> = object : DotNetCoroutineImpl(completion as Continuation<Any?>) {
    override fun doResume(): Any? {
        exception?.let { throw it }
        return result
    }
}

public actual fun <T> (suspend () -> T).createCoroutineUnintercepted(
    completion: Continuation<T>,
): Continuation<Unit> = object : DotNetCoroutineImpl(completion as Continuation<Any?>) {
    private var label = 0

    override fun doResume(): Any? {
        return when (label) {
            0 -> {
                label = 1
                exception?.let { throw it }
                this@createCoroutineUnintercepted.startCoroutineUninterceptedOrReturn(this as Continuation<T>)
            }
            1 -> {
                label = 2
                exception?.let { throw it }
                result
            }
            else -> error("This coroutine had already completed")
        }
    }
}

public actual fun <R, T> (suspend R.() -> T).createCoroutineUnintercepted(
    receiver: R,
    completion: Continuation<T>,
): Continuation<Unit> = object : DotNetCoroutineImpl(completion as Continuation<Any?>) {
    private var label = 0

    override fun doResume(): Any? {
        return when (label) {
            0 -> {
                label = 1
                exception?.let { throw it }
                this@createCoroutineUnintercepted.startCoroutineUninterceptedOrReturn(receiver, this as Continuation<T>)
            }
            1 -> {
                label = 2
                exception?.let { throw it }
                result
            }
            else -> error("This coroutine had already completed")
        }
    }
}

public actual fun <T> Continuation<T>.intercepted(): Continuation<T> =
    (this as? DotNetCoroutineImpl)?.intercepted() as? Continuation<T> ?: this
