/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin.reflect

/** The minimal Common KClass contract physically implemented by Kotlin.Runtime. */
public actual interface KClass<T : Any> : KClassifier {
    public actual val simpleName: String?

    public actual val qualifiedName: String?

    @SinceKotlin("1.1")
    public actual fun isInstance(value: Any?): Boolean

    actual override fun equals(other: Any?): Boolean

    actual override fun hashCode(): Int
}
