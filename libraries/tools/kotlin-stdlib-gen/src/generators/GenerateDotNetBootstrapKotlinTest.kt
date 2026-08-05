/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package generators

import java.io.File
import kotlin.system.exitProcess

/**
 * Projects the smallest authoritative kotlin.test closure needed to execute dependency-closed
 * upstream stdlib tests on Kotlin/.NET. This is a staged Common source product, not a target copy
 * of assertion algorithms and not a claim that the complete kotlin.test API is available.
 */
fun main(args: Array<String>) {
    if (args.size != 1) {
        println("Parameters:\n    <kotlin-base-dir>")
        exitProcess(1)
    }

    val baseDir = File(args.single())
    val outputDirectory = baseDir.resolve(
        "libraries/kotlin.test/dotnet/common/src/main/kotlin/kotlin/test"
    )
    val commonAnnotations = baseDir.resolve(
        "libraries/kotlin.test/annotations-common/src/main/kotlin/kotlin.test/Annotations.kt"
    )
    val commonAssertions = baseDir.resolve(
        "libraries/kotlin.test/common/src/main/kotlin/kotlin/test/Assertions.kt"
    )
    val commonUtils = baseDir.resolve(
        "libraries/kotlin.test/common/src/main/kotlin/kotlin/test/Utils.kt"
    )
    val commonDefaultAsserter = baseDir.resolve(
        "libraries/kotlin.test/common/src/main/kotlin/kotlin/test/DefaultAsserter.kt"
    )

    outputDirectory.mkdirs()
    outputDirectory.resolve("_DotNetBootstrapTestAnnotation.kt").writeText(
        buildProjectedSource(
            packageName = "kotlin.test",
            declarations = listOf(
                extractCommonDeclaration(commonAnnotations, "public expect annotation class Test()"),
            ),
            generatorName = "GenerateDotNetBootstrapKotlinTest.kt",
        ),
        Charsets.UTF_8,
    )
    val assertions = buildProjectedSource(
        packageName = "kotlin.test",
        fileAnnotations = listOf("Suppress(\"INVISIBLE_MEMBER\", \"INVISIBLE_REFERENCE\")"),
        imports = listOf("kotlin.internal.*"),
        declarations = listOf(
            extractCommonDeclaration(
                commonAssertions,
                "public fun <@OnlyInputTypes T> assertEquals(expected: T, actual: T, message: String? = null)",
            ),
            extractCommonDeclaration(
                commonAssertions, "public interface Asserter {",
            ),
            extractCommonSingleLineDeclaration(
                commonUtils,
                "internal fun messagePrefix(message: String?) = if (message == null) \"\" else \"${'$'}message. \"",
            ),
        ),
        generatorName = "GenerateDotNetBootstrapKotlinTest.kt",
    )
    outputDirectory.resolve("_DotNetBootstrapAssertions.kt").writeText(assertions, Charsets.UTF_8)
    outputDirectory.resolve("_DotNetBootstrapAssertionExpect.kt").writeText(
        buildProjectedSource(
            packageName = "kotlin.test",
            declarations = listOf(
                extractCommonDeclaration(
                    commonAssertions,
                    "internal expect fun AssertionErrorWithCause(message: String?, cause: Throwable?): AssertionError",
                ),
            ),
            generatorName = "GenerateDotNetBootstrapKotlinTest.kt",
        ),
        Charsets.UTF_8,
    )
    outputDirectory.resolve("_DotNetBootstrapDefaultAsserter.kt").writeText(
        projectWholeCommonFile(commonDefaultAsserter, "GenerateDotNetBootstrapKotlinTest.kt"),
        Charsets.UTF_8,
    )
}
