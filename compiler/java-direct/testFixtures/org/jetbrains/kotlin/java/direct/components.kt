/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.java.direct

import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.JVMConfigurationKeys
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.services.*

/**
 * Enables `java-direct` for `JavaUsingAst*` tests by setting the
 * `JvmAnalysisFlags.useJavaDirect` analysis flag. `JvmFrontendPipelinePhase` consults this flag
 * to decide whether to wire `createJavaDirectSourceJavaFacadeBuilder` into the FIR session.
 *
 * Other tests using the same CLI test pipeline (Lombok, plain JVM black-box) leave the flag
 * unset → PSI-backed `FirJavaFacade`.
 */
internal class JavaDirectConfigurator(testServices: TestServices) : EnvironmentConfigurator(testServices) {
    override fun configureCompilerConfiguration(
        configuration: CompilerConfiguration,
        module: TestModule,
    ) {
        super.configureCompilerConfiguration(configuration, module)

        configuration.put(JVMConfigurationKeys.USE_JAVA_DIRECT, true)
    }
}

private val javaFileRegex = Regex("""^\s*//\s* FILE:\s* .*\.java\s*$""")

class OnlyTestsWithJavaSourcesMetaConfigurator(testServices: TestServices) : MetaTestConfigurator(testServices) {
    override fun shouldSkipTest(): Boolean =
        testServices.moduleStructure.modules.any { module -> module.files.any { it.isJavaFile } }
}

