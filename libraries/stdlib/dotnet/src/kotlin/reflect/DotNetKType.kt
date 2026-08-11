/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin.reflect

/**
 * The Common Kotlin type graph; the CLR carrier is deliberately not `System.Type`.
 *
 * Like the JVM reflection surface, .NET additionally exposes runtime-retained annotations on
 * declaration-derived type uses. This is a platform reflection capability, not a Common `KType`
 * requirement.
 */
public actual interface KType : KAnnotatedElement {
    @SinceKotlin("1.1")
    public actual val classifier: KClassifier?

    @SinceKotlin("1.1")
    public actual val arguments: List<KTypeProjection>

    public actual val isMarkedNullable: Boolean
}
