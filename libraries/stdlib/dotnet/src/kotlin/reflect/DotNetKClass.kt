/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin.reflect

/**
 * The Common KClass contract plus the optional JVM-shaped .NET reflection
 * surface. Full member discovery requires the separate reflection product.
 */
public actual interface KClass<T : Any> : KDeclarationContainer, KAnnotatedElement, KClassifier {
    public actual val simpleName: String?

    public actual val qualifiedName: String?

    /**
     * All functions and properties accessible in this class, including those
     * declared in this class and its superclasses. Does not include constructors.
     */
    override val members: Collection<KCallable<*>>

    @SinceKotlin("1.1")
    public actual fun isInstance(value: Any?): Boolean

    actual override fun equals(other: Any?): Boolean

    actual override fun hashCode(): Int
}
