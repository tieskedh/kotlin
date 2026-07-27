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
import org.jetbrains.kotlin.gradle.testbase.assertFileInProjectNotExists
import org.jetbrains.kotlin.gradle.testbase.assertTasksAreNotInTaskGraph
import org.jetbrains.kotlin.gradle.testbase.assertTasksExecuted
import org.jetbrains.kotlin.gradle.testbase.build
import org.jetbrains.kotlin.gradle.testbase.project
import org.jetbrains.kotlin.test.TestMetadata
import org.junit.jupiter.api.DisplayName
import kotlin.io.path.deleteExisting
import kotlin.io.path.readText

@DisplayName("Kotlin/.NET target")
class KotlinDotNetTargetIT : KGPBaseTest() {
    @GradleTest
    @GradleTestVersions(minVersion = TestVersions.Gradle.MAX_SUPPORTED)
    @MppGradlePluginTests
    @TestMetadata("dotnetSimple")
    fun producesSelfDescribingDllAndUsesItForAssociatedCompilation(
        gradleVersion: GradleVersion,
    ) {
        project("dotnetSimple", gradleVersion) {
            build("compileKotlinDotnet") {
                assertTasksExecuted(":compileKotlinDotnet")
            }

            assertFileInProjectExists("build/classes/kotlin/dotnet/main/Sample.Library.klib")
            assertFileInProjectExists("build/classes/kotlin/dotnet/main/Sample.Library.dll")
            projectPath.resolve("build/classes/kotlin/dotnet/main/Sample.Library.klib").deleteExisting()

            build("compileTestKotlinDotnet", "-x", "compileKotlinDotnet") {
                assertTasksExecuted(":compileTestKotlinDotnet")
                assertTasksAreNotInTaskGraph(":compileKotlinDotnet")
            }

            assertFileInProjectNotExists("build/classes/kotlin/dotnet/main/Sample.Library.klib")
            assertFileInProjectExists("build/classes/kotlin/dotnet/test/Sample.Library_test.klib")
            assertFileInProjectExists("build/classes/kotlin/dotnet/test/Sample.Library_test.dll")
        }
    }

    @GradleTest
    @GradleTestVersions(minVersion = TestVersions.Gradle.MAX_SUPPORTED)
    @MppGradlePluginTests
    @TestMetadata("dotnetProjectDependency")
    fun projectDependencyResolvesDllWithoutSiblingKlib(
        gradleVersion: GradleVersion,
    ) {
        project("dotnetProjectDependency", gradleVersion) {
            build(":producer:compileKotlinDotnet") {
                assertTasksExecuted(":producer:compileKotlinDotnet")
            }

            assertFileInProjectExists("producer/build/classes/kotlin/dotnet/main/Producer.Library.dll")
            projectPath.resolve("producer/build/classes/kotlin/dotnet/main/Producer.Library.klib").deleteExisting()

            build(":consumer:compileKotlinDotnet", "-x", ":producer:compileKotlinDotnet") {
                assertTasksExecuted(":consumer:compileKotlinDotnet")
                assertTasksAreNotInTaskGraph(":producer:compileKotlinDotnet")
            }

            assertFileInProjectNotExists("producer/build/classes/kotlin/dotnet/main/Producer.Library.klib")
            val consumerIl = projectPath
                .resolve("consumer/build/classes/kotlin/dotnet/main/Consumer.Library.il")
                .readText()
            kotlin.test.assertTrue(".assembly extern 'Producer.Library'" in consumerIl, consumerIl)
        }
    }
}
