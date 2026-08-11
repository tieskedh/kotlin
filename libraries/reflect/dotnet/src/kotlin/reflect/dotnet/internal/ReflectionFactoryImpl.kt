/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin.reflect.dotnet.internal

import kotlin.reflect.KCallable
import kotlin.reflect.KClass

/** Versioned entry point loaded reflectively by the lightweight Kotlin.Runtime bootstrap. */
public fun getMembersV1(kClass: KClass<*>): Collection<KCallable<*>>? =
    dotNetGetGeneratedMembersV1(kClass)

/**
 * Irreducible Runtime bootstrap call. Logical member selection remains in this optional product;
 * Runtime only invokes the exact compiler-emitted factory selected by that policy.
 */
private external fun dotNetGetGeneratedMembersV1(kClass: KClass<*>): Collection<KCallable<*>>?
