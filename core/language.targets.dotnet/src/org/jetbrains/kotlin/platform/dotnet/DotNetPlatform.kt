/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.platform.dotnet

import org.jetbrains.kotlin.platform.SimplePlatform
import org.jetbrains.kotlin.platform.TargetPlatform
import org.jetbrains.kotlin.platform.toTargetPlatform

abstract class DotNetPlatform : SimplePlatform("DotNet")

object DotNetPlatforms {
    object DefaultSimpleDotNetPlatform : DotNetPlatform() {
        override val targetName: String
            get() = "dotnet"

        override val oldFashionedDescription: String
            get() = "Kotlin/.NET"
    }

    val defaultDotNetPlatform: TargetPlatform
        get() = DefaultSimpleDotNetPlatform.toTargetPlatform()

    val allDotNetPlatforms: List<TargetPlatform>
        get() = listOf(defaultDotNetPlatform)
}

fun TargetPlatform?.isDotNet(): Boolean = this?.singleOrNull() is DotNetPlatform
