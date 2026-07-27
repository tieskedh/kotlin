/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle

import org.gradle.util.GradleVersion
import org.jetbrains.kotlin.gradle.testbase.GradleTest
import org.jetbrains.kotlin.gradle.testbase.GradleTestVersions
import org.jetbrains.kotlin.gradle.testbase.KGPBaseTest
import org.jetbrains.kotlin.gradle.testbase.MppGradlePluginTests
import org.jetbrains.kotlin.gradle.testbase.TestVersions
import org.jetbrains.kotlin.gradle.testbase.assertFileInProjectExists
import org.jetbrains.kotlin.gradle.testbase.assertTasksExecuted
import org.jetbrains.kotlin.gradle.testbase.build
import org.jetbrains.kotlin.gradle.testbase.project
import org.jetbrains.kotlin.test.TestMetadata
import org.junit.jupiter.api.DisplayName

@DisplayName("Kotlin/.NET target")
class KotlinDotNetTargetIT : KGPBaseTest() {
    @GradleTest
    @GradleTestVersions(minVersion = TestVersions.Gradle.MAX_SUPPORTED)
    @MppGradlePluginTests
    @TestMetadata("dotnetSimple")
    fun producesMatchedKlibAndDllWithTheRealCompiler(
        gradleVersion: GradleVersion,
    ) {
        project("dotnetSimple", gradleVersion) {
            build("compileTestKotlinDotnet") {
                assertTasksExecuted(":compileKotlinDotnet", ":compileTestKotlinDotnet")
            }

            assertFileInProjectExists("build/classes/kotlin/dotnet/main/Sample.Library.klib")
            assertFileInProjectExists("build/classes/kotlin/dotnet/main/Sample.Library.dll")
            assertFileInProjectExists("build/classes/kotlin/dotnet/test/Sample.Library_test.klib")
            assertFileInProjectExists("build/classes/kotlin/dotnet/test/Sample.Library_test.dll")
        }
    }
}
