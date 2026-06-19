/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.android.externalAndroidTarget

import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.kotlin.dsl.kotlin
import org.gradle.util.GradleVersion
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerArgumentsProducer
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.gradle.testbase.*
import org.jetbrains.kotlin.gradle.uklibs.ignoreAccessViolations
import kotlin.test.assertEquals

// Used AGP 9.0 as the minimal stable version supported for the android library
@AndroidTestVersions(minVersion = TestVersions.AGP.AGP_90)
@AndroidGradlePluginTests
class JvmToolchainExternalAndroidTargetIT : KGPBaseTest() {

    // Uses `com.android.kotlin.multiplatform.library`, requires AGP new DSL.
    override val defaultBuildOptions: BuildOptions
        get() = super.defaultBuildOptions.copy(enableLegacyAgpDsl = false)

    @GradleAndroidTest
    fun `test - jvmToolchain configures compiler arguments for androidLibrary and androidHostTest`(
        gradleVersion: GradleVersion,
        androidVersion: String,
        jdkVersion: JdkVersions.ProvidedJdk,
    ) {
        externalAndroidLibraryProject(
            gradleVersion = gradleVersion,
            androidVersion = androidVersion,
            jdkVersion = jdkVersion,
            namespace = "org.jetbrains.sample.multitarget",
            androidLibraryConfiguration = {
                withHostTest {}
            },
            kotlinConfiguration = {
                jvmToolchain(11)
            },
        ) {

            val taskConfigurations = kotlinTaskConfigurations("compileAndroidMain", "compileAndroidHostTest")
            val expectedConfiguration = mapOf(
                "jvmTarget" to "11",
                "toolchainHome" to jdk11Info.jdkRealPath,
            )

            assertEquals(expectedConfiguration, taskConfigurations.getValue("compileAndroidMain"))
            assertEquals(expectedConfiguration, taskConfigurations.getValue("compileAndroidHostTest"))
        }
    }

    @GradleAndroidTest
    fun `test - jvmToolchain configures Kotlin and Java tasks for androidLibrary withJava`(
        gradleVersion: GradleVersion,
        androidVersion: String,
        jdkVersion: JdkVersions.ProvidedJdk,
    ) {
        externalAndroidLibraryProject(
            gradleVersion = gradleVersion,
            androidVersion = androidVersion,
            jdkVersion = jdkVersion,
            namespace = "org.jetbrains.sample.withjava",
            kotlinConfiguration = {
                jvmToolchain(11)
            },
        ) {

            val kotlinTaskConfiguration = kotlinTaskConfigurations("compileAndroidMain").getValue("compileAndroidMain")
            val javaTaskConfiguration = javaTaskConfiguration("compileAndroidMainJavaWithJavac")

            assertEquals(
                mapOf(
                    "jvmTarget" to "11",
                    "toolchainHome" to jdk11Info.jdkRealPath,
                ),
                kotlinTaskConfiguration,
            )
            assertEquals(
                mapOf("toolchainHome" to jdk11Info.jdkRealPath),
                javaTaskConfiguration,
            )
        }
    }

    @GradleAndroidTest
    fun `test - jvmToolchain and jvmTarget configure androidLibrary task independently`(
        gradleVersion: GradleVersion,
        androidVersion: String,
        jdkVersion: JdkVersions.ProvidedJdk,
    ) {
        externalAndroidLibraryProject(
            gradleVersion = gradleVersion,
            androidVersion = androidVersion,
            jdkVersion = jdkVersion,
            namespace = "com.example.lib",
            androidLibraryConfiguration = {
                compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
            },
            kotlinConfiguration = {
                jvmToolchain(17)
            },
        ) {

            assertEquals(
                mapOf(
                    "jvmTarget" to "11",
                    "toolchainHome" to jdk17Info.jdkRealPath,
                ),
                kotlinTaskConfigurations("compileAndroidMain").getValue("compileAndroidMain"),
            )
        }
    }

    private fun externalAndroidLibraryProject(
        gradleVersion: GradleVersion,
        androidVersion: String,
        jdkVersion: JdkVersions.ProvidedJdk,
        namespace: String,
        androidLibraryConfiguration: KotlinMultiplatformAndroidLibraryTarget.() -> Unit = {},
        kotlinConfiguration: KotlinMultiplatformExtension.() -> Unit = {},
        configureProject: TestProject.() -> Unit = {},
    ): TestProject = project(
        "empty",
        gradleVersion = gradleVersion,
        buildOptions = defaultBuildOptions.copy(androidVersion = androidVersion),
        buildJdk = jdkVersion.location,
    ) {
        plugins {
            kotlin("multiplatform")
            id("com.android.kotlin.multiplatform.library")
        }
        buildScriptInjection {
            kotlinMultiplatform.apply {
                targets.withType(KotlinMultiplatformAndroidLibraryTarget::class.java).configureEach { target ->
                    target.compileSdk = 34
                    target.namespace = namespace
                    target.withJava()
                    target.androidLibraryConfiguration()
                }
                kotlinConfiguration()
            }
        }
        projectPath.resolve("gradle.properties").toFile().appendText(
            """

            org.gradle.java.installations.auto-download=false
            org.gradle.java.installations.auto-detect=false
            org.gradle.java.installations.paths=${jdk11Info.javaHome},${jdk17Info.javaHome},${jdkVersion.location}
            """.trimIndent()
        )
        configureProject()
    }

    private fun TestProject.kotlinTaskConfigurations(vararg taskNames: String): Map<String, Map<String, String>> =
        buildScriptReturn {
            project.ignoreAccessViolations {
                taskNames.associateWith { taskName ->
                    val task = project.tasks.named(taskName, KotlinCompile::class.java).get()
                    val arguments = task.createCompilerArguments(KotlinCompilerArgumentsProducer.CreateCompilerArgumentsContext.default)
                    val toolchainHome = task.kotlinJavaToolchain.javaVersion
                        .map { version ->
                            when (version.majorVersion) {
                                "11" -> jdk11Info.jdkRealPath
                                "17" -> jdk17Info.jdkRealPath
                                else -> error("Unexpected Java toolchain version for '$taskName': ${version.majorVersion}")
                            }
                        }
                        .get()
                    mapOf(
                        "jvmTarget" to arguments.jvmTarget.orEmpty(),
                        "toolchainHome" to toolchainHome,
                    )
                }
            }
        }.buildAndReturn(":help")

    private fun TestProject.javaTaskConfiguration(taskName: String): Map<String, String> =
        buildScriptReturn {
            project.ignoreAccessViolations {
                val task = project.tasks.named(taskName, JavaCompile::class.java).get()
                mapOf(
                    "toolchainHome" to task.javaCompiler.get().metadata.installationPath.asFile.toPath().toRealPath().toString(),
                )
            }
        }.buildAndReturn(":help")
}
