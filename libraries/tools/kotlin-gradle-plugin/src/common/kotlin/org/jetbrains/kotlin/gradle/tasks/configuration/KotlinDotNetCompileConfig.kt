/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.tasks.configuration

import org.jetbrains.kotlin.gradle.plugin.KotlinCompilationInfo
import org.jetbrains.kotlin.gradle.targets.dotnet.KotlinDotNetCompilation
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilerExecutionStrategy
import org.jetbrains.kotlin.gradle.tasks.KotlinDotNetCompile

internal class KotlinDotNetCompileConfig(
    compilationInfo: KotlinCompilationInfo,
) : AbstractKotlinCompileConfig<KotlinDotNetCompile>(compilationInfo) {
    init {
        configureTask { task ->
            val compilation = compilationInfo.origin as KotlinDotNetCompilation
            task.targetFramework.value(compilation.targetFramework).disallowChanges()

            // The daemon and Build Tools API protocols do not have a .NET compiler target yet.
            task.compilerExecutionStrategy.value(KotlinCompilerExecutionStrategy.IN_PROCESS).disallowChanges()
            task.runViaBuildToolsApi.value(false).disallowChanges()
            task.generateCompilerRefIndex.value(false).disallowChanges()
            task.incrementalModuleInfoProvider.disallowChanges()
        }
    }
}
