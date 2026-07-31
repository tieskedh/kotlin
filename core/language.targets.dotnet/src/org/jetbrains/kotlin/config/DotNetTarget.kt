/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.config

import org.jetbrains.kotlin.platform.TargetPlatformVersion

/** Target-framework/API profile, independent of product kind, runtime identifier, and packaging. */
enum class DotNetTarget(
    override val description: String,
) : TargetPlatformVersion {
    NET48("net48"),
    NETSTANDARD_2_0("netstandard2.0"),
    NET10_0("net10.0");

    override fun toString(): String = description

    companion object {
        @JvmField
        val DEFAULT: DotNetTarget = NET48

        @JvmStatic
        fun fromString(value: String): DotNetTarget? = entries.firstOrNull { it.description == value }
    }
}

/** True only where the selected CLR/API contract admits by-ref-like generic arguments. */
val DotNetTarget.supportsByRefLikeGenericArguments: Boolean
    get() = this == DotNetTarget.NET10_0
