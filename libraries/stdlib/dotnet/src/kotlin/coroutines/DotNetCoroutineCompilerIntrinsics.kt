/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:Suppress("RedundantSuspendModifier", "UNCHECKED_CAST")

package kotlin.dotnet.internal

import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.internal.UsedFromCompilerGeneratedCode

@PublishedApi
@UsedFromCompilerGeneratedCode
internal fun <T> getContinuation(): Continuation<T> =
    error("Implemented as a Kotlin/.NET compiler intrinsic")

@PublishedApi
@UsedFromCompilerGeneratedCode
internal suspend fun <T> returnIfSuspended(argument: Any?): T = argument as T

@PublishedApi
@UsedFromCompilerGeneratedCode
internal suspend inline fun getCoroutineContext(): CoroutineContext =
    getContinuation<Any?>().context

@PublishedApi
@UsedFromCompilerGeneratedCode
internal suspend inline fun <T> suspendCoroutineUninterceptedOrReturnDotNet(
    block: (Continuation<T>) -> Any?,
): T = returnIfSuspended(block(getContinuation()))
