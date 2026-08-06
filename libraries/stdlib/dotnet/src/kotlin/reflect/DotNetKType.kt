/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin.reflect

/** The Common Kotlin type graph; the CLR carrier is deliberately not `System.Type`. */
public actual interface KType {
    @SinceKotlin("1.1")
    public actual val classifier: KClassifier?

    @SinceKotlin("1.1")
    public actual val arguments: List<KTypeProjection>

    public actual val isMarkedNullable: Boolean
}
