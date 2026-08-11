/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.config.keys.generator

import org.jetbrains.kotlin.config.DotNetTarget
import org.jetbrains.kotlin.config.keys.generator.model.KeysContainer
import java.io.File

@Suppress("unused")
object DotNetConfigurationKeysContainer :
    KeysContainer("org.jetbrains.kotlin.config", "DotNetConfigurationKeys") {
    val OUTPUT by key<File>(
        comment = "Output .NET IL file or library directory.",
        accessorName = "dotNetOutput",
        throwOnNull = false,
    )
    val ASSEMBLY_NAME by key<String>(
        comment = "Output .NET assembly name.",
        accessorName = "dotNetAssemblyName",
        throwOnNull = false,
    )
    val PRODUCE_STDLIB by key<Boolean>(
        comment = "Produce the bootstrap Kotlin/.NET standard-library assembly.",
        accessorName = "dotNetProducesStdlib",
    )
    val PRODUCE_LIBRARY by key<Boolean>(
        comment = "Produce a Kotlin/.NET library assembly.",
        accessorName = "dotNetProducesLibrary",
    )
    val MEMBER_REFLECTION by key<Boolean>(
        comment = "Emit executable producer metadata for optional Kotlin class-member reflection.",
        accessorName = "dotNetMemberReflection",
    )
    val TARGET by key<DotNetTarget>(
        comment = "Target .NET API/runtime profile.",
        defaultValue = "DotNetTarget.DEFAULT",
        accessorName = "dotNetTarget",
    )
}
