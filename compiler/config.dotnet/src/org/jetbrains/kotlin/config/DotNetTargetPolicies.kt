/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.config

/** Product-policy fact: netstandard2.0 is a library contract rather than an execution target. */
val DotNetTarget.supportsExecutables: Boolean
    get() = this != DotNetTarget.NETSTANDARD_2_0

/** Library-linking compatibility for the current target-framework matrix. */
fun DotNetTarget.canConsumeLibrary(targetFramework: String): Boolean = when (this) {
    DotNetTarget.NET48 ->
        targetFramework == DotNetTarget.NET48.description ||
                targetFramework == DotNetTarget.NETSTANDARD_2_0.description

    DotNetTarget.NETSTANDARD_2_0 ->
        targetFramework == DotNetTarget.NETSTANDARD_2_0.description

    DotNetTarget.NET10_0 ->
        targetFramework == DotNetTarget.NET10_0.description ||
                targetFramework == DotNetTarget.NETSTANDARD_2_0.description
}
