/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.plugin

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.TaskProvider
import org.jetbrains.kotlin.gradle.dsl.KotlinDotNetCompilerOptions
import org.jetbrains.kotlin.gradle.tasks.KotlinDotNetCompile
import org.jetbrains.kotlin.gradle.tasks.KotlinTasksProvider
import org.jetbrains.kotlin.gradle.tasks.configuration.KotlinDotNetCompileConfig
import org.jetbrains.kotlin.gradle.tasks.dependsOn
import org.jetbrains.kotlin.gradle.tasks.locateTask

internal class KotlinDotNetSourceSetProcessor(
    compilation: KotlinCompilationInfo,
    tasksProvider: KotlinTasksProvider,
) : KotlinSourceSetProcessor<KotlinDotNetCompile>(
    tasksProvider,
    taskDescription = "Compiles the Kotlin sources in $compilation to a Kotlin/.NET library.",
    kotlinCompilation = compilation,
) {
    override fun doTargetSpecificProcessing() {
        project.tasks.named(compilationInfo.compileAllTaskName).dependsOn(kotlinTask)
        if (compilationInfo.isMain) {
            compilationInfo.tcs.compilation.target.let { target ->
                project.locateTask<Task>(target.artifactsTaskName)?.dependsOn(kotlinTask)
            }
        }

        project.launchInStage(KotlinPluginLifecycle.Stage.AfterEvaluateBuildscript) {
            val subpluginEnvironment = SubpluginEnvironment.loadSubplugins(project)
            subpluginEnvironment.addSubpluginOptions(project, compilationInfo.tcs.compilation)
        }
    }

    override fun doRegisterTask(project: Project, taskName: String): TaskProvider<out KotlinDotNetCompile> {
        val configAction = KotlinDotNetCompileConfig(compilationInfo)
        applyStandardTaskConfiguration(configAction)
        return tasksProvider.registerKotlinDotNetTask(
            project,
            taskName,
            compilationInfo.compilerOptions.options as KotlinDotNetCompilerOptions,
            configAction,
        )
    }
}
