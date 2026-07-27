/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.targets.dotnet

import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.plugin.AbstractKotlinTargetConfigurator
import org.jetbrains.kotlin.gradle.plugin.KotlinOnlyTargetConfigurator
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.attributes.KotlinDotNetTargetFramework
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinCompilationFactory
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinOnlyTargetPreset

internal class KotlinDotNetTargetPreset(
    project: Project,
    val targetFramework: KotlinDotNetTargetFramework,
) : KotlinOnlyTargetPreset<KotlinDotNetTarget, KotlinDotNetCompilation>(project) {

    override val platformType: KotlinPlatformType = KotlinPlatformType.dotnet

    override val name: String = presetName(targetFramework)

    override fun instantiateTarget(name: String): KotlinDotNetTarget =
        project.objects.newInstance(KotlinDotNetTarget::class.java, project, targetFramework)

    override fun createKotlinTargetConfigurator(): AbstractKotlinTargetConfigurator<KotlinDotNetTarget> =
        KotlinDotNetTargetConfigurator()

    override fun createCompilationFactory(
        forTarget: KotlinDotNetTarget,
    ): KotlinCompilationFactory<KotlinDotNetCompilation> = KotlinDotNetCompilationFactory(forTarget)

    companion object {
        fun presetName(targetFramework: KotlinDotNetTargetFramework): String =
            "dotnet-${targetFramework.targetFrameworkMoniker}"
    }
}

private class KotlinDotNetTargetConfigurator :
    KotlinOnlyTargetConfigurator<KotlinDotNetCompilation, KotlinDotNetTarget>(createTestCompilation = true)
