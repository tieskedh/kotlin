/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.unitTests.compilerArgumetns

import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.api.tasks.compile.JavaCompile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerArgumentsProducer.CreateCompilerArgumentsContext.Companion.lenient
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.gradle.util.buildProject
import org.jetbrains.kotlin.gradle.util.kotlin
import org.jetbrains.kotlin.gradle.util.setAndroidSdkDirProperty
import org.jetbrains.kotlin.gradle.utils.named
import kotlin.test.Test
import kotlin.test.assertEquals

class JvmToolchainExternalAndroidTargetTest {

    @Test
    fun `jvmToolchain configures Kotlin and Java tasks for androidLibrary and androidHostTest`() {
        val project = externalAndroidLibraryProject(
            androidLibraryConfiguration = {
                withHostTest {}
            },
            kotlinConfiguration = {
                jvmToolchain(jvm8ToolchainVersion)
            },
        )
        project.evaluate()

        val expectedConfiguration = KotlinTaskConfiguration(
            jvmTarget = jvm8Target,
            toolchainVersion = jvm8ToolchainVersion,
        )

        assertEquals(expectedConfiguration, project.kotlinTaskConfiguration("compileAndroidMain"))
        assertEquals(expectedConfiguration, project.kotlinTaskConfiguration("compileAndroidHostTest"))
        assertEquals(
            JavaTaskConfiguration(toolchainVersion = jvm8ToolchainVersion,),
            project.javaTaskConfiguration("compileAndroidMainJavaWithJavac"),
        )
    }

    @Test
    fun `jvmToolchain and jvmTarget configure androidLibrary task independently`() {
        val project = externalAndroidLibraryProject(
            androidLibraryConfiguration = {
                compilerOptions.jvmTarget.set(JvmTarget.JVM_11)
            },
            kotlinConfiguration = {
                jvmToolchain(jvm8ToolchainVersion)
            },
        )
        project.evaluate()

        assertEquals(
            KotlinTaskConfiguration(
                jvmTarget = JvmTarget.JVM_11.target,
                toolchainVersion = jvm8ToolchainVersion,
            ),
            project.kotlinTaskConfiguration("compileAndroidMain"),
        )
    }

    private fun externalAndroidLibraryProject(
        androidLibraryConfiguration: KotlinMultiplatformAndroidLibraryTarget.() -> Unit = {},
        kotlinConfiguration: KotlinMultiplatformExtension.() -> Unit = {},
    ): ProjectInternal = buildProject {
        setAndroidSdkDirProperty(project)
        plugins.apply("kotlin-multiplatform")
        plugins.apply("com.android.kotlin.multiplatform.library")
        kotlin {
            targets.withType(KotlinMultiplatformAndroidLibraryTarget::class.java).configureEach { target ->
                target.compileSdk = 34
                target.namespace = "org.jetbrains.sample.multitarget"
                target.withJava()
                target.androidLibraryConfiguration()
            }
            kotlinConfiguration()
        }
    }

    private fun Project.kotlinTaskConfiguration(taskName: String): KotlinTaskConfiguration {
        val task = tasks.named<KotlinCompile>(taskName).get()
        return KotlinTaskConfiguration(
            jvmTarget = task.createCompilerArguments(lenient).jvmTarget.orEmpty(),
            toolchainVersion = task.kotlinJavaToolchain.javaVersion.get().majorVersion.toInt(),
        )
    }

    private fun Project.javaTaskConfiguration(taskName: String): JavaTaskConfiguration {
        val task = tasks.named<JavaCompile>(taskName).get()
        return JavaTaskConfiguration(
            toolchainVersion = task.javaCompiler.get().metadata.languageVersion.asInt(),
        )
    }

    private data class KotlinTaskConfiguration(
        val jvmTarget: String,
        val toolchainVersion: Int,
    )

    private data class JavaTaskConfiguration(
        val toolchainVersion: Int,
    )

    private companion object {
        val jvm8ToolchainVersion: Int = JavaVersion.VERSION_1_8.majorVersion.toInt()
        val jvm8Target: String = JvmTarget.fromTarget(JavaVersion.VERSION_1_8.toString()).target
    }
}
