/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.konan.test.dump

import org.jetbrains.kotlin.konan.test.blackbox.support.compilation.TestCompilationArtifact.KLIB
import org.jetbrains.kotlin.konan.test.blackbox.support.settings.KotlinNativeClassLoader
import org.jetbrains.kotlin.konan.test.blackbox.support.util.dumpIr
import org.jetbrains.kotlin.konan.test.blackbox.testRunSettings
import org.jetbrains.kotlin.library.KotlinIrSignatureVersion
import org.jetbrains.kotlin.test.Constructor
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.services.TestServices
import org.jetbrains.kotlin.test.services.sourceFileProvider
import java.io.File

abstract class AbstractNativeKlibDumpIrTest : AbstractKlibToolDumpTest() {
    override fun getDumpHandlers(): List<Constructor<AbstractKlibToolDumpHandler>> = listOf(::KlibToolIrDumpHandler)
}

private class KlibToolIrDumpHandler(testServices: TestServices) : AbstractKlibToolDumpHandler(testServices, suffix = "ir") {
    override val signatureVersion: KotlinIrSignatureVersion?
        get() = null // TODO: test for all signature versions, KT-62828

    override fun makeDump(klib: File, module: TestModule): String {
        val sourceDir = testServices.sourceFileProvider.getKotlinSourceDirectoryForModule(module).absolutePath
        val absolutePathPrefix = if (sourceDir.endsWith(File.separatorChar)) sourceDir else sourceDir + File.separatorChar

        return KLIB(klib).dumpIr(
            kotlinNativeClassLoader = testServices.testRunSettings.get<KotlinNativeClassLoader>().classLoader,
            absolutePathPrefixes = listOf(absolutePathPrefix),
        )
    }
}
