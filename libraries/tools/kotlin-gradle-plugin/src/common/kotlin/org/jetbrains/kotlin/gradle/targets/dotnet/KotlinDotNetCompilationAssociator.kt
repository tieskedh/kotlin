/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.targets.dotnet

import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.jetbrains.kotlin.gradle.dsl.KotlinDotNetCompilerOptions
import org.jetbrains.kotlin.gradle.plugin.KotlinTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.InternalKotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.mpp.compilationImpl.KotlinCompilationAssociator
import org.jetbrains.kotlin.gradle.plugin.mpp.compilationImpl.KotlinCompilationFriendPathsResolver
import org.jetbrains.kotlin.gradle.plugin.mpp.internal
import org.jetbrains.kotlin.gradle.plugin.mpp.legacyImplementationConfigurationName
import org.jetbrains.kotlin.gradle.tasks.KotlinDotNetCompile
import org.jetbrains.kotlin.gradle.utils.filesProvider

/**
 * A CLR friend is necessarily two-sided: the producer emits InternalsVisibleTo and the consumer
 * supplies the producer's exact metadata KLIB as a friend dependency.
 */
internal object KotlinDotNetCompilationAssociator : KotlinCompilationAssociator {
    override fun associate(
        target: KotlinTarget,
        auxiliary: InternalKotlinCompilation<*>,
        main: InternalKotlinCompilation<*>,
    ) {
        check(target is KotlinDotNetTarget)

        val producerKlib = target.project.filesProvider(main.compileKotlinTaskName) {
            main.dotNetKlibOutputFile()
        }
        auxiliary.compileDependencyFiles += producerKlib

        target.project.configurations.named(auxiliary.legacyImplementationConfigurationName).configure { configuration ->
            configuration.extendsFrom(
                target.project.configurations.getByName(main.legacyImplementationConfigurationName)
            )
        }

        @Suppress("DEPRECATION")
        val consumerModuleName =
            (auxiliary.compilerOptions.options as KotlinDotNetCompilerOptions).moduleName
        target.project.tasks.withType(KotlinDotNetCompile::class.java).configureEach { task ->
            if (task.name == main.compileKotlinTaskName) {
                task.authorizedFriendAssemblies.add(consumerModuleName)
            }
        }
    }
}

/**
 * The .NET compiler verifies friendship against the bound KLIB/DLL pair. Passing the compilation
 * output directory, as the default JVM-shaped resolver does, would not identify that pair.
 */
internal object KotlinDotNetCompilationFriendPathsResolver : KotlinCompilationFriendPathsResolver {
    override fun resolveFriendPaths(
        compilation: InternalKotlinCompilation<*>,
    ): Iterable<FileCollection> {
        val friendKlibs = compilation.project.files()
        compilation.allAssociatedCompilations.forAll { associatedCompilation ->
            val associated = associatedCompilation.internal
            friendKlibs.from(
                compilation.project.filesProvider(associated.compileKotlinTaskName) {
                    associated.dotNetKlibOutputFile()
                }
            )
        }
        return listOf(friendKlibs)
    }
}

@Suppress("UNCHECKED_CAST")
private fun InternalKotlinCompilation<*>.dotNetKlibOutputFile(): Provider<RegularFile> =
    (compileTaskProvider as TaskProvider<KotlinDotNetCompile>).flatMap { task -> task.klibOutputFile }
