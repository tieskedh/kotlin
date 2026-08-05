/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin.concurrent

/**
 * Resolution-only marker for volatile reference caches used by admitted Common stdlib sources.
 *
 * This is intentionally internal until the complete public Common `Volatile` contract, including
 * the CLR rules for every supported scalar carrier, is admitted. The backend nevertheless emits
 * the exact CLR volatile access prefix for fields carrying this marker.
 */
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.SOURCE)
internal annotation class Volatile
