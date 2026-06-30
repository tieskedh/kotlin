package org.jetbrains.kotlin.gradle.android.externalAndroidTarget

import org.jetbrains.kotlin.gradle.testing.internal.kotlinTestRegistry
import org.jetbrains.kotlin.gradle.util.buildProjectWithMPP
import org.jetbrains.kotlin.gradle.util.populateTaskGraph
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AllTestsExternalAndroidTargetTest {

    @Test
    fun `allTests task graph includes Android host test and excludes device test tasks`() {
        val project = buildProjectWithMPP()

        val compileAndroidHostTest = project.tasks.register("compileAndroidHostTest")
        val compileAndroidHostTestJavaWithJavac = project.tasks.register("compileAndroidHostTestJavaWithJavac")
        val compileAndroidDeviceTest = project.tasks.register("compileAndroidDeviceTest")
        val compileAndroidDeviceTestJavaWithJavac = project.tasks.register("compileAndroidDeviceTestJavaWithJavac")
        project.tasks.register("testAndroidDeviceTest", org.gradle.api.tasks.testing.Test::class.java) { task ->
            task.dependsOn(compileAndroidDeviceTest, compileAndroidDeviceTestJavaWithJavac)
        }
        val testAndroidHostTest = project.tasks.register("testAndroidHostTest", org.gradle.api.tasks.testing.Test::class.java) { task ->
            task.dependsOn(compileAndroidHostTest, compileAndroidHostTestJavaWithJavac)
        }

        project.kotlinTestRegistry.registerTestTask(testAndroidHostTest)
        val allTests = project.kotlinTestRegistry.allTestsTask.get()

        assertFalse(allTests.checkFailedTests)
        assertFalse(testAndroidHostTest.get().ignoreFailures)

        project.populateTaskGraph(allTests)

        assertTrue(allTests.checkFailedTests)
        assertTrue(testAndroidHostTest.get().ignoreFailures)
        assertTrue(project.gradle.taskGraph.hasTask(":allTests"))
        assertTrue(project.gradle.taskGraph.hasTask(":testAndroidHostTest"))
        assertTrue(project.gradle.taskGraph.hasTask(":compileAndroidHostTest"))
        assertTrue(project.gradle.taskGraph.hasTask(":compileAndroidHostTestJavaWithJavac"))

        assertFalse(project.gradle.taskGraph.hasTask(":compileAndroidDeviceTest"))
        assertFalse(project.gradle.taskGraph.hasTask(":compileAndroidDeviceTestJavaWithJavac"))
        assertFalse(project.gradle.taskGraph.hasTask(":testAndroidDeviceTest"))
    }
}
