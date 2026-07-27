/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:Suppress("DEPRECATION", "DEPRECATION_ERROR", "TYPEALIAS_EXPANSION_DEPRECATION_ERROR")

package org.jetbrains.kotlin.gradle.targets.dotnet

import org.gradle.api.tasks.TaskProvider
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.KotlinDotNetCompilerOptions
import org.jetbrains.kotlin.gradle.plugin.DeprecatedHasCompilerOptions
import org.jetbrains.kotlin.gradle.plugin.KotlinAnyOptionsDeprecated
import org.jetbrains.kotlin.gradle.plugin.attributes.KotlinDotNetTargetFramework
import org.jetbrains.kotlin.gradle.plugin.mpp.DeprecatedAbstractKotlinCompilationToRunnableFiles
import org.jetbrains.kotlin.gradle.plugin.mpp.compilationImpl.KotlinCompilationImpl
import org.jetbrains.kotlin.gradle.plugin.mpp.disambiguateName
import org.jetbrains.kotlin.gradle.tasks.KotlinDotNetCompile
import javax.inject.Inject

@ExperimentalKotlinGradlePluginApi
open class KotlinDotNetCompilation @Inject internal constructor(
    compilation: KotlinCompilationImpl,
) : DeprecatedAbstractKotlinCompilationToRunnableFiles<KotlinAnyOptionsDeprecated>(compilation) {

    override val target: KotlinDotNetTarget
        get() = super.target as KotlinDotNetTarget

    val targetFramework: KotlinDotNetTargetFramework
        get() = target.targetFramework

    @Deprecated(
        "To configure compilation compiler options use 'compileTaskProvider':\n" +
                "compilation.compileTaskProvider.configure {\n    compilerOptions {}\n}"
    )
    @Suppress("UNCHECKED_CAST")
    override val compilerOptions: DeprecatedHasCompilerOptions<KotlinDotNetCompilerOptions>
        get() = compilation.compilerOptions as DeprecatedHasCompilerOptions<KotlinDotNetCompilerOptions>

    @Suppress("UNCHECKED_CAST")
    override val compileTaskProvider: TaskProvider<KotlinDotNetCompile>
        get() = compilation.compileTaskProvider as TaskProvider<KotlinDotNetCompile>

    override val processResourcesTaskName: String
        get() = disambiguateName("processResources")
}
