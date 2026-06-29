/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.android.externalAndroidTarget

import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.gradle.kotlin.dsl.kotlin
import org.gradle.util.GradleVersion
import org.jetbrains.kotlin.gradle.testbase.*

// Used AGP 9.0 as the minimal stable version supported for the android library compose setup.
@AndroidTestVersions(minVersion = TestVersions.AGP.AGP_90)
@AndroidGradlePluginTests
class AllTestsExternalAndroidTargetIT : KGPBaseTest() {

    @GradleAndroidTest
    fun `test - allTests runs android JVM tests from Kotlin and Java sources`(
        gradleVersion: GradleVersion,
        androidVersion: String,
        jdkVersion: JdkVersions.ProvidedJdk,
    ) {
        project(
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
                        target.namespace = "org.jetbrains.sample.alltests"
                        target.withJava()
                        target.withHostTest {}
                        target.withDeviceTest {}
                    }
                    sourceSets.getByName("androidHostTest").dependencies {
                        implementation("junit:junit:4.13.2")
                    }
                    sourceSets.getByName("androidDeviceTest").dependencies {
                        implementation("junit:junit:4.13.2")
                    }
                }
                project.tasks.matching { it.name == "allTests" }.configureEach { task ->
                    task.dependsOn("compileAndroidDeviceTest", "compileAndroidDeviceTestJavaWithJavac")
                }
            }

            projectPath.source("src/commonMain/kotlin/CommonMain.kt") {
                """
                object CommonMain {
                    override fun toString(): String = "CommonMain"
                }
                """.trimIndent()
            }
            projectPath.source("src/androidMain/kotlin/AndroidMain.kt") {
                """
                class AndroidMain {
                    companion object {
                        fun useCommonMain() {
                            println("useCommonMain: ${'$'}CommonMain")
                        }
                    }
                }
                """.trimIndent()
            }
            projectPath.source("src/androidHostTest/kotlin/AndroidKotlinAllTestsTest.kt") {
                """
                import org.junit.Assert.assertEquals
                import org.junit.Test

                class AndroidKotlinAllTestsTest {
                    @Test
                    fun kotlinTestRunsFromAllTests() {
                        println("KOTLIN_ANDROID_JVM_TEST_EXECUTED")
                        AndroidMain.useCommonMain()
                        assertEquals("CommonMain", CommonMain.toString())
                    }
                }
                """.trimIndent()
            }
            projectPath.source("src/androidHostTest/java/AndroidJavaAllTestsTest.java") {
                """
                import org.junit.Assert;
                import org.junit.Test;

                public class AndroidJavaAllTestsTest {
                    @Test
                    public void javaTestRunsFromAllTests() {
                        System.out.println("JAVA_ANDROID_JVM_TEST_EXECUTED");
                        AndroidMain.Companion.useCommonMain();
                        Assert.assertEquals("CommonMain", CommonMain.INSTANCE.toString());
                    }
                }
                """.trimIndent()
            }
            projectPath.source("src/androidDeviceTest/kotlin/AndroidDeviceAllTestsTest.kt") {
                """
                import org.junit.Test

                object AndroidDeviceAllTestsTest {
                    @Test
                    fun deviceTestCanAccessMainSources() {
                        AndroidMain.useCommonMain()
                        CommonMain.toString()
                    }
                }
                """.trimIndent()
            }
            projectPath.source("src/androidDeviceTest/java/AndroidJavaDeviceAllTestsTest.java") {
                """
                import org.junit.Test;

                public class AndroidJavaDeviceAllTestsTest {
                    @Test
                    public void deviceTestCanAccessMainSources() {
                        AndroidMain.Companion.useCommonMain();
                        CommonMain.INSTANCE.toString();
                    }
                }
                """.trimIndent()
            }

            build("allTests") {
                assertTasksExecuted(
                    ":allTests",
                    ":testAndroidHostTest",
                    ":compileAndroidDeviceTest",
                    ":compileAndroidDeviceTestJavaWithJavac"
                )
                assertOutputContains("KOTLIN_ANDROID_JVM_TEST_EXECUTED")
                assertOutputContains("JAVA_ANDROID_JVM_TEST_EXECUTED")
            }
        }
    }
}
