/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.unitTests

import org.jetbrains.kotlin.cli.common.arguments.K2DotNetCompilerArguments
import org.jetbrains.kotlin.gradle.dsl.KotlinDotNetCompilerOptionsDefault
import org.jetbrains.kotlin.gradle.dsl.KotlinDotNetCompilerOptionsHelper
import org.jetbrains.kotlin.gradle.util.buildProject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KotlinDotNetCompilerOptionsTest {
    @Test
    fun `generated options fill common and dotnet compiler arguments`() {
        val project = buildProject()
        val options = project.objects.newInstance(KotlinDotNetCompilerOptionsDefault::class.java).apply {
            moduleName.set("Sample.Assembly")
            progressiveMode.set(true)
        }
        val arguments = K2DotNetCompilerArguments()

        KotlinDotNetCompilerOptionsHelper.fillCompilerArguments(options, arguments)

        assertEquals("Sample.Assembly", arguments.moduleName)
        assertTrue(arguments.progressiveMode)
    }

    @Test
    fun `generated options synchronize target conventions without preventing overrides`() {
        val project = buildProject()
        val targetOptions = project.objects.newInstance(KotlinDotNetCompilerOptionsDefault::class.java).apply {
            moduleName.set("Target.Assembly")
            progressiveMode.set(true)
        }
        val compilationOptions = project.objects.newInstance(KotlinDotNetCompilerOptionsDefault::class.java)

        KotlinDotNetCompilerOptionsHelper.syncOptionsAsConvention(targetOptions, compilationOptions)

        assertEquals("Target.Assembly", compilationOptions.moduleName.get())
        assertTrue(compilationOptions.progressiveMode.get())

        compilationOptions.moduleName.set("Compilation.Assembly")
        assertEquals("Compilation.Assembly", compilationOptions.moduleName.get())
    }
}
