/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:Suppress("IncorrectFormatting", "unused")

package org.jetbrains.kotlin.config

/*
 * This file was generated automatically
 * DO NOT MODIFY IT MANUALLY
 */

import java.io.File

object DotNetConfigurationKeys {
    // Output .NET IL file or library directory.
    @JvmField
    val OUTPUT = CompilerConfigurationKey.create<File>("OUTPUT")

    // Output .NET assembly name.
    @JvmField
    val ASSEMBLY_NAME = CompilerConfigurationKey.create<String>("ASSEMBLY_NAME")

    // Produce the bootstrap Kotlin/.NET standard-library assembly.
    @JvmField
    val PRODUCE_STDLIB = CompilerConfigurationKey.create<Boolean>("PRODUCE_STDLIB")

    // Produce a Kotlin/.NET library assembly.
    @JvmField
    val PRODUCE_LIBRARY = CompilerConfigurationKey.create<Boolean>("PRODUCE_LIBRARY")

    // Target .NET API/runtime profile.
    @JvmField
    val TARGET = CompilerConfigurationKey.create<DotNetTarget>("TARGET")

}

var CompilerConfiguration.dotNetOutput: File?
    get() = get(DotNetConfigurationKeys.OUTPUT)
    set(value) { putIfNotNull(DotNetConfigurationKeys.OUTPUT, value) }

var CompilerConfiguration.dotNetAssemblyName: String?
    get() = get(DotNetConfigurationKeys.ASSEMBLY_NAME)
    set(value) { putIfNotNull(DotNetConfigurationKeys.ASSEMBLY_NAME, value) }

var CompilerConfiguration.dotNetProducesStdlib: Boolean
    get() = getBoolean(DotNetConfigurationKeys.PRODUCE_STDLIB)
    set(value) { put(DotNetConfigurationKeys.PRODUCE_STDLIB, value) }

var CompilerConfiguration.dotNetProducesLibrary: Boolean
    get() = getBoolean(DotNetConfigurationKeys.PRODUCE_LIBRARY)
    set(value) { put(DotNetConfigurationKeys.PRODUCE_LIBRARY, value) }

var CompilerConfiguration.dotNetTarget: DotNetTarget
    get() = get(DotNetConfigurationKeys.TARGET, DotNetTarget.DEFAULT)
    set(value) { put(DotNetConfigurationKeys.TARGET, value) }

