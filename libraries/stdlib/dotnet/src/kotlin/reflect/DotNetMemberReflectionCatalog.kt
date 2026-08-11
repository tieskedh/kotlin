/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin.reflect

/**
 * Compiler ABI populated from the authoritative Stdlib/built-ins class scopes after KLIB
 * serialization. The optional reflection product calls the physical method through its private
 * target intrinsic; this Kotlin-internal declaration is not a public reflection API.
 */
@PublishedApi
internal fun dotNetGetStdlibMembersV1(kClass: KClass<*>): Array<KCallable<*>>? =
    throw NotImplementedError("Implemented by the Kotlin/.NET Stdlib member-catalog lowering: $kClass")
