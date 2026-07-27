/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.targets.dotnet

import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.HasConfigurableKotlinCompilerOptions
import org.jetbrains.kotlin.gradle.dsl.KotlinDotNetCompilerOptions
import org.jetbrains.kotlin.gradle.dsl.KotlinDotNetCompilerOptionsDefault
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.attributes.KotlinDotNetTargetFramework
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinOnlyTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.baseModuleName
import org.jetbrains.kotlin.gradle.plugin.mpp.disambiguateName
import org.jetbrains.kotlin.gradle.utils.moduleName
import org.jetbrains.kotlin.gradle.utils.newInstance
import javax.inject.Inject

@ExperimentalKotlinGradlePluginApi
abstract class KotlinDotNetTarget @Inject internal constructor(
    project: Project,
    val targetFramework: KotlinDotNetTargetFramework,
) : KotlinOnlyTarget<KotlinDotNetCompilation>(project, KotlinPlatformType.dotnet),
    HasConfigurableKotlinCompilerOptions<KotlinDotNetCompilerOptions> {

    init {
        attributes.attribute(KotlinDotNetTargetFramework.ATTRIBUTE, targetFramework)
    }

    override val artifactsTaskName: String
        get() = disambiguateName("artifacts")

    override val compilerOptions: KotlinDotNetCompilerOptions = project.objects
        .newInstance<KotlinDotNetCompilerOptionsDefault>()
        .apply {
            moduleName.convention(project.moduleName(project.baseModuleName()))
        }
}
