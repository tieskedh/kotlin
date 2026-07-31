/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.cli.dotnet.config

import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.config.ContentRoot
import org.jetbrains.kotlin.config.CompilerConfiguration
import java.io.File

/** A physical Kotlin-library or managed-assembly input to the .NET compiler pipeline. */
data class DotNetClasspathRoot(val file: File) : ContentRoot

fun CompilerConfiguration.addDotNetClasspathRoot(file: File) {
    add(CLIConfigurationKeys.CONTENT_ROOTS, DotNetClasspathRoot(file))
}

val CompilerConfiguration.dotNetClasspathRoots: List<File>
    get() = getList(CLIConfigurationKeys.CONTENT_ROOTS)
        .filterIsInstance<DotNetClasspathRoot>()
        .map(DotNetClasspathRoot::file)
