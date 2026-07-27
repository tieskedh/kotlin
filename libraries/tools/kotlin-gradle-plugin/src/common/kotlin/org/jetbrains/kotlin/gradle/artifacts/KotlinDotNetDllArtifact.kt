/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.artifacts

import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.plugins.BasePlugin
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation.Companion.MAIN_COMPILATION_NAME
import org.jetbrains.kotlin.gradle.plugin.addToAssemble
import org.jetbrains.kotlin.gradle.targets.dotnet.KotlinDotNetTarget
import org.jetbrains.kotlin.gradle.tasks.registerTask
import org.jetbrains.kotlin.gradle.utils.registerArtifact

/**
 * Publishes the self-describing CLR assembly directly.
 *
 * Kotlin metadata is the private Kotlin.Metadata resource inside this DLL, so a Gradle variant
 * must not publish or require a standalone KLIB as a second artifact identity.
 */
internal val KotlinDotNetDllArtifact = KotlinTargetArtifact { target, apiElements, runtimeElements ->
    if (target !is KotlinDotNetTarget) return@KotlinTargetArtifact

    val mainCompilation = target.compilations.getByName(MAIN_COMPILATION_NAME)
    val compileTask = mainCompilation.compileTaskProvider
    val assemblyFile = compileTask.flatMap { task -> task.assemblyOutputFile }
    val artifactsTask = target.project.registerTask<DefaultTask>(target.artifactsTaskName) { task ->
        task.group = BasePlugin.BUILD_GROUP
        task.description = "Assembles the self-describing DLL for target '${target.name}'."
        task.dependsOn(compileTask)
    }
    target.project.addToAssemble(artifactsTask)

    listOfNotNull(apiElements, runtimeElements).forEach { configuration ->
        configuration.outgoing.registerArtifact(
            artifactProvider = assemblyFile,
            name = target.project.name,
            type = DOTNET_DLL_TYPE,
            extension = DOTNET_DLL_TYPE,
        ) {
            builtBy(compileTask)
        }
        configuration.outgoing.attributes.attribute(
            ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
            DOTNET_DLL_TYPE,
        )
    }
}

private const val DOTNET_DLL_TYPE = "dll"
