/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
@file:Suppress("DEPRECATION")

package org.jetbrains.kotlin.gradle.unitTests

import org.jetbrains.kotlin.gradle.dsl.multiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerArgumentsProducer.CreateCompilerArgumentsContext.Companion.lenient
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.attributes.KotlinDotNetTargetFramework
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilerExecutionStrategy
import org.jetbrains.kotlin.gradle.tasks.KotlinDotNetCompile
import org.jetbrains.kotlin.gradle.util.buildProjectWithMPP
import org.jetbrains.kotlin.gradle.util.main
import org.jetbrains.kotlin.gradle.util.test
import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class KotlinDotNetTargetTest {
    @Test
    fun `each profile owns an immutable target compilation and task`() {
        val project = buildProjectWithMPP(
            projectBuilder = { withName("dotnetProject") },
        )
        val kotlin = project.multiplatformExtension
        val targets = KotlinDotNetTargetFramework.values().associateWith { targetFramework ->
            kotlin.dotnet(targetFramework, targetName(targetFramework))
        }

        project.evaluate()

        targets.forEach { (targetFramework, target) ->
            assertEquals(KotlinPlatformType.dotnet, target.platformType)
            assertEquals(targetFramework, target.targetFramework)
            assertEquals(targetFramework, target.attributes.getAttribute(KotlinDotNetTargetFramework.ATTRIBUTE))
            assertSame(target, kotlin.dotnet(targetFramework, target.name))

            val mainCompilation = target.compilations.main
            val testCompilation = target.compilations.test
            assertEquals(targetFramework, mainCompilation.targetFramework)
            assertEquals(targetFramework, testCompilation.targetFramework)
            assertEquals("dotnetProject", mainCompilation.compilerOptions.options.moduleName.get())
            assertEquals("dotnetProject_test", testCompilation.compilerOptions.options.moduleName.get())

            val mainTask = mainCompilation.compileTaskProvider.get()
            val testTask = testCompilation.compileTaskProvider.get()
            assertTrue(KotlinDotNetCompile::class.java.isAssignableFrom(mainTask.javaClass))
            assertEquals(targetFramework, mainTask.targetFramework.get())
            assertEquals(KotlinCompilerExecutionStrategy.IN_PROCESS, mainTask.compilerExecutionStrategy.get())
            assertFalse(mainTask.runViaBuildToolsApi.get())
            assertFalse(mainTask.generateCompilerRefIndex.get())

            val mainArguments = mainTask.createCompilerArguments(lenient)
            assertEquals(targetFramework.targetFrameworkMoniker, mainArguments.dotNetTarget)
            assertEquals("dotnetProject", mainArguments.moduleName)
            assertEquals(listOf("dotnetProject_test"), mainArguments.dotNetFriendAssemblies.toList())
            assertTrue(mainArguments.dotNetProduceLibrary)
            assertTrue(
                File(mainArguments.destination!!).toPath().endsWith(Paths.get(target.name, "main"))
            )

            assertTrue(mainTask in testTask.taskDependencies.getDependencies(testTask))
            assertTrue(mainTask in testTask.friendPaths.buildDependencies.getDependencies(testTask))
            assertTrue(mainTask in testTask.libraries.buildDependencies.getDependencies(testTask))
        }
    }

    @Test
    fun `target framework is present on incoming and outgoing variants`() {
        val project = buildProjectWithMPP()
        val targetFramework = KotlinDotNetTargetFramework.NET10_0
        val target = project.multiplatformExtension.dotnet(targetFramework)

        project.evaluate()

        val main = target.compilations.main
        val configurationNames = listOf(
            target.apiElementsConfigurationName,
            target.runtimeElementsConfigurationName,
            main.compileDependencyConfigurationName,
            main.runtimeDependencyConfigurationName,
        )
        configurationNames.forEach { configurationName ->
            val actual = project.configurations.getByName(configurationName)
                .attributes
                .getAttribute(KotlinDotNetTargetFramework.ATTRIBUTE)
            assertEquals(targetFramework, actual, "Unexpected target framework on $configurationName")
        }
    }

    @Test
    fun `target compiler options are compilation conventions and remain overridable`() {
        val project = buildProjectWithMPP()
        val target = project.multiplatformExtension.dotnet(KotlinDotNetTargetFramework.NET48) {
            compilerOptions.moduleName.set("Profile.Library")
            compilerOptions.progressiveMode.set(true)
        }

        project.evaluate()

        val mainOptions = target.compilations.main.compilerOptions.options
        val testOptions = target.compilations.test.compilerOptions.options
        assertEquals("Profile.Library", mainOptions.moduleName.get())
        assertEquals("Profile.Library_test", testOptions.moduleName.get())
        assertTrue(mainOptions.progressiveMode.get())

        mainOptions.moduleName.set("Compilation.Library")
        assertEquals("Compilation.Library", mainOptions.moduleName.get())
    }

    private fun targetName(targetFramework: KotlinDotNetTargetFramework): String =
        "dotnet" + targetFramework.name.lowercase().replace("_", "")
}
