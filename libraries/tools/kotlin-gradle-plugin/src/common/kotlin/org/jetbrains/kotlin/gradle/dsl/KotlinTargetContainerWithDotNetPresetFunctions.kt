/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.dsl

import org.gradle.api.Action
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.InternalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.plugin.attributes.KotlinDotNetTargetFramework
import org.jetbrains.kotlin.gradle.targets.dotnet.KotlinDotNetTarget

@KotlinGradlePluginPublicDsl
@ExperimentalKotlinGradlePluginApi
interface KotlinTargetContainerWithDotNetPresetFunctions : KotlinTargetContainerWithPresetFunctions {
    fun dotnet(
        targetFramework: KotlinDotNetTargetFramework,
        name: String = DEFAULT_DOTNET_NAME,
        configure: KotlinDotNetTarget.() -> Unit = {},
    ): KotlinDotNetTarget

    fun dotnet(targetFramework: KotlinDotNetTargetFramework): KotlinDotNetTarget =
        dotnet(targetFramework, DEFAULT_DOTNET_NAME) {}

    fun dotnet(targetFramework: KotlinDotNetTargetFramework, name: String): KotlinDotNetTarget =
        dotnet(targetFramework, name) {}

    fun dotnet(
        targetFramework: KotlinDotNetTargetFramework,
        configure: Action<KotlinDotNetTarget>,
    ): KotlinDotNetTarget = dotnet(targetFramework) {
        configure.execute(this)
    }

    fun dotnet(
        targetFramework: KotlinDotNetTargetFramework,
        name: String,
        configure: Action<KotlinDotNetTarget>,
    ): KotlinDotNetTarget = dotnet(targetFramework, name) {
        configure.execute(this)
    }

    @InternalKotlinGradlePluginApi
    companion object {
        internal const val DEFAULT_DOTNET_NAME = "dotnet"
    }
}
