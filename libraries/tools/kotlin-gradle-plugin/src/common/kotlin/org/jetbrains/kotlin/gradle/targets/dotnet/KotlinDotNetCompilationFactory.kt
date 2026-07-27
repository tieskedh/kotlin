/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.targets.dotnet

import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinCompilationFactory
import org.jetbrains.kotlin.gradle.plugin.mpp.compilationImpl.factory.DefaultKotlinCompilationDependencyConfigurationsFactory
import org.jetbrains.kotlin.gradle.plugin.mpp.compilationImpl.factory.KotlinCompilationImplFactory
import org.jetbrains.kotlin.gradle.plugin.mpp.compilationImpl.factory.KotlinDotNetCompilerOptionsFactory

internal class KotlinDotNetCompilationFactory(
    override val target: KotlinDotNetTarget,
) : KotlinCompilationFactory<KotlinDotNetCompilation> {

    override val itemClass: Class<KotlinDotNetCompilation>
        get() = KotlinDotNetCompilation::class.java

    private val compilationImplFactory = KotlinCompilationImplFactory(
        compilerOptionsFactory = KotlinDotNetCompilerOptionsFactory,
        compilationDependencyConfigurationsFactory = DefaultKotlinCompilationDependencyConfigurationsFactory.WithRuntime(),
        compilationAssociator = KotlinDotNetCompilationAssociator,
        compilationFriendPathsResolver = KotlinDotNetCompilationFriendPathsResolver,
    )

    override fun create(name: String): KotlinDotNetCompilation = target.project.objects.newInstance(
        KotlinDotNetCompilation::class.java,
        compilationImplFactory.create(target, name),
    )
}
